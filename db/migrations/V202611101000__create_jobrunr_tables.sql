-- JobRunr's tables (step 1.10), in the jobrunr schema that db/bootstrap/roles.sql creates for migrator. This is the
-- schema JobRunr 9.0.2 itself builds on Postgres (its migrations v000 to v017, with the Postgres v014), written out
-- in its final form because JobRunr's own table creation is off. JobRunrSchemaTests compares it with what JobRunr
-- creates and with JobRunr's migration list, so upgrading JobRunr without a new migration here fails the build.
--
-- These tables hold no tenant data beyond IDs in job arguments (data-model.md section 6). Only jobrunr_user, the
-- login of JobRunr's private pool, may use them; the app roles get nothing here, and jobrunr_user gets nothing in
-- northstar (data-model.md section 12).

create table jobrunr.jobrunr_migrations (
  id          nchar(36) primary key,
  script      varchar(64) not null,
  installedon varchar(29) not null
);

create table jobrunr.jobrunr_jobs (
  id             nchar(36) primary key,
  version        int          not null,
  jobasjson      text         not null,
  jobsignature   varchar(512) not null,
  state          varchar(36)  not null,
  createdat      timestamp    not null,
  updatedat      timestamp    not null,
  scheduledat    timestamp,
  recurringjobid varchar(128)
);
create index jobrunr_state_idx on jobrunr.jobrunr_jobs (state);
create index jobrunr_job_signature_idx on jobrunr.jobrunr_jobs (jobsignature);
create index jobrunr_job_created_at_idx on jobrunr.jobrunr_jobs (createdat);
create index jobrunr_job_scheduled_at_idx on jobrunr.jobrunr_jobs (scheduledat);
create index jobrunr_job_rci_idx on jobrunr.jobrunr_jobs (recurringjobid);
create index jobrunr_jobs_state_updated_idx on jobrunr.jobrunr_jobs (state asc, updatedat asc);

create table jobrunr.jobrunr_recurring_jobs (
  id        nchar(128) primary key,
  version   int    not null,
  jobasjson text   not null,
  createdat bigint not null default '0'
);
create index jobrunr_recurring_job_created_at_idx on jobrunr.jobrunr_recurring_jobs (createdat);

create table jobrunr.jobrunr_backgroundjobservers (
  id                         nchar(36) primary key,
  workerpoolsize             int           not null,
  pollintervalinseconds      int           not null,
  firstheartbeat             timestamp(6)  not null,
  lastheartbeat              timestamp(6)  not null,
  running                    int           not null,
  systemtotalmemory          bigint        not null,
  systemfreememory           bigint        not null,
  systemcpuload              numeric(3, 2) not null,
  processmaxmemory           bigint        not null,
  processfreememory          bigint        not null,
  processallocatedmemory     bigint        not null,
  processcpuload             numeric(3, 2) not null,
  deletesucceededjobsafter   varchar(32),
  permanentlydeletejobsafter varchar(32),
  name                       varchar(128)
);
create index jobrunr_bgjobsrvrs_fsthb_idx on jobrunr.jobrunr_backgroundjobservers (firstheartbeat);
create index jobrunr_bgjobsrvrs_lsthb_idx on jobrunr.jobrunr_backgroundjobservers (lastheartbeat);

create table jobrunr.jobrunr_metadata (
  id        varchar(156) primary key,
  name      varchar(92) not null,
  owner     varchar(64) not null,
  value     text        not null,
  createdat timestamp   not null,
  updatedat timestamp   not null
);
insert into jobrunr.jobrunr_metadata (id, name, owner, value, createdat, updatedat)
values ('succeeded-jobs-counter-cluster', 'succeeded-jobs-counter', 'cluster', '0', current_timestamp,
        current_timestamp);

-- JobRunr's v016 view, as security_invoker (the release gate refuses views that run with their owner's rights).
create view jobrunr.jobrunr_jobs_stats with (security_invoker = true) as
with job_stat_results as (
  select state, count(*) as count from jobrunr.jobrunr_jobs group by state
)
select coalesce((select sum(job_stat_results.count) from job_stat_results), 0)                             as total,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'AWAITING'), 0)    as awaiting,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'SCHEDULED'), 0)   as scheduled,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'ENQUEUED'), 0)    as enqueued,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'PROCESSING'), 0)  as processing,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'PROCESSED'), 0)   as processed,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'FAILED'), 0)      as failed,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'SUCCEEDED'), 0)   as succeeded,
       coalesce((select cast(cast(value as char(10)) as decimal(10, 0))
                 from jobrunr.jobrunr_metadata jm
                 where jm.id = 'succeeded-jobs-counter-cluster'), 0)                                       as alltimesucceeded,
       coalesce((select sum(job_stat_results.count) from job_stat_results where state = 'DELETED'), 0)     as deleted,
       (select count(*) from jobrunr.jobrunr_backgroundjobservers)                                         as nbrofbackgroundjobservers,
       (select count(*) from jobrunr.jobrunr_recurring_jobs)                                               as nbrofrecurringjobs;

-- Recorded as applied, so JobRunr's own migration runner (if ever switched on) would find nothing to do.
insert into jobrunr.jobrunr_migrations (id, script, installedon)
select gen_random_uuid()::text, script, to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
from unnest(array[
  'v000__create_migrations_table.sql',
  'v001__create_job_table.sql',
  'v002__create_recurring_job_table.sql',
  'v003__create_background_job_server_table.sql',
  'v004__create_job_stats_view.sql',
  'v005__update_job_stats_view.sql',
  'v006__alter_table_jobs_add_recurringjob.sql',
  'v007__alter_table_backgroundjobserver_add_delete_config.sql',
  'v008__alter_table_jobs_increase_jobAsJson_size.sql',
  'v009__change_jobrunr_job_counters_to_jobrunr_metadata.sql',
  'v010__change_job_stats.sql',
  'v011__change_sqlserver_text_to_varchar.sql',
  'v012__change_oracle_alter_jobrunr_metadata_column_size.sql',
  'v013__alter_table_recurring_job_add_createdAt.sql',
  'v014__improve_job_stats.sql',
  'v015__alter_table_backgroundjobserver_add_name.sql',
  'v016__alter_jobs_stats_add_awaiting_jobs.sql',
  'v017__change_job_stats_view_to_sql_security_invoker.sql'
]) as script;

-- Only jobrunr_user, with full DML (JobRunr deletes its own finished jobs). Nothing for anyone else.
revoke all on all tables in schema jobrunr from public;
grant usage on schema jobrunr to jobrunr_user;
grant select, insert, update, delete on all tables in schema jobrunr to jobrunr_user;
revoke insert, update, delete on jobrunr.jobrunr_jobs_stats from jobrunr_user;
