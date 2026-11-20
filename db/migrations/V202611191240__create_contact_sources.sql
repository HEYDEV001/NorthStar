-- Step 2.3: contact_sources (data-model.md section 4): one row per distinct source a contact arrived from, for
-- attribution. Insert-only (data-model.md section 12): a source, once seen, never changes and is never deleted.
-- source takes the values of leads.origin. first_seen_at defaults to the insert time; the inbound job (step 2.4) may
-- set it from the message's WhatsApp timestamp.
--
-- chat_link_id and ad_referral_id get their composite foreign keys with chat_links and ad_referrals (step 7.1).
create table northstar.contact_sources (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references northstar.tenants (id),
  contact_id     uuid not null,
  source         text not null check (source in ('meta_ad', 'chat_link', 'direct', 'manual')),
  chat_link_id   uuid,
  ad_referral_id uuid,
  first_seen_at  timestamptz not null default now(),
  unique (tenant_id, id),
  -- One row per distinct source: the same source, link and referral (nulls equal) is stored once.
  constraint contact_sources_one_per_source
    unique nulls not distinct (tenant_id, contact_id, source, chat_link_id, ad_referral_id),
  foreign key (tenant_id, contact_id) references northstar.contacts (tenant_id, id)
);

revoke update on northstar.contact_sources from app_user;

alter table northstar.contact_sources enable row level security;
alter table northstar.contact_sources force row level security;
create policy tenant_isolation on northstar.contact_sources for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
-- Staff see a contact's sources exactly when they see the contact: contact_sources_staff_assigned arrives with
-- contacts_staff_assigned (V202611191300).
