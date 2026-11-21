-- Step 2.5: who an action messages, and why one was cancelled (W32; data-model.md section 6, workflow-spec.md section
-- 13 Opt-out).
--
-- recipient_contact_id is set exactly on actions that send something to the contact (a WhatsApp message to the client,
-- for example), and never on actions about a lead that message someone else (a staff reminder) or nobody (a calendar
-- write). An opt-out cancels the contact's actions by this column, not by lead, so a lead's staff-facing actions are
-- left alone. It is written once, with the action, and its composite foreign key keeps it the action's own tenant's
-- contact.
--
-- cancel_reason is a fixed code (never message text or personal data), set exactly when the action is cancelled:
-- contact_opted_out for an opt-out (step 2.5). No action is cancelled before this step, so the check holds for every
-- existing row.
alter table northstar.actions
  add column recipient_contact_id uuid,
  add column cancel_reason        text check (cancel_reason ~ '^[a-z0-9_]{1,64}$');

alter table northstar.actions
  add constraint actions_recipient_fk
    foreign key (tenant_id, recipient_contact_id) references northstar.contacts (tenant_id, id),
  add constraint actions_cancel_reason_complete check ((status = 'cancelled') = (cancel_reason is not null));

-- The opt-out's cancellation (data-model.md section 13): a contact's actions that can still run.
create index actions_open_by_recipient on northstar.actions (tenant_id, recipient_contact_id)
  where status in ('pending', 'needs_review', 'paused');

-- The recipient is written once; the reason is a lifecycle column. An action created for a contact who has already
-- opted out is inserted as cancelled, with its reason.
grant insert (recipient_contact_id, cancel_reason) on northstar.actions to app_user;
grant update (cancel_reason) on northstar.actions to app_user;
