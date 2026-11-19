# Staging runbook

How Northstar's staging backend is deployed, rolled back and kept secret (step 1.15). Settings every environment must
have: `docs/deploy-checklist.md`. Database roles: `db/bootstrap/README.md`.

## Status (19 November 2026)

**Staging hosting is paused for budget.** The pipeline is built and verified up to the first deploy; the App Platform
app has not been created, and `deploy-staging` is switched off (`STAGING_DEPLOY_ENABLED` unset), so on `main` it shows
as skipped. `backend` and `cross-tenant` run on every pull request and push as before.

Verified on the real staging accounts (First deploy, steps 1 to 6):

- `roles.sql` ran on the Supabase staging project, and `verify.sql` printed 18 PASS lines and no FAIL;
- `migrator`, `app_user` and `jobrunr_user` each logged in through the pooler;
- the deploy token logged in to the container registry and pushed an image.

Not yet verified, because they need the app: the pre-deploy job and a failed deploy leaving the old version running,
registry tag deletion and garbage collection with the token's scopes, and everything from step 7 on.

**To resume:** First deploy, step 7. Before it:

- set `STAGING_DEPLOY_ENABLED` to `true` (step 4);
- check that the deploy token has not expired (90 days from creation); if it has, rotate it first (Rotating secrets);
- step 7 needs an `app-<sha>` tag in the registry. If the one pushed in step 6 is gone, or you want a current commit,
  repeat step 6 first;
- re-run `roles.sql` and then `verify.sql` on the staging project (`db/bootstrap/README.md`, Commands (hosted)).
  Since step 2.1a they also create and check the `secrets_user` role and the `secrets` and `ingest` schemas, which the
  migrations need; the 18 PASS lines above predate them. In the same session, turn on `secrets_user`'s login and set
  its password with `\password secrets_user`;
- generate the credential key and keep its offline copy (Secrets, Generating the credential key). When the app is
  created in step 7, `worker` needs `SECRETS_DB_USER`, `SECRETS_DB_PASSWORD`, `NORTHSTAR_CREDENTIAL_KEYS` and
  `NORTHSTAR_CREDENTIAL_ACTIVE_KEY` (step 2.1b): the worker refuses to start without them.

## What runs where

| Piece | Where | Notes |
| --- | --- | --- |
| Database, Auth | Supabase staging project, Mumbai | Email confirmation on, Data API off |
| `api` service | DigitalOcean App Platform `northstar-staging`, Bangalore, 1 × 1 vCPU / 1 GiB | Public; health check `/actuator/health` |
| `worker` | Same app, 1 × 1 vCPU / 1 GiB | `NORTHSTAR_WORKER_ENABLED=true`; no public route |
| `migrate` pre-deploy job | Same app, 1 vCPU / 512 MiB, about a minute per deploy | Applies `db/migrations` as `migrator` before the new version starts |
| Image | DigitalOcean Container Registry, Starter plan (500 MiB, 1 repository), repository `northstar`, tags `app-<commit sha>` | One image for all three components; the deploy keeps 2 tags |
| Deploy | GitHub Actions job `deploy-staging` | Only on `main`, only after `backend` and `cross-tenant` pass, only while the repository variable `STAGING_DEPLOY_ENABLED` is `true` |

The components and their variables are declared in `.do/staging.app.yaml`; `StagingAppSpecTests` checks the rules, and
the deploy job fails if the live app drifts from the file.

**Cost** (DigitalOcean's pricing page, November 2026):

| Item | $/month |
| --- | --- |
| `api`, 1 × shared 1 vCPU / 1 GiB | 10 |
| `worker`, 1 × shared 1 vCPU / 1 GiB | 10 |
| `migrate` job, billed per second at the $5/month rate, about a minute per deploy | under 0.10 |
| Container registry, Starter (500 MiB, 1 repository) | 0 |
| **Total** | **≈ 20, plus GST** |

The registry moves to Basic ($5, 5 GiB) before production (see Image tags).

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
| secrets_user password (`SECRETS_DB_PASSWORD`) | DigitalOcean encrypted variable | `worker` only |
| `NORTHSTAR_CREDENTIAL_KEYS` (decrypts every business's tokens) | DigitalOcean encrypted variable, plus the offline copy | `worker` only |
| `NORTHSTAR_WHATSAPP_APP_SECRET` (the staging Meta test app's App Secret) | DigitalOcean encrypted variable; Meta's App Dashboard | `api` only |
| `NORTHSTAR_WHATSAPP_VERIFY_TOKEN` | DigitalOcean encrypted variable; Meta's webhook settings | `api` only |
| `DIGITALOCEAN_ACCESS_TOKEN` | GitHub environment secret, environment `staging` | `deploy-staging` job only |

Plain variables, typed in the dashboard (not secret, but kept out of the repo because they name the project):
`FLYWAY_URL` (session pooler, port 5432) and `FLYWAY_USER` (`migrator.<project-ref>`) on `migrate`; `DB_URL`
(transaction pooler, port 6543), `DB_USER` (`app_user.<project-ref>`), `JOBRUNR_DB_USER` (`jobrunr_user.<project-ref>`)
and `SUPABASE_URL` on `api` and `worker`; `SECRETS_DB_USER` (`secrets_user.<project-ref>`) and
`NORTHSTAR_CREDENTIAL_ACTIVE_KEY` (the key ID, not the key) on `worker` only. Both URLs end in `?sslmode=require` and
never carry a password. The fixed
values (`NORTHSTAR_WORKER_ENABLED`, `NORTHSTAR_EMAIL_PROVIDER`, `NORTHSTAR_EMAIL_FROM`, `NORTHSTAR_APP_URL`) are in the
spec file.

**Generating a secret.** In a terminal, `openssl rand -base64 32 | pbcopy` puts 32 random bytes on the clipboard without
showing them. Paste where it goes, then clear the clipboard with `pbcopy < /dev/null`. Before any password step, turn
off or pause every clipboard-history app (Raycast, Alfred, Maccy, Paste and the like), so no secret is kept in its
history.

**Generating the credential key** (`NORTHSTAR_CREDENTIAL_KEYS`, `integration-specs.md` section 7). It encrypts every
business's WhatsApp, Razorpay, Google and Meta tokens. Each environment has its own; never reuse one between staging
and production, or copy one to a laptop.

1. Choose a key ID: the environment and the month, e.g. `stg-2026-11` (lowercase letters, digits and hyphens, at most
   32 characters).
2. `printf 'stg-2026-11:%s' "$(openssl rand -base64 32)" | pbcopy` puts the whole entry on the clipboard without
   showing it.
3. **Offline copy first.** Paste it into a new item in the team password manager's restricted vault (not the
   DigitalOcean account, the repo, a chat or a ticket), named `Northstar <environment> credential key <key ID>`, with
   the date. Losing every copy of a key means every business must reconnect its integrations.
4. App → Settings → `worker` → Environment Variables: paste into `NORTHSTAR_CREDENTIAL_KEYS` (Encrypt ticked), and set
   `NORTHSTAR_CREDENTIAL_ACTIVE_KEY` to the key ID. Save.
5. `pbcopy < /dev/null`.

## Email on staging

Staging sends through a free Resend account (company email, two-factor on) with a sending-only API key, from
`Northstar <onboarding@resend.dev>`, Resend's shared test sender. **It delivers only to the Resend account owner's
address.** An invite to anyone else fails permanently as `sender_not_allowed`: it shows in Failed Actions with an alert,
and nothing is paused. To invite other people, verify a staging sub-domain in Resend (SPF, DKIM), change
`NORTHSTAR_EMAIL_FROM` in `.do/staging.app.yaml` and in the dashboard to an address on it, and deploy.

## WhatsApp webhooks on staging

Meta posts every WhatsApp delivery to `POST /webhooks/whatsapp` on the `api` component (step 2.2,
`integration-specs.md` sections 1 and 2). Staging uses Meta's separate test app and test number, never the live app.
The API refuses to start without both secrets; set them before the first deploy that includes step 2.2.

1. **App secret:** Meta App Dashboard → the staging test app → App settings → Basic → App secret → Show. Copy it into
   App → Settings → `api` → Environment Variables → `NORTHSTAR_WHATSAPP_APP_SECRET` (Encrypt ticked). Save.
2. **Verify token:** `openssl rand -hex 32 | pbcopy` (hex, so it survives a URL unchanged). Paste into `api` →
   `NORTHSTAR_WHATSAPP_VERIFY_TOKEN` (Encrypt ticked), Save, and keep it on the clipboard for step 3.
3. **Subscribe, after the deploy is active:** Meta App Dashboard → WhatsApp → Configuration → Webhook → Edit. Callback
   URL `https://<app>.ondigitalocean.app/webhooks/whatsapp`, Verify token: paste. Verify and save: Meta calls the GET
   handshake, which echoes its challenge only for the right token. Then `pbcopy < /dev/null`.
4. **Webhook fields:** subscribe to `messages` only. Template, quality and account updates are keyed by the WABA ID,
   which step 2.8 routes; until then the API accepts them with a 200 and stores nothing.
5. **Check:** App → Runtime Logs shows `webhook.verification provider=whatsapp status=200`. Send a message to the test
   number: the logs show `webhook.received ... stored=1` once the number's `whatsapp_accounts` row exists, or
   `webhook.unknown_account ... unrouted_id=<id>` before it does (parked, never processed).

## First deploy (once)

1. **Supabase:** turn on Enforce SSL, then run `roles.sql`, set the four passwords (`migrator`, `app_user`,
   `jobrunr_user`, `secrets_user`) and run `verify.sql` (`db/bootstrap/README.md`). Every line must say PASS; on any
   FAIL, stop. Generate the credential key and its offline copy now too (Secrets).
2. **Container registry:** DigitalOcean → Container Registry → Create: Starter plan, Bangalore if offered, a unique
   name. Turn on nothing else.
3. **Deploy token:** DigitalOcean → API → Tokens → Generate New Token. Name `github-deploy-staging`, expiration 90 days,
   Custom Scopes: App `read`, `update`; Registry `read`, `update`, `delete` (the deploy deletes old image tags). Copy it
   once, straight into step 4.
4. **GitHub:** repo Settings → Environments → New environment `staging`.
   - Deployment branches and tags: Selected branches → add `main`.
   - Environment secret `DIGITALOCEAN_ACCESS_TOKEN`: paste the token.
   - Environment variables: `DO_REGISTRY` = the registry's name, `DO_APP_NAME` = `northstar-staging`.
   - **Turn deploys on** (do this before step 6): repo Settings → Secrets and variables → Actions → Variables → New
     repository variable `STAGING_DEPLOY_ENABLED` = `true`. Without it, `deploy-staging` shows as skipped and nothing is
     deployed. It must be a repository variable, not one on the `staging` environment: the job's condition is decided
     before the job joins its environment, so GitHub does not promise that an environment's variables are visible to
     it.
5. **Resend:** create the account and an API key with Sending access. Copy it once, into step 7.
6. **Run the deploy job once.** With the switch on, push to `main` (or Actions → the latest CI run on `main` → Re-run
   all jobs). `deploy-staging` pushes the image `app-<sha>` and then fails with "App northstar-staging does not exist
   yet". That is expected once. Note the `<sha>` from the job.
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
   - `curl -i -X POST -H 'Content-Type: application/json' -d '{}' https://<app>.ondigitalocean.app/webhooks/whatsapp`
     → 401 `invalid_signature` (an unsigned delivery is refused and parked as metadata only).
   - The WhatsApp webhook handshake passes (WhatsApp webhooks on staging, step 3).
   - App → Runtime Logs: lines carry `request_id`, `tenant_id`, `user_id` and status codes only.
   - Run `verify.sql` again: every line PASS, now with the schema migrated.
10. Tick `docs/deploy-checklist.md` for staging.

## Deploying

Merge to `main`. When `backend` and `cross-tenant` pass, and while `STAGING_DEPLOY_ENABLED` is `true`, `deploy-staging`:

1. waits for any registry garbage collection to finish (the registry is read-only while one runs);
2. builds the image from that commit and pushes it as `app-<sha>`;
3. checks the live app against `.do/staging.app.yaml` (components, sizes, commands, variable names and types; never
   values) and fails on any difference;
4. records the tag the app runs now, takes the live spec, sets `app-<sha>` on `api`, `worker` and `migrate`, and waits
   for the deployment;
5. after a successful deploy only, cleans up the registry (see Image tags).

App Platform runs `migrate` first. If it fails, or the new `api` fails its health check, the deployment fails, the
previous version keeps running and DigitalOcean emails a `DEPLOYMENT_FAILED` alert. `migrate`'s log (App → Activity →
the deployment → `migrate`) shows migration versions and a SQL state, never SQL: `28P01` wrong password, `42501`
missing privilege, `08001` cannot connect, `reason=validation` an applied migration changed.

Two pushes to `main` never deploy at once: the newer run waits for the older one.

**Schema changes are forward-only** and must work with the version still running: the old version keeps serving while
the new one starts, and a rollback never undoes a migration. Add columns and tables first, use them in a later
deploy, and remove old ones only after nothing reads them.

## Rolling back

**Re-run an older green `deploy-staging` job:** Actions → the CI run of the last good commit on `main` →
`deploy-staging` → Re-run job. It rebuilds that commit's image, pushes it as `app-<that sha>` and deploys it, so it
works however long ago the commit was, even though the registry keeps only 2 tags. Its drift check uses that commit's
`.do/staging.app.yaml`; if the spec has changed since, make the live app match that version first (Changing the app
spec), or fix forward instead.

The dashboard's App → Activity → Rollback works only for the version that ran just before the current one: its image
is the only older tag the registry keeps.

Either way the database keeps its newer migrations: an older `migrate` ignores migrations it does not know (Flyway's
default), so it passes. If a migration itself is wrong, fix forward with a new migration; never edit or delete a
merged one.

## Image tags

Staging uses the free **Starter** registry: 500 MiB and 1 repository, with no overage, so a push that would go over
the limit is refused. **Keep 2 tags on Starter; raise to 20 when moving to Basic.**

After every successful deploy, `deploy-staging` cleans up by itself (`.github/scripts/clean-registry.sh`):

1. It keeps the tag just deployed and the tag the app ran before it (the dashboard's rollback target), and deletes
   every other tag, oldest first. If the two are the same (a re-run), it keeps the newest other tag instead. It never
   deletes a tag the app runs, and deletes nothing if the deployed tag is missing from the registry.
2. It starts garbage collection, which frees the deleted tags' space. If one is already running, it notes that and
   carries on; any other failure to start fails the job (the deploy itself has already succeeded).
3. It prints the storage used, with the time DigitalOcean measured it (the figure updates once garbage collection
   finishes), and adds a warning annotation to the run above 80% of the plan's limit.

Tags share the base and dependency layers, so two tags cost little more than one. A commit that changes dependencies
adds a new dependency layer until the older tag is deleted.

**If the warning appears or a push is refused for storage:** check Container Registry → `northstar` for tags other
than the two above and delete them, then Settings → Garbage collection → Start garbage collection, then re-run the job.
If two images no longer fit, move to Basic.

**Before production (or as soon as Starter is too small), move to Basic:** Container Registry → Settings → Change
plan → Basic ($5/month, 5 GiB). Then in GitHub → Settings → Environments → `staging` → Environment variables, set
`DO_REGISTRY_KEEP_TAGS` = `20` and `DO_REGISTRY_LIMIT_MIB` = `5120`. Unset, they default to Starter's `2` and `500`.

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
   Scopes App `read`, `update` and Registry `read`, `update`, `delete`. Copy it once.
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

**secrets_user password (`SECRETS_DB_PASSWORD`).** As above with `\password secrets_user`, set on `worker` only. Until
the redeploy, the worker cannot open new credential connections, so sends that need a token retry.

**Credential key (`NORTHSTAR_CREDENTIAL_KEYS`).** Rotate every 90 days, when someone who could read it leaves, or at
once if it may have leaked. Old values stay readable while their key is in the list, so a rotation never breaks a
business's integrations:

1. Generate a new entry with a new key ID and its offline copy (Secrets, Generating the credential key, steps 1 to 3).
2. Worker → `NORTHSTAR_CREDENTIAL_KEYS`: append `,` and the new entry after the existing ones (paste the full list
   from the password manager; the dashboard does not show encrypted values). Set `NORTHSTAR_CREDENTIAL_ACTIVE_KEY` to
   the new key ID. Save. New and refreshed tokens now use the new key.
3. **Keep the old key in the list.** Every value it made still needs it. Re-encrypting existing values under the new
   key is a store method today (step 2.1b) without a job that runs it for every business; until that job and its
   runbook steps exist, never remove a key. After a leak, rotate anyway (new values stop depending on the old key) and
   ask every business to reconnect, which replaces its tokens.
4. `pbcopy < /dev/null`.

**migrator password (`FLYWAY_PASSWORD`).** As above with `\password migrator`, set on the `migrate` job only. Nothing
running uses it, so there is no gap.

**`RESEND_API_KEY`.** Resend → API Keys → Create API key, Sending access; copy it once into `worker` →
`RESEND_API_KEY`; Save. After the deployment is active, delete the old key in Resend.

**`NORTHSTAR_INVITE_TOKEN_KEY`.** `openssl rand -base64 32 | pbcopy`, paste into `worker` →
`NORTHSTAR_INVITE_TOKEN_KEY`, Save, clear the clipboard. Links already sent keep working; invite emails being retried
at that moment fail with an alert, and the admin resends them.

**`NORTHSTAR_WHATSAPP_APP_SECRET`.** Meta App Dashboard → App settings → Basic → App secret → Reset, copy the new one
into `api` → `NORTHSTAR_WHATSAPP_APP_SECRET`, Save. Until the redeploy finishes, Meta's deliveries carry the new
signature and the API answers 401 (each is parked as metadata only); Meta retries non-200 deliveries for up to 7 days,
so they arrive once the new secret is live. Watch for `webhook.signature_invalid` lines stopping.

**`NORTHSTAR_WHATSAPP_VERIFY_TOKEN`.** Used only for Meta's subscription handshake. `openssl rand -hex 32 | pbcopy`,
paste into `api` → `NORTHSTAR_WHATSAPP_VERIFY_TOKEN`, Save; after the redeploy, paste the same value in Meta's webhook
settings and Verify and save. Clear the clipboard.

**`postgres` password.** Supabase → Project Settings → Database → Reset database password, generated as above. Nothing
in DigitalOcean or GitHub uses it.

## Later

- **Frontend phase:** `NORTHSTAR_APP_URL` becomes the staging frontend's address; Supabase Auth's Site URL and Redirect
  URLs, custom SMTP and the backend's CORS origin are set then (`docs/deploy-checklist.md`).
- **Production:** its own Supabase project, app, registry tags, token and secrets; nothing is shared with staging.

## Pausing staging

To stop deploying (for example to save cost), delete the repository variable `STAGING_DEPLOY_ENABLED` or set it to
anything but `true`: `deploy-staging` then shows as skipped, and `backend` and `cross-tenant` are unaffected. To stop
paying for the app too, destroy it in DigitalOcean (App → Settings → Destroy); the Supabase project, the registry and
the GitHub environment can stay. Resuming starts again at First deploy, step 6 (or step 7 if a recent image is in the
registry).
