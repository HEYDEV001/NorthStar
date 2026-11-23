-- Step 2.9b: the time of a contact's latest opt-out message (W32; data-model.md section 4, workflow-spec.md section 13
-- Opt-out). opt_out_at keeps the first opt-out and never moves (step 2.5), so it cannot tell that a client who wrote
-- again after opting out has since sent STOP again. The staff reply exception (step 2.9b) opens only for a message
-- newer than this time.
--
-- Set exactly while the contact is opted out (no step clears an opt-out yet), never before opt_out_at, and only moved
-- forward: by the inbound job (the system actor), with the WhatsApp timestamp of each opt-out message.
alter table northstar.contacts add column last_opt_out_at timestamptz;

-- Every opted-out contact so far has opted out once, as far as anything recorded says. The updated_at trigger is off
-- meanwhile: the backfill is not a change anyone made to the contact.
alter table northstar.contacts disable trigger contacts_set_updated_at;
update northstar.contacts set last_opt_out_at = opt_out_at where consent_state = 'opted_out';
alter table northstar.contacts enable trigger contacts_set_updated_at;

alter table northstar.contacts
  add constraint contacts_last_opt_out_complete
    check ((consent_state = 'opted_out') = (last_opt_out_at is not null)),
  add constraint contacts_last_opt_out_after_first check (last_opt_out_at >= opt_out_at);

-- contacts_consent_guard (step 2.5, SQLSTATE NSC01) now covers last_opt_out_at as well: for every role it only moves
-- forward, and only the system actor (or an operator as postgres or migrator) sets or moves it. Everything else is as
-- step 2.5 wrote it.
create or replace function northstar.contacts_consent_guard() returns trigger
language plpgsql
as $$
declare
  by_system boolean := current_user in ('postgres', 'migrator')
                       or (current_user = 'app_user'
                           and coalesce(current_setting('app.role', true), '') = 'system');
begin
  if tg_op = 'INSERT' then
    if not by_system
       and (new.consent_state <> 'unknown' or new.opted_in_at is not null or new.opt_out_at is not null
            or new.last_opt_out_at is not null) then
      raise exception 'Only the system records a contact''s consent' using errcode = 'NSC01';
    end if;
    return new;
  end if;
  if old.opt_out_at is not null and new.opt_out_at is distinct from old.opt_out_at then
    raise exception 'A contact''s opt-out time is set once' using errcode = 'NSC01';
  end if;
  if old.consent_state = 'opted_out' and new.consent_state <> 'opted_out' then
    raise exception 'An opted-out contact stays opted out' using errcode = 'NSC01';
  end if;
  if old.last_opt_out_at is not null
     and (new.last_opt_out_at is null or new.last_opt_out_at < old.last_opt_out_at) then
    raise exception 'A contact''s latest opt-out only moves forward' using errcode = 'NSC01';
  end if;
  if not by_system
     and (new.consent_state is distinct from old.consent_state
          or new.opted_in_at is distinct from old.opted_in_at
          or new.opt_out_at is distinct from old.opt_out_at
          or new.last_opt_out_at is distinct from old.last_opt_out_at) then
    raise exception 'Only the system changes a contact''s consent' using errcode = 'NSC01';
  end if;
  return new;
end
$$;
