package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The integration entities against the real schema (step 2.1a): inserts and updates through JPA write only the columns
 * app_user is granted, database defaults come back, and the tenant comes from the context.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IntegrationEntitiesTests {

    private final IntegrationRepository integrations;
    private final WhatsAppAccountRepository whatsAppAccounts;
    private final WebhookEventRepository webhookEvents;
    private final TransactionTemplate transactions;
    private final TestData data;

    IntegrationEntitiesTests(
            @Autowired IntegrationRepository integrations,
            @Autowired WhatsAppAccountRepository whatsAppAccounts,
            @Autowired WebhookEventRepository webhookEvents,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.integrations = integrations;
        this.whatsAppAccounts = whatsAppAccounts;
        this.webhookEvents = webhookEvents;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
    }

    @Test
    void anIntegrationIsSavedAndRecordsAnErrorCode() {
        TestData.Member owner = data.member("admin");
        UUID credential = data.credential(owner.tenantId());
        Instant failedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UUID id = inTenant(
                owner,
                () -> integrations
                        .save(new Integration("whatsapp", "connected", credential))
                        .getId());
        inTenant(owner, () -> {
            integrations.findById(id).orElseThrow().recordError("token_expired", failedAt);
            return null;
        });
        Integration saved = inTenant(owner, () -> integrations.findById(id).orElseThrow());

        assertThat(saved.getTenantId()).isEqualTo(owner.tenantId());
        assertThat(saved.getCredentialId()).isEqualTo(credential);
        assertThat(saved.getStatus()).isEqualTo("error");
        assertThat(saved.getLastErrorCode()).isEqualTo("token_expired");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void aWhatsAppAccountIsSavedAndRecordsItsQuality() {
        TestData.Member owner = data.member("admin");
        String phoneNumberId = "pn-" + UUID.randomUUID();

        UUID id = inTenant(
                owner,
                () -> whatsAppAccounts
                        .save(new WhatsAppAccount("waba-" + UUID.randomUUID(), phoneNumberId))
                        .getId());
        inTenant(owner, () -> {
            whatsAppAccounts.findById(id).orElseThrow().recordQuality("GREEN", "TIER_1K");
            return null;
        });
        WhatsAppAccount saved =
                inTenant(owner, () -> whatsAppAccounts.findById(id).orElseThrow());

        assertThat(saved.getTenantId()).isEqualTo(owner.tenantId());
        assertThat(saved.getPhoneNumberId()).isEqualTo(phoneNumberId);
        assertThat(saved.getQualityRating()).isEqualTo("GREEN");
    }

    @Test
    void aWebhookEventIsStampedByTheDatabaseAndChangesOnlyItsProcessingColumns() {
        TestData.Member owner = data.member("admin");
        String event = "wamid." + UUID.randomUUID();
        Instant processedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UUID id = inTenant(
                owner,
                () -> webhookEvents
                        .save(new WebhookEvent("whatsapp", event, "{\"entry\": []}"))
                        .getId());
        inTenant(owner, () -> {
            WebhookEvent stored = webhookEvents.findById(id).orElseThrow();
            stored.markProcessed(processedAt);
            stored.recordError("unsupported_type");
            return null;
        });
        WebhookEvent saved = inTenant(owner, () -> webhookEvents.findById(id).orElseThrow());

        assertThat(saved.getTenantId()).isEqualTo(owner.tenantId());
        assertThat(saved.getProviderEventId()).isEqualTo(event);
        assertThat(saved.getReceivedAt()).isNotNull();
        assertThat(saved.getProcessedAt()).isEqualTo(processedAt);
        assertThat(saved.getError()).isEqualTo("unsupported_type");
    }

    @Test
    void anotherTenantsRowsAreNotFound() {
        TestData.Member owner = data.member("admin");
        TestData.Member outsider = data.member("admin");
        UUID id = inTenant(
                owner,
                () -> webhookEvents
                        .save(new WebhookEvent("whatsapp", "wamid." + UUID.randomUUID(), "{}"))
                        .getId());

        assertThat(inTenant(outsider, () -> webhookEvents.findById(id))).isEmpty();
        assertThat(inTenant(outsider, () -> integrations.count())).isZero();
    }

    private <T> T inTenant(TestData.Member member, Supplier<T> work) {
        return TenantScope.call(
                new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                () -> transactions.execute(status -> work.get()));
    }
}
