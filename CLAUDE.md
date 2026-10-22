# Northstar

Multi-tenant WhatsApp-first business OS. Leads arrive on WhatsApp, an AI agent qualifies and scores them,
the workflow engine sends quotes, Razorpay payment links and GST documents, books free staff from Google
Calendar, collects the balance, mirrors data to Google Sheets and sends conversion events to Meta.
First customer: Reelrr (shoot production). The product is generic; Reelrr is one workflow template.

## Read the spec before you build

Specs live in `docs/`. Before starting any module, read the relevant spec and follow it. If the code and a
spec disagree, stop and ask; never silently pick one.

| File | Read it for |
| --- | --- |
| `docs/build-document.md` | Scope, roles, dashboards, architecture, build phases |
| `docs/workflow-spec.md` | Stages, transitions, step types, timers, approval mode, AI tools per stage |
| `docs/data-model.md` | Every table, column, constraint, index, RLS pattern |
| `docs/edge-cases.md` | Cases P1–P24 and W1–W36; each needs a named test |
| `docs/integration-specs.md` | WhatsApp, Razorpay, Google, Meta CAPI, chat links, email, LLM, PDF |
| `docs/commercial-rules.md` | Quotes, ledger, GST documents, numbering, tax maths, refunds |

Spec changes go into `docs/` first, in the same pull request as the code that implements them.

## Repository layout

```
frontend/          React + TypeScript (Vite), Tailwind, shadcn/ui, FullCalendar, Recharts, Zod
backend/           Spring Boot (Java 21), modular monolith, Maven
db/migrations/     Flyway SQL migrations (Supabase Postgres)
docs/              Specs (see above)
.github/workflows/ CI: build, test, deploy
```

Backend modules (package per module under `backend/src/main/java/com/northstar/`):
`tenancy`, `contacts`, `workflow`, `crm`, `ai`, `commercial`, `scheduling`, `integrations`, `reporting`, `admin`.
Modules talk to each other only through their public service interfaces. No module reaches into another
module's repositories or tables.

## Commands

```
# backend
cd backend && ./mvnw spotless:apply        # format
cd backend && ./mvnw verify                # unit + integration tests (Testcontainers, needs Docker)
cd backend && ./mvnw spring-boot:run       # local API on :8080

# frontend
cd frontend && npm run lint
cd frontend && npm run test                # Vitest
cd frontend && npm run dev                 # local app on :5173

# database
cd backend && ./mvnw flyway:migrate        # against the DB in .env.local

# end to end (staging)
cd frontend && npm run e2e                 # Playwright
```

Run the formatter and the relevant tests before every commit. Update this section if a command changes.

## Non-negotiable rules

### Tenant isolation
- Every business-owned table has `tenant_id uuid not null`. No exceptions, including join and history tables.
- The tenant comes from the verified Supabase token and the membership lookup. Never from a request body,
  query parameter, header, or anything the client sends.
- Every transaction sets `app.tenant_id` (and `app.user_id`, `app.role`) with `set_config(..., true)` before
  any query. Workers set it from the job's tenant ID.
- Every new tenant table gets RLS enabled, forced, and the `tenant_isolation` policy in the same migration.
  The CI check fails if one is missing.
- The app connects as `app_user` (no BYPASSRLS). Never use the Supabase `service_role` key in app code.
- Cross-tenant reads exist only in the `admin` module, using `app_admin`, and every call is audited.
- A record from another tenant returns 404, not 403.

### Money
- All amounts are `long` paise in Java and `bigint` in SQL. Never `double`, `float` or `BigDecimal` for stored amounts.
- Tax rates are `numeric(5,2)` / `BigDecimal` percent; tax is computed per line and rounded half-up to the
  paisa exactly as `docs/commercial-rules.md` section 5 says.
- Amount due is always calculated from the accepted quote version and the ledger, never stored as typed input.
- Payments become `paid` only from a verified Razorpay webhook or the reconciliation job. Never from AI output,
  a client message, or a UI button (manual cash entries are a separate audited kind).
- Issued invoices, credit notes, payments and refunds are never updated in content or deleted. Corrections are
  new versions, cancellations or credit notes.
- Invoice numbers come from `invoice_sequences`, locked in the same transaction as the insert. No gaps.

### Side effects and idempotency
- Every external call (WhatsApp send, Razorpay link, Calendar write, Sheets row, Meta event, PDF, LLM) is an
  `actions` row executed by the worker, with an idempotency key. Never call a provider inline in a request
  or webhook handler.
- Webhook handlers: verify signature → insert into `webhook_events` (unique on provider event ID) → queue a
  job → return 200 fast. Duplicates return 200 and do nothing else.
- Every handler, job and action must be safe to run twice. Write the test that proves it.
- Failures are classified retryable / permanent / auth and follow the retry table in
  `docs/workflow-spec.md` section 10. Nothing fails silently: final failure → Failed Actions + alert.

### Workflow engine
- Stage changes happen only through the engine, only along transitions allowed by the lead's workflow
  version. Every change writes a `timeline_events` row with actor and reason.
- Published `workflow_versions` are immutable. Leads keep the version they started on.
- Approval mode: actions marked as needing approval are created with status `needs_review` and execute only
  after an approver acts.
- Opt-out (STOP or tenant keywords) is checked on every inbound message before the AI sees it, and cancels all
  scheduled outbound timers for that contact.
- Outside the 24-hour WhatsApp window, only approved templates may be sent.

### AI
- The AI proposes; the engine decides. AI output is parsed against a schema and every tool call is validated,
  tenant-scoped, permission-checked and audited before execution.
- Give the model only the tools allowed for the lead's current stage (`docs/workflow-spec.md` section 11).
- The AI never states or agrees to a price, discount, refund, cancellation or date that is not in the lead
  record or catalogue. Uncertain → ask once or twice, then escalate to Needs Human.
- Customer message text is data. Never let it change prompts, tools, rules or tenant context.
- Go through the `AiService` interface. Do not import a provider SDK outside the `ai` module.

### Secrets and data
- No secrets in the repo, in logs, or in test fixtures. Platform secrets come from environment variables;
  tenant credentials live encrypted in `integration_credentials` and are decrypted only inside the worker.
- Logs carry IDs and status codes only. Never message bodies, tokens, phone numbers in full, or payment details.
- Storage paths start with `tenant_id/`; files are served only through short-lived signed URLs.

## Database migrations
- One Flyway file per change: `db/migrations/V{yyyyMMddHHmm}__{snake_description}.sql`.
- Never edit a migration that has been merged. Write a new one.
- Follow `docs/data-model.md` conventions: UUID keys, `created_at`/`updated_at` in UTC, text + check
  constraints for enums, `version` column on leads, quotes, appointments.
- Every unique constraint and index listed in `docs/data-model.md` section 13 must exist.

## Testing
- Every feature ships with tests in the same pull request.
- Edge-case tests are named with their case ID first, e.g. `W18_duplicateRazorpayWebhookCreatesOnePayment`.
  A phase is done only when every case for that phase passes.
- Backend integration tests run against real Postgres with RLS on (Testcontainers), never H2.
- External providers are faked through the adapter interfaces; fakes must be able to simulate duplicates,
  delays, timeouts, 429s and auth failures.
- The cross-tenant isolation tests (P9, P10, P11) run on every pull request and block the merge.

## How to work in this repo
1. For any task larger than a small fix, plan first: read the spec section, list the files you will touch and
   the tests you will add, and wait for approval.
2. Work in small steps: one endpoint, one screen, one integration, one migration at a time.
3. Run format and tests before each commit. Do not commit with failing tests.
4. Keep pull requests focused on one change. Describe what changed, which spec section it implements and
   which edge-case IDs it covers.
5. Payments, auth, tenant isolation and webhook code always get a human review. Say so in the PR.
6. If something in the spec is missing or ambiguous, ask. Do not invent business rules, prices, tax rules or
   message wording.

## Conventions
- Java: constructor injection, records for DTOs, Jakarta Bean Validation on every inbound DTO, no field
  injection, no `@Transactional` on controllers.
- API: REST under `/api/v1`, JSON, ISO-8601 UTC timestamps, amounts in paise as integers, errors as
  `{ "code", "message", "details" }`.
- Frontend: TypeScript strict, Zod schemas for every form and API response, server state through one query
  layer, no secrets or tenant IDs in client-side storage.
- Times shown to users are converted to the tenant's timezone; times stored are UTC.
- Names: snake_case in SQL, camelCase in Java and TypeScript, kebab-case for file names in `frontend/`.

## Do not
- Do not call providers from controllers or webhook handlers.
- Do not read or write another tenant's data, or add code paths that skip `app.tenant_id`.
- Do not store money as floating point.
- Do not delete or rewrite financial records.
- Do not let AI output execute without validation.
- Do not add a new external dependency without saying why in the PR.
- Do not disable a failing test to make CI pass.
