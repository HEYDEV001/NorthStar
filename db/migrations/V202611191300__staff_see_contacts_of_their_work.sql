-- Step 2.3: staff see only the contacts of their own work (P11; decided 19 Nov 2026, the step 2.3 plan). Contacts
-- hold phone numbers and names, which the inbox (step 2.9) shows; until now any staff member could read every contact
-- of the business.
--
-- A staff member sees a contact, and its sources, while one of its leads (open or closed) or its conversation is
-- assigned to their own active membership. Admins and the system actor see the tenant's contacts. Restrictive, so it
-- only narrows tenant_isolation; the webhook role, a missing role and an unknown one see nothing. The same expression
-- checks writes, so staff cannot create a contact: manual lead entry (step 2.10) decides how staff may add one.
--
-- These policies read leads and conversations, whose own policies read only memberships. Never add a policy on leads,
-- conversations or memberships that reads contacts or contact_sources: Postgres would refuse every query with
-- "infinite recursion detected in policy".

-- A contact's leads, open or closed (leads_one_open_per_contact covers open ones only). The conversations branch uses
-- conversations_one_per_contact_and_channel.
create index leads_contact on northstar.leads (tenant_id, contact_id);

create policy contacts_staff_assigned on northstar.contacts
  as restrictive for all to app_user
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

create policy contact_sources_staff_assigned on northstar.contact_sources
  as restrictive for all to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and (exists (
               select from northstar.leads l
               where l.tenant_id = contact_sources.tenant_id
                 and l.contact_id = contact_sources.contact_id
                 and l.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active'))
             or exists (
               select from northstar.conversations c
               where c.tenant_id = contact_sources.tenant_id
                 and c.contact_id = contact_sources.contact_id
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
               where l.tenant_id = contact_sources.tenant_id
                 and l.contact_id = contact_sources.contact_id
                 and l.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active'))
             or exists (
               select from northstar.conversations c
               where c.tenant_id = contact_sources.tenant_id
                 and c.contact_id = contact_sources.contact_id
                 and c.assigned_membership_id = (
                   select m.id from northstar.memberships m
                   where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                     and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                     and m.status = 'active')))));
