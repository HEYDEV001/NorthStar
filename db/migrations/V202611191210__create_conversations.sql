-- Step 2.3: conversations (data-model.md section 4). One ongoing conversation per contact and channel; WhatsApp is the
-- only channel. Runs as migrator, so app_user starts with select, insert and update; both are narrowed below.
--
-- window_expires_at is the end of WhatsApp's 24-hour customer service window (workflow-spec.md section 12): generated
-- from last_inbound_at, so it can never drift from it and nobody can write it. timestamptz + interval is not immutable
-- (it depends on the session's time zone), so the sum is taken in UTC, where 24 hours is always exactly 24 hours.
-- last_inbound_at is the WhatsApp timestamp of the latest inbound message and only moves forward (step 2.4).
--
-- The contact, the channel and the WhatsApp number never change, and conversations are never deleted.
create table northstar.conversations (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null references northstar.tenants (id),
  contact_id               uuid not null,
  channel                  text not null check (channel in ('whatsapp')),
  whatsapp_phone_number_id text not null,
  status                   text not null default 'open' check (status in ('open', 'closed')),
  ai_paused                boolean not null default false,
  assigned_membership_id   uuid,
  last_inbound_at          timestamptz,
  window_expires_at        timestamptz generated always as
                             (((last_inbound_at at time zone 'UTC') + interval '24 hours') at time zone 'UTC') stored,
  needs_human              boolean not null default false,
  created_at               timestamptz not null default now(),
  updated_at               timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, contact_id, id),  -- a lead's conversation must be its own contact's (step 2.3, leads)
  constraint conversations_one_per_contact_and_channel unique (tenant_id, contact_id, channel),
  foreign key (tenant_id, contact_id) references northstar.contacts (tenant_id, id),
  foreign key (tenant_id, whatsapp_phone_number_id) references northstar.whatsapp_accounts (tenant_id, phone_number_id),
  foreign key (tenant_id, assigned_membership_id) references northstar.memberships (tenant_id, id)
);

create trigger conversations_set_updated_at before update on northstar.conversations
  for each row execute function northstar.set_updated_at();

revoke insert, update on northstar.conversations from app_user;
grant insert (id, tenant_id, contact_id, channel, whatsapp_phone_number_id, status, ai_paused, assigned_membership_id,
              last_inbound_at, needs_human)
  on northstar.conversations to app_user;
grant update (status, ai_paused, needs_human, assigned_membership_id, last_inbound_at)
  on northstar.conversations to app_user;

alter table northstar.conversations enable row level security;
alter table northstar.conversations force row level security;
create policy tenant_isolation on northstar.conversations for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Staff see and change only the conversations assigned to their own active membership (P11, build-document.md
-- section 3), as leads_staff_assigned does for leads. Admins and the system actor (the worker) see the tenant's
-- conversations. Restrictive, so it only ever narrows tenant_isolation; the webhook role, a missing role and an
-- unknown one see nothing. A lead and its conversation each carry assigned_membership_id; the inbound job (2.4) and
-- reassignment (3.9) change both in one transaction.
create policy conversations_staff_assigned on northstar.conversations
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
