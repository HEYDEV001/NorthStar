-- Step 2.8b: template status as Meta reports it (data-model.md section 4, integration-specs.md section 2, Template
-- status from Meta). Three statuses join the five of step 2.3: 'disabled' (Meta's DISABLED, after a third pause),
-- 'deleted' (DELETED and PENDING_DELETION, or gone from a complete listing) and 'archived' (ARCHIVED, a year without
-- sends). Templates are still never deleted as rows: a deleted one keeps its row, and the messages that used it.
alter table northstar.message_templates drop constraint message_templates_status_check;
alter table northstar.message_templates add constraint message_templates_status_check
  check (status in ('draft', 'submitted', 'approved', 'rejected', 'paused', 'disabled', 'deleted', 'archived'));

-- Meta retries webhooks for days and does not keep them in order, so every write from Meta carries Meta's time for it
-- (a webhook's entry.time, or when a sync's listing started) and one older than this changes nothing. Null: never
-- written from Meta.
alter table northstar.message_templates add column meta_updated_at timestamptz;

-- variables, from Meta's body (step 2.8b): {"format": "positional" | "named", "names": [...]}. Null: none known.
-- Each part is coalesced to false, since a check that evaluates to null (an array, a missing key) would pass.
alter table northstar.message_templates add constraint message_templates_variables_shape
  check (variables is null
         or (jsonb_typeof(variables) = 'object'
             and coalesce(variables ->> 'format' in ('positional', 'named'), false)
             and coalesce(jsonb_typeof(variables -> 'names') = 'array', false)));

grant insert (meta_updated_at) on northstar.message_templates to app_user;
grant update (meta_updated_at) on northstar.message_templates to app_user;
