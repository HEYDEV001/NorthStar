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
| `message.status` (sent, delivered, read, failed) | WhatsApp webhook | message ID + status |
| `button.clicked` (Accept, Request Changes, confirm, reschedule) | WhatsApp interactive reply | message ID |
| `optout.received` (STOP and configured keywords) | WhatsApp message | message ID |
| `ai.fields_extracted` | AI worker | lead ID + turn |
| `ai.escalation` | AI worker | lead ID + turn |
| `payment.captured` | Razorpay webhook | Razorpay payment ID |
| `payment.link_expired` | Razorpay webhook or timer | link ID |
| `payment.failed` | Razorpay webhook | payment ID |
| `refund.processed` | Razorpay webhook | refund ID |
| `timer.fired` (follow-up, reminder, expiry, hold release) | Job queue | timer ID |
| `staff.action` (approve, reject, mark done, take over, hand back, move stage, reassign, cancel) | Dashboard / WhatsApp | request ID |
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
    {"id": "min_budget", "if": {"lt": ["budget", 15000]}, "then": "junk", "reason": "Below minimum budget"}
  ],
  "step_conditions": [
    {"step": "site_visit", "enabled_if": {"gt": ["budget", 50000]}},
    {"step": "payment.advance", "skip_if": {"eq": ["is_repeat_customer", true]}}
  ]
}
```

**Rules**

- Conditions are validated when the workflow is saved; an unknown field or operator blocks publishing.
- A missing field evaluates as false (never an error), except `exists`.
- Junk from a filter rule always stores the rule `id` and `reason` on the lead.

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

**Cancellation of timers:** any reply from the client, a stage change, opt-out, takeover or cancellation re-checks and cancels pending timers that no longer apply.

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

**Statuses:** Pending → Processing → Succeeded | Failed | Needs Review (approval or manual check) | Skipped (staff chose to skip) | Cancelled (no longer applies).

**Idempotency key** = `tenant_id + lead_id + action_type + stage_entry_id (+ version or amount where relevant)`. Before calling a provider, the worker checks whether an action with that key already succeeded; providers that support idempotency headers get the same key.

| Action type | Retry policy | On final failure |
| --- | --- | --- |
| WhatsApp send | 5 attempts, backoff 30 s → 1 m → 5 m → 15 m → 1 h | Failed; staff alerted; if outside 24-hour window, retry with template |
| Razorpay link create | 5 attempts, same backoff | Failed; lead stays in stage; staff alerted |
| Invoice generate + send | 5 attempts | Failed; payment record unaffected; retry from Failed Actions |
| Google Calendar write | 8 attempts up to 6 h | Failed; platform calendar still correct; resync on reconnect |
| Sheets row upsert | Batched; retry until success, max 24 h | Integration marked error; outbox kept |
| Meta CAPI event | 5 attempts up to 24 h | Failed; logged for admin view |
| AI call | 3 attempts | Escalate lead to Needs Human |

**Failed Actions screen:** shows lead, action, error, attempts, last try; buttons Retry, Skip (with reason), Open lead.

**Alerts:** same action failing 3 times, or any integration failing for more than 30 minutes, notifies the business admin; repeated failures across tenants notify the Northstar admin.

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
- The sender checks the window at send time; outside it, it switches to the matching template or fails the action with a clear reason.
- Opt-out (STOP and configured keywords) blocks all automated and promotional messages immediately; replies the client starts later are still answered.
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
3. Stage unchanged; staff see an Opted out badge; transactional replies only if the client writes first.

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

## 15. Workflow configuration example

The whole workflow is one validated JSON document per version; this abbreviated Reelrr example shows the shape the engine and the editor share.

```json
{
  "workflow_id": "wf_reelrr",
  "version": 3,
  "template": "shoot_production",
  "approval_mode": true,
  "meta_conversion_stage": "payment_pending",
  "quiet_hours": {"start": "21:00", "end": "09:00"},
  "stages": [
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
      "step": "score",
      "params": {"band_thresholds": {"hot": 75, "warm": 50}, "meta_lead_event": true},
      "exits": [{"to": "quoted", "on": "action.succeeded", "action": "send_quote"}]
    },
    {
      "key": "payment_pending",
      "step": "payment",
      "params": {"kind": "advance", "advance_pct": 50, "link_expiry_hours": 24},
      "approval": true,
      "exits": [
        {"to": "booked", "on": "payment.captured"},
        {"to": "quoted", "on": "payment.link_expired"}
      ]
    }
  ],
  "filter_rules": [{"id": "min_budget", "if": {"lt": ["budget", 15000]}, "then": "junk"}]
}
```

The editor validates this document against a JSON Schema kept in the repo; publishing fails on any unknown step, field, stage key or unreachable stage.

## 16. Versioning and migration

Publishing creates a new immutable version; existing leads stay on their version unless an admin migrates them explicitly.

- **Draft → Publish:** edits happen on a draft; publish validates the schema, creates version n+1 and makes it the default for new leads.
- **Existing leads:** keep running on the version they started with, including its timers and approval settings.
- **Migration:** admin selects leads (or all leads in given stages) and a target version; the engine maps each lead's stage by stage key; any lead whose stage no longer exists goes to Needs Human with a note. Migration is logged per lead.
- **Rollback:** an older version can be re-published as the newest version; nothing is deleted.
- **Setting changes outside the workflow** (catalogue prices, GST details, staff list) apply immediately and are not versioned, except that a quote already sent keeps its own prices.

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

These defaults need Reelrr's confirmation before Phase 3 starts.

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
