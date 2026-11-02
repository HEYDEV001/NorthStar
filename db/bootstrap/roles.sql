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
end
$$;

-- Re-assert the attributes on every run, so a role changed by hand is put back (data-model.md section 12).
alter role migrator  nocreatedb nocreaterole bypassrls;    -- Flyway only
alter role app_user  nocreatedb nocreaterole nobypassrls;  -- API and worker; RLS always applies
alter role app_admin nocreatedb nocreaterole bypassrls;    -- Northstar admin module, read-mostly, audited

-- postgres can act as migrator, so it can hand over the schema below and operators can run fixes as migrator.
grant migrator to postgres;

-- migrator owns the northstar schema and everything in it, so every environment has the same owner and the same
-- default privileges. Tables left owned by another role (a database first migrated as postgres) move to migrator.
create schema if not exists northstar authorization migrator;
alter schema northstar owner to migrator;

do $$
declare
  t record;
begin
  for t in
    select c.oid::regclass as name
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'northstar'
      and c.relkind in ('r', 'p')
      and pg_get_userbyid(c.relowner) <> 'migrator'
  loop
    execute format('alter table %s owner to migrator', t.name);
  end loop;
end
$$;
