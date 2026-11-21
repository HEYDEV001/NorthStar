package com.northstar.integrations.internal;

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
 * The job for a stored WhatsApp webhook event (step 2.4b), run by the worker as the system actor in the event's tenant.
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
 * phone numbers, wamids or phone number IDs, and never an exception's message.
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
            case WhatsAppInboundProcessor.StatusDeferred deferred ->
                log.info("webhook.status_deferred provider=whatsapp webhook_event_id={}", id);
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

    /** A missing or wrong tenant context anywhere in the cause chain: never recorded as a webhook failure. */
    private static boolean isTenantContextFailure(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof MissingTenantContextException || cause instanceof JobTenantException) return true;
        }
        return false;
    }
}
