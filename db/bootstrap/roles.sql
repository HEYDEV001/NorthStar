-- One-time setup of Northstar's database roles, per environment (see README.md in this folder).
-- Run as postgres. Safe to run again. No passwords here: logins are enabled separately with \password.

do $$
begin
  if not exists (select from pg_roles where rolname = 'migrator') then
    create role migrator nologin;
  end if;
  if not exists (select from pg_roles where rolname = 'app_user') then
    create role app_user nologin;
  end if;
  if not exists (select from pg_roles where rolname = 'app_admin') then
    create role app_admin nologin;
  end if;
  if not exists (select from pg_roles where rolname = 'jobrunr_user') then
    create role jobrunr_user nologin;
  end if;
  if not exists (select from pg_roles where rolname = 'secrets_user') then
    create role secrets_user nologin;
  end if;
end
$$;

-- Re-assert the attributes on every run, so a role changed by hand is put back (data-model.md section 12).
alter role migrator  nocreatedb nocreaterole bypassrls;    -- Flyway only
alter role app_user  nocreatedb nocreaterole nobypassrls;  -- API and worker; RLS always applies
alter role app_admin nocreatedb nocreaterole bypassrls;    -- Northstar admin module, read-mostly, audited
alter role jobrunr_user nocreatedb nocreaterole nobypassrls; -- JobRunr's own tables only; no access to northstar
alter role secrets_user nocreatedb nocreaterole nobypassrls; -- the worker's credential pool; secrets schema only

-- postgres can act as migrator, so it can hand over the schema below and operators can run fixes as migrator.
grant migrator to postgres;

-- migrator owns the northstar schema and everything in it, so every environment has the same owner and the same
-- default privileges. Tables left owned by another role (a database first migrated as postgres) move to migrator.
create schema if not exists northstar authorization migrator;
alter schema northstar owner to migrator;

-- JobRunr's tables live in their own schema, also owned by migrator; Flyway creates them and only jobrunr_user may
-- use them (step 1.10). No role but migrator gets anything here by default.
create schema if not exists jobrunr authorization migrator;
alter schema jobrunr owner to migrator;
revoke all on schema jobrunr from public;

-- Tenant credentials (step 2.1a): only secrets_user, whose password is set only on the worker, may use this schema, so
-- the API cannot read even the ciphertext. Webhooks that could not be routed to a tenant: only app_admin may read them.
-- Neither schema has default privileges; each table's migration grants exactly what its one role needs.
create schema if not exists secrets authorization migrator;
alter schema secrets owner to migrator;
revoke all on schema secrets from public;
create schema if not exists ingest authorization migrator;
alter schema ingest owner to migrator;
revoke all on schema ingest from public;

-- btree_gist (step 2.8a): lets an exclusion constraint compare plain columns with = and <>. Extensions are installed
-- here, by postgres, never by a migration running as migrator. The first use is "one tenant per WABA" on whatsapp_accounts;
-- Phase 6's booking exclusions need it too. It goes in the extensions schema, as Supabase installs its extensions;
-- hosted Supabase already has that schema, and a new database (local or test) gets it here. Neither app role is given
-- any right on it: an index uses the extension's operator classes without one.
create schema if not exists extensions;
create extension if not exists btree_gist with schema extensions;

do $$
declare
  t record;
begin
  for t in
    select c.oid::regclass as name
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname in ('northstar', 'secrets', 'ingest')
      and c.relkind in ('r', 'p')
      and pg_get_userbyid(c.relowner) <> 'migrator'
  loop
    execute format('alter table %s owner to migrator', t.name);
  end loop;
end
$$;
