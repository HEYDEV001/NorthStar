package com.northstar.integrations.internal;

import com.northstar.integrations.WhatsAppSync;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRecipient;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.FailureClass;
import com.northstar.workflow.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Syncs one business's WhatsApp account from Meta (steps 2.8a and 2.8b; integration-specs.md section 2, Quality and
 * limits, and Template status from Meta), as the worker's system actor in the business's tenant:
 *
 * <ol>
 *   <li>reads the integration and the business's numbers. WhatsApp not connected pauses the sync as auth
 *       {@value WhatsAppSendHandler#INTEGRATION_NOT_CONNECTED}, without calling Meta, as for sends;
 *   <li>decrypts the business token through the worker's credential store, then reads each number through the
 *       {@link WhatsAppAccountReader}, outside any transaction, one after another;
 *   <li>records each number's quality rating and messaging limit in its own short transaction. A value Meta did not
 *       send, or one Northstar does not recognise, keeps what is stored, with an INFO or WARN line and a fixed code: in
 *       particular, a limit missing from the answer, or refused by the pinned Graph API version, is
 *       {@value #LIMIT_ABSENT} and never fails the sync. A drop in quality (green to yellow or red, yellow to red) is a
 *       WARN {@code whatsapp.account.quality_dropped}, the admin's alert until Phase 8;
 *   <li>then lists the templates of the numbers' WABAs and applies them ({@link WhatsAppTemplateSync}), within
 *       {@value #TEMPLATE_BUDGET_SECONDS} seconds of the sync's start: a listing cut off by the budget still applies
 *       what it read but deletes nothing;
 *   <li>stops at the first failed read with its class (retried, permanent, or an auth pause that also sets the
 *       integration to error), and records the integration's health after each call, as sends do.
 * </ol>
 *
 * <p>Reading changes nothing at Meta and recording the same values again changes nothing here, so running a sync twice,
 * or two syncs at once, is safe (P1). Logs carry action, account and template IDs, attempts, Northstar's values,
 * classes and fixed codes only: never the token, phone number IDs, WABA IDs, template names or Meta's text.
 */
@Component
class WhatsAppSyncHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSyncHandler.class);

    /** WhatsApp sync (workflow-spec.md section 10): a first try, then retries after 30 s and 5 min (3 attempts). */
    static final RetryPolicy RETRIES = RetryPolicy.of(Duration.ofSeconds(30), Duration.ofMinutes(5));

    /**
     * Under the 5-minute lease. Each read takes at most 20 seconds ({@link WhatsAppCloudApiConfiguration}), and a missing
     * limit field costs one more read, so a business with up to five numbers always finishes in time; a larger one is
     * cut off and retried, keeping what it recorded.
     */
    static final Duration TIMEOUT = Duration.ofMinutes(4);

    /**
     * No template page is requested after this long from the sync's start (step 2.8b): one more page (20 seconds at
     * most) and applying up to Meta's 6,000 templates, one short transaction each, still end well within the timeout.
     */
    static final long TEMPLATE_BUDGET_SECONDS = 150;

    static final String QUALITY_ABSENT = "quality_rating_absent";
    static final String QUALITY_UNRECOGNISED = "quality_rating_unrecognised";
    static final String LIMIT_ABSENT = "messaging_limit_absent";
    static final String LIMIT_UNRECOGNISED = "messaging_limit_unrecognised";

    private final WhatsAppConnection connection;
    private final WhatsAppAccountHealth accounts;
    private final ObjectProvider<CredentialStore> credentials;
    private final WhatsAppAccountReader reader;
    private final WhatsAppTemplateSync templates;
    private final Clock clock;
    private final Duration templateBudget;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    /** As for sends, the credential store exists only on the worker, and only the worker runs this handler. */
    @Autowired
    WhatsAppSyncHandler(
            WhatsAppConnection connection,
            WhatsAppAccountHealth accounts,
            ObjectProvider<CredentialStore> credentials,
            WhatsAppAccountReader reader,
            WhatsAppTemplateSync templates,
            PlatformTransactionManager transactionManager) {
        this(
                connection,
                accounts,
                credentials,
                reader,
                templates,
                transactionManager,
                Clock.systemUTC(),
                Duration.ofSeconds(TEMPLATE_BUDGET_SECONDS));
    }

    /** With the clock the template budget is measured on, and the budget: for tests of a cut-off listing. */
    WhatsAppSyncHandler(
            WhatsAppConnection connection,
            WhatsAppAccountHealth accounts,
            ObjectProvider<CredentialStore> credentials,
            WhatsAppAccountReader reader,
            WhatsAppTemplateSync templates,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration templateBudget) {
        this.connection = connection;
        this.accounts = accounts;
        this.credentials = credentials;
        this.reader = reader;
        this.templates = templates;
        this.clock = clock;
        this.templateBudget = templateBudget;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public String type() {
        return WhatsAppSync.SYNC_TYPE;
    }

    /** A sync messages nobody, so an opt-out never touches it. */
    @Override
    public ActionRecipient recipient() {
        return ActionRecipient.NONE;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RETRIES;
    }

    @Override
    public Duration timeout() {
        return TIMEOUT;
    }

    /** What the sync found before calling Meta. */
    private record Prepared(
            Optional<WhatsAppConnection.Current> connection, List<WhatsAppAccountHealth.Number> numbers) {}

    @Override
    public ActionOutcome execute(ActionRequest request) {
        Instant deadline = clock.instant().plus(templateBudget);
        Prepared prepared = readOnly.execute(status -> new Prepared(connection.current(), accounts.numbers()));
        if (prepared.connection().isEmpty() || !prepared.connection().get().usable()) {
            return paused(request, WhatsAppSendHandler.INTEGRATION_NOT_CONNECTED);
        }
        if (prepared.numbers().isEmpty()) return done(request, 0, 0, null);

        CredentialStore store = credentials.getIfAvailable();
        if (store == null) throw new IllegalStateException("Business tokens are read only on the worker");
        Optional<Secret> token;
        try {
            token = store.read(prepared.connection().get().credentialId());
        } catch (CredentialDecryptionException e) {
            // Its message names the credential and key IDs only; still, only the code is logged.
            transactions.executeWithoutResult(
                    status -> connection.recordFailure(WhatsAppSendHandler.CREDENTIAL_UNREADABLE, true));
            return paused(request, WhatsAppSendHandler.CREDENTIAL_UNREADABLE);
        }
        if (token.isEmpty()) return paused(request, WhatsAppSendHandler.INTEGRATION_NOT_CONNECTED);

        int changed = 0;
        WhatsAppTemplateSync.Applied templatesApplied;
        boolean templatesComplete;
        try (Secret secret = token.get()) {
            for (WhatsAppAccountHealth.Number number : prepared.numbers()) {
                WhatsAppAccountReader.NumberRead read =
                        reader.readNumber(number.accountId(), number.phoneNumberId(), secret);
                switch (read) {
                    case WhatsAppAccountReader.Failed failed -> {
                        return failed(request, "account_id=" + number.accountId(), failed.failure());
                    }
                    case WhatsAppAccountReader.NumberHealth health -> {
                        if (record(request, number.accountId(), health)) changed++;
                    }
                }
            }

            // Meta's time for what the listing says: when it started (integration-specs.md section 2).
            Instant asOf = Instant.now().truncatedTo(ChronoUnit.MICROS);
            List<String> wabaIds = prepared.numbers().stream()
                    .map(WhatsAppAccountHealth.Number::wabaId)
                    .distinct()
                    .toList();
            switch (templates.fetch(wabaIds, secret, clock, deadline)) {
                case WhatsAppTemplateSync.FetchFailed failed -> {
                    return failed(request, "read=templates", failed.failure());
                }
                case WhatsAppTemplateSync.Fetched fetched -> {
                    templatesApplied = templates.apply(fetched, asOf, request.actionId());
                    templatesComplete = fetched.complete();
                }
            }
        }
        transactions.executeWithoutResult(status -> connection.recordSuccess());
        return done(
                request, prepared.numbers().size(), changed, new TemplatesDone(templatesApplied, templatesComplete));
    }

    /** What the template half did, for the result and the summary line. */
    private record TemplatesDone(WhatsAppTemplateSync.Applied applied, boolean complete) {}

    /** Records one number's values in its own transaction; true if anything changed. */
    private boolean record(ActionRequest request, UUID accountId, WhatsAppAccountReader.NumberHealth health) {
        QualityRating quality = null;
        if (health.qualityRating() == null) {
            kept(request, accountId, QUALITY_ABSENT, null);
        } else {
            quality = QualityRating.fromMeta(health.qualityRating()).orElse(null);
            if (quality == null) unrecognised(request, accountId, QUALITY_UNRECOGNISED);
        }
        MessagingLimit limit = null;
        if (health.messagingLimit() == null) {
            kept(request, accountId, LIMIT_ABSENT, health.limitFieldRefused() ? "field_refused" : "not_in_answer");
        } else {
            limit = MessagingLimit.fromMeta(health.messagingLimit()).orElse(null);
            if (limit == null) unrecognised(request, accountId, LIMIT_UNRECOGNISED);
        }

        QualityRating newQuality = quality;
        MessagingLimit newLimit = limit;
        Optional<WhatsAppAccountHealth.Recorded> recorded =
                transactions.execute(status -> accounts.record(accountId, newQuality, newLimit));
        if (recorded == null || recorded.isEmpty()) return false; // numbers are never deleted; nothing to record
        WhatsAppAccountHealth.Recorded values = recorded.get();
        log.info(
                "whatsapp.sync.number_recorded action_id={} account_id={} quality={} limit={} changed={}",
                request.actionId(),
                accountId,
                values.quality().map(QualityRating::databaseValue).orElse("none"),
                values.limit().map(MessagingLimit::databaseValue).orElse("none"),
                values.changed());
        if (values.qualityDropped()) {
            // The admin's alert until Phase 8.
            log.warn(
                    "whatsapp.account.quality_dropped account_id={} from={} to={}",
                    accountId,
                    values.previousQuality().orElseThrow().databaseValue(),
                    values.quality().orElseThrow().databaseValue());
        }
        return values.changed();
    }

    private static void kept(ActionRequest request, UUID accountId, String code, String reason) {
        if (reason == null) {
            log.info(
                    "whatsapp.sync.value_kept action_id={} account_id={} code={}", request.actionId(), accountId, code);
        } else {
            log.info(
                    "whatsapp.sync.value_kept action_id={} account_id={} code={} reason={}",
                    request.actionId(),
                    accountId,
                    code,
                    reason);
        }
    }

    /** Never the value itself: Meta's text is not logged. */
    private static void unrecognised(ActionRequest request, UUID accountId, String code) {
        log.warn("whatsapp.sync.value_kept action_id={} account_id={} code={}", request.actionId(), accountId, code);
    }

    /** what: {@code account_id=<id>} for a number, {@code read=templates} for the listing. */
    private ActionOutcome failed(ActionRequest request, String what, ActionOutcome.Failed failure) {
        boolean auth = failure.failureClass() == FailureClass.AUTH;
        transactions.executeWithoutResult(status -> connection.recordFailure(failure.code(), auth));
        log.warn(
                "whatsapp.sync.failed action_id={} {} attempt={} class={} code={}",
                request.actionId(),
                what,
                request.attempt(),
                failure.failureClass().databaseValue(),
                failure.code());
        return failure;
    }

    private static ActionOutcome paused(ActionRequest request, String code) {
        log.warn("whatsapp.sync.paused action_id={} attempt={} code={}", request.actionId(), request.attempt(), code);
        return ActionOutcome.auth(code);
    }

    /** templates null: no numbers, so no WABA to list. */
    private static ActionOutcome done(ActionRequest request, int numbers, int changed, TemplatesDone templates) {
        int listed = templates == null ? 0 : templates.applied().templates();
        int templatesChanged = templates == null ? 0 : templates.applied().changed();
        int deleted = templates == null ? 0 : templates.applied().deleted();
        boolean complete = templates == null || templates.complete();
        log.info(
                "whatsapp.sync.done action_id={} attempt={} numbers={} changed={} templates={} templates_changed={}"
                        + " templates_deleted={} templates_complete={}",
                request.actionId(),
                request.attempt(),
                numbers,
                changed,
                listed,
                templatesChanged,
                deleted,
                complete);
        return ActionOutcome.succeeded("{\"numbers\": " + numbers + ", \"changed\": " + changed
                + ", \"templates\": " + listed + ", \"templates_changed\": " + templatesChanged
                + ", \"templates_deleted\": " + deleted + ", \"templates_complete\": " + complete + "}");
    }
}
