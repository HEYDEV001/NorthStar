# Northstar Backend Roadmap

Backend first, frontend after. This file splits the backend into phases and commit-sized steps. It follows
`build-document.md` section 19 and keeps its phase numbers, so the Phase column in `edge-cases.md` still applies.
The frontend parts of each phase (dashboard shell, inbox, calendar views, wizard) move to a later frontend roadmap,
and each gate below is restated so it can be proven through the API and tests alone.

Tick a step when it is merged to `main`.

## How every step runs

1. Read the spec sections listed for the phase.
2. Plan the step: files, migration, tests. Wait for approval.
3. Build it with tests. Edge-case tests are named with the case ID first, e.g. `W18_duplicateRazorpayWebhookCreatesOnePayment`.
4. `./mvnw spotless:apply verify` passes.
5. Hand over a summary, the spec sections and edge-case IDs covered, and a commit message.
6. The developer commits on a branch, opens a PR, CI passes, merge.

Payments, auth, tenant isolation and webhook steps always get a human review before merge.

## Decisions (confirmed 2 Nov 2026)

| # | Decision | Outcome | Why |
| --- | --- | --- | --- |
| D1 | Order of phases 5 and 6 | Build 6 (Scheduling) before 5 (Commercial), keeping the numbers | W16, W17 and W20 (Phase 5) release, re-check and extend slot holds, which Phase 6 builds. The build document lets 4–7 overlap. |
| D2 | Where the job and action core lives | Phase 1, not Phase 3 | P10 (Phase 1) needs jobs to fail loudly without tenant context, and Phase 2 sends WhatsApp messages as `actions` rows. Phase 3 then adds the engine, timers and approval mode on top. |
| D3 | When tables are created | In the phase that first uses them, plus the minimal lead tables in Phase 1 | Smaller migrations that are easier to review. P11 (Phase 1) needs `leads` to prove staff isolation. |
| D4 | User who belongs to more than one business | One business per user for MVP. `memberships` can still hold several rows per user, but a partial unique index allows only one active membership per user. A tenant claim in the Supabase token can be added later without a migration. | `CLAUDE.md` forbids taking the tenant from a header, body or query parameter, so the membership lookup decides. The `data-model.md` change ships with step 1.3. |
| D5 | Backend-first order | Noted in `build-document.md` section 19 | `CLAUDE.md`: spec changes go into `docs/` first. |
| D6 | Staff login | Email only for MVP | Closes the phone OTP question in `data-model.md` section 15. |

## Phase overview

| Phase | Name | Edge cases | Gate (backend) |
| --- | --- | --- | --- |
| 0 | Setup | — | `./mvnw verify` green locally and in CI |
| 1 | Foundation: tenancy, auth, isolation, job core | P9–P14 | Two businesses via the API; tests prove neither can read the other's data through API or jobs |
| 2 | WhatsApp and conversations | P1, P3, P4, P15, P16, W1, W32–W34 | Real test message in, reply out; duplicate webhook creates nothing |
| 3 | Workflow engine | P5–P7, P21, P22, W2, W35, W36 | Test lead runs the Reelrr template end to end with fake providers; a forced failure appears in the failed-actions API |
| 4 | AI and scoring | W4–W9 | AI qualifies and scores with reasons, hands off when unsure, cannot act outside its tools |
| 6 | Scheduling (before 5, see D1) | P19, W10, W27–W30 | Only open slots offered; event lands in the right calendars; reassignment moves it |
| 5 | Commercial | P23, W11–W26, W31 | Quote → advance → invoice → balance → final invoice in Razorpay test mode |
| 7 | Outbound integrations | P2, P17, P18, W3 | Sheets update in seconds; events visible in Meta Events Manager; link sources tagged |
| 8 | Reporting, admin, billing APIs | P8, P20 | A new business can be onboarded end to end through the API |
| 9 | Hardening | P24 + all | Every backend edge case passes in CI; restore drill works |

Phase 10 (pilot) needs the frontend and is planned after it.

---

## Phase 0 — Setup

**Specs:** `CLAUDE.md`, `build-document.md` §13, §19.

- [x] 0.1 Repo hygiene: `.gitignore`, `.env.example`, `.editorconfig`, Claude Code deny rules, spec file names
- [x] 0.2 Backend skeleton: Spring Boot 4.1 on Java 21, module packages, Actuator health, Spotless, Testcontainers on `supabase/postgres`
- [x] 0.3 Database wiring: `db/migrations/` README, Flyway Maven plugin as `migrator`, Flyway applied to the test container, Supabase CLI migrations off, `site_url` → `:5173`
- [x] 0.4 CI: GitHub Actions `backend` job (JDK 21, `./mvnw verify`); make it a required check in the `protect-main` ruleset
- [x] 0.5 Check every backend command in `CLAUDE.md` and fix the Commands section

**Gate:** a PR shows the `backend` check green, and it is required for merge.

## Phase 1 — Foundation: tenancy, auth, isolation, job core

**Specs:** `data-model.md` §1, §3, §6 (leads only), §10 (audit), §12, §13; `build-document.md` §3, §15, §17; `integration-specs.md` §1, §6 (email).
**Modules:** `tenancy`, `admin` (role only), `workflow` (actions core), `integrations` (adapter pattern, email).

- [x] 1.1 Database roles: `migrator`, `app_user` (no BYPASSRLS), `app_admin`; grants and default privileges. Passwords set outside migrations.
- [x] 1.2 RLS release-gate test: every table with `tenant_id` has RLS enabled, forced and the `tenant_isolation` policy (rules in `data-model.md` §12)
  - Append-only tables in two tiers: insert-only (`timeline_events`, `audit_log`, `lead_score_history`, `credit_notes`) and lifecycle (`webhook_events`, `payments`, `invoices`, `refunds`: table-level update revoked, column-level update on declared columns only). The gate checks both.
- [ ] 1.3 Tenancy schema: `tenants`, `user_profiles`, `memberships` (one active membership per user, D4), `invites`, `staff_skills`, `staff_leave`, `audit_log`
  - `tenants` and `user_profiles` go on the gate's platform-wide list; `audit_log` is insert-only (update revoked).
- [ ] 1.4 Tenant context: `set_config('app.tenant_id' | 'app.user_id' | 'app.role', …, true)` at the start of every transaction; the app's connection switches to `app_user`
  - The app connects through Supabase's transaction pooler, so its JDBC URL needs `prepareThreshold=0`. Flyway uses a direct or session-pooler connection.
- [ ] 1.5 Supabase token verification with Spring Security, membership lookup, deactivated user rejected on the next request (P13)
- [ ] 1.6 API conventions: `/api/v1`, `{code, message, details}` errors, other tenant's record → 404 (P9)
- [ ] 1.7 Create business and first admin; `GET /api/v1/me`
- [ ] 1.8 Members: list, change role, deactivate; at least one active admin always (P14); audit entries
- [ ] 1.9 Invites: create, revoke, accept; single-use, expiry (P12)
- [ ] 1.10 Job and action core: JobRunr (own schema), worker sets tenant context from the job, `actions` table with idempotency key and statuses, retryable / permanent / auth classification, retry table; no tenant context → fails loudly (P10)
  - JobRunr's tables are created by a Flyway migration as `migrator`, with JobRunr's own table creation turned off. `app_user` gets full DML including delete on JobRunr's tables only. JobRunr's tables go on the gate's platform-wide list.
- [ ] 1.11 Fake-adapter test kit: duplicates, delays, timeouts, 429, auth failures
- [ ] 1.12 Email adapter (Resend) behind the integrations interface; invite email sent as an action
- [ ] 1.13 Minimal lead tables: `contacts`, `workflows`, `workflow_versions`, `leads`, with the staff policy on `leads` (P11)
  - The staff policy is restrictive; the gate fails on a second permissive policy.
- [ ] 1.14 Cross-tenant suite P9, P10, P11 runs on every PR
- [ ] 1.15 Staging deploy pipeline (needs DigitalOcean and the Supabase staging project from Step 0; may slip without blocking Phase 2)
  - Run `db/bootstrap/roles.sql` on the staging project and verify it there first: hosted `postgres` is not a full superuser.
  - Flyway uses a direct or session-pooler connection; the app uses the transaction pooler with `prepareThreshold=0`.

**Decided:** D4 (one active membership per user), D6 (email login only).
**New dependencies:** Spring Security OAuth2 resource server, JobRunr.

## Phase 2 — WhatsApp and conversations

**Specs:** `integration-specs.md` §1, §2, §7; `workflow-spec.md` §6, §12; `data-model.md` §4, §9.
**Modules:** `integrations`, `contacts`, `crm`.

- [ ] 2.1 Integration schema: `integrations`, `integration_credentials` (encrypted, decrypted only in the worker), `whatsapp_accounts`, `webhook_events`
  - `webhook_events` is a lifecycle table; declare its updatable columns in the gate. Its nullable `tenant_id` fails the gate as specified, so settle that open question here. Any new schema (`ingest`, `secrets`) joins the gate's schema list.
- [ ] 2.2 `POST /webhooks/whatsapp`: verify signature (P15) → store → dedupe (P1) → resolve tenant by phone number ID (P16) → queue → 200
- [ ] 2.3 Conversation schema: `contact_sources`, `conversations`, `messages`, `message_templates`
- [ ] 2.4 Inbound job: normalise to an `events` row; upsert contact by normalised phone; join the open lead's conversation (W1); lead created in one transaction (P3)
- [ ] 2.5 Opt-out checked first on every inbound message; cancels all scheduled outbound actions (W32)
- [ ] 2.6 Send action: WhatsApp adapter (real and fake), 24-hour window, templates only outside it (W33)
- [ ] 2.7 Delivery status webhooks update messages
- [ ] 2.8 Template sync and status; rejected or paused templates fail dependent actions visibly (W34)
- [ ] 2.9 Inbox API: conversations, messages, staff reply, take over and hand back (pause automation per conversation)
- [ ] 2.10 Manual lead entry with E.164 validation (P4)

**Needs answers:** whether `webhook_events.tenant_id` may be null for unrouted events, given the "no exceptions" rule; which database role reads and decrypts `integration_credentials`; where the encryption key lives.
**External:** Meta developer app and a WhatsApp test number.

## Phase 3 — Workflow engine

**Specs:** `workflow-spec.md` §2–§10, §13–§17; `build-document.md` §6; `data-model.md` §6, §8 (`tasks`), §10.
**Modules:** `workflow`, `crm`.

- [ ] 3.1 Workflow config format (§15) with validation; publish makes a version immutable
- [ ] 3.2 Reelrr template as seed data (§4, §14)
- [ ] 3.3 Lead state machine: only transitions allowed by the lead's version; `timeline_events` on every change; optimistic lock on `version` (P7)
- [ ] 3.4 Events and triggers (§6); conditions and rules (§7)
- [ ] 3.5 Step type executors (§5) producing actions
- [ ] 3.6 Timers (§8): `timers` mirrors JobRunr jobs; quiet hours and reminders in the tenant timezone (P22); cancellation
- [ ] 3.7 Approval mode (§9): `needs_review`, approve and reject API, reminders at 30 min and 2 h (W35)
- [ ] 3.8 Failed actions API: list, retry, skip; alert on final failure; kill switches per business and platform-wide
- [ ] 3.9 Leads API: reassign with history (P5), admin-only soft delete (P6), notes, tasks, request idempotency key (P21)
- [ ] 3.10 Contact from a Lost or Junk lead starts a new lead (W2)
- [ ] 3.11 Versioning and migration (§16): in-flight leads keep their version (W36)
- [ ] 3.12 End-to-end engine test on the Reelrr template with fake providers

**Needs answers:** Reelrr defaults (`workflow-spec.md` §18); one open lead per contact, and one or many workflows per tenant (`data-model.md` §15); precedence between tenant and action approval mode; `closed_at` for Junk and Lost.

## Phase 4 — AI and scoring

**Specs:** `workflow-spec.md` §11; `build-document.md` §7; `integration-specs.md` §6 (LLM); `data-model.md` §4 (`faq_entries`), §6 (`lead_fields`, `lead_score_history`).
**Module:** `ai` (the only module allowed a provider SDK).

- [ ] 4.1 `AiService` interface, Spring AI adapter, fake adapter
- [ ] 4.2 Tool registry limited to the lead's stage; every call schema-checked, tenant-scoped, permission-checked, audited
- [ ] 4.3 Prompt assembly: customer text is data (W7); FAQ entries
- [ ] 4.4 Qualifying conversation and field extraction with confidence; at most two clarifying questions, then Needs Human (W6)
- [ ] 4.5 Voice notes, images, location pins (W4); reply language per tenant setting (W5)
- [ ] 4.6 Filter rules → Junk with rule ID and reason (W9); AI score, reasons, history, bands
- [ ] 4.7 Provider down: three retries, then Needs Human and one holding message (W8)
- [ ] 4.8 Score dashboard API

**Needs answers:** LLM provider and per-tenant token budget; Hot and Warm thresholds; budget units.
**New dependency:** Spring AI.

## Phase 6 — Scheduling (built before Phase 5)

**Specs:** `build-document.md` §9; `integration-specs.md` §4; `data-model.md` §8.
**Module:** `scheduling`.

- [ ] 6.1 Schema: `calendar_connections`, `busy_blocks`, `slot_holds`, `appointments`, `appointment_assignments`
- [ ] 6.2 Google OAuth per staff member; disconnect removes them from auto-assignment (P19)
- [ ] 6.3 Free/busy sync into `busy_blocks`; keeps working when Google is down
- [ ] 6.4 Slot finder with skills, leave and tenant timezone; nearest open slots when none fit (W10)
- [ ] 6.5 Slot holds with expiry, plus the extension hook Phase 5 uses for W20
- [ ] 6.6 Auto-assignment; appointment and Google events created as actions
- [ ] 6.7 Reschedule (W27), leave or unavailability reassign (W28), overlapping-event conflict (W29), overdue job escalation (W30)
- [ ] 6.8 Calendar API for day, week and team views

**Needs answers:** Reelrr crew skills.
**External:** Google Cloud project and OAuth consent screen (test users are enough before verification).

## Phase 5 — Commercial

**Specs:** `commercial-rules.md` §1–§9; `build-document.md` §8; `integration-specs.md` §3, §6 (PDF); `data-model.md` §7.
**Module:** `commercial`. Every step gets a human review.

- [ ] 5.1 Catalogue and tax settings
- [ ] 5.2 Tax maths: per line, half-up to the paisa, CGST/SGST split, IGST for another state (W24); unit tests include the §9 worked example
- [ ] 5.3 Quotes: versions, send, accept, request changes, supersede (W11, W12, W14), validity expiry (W15), discount limit with admin approval (W13)
- [ ] 5.4 Ledger: `payments`; amount due derived from the accepted version and the ledger; partial payments (W19)
  - `payments`, `invoices` and `refunds` are lifecycle tables: declare their updatable columns in the gate, and add a trigger on each that allows only forward status transitions.
- [ ] 5.5 Razorpay: payment link action; webhook verify → store → dedupe → one ledger entry per payment ID (W18); link expiry (W16); late payment after release (W17); reconciliation every 15 min (W20); failed payments (W21)
- [ ] 5.6 Storage: private buckets, `tenant_id/` paths, short-lived signed URLs, upload type and size checks (P23)
- [ ] 5.7 Invoice templates: `.docx` upload, missing mapped fields block the template (W23); PDF via poi-tl and Gotenberg as an action
- [ ] 5.8 GST documents: `invoice_sequences` locked in the insert transaction, invoices, lines, credit notes; generation failure leaves the payment untouched (W22)
- [ ] 5.9 Cancellation and refunds: policy, owner approval, Razorpay refund, credit note (W25, W26)
- [ ] 5.10 Collection automation: balance reminders on days 1, 3, 7, escalation, optional delivery hold (W31)

**Needs answers:** Razorpay connection model; the earlier list of commercial-spec mismatches (document types, `invoice_sequences` document type, `manual` payment kind, `partially_paid` status, `amount_due_paise`). The chartered accountant checklist (`commercial-rules.md` §10) must be signed off before paying clients, not before building.
**New dependencies:** Razorpay Java SDK, poi-tl, Gotenberg (service container).

## Phase 7 — Outbound integrations

**Specs:** `integration-specs.md` §4 (Sheets), §5, §6 (chat links); `data-model.md` §5, §9; `build-document.md` §4, §10.
**Modules:** `integrations`, `reporting`.

- [ ] 7.1 Schema: `sheet_mappings`, `sync_outbox`, `sheet_conflicts`, `meta_events`, `chat_links`, `link_clicks`, `ad_referrals`, `ad_spend_daily`
- [ ] 7.2 Sheets sync from the outbox: upsert by ID in batches; quota backoff (P17); deleted sheet or tab (P18); protected-field edits restored and sent to review
- [ ] 7.3 Meta Conversions API events at configured stages, matched on click ID
- [ ] 7.4 Meta Marketing API daily ad spend
- [ ] 7.5 Expired or revoked authorisation pauses dependent actions and resumes on reconnect (P2)
- [ ] 7.6 Chat links, QR codes, click tracking, lead matching (W3)

**Needs answers:** Google Picker or always a new sheet; hashed phone for leads without a click ID.
**External:** Meta business verification and Tech Provider status.
**New dependencies:** Google Sheets client, ZXing.

## Phase 8 — Reporting, admin and billing APIs

**Specs:** `build-document.md` §11, §12; `data-model.md` §10 (`usage_daily`), §11, §14.
**Modules:** `reporting`, `admin`, `tenancy`.

- [ ] 8.1 Overview, CRM and report APIs; integration health API
- [ ] 8.2 Onboarding readiness API: each step green or pending, with what is blocking it
- [ ] 8.3 Northstar admin over `app_admin`, every call audited: tenants, failed jobs, webhook errors, usage, pause a business
- [ ] 8.4 Plans, subscriptions, usage alerts at 70 / 90 / 100% (P20)
- [ ] 8.5 Subscription end: grace period, then read-only with automation paused (P8)
- [ ] 8.6 Retention jobs (`data-model.md` §14)

**Needs answers:** grace period and read-only rules; plan limits; retention periods.

## Phase 9 — Hardening

**Specs:** `build-document.md` §15–§17; `edge-cases.md` §3.

- [ ] 9.1 Every P and W case that belongs to the backend has a passing named test
- [ ] 9.2 Load test against the launch capacity tier
- [ ] 9.3 Security review: auth, isolation, webhooks, secrets, logs
- [ ] 9.4 Backup and restore drill; restored data passes the isolation suite (P24)
- [ ] 9.5 Sentry, alerting, production deploy

**Gate:** every backend edge case passes in CI, and the restore drill works.
