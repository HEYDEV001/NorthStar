-- Step 1.13: leads (data-model.md section 6), the central record. Runs as migrator, so app_user gets select, insert
-- and update; no delete (P6 is a soft delete through deleted_at).
--
-- Not linked yet: conversation_id (conversations arrive in step 2.3), ad_referral_id and chat_link_id (Phase 7). Their
-- composite foreign keys are added with those tables. "Stage must exist in the version config" is the engine's check
-- (Phase 3), as the config's schema is the engine's.
create table northstar.leads (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references northstar.tenants (id),
  contact_id             uuid not null,
  conversation_id        uuid,
  workflow_version_id    uuid not null,
  stage                  text not null,
  previous_stage         text,
  stage_entered_at       timestamptz not null default now(),
  origin                 text not null check (origin in ('meta_ad', 'chat_link', 'direct', 'manual')),
  ad_referral_id         uuid,
  chat_link_id           uuid,
  service                text,
  event_date             date,
  event_time             time,
  location               text,
  location_geo           point,
  budget_paise           bigint,
  requirements           jsonb,
  score                  smallint check (score between 0 and 100),
  band                   text check (band in ('hot', 'warm', 'cold', 'junk')),
  score_reasons          jsonb,
  junk_rule_id           text,
  lost_reason            text,
  assigned_membership_id uuid,
  amount_due_paise       bigint,
  version                int not null default 0,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz,
  closed_at              timestamptz,
  deleted_at             timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, contact_id) references northstar.contacts (tenant_id, id),
  foreign key (tenant_id, workflow_version_id) references northstar.workflow_versions (tenant_id, id),
  foreign key (tenant_id, assigned_membership_id) references northstar.memberships (tenant_id, id)
);

-- One open lead per contact (data-model.md section 13; decided 13 Nov 2026). Junk, Lost and Completed set closed_at,
-- so a contact whose lead closed can start a new one (W2).
create unique index leads_one_open_per_contact on northstar.leads (tenant_id, contact_id)
  where closed_at is null and deleted_at is null;

-- Query indexes (data-model.md section 13).
create index leads_pipeline on northstar.leads (tenant_id, stage, updated_at desc);
create index leads_staff_views on northstar.leads (tenant_id, assigned_membership_id, stage);
create index leads_score_dashboard on northstar.leads (tenant_id, band, created_at desc);
create index leads_ad_sources on northstar.leads (tenant_id, origin, ad_referral_id);
create index leads_chat_link_sources on northstar.leads (tenant_id, chat_link_id);

create trigger leads_set_updated_at before update on northstar.leads
  for each row execute function northstar.set_updated_at();

alter table northstar.leads enable row level security;
alter table northstar.leads force row level security;
create policy tenant_isolation on northstar.leads for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Staff see and change only the leads assigned to their own active membership (P11, build-document.md section 3).
-- Admins and the system actor (the worker) see the tenant's leads. Restrictive, so it only ever narrows
-- tenant_isolation; a missing or unknown role sees nothing.
create policy leads_staff_assigned on northstar.leads
  as restrictive for all to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and assigned_membership_id = (
          select m.id from northstar.memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')))
  with check (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and assigned_membership_id = (
          select m.id from northstar.memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')));
