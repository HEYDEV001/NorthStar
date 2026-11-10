-- Step 1.10: which tenants have actions to run. The sweeper starts without a tenant, and RLS shows app_user nothing
-- across tenants, so this SECURITY DEFINER function, owned by migrator (BYPASSRLS, as RLS is forced on actions),
-- returns tenant IDs and nothing else. The sweeper then reads each tenant's due actions inside that tenant's scope.
-- Due means pending and past next_attempt_at, or processing with an expired lease. search_path is pinned; only
-- app_user may call it, and the RLS release gate allowlists it.
create function northstar.tenants_with_due_actions()
returns table (tenant_id uuid)
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select distinct a.tenant_id
  from northstar.actions a
  where (a.status = 'pending' and a.next_attempt_at <= now())
     or (a.status = 'processing' and a.lease_expires_at <= now())
$$;

revoke all on function northstar.tenants_with_due_actions() from public, anon, authenticated;
grant execute on function northstar.tenants_with_due_actions() to app_user;
