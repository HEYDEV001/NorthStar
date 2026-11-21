-- Step 2.5: the database refuses an action that would message a contact who has opted out (W32; workflow-spec.md
-- section 13 Opt-out). The worker and the action service check consent first and cancel such an action instead
-- (contact_opted_out); this trigger is the backstop for any path that skips them, so no later step can send to an
-- opted-out contact without changing this migration's rule.
--
-- Refused, with SQLSTATE NSA01, for every role:
--   * inserting an action with a recipient in any status but cancelled;
--   * any update that leaves an action with a recipient processing, which is how every attempt is claimed (a first
--     attempt, a retry, or the takeover of a lost one).
-- Moving back to pending (a retry, an approval, a resume) is not checked here: nothing is sent until the next claim.
--
-- The recipient must be visible and not opted out. The function runs with the caller's rights, so a staff member's
-- action for a contact they cannot see is refused too (it fails closed). The contact is read with a shared row lock:
-- the inbound job holds the contact's update lock while it records an opt-out and cancels the contact's actions, so
-- an action created or claimed meanwhile waits for that commit and then sees the opt-out.
create function northstar.actions_refuse_opted_out() returns trigger
language plpgsql
as $$
begin
  if new.recipient_contact_id is null then
    return new;
  end if;
  if tg_op = 'INSERT' and new.status = 'cancelled' then
    return new;
  end if;
  if tg_op = 'UPDATE' and new.status <> 'processing' then
    return new;
  end if;
  perform from northstar.contacts c
  where c.tenant_id = new.tenant_id
    and c.id = new.recipient_contact_id
    and c.consent_state <> 'opted_out'
  for share;
  if not found then
    raise exception 'The action''s recipient has opted out or is not visible' using errcode = 'NSA01';
  end if;
  return new;
end
$$;

create trigger actions_refuse_opted_out before insert or update on northstar.actions
  for each row execute function northstar.actions_refuse_opted_out();
