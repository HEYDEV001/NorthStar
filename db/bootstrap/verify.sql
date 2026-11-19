-- Read-only checks that roles.sql did what Northstar relies on, per environment (README.md in this folder, step 1.15).
-- Run as postgres after roles.sql and the \password steps, and again after the first migration. Every line must say
-- PASS. A FAIL means stop and report it: never fix one by weakening a policy or giving app_user more rights.
-- Prints role names, schema names and PASS/FAIL only.

begin read only;

with checks (n, ok, description) as (
  values
    -- Row-level security: only migrator (Flyway) and app_admin (Phase 8, audited) may bypass it.
    (1, (select rolbypassrls from pg_roles where rolname = 'migrator'), 'migrator has BYPASSRLS'),
    (2, (select not rolbypassrls from pg_roles where rolname = 'app_user'), 'app_user does not have BYPASSRLS'),
    (3, (select not rolbypassrls from pg_roles where rolname = 'jobrunr_user'),
        'jobrunr_user does not have BYPASSRLS'),
    (4, (select not rolbypassrls from pg_roles where rolname = 'secrets_user'),
        'secrets_user does not have BYPASSRLS'),
    (5, (select rolbypassrls from pg_roles where rolname = 'app_admin'), 'app_admin has BYPASSRLS'),
    -- No Northstar role is a superuser or can create roles or databases.
    (6, (select count(*) = 5 and bool_and(not rolsuper and not rolcreaterole and not rolcreatedb) from pg_roles
         where rolname in ('migrator', 'app_user', 'app_admin', 'jobrunr_user', 'secrets_user')),
        'all five roles exist; none is superuser or can create roles or databases'),
    -- Logins: migrator, app_user, jobrunr_user and secrets_user have one; app_admin gets one only in Phase 8.
    (7, (select rolcanlogin from pg_roles where rolname = 'migrator'), 'migrator can log in'),
    (8, (select rolcanlogin from pg_roles where rolname = 'app_user'), 'app_user can log in'),
    (9, (select rolcanlogin from pg_roles where rolname = 'jobrunr_user'), 'jobrunr_user can log in'),
    (10, (select rolcanlogin from pg_roles where rolname = 'secrets_user'), 'secrets_user can log in'),
    (11, (select not rolcanlogin from pg_roles where rolname = 'app_admin'), 'app_admin cannot log in yet'),
    -- app_user, jobrunr_user and secrets_user cannot become another role (and so pick up its rights).
    (12, (select not exists (select from pg_auth_members where member = 'app_user'::regrole)),
         'app_user is a member of no other role'),
    (13, (select not exists (select from pg_auth_members where member = 'jobrunr_user'::regrole)),
         'jobrunr_user is a member of no other role'),
    (14, (select not exists (select from pg_auth_members where member = 'secrets_user'::regrole)),
         'secrets_user is a member of no other role'),
    (15, pg_has_role('postgres', 'migrator', 'MEMBER'), 'postgres can act as migrator'),
    -- Schemas: migrator owns all four; JobRunr's login cannot reach Northstar's data, nor the app JobRunr's tables;
    -- only secrets_user reaches the credentials, and the app reaches neither private schema.
    (16, (select nspowner = 'migrator'::regrole from pg_namespace where nspname = 'northstar'),
         'migrator owns schema northstar'),
    (17, (select nspowner = 'migrator'::regrole from pg_namespace where nspname = 'jobrunr'),
         'migrator owns schema jobrunr'),
    (18, (select nspowner = 'migrator'::regrole from pg_namespace where nspname = 'secrets'),
         'migrator owns schema secrets'),
    (19, (select nspowner = 'migrator'::regrole from pg_namespace where nspname = 'ingest'),
         'migrator owns schema ingest'),
    (20, not has_schema_privilege('jobrunr_user', 'northstar', 'USAGE'),
         'jobrunr_user has no access to schema northstar'),
    (21, not has_schema_privilege('app_user', 'jobrunr', 'USAGE'), 'app_user has no access to schema jobrunr'),
    (22, not has_schema_privilege('secrets_user', 'northstar', 'USAGE'),
         'secrets_user has no access to schema northstar'),
    (23, not has_schema_privilege('app_user', 'secrets', 'USAGE'), 'app_user has no access to schema secrets'),
    (24, not has_schema_privilege('app_user', 'ingest', 'USAGE'), 'app_user has no access to schema ingest'),
    -- The two findings from the local image that Phase 1 relies on (data-model.md section 3).
    (25, not has_schema_privilege('migrator', 'auth', 'USAGE'),
         'migrator cannot use schema auth (so no foreign key to auth.users)'),
    (26, (select exists (select from pg_available_extensions where name = 'citext')
             and not exists (select from pg_extension where extname = 'citext')),
         'citext is available but not installed')
),
results as (
  select n, case when ok is true then 'PASS' else 'FAIL' end as result, description from checks
)
select result, description
from (
  select n, result, description from results
  union all
  select 1000,
         case when bool_and(result = 'PASS') then 'PASS' else 'FAIL' end,
         'all ' || count(*) || ' checks (' || count(*) filter (where result = 'FAIL') || ' failed)'
  from results
) summary
order by n;

rollback;
