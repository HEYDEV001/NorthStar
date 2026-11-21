-- Step 2.5: a contact's consent can only move to opted out, and only the system moves it (W32; data-model.md section 4,
-- workflow-spec.md section 13 Opt-out). Until a later step defines opting back in, no step can clear an opt-out.
--
-- An opted-out contact has the time it opted out (the WhatsApp timestamp of its first opt-out message, step 2.5). No
-- contact is opted out before this step, so the check holds for every existing row.
alter table northstar.contacts
  add constraint contacts_opted_out_has_time check (consent_state <> 'opted_out' or opt_out_at is not null);

-- Refused, with SQLSTATE NSC01:
--   * for every role, the table's owner included: changing opt_out_at once it is set, and moving consent_state away
--     from opted_out (the opt-in step replaces this function);
--   * for every role but the system actor (app_user with app.role 'system') and an operator (postgres or migrator):
--     inserting a contact with any consent but the default (unknown, no times), and changing consent_state,
--     opted_in_at or opt_out_at. Staff and admins can still edit a contact's other columns where its policies let
--     them. Any other role is refused, so the rule fails closed.
create function northstar.contacts_consent_guard() returns trigger
language plpgsql
as $$
declare
  by_system boolean := current_user in ('postgres', 'migrator')
                       or (current_user = 'app_user'
                           and coalesce(current_setting('app.role', true), '') = 'system');
begin
  if tg_op = 'INSERT' then
    if not by_system
       and (new.consent_state <> 'unknown' or new.opted_in_at is not null or new.opt_out_at is not null) then
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
  if not by_system
     and (new.consent_state is distinct from old.consent_state
          or new.opted_in_at is distinct from old.opted_in_at
          or new.opt_out_at is distinct from old.opt_out_at) then
    raise exception 'Only the system changes a contact''s consent' using errcode = 'NSC01';
  end if;
  return new;
end
$$;

create trigger contacts_consent_guard before insert or update on northstar.contacts
  for each row execute function northstar.contacts_consent_guard();
