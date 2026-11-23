-- Step 2.10: staff may add a contact, with its lead, by manual lead entry (decided 23 Nov 2026, the step 2.10 plan).
-- Until now contacts_staff_assigned (V202611191300) checked inserts with the same expression as reads, so a staff
-- member could never insert a contact: a new contact has no lead yet, and its lead cannot exist before it.
--
-- The policy is split by command. Reads, updates and deletes keep the step 2.3 expression word for word: staff see and
-- change a contact while one of its leads (open or closed) or its conversation is assigned to their own active
-- membership. Only inserts get a new rule: an admin, the system actor, or a staff member with an active membership.
--
-- Why this cannot let staff read a contact they could not read before:
--   - Postgres applies a policy only to the commands it is declared for. Every read is decided by the select and
--     update policies below, unchanged: a plain select, the rows an update or delete can reach, row locks (for share
--     and for update also apply the update policy), returning, and the existing row of on conflict do update.
--   - An insert policy only accepts or refuses the new row; it never makes a row visible. A contact a staff member
--     inserts stays invisible to them until a lead of theirs exists (so the insert cannot use returning, and on
--     conflict, which checks the new row against the select policy, is refused for them too).
--   - What an insert reveals is whether the phone number is taken (the unique index): exactly the contact_exists the
--     manual lead entry answers anyway.
--   - contacts_consent_guard (NSC01) still refuses a contact inserted with consent recorded by anyone but the system,
--     and contact_sources keeps contact_sources_staff_assigned: the source is written after the lead.
--
-- The four policies replace the one in the same transaction, so there is no moment without them. As in V202611191300,
-- never add a policy on leads, conversations or memberships that reads contacts (infinite recursion).

drop policy contacts_staff_assigned on northstar.contacts;

create policy contacts_staff_assigned_select on northstar.contacts
  as restrictive for select to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and (exists (
               select from northstar.leads l
               where l.tenant_id = contacts.tenant_id
                 and l.contact_id = contacts.id
                 and l.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active'))
             or exists (
               select from northstar.conversations c
               where c.tenant_id = contacts.tenant_id
                 and c.contact_id = contacts.id
                 and c.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active')))));

create policy contacts_staff_assigned_update on northstar.contacts
  as restrictive for update to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and (exists (
               select from northstar.leads l
               where l.tenant_id = contacts.tenant_id
                 and l.contact_id = contacts.id
                 and l.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active'))
             or exists (
               select from northstar.conversations c
               where c.tenant_id = contacts.tenant_id
                 and c.contact_id = contacts.id
                 and c.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active')))))
  with check (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and (exists (
               select from northstar.leads l
               where l.tenant_id = contacts.tenant_id
                 and l.contact_id = contacts.id
                 and l.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active'))
             or exists (
               select from northstar.conversations c
               where c.tenant_id = contacts.tenant_id
                 and c.contact_id = contacts.id
                 and c.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active')))));

-- app_user has no delete privilege on contacts (soft delete); this keeps the step 2.3 rule if one is ever granted.
create policy contacts_staff_assigned_delete on northstar.contacts
  as restrictive for delete to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and (exists (
               select from northstar.leads l
               where l.tenant_id = contacts.tenant_id
                 and l.contact_id = contacts.id
                 and l.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active'))
             or exists (
               select from northstar.conversations c
               where c.tenant_id = contacts.tenant_id
                 and c.contact_id = contacts.id
                 and c.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active')))));

-- Inserts: admins and the system actor as before; staff only with an active membership in the tenant. The webhook
-- actor, a missing role and an unknown one still insert nothing.
create policy contacts_staff_insert on northstar.contacts
  as restrictive for insert to app_user
  with check (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and exists (
          select from northstar.memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')));
