-- P14: a business always keeps at least one active admin, whatever code path changes a membership.
--
-- How it holds under concurrency: when an active admin stops being one (demoted, deactivated or deleted), the trigger
-- takes a transaction advisory lock for that tenant, then checks that an active admin is left. Two admins demoting each
-- other at once: the second trigger waits for the first transaction to commit, then its check runs with a fresh
-- snapshot, sees the first change, finds no admin left and refuses with SQLSTATE NSA01 (the API answers 409
-- last_admin). The lock is per tenant and only taken in that one case, so other businesses and other updates (for
-- example an admin's whatsapp_phone) never wait.
--
-- Isolation: the fresh snapshot is what makes the re-check correct. The app runs every transaction at Postgres's
-- default READ COMMITTED (TenantTransactionTests checks it). SERIALIZABLE is also safe: it aborts one of two
-- conflicting transactions itself. REPEATABLE READ is not: the second transaction would keep its old snapshot and miss
-- the first commit. So the function refuses to run under REPEATABLE READ (SQLSTATE NSA02).
--
-- Deliberately an invoker function, not SECURITY DEFINER: it runs as the caller, under RLS. A caller who cannot see the
-- tenant's memberships finds no admin and is refused. That is fail-closed and correct; do not "fix" it into
-- SECURITY DEFINER.

create function northstar.keep_an_active_admin() returns trigger
language plpgsql
set search_path = pg_catalog, pg_temp
as $$
begin
  if current_setting('transaction_isolation') = 'repeatable read' then
    raise exception 'keep_an_active_admin cannot run under REPEATABLE READ' using errcode = 'NSA02';
  end if;

  perform pg_advisory_xact_lock(hashtext('northstar.active_admins'), hashtext(old.tenant_id::text));

  if not exists (select from northstar.memberships m
                 where m.tenant_id = old.tenant_id and m.role = 'admin' and m.status = 'active') then
    raise exception 'a business must keep at least one active admin' using errcode = 'NSA01';
  end if;
  return null;
end
$$;

-- Only when an active admin actually stops being an active admin.
create trigger memberships_keep_an_active_admin_on_update
  after update on northstar.memberships
  for each row
  when (old.role = 'admin' and old.status = 'active' and (new.role <> 'admin' or new.status <> 'active'))
  execute function northstar.keep_an_active_admin();

-- app_user has no delete on memberships; this covers every other role.
create trigger memberships_keep_an_active_admin_on_delete
  after delete on northstar.memberships
  for each row
  when (old.role = 'admin' and old.status = 'active')
  execute function northstar.keep_an_active_admin();
