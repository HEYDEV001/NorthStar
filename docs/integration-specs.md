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
  mid-job, a failure waiting for its retry, and rows stored before processing existed. WhatsApp message and status rows
  (status rows since step 2.7, which also works through those stored since step 2.2).
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
  message never opens a lead. Status rows are processed by step 2.7 (section 2, How step 2.7 applies statuses).
- **Failures.** Retryable, with `processed_at` null and the sweep retrying: `database_unavailable`,
  `database_conflict`, `database_timeout`, `processing_failed`. Permanent, with `processed_at` set and an ERROR line:
  `payload_invalid` and `contact_phone_invalid` (a sender that is not an E.164 number, P4). A missing tenant context
  fails the job loudly and is never recorded (P10).
- **Logs:** webhook event, message and lead IDs, fixed codes and exception classes only; never message text, profile
  names, phone numbers, wamids or phone number IDs, and never an exception's message.

**Outbound (actions)**

- Every call is an `actions` row executed by the worker with an idempotency key; provider idempotency headers used where offered.
- Provider errors are classified: **retryable** (timeouts, 429, 5xx) → backoff; **permanent** (invalid input, a request the provider will never accept) → Failed + alert; **auth** (a business's token expired, invalid or revoked, or a permission it granted removed) → integration status error + pause dependent actions until the integration reconnects (decided 21 Nov 2026, step 2.6). Auth is for a business's own connection, which reconnecting fixes; a rejected Northstar platform key (Resend's, section 6) has no connection to reconnect and stays permanent.
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
| Status updates | sent, delivered, read, failed → `messages.status`, forward only; a failure sets `messages.error_code` and the send action stays succeeded (step 2.7, below) |
| Click-to-WhatsApp referral | `referral` object on the first message: `source_id`, `source_type`, `source_url`, `headline`, `ctwa_clid` → `ad_referrals`; `ctwa_clid` stored for Conversions API matching |

**Sending**

- `POST /{phone-number-id}/messages` on the Graph API with the business token.
- Free-form text and media only inside the 24-hour customer-service window (`conversations.window_expires_at`); outside it, an approved template.
- Quote and invoice PDFs sent as `document` messages with a signed URL or uploaded media ID.
- Accept / Request Changes as interactive reply buttons (button IDs carry the quote version ID).
- Opt-out keywords (STOP and tenant-configured words) checked on every inbound message before the AI sees it. Built in step 2.5 (`workflow-spec.md` section 13, Opt-out): a text or interactive reply whose whole normalised text is a keyword opts the contact out, in the inbound job's transaction; every outbound action to an opted-out contact is then refused, and every send action names the contact as its recipient (step 2.6a).

How step 2.6a sends (decided 21 Nov 2026):

- **Queueing.** `integrations.WhatsAppMessages` queues a text or a template message for a conversation, as the system
  actor (the sender is the system or the AI; staff replies are step 2.9). One transaction stores the message as
  `queued` and creates a `whatsapp.send` action whose recipient is the conversation's contact and whose payload is the
  message ID only, never the text, so retention clears the text in one place. The message ID is derived from the tenant
  and the idempotency key, so queueing the same send twice stores one message and one action (P1). Another tenant's
  conversation or template is not found (P9). Queueing share-locks the conversation's contact before it writes
  anything, so it takes its locks in the inbound job's order (contact, conversation, actions, messages) and cannot
  deadlock with an opt-out being processed. Text is 1 to 4,096 characters, Meta's limit for a text message. A
  template's parameter values are stored on the message (`messages.template_params`), positional or named; named
  parameters are lowercase letters and underscores, as Meta requires.
- **The window, when the send runs.** The handler reads the conversation when the action runs, not when it was queued,
  so a send that waited for approval, a retry or a reconnect sees the window as it is then. The window counts as open
  only while `window_expires_at` is more than 2 minutes away on the database's clock (a margin for clock differences
  and the time a send takes); a conversation with no inbound message has none. Inside it, free text is sent. Outside
  it, a text message fails permanently as `window_closed_no_template` and its message is `failed` with that code (W33).
  A template message is sent inside or outside the window.
- **No automatic switch to a template.** A text send never turns into a template send. The switch in `workflow-spec.md`
  section 12 is a new template send from the Phase 3 engine, so every message row records exactly what was sent.
- **Template status.** A template that is not `approved` when the send runs fails it permanently as
  `template_not_approved`, message `failed` (the half of W34 step 2.6 can do; syncing Meta's statuses is step 2.8).
- **The connection.** The business's WhatsApp integration must be `connected` with a credential, or the send pauses
  as auth `integration_not_connected` without calling Meta. The business token is decrypted only in the worker,
  through the credential store (section 7), when the call is made, and is never logged. A credential that cannot be
  decrypted pauses the send as auth `credential_unreadable` and sets the integration to `error`.
- **Accepted.** Meta's message ID is stored once (`messages_set_once`) with status `sent` and `sent_at` (the database's
  time), in one transaction with the integration's `last_success_at`. A re-run finds the ID and succeeds without
  calling Meta again (P1). Delivery status webhooks move the message on from there (step 2.7).
- **Failures,** classified as section 1 says. A retryable failure follows the WhatsApp send schedule
  (`workflow-spec.md` section 10) and leaves the message `queued`; a permanent one fails the send at once. An auth
  failure sets the integration's status to `error` with its code and pauses the send; every other send of the business
  then pauses when it is claimed, without calling Meta, until the integration reconnects (resuming is the reconnect
  step's). Every call to Meta updates the integration's health: `last_success_at`, or `last_error_code` and
  `last_error_at`. A send that ends without success (failed, or cancelled because its contact opted out, at creation,
  at claim or by the opt-out itself) marks its message `failed` with the action's code in the same transaction
  (`contact_opted_out` for an opt-out). A message Meta has accepted is never marked failed.
- **Duplicates, a known and accepted risk.** Meta's send API takes no idempotency key. A send whose answer is lost after
  Meta accepted it (a client timeout, a dropped connection, or a failure storing the returned ID) is retried and can
  reach the client twice. Permanent errors are never retried, the HTTP client gives up well before the handler's
  timeout so an answer that arrives is always stored, and once the ID is stored no re-run sends again.
- **Not yet:** documents, media and interactive buttons are Phase 5 (the Cloud API adapter, Meta's error codes and the
  pinned Graph API version are step 2.6b, below). No opt-out confirmation is sent, and every send to an
  opted-out contact stays refused, replies included (step 2.9 decides replies).
- **Logs:** action and message IDs, attempt numbers, outcome classes and fixed codes only; never text, template
  parameters, phone numbers, wamids, phone number IDs or tokens.

How step 2.6b calls Meta (decided 21 Nov 2026):

- **Request.** `POST {base URL}/{version}/{phone-number-id}/messages` with `Authorization: Bearer <business token>` and
  a JSON body: `messaging_product` `whatsapp`, `recipient_type` `individual`, `to` the contact's E.164 number (Meta's
  example keeps the leading `+`), then `type` `text` with `text.body`, or `type` `template` with `template.name`,
  `template.language.code` and, when the template takes values, one `body` component whose `parameters` are
  `{"type": "text", "text": ...}` in order, each with `parameter_name` for named values. A template without values
  sends no components. The phone number ID must be digits, as Meta's are, or the send fails as permanent
  `phone_number_id_invalid` without a request. Java's `HttpClient`, no SDK; redirects are never followed, so the token
  goes to the configured host only.
- **Version, pinned.** `northstar.whatsapp.graph.version=v26.0` in `application.properties` (released 29 Jul 2026; its
  changelog changes nothing for messaging, and the legacy protocol features it removes are not used; v27.0, released 4
  Nov 2026, had no changelog page to review when checked). The base URL is `northstar.whatsapp.graph.base-url`
  (`https://graph.facebook.com`). Neither is set per environment: no component's app spec has a variable for either
  (`StagingAppSpecTests`), so every environment calls the version the repo names. A version not shaped like `v26.0`,
  or a base URL that is not bare HTTPS (plain HTTP only to a loopback test stand-in), refuses startup, naming the
  setting.
- **Bumping the version,** well before the pinned version's expiry date in Meta's Graph API changelog
  (`deploy-checklist.md`): (1) read Meta's Graph API changelog for the new version and the WhatsApp Business Platform
  changelog for changes to `/messages`, templates and error codes; (2) change `northstar.whatsapp.graph.version` alone,
  in its own pull request that says what the changelogs say, with the tests green (`CloudApiWhatsAppSenderTests` and
  `WhatsAppCloudApiConfigurationTests` check the request path carries the configured version); (3) once a business can
  connect WhatsApp (onboarding, Phase 8), send a test message to staging's test number after the deploy; (4) update the
  version, date and Sources entry here.
- **Timeouts.** Connect 5 s, request 15 s: 20 s at most, well under the handler's 30 s, so an answer that arrives is
  stored before the attempt could be cut off and retried as a duplicate.
- **Answers.** A 2xx with `messages[0].id` is accepted, whatever `messages[0].message_status` says; delivery comes from
  status webhooks (step 2.7). A 2xx without an ID fails permanently as `response_unreadable` and is never retried, as
  Meta may have sent the message. Anything else is classified by Meta's `error.code`, as Meta advises ("Build your app's
  error handling around error codes instead of subcodes or HTTP response status codes"), with the table below. The HTTP
  status decides only when the code is missing or not in the table: 5xx, 408 and 429 are retryable (`http_<status>`),
  401 is auth (`http_401`), and anything else is permanent: `meta_<code>` for an unlisted code, so it can be looked up
  and added, or `http_<status>` without one. A client timeout is retryable `timeout`, a failed connection retryable
  `connection_error`. A `Retry-After` header, if Meta sends one, is honoured on a retryable answer.
- **Not documented by Meta** on the pages checked: the HTTP status of each error code (Meta says not to rely on it); a
  `Retry-After` header; what each `message_status` value (`accepted`, `held_for_quality_assessment`, `paused`, listed for
  paced templates) means; an idempotency key for sends; and whether a template with no values needs a `components`
  array.
- **Account-level failures** (131042, 368, 131031, 131045, 133010, 33, 131048, 131064) fail each send on its own, each
  with its own alert, and leave the integration `connected` with the code as `last_error_code`; grouping alerts per
  integration is Phase 8.
- **Logs:** Northstar's message ID, the HTTP status, Meta's numeric code, the class, our code and Meta's `fbtrace_id`
  (its support trace ID); never the request, the token, the numbers, Meta's message ID, or Meta's error `message` and
  `details`, which can echo the request.

**Meta's error codes** ([Meta: WhatsApp Cloud API error codes](https://developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes),
checked 21 Nov 2026; `MetaErrors`, and `MetaErrorsTests` fails if this table and the code differ). Codes Meta lists
for other endpoints (template creation, registration, migration, Flows, media, insights and the Marketing Messages API)
are not mapped and fall to the status rule above. "Judgement" marks a class Meta's text does not state outright.

| Meta code | Class | Our code | Meta's description |
| --- | --- | --- | --- |
| 0 | auth | `token_invalid` | Unable to authenticate the app user: the token expired or was invalidated, or the user changed a setting |
| 190 | auth | `token_expired` | The access token has expired |
| 3 | auth | `permission_missing` | Capability or permissions issue |
| 10 | auth | `permission_missing` | Permission not granted or removed |
| 200–299 | auth | `permission_missing` | Permission not granted or removed |
| 131005 | auth | `permission_missing` | Permission not granted or removed |
| 1 | retryable | `meta_request_or_server_error` | Invalid request or possible server error (judgement: retried; a request that really is invalid fails once its attempts are used) |
| 2 | retryable | `meta_unavailable` | Temporary downtime or overload |
| 131016 | retryable | `meta_unavailable` | A service is temporarily unavailable |
| 131000 | retryable | `meta_unknown_error` | Unknown error; "Try again" |
| 4 | retryable | `rate_limited_app` | The app reached its API call rate limit |
| 80007 | retryable | `rate_limited_waba` | The WhatsApp Business Account reached its rate limit |
| 130429 | retryable | `rate_limited_throughput` | Cloud API message throughput reached |
| 131056 | retryable | `rate_limited_pair` | Too many messages to the same recipient in a short time; "Wait and retry" |
| 131057 | retryable | `account_maintenance` | The Business Account is in maintenance mode, for example a throughput upgrade (judgement: temporary) |
| 100 | permanent | `invalid_parameter` | Unsupported or misspelled parameters |
| 131008 | permanent | `missing_parameter` | A required parameter is missing |
| 131009 | permanent | `invalid_parameter_value` | A parameter value is not valid |
| 135000 | permanent | `invalid_request` | Unknown error with the request parameters |
| 131051 | permanent | `unsupported_message_type` | Unsupported message type |
| 131021 | permanent | `recipient_is_sender` | Sender and recipient are the same number |
| 131026 | permanent | `recipient_unreachable` | Unable to deliver the message |
| 130403 | permanent | `recipient_blocked_by_business` | The business has blocked the user; "Do not retry" |
| 131050 | permanent | `recipient_stopped_marketing` | The user stopped receiving marketing messages; "Do not retry" |
| 131047 | permanent | `window_closed` | More than 24 hours since the user last replied; send a template |
| 131049 | permanent | `held_for_ecosystem` | Marketing message held for ecosystem health; "Retrying does not guarantee delivery" (judgement: our retries end within 81.5 minutes) |
| 130472 | permanent | `held_for_experiment` | Not sent, as part of an experiment |
| 132000 | permanent | `template_param_count` | The number of values does not match the template |
| 132001 | permanent | `template_not_found` | The template does not exist in that language, or is not approved |
| 132005 | permanent | `template_text_too_long` | Translated text is too long |
| 132007 | permanent | `template_policy_violation` | Template content violates a WhatsApp policy |
| 132012 | permanent | `template_param_format` | Values formatted incorrectly |
| 132015 | permanent | `template_paused` | Template paused for low quality |
| 132016 | permanent | `template_disabled` | Template paused too many times and now permanently disabled |
| 131063 | permanent | `marketing_disabled_on_cloud_api` | Marketing templates are disabled for the business's Cloud API |
| 131042 | permanent | `payment_method_error` | An error with the payment method |
| 368 | permanent | `account_restricted` | The account is restricted or disabled for a policy violation |
| 131031 | permanent | `account_restricted` | The account is restricted, disabled or locked |
| 130497 | permanent | `country_restricted` | The account may not message users in some countries |
| 131048 | permanent | `sending_restricted` | Sending from this number is restricted; "Check your quality status" (judgement: not lifted within our retries) |
| 131064 | permanent | `messaging_limit_restricted` | Messaging limit for template misclassification, lifted after the enforcement period (judgement: as 131048) |
| 131045 | permanent | `number_not_registered` | Phone number registration error |
| 133010 | permanent | `number_not_registered` | The phone number is not registered on the platform |
| 33 | permanent | `number_deleted` | The business phone number was deleted |
| 131037 | permanent | `display_name_not_approved` | The 555 number has no approved display name |

How step 2.7 applies statuses (decided 21 Nov 2026):

- **One transaction per status row,** as the system actor in its tenant: the webhook row is locked (one already
  processed is left alone, P1), the slice read (its `statuses[0]` `id` and `status` must match the row's key, or the
  row closes as `payload_invalid`; `timestamp` is Unix seconds; only `errors[0].code` is read), the outbound message
  with that wamid locked, the status applied, `message.status` recorded and `processed_at` set. Meta's statuses are
  `sent`, `delivered`, `read`, `failed` and `played`; `played` (voice messages, which Northstar does not send) and
  anything else close the row as `status_unsupported`.
- **Forward only:** `queued` < `sent` < `delivered` < `read`, any jump forward allowed. `failed` replaces `queued` or
  `sent` only (a status always finds its message `sent`: 2.6a stores Meta's ID with that status), and nothing leaves
  `failed`. The guarded update decides, and the trigger `messages_status_forward` (SQLSTATE NSM02, every role) refuses
  any other move as the backstop. Meta's timestamp fills `delivered_at` or `read_at` once (`messages_set_once`);
  `sent_at` stays the database's time from step 2.6a.
- **What each status does.** Forward: applied, recorded, closed. The same status again (Meta's `sent` after our own,
  or a replay): nothing changes, recorded under its dedupe key (a no-op if already there), closed. Backward (`sent`
  after `delivered`, say), or `failed` after `delivered` or `read`: ignored, nothing recorded, an INFO line with IDs,
  closed. A late `delivered` after `read` (Meta may skip `delivered` when a message is read at once, as read implies
  delivered): the status stays `read`, `delivered_at` is filled if it was empty, `message.status` is recorded under
  `delivered`'s own key, an INFO line, closed.
- **A failure after sending** sets `error_code` from Meta's code through the error table (section 2, Meta's error
  codes): the table's code, `meta_<code>` for an unlisted one, `delivery_failed` when Meta sends none; the class does
  not apply, as nothing is retried. The send action stays `succeeded`, as finished actions are never reopened; the
  failure shows on the message (inbox, step 2.9) and as `message.status` `failed` with its code (for Failed Actions,
  step 3.8), and a WARN line `webhook.status_failed` with IDs and the code.
- **A status before its message** (Meta's webhook beating 2.6a's commit of the ID, or a send we never stored) is not
  dropped: the row stays unprocessed with the retryable code `message_not_found_yet` and the sweep retries it on its
  ladder (section 1); still unmatched at 24 hours, it is closed with that code and the ERROR line
  `webhook.failed ... final=true`, as an unmatched status can mean a send Meta accepted whose ID was not stored.
- **Locks:** the webhook row, then the message; never the contact, conversation or action. Two statuses of one message
  wait for each other at the message.
- **Not stored:** the `pricing` and `conversation` objects (usage metering, Phase 8), nor `recipient_id`.
- **Logs:** webhook event and message IDs, statuses and fixed codes only; never wamids, phone numbers, text, or Meta's
  error title, message or details.

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
  current webhook reference before launch and decide how such a contact is stored (noted 20 Nov 2026). Meta's
  business-scoped user ID page says a user who adopts a username may arrive without `wa_id` or `from`, identified by
  a business-scoped user ID (BSUID) in `user_id`, and that sends to a BSUID (`recipient` instead of `to`) have been
  supported since July 2026 ([Meta: business-scoped user IDs](https://developers.facebook.com/documentation/business-messaging/whatsapp/business-scoped-user-ids.md),
  checked 21 Nov 2026). Step 2.6a sends only to a contact's E.164 number in `to`. Still a launch blocker to decide
  (noted 21 Nov 2026).

## Sources

- [Meta: Conversions API for Business Messaging onboarding guide](https://developers.facebook.com/docs/marketing-api/conversions-api/business-messaging)
- [Razorpay: Payment Links webhooks](https://razorpay.com/docs/payments/payment-links/subscribe-to-webhooks.md)
- [Razorpay: all webhook events](https://razorpay.com/docs/webhooks/all)

- [Meta: Graph API webhooks, getting started](https://developers.facebook.com/docs/graph-api/webhooks/getting-started) (verification request, `X-Hub-Signature-256`, batching of up to 1000 updates, deduplication; checked 19 Nov 2026 for step 2.2)
- [Meta: WhatsApp Cloud API, set up webhooks](https://developers.facebook.com/docs/whatsapp/cloud-api/guides/set-up-webhooks) (retries of non-200 deliveries for up to 7 days, payloads up to 3 MB, mTLS rather than IP allowlists)
- [Meta: WhatsApp webhook components](https://developers.facebook.com/docs/whatsapp/cloud-api/webhooks/components) (`entry`, `changes`, `value.metadata.phone_number_id`, `messages[].id`, `statuses[]`, `errors`)

Checked 21 Nov 2026 for steps 2.6a and 2.6b (sending):

- [Meta: WhatsApp Cloud API error codes](https://developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes) (every code, its details and solution; build on `code` and `details`, not subcodes, titles or HTTP status; HTTP statuses given only for the Marketing Messages API)
- [Meta: Graph API changelog](https://developers.facebook.com/docs/graph-api/changelog) (v26.0 released 29 Jul 2026 and v27.0 4 Nov 2026, both "TBD" to expire; v25.0 until 29 Jul 2028) and [Graph API v26.0 changelog](https://developers.facebook.com/docs/graph-api/changelog/version26.0) (no change to messaging; legacy protocol features removed: `pretty`, `debug`, `date_format`, `GET /?ids=`, `If-None-Match`)
- [Meta: send text messages](https://developers.facebook.com/docs/whatsapp/cloud-api/messages/text-messages) (the request body, `to` as `+16505551234`, at most 4,096 characters, the response with `messages[0].id`)
- [Meta: Messages API reference](https://developers.facebook.com/docs/whatsapp/cloud-api/reference/messages) (`Authorization: Bearer`, the response fields, `message_status` values for paced templates)
- [Meta: send messages](https://developers.facebook.com/documentation/business-messaging/whatsapp/messages/send-messages.md) (a success does not mean delivery; outside the 24-hour window only templates)
- [Meta: template fundamentals](https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/overview.md) (send-time body parameters, positional or named with `parameter_name`; named parameters are lowercase letters and underscores; only APPROVED templates can be sent)
- [Meta: template pausing](https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-pausing.md) (pauses of 3 and 6 hours, then disabled)
- [Meta: throughput](https://developers.facebook.com/docs/whatsapp/throughput) (80 messages a second per number by default, up to 1,000; 130429 when exceeded)
- [Meta: pricing](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing.md) (templates sent inside an open customer-service window charged per message since 1 Oct 2026)
- [Meta: business-scoped user IDs](https://developers.facebook.com/documentation/business-messaging/whatsapp/business-scoped-user-ids.md) (users with usernames may arrive without a phone number; sends to a BSUID since July 2026)
- No page checked offers an idempotency key for sends.
- [Meta: status webhook reference](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/messages/status) (step 2.7: statuses `sent`, `delivered`, `read`, `failed` and `played`; `timestamp` in Unix time; `errors[]` with `code`, `title`, `message`, `error_data.details` and `href`; "A status is considered read only if it has been delivered", and `delivered` may be skipped when a message is read at once; `pricing` in the sent or the delivered / read webhook; `conversation` omitted from v24.0 outside free entry points) and [WhatsApp webhook components](https://developers.facebook.com/docs/whatsapp/cloud-api/webhooks/components) (up to three status webhooks per message; "Use `statuses.id` to correlate")

Google API details are from general platform knowledge; confirm endpoints, scopes and quotas against current provider docs during build.
