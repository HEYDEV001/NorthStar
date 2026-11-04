# Database migrations

Flyway owns the schema. The Supabase CLI's own migrations are turned off (`supabase/config.toml`).

## Rules

- One file per change: `V{yyyyMMddHHmm}__{snake_description}.sql`, e.g. `V202611051030__create_tenants.sql`.
- Never edit a migration that has been merged. Write a new one.
- Every table goes in the `northstar` schema (`data-model.md` section 1).
- A table with `tenant_id` gets RLS enabled, forced, and the `tenant_isolation` policy in the same file
  (`data-model.md` section 12). The CI check fails if one is missing.
- A tenant table references another tenant table by `(tenant_id, x_id)` → parent `(tenant_id, id)`, and every
  parent has `unique (tenant_id, id)` (`data-model.md` section 1). Foreign keys to `tenants` and `user_profiles` are
  plain.
- Trigger and helper functions are normal invoker functions, never `SECURITY DEFINER` unless allowlisted in the gate.
- Follow `data-model.md` for keys, timestamps, money, enums and the indexes in section 13.

## Before a migration merges

The RLS release gate (`backend/src/test/java/com/northstar/RlsReleaseGate.java`, `data-model.md` section 12) runs in
every build. Update its declarations in the same pull request:

- A table without `tenant_id`: add it to the platform-wide list.
- Any policy on a tenant table besides `tenant_isolation`: make it restrictive and declare its exact text.
- An insert-only table: revoke update from `app_user` in the migration.
- A lifecycle table: revoke table-level update from `app_user`, grant update on the status and processing columns
  only, and list exactly those columns in the gate.
- A `SECURITY DEFINER` function: add it to the allowlist, with a reason in the migration.
- A new schema: add it to the gate's schema list.
- Views use `with (security_invoker = true)`. No materialized views.

## Who runs them

Every environment runs migrations as `migrator`, the owner of `northstar`, so every table gets the same owner and
the same default privileges. The roles must exist first: run `db/bootstrap/roles.sql` once per environment
(`db/bootstrap/README.md`).

| Where | How |
| --- | --- |
| Tests | Test setup runs the bootstrap script, then Flyway applies this folder as `migrator` before each test context |
| Local Supabase | `cd backend && ./mvnw initialize flyway:migrate`, with `FLYWAY_USER=migrator` in `.env.local` |
| Staging, production | Deploy pipeline (Phase 1), over a direct or session-pooler connection |

The running app never migrates: Flyway is not on its runtime classpath, and it connects as `app_user`.

`FLYWAY_URL` is a JDBC URL: `jdbc:postgresql://127.0.0.1:54322/postgres` locally.

`supabase db reset` wipes the local database, including `northstar`. Afterwards, run the bootstrap script and set
the passwords again, then run the migrate command. `flyway:clean` is disabled.

New tables give `app_user` select, insert and update, and `app_admin` select, automatically. A migration grants
delete only where the spec allows hard deletes; financial records and append-only tables never get it.
