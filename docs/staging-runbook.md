# Staging runbook

How Northstar's staging backend is deployed, rolled back and kept secret (step 1.15). Settings every environment must
have: `docs/deploy-checklist.md`. Database roles: `db/bootstrap/README.md`.

## What runs where

| Piece | Where | Notes |
| --- | --- | --- |
| Database, Auth | Supabase staging project, Mumbai | Email confirmation on, Data API off |
| `api` service | DigitalOcean App Platform `northstar-staging`, Bangalore, 1 × 1 vCPU / 1 GiB | Public; health check `/actuator/health` |
| `worker` | Same app, 1 × 1 vCPU / 1 GiB | `NORTHSTAR_WORKER_ENABLED=true`; no public route |
| `migrate` pre-deploy job | Same app, 1 vCPU / 512 MiB, about a minute per deploy | Applies `db/migrations` as `migrator` before the new version starts |
| Image | DigitalOcean Container Registry, Basic plan, repository `northstar`, tags `app-<commit sha>` | One image for all three components |
| Deploy | GitHub Actions job `deploy-staging` | Only on `main`, only after `backend` and `cross-tenant` pass |

The components and their variables are declared in `.do/staging.app.yaml`; `StagingAppSpecTests` checks the rules, and
the deploy job fails if the live app drifts from the file.

**Cost** (DigitalOcean's pricing page, November 2026): api $10 + worker $10 + container registry Basic $5 + the
`migrate` job, billed per second at the $5/month rate for about a minute per deploy (cents a month) ≈ **$25/month**, plus
GST.

## Secrets

Every secret is typed by hand into a psql prompt, the DigitalOcean dashboard or GitHub's secret settings. None is ever
in the repo, a command line, a log or a chat.

| Secret | Lives in | Given to |
| --- | --- | --- |
| `postgres` password | Supabase | Only the operator's psql prompt; never DigitalOcean or GitHub |
| migrator password (`FLYWAY_PASSWORD`) | DigitalOcean encrypted variable | `migrate` job only |
| app_user password (`DB_PASSWORD`) | DigitalOcean encrypted variable | `api`, `worker` |
| jobrunr_user password (`JOBRUNR_DB_PASSWORD`) | DigitalOcean encrypted variable | `api` (it queues jobs), `worker` |
| `RESEND_API_KEY` (sending access only) | DigitalOcean encrypted variable | `worker` only |
| `NORTHSTAR_INVITE_TOKEN_KEY` | DigitalOcean encrypted variable | `worker` only |
| `DIGITALOCEAN_ACCESS_TOKEN` | GitHub environment secret, environment `staging` | `deploy-staging` job only |

Plain variables, typed in the dashboard (not secret, but kept out of the repo because they name the project):
`FLYWAY_URL` (session pooler, port 5432) and `FLYWAY_USER` (`migrator.<project-ref>`) on `migrate`; `DB_URL`
(transaction pooler, port 6543), `DB_USER` (`app_user.<project-ref>`), `JOBRUNR_DB_USER` (`jobrunr_user.<project-ref>`)
and `SUPABASE_URL` on `api` and `worker`. Both URLs end in `?sslmode=require` and never carry a password. The fixed
values (`NORTHSTAR_WORKER_ENABLED`, `NORTHSTAR_EMAIL_PROVIDER`, `NORTHSTAR_EMAIL_FROM`, `NORTHSTAR_APP_URL`) are in the
spec file.

**Generating a secret.** In a terminal, `openssl rand -base64 32 | pbcopy` puts 32 random bytes on the clipboard without
showing them. Paste where it goes, then clear the clipboard with `pbcopy < /dev/null`. Before any password step, turn
off or pause every clipboard-history app (Raycast, Alfred, Maccy, Paste and the like), so no secret is kept in its
history.

## Email on staging

Staging sends through a free Resend account (company email, two-factor on) with a sending-only API key, from
`Northstar <onboarding@resend.dev>`, Resend's shared test sender. **It delivers only to the Resend account owner's
address.** An invite to anyone else fails permanently as `sender_not_allowed`: it shows in Failed Actions with an alert,
and nothing is paused. To invite other people, verify a staging sub-domain in Resend (SPF, DKIM), change
`NORTHSTAR_EMAIL_FROM` in `.do/staging.app.yaml` and in the dashboard to an address on it, and deploy.

## First deploy (once)

1. **Supabase:** turn on Enforce SSL, then run `roles.sql`, set the three passwords and run `verify.sql`
   (`db/bootstrap/README.md`). Every line must say PASS; on any FAIL, stop.
2. **Container registry:** DigitalOcean → Container Registry → Create: Basic plan, Bangalore if offered, a unique
   name. Turn on nothing else.
3. **Deploy token:** DigitalOcean → API → Tokens → Generate New Token. Name `github-deploy-staging`, expiration 90 days,
   Custom Scopes: App `read`, `update`; Registry `read`, `update`. Copy it once, straight into step 4.
4. **GitHub:** repo Settings → Environments → New environment `staging`.
   - Deployment branches and tags: Selected branches → add `main`.
   - Environment secret `DIGITALOCEAN_ACCESS_TOKEN`: paste the token.
   - Environment variables: `DO_REGISTRY` = the registry's name, `DO_APP_NAME` = `northstar-staging`.
5. **Resend:** create the account and an API key with Sending access. Copy it once, into step 7.
6. **Merge the pull request.** On `main`, `deploy-staging` pushes the image `app-<sha>` and then fails with "App
   northstar-staging does not exist yet". That is expected once. Note the `<sha>` from the job.
7. **Create the app.** DigitalOcean → Create → App Platform → Container image → DigitalOcean Container Registry →
   repository `northstar`, tag `app-<sha>`; region Bangalore; project `Northstar`. Then in the app: Settings → App Spec
   → Edit, paste `.do/staging.app.yaml` with every `app-placeholder` replaced by `app-<sha>`, and Save. If the editor
   refuses a variable without a value, type that value there (plain variables only; secrets go in the next step).
   Then for each component (`api`, `worker`, `migrate`): Settings → the component → Environment Variables → Edit, fill
   in every value, tick **Encrypt** on each secret, Save. A deployment that starts before the values are in fails at
   `migrate`; that is harmless.
8. **Re-run** the failed `deploy-staging` job (Actions → the run → Re-run failed jobs). It checks the spec, deploys
   and waits.
9. **Smoke test:**
   - `curl https://<app>.ondigitalocean.app/actuator/health` → `{"status":"UP"}`.
   - `curl -i https://<app>.ondigitalocean.app/api/v1/members` → 401 with an `X-Request-Id` header.
   - App → Runtime Logs: lines carry `request_id`, `tenant_id`, `user_id` and status codes only.
   - Run `verify.sql` again: every line PASS, now with the schema migrated.
10. Tick `docs/deploy-checklist.md` for staging.

## Deploying

Merge to `main`. When `backend` and `cross-tenant` pass, `deploy-staging`:

1. builds the image from that commit and pushes it as `app-<sha>`;
2. checks the live app against `.do/staging.app.yaml` (components, sizes, commands, variable names and types; never
   values) and fails on any difference;
3. takes the live spec, sets `app-<sha>` on `api`, `worker` and `migrate`, and waits for the deployment.

App Platform runs `migrate` first. If it fails, or the new `api` fails its health check, the deployment fails, the
previous version keeps running and DigitalOcean emails a `DEPLOYMENT_FAILED` alert. `migrate`'s log (App → Activity →
the deployment → `migrate`) shows migration versions and a SQL state, never SQL: `28P01` wrong password, `42501`
missing privilege, `08001` cannot connect, `reason=validation` an applied migration changed.

Two pushes to `main` never deploy at once: the newer run waits for the older one.

**Schema changes are forward-only** and must work with the version still running: the old version keeps serving while
the new one starts, and a rollback never undoes a migration. Add columns and tables first, use them in a later
deploy, and remove old ones only after nothing reads them.

## Rolling back

- **Fastest:** App → Activity → the last good deployment → Rollback. App Platform redeploys that version's image and
  spec.
- **From GitHub:** Actions → the CI run of the last good commit on `main` → `deploy-staging` → Re-run job. It
  deploys that commit's tag again. Its drift check uses that commit's `.do/staging.app.yaml`, so this works only if the
  spec has not changed since; otherwise use the dashboard.

Either way the database keeps its newer migrations: an older `migrate` ignores migrations it does not know (Flyway's
default), so it passes. If a migration itself is wrong, fix forward with a new migration; never edit or delete a
merged one.

## Image tags

The Basic registry holds 5 GiB. Every tag shares the base and dependency layers; a commit that changes only code adds a
few MB. **Keep the 20 most recent `app-*` tags, and always the tag running now plus the two before it** (the rollback
targets). Once a month, or when the registry passes 80% full:

1. DigitalOcean → Container Registry → `northstar` → sort tags by date; delete tags older than the 20 most recent,
   never the three above.
2. Settings → Garbage collection → Start garbage collection. The registry is read-only while it runs, so do it when
   no deploy is running.

## Changing the app spec

The deploy changes only image tags, so a change to components, sizes or variables is made twice, and the drift check
holds them together:

1. Edit `.do/staging.app.yaml` in a pull request (`StagingAppSpecTests` checks it).
2. After it merges, and before the next deploy succeeds: App → Settings → App Spec → Edit, make the same change, and
   Save. The editor keeps each secret as its encrypted `EV[...]` value; leave those lines as they are. A new secret is
   typed in the component's Environment Variables, with Encrypt ticked.
3. Re-run `deploy-staging` if it failed on drift in between.

Never apply the repo file with `doctl apps update --spec` or app_action's spec mode: the file has no secret values, so
every secret would be emptied.

## Rotating secrets

Every 90 days, when someone leaves, or at once if a secret may have leaked (`integration-specs.md` section 7). Turn off
clipboard history first. Each change saved in a component's Environment Variables redeploys the app (`migrate` runs
and finds nothing to do).

**Deploy token (`DIGITALOCEAN_ACCESS_TOKEN`), expires after 90 days.** Rotate at about day 80; set a reminder when
you create it. If it has already expired, `deploy-staging` fails at the doctl step; do the same steps.

1. DigitalOcean → API → Tokens → Generate New Token: name `github-deploy-staging-<yyyy-mm>`, expiration 90 days, Custom
   Scopes App `read`, `update` and Registry `read`, `update`. Copy it once.
2. GitHub → repo Settings → Environments → `staging` → `DIGITALOCEAN_ACCESS_TOKEN` → edit → paste → Update secret.
3. Actions → the latest CI run on `main` → `deploy-staging` → Re-run job. It must pass.
4. DigitalOcean → API → Tokens → the old token → Delete.

**app_user password (`DB_PASSWORD`).**

1. `openssl rand -base64 32 | pbcopy`.
2. Open a psql session as `postgres` (`db/bootstrap/README.md`), run `\password app_user`, paste twice, `\q`.
3. App → Settings → `api` → Environment Variables → `DB_PASSWORD` → paste (Encrypt stays ticked); the same on
   `worker`; Save.
4. `pbcopy < /dev/null`.

Between steps 2 and 3, connections the containers already hold keep working, but new ones fail until the redeploy
finishes: expect a minute or two of errors on staging. If the pooler still refuses the new password after the
redeploy, wait a few minutes and redeploy again (App → Actions → Force rebuild and deploy).

**jobrunr_user password (`JOBRUNR_DB_PASSWORD`).** As above with `\password jobrunr_user`, set on `api` and `worker`.

**migrator password (`FLYWAY_PASSWORD`).** As above with `\password migrator`, set on the `migrate` job only. Nothing
running uses it, so there is no gap.

**`RESEND_API_KEY`.** Resend → API Keys → Create API key, Sending access; copy it once into `worker` →
`RESEND_API_KEY`; Save. After the deployment is active, delete the old key in Resend.

**`NORTHSTAR_INVITE_TOKEN_KEY`.** `openssl rand -base64 32 | pbcopy`, paste into `worker` →
`NORTHSTAR_INVITE_TOKEN_KEY`, Save, clear the clipboard. Links already sent keep working; invite emails being retried
at that moment fail with an alert, and the admin resends them.

**`postgres` password.** Supabase → Project Settings → Database → Reset database password, generated as above. Nothing
in DigitalOcean or GitHub uses it.

## Later

- **Frontend phase:** `NORTHSTAR_APP_URL` becomes the staging frontend's address; Supabase Auth's Site URL and Redirect
  URLs, custom SMTP and the backend's CORS origin are set then (`docs/deploy-checklist.md`).
- **Production:** its own Supabase project, app, registry tags, token and secrets; nothing is shared with staging.
