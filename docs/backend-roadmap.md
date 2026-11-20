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
| D7 | Persistence | Spring Data JPA + Hibernate over the Flyway-owned schema: `ddl-auto=validate`; columns `app_user` may not write are `insertable = false, updatable = false`; foreign keys as plain IDs, no associations; insert-only tables `@Immutable`; row locks, `on conflict` and the tenant context stay native SQL with bind parameters; `open-in-view` off; second-level and query caches off | Familiar repositories and `@Version` locking, without letting Hibernate own the schema or write columns the database forbids. |

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
- [x] 1.3 Tenancy schema: `tenants`, `user_profiles`, `memberships` (one active membership per user, D4), `invites`, `staff_skills`, `staff_leave`, `audit_log`
  - `tenants` and `user_profiles` go on the gate's platform-wide list with their exact policies; `audit_log` is insert-only (update revoked). Composite tenant foreign keys from here on, enforced by the gate.
- [x] 1.4 Tenant context: `set_config('app.tenant_id' | 'app.user_id' | 'app.role', …, true)` at the start of every transaction; the app's connection switches to `app_user`
  - The app connects through Supabase's transaction pooler, so its JDBC URL needs `prepareThreshold=0`. Flyway uses a direct or session-pooler connection.
  - Built as: `TenantScope` (thread-local, not inherited, always cleared), a tenant-aware JPA transaction manager that sets the context on the transaction's own connection at begin, a connection guard that refuses statements outside a tenant transaction, `@TenantId` from the same context, and a repository advice that requires a context before any repository call.
  - Hibernate's schema validation needs one statement at startup (sequence metadata), so the guard allows statements on the startup thread only, until every singleton exists and before the web server starts; Spring Data's startup sessions get a placeholder tenant that matches nothing. Tests prove both are closed afterwards.
- [x] 1.5 Supabase token verification with Spring Security, membership lookup, deactivated user rejected on the next request (P13)
  - The membership lookup runs before the tenant is known: a narrow user-only context calls one allowlisted `SECURITY DEFINER` function that takes no tenant argument, uses only `app.user_id`, and returns only that user's active membership.
  - Built as: JWKS verification with ES256/RS256 only (no HS256), checks on issuer, audience, expiry, `role = authenticated`, `is_anonymous` not true and `sub` a UUID; `northstar.current_membership()` called in the guard's lookup mode on every request (no cache); 401 `unauthorized` / `invalid_token`, 403 `no_active_membership`. The gate now also requires every allowlisted `SECURITY DEFINER` function to pin `search_path` and not be executable by `public`, `anon` or `authenticated`.
  - P13's second half (flagging a deactivated member's open leads and future appointments for reassignment) comes with leads and appointments in Phases 3 and 6; 1.8 delivers the deactivation and the refusal on the next request.
- [x] 1.6 API conventions: `/api/v1`, `{code, message, details}` errors, other tenant's record → 404 (P9)
  - Built as: `com.northstar.api` with one error mapper used by the exception handler, an error filter wrapping Spring Security, and a JSON `/error` fallback; `NotFoundException`, `ConflictException`, `ForbiddenException`; strict JSON for API bodies; the `/api/v1` prefix on every `@RestController`; a request ID on every response and log line, with `tenant_id` and `user_id` inside a tenant scope. Filter order: request ID, error filter, Spring Security.
- [x] 1.7 Create business and first admin; `GET /api/v1/me`
  - Add the create-business endpoint to `SecurityConfiguration`'s membership-optional list: a signed-in user with no active membership must be able to call it, without a tenant scope.
  - Creating a business sets `app.tenant_id` to the new tenant's ID (and `app.role` to `admin`), then inserts the tenant and the first admin membership in one transaction.
  - New migration: check constraint `tenants.id <> '00000000-0000-0000-0000-000000000000'`. The tenant guard's startup placeholder uses the all-zero UUID, so no real tenant may ever have it.
  - Built as: `POST /api/v1/businesses` and `GET /api/v1/me`, both membership-optional. One transaction in a tenant scope for the new ID (role admin) writes the profile (if new), tenant, admin membership and a `business.created` audit entry. Slugs: format and reserved words in `Slugs`, checked first through the allowlisted `northstar.slug_is_taken(text)`; a supplied taken slug is 409 `slug_taken`, a reserved one 409 `slug_reserved`, a generated one gets `-2`…`-20` then a random suffix. An existing member gets 409 `already_has_business` (D4).
- [x] 1.8 Members: list, change role, deactivate; at least one active admin always (P14); audit entries
  - Add a profile-update endpoint (the signed-in user's own `full_name`, `phone`, `avatar_path`), so `GET /api/v1/me` can show a name.
  - Member and invite writes are admin-only in the database (restrictive policies); the endpoints also check the role and return a clear error instead of a silent no-op.
  - Column-level update grants: `memberships` only `role`, `status`, `whatsapp_phone`; `invites` only `revoked_at`. The entities map every other column as `updatable = false`.
  - Built as: admin-only `GET /members`, `PATCH /members/{id}/role`, `POST /members/{id}/deactivate|reactivate`, and `PUT /me/profile` (name and phone) for members. Staff get 403 `admin_only` whatever the ID; another tenant's ID is the same 404 as a missing one, and every by-ID miss is logged with its record type and UUID (P9). Changes are idempotent and audited with `{"from", "to"}` codes. P14 is enforced in the database by `northstar.keep_an_active_admin()`: a per-tenant advisory lock plus a re-check when an active admin stops being one, refusing REPEATABLE READ; the API answers 409 `last_admin`, and deadlocks or lock timeouts 409 `conflict_retry`. The RLS release gate now pins the column grants of `memberships`, `invites`, `tenants` and `user_profiles` exactly.
  - Later: the second half of P13 (in-flight jobs and AI conversations for a deactivated member) lands in Phases 3 and 6. The onboarding gate stays in Phase 2.
  - Needed before staff WhatsApp notifications are built (step to be placed): an admin endpoint to set a member's `whatsapp_phone`, with masked display.
- [x] 1.9 Invites: create, revoke, accept; single-use, expiry (P12)
  - Acceptance runs before the user has tenant context, so it uses one allowlisted `SECURITY DEFINER` function that looks up only by token hash and returns the minimum needed; the endpoint calling it is rate-limited.
  - That function bypasses RLS, so it must itself check that the token is unexpired, unrevoked and unused; that the logged-in user's email matches the invite; that the user has no other active membership; and that the membership is created only in the invite's tenant.
  - Built as: admin-only `POST /invites` (201), `GET /invites` (open invites, `pending` or `expired`), `POST /invites/{id}/revoke` (a second revoke is a 200 no-op; accepted is 409 `invite_already_accepted`) and `POST /invites/{id}/resend` (pending or expired only; 409 on accepted or revoked; locks and revokes the old invite, then inserts the new one in the same transaction). One pending invite per email, ignoring case: 409 `invite_pending` with `details.inviteId`. The raw token (32 random bytes, base64url) exists only in the invite link `NORTHSTAR_APP_URL/invite#token=…`, returned once by create and resend; only its SHA-256 is stored, and it is never listed, logged or audited. Audit entries `invite.created`, `invite.resent`, `invite.revoked`, `invite.accepted` carry IDs and fixed codes only, never the email.
  - `POST /invites/accept` is membership-optional and calls `northstar.accept_invite` (`data-model.md` section 12) in the guard's acceptance mode. Answers: 404 `invite_not_found`, 403 `invite_email_mismatch`, 410 `invite_used` / `invite_revoked` / `invite_expired`, 409 `already_has_business`; no error body contains the invite's email, the business name or the tenant ID. Rate-limited in memory to 10 attempts per 10 minutes per user (429 `rate_limited`). Supabase email confirmation is on locally and is a `deploy-checklist.md` item for every hosted environment.
- [x] 1.10 Job and action core: JobRunr (own schema), worker sets tenant context from the job, `actions` table with idempotency key and statuses, retryable / permanent / auth classification, retry table; no tenant context → fails loudly (P10)
  - JobRunr's tables are created by a Flyway migration as `migrator`, with JobRunr's own table creation turned off. Only the dedicated `jobrunr_user` role gets DML (including delete) on them, through JobRunr's private pool; `app_user` gets nothing there and `jobrunr_user` nothing in `northstar`. JobRunr's tables go on the gate's platform-wide list, and the gate checks the schema is isolated both ways.
  - Built as: JobRunr 9.0.2 core (not the Spring Boot starter, which offers no hook for job filters), wired in `com.northstar.jobs` on a private pool (5 connections, `prepareThreshold=0`, transaction pooler: JobRunr uses no advisory locks, LISTEN/NOTIFY or session state). No scheduler beans: jobs are created only through `Jobs`, after the surrounding transaction commits, with stable job IDs so a repeat creates nothing; inside a tenant scope only for that tenant. The worker's activator runs only tenant and platform job handlers. Worker on only with `NORTHSTAR_WORKER_ENABLED=true`; no dashboard; JobRunr's own retries off. Actions in `workflow`: `Actions` (create, approve, failed list, resume paused), `ActionHandler` with a timeout under the 5-minute lease, the executor (claim under a row lock → call outside any transaction → record), `RetryPolicy.STANDARD_BACKOFF`, `ActionAlerts` (logged for now), and a 30-second sweeper through `northstar.tenants_with_due_actions()`.
  - Later: notifying the business admin on alerts (1.12 email, Phase 2 WhatsApp); integration status on auth failures and the resume trigger (Phase 2 / 7); retry numbers for each other action type when it is built; Failed Actions endpoints (Phase 3).
  - `Actions.approve` is admin-only for now (403 `admin_only` for staff). Step 3.7 sets the per-role approval rules from `workflow-spec.md` §9 (staff approve most types; refunds and discounts above the limit stay with admins).
- [x] 1.11 Fake-adapter test kit: duplicates, delays, timeouts, 429, auth failures
  - Built as: `com.northstar.fakes` in test sources. `FakeProvider` plays scripted `FakeCall`s per idempotency key (success, 5xx, client timeout, 429 with Retry-After, rejection, expired credentials, a hang past the handler's timeout, a performed call whose response is lost, a gate, an unexpected exception) and counts calls and side effects per key; it does not honour idempotency keys unless a test turns that on. `FakeClock` drives every delay (the executor stays on system time). Inbound, `WebhookScript` and `HmacSigner` build HMAC-SHA256-signed deliveries, delivered twice, out of order, late, badly signed or unsigned; header names and prefixes come from 2.2 and 5.5. No production code: adapters report through 1.10's `ActionOutcome` and `FailureClass`.
  - Tests never reach the network: Surefire's argLine appends `-Djdk.net.hosts.file` with a hosts file that knows only `localhost` (literal IPs are not blocked).
- [x] 1.12 Email adapter (Resend) behind the integrations interface; invite email sent as an action
  - The send action issues or rotates the invite token at send time, and the invite API stops returning the link.
  - Workers run as the system actor, which the admin-only policy on `invites` denies, and `token_hash` has no update grant. Rotating the token from the worker needs its own fix (for example a narrow allowlisted function); decide it in this step's plan.
  - Built as: `integrations.EmailSender` with a Resend adapter on Java's `HttpClient` (no SDK) and a local-only Mailpit adapter; the first lenient provider JSON mapper. Create and resend queue an `invite.email` action (payload: the invite ID) in the same transaction and return only the invite. The new `notifications` module runs it: the token is `HMAC-SHA256(NORTHSTAR_INVITE_TOKEN_KEY, "invite-token:v1:" + actionId)`, set through the allowlisted `northstar.set_invite_token` (system actor only, pending invites in its tenant), and sent under the action's idempotency key, so a lost response never sends a second, different email. Tenancy stays independent through two ports, `InviteEmailQueue` and `InviteDelivery`; `ModuleDependencyTests` fails on any module cycle or any use of another module's `.internal`.
- [x] 1.13 Minimal lead tables: `contacts`, `workflows`, `workflow_versions`, `leads`, with the staff policy on `leads` (P11)
  - The staff policy is restrictive; the gate fails on a second permissive policy.
  - The staff policy must let the system actor (`app.role = 'system'`) through, or workers cannot act on leads.
  - Add the composite foreign key from `actions (tenant_id, lead_id)` to `leads`.
  - Built as: four migrations with composite FKs, RLS and `tenant_isolation`; every §13 lead index, including one open lead per contact (decided 13 Nov 2026). `leads_staff_assigned` is restrictive: admins and the system actor see the tenant's leads, staff only those assigned to their own active membership (a subquery on `memberships`, no new context). Published `workflow_versions` are insert-only for `app_user` and refused by triggers for every role (SQLSTATE NSW01). A workflow's current version must be its own. Only a minimal `Lead` entity in `workflow`; P9 and P11 are shown through a test-only endpoint in test sources.
- [x] 1.14 Cross-tenant suite P9, P10, P11 runs on every PR
  - Built as: the CI job `cross-tenant` runs every test named `P9_`, `P10_` or `P11_` (`-Dtest='*Tests#P9_*+P10_*+P11_*'`); `CrossTenantSuiteGuardTests` fails, by name, on a case test the pattern would miss. `CrossTenantLeakTests` reads every tenant table from the catalogue, seeds one row per tenant from `TenantFixtures` (an unregistered or stale table fails by name), and checks admin, staff, the system actor and no context: tenant A sees and changes nothing of tenant B, with a positive control, plus `tenants` and `user_profiles`. `EndpointIsolationTests` walks Spring's request mappings: every main-code endpoint taking an ID, any method, gives the same 404 for another tenant's record as for a missing one. A new tenant table needs a fixture; a new endpoint needs nothing, unless its ID or body is unusual.
- [ ] 1.15 Staging deploy pipeline (needs DigitalOcean and the Supabase staging project from Step 0; may slip without blocking Phase 2)
  - **Status (19 Nov 2026): pipeline built and verified up to the first deploy; app creation deferred for budget; resume with `docs/staging-runbook.md`, section First deploy, step 7.** Verified on staging: `roles.sql` and `verify.sql` (18 PASS), pooler logins for `migrator`, `app_user` and `jobrunr_user`, the deploy token's registry login and image push. `deploy-staging` is opt-in through the repository variable `STAGING_DEPLOY_ENABLED` (unset: skipped); `DeployWorkflowTests` requires the switch, a push to `main` and both green checks. Still to verify once the app exists: the pre-deploy job (and a failed deploy keeping the old version), tag deletion and garbage collection with the token's scopes, and the smoke test.
  - Run `db/bootstrap/roles.sql` on the staging project and verify it there first: hosted `postgres` is not a full superuser.
  - Flyway uses a direct or session-pooler connection; the app uses the transaction pooler with `prepareThreshold=0`.
  - Verify `jobrunr_user` there too. Deploy the worker as its own container with `NORTHSTAR_WORKER_ENABLED=true`; API containers leave it off.
  - Built as: one image (`backend/Dockerfile`, base images pinned by digest, non-root, allowlist `.dockerignore`) for the `api` service, the `worker` and a `migrate` pre-deploy job on App Platform in Bangalore (`.do/staging.app.yaml`; every variable per component, secrets only in DigitalOcean, the migrator's only on `migrate`). Migrations run through a small runner in `db/migrate/` (its own build on Spring Boot's Flyway and driver versions, the Maven plugin's settings, environment-only configuration, output limited to versions and SQL states), which `MigrationRunnerTests` proves builds exactly the schema the tests build. The CI job `deploy-staging` runs only on `main` after `backend` and `cross-tenant`: it pushes `app-<sha>` to the container registry, fails if the live app drifts from the spec (names and types only, never values), then sets that tag on the live spec, so encrypted secrets are never resubmitted from the repo. The app, JobRunr's pool and the runner refuse a database URL that carries a password; `StartupLogsTests` shows no secret in startup output. `db/bootstrap/verify.sql` prints PASS/FAIL per check, BYPASSRLS first. How to deploy, roll back and rotate: `docs/staging-runbook.md`.
  - The container registry stays on Starter (500 MiB, 1 repository) until production: after each successful deploy the job keeps only the deployed tag and the one before it, starts garbage collection and warns above 80% of the limit; rollback re-runs an older green job, which rebuilds that commit. **Before production:** move the registry to Basic and raise the kept tags to 20 (`DO_REGISTRY_KEEP_TAGS`, `DO_REGISTRY_LIMIT_MIB`; `docs/deploy-checklist.md`).
  - Done when the first staging deploy passes the runbook's smoke test and `verify.sql` passes there (after the migrations).
  - Staging email: Resend's shared test sender (`onboarding@resend.dev`), which delivers only to the account owner; other invites fail as `sender_not_allowed` until a staging sub-domain is verified.

**Decided:** D4 (one active membership per user), D6 (email login only).
**New dependencies:** Spring Security OAuth2 resource server, JobRunr.
**Before the frontend phase:** CORS for the React app: `http://localhost:5173` locally and each environment's real domain through config, with credentials and the `Authorization` and `X-Request-Id` headers allowed and `X-Request-Id` exposed to the browser. On staging, change `NORTHSTAR_APP_URL` from `http://localhost:5173` to the staging frontend's address (`.do/staging.app.yaml` and the dashboard), and set Supabase Auth's Site URL, Redirect URLs and custom SMTP (`docs/deploy-checklist.md`).
**Frontend note (invites):** the invite page reads the token from the URL fragment, then clears it from the address bar with `history.replaceState` before calling `POST /api/v1/invites/accept`.

## Phase 2 — WhatsApp and conversations

**Specs:** `integration-specs.md` §1, §2, §7; `workflow-spec.md` §6, §12; `data-model.md` §4, §9.
**Modules:** `integrations`, `contacts`, `crm`.

- [x] 2.1a Integration schema: `integrations`, `secrets.integration_credentials`, `whatsapp_accounts`, `webhook_events`, `ingest.unrouted_webhooks`; the `secrets_user` role and the `secrets` and `ingest` schemas
  - Decided 19 Nov 2026 (the Phase 2 questions): webhooks are routed before they are stored, so `webhook_events.tenant_id` is not null and unroutable deliveries are parked in the platform-wide `ingest.unrouted_webhooks`, readable only by `app_admin`; `integration_credentials` lives in the `secrets` schema, which only the worker's own login `secrets_user` can use; the encryption key lives only in the worker (`integration-specs.md` section 7).
  - Built as: `roles.sql` creates `secrets_user` (no BYPASSRLS) and the `secrets` and `ingest` schemas, owned by `migrator` and closed to `public`; `verify.sql` checks them. Five migrations, each table with `unique (tenant_id, id)`, RLS forced and `tenant_isolation` (for `secrets_user` in `secrets`), composite foreign keys, and column-level update grants: `integrations` (status, account_label, scopes, credential_id, last_success_at, last_error_code, last_error_at), `whatsapp_accounts` (display and quality columns only; `phone_number_id` unique across tenants), `webhook_events` (lifecycle: processed_at and error only; `unique (provider, provider_event_id)` across tenants), and `secrets.integration_credentials` (granted only to `secrets_user`). `ingest.unrouted_webhooks` has no tenant_id; its checks keep no payload or routing key for a bad signature and no payload over 64 KB for an unknown account, with a partial unique on verified provider event IDs; only `app_admin` may select from it. The RLS release gate scans the four schemas, checks `secrets` as isolated to `secrets_user` (including the role of its `tenant_isolation`) and `ingest` as admin-read only, and declares the new lifecycle and column-restricted tables. `TenantFixtures` covers the four tenant tables, and the cross-tenant suite checks the `secrets` table through `secrets_user`. JPA entities `Integration`, `WhatsAppAccount` and `WebhookEvent` in `integrations.internal`; the credentials table has no entity (2.1b reads it on its own pool).
- [x] 2.1b Credential encryption: AES-256-GCM cipher, keyring, worker-only credential store on its own `secrets_user` pool, staging variables (`integration-specs.md` section 7)
  - Re-encryption under the active key is a store method with a test; the job that runs it for every tenant, and its runbook steps, come later. Until then the runbook says never to remove a key.
  - Deploy checklist: an offline copy of every credential key, and `secrets_user`'s login and password on the worker only.
  - Built as: in `integrations.internal`, all package-private. `Secret` (plaintext holder: `toString()` always `Secret[redacted]`, identity equality, not serializable, wiped on `close()`); `CredentialKeyring` (parses `NORTHSTAR_CREDENTIAL_KEYS` and `NORTHSTAR_CREDENTIAL_ACTIVE_KEY`; errors name the setting, entry position or a valid key ID, never key material, and keep no cause); `CredentialCipher` (JDK AES-256-GCM, format byte `0x01` + random 96-bit nonce + ciphertext and 128-bit tag, associated data `credential:v1|tenant|credential|provider|key_id`; failures are `CredentialDecryptionException` with a fixed reason code and no cause); `CredentialStore` (on its own Hikari pool as `secrets_user`, system actor only, one transaction per call setting the tenant context, explicit tenant filter: `insertIfAbsent`, `read`, `replace`, `reencryptUnderActiveKey`); `CredentialsConfiguration` (only with `NORTHSTAR_WORKER_ENABLED=true`; the store is the only bean, and its pool, keyring and cipher are not; refuses at startup, before connecting, missing or malformed keys, a database URL with a password, or a missing `SECRETS_DB_USER` / `SECRETS_DB_PASSWORD`). Settings `northstar.credentials.*`; `.do/staging.app.yaml` gives the four variables to the worker only, which `StagingAppSpecTests` enforces. Tests include tampering at every byte, the wrong and unknown keys, rotation, a database copy of one tenant's ciphertext into another's row, and mutation checks on the associated data (each part changed alone, the documented format rebuilt with plain JCE, and a control without associated data) and on the redacted `toString` (every rendering, including logs, hex, base64 and records). With JDBC tracing on, no plaintext, key or password is printed. `SystemScope` in the jobs test package runs worker-only code as the system actor.
- [x] 2.2 `POST /webhooks/whatsapp`: verify signature (P15) → resolve tenant by phone number ID (P16) → store in the tenant's scope and dedupe (P1) → queue → 200
  - Bad signatures and unknown phone number IDs go to `ingest.unrouted_webhooks` through one allowlisted `SECURITY DEFINER` function in a narrow guard mode (as the membership lookup does). Anyone can POST to the webhook URL, so this unrouted write path needs rate limiting; decide the limits in this step's plan.
  - Resolving the tenant before it is known needs its own allowlisted lookup on `whatsapp_accounts.phone_number_id`.
  - Extend the cross-tenant suite: a webhook routed to tenant A never reads or writes tenant B, named `P9_`/`P10_` so the `cross-tenant` job runs it. `webhook_events` and every new tenant table need a `TenantFixtures` entry.
  - Decided 19 Nov 2026 (this step's plan): a new `webhook` role whose context only the routing service creates, for a tenant the lookup returned, and in which the guard allows only the `webhook_events` insert; the job only confirms its row (processing and the unprocessed-rows sweep are 2.4); non-message changes and ID-less errors are counted and not stored until 2.8; a signed but unreadable body is a 200 with one ERROR line; global rate limits (30 and 120 a minute per instance, 60 and 600 across instances); an unknown number's row holds only its own slice; endpoints active whenever both secrets are set, an API container refuses to start without them, a worker-on process answers 503; the P16 alert is a WARN line until Phase 8.
  - Built as: `GET` and `POST /webhooks/whatsapp` (`integrations.internal.WhatsAppWebhookController`, a `@Controller` outside `/api/v1`) on their own security chain, matched by exact method and path, with no token. POST refuses a body over 3 MiB (413) before reading past it, checks `X-Hub-Signature-256` on the raw bytes in constant time (401 `invalid_signature`, parked metadata-only through `northstar.park_invalid_signature`, P15), then `WhatsAppWebhookIntake` splits the delivery into items (`message:<wamid>`, `status:<wamid>:<status>`, each with a Meta-shaped slice), routes them through `northstar.whatsapp_tenants()` (`tenancy.WebhookRouting`, guard mode `WHATSAPP_ROUTING`), stores each tenant's items in one transaction in a webhook context with `insert ... on conflict do nothing returning id` (P1) and queues one `ProcessWhatsAppWebhook` job per new row after the commit, and parks unknown numbers' items through `northstar.park_unknown_account` (P16), behind `UnroutedRateLimiter` and the functions' own caps. GET answers the handshake (constant-time token, digits-only challenge as text/plain, else 403). Settings in `WhatsAppWebhookSettings` (`NORTHSTAR_WHATSAPP_APP_SECRET`, `NORTHSTAR_WHATSAPP_VERIFY_TOKEN`, on `api` only in `.do/staging.app.yaml`, enforced by `StagingAppSpecTests`). `TenantContext` gains the `WEBHOOK` role (nil user, created only by `WebhookRoutingService`) and `hasUser()`. The RLS gate allowlists the three functions. Tests: P1, P15, P16, P9 and P10 cases in `WhatsAppWebhookTests`, `WebhookRoutingTests`, `WhatsAppDeliveryTests` and `WebhookSignatureTests`, the rate limit (`WhatsAppWebhookRateLimitTests`, `UnroutedRateLimiterTests`), the three startup cases (`WhatsAppWebhookSettingsTests`, `WhatsAppWebhookNotConfiguredTests`), the body limit, the security scope and the logs.
  - Also in this step: every pool turns off the driver's server error detail (`logServerErrorDetail=false`: the app's, JobRunr's, the worker's credential pool and the migration runner's), so "Key (...)=(...)" and "Failing row contains (...)" never reach a log inside an exception message; the SQL state and constraint name are kept. Hikari hands data-source properties to the driver as `Properties`, which reads only String values, so JobRunr's and the credential pool's `prepareThreshold=0`, set as a number since 1.10 and 2.1b, had been ignored; both are now strings. `DatabaseErrorDetailTests` checks each pool's effective settings and logs a duplicate on a marker value through each (on a temporary table, as Postgres already hides values for tables with RLS), with a control on the driver's default.
- [ ] 2.2a Resend webhooks: bounces and complaints recorded through the webhook pipeline (verify, store, dedupe, queue); repeated bounces mark the address invalid (`integration-specs.md` §6). Decide the table for invalid addresses in `data-model.md` first.
- [x] 2.3 Conversation schema: `contact_sources`, `conversations`, `messages`, `message_templates`
  - Add the composite foreign key from `leads (tenant_id, conversation_id)` to `conversations`, unlinked since 1.13.
  - `conversations` gets the same restrictive staff policy pattern as `leads` (assigned membership; admins and the system actor see all), declared in the gate.
  - Decided 19 Nov 2026 (this step's plan): staff see messages only through their assigned conversations, and contacts and contact sources only while one of the contact's leads (open or closed) or its conversation is assigned to them; templates are read by everyone in the business and written only by admins and the system actor. No column encryption for message bodies (`data-model.md` section 15); instead `app_admin` cannot select `messages.body` or `webhook_events.payload`. `window_expires_at` is a stored generated column. Column tiers: `messages` lifecycle, `conversations` and `message_templates` column-restricted, `contact_sources` insert-only. Clearing bodies for retention is 8.6. Statuses, message types, sources and the message checks as proposed in the plan.
  - Built as: eight migrations. `whatsapp_accounts` gains unique (tenant_id, phone_number_id); `conversations` (one per contact and channel; composite FKs to contacts, the tenant's WhatsApp number and memberships; `window_expires_at` generated from `last_inbound_at` in UTC); `message_templates` (unique per name and language); `messages` (unique (tenant_id, provider_message_id), P1; the inbox index; checks tying direction, sender, template, status and provider ID together; `media_path` under `tenant_id/`; trigger `messages_set_once` keeps `provider_message_id` and `media_path` set once for every role, SQLSTATE NSM01); `contact_sources` (one row per distinct source, nulls not distinct); `leads_conversation_fk` on (tenant_id, contact_id, conversation_id), so a lead's conversation is its own contact's; `contacts_staff_assigned` and `contact_sources_staff_assigned` with the new `leads (tenant_id, contact_id)` index; and `app_admin`'s column grants on `webhook_events`. Every table has `unique (tenant_id, id)`, RLS forced, `tenant_isolation`, column grants for insert and update and no delete. The RLS release gate pins the five new staff and template policy sets and the tiers. No entities or repositories yet: 2.4, 2.6 and 2.8 map what they write. Tests: `ConversationSchemaTests` (references, keys, the window across a DST change, P1, set-once, the checks, grants, `app_admin`), `contacts.ConversationIsolationTests` (P11 for conversations, messages, reassignment, contacts and templates; P10 for the webhook role and unknown roles), gate probes, four new `TenantFixtures` entries (the contact fixture is the contact of the staff member's conversation), and `WebhookRoutingTests` probing the new tables. The cross-tenant suite leaves generated columns out of its copied-row insert.
- [ ] 2.4a Engine `events` table, the unprocessed-webhook scan and the webhook sweep
  - Split from 2.4 (decided 20 Nov 2026): 2.4a builds what the processing sits in, 2.4b the processing itself.
  - Built as: two migrations. `events` (`data-model.md` section 6): `type` checked against the fifteen types of `workflow-spec.md` section 6, `dedupe_key` unique per tenant (P1), `payload` a JSON object of at most 4 KB (IDs, fixed codes and timestamps only, never message text; the size check is a backstop), `lead_id` nullable with its composite foreign key; a lifecycle table whose only updatable column is `processed_at` (set by the engine, Phase 3); RLS forced, `tenant_isolation`, and the restrictive `events_staff_assigned` (staff see and write the events of leads assigned to them; an event with no lead is for admins and the system actor only). `northstar.tenants_with_unprocessed_webhooks(p_older_than interval)` (allowlisted, tenant IDs only: WhatsApp `message:` rows with `processed_at` null received more than `p_older_than` ago, on the database's clock; status rows wait for 2.7, which replaces it) and the partial index `webhook_events (tenant_id, received_at) where processed_at is null`. The tenant guard's new unprocessed-webhook scan mode allows only that call, through `TenantDirectory.tenantsWithUnprocessedWebhooks(Duration)` (the two tenant scans now share one helper). In `integrations.internal`: `UnprocessedWebhookSweeper`, a recurring platform job every minute that queues one `SweepTenantWebhooks` per tenant per interval; `TenantWebhookSweeper`, which reads up to 2,000 of the tenant's oldest waiting rows (`UnprocessedWebhookEvents`, native SQL with the tenant filter) and follows `WebhookRetrySchedule`: retry steps at ages 2 min (the grace period), 10 min, 1 h and 6 h, each queued under its own job ID (`ProcessWhatsAppWebhook.retryJobIdFor`, as JobRunr's retries are off and it skips an ID it already has) and only within 10 minutes of reaching it (each skipped ID would cost a failed insert and an INFO line); a failed row (`error` set) is closed at 24 h, `processed_at` set and its code kept, with an ERROR `webhook.failed ... final=true`; a row with no failure recorded is never closed, and past the last step it is queued under that step on every sweep (a no-op once the job exists). Tests: `EventsSchemaTests` (P1, the checks, the foreign key, the lifecycle grants, P11, P10), `UnprocessedWebhookScanTests` (the scan, the database clock, the lock-down, the P10 guard mode), `WebhookRetryScheduleTests`, `WebhookSweepTests` (a lost job, the grace period, status rows, one job per step, the 24-hour close, a row never attempted, P9 per tenant, the logs); `TenantFixtures` and the RLS release gate declare `events` and the function.
  - Until 2.4b: the 2.2 handler is unchanged, so a re-queued job only confirms its row, every message row stays unprocessed with no error, and a tenant's sweep queues the last step again for rows older than 6 h 10 min on every sweep (no-ops, each with JobRunr's INFO line).
- [ ] 2.4b Inbound job: normalise to an `events` row; upsert contact by normalised phone; join the open lead's conversation (W1); lead created in one transaction (P3)
  - Replaces the body of `ProcessWhatsAppWebhookHandler` (since 2.2 it only confirms its row and logs `webhook.queued`) and sets `processed_at` and `error`; 2.4a's sweep retries what it leaves unprocessed, including rows stored since 2.2.
  - From 2.3: store messages with `insert ... on conflict (tenant_id, provider_message_id) do nothing` (P1). An inbound message's `sent_at` is WhatsApp's timestamp, and `conversations.last_inbound_at` is set from it and only moves forward (`greatest(last_inbound_at, :sent_at)`), so a late or redelivered webhook never stretches the window past Meta's. `contact_sources.first_seen_at` may be set from the same timestamp. A new lead's `assigned_membership_id` and its conversation's are set in the same transaction (they are separate columns, each with its own staff policy).
  - Decided 20 Nov 2026 (the 2.4 plan):
    - One transaction per webhook event, as the system actor: lock the row (`processed_at` set: nothing to do; a status row: left alone); parse the slice; contact (insert on conflict, then `select ... for update`, which serialises one contact's messages so the lead decision cannot race, P3); contact source; open lead; conversation; message; lead; event; `processed_at`. `integrations` parses and sequences, `contacts` writes contacts, sources, conversations and messages, `workflow` owns leads and events; their public methods are `@Transactional(propagation = MANDATORY)`. The one new module edge is `integrations` → `contacts`.
    - Workflow and stage: the tenant's one workflow with a current version; every lead is created in stage `new`, not read from the config (3.1). No published workflow, or more than one: the contact, source, conversation, message and event (no lead) are stored, `processed_at` is set with `no_published_workflow`, and a WARN is logged; the next message after publishing opens the lead.
    - Origin and source: `direct`. The `referral` object is not read yet; see Phase 7, before any live tenant traffic.
    - Assignment: nothing is auto-assigned. A new lead takes its conversation's assignee, a new conversation takes the open lead's, and both new are both unassigned. 2.4b never changes an existing assignee; an existing mismatch is 3.9's.
    - Contact name: WhatsApp's profile name fills an empty `contacts.name` only, never overwrites one, and is never logged.
    - Message types: text → `text`; button_reply, list_reply and `button` → `interactive` (body the reply's title or text; the button ID stays in the payload for Phase 5); image and document → their type, body the caption; audio → `audio`, body null; location → `location`, body null (the coordinates stay in the payload for 4.5, W4); video, sticker, reaction, contacts, order, system and anything unknown → `unsupported`, body null. Media: `media_path` stays null, nothing is downloaded and no media ID is stored (4.5 reads it from the payload, kept 30 days). Reaction and system messages are stored and join an open lead if there is one, but never open a lead on their own; every other type, unsupported ones included, follows the normal lead rule.
    - W2: a contact whose lead is closed gets a new lead on the same contact and conversation, created in New (3.10 moves returning contacts on). Completed sets `closed_at` too, so a returning customer also gets a new lead; Cancelled does not yet (3.3).
    - Failures. Retryable (`processed_at` null, `error` set, retried by the sweep): `database_unavailable` (SQLSTATE 08xxx, no connection), `database_conflict` (40001, 40P01, 55P03, 23505), `database_timeout` (57014), `processing_failed` (anything unexpected). Permanent (`processed_at` and `error` set, ERROR line): `payload_invalid`, `contact_phone_invalid` (a `from` that is not E.164, P4). `MissingTenantContextException` and `JobTenantException` are rethrown, never recorded. Failure logs carry the code and the exception class only, never an exception message from parsing.
    - Events: `message.received`, dedupe key `message.received:<wamid>`, payload IDs, fixed codes and the WhatsApp timestamp only.
    - Status rows stay untouched (`processed_at` and `error` null) until 2.7, so statuses for 2.6's sends are not lost if 2.6 merges first.
    - `timeline_events` is deferred to 3.3; 2.4b writes none.
    - Tests: W1 (an open lead's conversation; an open lead with no conversation linked), W2, P3 (two messages from a new contact at once; a failure midway, then the retry), P1 (re-run, concurrent runs, replay with `processed_at` cleared), P4, P9 (a two-tenant batch; a job naming another tenant's event), reaction and system messages never opening a lead, assignment, the profile name, `last_inbound_at`, events holding IDs only, and logs without text, phone numbers or wamids.
- [ ] 2.5 Opt-out checked first on every inbound message; cancels all scheduled outbound actions (W32)
- [ ] 2.6 Send action: WhatsApp adapter (real and fake), 24-hour window, templates only outside it (W33)
  - From 2.3: an outbound message is stored as `queued` with no `provider_message_id`, and the send sets it once from Meta's response (`messages_set_once`). The action's payload carries the message ID, never the text, so retention can clear the text in one place. The window check reads `conversations.window_expires_at`.
- [ ] 2.7 Delivery status webhooks update messages
  - Statuses must only move forward when Meta's webhooks arrive out of order (`messages.status` is updatable since 2.3, with no order enforced yet): decide a trigger or a guarded update here.
- [ ] 2.8 Template sync and status; rejected or paused templates fail dependent actions visibly (W34)
  - Define the allowed values of `whatsapp_accounts.quality_rating` and `messaging_limit` (unchecked text since 2.1a) and add their checks.
  - Route webhook changes that are not `messages` (template status, quality and account updates) by the WABA ID in `entry.id`, which needs its own allowlisted lookup on `whatsapp_accounts.waba_id`. Since 2.2 they get a 200 and are only counted as ignored; subscribe their webhook fields in Meta's dashboard only once this ships (`docs/staging-runbook.md`, WhatsApp webhooks on staging).
- [ ] 2.9 Inbox API: conversations, messages, staff reply, take over and hand back (pause automation per conversation)
  - Staff queries start from their conversations and join to messages: `messages_staff_assigned` costs one probe on `conversations` per message scanned, cheap for one thread, costly for a wide scan such as a search across every message. Reassigning a conversation changes its lead's `assigned_membership_id` in the same transaction (2.4b, 3.9).
- [ ] 2.10 Manual lead entry with E.164 validation (P4)
  - Since 2.3 staff cannot insert contacts (`contacts_staff_assigned` checks writes too, and a new contact has no lead yet). Decide here how a staff member adds one: for example the contact and its assigned lead in one transaction with a split insert rule, or entry by the system actor on their behalf.

**Decided (19 Nov 2026, step 2.1):** `webhook_events.tenant_id` stays not null, with unroutable deliveries in `ingest.unrouted_webhooks`; only `secrets_user` reads `integration_credentials`; the key lives only in the worker's environment.
**External:** Meta developer app and a WhatsApp test number.

## Phase 3 — Workflow engine

**Specs:** `workflow-spec.md` §2–§10, §13–§17; `build-document.md` §6; `data-model.md` §6, §8 (`tasks`), §10.
**Modules:** `workflow`, `crm`.

- [ ] 3.1 Workflow config format (§15) with validation; publish makes a version immutable
  - Every config needs a `new` stage: 2.4b creates every lead in it without reading the config (decided 20 Nov 2026).
- [ ] 3.2 Reelrr template as seed data (§4, §14)
- [ ] 3.3 Lead state machine: only transitions allowed by the lead's version; `timeline_events` on every change; optimistic lock on `version` (P7)
  - Every closing stage (Junk, Lost, Completed) sets `closed_at`, so the one-open-lead-per-contact index frees the contact (W2).
  - The engine checks that a lead's stage exists in its version's config; the database does not (decided in 1.13).
  - The engine enforces one active workflow per tenant for MVP; there is no database constraint while `data-model.md` §15 is open.
  - Creates `timeline_events` (deferred from 2.4, decided 20 Nov 2026): its types, its writer's module and its staff policy. Rows for messages reference the message by ID and never copy its text (the table is insert-only, so text there could not be cleared for retention).
  - Decide whether Cancelled sets `closed_at` (`data-model.md` §6 lists only Junk, Lost and Completed). Until it does, a message from a cancelled lead's contact joins that lead (2.4b).
  - Engine events recorded before Phase 3 (2.4b's `message.received`, `processed_at` null) exist only in development and staging data; decide whether the engine processes or skips them.
- [ ] 3.4 Events and triggers (§6); conditions and rules (§7)
- [ ] 3.5 Step type executors (§5) producing actions
- [ ] 3.6 Timers (§8): `timers` mirrors JobRunr jobs; quiet hours and reminders in the tenant timezone (P22); cancellation
- [ ] 3.7 Approval mode (§9): `needs_review`, approve and reject API, reminders at 30 min and 2 h (W35)
  - Per-role approval rules per action type replace 1.10's admin-only `Actions.approve`.
- [ ] 3.8 Failed actions API: list, retry, skip; alert on final failure; kill switches per business and platform-wide
- [ ] 3.9 Leads API: reassign with history (P5), admin-only soft delete (P6), notes, tasks, request idempotency key (P21)
  - Staff access to contacts was decided in 2.3 (`contacts_staff_assigned`: the contacts of their own leads and conversations). Reassigning a lead also reassigns its conversation in the same transaction (P5), or the previous assignee keeps the contact through the conversation.
- [ ] 3.10 Contact from a Lost or Junk lead starts a new lead (W2)
  - 2.4b already creates that lead, in New like every lead. `workflow-spec.md` §3 sends a returning contact to Qualifying (Lost / Junk → Qualifying): this step moves it (decided 20 Nov 2026).
- [ ] 3.11 Versioning and migration (§16): in-flight leads keep their version (W36)
- [ ] 3.12 End-to-end engine test on the Reelrr template with fake providers

**Needs answers:** Reelrr defaults (`workflow-spec.md` §18); one or many workflows per tenant (`data-model.md` §15); precedence between tenant and action approval mode. (One open lead per contact, and `closed_at` for Junk, Lost and Completed, were decided on 13 Nov 2026.)

## Phase 4 — AI and scoring

**Specs:** `workflow-spec.md` §11; `build-document.md` §7; `integration-specs.md` §6 (LLM); `data-model.md` §4 (`faq_entries`), §6 (`lead_fields`, `lead_score_history`).
**Module:** `ai` (the only module allowed a provider SDK).

- [ ] 4.1 `AiService` interface, Spring AI adapter, fake adapter
- [ ] 4.2 Tool registry limited to the lead's stage; every call schema-checked, tenant-scoped, permission-checked, audited
- [ ] 4.3 Prompt assembly: customer text is data (W7); FAQ entries
- [ ] 4.4 Qualifying conversation and field extraction with confidence; at most two clarifying questions, then Needs Human (W6)
- [ ] 4.5 Voice notes, images, location pins (W4); reply language per tenant setting (W5)
  - Media is stored under `tenant_id/` (a check on `messages.media_path` since 2.3) and `media_path` is set once.
- [ ] 4.6 Filter rules → Junk with rule ID and reason (W9); AI score, reasons, history, bands
- [ ] 4.7 Provider down: three retries, then Needs Human and one holding message (W8)
- [ ] 4.8 Score dashboard API

**Needs answers:** LLM provider and per-tenant token budget; Hot and Warm thresholds; budget units.
**New dependency:** Spring AI.

## Phase 6 — Scheduling (built before Phase 5)

**Specs:** `build-document.md` §9; `integration-specs.md` §4; `data-model.md` §8.
**Module:** `scheduling`.

- [ ] 6.1 Schema: `calendar_connections`, `busy_blocks`, `slot_holds`, `appointments`, `appointment_assignments`
  - `appointments` gets the same restrictive staff policy pattern as `leads` (assigned membership; admins and the system actor see all), declared in the gate.
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
  - Their entities map every column not declared updatable as `updatable = false`.
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
  - **Before any live tenant traffic** (decided 20 Nov 2026): `ad_referrals` capture, reading the first message's `referral` object with its `ctwa_clid`, must be built first. Until then 2.4b tags every lead `direct`, and the webhook payload that holds the referral is kept only 30 days (`data-model.md` §14), so attribution missed in that time cannot be recovered. Also an open question in `integration-specs.md` §8.
  - Add the composite foreign keys from `leads (tenant_id, ad_referral_id)` and `leads (tenant_id, chat_link_id)`, unlinked since 1.13, and from `contact_sources (tenant_id, chat_link_id)` and `contact_sources (tenant_id, ad_referral_id)`, unlinked since 2.3.
- [ ] 7.2 Sheets sync from the outbox: upsert by ID in batches; quota backoff (P17); deleted sheet or tab (P18); protected-field edits restored and sent to review
- [ ] 7.3 Meta Conversions API events at configured stages, matched on click ID
  - Meta does not deduplicate business-messaging events, so a lost response followed by a retry sends the event twice (`lostResponseWithoutProviderIdempotencyDuplicatesTheEffect` shows it). The send needs its own dedupe first, e.g. on `meta_events (tenant_id, lead_id, event_name)`, checked and recorded before the call.
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
  - Exports join the cross-tenant suite: an export or report run as tenant A contains none of tenant B's rows, named `P9_` so the `cross-tenant` job runs it.
- [ ] 8.2 Onboarding readiness API: each step green or pending, with what is blocking it
- [ ] 8.3 Northstar admin over `app_admin`, every call audited: tenants, failed jobs, webhook errors, usage, pause a business
  - Unknown-account webhooks (P16) alert the Northstar admin here; since 2.2 the alert is a WARN line, `webhook.unknown_account ... unrouted_id=<id>`. The admin reads `ingest.unrouted_webhooks` (select only, audited).
  - `app_admin` gets its own datasource bean, separate from the guarded `dataSource`; the tenant guard wraps only `dataSource`, so this needs its own audited access rules, not a bypass of the guard.
  - Since 2.3 `app_admin` cannot select `messages.body` or `webhook_events.payload` (no `select *` on either table); the webhook-errors view reads IDs, times and error codes.
- [ ] 8.4 Plans, subscriptions, usage alerts at 70 / 90 / 100% (P20)
- [ ] 8.5 Subscription end: grace period, then read-only with automation paused (P8)
- [ ] 8.6 Retention jobs (`data-model.md` §14)
  - Includes deleting `ingest.unrouted_webhooks` rows after 30 days, and clearing `webhook_events.payload` after 30 days (it is not null and not updatable since 2.1a, so this needs a migration or a narrow function).
  - Clearing `messages.body` and `media_path` 12 months after the conversation's last activity (and deleting the stored media): neither is updatable to null since 2.3 (`body` has no update grant; `messages_set_once` refuses changing `media_path`), so this needs a system-only grant or narrow function and a change to the trigger. Consider a `content_removed_at` marker.

**Needs answers:** grace period and read-only rules; plan limits; retention periods.

## Phase 9 — Hardening

**Specs:** `build-document.md` §15–§17; `edge-cases.md` §3.

- [ ] 9.1 Every P and W case that belongs to the backend has a passing named test
- [ ] 9.2 Load test against the launch capacity tier
- [ ] 9.3 Security review: auth, isolation, webhooks, secrets, logs
  - Replace the in-memory invite-acceptance rate limiter (per API instance) with a shared one.
- [ ] 9.4 Backup and restore drill; restored data passes the isolation suite (P24)
- [ ] 9.5 Sentry, alerting, production deploy

**Gate:** every backend edge case passes in CI, and the restore drill works.
