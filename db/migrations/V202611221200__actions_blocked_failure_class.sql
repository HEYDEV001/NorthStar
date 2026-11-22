-- Step 2.8c: a fourth failure class, 'blocked' (W34; workflow-spec.md section 10, data-model.md section 6). An action
-- is blocked when something it depends on is unavailable for now, such as a WhatsApp template Meta has paused: it
-- pauses without using an attempt and without the auth alert, and resumes when that dependency is back (the template
-- approved again), keeping the time it was scheduled for. Reconnecting an integration resumes auth pauses only, and
-- unblocking resumes blocked pauses only, so neither resumes the other's actions.
--
-- The check was declared on the column in step 1.10, so it carries Postgres's generated name.
alter table northstar.actions drop constraint actions_last_error_class_check;
alter table northstar.actions add constraint actions_last_error_class_check
  check (last_error_class in ('retryable', 'permanent', 'auth', 'blocked'));
