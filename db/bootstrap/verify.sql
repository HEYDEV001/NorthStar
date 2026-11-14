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
    (4, (select rolbypassrls from pg_roles where rolname = 'app_admin'), 'app_admin has BYPASSRLS'),
    -- No Northstar role is a superuser or can create roles or databases.
    (5, (select count(*) = 4 and bool_and(not rolsuper and not rolcreaterole and not rolcreatedb) from pg_roles
         where rolname in ('migrator', 'app_user', 'app_admin', 'jobrunr_user')),
        'all four roles exist; none is superuser or can create roles or databases'),
    -- Logins: migrator, app_user and jobrunr_user have one; app_admin gets one only in Phase 8.
    (6, (select rolcanlogin from pg_roles where rolname = 'migrator'), 'migrator can log in'),
    (7, (select rolcanlogin from pg_roles where rolname = 'app_user'), 'app_user can log in'),
    (8, (select rolcanlogin from pg_roles where rolname = 'jobrunr_user'), 'jobrunr_user can log in'),
    (9, (select not rolcanlogin from pg_roles where rolname = 'app_admin'), 'app_admin cannot log in yet'),
    -- app_user and jobrunr_user cannot become another role (and so pick up its rights).
    (10, (select not exists (select from pg_auth_members where member = 'app_user'::regrole)),
         'app_user is a member of no other role'),
    (11, (select not exists (select from pg_auth_members where member = 'jobrunr_user'::regrole)),
         'jobrunr_user is a member of no other role'),
    (12, pg_has_role('postgres', 'migrator', 'MEMBER'), 'postgres can act as migrator'),
    -- Schemas: migrator owns both; JobRunr's login cannot reach Northstar's data, nor the app JobRunr's tables.
    (13, (select nspowner = 'migrator'::regrole from pg_namespace where nspname = 'northstar'),
         'migrator owns schema northstar'),
    (14, (select nspowner = 'migrator'::regrole from pg_namespace where nspname = 'jobrunr'),
         'migrator owns schema jobrunr'),
    (15, not has_schema_privilege('jobrunr_user', 'northstar', 'USAGE'),
         'jobrunr_user has no access to schema northstar'),
    (16, not has_schema_privilege('app_user', 'jobrunr', 'USAGE'), 'app_user has no access to schema jobrunr'),
    -- The two findings from the local image that Phase 1 relies on (data-model.md section 3).
    (17, not has_schema_privilege('migrator', 'auth', 'USAGE'),
         'migrator cannot use schema auth (so no foreign key to auth.users)'),
    (18, (select exists (select from pg_available_extensions where name = 'citext')
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
