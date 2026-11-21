# Northstar Integration Specs

Oct 22, 2026 · @Abhishek

## 1. Common adapter pattern

Every external service sits behind one Java interface in the `integrations` module, with a real adapter and a fake adapter for tests; nothing outside that module calls a provider directly.

**Inbound (webhooks)**

1. Endpoint per provider: `POST /webhooks/{provider}`.
2. Verify signature first; invalid → 401, parked in `ingest.unrouted_webhooks` with `signature_valid = false` and metadata only (provider, provider event ID if parseable, body size, time); the payload is not kept (P15).
3. Resolve the tenant (from phone number ID, account ID or reference ID). Unknown → parked in `ingest.unrouted_webhooks` as `unknown_account` with its payload (bodies over 64 KB are kept without it), Northstar admin alerted, 200, nothing processed (P16).
4. In that tenant's scope, insert into `webhook_events` (unique on provider event ID); duplicate → 200 and stop, and the second copy is not stored (P1).
5. Queue a job and return 200 within a few hundred milliseconds.
6. The worker normalises the payload into an engine event (`events` table) and processes it.

Anyone can POST to a webhook URL, so the unrouted write path is rate-limited (step 2.2). Unrouted rows are never processed or copied into a tenant table, and are deleted after 30 days (`data-model.md` section 14).

How step 2.2 builds this (decided 19 Nov 2026):

- **Public, and only there.** The webhook paths live outside `/api/v1` and have their own security chain, which
  matches exactly the listed method and path pairs (`GET` and `POST /webhooks/whatsapp` so far) and needs no token; a
  bearer token sent there is ignored. Every other path keeps the Supabase login.
- **Body limit.** Bodies over `northstar.webhooks.max-body-bytes` (3 MiB, Meta's maximum) get 413 before they are read
  past the limit; nothing is stored or parked.
- **Status codes.** Bad or missing signature: 401. Unknown account: 200 (a non-200 would make the provider retry for
  days against an endpoint every tenant shares). A signed body that is unreadable, or carries nothing storable: 200,
  nothing stored, one ERROR line for an unreadable one. Any database failure: 500 or 503, so the provider retries;
  dedupe makes the retry safe. Not configured on this process: 503 (section 7).
- **Rate limits on the unrouted path, global rather than per IP** (the client address would come from an edge header
  the app must trust, and senders rotate addresses): a token bucket per reason in each API instance (30 invalid
  signatures and 120 unknown accounts a minute), then a cap inside the parking functions across every instance (60 and
  600 a minute). Over a limit nothing is stored, the answer is unchanged, and drops are logged once, then counted once
  a minute.
- **Alert for an unknown account:** for now a WARN line, `webhook.unknown_account ... unrouted_id=<id>`; the Northstar
  admin is notified from Phase 8.
- **Logs:** counts, status codes, webhook event IDs, unrouted row IDs and tenant IDs only; never a body, wamid, phone
  number ID, phone number, signature, secret or challenge.

How step 2.4a keeps stored webhooks from being stranded (decided 20 Nov 2026):

- **The sweep.** Every minute a platform job asks the allowlisted `northstar.tenants_with_unprocessed_webhooks()` which
  tenants have rows still unprocessed 2 minutes (the grace period) after they were received, on the database's clock,
  and queues one sweep per tenant. That sweep reads the tenant's oldest waiting rows (up to 2,000) in its own scope and
  queues each row's processing job again. It covers a job lost between the commit and its creation, a worker that died
  mid-job, a failure waiting for its retry, and rows stored before processing existed. WhatsApp message rows only until
  step 2.7 adds status rows.
- **Retries by age, not by count.** `webhook_events` holds only `processed_at` and `error`, so a row's age decides its
  retry step: 2 min, 10 min, 1 h and 6 h. Each step is queued under its own job ID (JobRunr's retries are off and it
  skips an ID it already has), and only within 10 minutes of reaching it; a step missed while the worker was down for
  longer is skipped.
- **Giving up.** A row whose processing has failed (`error` set) is closed at 24 hours: `processed_at` set, its last
  error code kept, never retried again, and an ERROR line `webhook.failed ... final=true` (the alert, until Phase 8's
  admin view of webhook errors). A row with no failure recorded is never closed without an attempt.
- **Logs:** counts, webhook event IDs and fixed error codes only.

How step 2.4b processes a stored WhatsApp message (step 6; decided 20 Nov 2026):

- **One transaction per webhook event, as the system actor in its tenant.** The event row is locked first, so runs of
  one job wait for each other and a processed row is left alone (P1). Then: the message is read from the stored slice;
  the contact is created if new and locked, so one contact's messages are processed one at a time (P3); its source
  (`direct` until Phase 7); its open lead; its conversation (created on the receiving number with the open lead's
  assignee); `last_inbound_at`, moved forward only; the message; the lead; one `message.received` engine event; and
  `processed_at`. A failure anywhere rolls all of it back.
- **The lead.** A contact's open lead is joined, and linked to the conversation if it had none (W1). Otherwise a new
  lead opens in New on the tenant's one published workflow version, with the conversation's assignee (W2: a contact
  whose lead closed gets a new lead on the same contact and conversation). Reactions and system messages never open a
  lead. With no single published workflow, everything else is stored, no lead opens, and the row is closed as
  `no_published_workflow` with a WARN line.
- **A message already stored** (a replay with `processed_at` cleared) closes the row and writes nothing else, so an old
  message never opens a lead. Status rows are left untouched (`processed_at` and `error` null) for step 2.7.
- **Failures.** Retryable, with `processed_at` null and the sweep retrying: `database_unavailable`,
  `database_conflict`, `database_timeout`, `processing_failed`. Permanent, with `processed_at` set and an ERROR line:
  `payload_invalid` and `contact_phone_invalid` (a sender that is not an E.164 number, P4). A missing tenant context
  fails the job loudly and is never recorded (P10).
- **Logs:** webhook event, message and lead IDs, fixed codes and exception classes only; never message text, profile
  names, phone numbers, wamids or phone number IDs, and never an exception's message.

**Outbound (actions)**

- Every call is an `actions` row executed by the worker with an idempotency key; provider idempotency headers used where offered.
- Provider errors are classified: **retryable** (timeouts, 429, 5xx) → backoff; **permanent** (invalid input, revoked auth) → Failed + alert; **auth** (expired token) → integration status error + pause dependent actions.
- Rate limits respected per tenant and per provider with a token bucket in the worker.

**Health**

- `integrations.status`, `last_success_at`, `last_error` updated on every call.
- Dashboard shows connected / error / reconnect per integration; Northstar admin sees all tenants.

**Logging:** request IDs and status codes only; never message bodies, tokens or card data.

## 2. WhatsApp Cloud API

Northstar operates as a Meta Tech Provider: each business connects its own WhatsApp number through Embedded Signup, and one Northstar app receives every tenant's webhooks.

**Onboarding (per business)**

1. Business runs Embedded Signup from the onboarding wizard; Northstar receives the WABA ID, phone number ID and a business token.
2. Store in `whatsapp_accounts` and `integration_credentials`; subscribe the WABA to Northstar's app webhooks.
3. Create the starter message templates in the business's WABA; track approval status in `message_templates`.
4. Send a test message; readiness check passes when inbound and outbound both work.

**App permissions to request:** `whatsapp_business_management`, `whatsapp_business_messaging`, and `whatsapp_business_manage_events` (needed for the Conversions API, section 5).

**Webhooks**

| Item | Rule |
| --- | --- |
| Endpoint | `GET` and `POST /webhooks/whatsapp`, outside `/api/v1` (step 2.2) |
| Verification | `GET` with `hub.mode`, `hub.verify_token`, `hub.challenge`; echo the challenge when the token matches. Built: `hub.mode` must be `subscribe` (Meta: "always set to subscribe"), the token is compared in constant time, and the challenge (Meta: "an int you must pass back") is echoed as plain text only if it is 1 to 64 digits; anything else is 403 `verification_failed` |
| Signature | `X-Hub-Signature-256`: HMAC-SHA256 of the raw body with the app secret; reject on mismatch. Built: exactly `sha256=` and 64 lowercase hex digits, checked on the exact bytes received before any JSON parsing, in constant time; mismatch, missing or malformed → 401 `invalid_signature`, parked as metadata only (P15). The provider event ID of a bad-signature row stays null: an unverified body is never parsed |
| Routing | `metadata.phone_number_id` → `whatsapp_accounts` → tenant, through the allowlisted `northstar.whatsapp_tenants()` (`data-model.md` section 12) |
| Splitting | One delivery can carry several entries and changes, for several numbers and so several tenants, and Meta batches up to 1000 updates without guaranteeing the grouping. Each element of a change's `messages` or `statuses` is one item, stored as its own `webhook_events` row in its number's tenant (one transaction per tenant), with a Meta-shaped slice as payload: the envelope, the entry ID, one change with its `value` fields (metadata, contacts, messaging_product) and only that item. Items for unknown numbers are parked one by one with only their own slice. U+0000 and unpaired surrogates in the slice become U+FFFD (jsonb cannot hold them) |
| Dedupe | Message ID for messages; message ID + status for status updates. Built as `provider_event_id` = `message:<wamid>` (`messages[].id`) and `status:<wamid>:<status>` (`statuses[].id`, the sent message's wamid, and `statuses[].status`); per item, so a retry batched differently still dedupes (P1). A duplicate is not stored and queues nothing |
| Not stored (until step 2.8) | Changes whose `field` is not `messages` (template status, quality and account updates, keyed by the WABA ID in `entry.id`), value-level `errors` (no item ID), items without a usable ID and changes without a phone number ID: 200, counted as `ignored` in the log. Only the `messages` field is subscribed until 2.8 routes the rest by WABA |
| Queueing | One `ProcessWhatsAppWebhook` job (IDs only) per stored row, created after the tenant's transaction commits; it processes the row (step 2.4b, section 1). Since step 2.4a the webhook sweep queues it again for a message row still unprocessed past the grace period (section 1) |
| Inbound types handled | Stored as (step 2.4b): text → `text`; interactive `button_reply` and `list_reply`, and a template's quick-reply `button` → `interactive`, body the reply's title or text (the button ID stays in the webhook payload for Phase 5); image and document → their type, body the caption; audio and location → their type, no body (media IDs and coordinates stay in the webhook payload, kept 30 days, for step 4.5); video, sticker, contacts, order, reaction, system and any other type → `unsupported`, no body. No media is downloaded yet. Reactions and system messages never open a lead on their own |
| Status updates | sent, delivered, read, failed → `messages.status`, failures → action error (step 2.7; until then the job leaves status rows untouched) |
| Click-to-WhatsApp referral | `referral` object on the first message: `source_id`, `source_type`, `source_url`, `headline`, `ctwa_clid` → `ad_referrals`; `ctwa_clid` stored for Conversions API matching |

**Sending**

- `POST /{phone-number-id}/messages` on the Graph API with the business token.
- Free-form text and media only inside the 24-hour customer-service window (`conversations.window_expires_at`); outside it, an approved template.
- Quote and invoice PDFs sent as `document` messages with a signed URL or uploaded media ID.
- Accept / Request Changes as interactive reply buttons (button IDs carry the quote version ID).
- Opt-out keywords (STOP and tenant-configured words) checked on every inbound message before the AI sees it. Built in step 2.5 (`workflow-spec.md` section 13, Opt-out): a text or interactive reply whose whole normalised text is a keyword opts the contact out, in the inbound job's transaction; every outbound action to an opted-out contact is then refused, and the sender (step 2.6) must create its send actions with the contact as recipient.

**Quality and limits:** store the number's quality rating and messaging limit; alert the admin when quality drops.

## 3. Razorpay

Each business collects into its own Razorpay account; Northstar creates payment links on its behalf and marks payments paid only from Razorpay's signed webhooks, backed by a reconciliation job.

**Connection:** the business connects its account in onboarding (API key pair or Razorpay partner authorisation, open decision); Northstar registers a webhook on that account with a per-tenant webhook secret.

**Creating a payment link**

- Payment Links API with: amount in paise, currency INR, customer name and phone, description (quote number + kind), `reference_id` = Northstar payment ID, `expire_by` = link expiry, notifications off (Northstar sends the link on WhatsApp).
- Store `razorpay_link_id`, link URL and expiry on the `payments` row.

**Webhook events to subscribe**

| Event | Northstar action |
| --- | --- |
| `payment_link.paid` | Payment → paid; engine event `payment.captured`; lead moves on |
| `payment_link.partially_paid` | Payment → partial; remainder link flow (Edge case W19) |
| `payment_link.expired` | Payment → expired; slot released; fresh-link flow |
| `payment_link.cancelled` | Payment → expired (cancelled by staff) |
| `payment.failed` | Record failure reason; fresh link after 1 failure, alert after 2 |
| `refund.processed` | Refund → processed; ledger + credit note finalised |
| `refund.failed` | Refund → failed; owner alerted |

**Validation:** verify the signature header (`X-Razorpay-Signature`, HMAC-SHA256 of the raw body with the tenant's webhook secret) before anything else; dedupe on the Razorpay event ID header and on `razorpay_payment_id`. Confirm header names against Razorpay's webhook validation docs during build.

**Refunds:** created through the Refunds API only after owner approval; amount from the cancellation policy; refund ID stored on `refunds`.

**Reconciliation:** every 15 minutes, a job fetches the status of payment links still pending past their expected time and of refunds still processing, so a delayed or missed webhook never leaves a booking stuck.

**Never:** store card or UPI details, mark paid from a client's screenshot or message, or let the AI change payment status.

## 4. Google: OAuth, Calendar and Sheets

One Google Cloud project serves all tenants; asking for the narrowest scopes keeps Google's app verification simpler and protects staff privacy.

**OAuth**

- Authorization-code flow with offline access; refresh tokens encrypted in `integration_credentials`.
- Business admin connects the business Google account (business calendar + Sheets); each staff member connects their own calendar.
- Scopes, narrowest that works: Calendar events read/write + free/busy for calendars; `drive.file` for Sheets, so Northstar can only touch spreadsheets it created or the user picked. Confirm each scope's sensitivity class in Google's scope list; Calendar scopes need app verification before outside users can connect.
- Revoked or expired refresh token → integration error, dependent actions pause, reconnect prompt.

**Calendar**

| Need | How |
| --- | --- |
| Availability | Free/busy query for the candidate staff calendars and the business calendar over the requested window; returns busy times only, so private event details are never read |
| Cache | `busy_blocks` refreshed on query and by push notifications (watch channels) on each connected calendar, plus a periodic resync |
| Create | On Scheduled: event in business calendar and the assigned staff calendar; IDs saved in `appointments.google_event_ids` |
| Update | Reschedule or reassignment patches the event, or deletes from the old staff calendar and creates in the new one |
| Cancel | Delete events; platform appointment kept as Cancelled |
| Conflicts | A new Google event overlapping a job → conflict flag to admin (Edge case W29) |

**Sheets**

- Northstar creates the spreadsheet (two tabs: Leads, Payments) in the business's Drive during onboarding, or the admin picks one through the Google Picker.
- Header row written by Northstar; first column `platform_record_id`, last columns `updated_at` and `sync_status`.
- Writes come from `sync_outbox` in batches per tenant (values batch update / append); rows located by `platform_record_id`, never by row number.
- Quotas are per project and shared by all tenants: batch writes, back off on 429, and request a quota increase before about 100 tenants.
- Inbound edits: only columns listed in `sheet_mappings.inbound_fields`, read on a schedule; compared against `updated_at`; protected-field edits are reverted and logged to `sheet_conflicts`.
- Never written to sheets: message transcripts, tokens, card or bank data.

## 5. Meta Conversions API and Marketing API

Lead stages go back to Meta through the Conversions API for business messaging, matched on the click ID from each click-to-WhatsApp chat; Meta does not deduplicate these events, so Northstar must send each one exactly once.

**Setup (per business)**

- Requires app permissions `whatsapp_business_management` and `whatsapp_business_manage_events`, Marketing API access, and the business connected through Embedded Signup ([Meta guide](https://developers.facebook.com/docs/marketing-api/conversions-api/business-messaging)).
- Create or fetch the dataset for the business's WABA (`POST/GET /{WABA_ID}/dataset`); store the dataset ID. Only one dataset can be linked to a page.

**Sending an event:** `POST /{DATASET_ID}/events` on the Graph API with `data[]` and `partner_agent` = "northstar".

| Field | Value |
| --- | --- |
| `event_name` | From the mapping below |
| `event_time` | Unix time of the stage change |
| `action_source` | `business_messaging` |
| `messaging_channel` | `whatsapp` |
| `user_data` | `whatsapp_business_account_id` + `ctwa_clid` (preferred); without a click ID: `page_id` + WABA ID + SHA-256 hashed phone |
| `custom_data` | `currency` = INR, `value` in rupees (Purchase and InitiateCheckout) |

**Stage → event mapping (configurable per business)**

| Northstar stage | Meta event | Role |
| --- | --- | --- |
| New lead from an ad | `LeadSubmitted` | Volume signal |
| Scored Warm or Hot | `QualifiedLead` | Quality signal |
| Payment pending (pre-final, default) | `InitiateCheckout` | Main optimisation event |
| Completed | `Purchase` with value | Value signal for ROAS |

**Rules**

- One event per lead per event name: `meta_events` unique on (tenant, lead, event\_name); Northstar never resends a succeeded event.
- Leads without a click ID (chat links, direct) are not sent by default; matching on hashed phone is limited and not guaranteed.
- Only events that happened in the WhatsApp thread go through this API.
- Each business can check events in its Events Manager; the onboarding guide shows them where.

**Marketing API (inbound):** with `ads_read` on the business's ad account, a daily job pulls campaign, ad set and ad names plus daily spend into `ad_spend_daily`, enabling cost per lead, per qualified lead and per booking.

## 6. Chat links, email, LLM and PDF services

These four are smaller integrations, but each follows the same adapter, retry and logging rules as section 1.

**Tracked chat links**

1. `GET northstar.com/c/{code}` → look up `chat_links`; unknown or inactive → friendly error page.
2. Create a `link_clicks` row with a short unique `ref_code`, UTM parameters and page referrer.
3. Redirect (302) to `https://wa.me/{business_number}?text=<prefill text + ref_code>`.
4. When the inbound message contains a known `ref_code`, attach the click and link to the lead; tag source.
5. QR codes generated with ZXing as PNG and SVG, stored in Supabase Storage.

**Email (Resend)**

- Sending domain verified (SPF, DKIM) before launch; separate sub-domain for transactional mail.
- Used for: staff invites, email verification, approval and failure notifications, Northstar's own subscription invoices.
- Bounces and complaints recorded; repeated bounces mark the address invalid (Resend webhooks, after the webhook pipeline in step 2.2).
- Sent as an `actions` row through `POST /emails` with the action's idempotency key in `Idempotency-Key`. Resend keeps a key 24 hours: the same key and body is not sent again (the first response comes back); the same key with a different body is refused (409 `invalid_idempotent_request`); the same key while the first request runs is a 409 `concurrent_idempotent_requests`.
- Classification: 429 rate limit (with `retry-after`), 5xx, timeouts, lost connections and a concurrent idempotent request are retryable; a rejected or suspended platform key, an unverified sender, daily or monthly quota exhaustion, 400/422 and an idempotency conflict are permanent. A rejected key also logs the platform event `resend_key_rejected` at ERROR.
- Invite emails derive their token from the action (HMAC of a platform key), so a retry is byte-identical and Resend's idempotency gives the invitee one email whose link works.
- Local development can use the Mailpit that the Supabase CLI runs (`NORTHSTAR_EMAIL_PROVIDER=mailpit`, localhost only).

**LLM provider**

- Accessed through Spring AI behind Northstar's own `AiService` interface, so the provider can change without touching the engine.
- Each call sends: system prompt (versioned), tenant's approved FAQ and catalogue excerpt, the lead's current fields, the last N messages, and only the tools allowed for the lead's stage.
- Structured output validated against a schema; invalid output → one retry, then Needs Human.
- Per-tenant token budgets and rate limits; usage written to `usage_daily`.
- Provider data-retention and training settings configured to the most restrictive option offered.

**PDF service (Gotenberg)**

- Internal-only container; reachable from the worker, never from the internet.
- Flow: fill the tenant's Word template with poi-tl → send the .docx to Gotenberg → receive PDF → store in Supabase Storage under `tenant_id/invoices/` → send on WhatsApp.
- Timeout 30 seconds per document; failure → action retry (Edge case W22).

## 7. Secrets, environments and test accounts

Staging and production never share credentials, and staging uses only provider test modes and test numbers.

| Integration | Staging | Production |
| --- | --- | --- |
| WhatsApp | Meta test number on a separate test app | Northstar's live app; each business's own number |
| Razorpay | Test mode keys; test card and UPI flows | Each business's live account |
| Google | Separate OAuth client; test users only | Verified OAuth client |
| Meta CAPI | Test events code in Events Manager | Live dataset per business |
| LLM | Separate API key with a low budget | Production key with per-tenant budgets |
| Email | Sandbox or test domain | Verified sending domain |

**Platform secrets** (Northstar's own app secrets, signing keys, database roles) live in DigitalOcean App Platform encrypted environment variables, never in the repo, each set only on the component that reads it. The one exception is the deploy token (`DIGITALOCEAN_ACCESS_TOKEN`: custom scopes app read/update and registry read/update/delete, 90-day expiry), a GitHub environment secret per environment that only `main` may use. Staging email uses Resend's shared test sender until a staging sub-domain is verified; it delivers only to the Resend account owner (`docs/staging-runbook.md`). **Tenant secrets** (business tokens, refresh tokens, Razorpay keys) live encrypted in `secrets.integration_credentials`, decrypted only inside the worker at call time. Rotation: platform secrets every 90 days or on staff change; tenant tokens refreshed per provider rules.

**WhatsApp webhook secrets** (step 2.2): `NORTHSTAR_WHATSAPP_APP_SECRET` (the Meta app's App Secret, which signs every delivery) and `NORTHSTAR_WHATSAPP_VERIFY_TOKEN` (32+ random characters, for the subscription handshake), each 32 to 512 printable characters, encrypted variables on the api component only. The endpoints are active whenever both are set, whatever `NORTHSTAR_WORKER_ENABLED` says (local development runs one process with the worker on that also serves the API). A process with the worker off (an API container) refuses to start if either is missing, naming the setting, never the value; a process with the worker on and either missing starts and answers 503 `webhooks_not_configured` on the webhook paths. A malformed value refuses startup everywhere.

**Tenant credential storage** (decided 19 Nov 2026, step 2.1; built in 2.1b):

- **Who can read them:** only `secrets_user`, a database login whose password is set only on the worker component. Its private pool is created only when `NORTHSTAR_WORKER_ENABLED=true` and is not a Spring bean. No other role can use the `secrets` schema, so the API process can read neither the plaintext nor the ciphertext. The credential store also refuses any tenant context that is not the system actor.
- **Encryption:** AES-256-GCM from the JDK (no new dependency), a fresh random 96-bit nonce from `SecureRandom` per value and a 128-bit tag. `encrypted_secret` is a format byte (`0x01`), the nonce, then ciphertext and tag; `key_id` names the key. The associated data is `credential:v1|tenant_id|credential_id|provider|key_id`, so a value copied to another row, tenant or provider fails authentication.
- **The key:** only in the worker's environment, as DigitalOcean encrypted variables: `NORTHSTAR_CREDENTIAL_KEYS` (comma-separated `keyId:base64` entries, each exactly 32 random bytes) and `NORTHSTAR_CREDENTIAL_ACTIVE_KEY` (the key ID new values use). A missing or malformed key stops the worker at startup with an error naming the setting or key ID, never the value. Keep an offline copy of every key: losing all of them means every business reconnects its integrations.
- **Rotation:** add a new key and make it active; new values and refreshed tokens use it, and older values still decrypt by their `key_id`. Re-encrypt every tenant's rows under the active key, then remove the old key once no row names it.
- **Never in logs, errors or the database:** plaintext is held in a type whose `toString()` is redacted and wiped after use; decryption failures name the credential and key IDs only; the keys are never logged; only ciphertext is ever sent to the database.

## 8. Open questions

- [ ] Razorpay connection model: business API keys, or Razorpay partner authorisation?
- [ ] Who pays WhatsApp message charges: business's own Meta payment method (default assumption) or Northstar?
- [ ] Should leads without a click ID be sent to Meta using hashed phone, given matching is limited?
- [ ] Google Picker for existing sheets, or always create a new Northstar sheet?
- [ ] First LLM provider and model, and per-tenant monthly token budget
- [ ] **Before any live tenant traffic:** build `ad_referrals` capture (the first message's `referral` object with its
  `ctwa_clid`, Phase 7). Until then step 2.4b tags every lead `direct`, and the webhook payload holding the referral is
  kept only 30 days (`data-model.md` section 14), so ad attribution missed in that time cannot be recovered (noted 20 Nov
  2026).
- [ ] **Launch blocker to check:** inbound messages without a phone number in `from`. We understand Meta has announced
  WhatsApp usernames, which may mean a message whose sender is not identified by a phone number. Step 2.4b fails such a
  message permanently as `contact_phone_invalid` (P4 assumes every WhatsApp lead has a phone); confirm against Meta's
  current webhook reference before launch and decide how such a contact is stored (noted 20 Nov 2026).

## Sources

- [Meta: Conversions API for Business Messaging onboarding guide](https://developers.facebook.com/docs/marketing-api/conversions-api/business-messaging)
- [Razorpay: Payment Links webhooks](https://razorpay.com/docs/payments/payment-links/subscribe-to-webhooks.md)
- [Razorpay: all webhook events](https://razorpay.com/docs/webhooks/all)

- [Meta: Graph API webhooks, getting started](https://developers.facebook.com/docs/graph-api/webhooks/getting-started) (verification request, `X-Hub-Signature-256`, batching of up to 1000 updates, deduplication; checked 19 Nov 2026 for step 2.2)
- [Meta: WhatsApp Cloud API, set up webhooks](https://developers.facebook.com/docs/whatsapp/cloud-api/guides/set-up-webhooks) (retries of non-200 deliveries for up to 7 days, payloads up to 3 MB, mTLS rather than IP allowlists)
- [Meta: WhatsApp webhook components](https://developers.facebook.com/docs/whatsapp/cloud-api/webhooks/components) (`entry`, `changes`, `value.metadata.phone_number_id`, `messages[].id`, `statuses[]`, `errors`)

Google API details are from general platform knowledge; confirm endpoints, scopes and quotas against current provider docs during build.
