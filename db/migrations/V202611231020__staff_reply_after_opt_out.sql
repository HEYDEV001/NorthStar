-- Step 2.9b: the one exception to the opt-out refusal (W32; workflow-spec.md sections 12 and 13 Opt-out). A contact who
-- opted out and then writes again may get a staff member's free-text reply, and nothing else, inside the 24-hour window
-- that message opens: until 24 hours after the client's latest message (conversations.last_contact_message_at), and
-- only while that message is newer than their latest opt-out (contacts.last_opt_out_at), so another STOP closes it
-- again. A message in the same second as the latest opt-out does not open it. Templates, AI and system sends to the
-- contact stay refused, and consent stays opted_out.
--
-- Two functions hold the rule, each with the caller's rights (so RLS applies: a staff member's call sees only their own
-- conversations) and a pinned search_path. The app calls the same functions with the sender's 2-minute margin, so it
-- cancels a reply the database would refuse before it gets there.

-- When the exception ends for this conversation, or null when it is not open: the contact has not opted out, or has
-- not written since their latest opt-out (a reaction or system message does not count).
create function northstar.opt_out_reply_until(p_tenant uuid, p_conversation uuid) returns timestamptz
language sql
stable
set search_path = ''
as $$
  select cv.last_contact_message_at + interval '24 hours'
  from northstar.conversations cv
  join northstar.contacts ct on ct.tenant_id = cv.tenant_id and ct.id = cv.contact_id
  where cv.tenant_id = p_tenant
    and cv.id = p_conversation
    and ct.consent_state = 'opted_out'
    and cv.last_contact_message_at > ct.last_opt_out_at
$$;

-- Whether this message may go to this opted-out contact: it is a staff member's outbound text in the contact's own
-- conversation, and the exception is open for more than p_margin from now.
create function northstar.staff_reply_allowed_after_opt_out(
  p_tenant uuid, p_contact uuid, p_message uuid, p_margin interval) returns boolean
language sql
stable
set search_path = ''
as $$
  select coalesce((
    select northstar.opt_out_reply_until(m.tenant_id, m.conversation_id) > now() + p_margin
    from northstar.messages m
    join northstar.conversations cv on cv.tenant_id = m.tenant_id and cv.id = m.conversation_id
    where m.tenant_id = p_tenant
      and m.id = p_message
      and cv.contact_id = p_contact
      and m.direction = 'out'
      and m.sender_type = 'staff'
      and m.type = 'text'), false)
$$;

revoke execute on function northstar.opt_out_reply_until(uuid, uuid) from public;
revoke execute on function northstar.staff_reply_allowed_after_opt_out(uuid, uuid, uuid, interval) from public;
grant execute on function northstar.opt_out_reply_until(uuid, uuid) to app_user;
grant execute on function northstar.staff_reply_allowed_after_opt_out(uuid, uuid, uuid, interval) to app_user;

-- actions_refuse_opted_out (step 2.5, SQLSTATE NSA01, every role) keeps refusing a runnable action for an opted-out or
-- invisible recipient, inserted in any status but cancelled or claimed into processing, with one exception: a
-- whatsapp.send whose message (its payload's messageId) the rule above allows, with no margin. The contact is read with
-- a shared row lock whatever its consent, so an opt-out being recorded meanwhile (which holds the contact's update
-- lock) is waited for and then seen, a second STOP included.
create or replace function northstar.actions_refuse_opted_out() returns trigger
language plpgsql
as $$
declare
  state text;
  message_id text;
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
  select c.consent_state into state
  from northstar.contacts c
  where c.tenant_id = new.tenant_id and c.id = new.recipient_contact_id
  for share;
  if not found then
    raise exception 'The action''s recipient has opted out or is not visible' using errcode = 'NSA01';
  end if;
  if state <> 'opted_out' then
    return new;
  end if;
  message_id := new.payload ->> 'messageId';
  if new.type = 'whatsapp.send'
     and message_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
     and northstar.staff_reply_allowed_after_opt_out(
           new.tenant_id, new.recipient_contact_id, message_id::uuid, interval '0') then
    return new;
  end if;
  raise exception 'The action''s recipient has opted out or is not visible' using errcode = 'NSA01';
end
$$;
