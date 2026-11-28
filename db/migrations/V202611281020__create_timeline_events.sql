-- Step 3.3: timeline_events, a lead's history (data-model.md section 10). Step 3.3 writes stage_changed for every stage
-- change and for a lead's creation; each later type joins the type check, with its data and ref, in its own step.
--
-- No text: summary is a fixed code and data a JSON object of IDs, stage keys, fixed codes, numbers and timestamps only
-- (the size check is a backstop for that rule, not the rule itself). The table is insert-only, so anything written here
-- stays: a message is referenced by its ID, never copied, so retention clearing its body clears it here too.
--
-- The contact is always the lead's own (the composite key leads_contact_lead_key). ref_id has no foreign key, like
-- audit_log.target_id: what it points at depends on ref_table.
--
-- Insert-only (data-model.md section 12). Runs as migrator, so app_user gets select, insert and update by default:
-- update is revoked, delete is never granted, and created_at is stamped by the database.
create table northstar.timeline_events (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references northstar.tenants (id),
  lead_id             uuid not null,
  contact_id          uuid not null,
  type                text not null check (type in ('stage_changed')),
  actor_type          text not null check (actor_type in ('ai', 'system', 'staff', 'contact')),
  actor_membership_id uuid,
  ref_table           text check (ref_table in ('events', 'messages')),
  ref_id              uuid,
  summary             text not null check (summary ~ '^[a-z][a-z0-9_.]{0,63}$'),
  data                jsonb not null default '{}'
                        check (jsonb_typeof(data) = 'object' and octet_length(data::text) <= 4096),
  occurred_at         timestamptz not null default now(),
  created_at          timestamptz not null default now(),
  unique (tenant_id, id),
  -- A staff actor (an admin's or a staff member's membership) names its membership; no other actor does.
  constraint timeline_events_actor_complete check ((actor_type = 'staff') = (actor_membership_id is not null)),
  constraint timeline_events_ref_complete check ((ref_table is null) = (ref_id is null)),
  constraint timeline_events_lead_fk
    foreign key (tenant_id, contact_id, lead_id) references northstar.leads (tenant_id, contact_id, id),
  constraint timeline_events_actor_fk
    foreign key (tenant_id, actor_membership_id) references northstar.memberships (tenant_id, id)
);

-- A lead's history, newest first, paged by (occurred_at, id) (data-model.md section 13).
create index timeline_events_lead_history
  on northstar.timeline_events (tenant_id, lead_id, occurred_at desc, id desc);

revoke insert, update on northstar.timeline_events from app_user;
grant insert (id, tenant_id, lead_id, contact_id, type, actor_type, actor_membership_id, ref_table, ref_id, summary,
              data, occurred_at)
  on northstar.timeline_events to app_user;

alter table northstar.timeline_events enable row level security;
alter table northstar.timeline_events force row level security;
create policy tenant_isolation on northstar.timeline_events for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Staff see and write only the rows of leads assigned to their own active membership (P11), as with the lead's events;
-- admins and the system actor see the tenant's. Restrictive, so it only narrows tenant_isolation; the webhook role, a
-- missing role and an unknown one see nothing. It reads leads, whose own policy reads only memberships, so there is no
-- recursion.
create policy timeline_events_staff_assigned on northstar.timeline_events
  as restrictive for all to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and exists (
          select from northstar.leads l
          where l.tenant_id = timeline_events.tenant_id
            and l.id = timeline_events.lead_id
            and l.assigned_membership_id = (
              select m.id from northstar.memberships m
              where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                and m.status = 'active'))))
  with check (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and exists (
          select from northstar.leads l
          where l.tenant_id = timeline_events.tenant_id
            and l.id = timeline_events.lead_id
            and l.assigned_membership_id = (
              select m.id from northstar.memberships m
              where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                and m.status = 'active'))));

-- Who a row names as its actor: the system actor any (a staff member's action can reach the worker from WhatsApp); an
-- admin or a staff member only themselves, as a staff actor with their own active membership, so no member writes a
-- row in someone else's name.
create policy timeline_events_actor_own on northstar.timeline_events
  as restrictive for insert to app_user
  with check (
    current_setting('app.role', true) = 'system'
    or (current_setting('app.role', true) in ('admin', 'staff')
        and actor_type = 'staff'
        and actor_membership_id = (
          select m.id from northstar.memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')));
