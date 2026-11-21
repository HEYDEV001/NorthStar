-- Step 2.7: delivery statuses only move forward (data-model.md section 4, integration-specs.md section 2, How step 2.7
-- applies statuses). Meta's status webhooks arrive in any order and more than once; the status processor applies a
-- status with a guarded update, and these triggers are the backstop, for every role, the table's owner included (as
-- messages_set_once is).
--
-- Order: queued < sent < delivered < read, any jump forward. failed replaces queued or sent only, never delivered or
-- read, and nothing leaves failed. The same status again changes nothing and is allowed. A null or a value the status
-- check does not allow is left to the checks (messages_status_outbound_only, messages_status_check), so their errors
-- still name the rule that failed. SQLSTATE NSM02.
--
-- Timestamps: delivered_at and read_at are Meta's, set once, so a redelivered or late status never moves them
-- (messages_set_once, SQLSTATE NSM01, which already keeps provider_message_id and media_path set once).

create or replace function northstar.messages_set_once() returns trigger
language plpgsql
as $$
begin
  if old.provider_message_id is not null and new.provider_message_id is distinct from old.provider_message_id then
    raise exception 'A message''s provider ID is set once' using errcode = 'NSM01';
  end if;
  if old.media_path is not null and new.media_path is distinct from old.media_path then
    raise exception 'A message''s media path is set once' using errcode = 'NSM01';
  end if;
  if old.delivered_at is not null and new.delivered_at is distinct from old.delivered_at then
    raise exception 'A message''s delivery time is set once' using errcode = 'NSM01';
  end if;
  if old.read_at is not null and new.read_at is distinct from old.read_at then
    raise exception 'A message''s read time is set once' using errcode = 'NSM01';
  end if;
  return new;
end
$$;

create function northstar.messages_status_forward() returns trigger
language plpgsql
as $$
declare
  forward constant text[] := array['queued', 'sent', 'delivered', 'read'];
begin
  if new.status is not distinct from old.status then
    return new;
  end if;
  if old.status is null or new.status is null
     or not (old.status = any (forward) or old.status = 'failed')
     or not (new.status = any (forward) or new.status = 'failed') then
    return new; -- left to the checks
  end if;
  if old.status = 'failed' then
    raise exception 'A failed message''s status never changes' using errcode = 'NSM02';
  end if;
  if new.status = 'failed' then
    if old.status in ('queued', 'sent') then
      return new;
    end if;
    raise exception 'Only a queued or sent message can fail' using errcode = 'NSM02';
  end if;
  if array_position(forward, new.status) > array_position(forward, old.status) then
    return new;
  end if;
  raise exception 'A message''s status only moves forward' using errcode = 'NSM02';
end
$$;

create trigger messages_status_forward before update of status on northstar.messages
  for each row execute function northstar.messages_status_forward();
