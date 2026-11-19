-- Step 2.1a: each business's WhatsApp number (data-model.md section 9, integration-specs.md section 2). Inbound
-- webhooks are routed to the tenant by phone_number_id, so it is unique across all tenants: one number can never belong
-- to two businesses. Runs as migrator, so app_user starts with select, insert and update; both are narrowed below.
--
-- quality_rating and messaging_limit are unchecked text until step 2.8 defines their values.
create table northstar.whatsapp_accounts (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references northstar.tenants (id),
  waba_id         text not null check (length(waba_id) between 1 and 64),
  phone_number_id text not null check (length(phone_number_id) between 1 and 64),
  display_phone   text,
  display_name    text,
  quality_rating  text,
  messaging_limit text,
  verified_at     timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz,
  unique (tenant_id, id),
  constraint whatsapp_accounts_phone_number_id_key unique (phone_number_id)
);

create trigger whatsapp_accounts_set_updated_at before update on northstar.whatsapp_accounts
  for each row execute function northstar.set_updated_at();

-- The account and number IDs never change (another number is another row); accounts are never deleted.
revoke insert, update on northstar.whatsapp_accounts from app_user;
grant insert (id, tenant_id, waba_id, phone_number_id, display_phone, display_name, quality_rating, messaging_limit,
              verified_at)
  on northstar.whatsapp_accounts to app_user;
grant update (display_phone, display_name, quality_rating, messaging_limit, verified_at)
  on northstar.whatsapp_accounts to app_user;

alter table northstar.whatsapp_accounts enable row level security;
alter table northstar.whatsapp_accounts force row level security;
create policy tenant_isolation on northstar.whatsapp_accounts for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
