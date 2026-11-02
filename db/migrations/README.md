# Database migrations

Flyway owns the schema. The Supabase CLI's own migrations are turned off (`supabase/config.toml`).

## Rules

- One file per change: `V{yyyyMMddHHmm}__{snake_description}.sql`, e.g. `V202611051030__create_tenants.sql`.
- Never edit a migration that has been merged. Write a new one.
- Every table goes in the `northstar` schema (`data-model.md` section 1).
- A table with `tenant_id` gets RLS enabled, forced, and the `tenant_isolation` policy in the same file
  (`data-model.md` section 12). The CI check fails if one is missing.
- Follow `data-model.md` for keys, timestamps, money, enums and the indexes in section 13.

## Who runs them

| Where | How | Role |
| --- | --- | --- |
| Tests | Flyway applies this folder to the Testcontainers database before each test context | `postgres` |
| Local Supabase | `cd backend && ./mvnw initialize flyway:migrate` | `FLYWAY_USER` from `.env.local` |
| Staging, production | Deploy pipeline (Phase 1) | `migrator` |

The running app never migrates: Flyway is not on its runtime classpath, and it connects as `app_user`.

Until Phase 1 creates the `migrator` role, `FLYWAY_USER` is `postgres` locally. `FLYWAY_URL` is a JDBC URL:
`jdbc:postgresql://127.0.0.1:54322/postgres`.

`supabase db reset` wipes the local database, including `northstar`; run the migrate command again afterwards.
`flyway:clean` is disabled.
