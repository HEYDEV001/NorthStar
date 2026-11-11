/**
 * The fake-adapter test kit (step 1.11): what every provider's fake builds on. Outbound, a {@link
 * com.northstar.fakes.FakeProvider} plays scripted {@link com.northstar.fakes.FakeCall}s (success, 5xx, client timeout,
 * 429 with Retry-After, rejection, expired credentials, a hang past the handler's timeout, a lost response) and counts
 * side effects per idempotency key. Inbound, a {@link com.northstar.fakes.WebhookScript} builds signed deliveries:
 * duplicated, out of order, late or badly signed. Time comes from a {@link com.northstar.fakes.FakeClock}; nothing
 * sleeps, and nothing reaches the network (tests run with a hosts file that knows only localhost).
 *
 * <p>Test sources only. Header names, signature prefixes and payloads belong to the provider steps that use the kit.
 */
package com.northstar.fakes;
