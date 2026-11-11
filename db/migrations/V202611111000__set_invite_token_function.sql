-- Step 1.12: the invite email action sets the invite's token at send time. The worker runs as the system actor, which
-- the admin-only policies on invites deny, and app_user has no update grant on token_hash. This SECURITY DEFINER
-- function, owned by migrator (BYPASSRLS, as RLS is forced on invites), is the one way to change token_hash:
--   - only for app.role = 'system', so no request path can set a token;
--   - only on an invite in the caller's tenant (app.tenant_id) that is neither accepted, revoked nor expired;
--   - only to a well-formed SHA-256 hash: the raw token stays in the worker's memory and the email.
-- It returns whether it changed a row and nothing else. search_path is pinned; only app_user may call it, and the RLS
-- release gate allowlists it.
create function northstar.set_invite_token(p_invite_id uuid, p_token_hash text)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
  v_updated int;
begin
  if pg_catalog.current_setting('app.role', true) is distinct from 'system' then
    return false;
  end if;
  if p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$' then
    return false;
  end if;

  update northstar.invites i
     set token_hash = p_token_hash
   where i.id = p_invite_id
     and i.tenant_id = nullif(pg_catalog.current_setting('app.tenant_id', true), '')::uuid
     and i.accepted_at is null
     and i.revoked_at is null
     and i.expires_at > pg_catalog.now();
  get diagnostics v_updated = row_count;
  return v_updated = 1;
end
$$;

revoke all on function northstar.set_invite_token(uuid, text) from public, anon, authenticated;
grant execute on function northstar.set_invite_token(uuid, text) to app_user;
