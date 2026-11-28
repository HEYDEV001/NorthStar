-- Step 3.3b: a lead's stage move takes an Idempotency-Key (P21; data-model.md section 3, workflow-spec.md section 3),
-- stored in request_keys like the other writes' under its own operation code, so a key used for one never answers for
-- another. target_id names the move's timeline_events row.
alter table northstar.request_keys drop constraint request_keys_operation_check;
alter table northstar.request_keys
  add constraint request_keys_operation_check
  check (operation in ('lead.create', 'workflow.create', 'workflow.draft_save', 'workflow.publish', 'lead.move_stage'));
