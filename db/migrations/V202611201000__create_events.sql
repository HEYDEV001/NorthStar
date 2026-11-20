-- Step 2.4a: events, the engine's input log (data-model.md section 6, workflow-spec.md section 6). Every input to the
-- engine is one normalised event with a dedupe key unique in its tenant, so the same event recorded twice has no extra
-- effect (insert ... on conflict (tenant_id, dedupe_key) do nothing). Step 2.4b writes message.received; the engine
-- (Phase 3) reads them and sets processed_at.
--
-- IDs only: payload holds IDs, fixed codes and timestamps, never message text, names or phone numbers (message text
-- lives only in messages.body and webhook_events.payload, where retention can clear it, data-model.md section 14). The
-- size check is a backstop for that rule, not the rule itself.
--
-- lead_id is null for an event with no lead (a message from a tenant with no published workflow yet, step 2.4b).
--
-- A lifecycle table (data-model.md section 12): only processed_at changes after the insert, and nothing is ever
-- deleted. received_at is stamped by the database.
create table northstar.events (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references northstar.tenants (id),
  lead_id      uuid,
  type         text not null
                 check (type in ('message.received', 'message.status', 'button.clicked', 'optout.received',
                                 'ai.fields_extracted', 'ai.escalation', 'payment.captured', 'payment.link_expired',
                                 'payment.failed', 'refund.processed', 'timer.fired', 'staff.action',
                                 'calendar.changed', 'sheet.edited', 'integration.disconnected')),
  dedupe_key   text not null check (length(dedupe_key) between 1 and 300),
  payload      jsonb not null default '{}'
                 check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 4096),
  received_at  timestamptz not null default now(),
  processed_at timestamptz,
  unique (tenant_id, id),
  constraint events_dedupe_key unique (tenant_id, dedupe_key),
  foreign key (tenant_id, lead_id) references northstar.leads (tenant_id, id)
);

revoke insert, update on northstar.events from app_user;
grant insert (id, tenant_id, lead_id, type, dedupe_key, payload) on northstar.events to app_user;
grant update (processed_at) on northstar.events to app_user;

alter table northstar.events enable row level security;
alter table northstar.events force row level security;
create policy tenant_isolation on northstar.events for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Staff see and write only the events of leads assigned to their own active membership (P11), as messages follow
-- their conversation; an event with no lead is for admins and the system actor only. Admins and the system actor (the
-- worker) see the tenant's events. Restrictive, so it only narrows tenant_isolation; the webhook role, a missing role
-- and an unknown one see nothing. This policy reads leads, whose own policy reads only memberships, so there is no
-- recursion.
create policy events_staff_assigned on northstar.events
  as restrictive for all to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and exists (
          select from northstar.leads l
          where l.tenant_id = events.tenant_id
            and l.id = events.lead_id
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
          where l.tenant_id = events.tenant_id
            and l.id = events.lead_id
            and l.assigned_membership_id = (
              select m.id from northstar.memberships m
              where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                and m.status = 'active'))));
