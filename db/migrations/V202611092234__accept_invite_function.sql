-- Step 1.9: accepting an invite (P12). The invitee has no tenant context yet, and RLS hides every invite and
-- membership from them, so acceptance is one SECURITY DEFINER function owned by migrator. Like current_membership()
-- and slug_is_taken(), it relies on migrator's BYPASSRLS: RLS is forced on these tables, so without it the function
-- would see nothing. Because it bypasses RLS, it does every check itself, in this order:
--
--   1. not_found        no invite with this token hash
--   2. email_mismatch   the signed-in account's email (from the verified token) is not the invite's, ignoring case;
--                       checked before anything else, so a wrong account learns nothing about the invite's state
--   3. used / revoked / expired
--   4. active_elsewhere the user already has an active membership anywhere (D4)
--
-- On success, in the same transaction: the profile is created if new, an active membership is created in the invite's
-- tenant with the invite's role (or the user's deactivated membership there is reactivated with that role), the invite
-- is marked accepted, and invite.accepted is audited. Every write uses the invite's own tenant_id, never anything the
-- caller passes. The invite row is locked first, so two accepts of one token, or an accept racing a revoke, run one
-- after the other: single use (P12).
--
-- The caller is the user in app.user_id (set by the backend from the verified token); the token hash and the email
-- are the only arguments. A failed check writes nothing and only returns its outcome; the API maps outcomes to errors
-- that never include the invite's email, the business or the tenant.

create function northstar.accept_invite(p_token_hash text, p_email text)
returns table (outcome text, tenant_id uuid, membership_id uuid, role text)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
declare
  v_user uuid := nullif(pg_catalog.current_setting('app.user_id', true), '')::uuid;
  v_invite northstar.invites%rowtype;
  v_membership uuid;
  v_previous_status text;
  v_constraint text;
begin
  if v_user is null then
    raise exception 'accept_invite needs app.user_id' using errcode = 'NSI01';
  end if;

  select i.* into v_invite from northstar.invites i where i.token_hash = p_token_hash for update;
  if not found then
    return query select 'not_found'::text, null::uuid, null::uuid, null::text;
    return;
  end if;
  if lower(v_invite.email) <> lower(coalesce(p_email, '')) then
    return query select 'email_mismatch'::text, null::uuid, null::uuid, null::text;
    return;
  end if;
  if v_invite.accepted_at is not null then
    return query select 'used'::text, null::uuid, null::uuid, null::text;
    return;
  end if;
  if v_invite.revoked_at is not null then
    return query select 'revoked'::text, null::uuid, null::uuid, null::text;
    return;
  end if;
  if v_invite.expires_at <= now() then
    return query select 'expired'::text, null::uuid, null::uuid, null::text;
    return;
  end if;
  if exists (select from northstar.memberships m where m.user_id = v_user and m.status = 'active') then
    return query select 'active_elsewhere'::text, null::uuid, null::uuid, null::text;
    return;
  end if;

  insert into northstar.user_profiles (id) values (v_user) on conflict (id) do nothing;

  select m.id, m.status into v_membership, v_previous_status
  from northstar.memberships m
  where m.tenant_id = v_invite.tenant_id and m.user_id = v_user
  for update;

  begin
    if v_membership is null then
      insert into northstar.memberships (tenant_id, user_id, role, status)
      values (v_invite.tenant_id, v_user, v_invite.role, 'active')
      returning id into v_membership;
    else
      update northstar.memberships set role = v_invite.role, status = 'active' where id = v_membership;
    end if;
  exception when unique_violation then
    -- Two businesses accepted for this user at the same moment: the D4 index decides, and this one lost.
    get stacked diagnostics v_constraint = constraint_name;
    if v_constraint = 'memberships_one_active_per_user' then
      return query select 'active_elsewhere'::text, null::uuid, null::uuid, null::text;
      return;
    end if;
    raise;
  end;

  update northstar.invites set accepted_at = now() where id = v_invite.id;

  insert into northstar.audit_log (tenant_id, actor_user_id, action, target_table, target_id, metadata)
  values (v_invite.tenant_id, v_user, 'invite.accepted', 'invites', v_invite.id,
          case when v_previous_status is not null
               then jsonb_build_object('from', v_previous_status, 'to', 'active') end);

  return query select 'accepted'::text, v_invite.tenant_id, v_membership, v_invite.role;
end
$$;

revoke all on function northstar.accept_invite(text, text) from public, anon, authenticated;
grant execute on function northstar.accept_invite(text, text) to app_user;
