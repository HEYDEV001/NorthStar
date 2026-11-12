-- Step 1.13: contacts (data-model.md section 4). One per phone number per tenant; a contact can have many leads over
-- time. Runs as migrator, so app_user gets select, insert and update (no delete: contacts are soft-deleted).
create table northstar.contacts (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references northstar.tenants (id),
  normalized_phone   text not null check (normalized_phone ~ '^\+[1-9][0-9]{6,14}$'),  -- E.164, as memberships
  name               text,
  email              text,
  is_repeat_customer boolean not null default false,
  consent_state      text not null default 'unknown' check (consent_state in ('unknown', 'opted_in', 'opted_out')),
  opted_in_at        timestamptz,
  opt_out_at         timestamptz,
  attributes         jsonb,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz,
  deleted_at         timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, normalized_phone)
);

create trigger contacts_set_updated_at before update on northstar.contacts
  for each row execute function northstar.set_updated_at();

alter table northstar.contacts enable row level security;
alter table northstar.contacts force row level security;
create policy tenant_isolation on northstar.contacts for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
