-- Step 2.9b: the time of the client's latest message in a conversation that counts as the client writing (W32;
-- data-model.md section 4, workflow-spec.md section 13 Opt-out): the WhatsApp timestamp of the latest inbound message
-- that is not a reaction or a system message, the ones that never open a lead either (step 2.4b). An opt-out message
-- counts too; it changes nothing, as the exception needs a message newer than the latest opt-out.
--
-- The staff reply exception (step 2.9b) opens for 24 hours from this time, and only while it is later than the
-- contact's last_opt_out_at. last_inbound_at, which every inbound message moves, still drives WhatsApp's window.
--
-- No backfill: messages store reactions and system messages as 'unsupported' like other types, so the stored rows
-- cannot tell which counted. The exception opens with the client's next message.
alter table northstar.conversations add column last_contact_message_at timestamptz;

grant update (last_contact_message_at) on northstar.conversations to app_user;

-- Both inbound times decide whether a message may go out (the window and the exception), so only the inbound job (the
-- system actor) writes them, and only forward, refused with SQLSTATE NSV01. Since step 2.3 any app_user role that may
-- update a conversation could have written last_inbound_at. An operator (postgres or migrator) may still set either,
-- as fixtures and repairs do. Inserting a conversation with either time set is the system's too.
create function northstar.conversations_inbound_times_guard() returns trigger
language plpgsql
as $$
declare
  by_operator boolean := current_user in ('postgres', 'migrator');
  by_system boolean := current_user = 'app_user' and coalesce(current_setting('app.role', true), '') = 'system';
begin
  if by_operator then
    return new;
  end if;
  if tg_op = 'INSERT' then
    if not by_system and (new.last_inbound_at is not null or new.last_contact_message_at is not null) then
      raise exception 'Only the system records when the client wrote' using errcode = 'NSV01';
    end if;
    return new;
  end if;
  if new.last_inbound_at is not distinct from old.last_inbound_at
     and new.last_contact_message_at is not distinct from old.last_contact_message_at then
    return new;
  end if;
  if not by_system then
    raise exception 'Only the system records when the client wrote' using errcode = 'NSV01';
  end if;
  if (old.last_inbound_at is not null
      and (new.last_inbound_at is null or new.last_inbound_at < old.last_inbound_at))
     or (old.last_contact_message_at is not null
         and (new.last_contact_message_at is null or new.last_contact_message_at < old.last_contact_message_at)) then
    raise exception 'When the client wrote only moves forward' using errcode = 'NSV01';
  end if;
  return new;
end
$$;

create trigger conversations_inbound_times_guard before insert or update on northstar.conversations
  for each row execute function northstar.conversations_inbound_times_guard();
