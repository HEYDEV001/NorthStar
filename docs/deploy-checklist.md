# Deploy checklist

Settings every hosted environment (staging, production) must have before the API serves traffic. Local
development gets the same settings from `supabase/config.toml` and `.env.local`. Check each item when an
environment is created and after any change to its Supabase project or app configuration. How to deploy, roll back
and rotate secrets on staging: `docs/staging-runbook.md`.

## Supabase Auth

- [ ] Supabase email confirmation must be ON in every hosted environment; invite acceptance trusts the token's email.
- [ ] Before the frontend phase: Site URL is the environment's frontend address, and Redirect URLs allow only
      `https://<frontend>/**`. Until then nothing is needed: a confirmation link still confirms the address even when
      the Site URL page does not load.
- [ ] Before the frontend phase: custom SMTP for Supabase Auth's own emails (the built-in sender mails only the
      project's team members and is heavily rate-limited).
- [ ] The backend never holds Supabase's secret key, `service_role` key or JWT secret: it verifies tokens against the
      project's public signing keys (`SUPABASE_URL`).

## Database

- [ ] `db/bootstrap/roles.sql` has run on the project and `db/bootstrap/verify.sql` prints PASS on every line
      (hosted `postgres` is not a full superuser). A FAIL stops the deploy; it is never fixed by weakening a policy or
      giving `app_user` more rights.
- [ ] SSL is enforced on incoming connections (Supabase: Database → Settings → SSL Configuration).
- [ ] Flyway uses a direct or session-pooler connection; the app uses the transaction pooler with `prepareThreshold=0`.
      On App Platform both go through the pooler (its direct connection is IPv6 only): `FLYWAY_URL` on port 5432,
      `DB_URL` on port 6543, both with `?sslmode=require`, and pooler user names of the form `<role>.<project-ref>`.
- [ ] No database URL carries a password: the app, JobRunr's pool and the migration runner each refuse to start if one
      does. Passwords go in `DB_PASSWORD`, `JOBRUNR_DB_PASSWORD`, `SECRETS_DB_PASSWORD` and `FLYWAY_PASSWORD`.
- [ ] Every pool sets `logServerErrorDetail=false` (database errors keep their SQL state and primary message, never row
      values) and the transaction-pooler pools `prepareThreshold=0`; Hikari data-source properties are given as
      strings, or the driver ignores them (`DatabaseErrorDetailTests`).
- [ ] Production: `sslmode=verify-full` with Supabase's CA certificate, once it is confirmed to validate the pooler.
- [ ] `jobrunr_user` has a login and its own generated password (`JOBRUNR_DB_USER`, `JOBRUNR_DB_PASSWORD`); JobRunr also uses the transaction pooler (it keeps no session state).
- [ ] `secrets_user` has a login and its own generated password, set only on the worker (never on the api or the
      migrate job). It is the only role that can read tenant credentials (`data-model.md` section 9).

## Tenant credential key

- [ ] `NORTHSTAR_CREDENTIAL_KEYS` was generated for this environment alone (`docs/staging-runbook.md`, Generating the
      credential key): 32 random bytes per entry, never shared with another environment or kept on a laptop.
- [ ] An offline copy of every key in the list is in the team password manager's restricted vault, named with the
      environment and key ID. Losing every copy means every business reconnects its integrations.
- [ ] `NORTHSTAR_CREDENTIAL_KEYS` and `SECRETS_DB_PASSWORD` are encrypted variables on the worker only, and
      `NORTHSTAR_CREDENTIAL_ACTIVE_KEY` names a key in the list (the worker refuses to start otherwise).
- [ ] No key has been removed from the list while values it made may remain (re-encryption job not built yet).
- [ ] Connections fit the plan's pooler client limit: every container opens up to 10 app connections (Hikari's default) plus up to 5 for JobRunr, so 15 per container, and the worker 2 more for credentials (17); at launch (2 API + 1 worker) that is 47, plus Flyway's and operators' sessions. Staging (1 API + 1 worker) uses up to 32.
- [ ] Migrations run only in the deploy's pre-deploy job (`migrate`), as `migrator`. The migrator's credentials are
      set on that job only, never on the api or worker.

## Email (Resend)

- [ ] The sending sub-domain is verified in Resend (SPF, DKIM) before launch; staging uses a sandbox or test domain (integration-specs.md sections 6 and 7).
- [ ] Staging until a sub-domain is verified: `NORTHSTAR_EMAIL_FROM=Northstar <onboarding@resend.dev>`, Resend's shared
      test sender, which delivers only to the Resend account owner's address. Invites to anyone else fail as
      `sender_not_allowed` and show in Failed Actions.
- [ ] `RESEND_API_KEY` is set in the worker container (sending access only), separate per environment; `NORTHSTAR_EMAIL_FROM` is an address on the verified sub-domain.
- [ ] `NORTHSTAR_EMAIL_PROVIDER` is unset or `resend`; `mailpit` is for local development only and refuses any host but localhost.
- [ ] `NORTHSTAR_INVITE_TOKEN_KEY` is 32+ random bytes (base64), generated per environment, in the worker container only. Rotating it affects only invite emails still being retried at that moment: their retries fail with an alert, and the admin resends those invites. Already-sent links keep working.

## WhatsApp webhooks

- [ ] `NORTHSTAR_WHATSAPP_APP_SECRET` is the App Secret of this environment's own Meta app (staging: the separate test
      app), and `NORTHSTAR_WHATSAPP_VERIFY_TOKEN` is 32+ random characters generated for this environment
      (`openssl rand -hex 32`). Both are encrypted variables on the api component only; the API refuses to start
      without either (a worker-on process answers 503 on the webhook paths instead).
- [ ] Meta's webhook Callback URL is `https://<api host>/webhooks/whatsapp` and its Verify and save passed.
- [ ] Until step 2.8, only the `messages` webhook field is subscribed.
- [ ] Only `GET` and `POST /webhooks/whatsapp` are public; every other path still needs a Supabase token
      (`SecurityConfiguration`).

## App configuration

- [ ] `NORTHSTAR_APP_URL` is set to the environment's frontend address; invite links are built from it. Staging uses
      `http://localhost:5173` until the frontend phase.
- [ ] `NORTHSTAR_WORKER_ENABLED=true` in the worker container only; the API containers leave it unset.
- [ ] The platform health check uses `/actuator/health` only; no other actuator endpoint is exposed.
- [ ] Every variable is set on its component, never app-wide (`.do/staging.app.yaml`); secrets have Encrypt ticked.
- [ ] Before the frontend phase: CORS allows the environment's frontend origin (backend-roadmap.md, Phase 1 notes).

## Deploy pipeline

- [ ] Deploys come only from the `deploy-staging` CI job after `backend` and `cross-tenant` pass on `main`; App
      Platform's auto-deploy is off on every component.
- [ ] The deploy is opt-in: the repository variable `STAGING_DEPLOY_ENABLED` is `true` only while the app exists.
      Unset, `deploy-staging` is skipped and the required checks still run (`DeployWorkflowTests`). It is a repository
      variable, not an environment one, because the job's condition is decided before it joins its environment.
- [ ] Staging, 19 November 2026: hosting paused for budget, switch unset. Verified so far: `roles.sql` and
      `verify.sql` (18 PASS), pooler logins for `migrator`, `app_user` and `jobrunr_user`, and the deploy token's
      registry login and image push. Resume with `docs/staging-runbook.md`, First deploy, step 7.
- [ ] The GitHub environment (`staging`) allows only the `main` branch, and holds `DIGITALOCEAN_ACCESS_TOKEN`: a
      custom-scoped token (app read and update, registry read, update and delete), 90-day expiry, rotated before it
      expires.
- [ ] The container registry keeps enough tags to roll back: staging is on Starter (500 MiB) and the deploy keeps 2
      tags (`docs/staging-runbook.md`, image tags).
- [ ] Before production: the registry is on Basic (5 GiB), with `DO_REGISTRY_KEEP_TAGS=20` and
      `DO_REGISTRY_LIMIT_MIB=5120` set on the GitHub environment.
