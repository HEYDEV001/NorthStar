# Deploy checklist

Settings every hosted environment (staging, production) must have before the API serves traffic. Local
development gets the same settings from `supabase/config.toml` and `.env.local`. Check each item when an
environment is created and after any change to its Supabase project or app configuration.

## Supabase Auth

- [ ] Supabase email confirmation must be ON in every hosted environment; invite acceptance trusts the token's email.

## Database

- [ ] `db/bootstrap/roles.sql` has run and been verified on the project (hosted `postgres` is not a full superuser).
- [ ] Flyway uses a direct or session-pooler connection; the app uses the transaction pooler with `prepareThreshold=0`.
- [ ] `jobrunr_user` has a login and its own generated password (`JOBRUNR_DB_USER`, `JOBRUNR_DB_PASSWORD`); JobRunr also uses the transaction pooler (it keeps no session state).
- [ ] Connections fit the plan's pooler client limit: every container opens up to 10 app connections (Hikari's default) plus up to 5 for JobRunr, so 15 per container; at launch (2 API + 1 worker) that is 45, plus Flyway's and operators' sessions.

## Email (Resend)

- [ ] The sending sub-domain is verified in Resend (SPF, DKIM) before launch; staging uses a sandbox or test domain (integration-specs.md sections 6 and 7).
- [ ] `RESEND_API_KEY` is set in the worker container (sending access only), separate per environment; `NORTHSTAR_EMAIL_FROM` is an address on the verified sub-domain.
- [ ] `NORTHSTAR_EMAIL_PROVIDER` is unset or `resend`; `mailpit` is for local development only and refuses any host but localhost.
- [ ] `NORTHSTAR_INVITE_TOKEN_KEY` is 32+ random bytes (base64), generated per environment, in the worker container only. Rotating it affects only invite emails still being retried at that moment: their retries fail with an alert, and the admin resends those invites. Already-sent links keep working.

## App configuration

- [ ] `NORTHSTAR_APP_URL` is set to the environment's frontend address; invite links are built from it.
- [ ] `NORTHSTAR_WORKER_ENABLED=true` in the worker container only; the API containers leave it unset.
