package com.northstar.integrations.internal;

import com.northstar.fakes.FakeClock;
import com.northstar.fakes.FakeProvider;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionOutcome;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only (step 2.6a): a WhatsApp Cloud API that behaves as Meta's does where it matters here. It honours no
 * idempotency key (Meta's send API takes none), so a call performed twice sends twice, and "sent once" is proven by
 * Northstar. Scripted calls go through {@link #provider()}, keyed by {@link #key}: duplicates (a performed call whose
 * answer is lost), delays, client timeouts, hangs, 429s, 5xx, rejections and auth failures, from the 1.11 kit.
 *
 * <p>Every message it "sends" lands in {@link #sent()}, with the business number and the token it was sent with, so
 * tests can see that each tenant's send used its own (P9). It lives beside the handler because the adapter interface
 * and {@link Secret} are package-private.
 */
final class FakeWhatsAppSender implements WhatsAppSender {

    /** One message the fake actually sent. */
    record Sent(
            UUID messageId,
            UUID tenantId,
            String phoneNumberId,
            String to,
            String token,
            Body body,
            String providerMessageId) {}

    private final FakeProvider provider;
    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    FakeWhatsAppSender(FakeClock clock) {
        this.provider = new FakeProvider(clock, false);
    }

    /** The provider's script key for a message. */
    static String key(UUID messageId) {
        return "whatsapp:" + messageId;
    }

    FakeProvider provider() {
        return provider;
    }

    List<Sent> sent() {
        return List.copyOf(sent);
    }

    List<Sent> sent(UUID messageId) {
        return sent.stream().filter(s -> s.messageId().equals(messageId)).toList();
    }

    @Override
    public WhatsAppSendResult send(OutgoingWhatsApp message, Secret businessToken) {
        String token = businessToken.utf8();
        UUID tenant = TenantScope.current().map(TenantContext::tenantId).orElse(null);
        String[] providerMessageId = new String[1];
        ActionOutcome outcome = provider.call(key(message.messageId()), () -> {
            providerMessageId[0] = "wamid.FAKE" + UUID.randomUUID().toString().replace("-", "");
            sent.add(new Sent(
                    message.messageId(),
                    tenant,
                    message.phoneNumberId(),
                    message.to(),
                    token,
                    message.body(),
                    providerMessageId[0]));
        });
        return switch (outcome) {
            case ActionOutcome.Succeeded succeeded -> new Accepted(providerMessageId[0]);
            case ActionOutcome.Failed failed -> new Rejected(failed);
        };
    }
}
