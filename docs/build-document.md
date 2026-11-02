# Northstar Build Document

Oct 22, 2026 · @Abhishek

## 1. Overview

Northstar is a multi-tenant WhatsApp-first business OS that takes a lead from first message to paid, scheduled and completed work with AI and automation. Every lead source opens a WhatsApp chat; the AI qualifies and scores the lead, sends a quote, collects payment through Razorpay, issues a GST invoice, books free staff from their Google Calendars, collects the balance, and feeds conversions back to Meta so the next ads bring better leads.

**Who it is for:** small and mid-size Indian service businesses that sell through WhatsApp and ads: shoot production, agencies, home services, coaching and similar.

**First customer:** Reelrr (shoot production). Reelrr is the reference workflow, not the product boundary; the platform is configurable for any industry.

**Product goals**

- One record per customer from first message to final payment
- Hands-free lead handling, with staff approval where the business wants it
- Ads optimised on real buyers, not just chats
- Live visibility in a dashboard and Google Sheets

**Domains:** `northstar.com` (marketing site), `app.northstar.com` (the platform), `northstar.com/c/<code>` (tracked chat links).

## 2. Scope

The MVP covers the full lead-to-payment loop for any business on WhatsApp; visual workflow building, voice calls and extra lead platforms come later.

| Area | MVP (pilot) | Phase 2 |
| --- | --- | --- |
| Lead sources | WhatsApp only: Meta click-to-WhatsApp ads, organic WhatsApp, tracked chat links and QR codes for website, email, social media | IndiaMART, voice calls, direct website forms |
| Workflow | Configurable stages, step on/off, 4 industry templates + blank, simple conditions, approval mode toggle | Visual drag-and-drop builder, complex branching |
| AI | Qualifying conversation, extraction, rules + AI scoring with reasons, hand-off to staff | Voice agent on the same workflow |
| Commercial | Catalogue, quote versions, accept / request changes, discount approval, Razorpay links, payment ledger, GST invoices in the business's own template, credit notes, refunds with owner approval | Line-item partial approval, automatic refund rules, e-invoicing (IRN) |
| Scheduling | Platform calendar + Google Calendar (business and staff), free/busy, slot hold, auto-assignment, reassignment, leave | Route planning |
| Integrations | Google Sheets (Leads + Payments tabs), Meta Conversions API, Meta Marketing API, email | More connectors |
| Reliability | Action statuses, retries, failed-actions screen, alerts, integration health | Advanced monitoring |
| Dashboards | Business, staff, Northstar admin, lead score dashboard, customer timeline, reports | Cross-tenant benchmarking |

**Explicitly out of MVP:** voice calling, mass broadcast campaigns, accounting ledger, payroll/HR, inventory, custom mobile apps.

## 3. Users, roles and permissions

Three roles: the business admin controls everything in their workspace, staff see only their own work, and the Northstar admin runs the platform.

| Capability | Business admin | Staff | Northstar admin |
| --- | --- | --- | --- |
| Leads | All leads | Assigned leads only | Support view only |
| Calendar | All staff, combined view | Own calendar only | — |
| Conversations | All, take over any | Assigned only | — |
| Quotes, payments, invoices | Full control, approvals | View for own jobs | — |
| Team | Invite, change role, deactivate, mark leave | — | — |
| Settings, workflow, integrations | Full control | — | — |
| Tenants, usage, failed jobs, pause | — | — | Full control |

**Staff invites**

1. Admin enters the staff email and role under Team.
2. Staff receives an invite email (expires after 7 days), sets a password, logs in.
3. On first login, staff connects their own Google Calendar.
4. Admin can resend, change role or deactivate at any time; deactivation removes access immediately and keeps past records.

**Privacy rule:** personal Google Calendar events show to the admin as "Busy" only, without title or details. Platform-created jobs show in full.

## 4. Lead sources and attribution

WhatsApp is the only entry point: every channel carries a WhatsApp chat button, and the platform tags each lead with where it came from.

| Source | How it reaches WhatsApp | How the source is captured |
| --- | --- | --- |
| Meta click-to-WhatsApp ads | Ad opens a chat | Ad ID, campaign and click ID arrive with the first message (referral payload) |
| Website, email, Instagram/Facebook bio, other | Tracked chat link `northstar.com/c/<code>` or its QR code | Link logs the click (source, UTM tags, page), then opens WhatsApp with a pre-filled message carrying a short reference code; the platform matches the code on arrival |
| Direct message, no link | Customer messages the number | Tagged "Direct / Organic" |

**Chat Links section (dashboard)**

- Create, name and archive links; download QR codes.
- Per link: clicks, leads, average score, conversions, revenue.

**Duplicate check:** a new message from a phone number that already exists in the workspace attaches to the existing contact; the new source is added to the contact's source history.

## 5. End-to-end automated workflow

A lead moves through ten stages from first message to closed, with staff availability checked before the quote and the Meta conversion event fired when the payment link goes out.

&#91;embedded content: lead workflow · 10 stages, 4 side stages\]

Dashed boxes are side stages a lead can drop into; staff availability is checked at Quoted, and the Meta pre-final event fires when the payment link goes out.

| # | Stage | What happens | Done by |
| --- | --- | --- | --- |
| 1 | New | Lead messages on WhatsApp; source captured; duplicate check by phone | System |
| 2 | Qualifying | AI asks the business's qualifying questions; collects service, date, time, location, budget, requirements | AI |
| 3 | Scored | Filter rules first (fail → Junk); AI score 0–100 with reasons → Hot / Warm / Cold | Rules + AI |
| 4 | Quoted | Free staff checked for the requested slot; only open slots offered; quote PDF from catalogue sent on WhatsApp | System (staff approve in approval mode) |
| 5 | Payment pending | Client accepts; Razorpay link (advance or full) sent; slot held; **Meta pre-final conversion event fires** | System |
| 6 | Booked | Razorpay confirms payment; GST invoice sent; free staff auto-assigned | System |
| 7 | Scheduled | Event created in platform calendar and Google Calendar (business + staff); reminders to client and staff | System |
| 8 | In progress / done | Staff marks the task complete from dashboard or WhatsApp | Staff |
| 9 | Balance pending | If the first payment was partial, balance link sent; reminders until paid | System |
| 10 | Completed | Balance paid; final invoice, thank-you and review request sent; Purchase event with value sent to Meta | System |

**Side stages:** Needs Human (AI unsure, negotiation, complaint) · Negotiating (client asked for changes) · Junk (failed filter rules) · Lost (declined or silent after follow-ups) · Cancelled.

**Branches**

- No reply → automated follow-ups (default maximum 2) → task for staff.
- Request changes on quote → Negotiating → staff revises → new quote version.
- Payment link expires → slot released → one reminder with a fresh link.
- Payment after slot release → availability re-checked → confirm, or alert staff to reschedule.
- Balance unpaid after work → scheduled reminders → escalation to staff; delivery hold optional per business.

**Approval mode (per business toggle)**

- **Approval mode (default for new clients):** AI drafts quotes, payment links, bookings and non-template messages; staff tap Approve.
- **Automatic mode:** the same actions go out without approval, still within the rules (price limits, discount limits).
- Business admin switches modes in Settings; every action records which mode it ran under.

## 6. Configurable workflow engine

One engine runs every business; each business picks a template and switches steps on or off, so a new industry needs configuration, not new code.

**Building blocks**

- **Step types:** qualify, score, discovery call, site visit, counselling, quote, approval, advance payment, full payment, schedule, assign staff, service/job, batch assignment, delivery, balance payment, review request, close.
- **Per stage:** name, order, required fields, allowed next stages, automation triggers, message templates, owner/queue, timers, approval required (yes/no).
- **Simple conditions:** rules on lead fields, e.g. "budget > ₹50,000 → add site visit", "repeat customer → skip advance".
- **Versions:** a published workflow is locked; leads in progress keep the version they started on unless an admin migrates them.

**Industry templates**

| Template | Flow |
| --- | --- |
| Shoot production (Reelrr) | The 10-stage flow in section 5 |
| Agency | Enquiry → Discovery Call → Proposal → Retainer → Onboarding |
| AC / home service | Enquiry → Site Visit → Quote → Advance → Service |
| Coaching | Enquiry → Counselling → Admission → Fee Payment → Batch Assignment |
| Blank | Build from scratch |

**MVP boundary:** a form-based editor (pick steps, set order, set conditions). The drag-and-drop visual builder is Phase 2.

## 7. AI agent and lead scoring

The AI talks, extracts and scores, but it only proposes actions; the workflow engine checks rules and permissions before anything is executed.

**What the AI does**

- Holds the qualifying conversation in the business's tone and language.
- Extracts structured fields (service, date, time, location, budget, requirements) with a confidence value and the message it came from.
- Answers only from the business's approved FAQ and catalogue.
- Hands off to staff when unsure, when the client negotiates or complains, or on low confidence.

**Allowed tools (typed, validated, logged):** `get_lead`, `update_allowed_fields`, `propose_stage_change`, `check_availability`, `draft_quote`, `create_callback_task`, `escalate_to_human`.

**Never allowed:** access another business's data, run database queries, mark payments paid, issue refunds, confirm bookings or send quotes outside the rules, follow instructions inside customer messages that try to change its behaviour.

**Lead scoring**

1. **Filter rules (set by the business):** location zone, minimum budget, service type, spam keywords. A failed rule → Junk (kept, not deleted, so staff can review).
2. **AI score (0–100)** on leads that pass, with stated reasons, e.g. "budget matches, date available, location outside core zone".
3. **Bands:** Hot, Warm, Cold, Junk; thresholds set per business.
4. **Score history** stored whenever the score changes during the conversation.

**Lead score dashboard:** distribution by band, average score by source and by Meta campaign/ad, conversion rate by band, hot leads not yet contacted.

**Kill switch:** AI can be paused per conversation (staff takeover), per business and platform-wide.

## 8. Commercial module

Every price the client accepts is tied to an exact quote version, every rupee is a ledger entry, and invoices follow GST rules, including credit notes for cancellations.

**Quotes**

- Built from the business's service catalogue as line items (price, HSN/SAC, tax rate).
- Every revision is a new version (v1, v2, v3); old versions stay readable.
- Client sees **Accept** / **Request Changes** buttons on WhatsApp. Accept links to that exact version; Request Changes moves the lead to Negotiating and hands it to staff.
- Discounts above a business-set limit need admin approval before the revised quote goes out.
- Partial approval (accept some line items) is Phase 2 unless pilot clients need it.

**Payment ledger**

- Every payment is its own record: type (full, advance, partial, balance) and status (pending, paid, failed, expired, refunded).
- Amount due is always calculated from quote total minus paid amounts, never typed.
- Razorpay Payment Links created per payment; paid status comes only from Razorpay's verified webhook, never from AI or a manual guess.
- Failed payment → fresh link sent; due-date reminders on a schedule.

**Invoices**

| Item | Rule |
| --- | --- |
| Lifecycle | Draft → Issued → Partially Paid → Paid → Overdue → Cancelled |
| Cancellation | An issued invoice is never deleted; it is cancelled with a credit note |
| Numbering | Per business, per financial year, no gaps |
| Tax | CGST + SGST when client is in the same state, IGST when different; HSN/SAC and rate per service |
| Business details | GSTIN, legal name, billing address, state |
| Template | Business uploads its own Word (.docx) design with placeholders (e.g. `{{client_name}}`, `{{gst_amount}}`); field-mapping screen + preview. Default GST template for businesses without one. PDF-only businesses: Northstar team converts during onboarding |
| Delivery | PDF sent on WhatsApp and stored in the customer timeline |

Tax rules must be reviewed by a chartered accountant before launch. Mandatory e-invoicing (IRN) for larger businesses is Phase 2.

**Refunds**

- Cancellation policy set per business (e.g. full refund 48+ hours before, 50% within 48 hours, none same day).
- Refund amount calculated from the policy; owner approves; processed via Razorpay Refunds API; recorded in the ledger with a credit note.

## 9. Scheduling and calendar

The platform calendar is the master record, mirrored to Google Calendar; availability is checked before quoting, so a client never pays for a slot nobody can serve.

**Availability and booking**

1. At the quote stage, the system reads free/busy from every eligible staff member's Google Calendar plus platform jobs and leave.
2. Only open slots are offered to the client.
3. When the payment link goes out, the slot is held until the link expires.
4. On payment, free staff are auto-assigned (by skill and workload), and the event is created in the platform calendar, the business Google Calendar and the staff member's Google Calendar.

**Views**

- **Staff:** own calendar only (assigned jobs + own Google events).
- **Admin:** all staff in one view, colour-coded per person, filter by staff, day / week / month, side-by-side team view.

**Manual reassignment (admin)**

- From the appointment or by drag-and-drop in team view; free staff shown as available, busy staff greyed out but selectable with a warning.
- Both staff notified on WhatsApp and email; event moves between their Google Calendars.
- Client details and reminders unchanged; optional message to the client naming the new staff member.
- Logged in the appointment history (who, when, from whom to whom).

**Leave:** admin marks a staff member on leave for a date range; they are excluded from auto-assignment, and their existing appointments in that period are flagged for reassignment.

**Cancel / reschedule:** cancel releases the slot, removes calendar events, notifies staff and starts the refund flow; reschedule offers new open slots and moves calendars, reminders and assignment.

## 10. Integrations

Each business connects its own WhatsApp number, Razorpay account, Google account and Meta ad account; Northstar holds the platform-level apps and credentials that make those connections.

| Integration | API | Used for | Connected by |
| --- | --- | --- | --- |
| WhatsApp | Official WhatsApp Cloud API (Northstar as Meta Tech Provider, Embedded Signup) | Inbound/outbound messages, templates, PDFs, delivery status | Business, in onboarding |
| Payments | Razorpay Payment Links, Refunds, webhooks | Advance/balance links, paid confirmation, refunds | Business |
| Calendar | Google Calendar API (OAuth) | Free/busy, create/update/delete events | Business + each staff member |
| Sheets | Google Sheets API (OAuth) | Live Leads and Payments tabs | Business |
| Meta outbound | Conversions API (business messaging) | Stage events matched on click ID | Business (ad account / dataset) |
| Meta inbound | Marketing API | Campaign names, ad names, spend for cost-per-lead reports | Business |
| Email | Resend | Invites, verification, notifications | Northstar |
| AI | LLM API with tool calling (provider-agnostic) | Conversation, extraction, scoring | Northstar |

**Google Sheets sync**

- Database is the source of truth; Sheets is a live mirror.
- Two tabs: **Leads** (Lead ID, name, phone, source, campaign, ad, chat link, score, band, score reason, stage, assigned staff, service, date/time, location, quote amount, created, updated, sync status) and **Payments** (Payment ID, Lead ID, name, invoice no., type, amount, GST, total, Razorpay link ID, Razorpay payment ID, status, paid at).
- Rows matched by platform ID, never row number; updates batched to stay within Google's per-project quota.
- Platform → sheet by default; selected fields (e.g. notes, stage) can be opted in to flow back. Conflicting edits on protected fields: platform wins, change goes to a review list.
- No message transcripts or tokens copied into sheets.

**Meta Conversions API events**

| Event | Fires when | Purpose |
| --- | --- | --- |
| Lead / qualified | Lead scored Warm or Hot | Light signal |
| Pre-final conversion | Payment link sent (stage configurable per business) | Main optimisation event |
| Purchase (with value) | Lead completed | Value signal for ROAS |

**Connection health:** every integration shows connected / error / reconnect and last successful sync.

## 11. Dashboards

Three dashboards from one React app, each showing only what that role is allowed to see.

**Business dashboard (admin)**

| Section | Contents |
| --- | --- |
| Overview | Today's leads, hot leads, revenue collected, pending payments, upcoming jobs, items needing attention |
| Leads (CRM) | All leads with source, score, stage, assigned staff; filters; open a lead → customer timeline |
| Customer timeline | One chronological record: messages (AI and human), staff-logged calls, quote versions, payments, refunds, invoices, appointments, task changes, approvals, delivery evidence; each entry shows who (AI, system, staff) and when |
| Lead score | Distribution by band, average by source and campaign/ad, conversion by band, hot leads not contacted |
| Conversations | WhatsApp inbox, Needs Human queue, take over / hand back to AI |
| Calendar | All staff, team view, reassignment, leave |
| Quotes, invoices, payments | Versions, ledger, invoice PDFs, credit notes, refunds awaiting approval |
| Team | Staff, roles, workload, invites |
| Chat Links | Links, QR codes, clicks → leads → conversions per link |
| Reports | Funnel conversion, source/campaign performance with cost per qualified lead, time per stage, revenue |
| Failed actions | Failed or Needs Review actions, error, retry / skip |
| Integrations | Connection status, last sync, reconnect |
| Settings | Workflow, approval mode, rules, catalogue, invoice template, GST details, cancellation policy |

**Staff dashboard:** my tasks, my calendar, my assigned leads and conversations; mark task done; add notes and log calls.

**Northstar admin panel:** all businesses with status and subscription, message and AI usage per business, failed jobs and webhook errors, integration health across tenants, pause a business.

## 12. Client onboarding wizard

A new business goes live through nine guided steps, ending with a test lead run end to end; the workspace activates only after payment or an explicit trial override.

1. **Sign up and verify** email.
2. **Create workspace:** business name, industry, timezone, working hours, language, brand tone.
3. **Business details:** service catalogue with prices, HSN/SAC and tax rates; GSTIN, legal name, address, state.
4. **Invoice template:** upload Word template or choose the default; map fields; preview.
5. **Team:** invite staff by email with roles and skills; staff connect Google Calendars.
6. **Integrations:** WhatsApp (required), Razorpay (required), Google Calendar, Google Sheets, Meta ad account.
7. **Workflow settings:** template, qualifying questions, filter rules, score thresholds, advance %, reminder timings, cancellation policy, Meta conversion stage, approval mode.
8. **Chat links:** generate tracked links and QR codes per source.
9. **Test run:** a dummy lead goes through the full flow; check messages, CRM record, sheet row, calendar event, invoice; go live.

**Readiness checklist** shown per business: each step green / pending, with what is blocking. WhatsApp number verification can add external lead time, so activation is never promised as instant.

## 13. Architecture and tech stack

React frontend, Spring Boot modular monolith, Supabase Postgres in Mumbai as the single source of truth, and background jobs for everything slow or external.

&#91;embedded content: system architecture · frontend, backend, database, external services\]

Webhooks come into the API, which stores the event and queues a job; the worker does every slow or external call, so an outage at Google or Meta never blocks the dashboard.

| Layer | Tool | Role |
| --- | --- | --- |
| Frontend | React + TypeScript (Vite), Tailwind CSS, shadcn/ui | Business, staff and Northstar admin dashboards; onboarding |
| Calendar UI | FullCalendar | Day/week/month, team view, drag-and-drop |
| Charts | Recharts | Score dashboard, funnels, reports |
| Frontend validation | Zod | Forms |
| Backend | Spring Boot (Java 21), modular monolith | APIs, workflow engine, rules, integrations, webhooks |
| Backend validation | Jakarta Bean Validation | Webhooks, API input, AI output |
| Database | Supabase PostgreSQL (Mumbai), Flyway migrations | Source of truth |
| Tenant isolation | Spring Security tenant filter + Supabase Row Level Security | Two layers |
| Auth | Supabase Auth, tokens verified by Spring Security | Login, verification, invites, roles |
| Storage | Supabase Storage, signed short-lived links | Quotes, invoices, templates, delivery files |
| Background jobs | JobRunr on Postgres | Reminders, follow-ups, retries, syncs, link expiry, slot holds |
| AI | Spring AI + LLM with tool calling | Provider-agnostic |
| Documents | poi-tl (Word templates) + Gotenberg (PDF) | Quotes and GST invoices |
| QR codes | ZXing | Chat-link QR codes |
| Integrations | WhatsApp Cloud API, Razorpay Java SDK, Google API Java clients, Meta CAPI + Marketing API, Resend | See section 10 |
| Frontend hosting | Cloudflare Pages (or Vercel) | Static React app |
| Backend hosting | DigitalOcean App Platform, Bangalore | API (2 containers), worker, PDF service, staging |
| Monitoring | Sentry | Frontend + backend errors |
| Code and CI/CD | GitHub + GitHub Actions | Reviews, tests, deploys |
| Testing | JUnit 5 + Testcontainers, Vitest, Playwright | Including cross-tenant access test |

**Backend modules (one deployable):** tenant & access · contacts & conversations · workflow runtime · CRM & jobs · AI orchestration · commercial (quotes, ledger, invoices) · scheduling · integrations · reporting · admin. Modules call each other through service interfaces only, so any can be split out later.

## 14. Data model

Every business-owned row carries `tenant_id`, enforced in application queries and by Row Level Security; UUID keys, UTC timestamps, typed columns for queryable fields and JSONB only for business-specific extras.

| Table group | Key fields |
| --- | --- |
| tenants | id, name, slug, timezone, locale, business\_profile, gstin, state, plan, status, approval\_mode |
| users / memberships | auth\_user\_id, tenant\_id, role (admin / staff), skills, status, invited\_at, leave periods |
| contacts | tenant\_id, normalized\_phone (unique per tenant), name, consent\_state, opt\_out\_at, sources history |
| conversations / messages | tenant\_id, contact\_id, channel, provider\_message\_id (unique), direction, type, status, timestamps |
| chat\_links / link\_clicks | tenant\_id, code, source, utm fields, clicks, matched lead |
| workflows / workflow\_versions / stages | tenant\_id, template, version, config JSON, published\_at (immutable once published) |
| leads | tenant\_id, contact\_id, workflow\_version\_id, stage, source, campaign, ad, click\_id, requirements, score, band, score\_reasons, assigned\_staff\_id |
| lead\_score\_history | tenant\_id, lead\_id, score, reasons, created\_at |
| catalogue\_items | tenant\_id, name, price, hsn\_sac, tax\_rate |
| quotes / quote\_versions / quote\_lines | tenant\_id, lead\_id, version, lines, totals, status (draft, sent, accepted, changes requested, superseded) |
| payments (ledger) | tenant\_id, lead\_id, quote\_version\_id, type, amount, status, razorpay\_link\_id, razorpay\_payment\_id, paid\_at |
| invoices / credit\_notes | tenant\_id, number (per FY), type, lines, CGST/SGST/IGST, status, pdf\_path |
| invoice\_templates | tenant\_id, docx\_path, field\_mapping, is\_default |
| refunds | tenant\_id, payment\_id, amount, policy\_applied, approved\_by, razorpay\_refund\_id, status |
| appointments / slot\_holds | tenant\_id, lead\_id, staff\_id, start, end, google\_event\_ids, hold\_expires\_at, status |
| tasks | tenant\_id, lead\_id, owner, due\_at, status |
| actions (action log) | tenant\_id, lead\_id, type, status (pending, processing, succeeded, failed, needs review), attempts, error, idempotency\_key, mode (approval / automatic) |
| timeline\_events | tenant\_id, lead\_id, actor (AI / system / staff id), event type, reference, created\_at (append-only) |
| integrations / sync\_mappings | tenant\_id, provider, encrypted credentials reference, scopes, sheet/tab ids, field mapping, last\_sync, status |
| meta\_events | tenant\_id, lead\_id, event name, value, sent\_at, response |
| audit\_log / usage | tenant\_id, actor, action, target, safe metadata; usage units per message, AI call, storage |

Secrets and cross-tenant configuration never live in user-editable JSON.

## 15. Reliability, failure handling and data pipelines

The workflow never stops silently: every automated action has a status, failures retry automatically, and anything that still fails lands in front of a person.

**Pipeline**

1. **Inbound:** WhatsApp, Razorpay and Meta webhooks hit Spring Boot; signature verified; event ID checked for duplicates; event stored; job queued. The webhook replies fast and never does slow work inline.
2. **Processing:** a worker loads the lead, runs AI if needed, and the rules engine decides the next action based on stage, settings and approval mode.
3. **Outbound:** every external call (WhatsApp send, Razorpay link, Calendar event, Sheets row, Meta event, PDF) is an action in the job queue with an idempotency key.

**Rules**

- **Action status:** Pending → Processing → Succeeded / Failed / Needs Review.
- **Retries:** exponential backoff; after the retry limit → Failed, shown on the Failed Actions screen with the error, a Retry and a Skip button.
- **Alerts:** admin alerted when an action fails repeatedly (e.g. "Invoice for Rahul Sharma failed 3 times"); Northstar admin sees failures across all tenants.
- **Duplicate protection:** provider event IDs and idempotency keys mean a repeated webhook or job never creates a second lead, payment, invoice or message.
- **Outbox for Sheets and Meta:** changes written to an outbox table first, then synced in batches; last successful sync shown.
- **Degraded mode:** if Google Calendar or Sheets is down, the platform keeps working and catches up on reconnect.
- **Kill switches:** pause automation per conversation, per business, platform-wide.

**Scaling rules (from day one):** stateless Spring Boot containers, all heavy work through the queue, every table indexed by `tenant_id`, message history archived after a retention period.

## 16. Edge cases and expected behaviour

Each case below gets an automated test before launch; the behaviour is defined now, alongside the architecture, not left to the end of development.

| Situation | Expected behaviour |
| --- | --- |
| Same lead webhook arrives twice | No duplicate contact, lead or message |
| Meta authorisation expires | Connection error visible; reconnect option; automation for that integration paused, not failed silently |
| Server fails while saving a lead | Event kept and retried; no lost lead |
| Phone number missing | Defined rule: WhatsApp leads always have one; manual entries rejected without a phone |
| Lead reassigned to another user | Ownership history preserved |
| User deletes a lead | Admin only; soft delete with audit entry; retention rules apply |
| Two users edit the same lead | Version check; second save warned, never silently overwrites |
| Workspace subscription ends | Defined grace period, then read-only; automation paused; data kept per retention policy |
| Client pays twice, or Razorpay webhook repeats | One payment record, one invoice; extra payment flagged for refund |
| Payment link expires unpaid | Slot released; one reminder with a fresh link |
| Client pays after the slot was released | Availability re-checked; confirm if free, else staff alerted to reschedule |
| Assigned staff becomes unavailable | Auto-reassign to free staff, else alert admin |
| Client wants to reschedule or cancel after paying | Hand-off to staff; refund per business policy with owner approval |
| Client replies STOP or opts out | All automated messages stop at once, scheduled ones cancelled |
| AI unsure or client negotiates | Needs Human; conversation flagged |
| Quote sent with a wrong price | Staff revises; new version sent; history kept |
| Google Calendar disconnected | Platform calendar keeps working; error shown; sync resumes on reconnect |
| Client messages after 24 hours of silence | Approved WhatsApp template used, per Meta's customer-service window rule |
| Balance unpaid after work | Scheduled reminders, then escalation; delivery hold optional |
| Invoice generation fails | Action Failed → retries → Failed Actions screen; payment record unaffected |
| Sheet edited by a user on a protected field | Platform value restored; change sent to review list |

## 17. Security, privacy and compliance

Tenant isolation is a release gate: no build ships if a test shows one business can read or change another's data.

- **Tenant isolation:** tenant taken from the verified login token, never from the request; RLS on every tenant table; cross-tenant test on every pull request.
- **Auth:** secure sessions, MFA for admins where available, invite expiry, immediate offboarding, audited admin actions.
- **Secrets:** provider tokens encrypted at rest; service keys on the server only; separate staging and production; credential rotation.
- **Webhooks:** signature verification, payload size limits, duplicate rejection, minimal stored data.
- **AI privacy:** only necessary context sent to the model; provider data-retention settings configured; prompts and system instructions never exposed to customers.
- **WhatsApp policy:** approved templates where required, customer-service window honoured, opt-in recorded, opt-out immediate, no unsolicited bulk messaging, quality rating monitored.
- **Payments:** card data never stored; generated invoices never marked paid without Razorpay confirmation.
- **Data residency:** database and storage in Mumbai, backend in Bangalore; supports India's DPDP Act obligations.
- **Data rights:** documented retention, export and deletion workflows; backup expiry defined; message transcript retention minimised.
- **Incident readiness:** audit trail, backup and restore drill, error alerting, credential revocation procedure, support contact.

Before commercial launch, privacy notices, tax treatment, messaging consent and payment terms are reviewed by Indian legal and tax advisers.

## 18. Infrastructure cost and capacity

The launch setup costs about $245 a month (≈ ₹23,640), about ₹2.84 lakh for year 1 or ₹3.26 lakh with a 15% buffer, and serves 50–100 client businesses.

| Item | Service | Monthly (USD) |
| --- | --- | --- |
| Database, auth, storage | Supabase Pro + Small compute + staging | 60 |
| React frontend | Cloudflare Pages | 0 |
| Spring Boot API (2 × 1 vCPU / 2 GB) | DigitalOcean App Platform | 50 |
| Background worker (JobRunr) | DigitalOcean App Platform | 25 |
| PDF service (Gotenberg) | DigitalOcean App Platform | 25 |
| Staging backend | DigitalOcean App Platform | 25 |
| Email | Resend | 20 |
| Error tracking | Sentry | 26 |
| Code and CI/CD (3 users) | GitHub Team | 12 |
| Domain | — | 2 |
| **Total** |  | **245** |

Converted at ₹96.5 per USD; planning estimates to confirm on provider pricing pages. Not included: \~18% GST on provider bills (usually claimable as input credit), WhatsApp message charges, LLM usage, Razorpay fees, team costs.

**Capacity tiers**

| Setup | Client businesses | Approx. monthly infra |
| --- | --- | --- |
| Launch (above) | 50–100 | ≈ ₹27k |
| Growth: bigger Supabase compute, 3–4 API containers, 2 workers | 250–500 | ≈ ₹55–80k |
| Scale: read replica, dedicated worker pool, message archiving | 1,000+ | ≈ ₹1.5L+ |

**Growth limits to watch:** Google Sheets API quota shared across all tenants (batch writes, request higher quota before \~100 clients); LLM provider rate tier; database storage from message history; onboarding and support capacity.

## 19. Build phases with Claude Code

The build runs in ten phases, each closed by a gate that must pass before the next starts; external approvals (Meta, Google) start in week 1 because they usually set the real timeline.

**Step 0, week 1, in parallel**

- [ ] Meta: business verification, Meta app, Tech Provider application, WhatsApp / ads / Conversions API permissions
- [ ] Google Cloud: project, OAuth consent screen, Calendar + Sheets APIs, app verification submitted
- [ ] Razorpay: account and KYC; decide how clients connect their accounts
- [ ] Supabase: staging + production projects in Mumbai
- [ ] DigitalOcean (Bangalore), domain, Resend domain, Sentry, LLM API key, GitHub organisation

**Repository:** `northstar/` with `CLAUDE.md`, `docs/` (this document and specs), `frontend/`, `backend/`, `db/migrations/`, `.github/workflows/`.

**Working method per module:** plan in Claude Code's plan mode → review the plan → implement in small pieces → tests pass → developer review (always for payments, auth, tenant data, webhooks) → pull request → CI → merge. At least one experienced Java developer supervises.

| Phase | What gets built | Gate |
| --- | --- | --- |
| 1. Foundation | Repo, CI, deploy pipeline; schema, migrations, RLS; Supabase login verified by Spring Security; tenants, roles, staff invites; dashboard shell | Two test businesses; automated test proves neither sees the other's data |
| 2. WhatsApp + inbox | Webhook (signature, duplicates), message store, send, templates, delivery status; inbox with takeover; opt-out | Real message in, reply out; duplicate webhook creates nothing |
| 3. Workflow engine | Stages, step types, templates, versions; action log + statuses; JobRunr jobs, retries, failed-actions screen, alerts; approval mode | Test lead runs the Reelrr template end to end; forced failure appears on failed-actions screen |
| 4. AI + scoring | Spring AI tools, qualifying conversation, extraction; filter rules + AI score; hand-off; score dashboard | AI qualifies and scores with reasons, hands off when unsure, cannot act outside its tools |
| 5. Commercial | Catalogue, quote versions, accept / changes, discount approval; template upload + PDF; Razorpay links + webhooks; ledger; GST invoices, credit notes; refunds | Quote → pay advance → invoice → balance → final invoice in Razorpay test mode |
| 6. Scheduling | Google Calendar per staff, free/busy, slot hold, auto-assign; calendar views; reassignment, leave, cancel, reschedule | Only open slots offered; event lands in the right calendars; reassignment moves it |
| 7. Outbound integrations | Sheets sync; Meta CAPI + Marketing API; chat links + QR | Sheets update in seconds; events visible in Meta Events Manager; link sources tagged |
| 8. Dashboards + admin | Overview, CRM, timeline, reports, integration health; onboarding wizard; Northstar admin | New business goes live through the wizard unaided |
| 9. Hardening | All edge-case tests, load test, security review, backup/restore drill, alerts, production deploy | Every edge case in section 16 has a passing test; restore works |
| 10. Pilot | Reelrr + 2–4 design partners on real numbers | Real leads flow end to end; weekly fix cycle |

Phases 1–3 run in order; 4–7 can overlap with more than one developer.

**MVP acceptance criteria**

- [ ] Two or more businesses operate independently; no cross-tenant access through UI, API, jobs, exports or callbacks
- [ ] Inbound WhatsApp message creates or updates one contact and lead; staff can pause and take over
- [ ] Reelrr workflow runs all ten stages, including partial payment and balance
- [ ] Workflow config can disable payment or booking steps without code changes
- [ ] AI tool calls are validated, tenant-scoped, idempotent and audited; uncertain cases go to staff
- [ ] Sheets sync upserts by ID, retries visibly, handles conflicts and disconnects
- [ ] Opt-out stops all automated messages; WhatsApp window and template rules enforced
- [ ] Meta events fire at the configured stages and match on click ID
- [ ] Northstar admin sees tenants, failed jobs, webhook errors, usage, and can pause a business

## 20. Open decisions

These need an answer before or during Phase 0–1; none blocks starting the foundation.

- [ ] Who pays WhatsApp message charges: billed to each client's own Meta account, or bundled into the Northstar plan?
- [ ] How clients connect Razorpay: their own API keys, or a Razorpay partner integration?
- [ ] Which LLM provider to start with, and the per-tenant AI usage allowance
- [ ] Lead score thresholds for Hot / Warm / Cold defaults
- [ ] Subscription end behaviour: grace period length and read-only rules
- [ ] Data retention periods for messages, files and audit logs
- [ ] Default cancellation and refund policy offered in templates
- [ ] Chartered accountant review of GST, invoice numbering and credit-note rules
- [ ] Whether line-item partial quote approval is needed for pilot clients
