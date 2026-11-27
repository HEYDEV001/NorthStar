-- Step 3.1b: the workflow editor's three writes take an Idempotency-Key (P21; data-model.md section 3, workflow-spec.md
-- section 16): creating the workflow, saving its draft and publishing it. Their keys go in request_keys like manual
-- lead entry's, each under its own operation code, so a key used for one never answers for another.
alter table northstar.request_keys drop constraint request_keys_operation_check;
alter table northstar.request_keys
  add constraint request_keys_operation_check
  check (operation in ('lead.create', 'workflow.create', 'workflow.draft_save', 'workflow.publish'));
