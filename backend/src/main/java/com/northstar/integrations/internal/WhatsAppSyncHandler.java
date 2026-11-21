package com.northstar.integrations.internal;

import com.northstar.integrations.WhatsAppSync;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRecipient;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.FailureClass;
import com.northstar.workflow.RetryPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Syncs one business's WhatsApp account from Meta (step 2.8a; integration-specs.md section 2, Quality and limits), as
 * the worker's system actor in the business's tenant:
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
 *   <li>stops at the first failed read with its class (retried, permanent, or an auth pause that also sets the
 *       integration to error), and records the integration's health after each call, as sends do.
 * </ol>
 *
 * <p>Reading changes nothing at Meta and recording the same values again changes nothing here, so running a sync twice,
 * or two syncs at once, is safe (P1). Logs carry action and account IDs, attempts, Northstar's values, classes and fixed
 * codes only: never the token, phone number IDs, WABA IDs or Meta's text.
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

    static final String QUALITY_ABSENT = "quality_rating_absent";
    static final String QUALITY_UNRECOGNISED = "quality_rating_unrecognised";
    static final String LIMIT_ABSENT = "messaging_limit_absent";
    static final String LIMIT_UNRECOGNISED = "messaging_limit_unrecognised";

    private final WhatsAppConnection connection;
    private final WhatsAppAccountHealth accounts;
    private final ObjectProvider<CredentialStore> credentials;
    private final WhatsAppAccountReader reader;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    /** As for sends, the credential store exists only on the worker, and only the worker runs this handler. */
    WhatsAppSyncHandler(
            WhatsAppConnection connection,
            WhatsAppAccountHealth accounts,
            ObjectProvider<CredentialStore> credentials,
            WhatsAppAccountReader reader,
            PlatformTransactionManager transactionManager) {
        this.connection = connection;
        this.accounts = accounts;
        this.credentials = credentials;
        this.reader = reader;
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
        Prepared prepared = readOnly.execute(status -> new Prepared(connection.current(), accounts.numbers()));
        if (prepared.connection().isEmpty() || !prepared.connection().get().usable()) {
            return paused(request, WhatsAppSendHandler.INTEGRATION_NOT_CONNECTED);
        }
        if (prepared.numbers().isEmpty()) return done(request, 0, 0);

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
        try (Secret secret = token.get()) {
            for (WhatsAppAccountHealth.Number number : prepared.numbers()) {
                WhatsAppAccountReader.NumberRead read =
                        reader.readNumber(number.accountId(), number.phoneNumberId(), secret);
                switch (read) {
                    case WhatsAppAccountReader.Failed failed -> {
                        return failed(request, number.accountId(), failed.failure());
                    }
                    case WhatsAppAccountReader.NumberHealth health -> {
                        if (record(request, number.accountId(), health)) changed++;
                    }
                }
            }
        }
        transactions.executeWithoutResult(status -> connection.recordSuccess());
        return done(request, prepared.numbers().size(), changed);
    }

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

    private ActionOutcome failed(ActionRequest request, UUID accountId, ActionOutcome.Failed failure) {
        boolean auth = failure.failureClass() == FailureClass.AUTH;
        transactions.executeWithoutResult(status -> connection.recordFailure(failure.code(), auth));
        log.warn(
                "whatsapp.sync.failed action_id={} account_id={} attempt={} class={} code={}",
                request.actionId(),
                accountId,
                request.attempt(),
                failure.failureClass().databaseValue(),
                failure.code());
        return failure;
    }

    private static ActionOutcome paused(ActionRequest request, String code) {
        log.warn("whatsapp.sync.paused action_id={} attempt={} code={}", request.actionId(), request.attempt(), code);
        return ActionOutcome.auth(code);
    }

    private static ActionOutcome done(ActionRequest request, int numbers, int changed) {
        log.info(
                "whatsapp.sync.done action_id={} attempt={} numbers={} changed={}",
                request.actionId(),
                request.attempt(),
                numbers,
                changed);
        return ActionOutcome.succeeded("{\"numbers\": " + numbers + ", \"changed\": " + changed + "}");
    }
}
