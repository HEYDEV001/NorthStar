package com.northstar.integrations.internal;

import com.northstar.contacts.MessageTemplates;
import com.northstar.jobs.JobTenantException;
import com.northstar.jobs.TenantJobHandler;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.TenantDirectory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job for a stored WhatsApp webhook event (steps 2.4b, 2.7, 2.8a and 2.8b: a message, a delivery status, a
 * messaging-limit change or a template change), run by the worker as the system actor in the event's tenant.
 * Queued once per stored row by step 2.2, and again by the webhook sweep ({@link TenantWebhookSweeper}) while the row
 * is unprocessed past the grace period.
 *
 * <p>{@link WhatsAppInboundProcessor} does the work in one transaction. If it fails, everything it wrote is rolled
 * back, and a second transaction records a retryable code ({@link WebhookFailures}) with processed_at left null, so the
 * sweep retries it; the job itself then ends normally, as the retry is the sweep's. A missing tenant context is never
 * recorded: it fails the job loudly (P10). So does a job naming an event its tenant does not have (P9).
 *
 * <p>Running it twice changes nothing: the event row's lock serialises runs, and a processed row is left alone (P1).
 * Logs carry webhook event IDs, row IDs, fixed codes and exception classes only: never message text, profile names,
 * phone numbers, wamids, phone number IDs or WABA IDs, and never an exception's message.
 */
@Component
public class ProcessWhatsAppWebhookHandler extends TenantJobHandler<ProcessWhatsAppWebhook> {

    private static final Logger log = LoggerFactory.getLogger(ProcessWhatsAppWebhookHandler.class);

    private final WhatsAppInboundProcessor processor;
    private final WebhookEventProcessing rows;
    private final TransactionTemplate transactions;

    ProcessWhatsAppWebhookHandler(
            TenantDirectory tenants,
            WhatsAppInboundProcessor processor,
            WebhookEventProcessing rows,
            PlatformTransactionManager transactionManager) {
        super(tenants);
        this.processor = processor;
        this.rows = rows;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    protected void runForTenant(ProcessWhatsAppWebhook request) {
        UUID id = request.webhookEventId();
        WhatsAppInboundProcessor.Outcome outcome;
        try {
            outcome = transactions.execute(status -> processor.process(id));
        } catch (RuntimeException e) {
            if (isTenantContextFailure(e)) throw e;
            String code = WebhookFailures.retryableCode(e);
            log.warn(
                    "webhook.failed provider=whatsapp webhook_event_id={} error={} final=false exception={}",
                    id,
                    code,
                    e.getClass().getName());
            transactions.executeWithoutResult(status -> rows.recordRetryableFailure(id, code));
            return;
        }

        switch (outcome) {
            case WhatsAppInboundProcessor.Missing missing -> {
                // The job exists only if its row does, in the same tenant; anything else is a bug, and it fails loudly.
                log.error("webhook.event_missing webhook_event_id={}", id);
                throw new IllegalStateException("Webhook event not found in the job's tenant");
            }
            case WhatsAppInboundProcessor.AlreadyProcessed done ->
                log.info("webhook.already_processed provider=whatsapp webhook_event_id={}", id);
            case WhatsAppInboundProcessor.StatusProcessed processed -> logStatus(id, processed.outcome());
            case WhatsAppInboundProcessor.AccountUpdateProcessed processed -> logAccountUpdate(id, processed.outcome());
            case WhatsAppInboundProcessor.TemplateUpdateProcessed processed ->
                logTemplateUpdate(id, processed.outcome());
            case WhatsAppInboundProcessor.Rejected rejected ->
                log.error(
                        "webhook.failed provider=whatsapp webhook_event_id={} error={} final=true",
                        id,
                        rejected.errorCode());
            case WhatsAppInboundProcessor.DuplicateMessage duplicate ->
                log.info("webhook.duplicate_message provider=whatsapp webhook_event_id={}", id);
            // IDs, codes and counts only: never the text or the keyword it matched (step 2.5).
            case WhatsAppInboundProcessor.OptedOut optedOut ->
                log.info(
                        "webhook.opt_out provider=whatsapp webhook_event_id={} message_id={} contact_id={} lead_id={}"
                                + " lead={} first_opt_out={} cancelled_actions={}",
                        id,
                        optedOut.messageId(),
                        optedOut.contactId(),
                        optedOut.leadId(),
                        optedOut.lead().code(),
                        optedOut.firstOptOut(),
                        optedOut.cancelledActions());
            case WhatsAppInboundProcessor.Stored stored
            when stored.noPublishedWorkflow() ->
                log.warn(
                        "webhook.no_published_workflow provider=whatsapp webhook_event_id={} message_id={}",
                        id,
                        stored.messageId());
            case WhatsAppInboundProcessor.Stored stored ->
                log.info(
                        "webhook.processed provider=whatsapp webhook_event_id={} message_id={} lead_id={} lead={}",
                        id,
                        stored.messageId(),
                        stored.leadId(),
                        stored.lead().code());
        }
    }

    /** A delivery status's outcome (step 2.7): IDs, statuses and fixed codes only, never the wamid or Meta's text. */
    private static void logStatus(UUID id, WhatsAppStatusProcessor.Outcome outcome) {
        switch (outcome) {
            case WhatsAppStatusProcessor.Applied applied
            when applied.errorCode() != null ->
                log.warn(
                        "webhook.status_failed provider=whatsapp webhook_event_id={} message_id={} from={} code={}",
                        id,
                        applied.messageId(),
                        applied.from(),
                        applied.errorCode());
            case WhatsAppStatusProcessor.Applied applied ->
                log.info(
                        "webhook.status_applied provider=whatsapp webhook_event_id={} message_id={} from={} to={}",
                        id,
                        applied.messageId(),
                        applied.from(),
                        applied.to());
            case WhatsAppStatusProcessor.Unchanged unchanged ->
                log.info(
                        "webhook.status_unchanged provider=whatsapp webhook_event_id={} message_id={} status={}",
                        id,
                        unchanged.messageId(),
                        unchanged.status());
            case WhatsAppStatusProcessor.LateDelivered late ->
                log.info(
                        "webhook.status_late_delivered provider=whatsapp webhook_event_id={} message_id={}"
                                + " delivered_at_filled={}",
                        id,
                        late.messageId(),
                        late.filled());
            case WhatsAppStatusProcessor.Ignored ignored ->
                log.info(
                        "webhook.status_ignored provider=whatsapp webhook_event_id={} message_id={} from={} to={}",
                        id,
                        ignored.messageId(),
                        ignored.from(),
                        ignored.to());
            case WhatsAppStatusProcessor.Waiting waiting ->
                log.info(
                        "webhook.status_waiting provider=whatsapp webhook_event_id={} error={}",
                        id,
                        WhatsAppStatusProcessor.MESSAGE_NOT_FOUND_YET);
            case WhatsAppStatusProcessor.Unsupported unsupported ->
                log.info(
                        "webhook.status_unsupported provider=whatsapp webhook_event_id={} error={}",
                        id,
                        WhatsAppStatusProcessor.STATUS_UNSUPPORTED);
            case WhatsAppStatusProcessor.Rejected rejected ->
                log.error(
                        "webhook.failed provider=whatsapp webhook_event_id={} error={} final=true",
                        id,
                        rejected.errorCode());
        }
    }

    /**
     * An account-level change's outcome (step 2.8a): IDs, counts, Northstar's limit value and fixed codes only, never
     * the WABA ID or the payload.
     */
    private static void logAccountUpdate(UUID id, WhatsAppAccountUpdateProcessor.Outcome outcome) {
        switch (outcome) {
            case WhatsAppAccountUpdateProcessor.LimitApplied applied ->
                log.info(
                        "webhook.limit_applied provider=whatsapp webhook_event_id={} limit={} numbers={} changed={}",
                        id,
                        applied.limit().databaseValue(),
                        applied.numbers(),
                        applied.changed());
            case WhatsAppAccountUpdateProcessor.LimitMissing missing ->
                log.info("webhook.limit_missing provider=whatsapp webhook_event_id={}", id);
            case WhatsAppAccountUpdateProcessor.Rejected rejected
            when WhatsAppAccountUpdateProcessor.LIMIT_UNRECOGNISED.equals(rejected.errorCode()) ->
                log.warn(
                        "webhook.limit_unrecognised provider=whatsapp webhook_event_id={} error={}",
                        id,
                        rejected.errorCode());
            case WhatsAppAccountUpdateProcessor.Rejected rejected ->
                log.error(
                        "webhook.failed provider=whatsapp webhook_event_id={} error={} final=true",
                        id,
                        rejected.errorCode());
        }
    }

    /**
     * A template change's outcome (step 2.8b): Northstar's template and action IDs, statuses, Meta's categories and fixed
     * codes only; never the template's name, text, Meta's template ID or the WABA ID. A template newly unable to send
     * (rejected, paused, disabled, deleted or archived) is a WARN, the admin's alert until Phase 8.
     */
    private static void logTemplateUpdate(UUID id, WhatsAppTemplateUpdateProcessor.Outcome outcome) {
        switch (outcome) {
            case WhatsAppTemplateUpdateProcessor.StatusApplied status -> {
                MessageTemplates.Applied applied = status.applied();
                if (applied.outcome() == MessageTemplates.Outcome.STALE) {
                    log.info(
                            "webhook.template_stale provider=whatsapp webhook_event_id={} template_id={}",
                            id,
                            applied.templateId());
                } else if (applied.statusChanged()) {
                    TemplateLogs.statusChanged(
                            applied.templateId(),
                            applied.previousStatus(),
                            applied.status(),
                            "webhook",
                            status.reason());
                } else {
                    log.info(
                            "webhook.template_status_unchanged provider=whatsapp webhook_event_id={} template_id={}"
                                    + " status={}",
                            id,
                            applied.templateId(),
                            applied.status().code());
                }
            }
            case WhatsAppTemplateUpdateProcessor.Noted noted
            when "flagged".equals(noted.event()) ->
                log.warn(
                        "webhook.template_flagged provider=whatsapp webhook_event_id={} template_id={}",
                        id,
                        noted.templateId());
            case WhatsAppTemplateUpdateProcessor.Noted noted ->
                log.info(
                        "webhook.template_noted provider=whatsapp webhook_event_id={} template_id={} event={}",
                        id,
                        noted.templateId(),
                        noted.event());
            case WhatsAppTemplateUpdateProcessor.SyncRequested requested
            when "unarchived".equals(requested.code()) ->
                log.info(
                        "webhook.template_unarchived provider=whatsapp webhook_event_id={} template_id={}"
                                + " sync_action_id={}",
                        id,
                        requested.templateId(),
                        requested.syncActionId());
            case WhatsAppTemplateUpdateProcessor.SyncRequested requested ->
                log.warn(
                        "webhook.{} provider=whatsapp webhook_event_id={} template_id={} error={} sync_action_id={}",
                        requested.code(),
                        id,
                        requested.templateId(),
                        requested.code(),
                        requested.syncActionId());
            case WhatsAppTemplateUpdateProcessor.CategoryApplied category -> {
                MessageTemplates.Applied applied = category.applied();
                if (applied.outcome() == MessageTemplates.Outcome.STALE) {
                    log.info(
                            "webhook.template_stale provider=whatsapp webhook_event_id={} template_id={}",
                            id,
                            applied.templateId());
                } else if (applied.outcome() == MessageTemplates.Outcome.UPDATED) {
                    TemplateLogs.categoryChanged(
                            applied.templateId(), applied.previousCategory(), applied.category(), "webhook");
                } else {
                    log.info(
                            "webhook.template_category_unchanged provider=whatsapp webhook_event_id={} template_id={}"
                                    + " category={}",
                            id,
                            applied.templateId(),
                            applied.category());
                }
            }
            case WhatsAppTemplateUpdateProcessor.CategoryNotice notice ->
                log.info(
                        "whatsapp.template.category_change_notice template_id={} current={} upcoming={}"
                                + " webhook_event_id={}",
                        notice.templateId(),
                        notice.current(),
                        notice.upcoming(),
                        id);
            case WhatsAppTemplateUpdateProcessor.Rejected rejected
            when WhatsAppTemplateUpdateProcessor.CATEGORY_UNRECOGNISED.equals(rejected.errorCode()) ->
                log.warn(
                        "webhook.template_category_unrecognised provider=whatsapp webhook_event_id={} error={}",
                        id,
                        rejected.errorCode());
            case WhatsAppTemplateUpdateProcessor.Rejected rejected ->
                log.error(
                        "webhook.failed provider=whatsapp webhook_event_id={} error={} final=true",
                        id,
                        rejected.errorCode());
        }
    }

    /** A missing or wrong tenant context anywhere in the cause chain: never recorded as a webhook failure. */
    private static boolean isTenantContextFailure(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof MissingTenantContextException || cause instanceof JobTenantException) return true;
        }
        return false;
    }
}
