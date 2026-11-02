# Database bootstrap

`roles.sql` creates Northstar's three database roles and makes `migrator` the owner of the `northstar` schema
(`data-model.md` section 12). Run it once per environment as `postgres`, before the first Flyway migration. It is
safe to run again, and it puts back any role attribute changed by hand.

| Role | Login used by | Bypasses RLS |
| --- | --- | --- |
| `migrator` | Flyway (`FLYWAY_USER`) | Yes |
| `app_user` | Spring Boot API and worker (`DB_USER`) | No |
| `app_admin` | Northstar admin module, from Phase 8 | Yes, read-only by default |

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
  \q
```

Then in `.env.local`: `FLYWAY_USER=migrator`, `FLYWAY_PASSWORD=` the migrator password, `DB_USER=app_user`,
`DB_PASSWORD=` the app_user password. Run `cd backend && ./mvnw initialize flyway:migrate`.

`\password` sends only a hash of the password to the server, so the password itself never appears in database logs.
Do not use `alter role ... password '...'` with a plain-text password.

## Staging and production (hosted Supabase)

**Verify this script on the staging project before production.** On hosted Supabase `postgres` is not a full
superuser, so check that the script runs cleanly there and that the result matches:

```
select rolname, rolbypassrls, rolcreaterole, rolcreatedb, rolcanlogin
from pg_roles where rolname in ('migrator', 'app_user', 'app_admin');
select nspowner::regrole from pg_namespace where nspname = 'northstar';
```

Expected: `migrator` and `app_admin` bypass RLS, `app_user` does not, none can create roles or databases, and
`migrator` owns `northstar`.

1. Connect with `psql` as `postgres` on a direct or session-pooler connection (not the transaction pooler).
2. Run `roles.sql`, then turn on login and set passwords with `\password` as above. Use generated passwords and keep
   them only in the environment's secret store.
3. Only then run Flyway as `migrator`.
