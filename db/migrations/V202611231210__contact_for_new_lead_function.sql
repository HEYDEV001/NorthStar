-- Step 2.10: the contact a member's manual lead entry is for, when the phone number already has one (W2, P11; decided
-- 23 Nov 2026, the step 2.10 plan).
--
-- A staff member entering a lead for a returning customer (a contact whose leads are all closed, or who has none) gets
-- a new lead on that contact, and sees the contact through it from then on. Until that lead exists, RLS hides the
-- contact from them (contacts_staff_assigned_select), so no query with the caller's rights can find its ID or lock it,
-- and the lead's insert needs both: the ID, and the contact's row lock that the inbound job also takes (steps 2.4b,
-- 2.5), so the two never open a lead for the same contact at once. This SECURITY DEFINER function, owned by migrator
-- (BYPASSRLS, as RLS is forced on contacts and leads), does only that:
--   - only for app.role 'admin' or 'staff' with an active membership of that role in app.tenant_id (the system, the
--     webhook actor and a missing or unknown role get nothing), and only in that tenant: the tenant is never an
--     argument;
--   - it finds the contact with exactly this normalized phone and locks it for update until the caller's transaction
--     ends;
--   - it returns whether the contact has an open lead (not closed, not deleted), and its ID only when the caller may
--     use or see it: when it has no open lead (the caller is about to open one), when the caller is an admin (who
--     sees every contact anyway), or when the open lead is the caller's own. A contact with another member's open lead
--     yields no ID to a staff member, so they learn nothing beyond contact_exists.
-- It reads nothing else and changes nothing. search_path is pinned; only app_user may call it, and the RLS release gate
-- allowlists it.
create function northstar.contact_for_new_lead(p_phone text)
returns table (contact_id uuid, has_open_lead boolean)
language sql
security definer
set search_path = pg_catalog, pg_temp
as $$
  select case
           when open_lead.id is null
             or caller.role = 'admin'
             or open_lead.assigned_membership_id = caller.id
           then c.id
         end,
         open_lead.id is not null
  from northstar.memberships caller
  join northstar.contacts c
    on c.tenant_id = caller.tenant_id
   and c.normalized_phone = p_phone
  left join lateral (
    select l.id, l.assigned_membership_id
    from northstar.leads l
    where l.tenant_id = c.tenant_id
      and l.contact_id = c.id
      and l.closed_at is null
      and l.deleted_at is null
  ) open_lead on true
  where caller.tenant_id = nullif(pg_catalog.current_setting('app.tenant_id', true), '')::uuid
    and caller.user_id = nullif(pg_catalog.current_setting('app.user_id', true), '')::uuid
    and caller.status = 'active'
    and caller.role = pg_catalog.current_setting('app.role', true)
    and caller.role in ('admin', 'staff')
  for update of c
$$;

revoke all on function northstar.contact_for_new_lead(text) from public, anon, authenticated;
grant execute on function northstar.contact_for_new_lead(text) to app_user;
