-- Step 2.1a: one row per connected provider per business (data-model.md section 9, integration-specs.md section 1).
-- Runs as migrator, so app_user starts with select, insert and update; both are narrowed below.
--
-- last_error_code is a fixed code with the same check as actions.last_error_code: never provider message text, which
-- can carry tokens, phone numbers or message bodies. Staff calendars connect per member in calendar_connections
-- (Phase 6), so one row per provider is enough here.
create table northstar.integrations (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references northstar.tenants (id),
  provider        text not null check (provider in ('whatsapp', 'razorpay', 'google', 'meta_ads', 'meta_capi')),
  status          text not null check (status in ('connected', 'error', 'disconnected')),
  account_label   text,
  scopes          text[] not null default '{}',
  credential_id   uuid,
  last_success_at timestamptz,
  last_error_code text check (last_error_code ~ '^[a-z0-9_]{1,64}$'),
  last_error_at   timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, provider),
  -- The credential sits in the private secrets schema; the foreign key check runs as the owner, so app_user needs no
  -- access there.
  constraint integrations_credential_fk foreign key (tenant_id, credential_id)
    references secrets.integration_credentials (tenant_id, id),
  constraint integrations_error_complete check ((last_error_code is null) = (last_error_at is null))
);

create trigger integrations_set_updated_at before update on northstar.integrations
  for each row execute function northstar.set_updated_at();

-- The tenant and provider never change; integrations are never deleted (a disconnected one keeps its row).
revoke insert, update on northstar.integrations from app_user;
grant insert (id, tenant_id, provider, status, account_label, scopes, credential_id, last_success_at, last_error_code,
              last_error_at)
  on northstar.integrations to app_user;
grant update (status, account_label, scopes, credential_id, last_success_at, last_error_code, last_error_at)
  on northstar.integrations to app_user;

alter table northstar.integrations enable row level security;
alter table northstar.integrations force row level security;
create policy tenant_isolation on northstar.integrations for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
