package com.northstar.fakes;

import com.northstar.integrations.EmailSender;
import com.northstar.integrations.OutgoingEmail;
import com.northstar.workflow.ActionOutcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake email sender that behaves like Resend (step 1.12): it honours idempotency keys (a repeated key sends nothing
 * new, until {@link FakeProvider#expireIdempotencyKey} models the 24-hour window lapsing), and refuses a repeated key
 * with a different body as a permanent idempotency_conflict, as Resend's 409 invalid_idempotent_request does. Emails it
 * "sends" land in {@link #outbox()}, where tests read the invite link. Scripted failures go through {@link #provider()}.
 */
public final class FakeEmailSender implements EmailSender {

    /** One email the fake actually sent. */
    public record Sent(String idempotencyKey, String to, String subject, String text) {}

    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

    private final FakeProvider provider;
    private final List<Sent> outbox = new CopyOnWriteArrayList<>();
    private final Map<String, OutgoingEmail> firstBodies = new ConcurrentHashMap<>();

    public FakeEmailSender(FakeClock clock) {
        this.provider = new FakeProvider(clock, true);
    }

    public FakeProvider provider() {
        return provider;
    }

    public List<Sent> outbox() {
        return List.copyOf(outbox);
    }

    public List<Sent> sentTo(String address) {
        return outbox.stream()
                .filter(sent -> sent.to().equalsIgnoreCase(address))
                .toList();
    }

    /** The token in the link of the latest email to this address, if any. */
    public Optional<String> latestTokenFor(String address) {
        List<Sent> sent = sentTo(address);
        if (sent.isEmpty()) return Optional.empty();
        Matcher token = TOKEN.matcher(sent.getLast().text());
        return token.find() ? Optional.of(token.group(1)) : Optional.empty();
    }

    /** Resend forgets the key and its body after 24 hours: a repeat is then sent again. */
    public void expireIdempotencyKey(String idempotencyKey) {
        firstBodies.remove(idempotencyKey);
        provider.expireIdempotencyKey(idempotencyKey);
    }

    @Override
    public ActionOutcome send(OutgoingEmail email, String idempotencyKey) {
        OutgoingEmail first = firstBodies.putIfAbsent(idempotencyKey, email);
        if (first != null && !sameContent(first, email)) return ActionOutcome.permanent("idempotency_conflict");
        return provider.call(
                idempotencyKey, () -> outbox.add(new Sent(idempotencyKey, email.to(), email.subject(), email.text())));
    }

    private static boolean sameContent(OutgoingEmail a, OutgoingEmail b) {
        return a.to().equals(b.to())
                && a.subject().equals(b.subject())
                && a.text().equals(b.text());
    }
}
