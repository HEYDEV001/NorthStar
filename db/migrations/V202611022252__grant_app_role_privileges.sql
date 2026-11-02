-- Access for the app roles (data-model.md section 12). Runs as migrator, the owner of northstar, so the default
-- privileges below apply to every table and sequence migrator creates from now on.

grant usage on schema northstar to app_user, app_admin;

-- app_user reads and writes but never deletes by default. A table that allows hard deletes grants delete in its own
-- migration; financial records and append-only tables never do.
alter default privileges in schema northstar grant select, insert, update on tables to app_user;
alter default privileges in schema northstar grant usage, select on sequences to app_user;

-- app_admin is read-only by default; any admin write is granted per table.
alter default privileges in schema northstar grant select on tables to app_admin;

-- Migration history belongs to migrator alone.
revoke all on northstar.flyway_schema_history from app_user, app_admin;
