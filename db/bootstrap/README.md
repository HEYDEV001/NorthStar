# Database bootstrap

`roles.sql` creates Northstar's four database roles and makes `migrator` the owner of the `northstar` and `jobrunr`
schemas (`data-model.md` section 12). Run it once per environment as `postgres`, before the first Flyway migration. It is
safe to run again, and it puts back any role attribute changed by hand.

| Role | Login used by | Bypasses RLS |
| --- | --- | --- |
| `migrator` | Flyway (`FLYWAY_USER`) | Yes |
| `app_user` | Spring Boot API and worker (`DB_USER`) | No |
| `app_admin` | Northstar admin module, from Phase 8 | Yes, read-only by default |
| `jobrunr_user` | JobRunr's private pool, step 1.10 (`JOBRUNR_DB_USER`) | No; may use only the `jobrunr` schema |

The roles are created without login. Passwords never go in the repo, in this script or in a migration.

## Local Supabase

Run from the repo root, with `supabase start` running. No local `psql` needed.

```
# 1. Create the roles (again after every `supabase db reset`)
docker exec -i supabase_db_NorthStar psql -U postgres -d postgres -v ON_ERROR_STOP=1 < db/bootstrap/roles.sql

# 2. Turn on login and set passwords (psql prompts; choose your own local passwords)
docker exec -it supabase_db_NorthStar psql -U postgres -d postgres
  alter role migrator login;
  \password migrator
  alter role app_user login;
  \password app_user
  alter role jobrunr_user login;
  \password jobrunr_user
  \q
```

Then in `.env.local`: `FLYWAY_USER=migrator`, `FLYWAY_PASSWORD=` the migrator password, `DB_USER=app_user`,
`DB_PASSWORD=` the app_user password, `JOBRUNR_DB_USER=jobrunr_user`, `JOBRUNR_DB_PASSWORD=` the jobrunr_user
password. Run `cd backend && ./mvnw initialize flyway:migrate`.

`\password` sends only a hash of the password to the server, so the password itself never appears in database logs.
Do not use `alter role ... password '...'` with a plain-text password.

## Staging and production (hosted Supabase)

**Verify this script on the staging project before production.** On hosted Supabase `postgres` is not a full
superuser, so check that the script runs cleanly there and that `verify.sql` passes. `verify.sql` is read-only and
prints one `PASS` or `FAIL` line per check, then a summary line. It checks:

- `migrator` and `app_admin` have `BYPASSRLS`; `app_user` and `jobrunr_user` do not;
- no Northstar role is a superuser or can create roles or databases;
- `migrator`, `app_user` and `jobrunr_user` can log in, `app_admin` not yet;
- `app_user` and `jobrunr_user` are members of no other role, and `postgres` can act as `migrator`;
- `migrator` owns both schemas, `jobrunr_user` cannot reach `northstar` and `app_user` cannot reach `jobrunr`;
- the two findings from the local image that Phase 1 relies on: `migrator` cannot use schema `auth` (so `user_profiles`
  has no foreign key to `auth.users` and the service enforces it), and `citext` is available but not installed (so
  `invites.email` is plain text with a `lower(email)` index).

**If any line says FAIL, stop and report it before running Flyway.** In particular, if `migrator` cannot get
`BYPASSRLS` on hosted Supabase, stop: the fix is never to weaken a policy, drop `force row level security`, or give
`app_user` more rights. If the two `auth`/`citext` findings differ, update `data-model.md` section 3 before production.

### Commands (hosted)

Connect through the **session pooler** (port 5432), never the transaction pooler: `\password` and the bootstrap need a
session. The pooler's user names carry the project ref, e.g. `postgres.<project-ref>`; the host and ref are in the
Supabase dashboard under **Connect → Session pooler** (the connection string shown there has only a
`[YOUR-PASSWORD]` placeholder). No local `psql` is needed; Docker runs one. Run these from the repo root:

```
# 1. Create the roles (psql asks for the postgres password; type it at the prompt)
docker run --rm -it -v "$PWD/db/bootstrap:/bootstrap:ro" postgres:17-alpine \
  psql "host=<session-pooler-host> port=5432 dbname=postgres user=postgres.<project-ref> sslmode=require" \
  -v ON_ERROR_STOP=1 -f /bootstrap/roles.sql

# 2. Open a session (same command without -v and -f), then:
alter role migrator login;
alter role app_user login;
alter role jobrunr_user login;
\password migrator
\password app_user
\password jobrunr_user
\q

# 3. Verify (same command as step 1 with -f /bootstrap/verify.sql); every line must say PASS
```

For each `\password`, generate the password straight onto the clipboard in a second terminal, so it is never shown and
never on a command line: `openssl rand -base64 32 | pbcopy`. Paste it at both prompts, then paste it into its
DigitalOcean variable (`docs/staging-runbook.md`), then clear the clipboard with `pbcopy < /dev/null`. Do one role at a
time. Before starting, turn off any clipboard-history app (Raycast, Alfred, Maccy, Paste and the like) or pause its
history, so no password is kept in it.

Leave `app_admin` without a login until Phase 8. Then run `verify.sql` again after the first deploy has migrated the
database. Only after it passes does Flyway run as `migrator`, through the deploy's pre-deploy job.
