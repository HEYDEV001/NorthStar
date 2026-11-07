-- The membership lookup at login (step 1.5, data-model.md section 12). It runs before the tenant is known, when RLS
-- hides every membership, so it is a SECURITY DEFINER function owned by migrator (which bypasses RLS). It is kept as
-- narrow as possible: no arguments, it reads only app.user_id, and it returns only that user's active membership
-- (at most one row, D4). search_path is pinned and every name is fully qualified, so nothing a caller creates can be
-- picked up by it. Only app_user may call it, and the RLS release gate allowlists it.

create function northstar.current_membership()
returns table (tenant_id uuid, membership_id uuid, role text)
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select m.tenant_id, m.id, m.role
  from northstar.memberships m
  where m.user_id = nullif(pg_catalog.current_setting('app.user_id', true), '')::uuid
    and m.status = 'active'
$$;

revoke all on function northstar.current_membership() from public, anon, authenticated;
grant execute on function northstar.current_membership() to app_user;
