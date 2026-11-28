# Northstar Workflow Spec

Oct 22, 2026 · @Abhishek

## 1. Purpose and principles

This spec defines how Northstar's workflow engine moves a lead from first message to closed, so it can be built and tested exactly; it is the reference for Phase 3 (workflow engine) and every phase that adds actions to it. The product context is in the Northstar Build Document.

**Engine principles**

1. **One engine, configured per business.** Industries differ by configuration (stages, steps, rules), never by code forks.
2. **Deterministic core, AI at the edges.** The engine decides transitions from events, rules and stage config. The AI proposes; the engine validates and executes.
3. **Every side effect is an action.** Sending a message, creating a payment link, issuing an invoice, writing a calendar event: each is a logged, idempotent, retryable action with a status.
4. **Nothing stops silently.** A failed action retries, then surfaces to a human.
5. **Humans can always step in.** Staff can pause automation, take over a conversation, or move a lead manually; manual moves are logged.
6. **Versioned configuration.** A lead runs on the workflow version it started with.
7. **Money and commitments are guarded.** Payment status comes only from Razorpay; quotes, bookings, invoices and refunds follow approval rules.

## 2. Core concepts

A business's workflow is a versioned set of stages; events move leads between stages, and each stage runs actions through typed steps.

| Concept | Definition | Stored in |
| --- | --- | --- |
| Workflow | A business's lead process, built from a template | `workflows` |
| Workflow version | An immutable published snapshot of the workflow config | `workflow_versions` |
| Stage | A named state a lead is in (e.g. Quoted). Has required fields, entry actions, timers, allowed exits, approval flag | Version config |
| Side stage | A state outside the main path: Needs Human, Negotiating, Junk, Lost, Cancelled | Version config |
| Step type | A reusable capability with parameters (qualify, quote, advance payment, schedule…) attached to a stage | Engine code + config |
| Event | Something that happened: message received, payment captured, timer fired, staff action | `events` (append-only) |
| Trigger | An event, optionally with a condition, that causes a transition or action | Version config |
| Condition | A rule over lead fields that gates a transition or switches a step on/off | Version config |
| Transition | A move from one stage to another, with a guard and a reason | `timeline_events` |
| Timer | A scheduled event (follow-up, reminder, link expiry, slot-hold release) | Job queue |
| Action | One side effect with status, attempts and idempotency key | `actions` |
| Approval | A staff decision required before an action executes (approval mode) | `actions` (status Needs Review) |

**Runtime loop:** event arrives → engine loads lead + its workflow version → evaluates triggers for the current stage → if a transition is allowed, moves the stage and records it → runs entry actions of the new stage → schedules timers. Every step of the loop is written to the lead's timeline.

## 3. Lead states and allowed transitions

The Reelrr default has ten main stages and five side stages; any transition not listed here is rejected by the engine unless an admin forces it manually, which is logged with a reason.

&#91;embedded content: lead state machine · 10 stages, 5 side stages\]

Dashed paths are side transitions; Needs Human can be entered from any open stage, shown here from Qualifying only. The table below is the full list the engine enforces.

| From | To | Trigger | Guard |
| --- | --- | --- | --- |
| — | New | First inbound message from an unknown phone | Phone not already an open lead in this workspace |
| New | Qualifying | Lead created | AI enabled for workspace and conversation |
| Qualifying | Scored | All required qualifying fields captured | Required fields valid |
| Qualifying | Needs Human | AI low confidence, complaint, or explicit request for a person | — |
| Scored | Junk | Filter rule failed | — |
| Scored | Quoted | Quote sent | Score band not Junk; availability found for requested slot |
| Scored | Needs Human | No open slot for requested date | — |
| Quoted | Negotiating | Client taps Request Changes or asks for a discount | — |
| Negotiating | Quoted | Staff sends revised quote version | Discount within limit or admin approved |
| Quoted | Payment pending | Client taps Accept | Accepted version is the latest sent version |
| Payment pending | Booked | Razorpay `payment.captured` / link paid webhook | Amount ≥ required advance; slot still held or re-confirmed |
| Payment pending | Quoted | Payment link expired | Slot released; fresh link offered |
| Booked | Scheduled | Staff assigned and calendar events created | Assigned staff free at slot |
| Scheduled | In progress | Job start time reached or staff marks started | — |
| In progress | Balance pending | Staff marks done | Amount due > 0 |
| In progress | Completed | Staff marks done | Amount due = 0 |
| Balance pending | Completed | Balance payment captured | Amount due = 0 |
| Any open stage | Lost | Client declines, or no reply after final follow-up | Not Booked or later |
| Booked / Scheduled | Cancelled | Client or admin cancels | Refund flow started per policy |
| Any open stage | Needs Human | Staff takeover, AI escalation, failed action needing review | — |
| Needs Human | Previous stage | Staff hands back to automation | — |
| Lost / Junk | Qualifying | Same contact messages again | New lead record linked to contact |

**Rules**

- A lead is in exactly one stage at a time.
- Every transition writes a timeline entry: from, to, trigger, actor (AI / system / staff id), timestamp.
- Opt-out does not change stage; it blocks all outbound automation for the contact.

## 4. Stage-by-stage definitions (Reelrr default)

Each stage lists what must be true on entry, the fields it needs, the actions it runs, its timers and whether staff must approve in approval mode.

| Stage | Required fields | Entry actions | Timers | Approval (approval mode) |
| --- | --- | --- | --- | --- |
| 1. New | phone, source | Create contact + lead; attach source, campaign, ad, click ID or chat-link code; send greeting (session message) | — | No |
| 2. Qualifying | — (collecting) | AI asks configured questions one or two at a time; extracts fields with confidence | Follow-up if no reply (section 8) | No |
| 3. Scored | service, date, time, location, budget | Run filter rules; AI score 0–100 + reasons; set band; send Meta Lead event if Warm/Hot | — | No |
| 4. Quoted | service lines, slot | Check availability; build quote version from catalogue; render PDF; send with Accept / Request Changes | Quote follow-up | **Yes**: quote send |
| 5. Payment pending | accepted quote version, payment type | Create Razorpay link (advance % or full); hold slot until link expiry; send link; **send Meta pre-final event** | Payment reminder; link expiry; slot-hold release | **Yes**: payment link send |
| 6. Booked | captured payment | Record ledger entry; generate + send GST invoice; auto-assign free staff | — | **Yes**: booking confirmation |
| 7. Scheduled | assigned staff | Create events in platform + business + staff Google Calendars; send confirmation to client and staff | Job reminders (client + staff) | No |
| 8. In progress | — | Notify staff of job start; open job tasks | Overdue-task escalation | No |
| 9. Balance pending | amount due > 0 | Create balance Razorpay link; send | Balance reminders; escalation | **Yes**: balance link send |
| 10. Completed | amount due = 0 | Final invoice; thank-you; review request; Meta Purchase event with value; close lead | Review request reminder (once) | No |

**Side stages**

| Side stage | Entry actions | Exit |
| --- | --- | --- |
| Needs Human | Pause AI for the conversation; notify assigned staff or admin; flag in inbox | Staff hands back (to previous stage) or moves lead manually |
| Negotiating | Notify staff with the client's request | Staff sends a revised quote version → Quoted |
| Junk | Stop automation; keep record for review | Staff restores → Qualifying |
| Lost | Stop automation; record reason | New inbound message → new lead |
| Cancelled | Release slot; delete calendar events; notify staff; start refund flow | Terminal |

## 5. Step type catalogue

Step types are the building blocks a business switches on, orders and configures; each is implemented once in the engine and reused by every template.

| Step type | What it does | Key parameters |
| --- | --- | --- |
| `qualify` | AI conversation to collect fields | questions\[\], required\_fields\[\], max\_turns, language, tone |
| `score` | Filter rules + AI score | rules\[\], band\_thresholds {hot, warm}, meta\_lead\_event (on/off) |
| `call` (discovery / counselling) | Book a call with staff | duration\_min, staff\_skill, calendar\_required |
| `site_visit` | Book an on-site visit | duration\_min, staff\_skill, location\_required |
| `quote` | Build and send a quote version | catalogue\_filter, validity\_days, discount\_limit\_pct, approval |
| `payment` | Create and send a Razorpay link | kind (advance / full / balance / fee), advance\_pct, link\_expiry\_hours, approval |
| `invoice` | Generate and send GST invoice | template\_id, on (payment\_captured / manual) |
| `schedule` | Check availability, hold slot, create events | duration\_min, buffer\_min, slot\_hold = link expiry, calendars (business / staff) |
| `assign` | Pick staff for the job | strategy (least\_busy / round\_robin / skill\_match), required\_skill |
| `job` | Service delivery with tasks | tasks\[\], overdue\_after\_hours |
| `batch_assign` | Put a customer into a batch/cohort (coaching) | batches\[\], capacity |
| `delivery` | Send delivery link/file to client | requires\_approval, link\_field |
| `review_request` | Ask for a review | delay\_hours, review\_url |
| `close` | Mark complete, send thank-you, Meta Purchase event | send\_purchase\_event (on/off) |

**Rules**

- A step can be switched off without breaking the workflow; the engine skips it and moves to the next enabled step.
- `payment` with kind `advance` automatically implies a later `balance` payment step if the advance is below 100%.
- `schedule` and `assign` require Google Calendar connected for at least the business calendar; staff calendars are optional but recommended.

## 6. Events and triggers

Every input to the engine is a normalised event with a unique ID; the same event processed twice must have no extra effect.

| Event | Source | Dedupe key |
| --- | --- | --- |
| `message.received` | WhatsApp webhook | WhatsApp message ID |
| `message.status` (sent, delivered, read, failed) | WhatsApp webhook | message ID + status (`message.status:<wamid>:<status>`, step 2.7) |
| `button.clicked` (Accept, Request Changes, confirm, reschedule) | WhatsApp interactive reply | message ID |
| `optout.received` (STOP and configured keywords) | WhatsApp message | message ID |
| `ai.fields_extracted` | AI worker | lead ID + turn |
| `ai.escalation` | AI worker | lead ID + turn |
| `payment.captured` | Razorpay webhook | Razorpay payment ID |
| `payment.link_expired` | Razorpay webhook or timer | link ID |
| `payment.failed` | Razorpay webhook | payment ID |
| `refund.processed` | Razorpay webhook | refund ID |
| `timer.fired` (follow-up, reminder, expiry, hold release) | Job queue | timer ID |
| `staff.action` (approve, reject, mark done, take over, hand back, move stage, reassign, cancel) | Dashboard / WhatsApp | request ID; for take over and hand back (step 2.9c) `staff.action:<action>:<conversation ID>:<take over time in microseconds>` (action `take_over` or `hand_back`), unique for each take over and the hand back that ends it, as a client can choose its own request ID |
| `calendar.changed` | Google Calendar sync | event ID + updated time |
| `sheet.edited` (opted-in fields only) | Sheets poll | row ID + version |
| `integration.disconnected` | Any connector | provider + tenant + time |

**Trigger format:** `on: <event>` + optional `if: <condition>` + `do: transition | action[]`. Triggers are evaluated only for the lead's current stage plus global triggers (opt-out, takeover, cancellation).

## 7. Conditions and rules format

Conditions are small JSON expressions over lead fields, evaluated by the engine; no free-form code and no AI judgement inside a condition.

**Operators:** `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `not_in`, `contains`, `exists`, `within_zone`; combined with `all` (AND), `any` (OR), `not`.

**Fields available:** lead fields (service, budget, date, location, source, score, band), contact fields (is\_repeat\_customer, opt\_out), workspace settings, and `requirements.<key>` for business-specific fields.

```json
{
  "filter_rules": [
    {"id": "zone", "if": {"not": {"within_zone": ["location", "service_zone"]}}, "then": "junk", "reason": "Outside service area"},
    {"id": "min_budget", "if": {"lt": ["budget", 1500000]}, "then": "junk", "reason": "Below minimum budget"}
  ],
  "step_conditions": [
    {"step": "site_visit", "enabled_if": {"gt": ["budget", 5000000]}},
    {"step": "payment.advance", "skip_if": {"eq": ["is_repeat_customer", true]}}
  ]
}
```

**Amounts are in paise**, like every amount in Northstar: `{"lt": ["budget", 1500000]}` is a budget below ₹15,000. The editor shows and takes rupees and converts (step 3.1, decided 27 Nov 2026).

**Rules**

- Conditions are validated when the workflow is saved; an unknown field or operator blocks publishing.
- A missing field evaluates as false (never an error), except `exists`.
- Junk from a filter rule always stores the rule `id` and `reason` on the lead.

**Format v1** (step 3.1, decided 27 Nov 2026; section 15 has the rest of the format). Validated at every draft save and publish; the engine evaluates conditions from step 3.4.

- **Shape.** A condition is an object with exactly one operator. `eq`, `neq`, `gt`, `gte`, `lt`, `lte` and `contains` take `[field, value]`; `in` and `not_in` take `[field, [values]]`, a non-empty list; `exists` takes `[field]`; `within_zone` takes `[field, setting]`; `all` and `any` take a non-empty list of conditions; `not` takes one condition. Values are strings, numbers or booleans: integers for `budget` (paise) and `score`, booleans for `is_repeat_customer` and `opt_out`. At most 10 levels deep.
- **`{"all_required_fields": true}`** (section 15's exit to Scored) is true once every field in the stage step's `required_fields` is captured (section 3, Qualifying → Scored). It is allowed only in an exit of a stage whose step has `required_fields`, and its value is always `true`.
- **Fields.** Lead fields `service`, `budget`, `date`, `time`, `location`, `source`, `score` and `band`; contact fields `is_repeat_customer` and `opt_out`; and `requirements.<key>`, with the key in stage-key form (section 15). No workspace setting exists yet, so `within_zone` (and the zone rule in the example above) is refused until step 3.4 defines one.

## 8. Timers, follow-ups and reminders

All timers are tenant-configurable; the values below are proposed starting defaults to validate in the pilot, and every timer respects quiet hours and opt-out.

| Timer | Starts when | Default | On fire |
| --- | --- | --- | --- |
| Qualifying follow-up | Client stops replying mid-qualification | 4 h, then 24 h (max 2 automated attempts) | Nudge message; after 2nd → task for staff, lead stays |
| Quote follow-up | Quote sent, no response | 24 h, then 72 h (max 2) | Nudge; after 2nd → Lost unless staff extends |
| Payment reminder | Payment link sent | 12 h before expiry | Reminder with same link |
| Payment link expiry | Payment link sent | 24 h | Link expires; slot released; one fresh-link offer |
| Slot hold | Payment link sent | = link expiry | Slot returned to availability |
| Job reminder (client) | Scheduled | 24 h and 2 h before start | Reminder with date, time, place, staff name |
| Job reminder (staff) | Scheduled | Morning of the job (8:00 tenant time) and 2 h before | Reminder with client details for that job only |
| Overdue task | Job task due | Due time + 4 h | Escalate to admin |
| Balance reminder | Balance link sent | Day 1, 3, 7 | Reminder; after day 7 → escalate to staff |
| Review request | Completed | 24 h after completion | One message; no repeat |
| Approval waiting | Action set to Needs Review | 30 min, then 2 h | Notify approver again; then admin |

**Quiet hours:** default 21:00–09:00 tenant time; a timer firing inside quiet hours is delayed to the next allowed time, except job reminders due within 2 hours.

**Cancellation of timers:** any reply from the client, a stage change, opt-out, takeover or cancellation re-checks and cancels pending timers that no longer apply. A take over (step 2.9c) already cancels the conversation's queued automated sends; its lead's timers are cancelled from its `staff.action` event once timers exist (step 3.6).

## 9. Approval mode vs automatic mode

New businesses start in approval mode; the admin can switch to automatic, but some actions always need a human regardless of mode.

| Action | Approval mode | Automatic mode |
| --- | --- | --- |
| Greeting and qualifying messages | Auto | Auto |
| Score and band | Auto | Auto |
| Send quote (catalogue price, no discount) | Staff approves | Auto |
| Send quote with discount within limit | Staff approves | Auto |
| Send quote with discount above limit | Admin approves | Admin approves |
| Send payment link | Staff approves | Auto |
| Booking confirmation + staff assignment | Staff approves | Auto |
| Invoice after captured payment | Auto | Auto |
| Balance link | Staff approves | Auto |
| Refund | Owner/admin approves | Owner/admin approves |
| Cancellation after payment | Staff handles | Staff handles |
| Delivery link to client | Staff approves | Staff approves (configurable to auto) |
| Non-template promotional message | Staff approves | Staff approves |

**How approval works**

1. The engine creates the action with status **Needs Review** and the drafted content (quote PDF, message text, amount).
2. The approver sees it in the dashboard (and a WhatsApp notification) with Approve, Edit, Reject.
3. Approve → action executes. Edit → new draft, then executes. Reject → lead goes to Needs Human.
4. Every action stores the mode it ran under and the approver.

## 10. Action execution model

Every side effect runs as an action through the job queue with a status, an idempotency key and a retry policy; the engine never calls an external service inline from a webhook.

**Statuses:** Pending → Processing → Succeeded | Failed | Needs Review (approval or manual check) | Paused (an auth failure, waiting for the integration to reconnect; or blocked, waiting for what the action depends on, such as a WhatsApp template Meta paused) | Skipped (staff chose to skip) | Cancelled (no longer applies).

**Attempts and retries.** "N attempts" in the table below counts every try, the first one included. Where waits are listed, the first try runs at once and each retry waits the next listed time, so "a first try plus 5 retries" is 6 attempts. While an attempt runs the action is Processing under a lease (5 minutes); every action handler has a timeout shorter than the lease, and an attempt that times out is recorded as a retryable failure. An attempt whose worker dies is taken over when its lease expires, and still counts.

**Failure classes** (`integration-specs.md` section 1): **retryable** (timeouts, 429, 5xx) waits for the next retry, and the action fails once its attempts are used up; **permanent** (invalid input, a request the provider will never accept) fails at once; **auth** (a business's credentials expired, invalid or revoked, or a permission removed) pauses the action without using an attempt, and it resumes when the integration reconnects; **blocked** (step 2.8c, W34: something the action depends on is unavailable for now, such as a WhatsApp template Meta paused) pauses it the same way, and it resumes when that dependency is back, at the later of the time it was due and now. A pending action can be blocked before it runs, keeping its time and its attempts; one waiting for review keeps waiting. A reconnect resumes auth pauses only, and a dependency coming back resumes only the actions blocked on it, so neither resumes the other's. A provider's `Retry-After` longer than the scheduled wait moves the next attempt later, but never past the type's overall window, counted from when the action first became runnable (created, or approved); a type with no window listed has the sum of its waits as its window.

**Idempotency key** = `tenant_id + lead_id + action_type + stage_entry_id (+ version or amount where relevant)`. Before calling a provider, the worker checks whether an action with that key already succeeded; providers that support idempotency headers get the same key.

| Action type | Retry policy | On final failure |
| --- | --- | --- |
| WhatsApp send | A first try plus 5 retries (6 attempts), waiting 30 s → 1 m → 5 m → 15 m → 1 h; window 81.5 min | Failed; staff alerted; if outside 24-hour window, retry with template (a new template send from the Phase 3 engine: a text send never turns into one, step 2.6a). Meta takes no idempotency key, so a send whose answer is lost after Meta accepted it can reach the client twice (accepted risk, `integration-specs.md` section 2) |
| Razorpay link create | Same as WhatsApp send (6 attempts) | Failed; lead stays in stage; staff alerted |
| Invoice generate + send | 5 attempts | Failed; payment record unaffected; retry from Failed Actions |
| Google Calendar write | 8 attempts up to 6 h | Failed; platform calendar still correct; resync on reconnect |
| Sheets row upsert | Batched; retry until success, max 24 h | Integration marked error; outbox kept |
| Meta CAPI event | 5 attempts up to 24 h | Failed; logged for admin view |
| Email send (Resend) | Same as WhatsApp send (6 attempts) | Failed; alert; for an invite, the admin resends it |
| WhatsApp sync (steps 2.8a and 2.8b: numbers and templates) | A first try plus 2 retries (3 attempts), waiting 30 s → 5 min; window 5.5 min | Failed; alert; the stored values stay as they were, and the next scheduled sync (one per business every 6 hours) tries again |
| AI call | 3 attempts | Escalate lead to Needs Human |

**Failed Actions screen:** shows lead, action, error, attempts, last try; buttons Retry, Skip (with reason), Open lead.

**Alerts:** same action failing 3 times, or any integration failing for more than 30 minutes, notifies the business admin; repeated failures across tenants notify the Northstar admin. An action raises at most one alert at its third failed attempt and at most one when it fails for good; when its third attempt is also its last, only the final-failure alert is sent. An auth pause raises its own alert; a blocked pause raises none, as the change that blocks it is reported where it happens (a template's status change, `integration-specs.md` section 2).

## 11. AI contract per stage

The AI gets a different, narrow set of tools in each stage; anything outside that set is impossible for it, not merely discouraged.

| Stage | AI may | AI hands off when |
| --- | --- | --- |
| New / Qualifying | Greet, ask configured questions, answer from approved FAQ, `update_allowed_fields`, `propose_stage_change` (→ Scored) | Confidence < threshold, complaint, client asks for a person, question not in FAQ |
| Scored | Explain next step; `check_availability`; `draft_quote` | No open slot; client asks for custom work outside catalogue |
| Quoted | Answer questions about the quote from its lines; detect Accept / Request Changes in free text | Any price negotiation or discount request (→ Negotiating) |
| Payment pending | Resend link, answer payment questions from FAQ | Payment problem, refund question |
| Booked → Completed | Answer logistics questions (date, place, staff name) from the lead record; `create_callback_task` | Reschedule, cancel, complaint, anything about money |
| Needs Human | Nothing (paused) | — |

**Every AI turn returns structured output:** reply text, extracted fields with confidence + source message ID, proposed tool calls, escalate flag + reason. The engine validates the schema before using any of it.

**Defaults**

- Confidence threshold for using an extracted field: 0.8; below it the AI asks a clarifying question.
- Clarifying questions per field: max 2, then hand off.
- The AI never states a price, discount, date commitment or refund that is not in the lead record or catalogue.
- Customer text is treated as data; instructions inside it never change the AI's tools, rules or tenant.

## 12. WhatsApp messages per stage

Inside the 24-hour customer-service window the system sends free-form messages; outside it, only pre-approved templates, so every timed message below needs a template ready.

| Message | Stage | Usually inside window? | Template needed | Expected category |
| --- | --- | --- | --- | --- |
| Greeting + first question | New | Yes | No | — |
| Qualifying questions | Qualifying | Yes | No | — |
| Qualifying follow-up | Qualifying | Often no | Yes | Utility or marketing (Meta decides) |
| Quote PDF + Accept / Request Changes | Quoted | Yes | Yes (for resend outside window) | Utility |
| Quote follow-up | Quoted | Often no | Yes | Marketing likely |
| Payment link | Payment pending | Usually | Yes | Utility |
| Payment reminder / fresh link | Payment pending | Often no | Yes | Utility |
| Invoice PDF + booking confirmation | Booked | Often no | Yes | Utility |
| Job reminder (client) | Scheduled | No | Yes | Utility |
| Staff assignment / reminder (to staff) | Scheduled | No | Yes | Utility |
| Balance link + reminders | Balance pending | No | Yes | Utility |
| Final invoice + thank-you | Completed | Maybe | Yes | Utility |
| Review request | Completed | No | Yes | Marketing likely |

**Rules**

- Templates are created per business in their WhatsApp account during onboarding, from Northstar's starter set, in the business's language.
- The sender checks the window at send time; outside it, it switches to the matching template or fails the action with a clear reason. Built in step 2.6a (`integration-specs.md` section 2): the window is read when the send runs and counts as open only while it has more than 2 minutes left; a text message outside it fails as `window_closed_no_template`; a template message is sent inside or outside it. The switch to the matching template is the Phase 3 engine's: a new template send, never a text send turned into one.
- Template status (W34; step 2.8c, `integration-specs.md` section 2, Template status from Meta). A send reads its template's status when it runs: approved sends; paused blocks it (section 10) until the template is approved again; rejected, disabled, deleted and archived fail it as `template_rejected`, `template_disabled`, `template_deleted` or `template_archived`; draft and submitted fail it as `template_not_approved`. When Meta changes a template's status, its sends still queued follow at once: paused blocks the pending ones, approved resumes the blocked ones on their schedule, and rejected, disabled, deleted or archived fail the pending, waiting-for-review and paused ones, their messages failed, visible on Failed Actions. A send waiting for review keeps waiting through a pause and is blocked if it runs during one.
- Opt-out (STOP and configured keywords) blocks all automated and promotional messages immediately. From step 2.5 every outbound action to an opted-out contact is refused, replies included (decided 21 Nov 2026), with one exception from step 2.9b (decided 22 Nov 2026, section 13 Opt-out): a staff member's free-text reply inside the 24-hour window the client opens by writing again after opting out.
- Message categories and pricing are set by Meta and can change; record the category returned on template approval.

## 13. Side flows

Each side flow below has a fixed sequence; the engine follows it the same way for every business, with only policy values configurable.

**Negotiation**

1. Client taps Request Changes or asks for a discount → Negotiating; AI stops pricing talk; staff notified.
2. Staff edits lines or discount → new quote version (v+1); previous version marked Superseded.
3. Discount above limit → admin approval first.
4. New version sent → Quoted. Only the latest sent version can be accepted.

**Payment link expiry**

1. Link expires unpaid → slot released → lead back to Quoted.
2. One message with a fresh link and re-checked slot availability.
3. Payment arrives on an old link after release → availability re-checked; free → Booked; not free → Needs Human to reschedule or refund.

**Duplicate or excess payment:** one ledger entry per Razorpay payment ID; any amount above the due amount is flagged "refund due" for admin.

**Cancellation (after payment)**

1. Client asks to cancel → Needs Human (AI never confirms cancellation).
2. Staff confirms → Cancelled: slot released, calendar events deleted, staff notified, reminders cancelled.
3. Refund amount calculated from the business policy → owner approves → Razorpay refund → ledger + credit note.

**Reschedule**

1. Client asks → staff (or AI, if enabled later) offers open slots.
2. New slot chosen → appointment moved, calendars updated, reminders rescheduled, assignment re-checked.
3. Old slot released. Stage unchanged.

**Staff unavailable / reassignment**

1. Leave marked or conflict detected → affected appointments flagged.
2. Auto-reassign to a free staff member with the required skill; none free → admin alerted.
3. Both staff notified; calendars moved; client optionally informed; history logged.

**Opt-out**

1. STOP (or configured keyword) → contact opt\_out\_at set.
2. All scheduled outbound timers for that contact cancelled; no further automated messages.
3. Stage unchanged; staff see an Opted out badge. From step 2.5 no outbound action to the contact is allowed at all, transactional replies included, except the staff reply step 2.9b allows (below).

How step 2.5 builds it (decided 21 Nov 2026):

- **What counts.** Only a text message or an interactive reply (button, list or template quick-reply) whose whole text, normalised, equals a keyword. Normalising applies Unicode NFKC, lowercases, removes invisible format characters, turns punctuation and white space into a space, makes runs of space one, and trims; the keyword is normalised the same way, and one that normalises to nothing never matches. "Stop!" and "Opt-out." opt out; "Please don't stop the shoot" does not. Media captions, locations, reactions and every other type never opt out. Keywords: the platform's `stop`, `unsubscribe`, `opt out`, `optout` and `stop promotions` (the label of Meta's marketing opt-out button, a full opt-out until marketing and transactional consent are split), plus the business's own (`tenants.opt_out_keywords`).
- **Checked first,** in the inbound job's one transaction, under the contact's row lock, before anything hands the message on.
- **Effect,** in that same transaction: consent\_state `opted_out` and opt\_out\_at (WhatsApp's timestamp of the message) the first time only; every pending (due, scheduled or waiting for a retry), needs\_review and paused action whose recipient is the contact cancelled with reason `contact_opted_out`; the open lead joined (and linked to the conversation if it had none), its stage unchanged, and no lead ever opened by an opt-out message; the message stored in the conversation as usual; `optout.received` recorded instead of `message.received`, so the engine and the AI never see it as conversation. An attempt already processing cannot be recalled; its next claim is refused.
- **Refused afterwards,** at three points: creating an action for the contact stores it cancelled (`contact_opted_out`) and never queues it; claiming one cancels it without calling its handler; and the database refuses a runnable action for an opted-out recipient (`actions_refuse_opted_out`, NSA01). Every action handler declares whether it messages a contact, and such a type cannot be created without its recipient.
- **Final.** No step can clear an opt-out yet (`contacts_consent_guard`, NSC01); opting back in is a later step.
- **Logs** carry IDs, codes and counts only, never the text or the keyword matched. No confirmation message is sent (none in step 2.6a either; its wording would need approval).
- **A cancelled send** (step 2.6a): its queued message is marked `failed` with `contact_opted_out` in the same transaction as the cancellation, wherever that happens (at creation, at claim, or by the opt-out). A send already processing when the opt-out lands is not recalled; if Meta accepts it, its message stays `sent`.

The one exception, step 2.9b (decided 22 Nov 2026):

- **What is allowed.** A contact who opted out and then writes again may get a reply from a staff member (an admin or staff, through the inbox), and only free text: no template, and nothing from the AI or the system. It is allowed only inside the 24-hour window that message opens, with the sender's 2-minute margin: until 24 hours after the client's latest message (`conversations.last_contact_message_at`), and only while that message is newer than their latest opt-out (`contacts.last_opt_out_at`). Each such reply is audited as `message.sent_after_opt_out`. Consent stays `opted_out`; opting back in is still a later step.
- **What counts as writing again.** Every inbound message but a reaction or a system message (the ones that never open a lead either, step 2.4b). A message in the same second as the latest opt-out does not count (WhatsApp's timestamps are in seconds). The times are WhatsApp's, so a late or redelivered webhook never reorders them.
- **Another opt-out closes it.** `opt_out_at` keeps the first opt-out; `last_opt_out_at` moves forward with every later one. A STOP after the client wrote again cancels a reply still waiting to be sent (as every opt-out cancels the contact's pending actions) and refuses new ones until the client writes again.
- **Checked three times,** as every send is: when the reply is requested (409 `contact_opted_out`, nothing stored), when its action is created and when it is claimed (cancelled with `contact_opted_out` once the window has passed or the client opted out again). The database's backstop (`actions_refuse_opted_out`, NSA01) refuses a runnable action for an opted-out recipient unless it is a `whatsapp.send` of exactly such a message, through the function `northstar.staff_reply_allowed_after_opt_out`, which the app calls too, with the margin. Only the inbound job writes the two times (`contacts_consent_guard`, NSC01; `conversations_inbound_times_guard`, NSV01).

**Take over and hand back** (step 2.9c, decided 22 Nov 2026; the per-conversation kill switch of build-document.md section 15)

1. An admin takes over any conversation of the business, a staff member their assigned ones (through the inbox). Taking over pauses automation for the conversation (`conversations.automation_paused`) and records who and when; a conversation already taken over is left as it is, its first taker and time kept.
2. In the same transaction, the conversation's automated sends not yet sent (from the system or the AI; pending, waiting for review, or paused for auth or a blocked template) are cancelled as `conversation_taken_over` and their messages failed with that code. Staff replies continue, and a send already being attempted is left to finish.
3. While it is taken over, queueing refuses automated sends to the conversation, and the engine (Phase 3) and the AI (Phase 4) do not act on it. Inbound messages, staff replies and timers never hand it back.
4. Only a hand back resumes automation; nothing it cancelled is re-queued. Handing back a conversation that is not taken over changes nothing.
5. Each take over and hand back writes an audit entry (`conversation.taken_over` / `conversation.handed_back`, from and to `automated` and `taken_over`) and, when the conversation's contact has an open lead the member can see, a `staff.action` event on it with its fixed reason (`conversation_taken_over` / `conversation_handed_back`): the engine acts on leads only.

## 14. Industry templates

Four templates plus Blank ship in the MVP; each is the same step types in a different order with different defaults, and the business edits its copy.

| Template | Step order | Payment defaults | Notable settings |
| --- | --- | --- | --- |
| Shoot production (Reelrr) | qualify → score → schedule check → quote → payment (advance) → invoice → assign → job (shoot, editing) → delivery → payment (balance) → invoice → review → close | Advance 50%, link expiry 24 h | Questions: event type, date, location, car, deliverables, budget; crew skills |
| Agency | qualify → score → call (discovery) → quote (proposal) → payment (first retainer) → invoice → assign (account manager) → job (onboarding) → close | Full first month | Retainer renews outside MVP; proposal validity 7 days |
| AC / home service | qualify → score → site\_visit → quote → payment (advance) → invoice → assign (technician) → job (service) → payment (balance) → invoice → review → close | Advance 30% | Location zone filter required; visit duration 60 min |
| Coaching | qualify → score → call (counselling) → quote (admission offer) → payment (fee) → invoice → batch\_assign → close | Full fee or first instalment | Batch capacity check before quote |
| Blank | qualify → close | — | Business adds steps |

The advance percentages and durations above are starting defaults, not recommendations for any specific business.

**Default shoot production template** (step 3.2, decided 28 Nov 2026 by the product owner). Northstar's fixed default for `shoot_production`, built only from the defaults this spec and `commercial-rules.md` state: no question wording, catalogue, price, service zone, minimum budget, crew skill or message text. Reelrr starts on a copy of it like any business; its answers to section 18 are onboarding edits to its own copy through the admin API (section 16), with no code change.

- **How a business gets it.** Creating a workflow with template `shoot_production` (`POST /workflows`, section 16) stores the template's current revision as the new workflow's draft; the admin reviews it, edits it and publishes it like any draft. Nothing is created or published when a business is created: a business has no industry then, and with one workflow per business a workflow it did not want could not be replaced. The other templates start with no draft until each is written.
- **Revisions.** The template is a numbered file in the backend (`workflow-templates/shoot_production/<n>.json`, the highest number current). Every revision is checked against format v1 (section 15) by a test and when the app starts, so none can be handed out unpublishable. A merged revision is never edited; a change is a new revision. A workflow copies the current revision once, when it is created, and later revisions never change a business's draft or versions; adopting one is the admin's own edit. A later step may add a read of the latest revision and a replacement of the draft allowed only while the draft is still exactly an earlier revision, so an edited draft is never overwritten.
- **Revision 1 holds what format v1 can express without inventing a trigger.** Every stage must be reachable from `new` (section 15), and Scored → Quoted waits for "quote sent" (`action.succeeded`, step 3.5), so the stages after Scored join in later revisions (below).

| Stage | Step and parameters | Timers | Exits | Approval |
| --- | --- | --- | --- | --- |
| `new` New | — | — | → `qualifying` on `message.received`: the first inbound message creates a lead (section 3's "lead created"; step 3.4 decides manual leads and the "AI enabled" guard) | No |
| `qualifying` Qualifying | `qualify`: `required_fields` service, date, time, location, budget (section 4) | `follow_up` after 4 h and 24 h, counted from the timer's start, 2 attempts (section 8) | → `scored` on `ai.fields_extracted` with `all_required_fields` (section 3) | No |
| `scored` Scored | `score`: hot 75, warm 50 (section 18's assumed thresholds); `meta_lead_event` on (section 4) | — | None yet | No |
| `needs_human`, `lost` | — | — | The engine's own moves (section 3) | — |

The document sets `approval_mode` true (section 9: new businesses start in approval mode) and quiet hours 21:00–09:00 (section 8); each stage's approval is section 4's column.

Left out, as no value is stated: the qualifying questions' wording (this section names topics only: event type, date, location, car, deliverables, budget), `max_turns`, `language` and `tone` (the business's settings), requirement fields for car and deliverables (not required by section 4), filter rules and with them the Junk stage (minimum budget and service zone, section 18), the catalogue, crew skills and every message text. `meta_conversion_stage` waits for its stage, Payment pending; Cancelled waits for Booked, the only stages it is entered from. The cancellation policy is not part of the workflow: a new business gets `commercial-rules.md` section 7's bands as its default policy, editable (step 5.9).

**How the template grows.** Each step below adds its stages to a new revision; the values are ready now.

| Stage | Values (source) | Joins when |
| --- | --- | --- |
| `quoted` Quoted | `quote`: `validity_days` 7, `discount_limit_pct` 10 (`commercial-rules.md` section 3); `follow_up` after 24 h and 72 h, 2 attempts (section 8); approval yes | Scored → Quoted on the quote's send (`action.succeeded`, step 3.5), and Accept told apart from Request Changes (step 3.4) |
| `negotiating` Negotiating | — | → Quoted on a revised quote's send (step 3.5) |
| `payment_pending` Payment pending | `payment`: advance, `advance_pct` 50, `link_expiry_hours` 24 (sections 8 and 14); approval yes; `meta_conversion_stage` (section 4); → `booked` on `payment.captured`, → `quoted` on `payment.link_expired` (section 3) | With Quoted; its reminder, expiry and slot-hold timers in step 3.6 |
| `booked` Booked | `invoice` on `payment_captured` (section 4); approval yes | → Scheduled once staff are assigned and calendar events created (step 3.5) |
| `cancelled` Cancelled | — | With Booked |
| `scheduled` Scheduled | `schedule`; no approval | → In progress on the job's start or staff marking it started (steps 3.4 and 3.6); job reminders (step 3.6) |
| `in_progress` In progress | `job`: `overdue_after_hours` 4 (section 8); no approval | Staff marking it done told apart, and an amount-due condition (step 3.4); `job.tasks` (step 3.5) |
| `balance_pending` Balance pending | `payment`: balance; approval yes; → `completed` on `payment.captured` (section 3) | The amount-due guard (step 3.4); balance reminders (step 3.6) |
| `completed` Completed | `close`: `send_purchase_event` on (section 4); no approval | With In progress |
| `junk` Junk | — | With the business's first filter rule (onboarding) |

Format v1 gives a stage one step, but section 4 runs several on some stages: the availability check and the quote on Quoted, the invoice and assignment on Booked, the final invoice, review request (24 h after completion, section 8) and close on Completed; and delivery (after the job, before the balance) has no stage of its own. Step 3.4's plan proposes how a stage runs more than one step, and step 3.5 builds it.

## 15. Workflow configuration

The whole workflow is one validated JSON document per version; this abbreviated example shows the shape the engine and the editor share, and passes format v1 (below).

```json
{
  "schema_version": 1,
  "template": "shoot_production",
  "approval_mode": true,
  "meta_conversion_stage": "payment_pending",
  "quiet_hours": {"start": "21:00", "end": "09:00"},
  "stages": [
    {
      "key": "new",
      "name": "New",
      "exits": [{"to": "qualifying", "on": "message.received"}]
    },
    {
      "key": "qualifying",
      "name": "Qualifying",
      "step": "qualify",
      "params": {
        "questions": ["What is the event?", "Date and time?", "Location?", "Which car?", "What deliverables do you need?", "Your budget?"],
        "required_fields": ["service", "date", "time", "location", "budget"],
        "max_turns": 12
      },
      "timers": [{"type": "follow_up", "after_hours": [4, 24], "max_attempts": 2}],
      "exits": [{"to": "scored", "on": "ai.fields_extracted", "if": {"all_required_fields": true}}]
    },
    {
      "key": "scored",
      "name": "Scored",
      "step": "score",
      "params": {"band_thresholds": {"hot": 75, "warm": 50}, "meta_lead_event": true},
      "exits": [{"to": "quoted", "on": "staff.action"}]
    },
    {
      "key": "quoted",
      "name": "Quoted",
      "step": "quote",
      "exits": [{"to": "payment_pending", "on": "button.clicked"}]
    },
    {
      "key": "payment_pending",
      "name": "Payment pending",
      "step": "payment",
      "params": {"kind": "advance", "advance_pct": 50, "link_expiry_hours": 24},
      "approval": true,
      "exits": [
        {"to": "booked", "on": "payment.captured"},
        {"to": "quoted", "on": "payment.link_expired"}
      ]
    },
    {"key": "booked", "name": "Booked", "step": "invoice", "params": {"on": "payment_captured"}},
    {"key": "junk"},
    {"key": "needs_human"}
  ],
  "filter_rules": [{"id": "min_budget", "if": {"lt": ["budget", 1500000]}, "then": "junk", "reason": "Below minimum budget"}]
}
```

The example is abbreviated and its triggers are illustrative. How New moves to Qualifying is step 3.4's. Scored moves on a staff action only because v1 has no way yet to say "when the quote is sent": step 3.5 restores `{"to": "quoted", "on": "action.succeeded", "action": "send_quote"}` properly, adding `action.succeeded` to section 6 and naming each step's actions. Until then v1 refuses both (`event_unknown`, `action_unknown`). Its values are illustrative: Northstar's default `shoot_production` template is section 14's (step 3.2).

Published as version 3 of a workflow, the stored document also carries `"workflow_id"` (the workflow's UUID) and `"version": 3`, which publishing writes (section 16); a draft never contains them.

**Format v1** (step 3.1, decided 27 Nov 2026). The backend checks every draft save and every publish with the same rules (`ConfigFormatV1` in the `workflow` module). The JSON Schema the editor validates against is generated from those rules when the editor is built, so the two cannot disagree. Every member not listed here is refused, at every level, step parameters included.

| Object | Members (✱ required) |
| --- | --- |
| Document | `schema_version`✱: the integer 1. `template`: the industry template (section 14), one of `shoot_production`, `agency`, `ac_home_service`, `coaching` and `blank`, and the workflow's own. `approval_mode`: a boolean. `meta_conversion_stage`: a stage key. `quiet_hours`: `{"start"✱, "end"✱}`, 24-hour `HH:MM`, start and end different. `stages`✱: a non-empty list. `filter_rules`, `step_conditions`: lists (section 7). `workflow_id`, `version`: written by publishing only |
| Stage | `key`✱: 1–50 lowercase letters, digits and `_`, starting with a letter; unique. `name`: 1–60 characters, not blank; required except on the reserved stages. `step`: a step type (section 5). `params`: that step's parameters (below), only with a `step`. `timers`, `exits`: lists. `approval`: a boolean (section 9; how it combines with `approval_mode` and the business's own setting is step 3.7's) |
| Exit | `to`✱: a declared stage, not the exit's own. `on`✱: an event of section 6 except `optout.received` (an opt-out never changes stage, section 3). `if`: a condition (section 7). `action`: no action names exist in v1, so any value is refused (step 3.5) |
| Timer | `type`✱: `follow_up` (section 8's other timers arrive with their steps, step 3.6); at most one of a type per stage. `after_hours`✱: a non-empty, strictly increasing list of positive integers. `max_attempts`✱: an integer from 1 to the number of `after_hours` |
| Filter rule | `id`✱: in stage-key form; unique. `if`✱: a condition. `then`✱: `junk`, which must be a declared stage. `reason`✱: 1–200 characters, not blank. Filter rules need a stage whose step is `score`; there is no `rules` parameter |
| Step condition | `step`✱: a step type some stage uses, or `payment.<kind>` for a payment stage of that kind. Exactly one of `enabled_if` and `skip_if`: a condition |

**Stages.** Every config has `new`, where every lead starts (step 2.4b), and `needs_human`, where the engine can send any lead (a take over, an AI escalation, a failed action, section 16's unmappable stages). Six keys are reserved, with fixed meanings and default names used when `name` is omitted; a business may rename them: `needs_human` (Needs Human), `negotiating` (Negotiating), `junk` (Junk), `lost` (Lost), `cancelled` (Cancelled) and `completed` (Completed). `junk`, `lost` and `completed` close a lead (section 3; whether Cancelled does is step 3.3's). The list order is the stage order. A stage may have no exits.

**Reachability.** Every stage must be reachable from `new`, through exits' `to`, filter rules' `then` (from the stage whose step is `score`) and the engine's own moves from any open stage to `needs_human`, `lost` and `cancelled` (section 3). Needs Human's way back to the previous stage is the engine's and is not written as an exit.

**Step parameters.** Section 5's key parameters are the complete list for v1. Every number is an integer.

| Step | Parameters |
| --- | --- |
| `qualify` | `questions`: texts of 1–500 characters. `required_fields`: distinct lead fields (section 7). `max_turns` ≥ 1. `language`: 1–35 characters. `tone`: 1–200 characters |
| `score` | `band_thresholds`: `{"hot"✱, "warm"✱}`, each 0–100, hot above warm. `meta_lead_event`: a boolean |
| `call` | `duration_min` ≥ 1. `staff_skill`: 1–50 characters. `calendar_required`: a boolean |
| `site_visit` | `duration_min` ≥ 1. `staff_skill`: 1–50 characters. `location_required`: a boolean |
| `quote` | `validity_days` ≥ 1. `discount_limit_pct`: 0–100 |
| `payment` | `kind`✱: `advance`, `full`, `balance` or `fee`. `advance_pct`: 1–100, required with `advance` and allowed only with it. `link_expiry_hours` ≥ 1 |
| `invoice` | `on`: `payment_captured` or `manual` |
| `schedule` | `duration_min` ≥ 1. `buffer_min` ≥ 0 |
| `assign` | `strategy`: `least_busy`, `round_robin` or `skill_match`. `required_skill`: 1–50 characters |
| `job` | `overdue_after_hours` ≥ 1 |
| `batch_assign` | `capacity` ≥ 1 |
| `delivery` | `requires_approval`: a boolean |
| `review_request` | `delay_hours` ≥ 0 |
| `close` | `send_purchase_event`: a boolean |

Not in v1, until the step that builds each executor defines its shape: `catalogue_filter`, `score.rules`, `job.tasks`, `batch_assign.batches`, `invoice.template_id`, `schedule.slot_hold` and `calendars`, `delivery.link_field` and `review_request.review_url`; and `approval` as a parameter, as it is the stage's. No WhatsApp message template is named in v1: the steps that send timed messages add one (steps 3.5 and 3.6), and publishing then checks it against the business's `message_templates`. Text lengths count characters (Unicode code points).

**Problems.** Validation reports every problem at once, each as `{"path", "code"}`, in document order: at most 500, then one `too_many_problems` at `$`. The path starts at `$`, the document, with `.name` for a member whose name is letters, digits and `_` not starting with a digit, `['name']` for any other (with `\` and `'` escaped), and `[n]` for a list item, counted from 0; a missing member's path names it. At publish the document is the stored draft, whose members the database keeps in its own order.

| Code | Meaning |
| --- | --- |
| `json_invalid` | Not JSON, or JSON the database cannot store: U+0000, a lone surrogate, a number of more than 1,000 digits |
| `json_member_duplicate` | An object names a member twice |
| `config_too_large` | More than 256 KB (262,144 bytes) of UTF-8 text |
| `config_not_object` | The document is not a JSON object |
| `schema_version_unsupported` | `schema_version` is an integer other than 1 |
| `field_required` | A required member is missing |
| `field_unknown` | A member the format does not have (also `params` without a `step`) |
| `field_server_set` | `workflow_id` or `version` in a draft |
| `type_invalid` | The wrong JSON type: null included, and a fraction where an integer belongs |
| `value_invalid` | The right type but not allowed: its form, length or range; an order (`after_hours`, hot above warm); an exit to its own stage; a filter rule's `then` other than `junk`; filter rules without a score stage; both `enabled_if` and `skip_if`; a `template` other than the workflow's |
| `stage_new_missing` | No stage `new` |
| `stage_needs_human_missing` | No stage `needs_human` |
| `stage_key_duplicate` | A stage key used by an earlier stage |
| `stage_unknown` | A reference to an undeclared stage: an exit's `to`, a filter rule's `then`, `meta_conversion_stage` |
| `stage_unreachable` | A stage `new` cannot reach (not checked when `new` is missing) |
| `step_unknown` | Not a step type, or a step condition naming a step no stage uses |
| `event_unknown` | Not an event of section 6 |
| `event_not_allowed` | `optout.received` as an exit's trigger |
| `action_unknown` | An exit's `action` (none exist in v1) |
| `template_unknown` | Not one of the five industry templates |
| `timer_type_unknown` | Not `follow_up` |
| `timer_duplicate` | A second timer of one type in a stage |
| `condition_operator_unknown` | Not an operator of section 7 |
| `condition_field_unknown` | Not a field of section 7, or a workspace setting (none exist yet) |
| `condition_invalid` | Malformed: not exactly one operator, the wrong operands or values, deeper than 10 levels, `all_required_fields` outside an exit of a stage with `required_fields` |
| `filter_rule_id_duplicate` | A filter rule ID used by an earlier rule |

**Drafts may be incomplete.** An admin builds a workflow a stage at a time, so saving a draft stores it and returns its problems as warnings; only the first four codes refuse a save. Publishing refuses on any problem. Validation is not retroactive: a version published before step 3.1 stays as it is, and its leads keep it (W36).

## 16. Versioning and migration

Publishing creates a new immutable version; existing leads stay on their version unless an admin migrates them explicitly.

- **Draft → Publish:** edits happen on a draft; publish validates the schema, creates version n+1 and makes it the default for new leads.
- **Existing leads:** keep running on the version they started with, including its timers and approval settings.
- **Migration:** admin selects leads (or all leads in given stages) and a target version; the engine maps each lead's stage by stage key; any lead whose stage no longer exists goes to Needs Human with a note. Migration is logged per lead.
- **Rollback:** an older version can be re-published as the newest version; nothing is deleted.
- **Setting changes outside the workflow** (catalogue prices, GST details, staff list) apply immediately and are not versioned, except that a quote already sent keeps its own prices.

How step 3.1 builds publishing (decided 27 Nov 2026; the format is section 15):

- **One workflow per business** for MVP (`data-model.md` section 15). Creating a second is refused (409 `workflow_exists`), and so is publishing while another workflow of the business is published (409 `another_workflow_published`, for rows made outside the service). The workflow service enforces both under a per-business transaction advisory lock; there is no database constraint, so allowing several later is a service change. A new workflow names its industry template (section 14), and its name is 1–100 characters. One created from `shoot_production` starts with that template's current revision as its draft (step 3.2, section 14); the others start with none.
- **Only admins and the system actor** create, edit and publish workflows (staff: 403 `admin_only`); the database's restrictive policies back it (`data-model.md` section 12). Another business's workflow is a 404.
- **Drafts** live in `workflows.draft_config`. Saving one stores it as sent and returns its problems as warnings, with the draft's hash (SHA-256 of the stored JSON text).
- **Publish** takes the hash of the draft the admin reviewed: a different one is 409 `draft_changed`, no draft 409 `draft_missing`. It validates the stored draft, and any problem is 422 `workflow_config_invalid` with every problem (`details.problems`). Then, in one transaction: version n+1 is inserted with `workflow_id` and `version` written into its document, published by the admin's own membership (none for the system actor); the workflow's current version moves to it; and the audit entry `workflow.published` is written. Leads are not touched: new leads take the new current version, existing ones keep theirs (W36). The draft stays as the starting point for the next edit, and publishing an unchanged draft again makes another version.
- **Immutable in the database**, for every role, the owner included: a published version is never updated, deleted or truncated (NSW01); a workflow's current version, once set, never becomes null again and moves only to that workflow's newest version (NSW02), so a rollback is always a re-publish; and a lead's `workflow_version_id` never changes (NSL01) until migration (step 3.11) allows an admin's explicit move.

The admin API (step 3.1b, decided 27 Nov 2026), under `/api/v1`, for admins only: staff get 403 `admin_only` before the Idempotency-Key or a config is looked at (a JSON request body that does not parse is a 400 first, as on every endpoint), and another business's workflow is a 404, exactly like a missing one (P9).

| Method and path | Idempotency-Key | Body | Answer |
| --- | --- | --- | --- |
| `GET /workflows` | — | — | 200 `{"items": [...]}`: the business's workflows (at most one in MVP), each without its draft |
| `POST /workflows` | Required | `{"name", "template"}` | 201 the workflow, with `Location` (for `shoot_production` its draft is the template's current revision, step 3.2; for the others there is none); 409 `workflow_exists`; 422 `workflow_name_invalid`, `template_unknown` |
| `GET /workflows/{workflowId}` | — | — | 200 the workflow with its draft |
| `PUT /workflows/{workflowId}/draft` | Required | The config itself | 200 `{"draftHash", "warnings"}`; 422 `workflow_config_invalid` for a draft that cannot be stored |
| `POST /workflows/{workflowId}/validate` | — | The config itself | 200 `{"problems"}`, even for text that is not JSON; nothing is stored |
| `POST /workflows/{workflowId}/publish` | Required | `{"draftHash"}` | 201 the version, with `Location`; 409 `draft_changed`, `draft_missing`, `another_workflow_published`; 422 `workflow_config_invalid` with every problem |
| `GET /workflows/{workflowId}/versions/{version}` | — | — | 200 the published version |

- **A workflow** is `id`, `name`, `template`, `currentVersionId`, `currentVersion`, `draft`, `draftHash`, `createdAt` and `updatedAt`; **a version** is `id`, `workflowId`, `version`, `config`, `publishedBy` and `publishedAt`. Drafts and configs are the JSON exactly as stored; problems and warnings are `[{"path", "code"}]` (section 15), and a refusal carries them in `details.problems`.
- **Config bodies** are the config itself, read as sent and never by the app's strict mapper, so a member named twice is reported rather than silently dropped. They are JSON (else 415), at most 256 KB, read no further (more is 422 `workflow_config_invalid` with `config_too_large`), and UTF-8 (else `json_invalid`); those two answers depend only on the bytes, so they are the same for every workflow ID. Every other problem comes after the workflow is found.
- **Idempotency-Key** on the three writes (P21): required (400 `idempotency_key_required` or `idempotency_key_invalid`, after the admin check) and stored in `request_keys` (`data-model.md` section 3), per member and operation, in the transaction that does the write, so a write that fails keeps no key. A repeat answers 200: a create with the workflow as it is now, a publish with the version it made (never a second one), and a draft save with its first answer recomputed from the same body, without saving it again, so a late retry never overwrites a newer draft. The same key with another request is 422 `idempotency_key_reused`.

## 17. Test scenarios

Each scenario becomes an automated end-to-end test on staging with a test WhatsApp number and Razorpay test mode; Phase 3 is done only when all pass.

- [ ] Happy path: new lead → all 10 stages → Completed, with 50% advance and balance
- [ ] Full payment upfront: Balance pending skipped
- [ ] Lead fails budget rule → Junk with rule ID and reason; staff restores it
- [ ] AI low confidence → Needs Human; staff hands back; flow resumes at the same stage
- [ ] No reply in Qualifying: 2 follow-ups, then staff task
- [ ] Request Changes → Negotiating → v2 sent → v1 can no longer be accepted
- [ ] Discount above limit blocked until admin approves
- [ ] Payment link expires → slot released → fresh link → paid → Booked
- [ ] Payment on expired link after slot taken → Needs Human
- [ ] Same Razorpay webhook sent 3 times → one ledger entry, one invoice
- [ ] Overpayment → refund-due flag
- [ ] No staff free at requested slot → Needs Human, no quote sent
- [ ] Assigned staff marked on leave → appointment flagged and reassigned
- [ ] Cancellation after payment → refund per policy with owner approval → credit note
- [ ] Reschedule → calendars and reminders move; stage unchanged
- [ ] STOP received → all pending timers cancelled; no further automated sends
- [ ] Message due outside 24-hour window → template used
- [ ] Approval mode: quote waits in Needs Review; approve, edit and reject paths
- [ ] Step switched off (site visit) → workflow skips it cleanly
- [ ] Workflow v2 published → in-flight leads stay on v1; migration moves selected leads
- [ ] Google Calendar down → booking still recorded; events created after reconnect
- [ ] Two businesses run the same scenario in parallel → no data crosses tenants

## 18. Open questions

Decided 28 Nov 2026 (product owner): Phase 3 does not wait for these. Step 3.2 builds Northstar's default `shoot_production` template from the defaults this spec states (section 14), and Reelrr starts on a copy of it like any business. Reelrr's answers become onboarding edits to its own copy through the admin API (section 16), with no code change, so the questions stay open as onboarding inputs, not Phase 3 blockers.

- [ ] Reelrr's exact qualifying questions and which fields are required
- [ ] Advance percentage (50% assumed) and payment link expiry (24 h assumed)
- [ ] Minimum budget and service zone for filter rules
- [ ] Score thresholds for Hot (75) and Warm (50)
- [ ] Crew skills used for assignment (camera, drone, editor…)
- [ ] Is editing a separate tracked task with its own deadline and assignee?
- [ ] Is delivery held until the balance is paid?
- [ ] Quiet hours (21:00–09:00 assumed)
- [ ] Cancellation and refund policy wording
- [ ] Which actions Reelrr wants in approval mode at launch
