-- Step 1.13: workflows and their published versions (data-model.md section 6, workflow-spec.md section 16). Edits
-- happen on workflows.draft_config; publishing inserts a new workflow_versions row, which never changes afterwards.
-- Whether a tenant may have more than one workflow is open (data-model.md section 15); the engine keeps one active
-- workflow per tenant for MVP, so there is no constraint here.
create table northstar.workflows (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references northstar.tenants (id),
  name               text not null,
  template           text,
  current_version_id uuid,  -- foreign key added below, once workflow_versions exists
  draft_config       jsonb,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz,
  unique (tenant_id, id)
);

create trigger workflows_set_updated_at before update on northstar.workflows
  for each row execute function northstar.set_updated_at();

alter table northstar.workflows enable row level security;
alter table northstar.workflows force row level security;
create policy tenant_isolation on northstar.workflows for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);


-- workflow_versions: published versions only. published_by is the publishing member, or null when the system
-- publishes one (a template at onboarding).
create table northstar.workflow_versions (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references northstar.tenants (id),
  workflow_id  uuid not null,
  version      int not null check (version > 0),
  config       jsonb not null,
  published_by uuid,
  published_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, workflow_id, id),
  unique (tenant_id, workflow_id, version),
  foreign key (tenant_id, workflow_id) references northstar.workflows (tenant_id, id),
  foreign key (tenant_id, published_by) references northstar.memberships (tenant_id, id)
);

-- A workflow's current version must be one of its own versions, in the same tenant.
alter table northstar.workflows
  add constraint workflows_current_version_fk
  foreign key (tenant_id, id, current_version_id) references northstar.workflow_versions (tenant_id, workflow_id, id);

-- Published versions are immutable for every role. app_user may only insert and read (the RLS release gate checks
-- it); these triggers also stop the owner (migrator) and app_admin, which the missing grant alone would not.
create function northstar.refuse_workflow_version_change() returns trigger
language plpgsql
as $$
begin
  raise exception 'Published workflow versions are immutable; publish a new version instead'
    using errcode = 'NSW01';
end
$$;

create trigger workflow_versions_immutable before update or delete on northstar.workflow_versions
  for each row execute function northstar.refuse_workflow_version_change();
create trigger workflow_versions_no_truncate before truncate on northstar.workflow_versions
  for each statement execute function northstar.refuse_workflow_version_change();

revoke update on northstar.workflow_versions from app_user;

alter table northstar.workflow_versions enable row level security;
alter table northstar.workflow_versions force row level security;
create policy tenant_isolation on northstar.workflow_versions for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
