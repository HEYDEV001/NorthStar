-- Step 3.1: the shape of a workflow's documents (data-model.md section 6, workflow-spec.md section 15). The workflow
-- service validates the format itself; these checks are the database's backstop for what every reader relies on.
--
-- A draft is a JSON object in any state (drafts may be incomplete). The service refuses more than 256 KB of submitted
-- text; the stored text can be longer (jsonb adds a space after every ':' and ','), so the backstop is 1 MiB.
alter table northstar.workflows
  add constraint workflows_draft_config_shape
  check (draft_config is null
         or (jsonb_typeof(draft_config) = 'object' and octet_length(draft_config::text) <= 1048576));

-- A published version states its format (schema_version, an integer) and carries the workflow ID and version number
-- that publishing writes into it, equal to its own columns. NOT VALID: versions published before step 3.1 exist only in
-- local databases (the Phase 2 gate's seed) and are immutable, so they are not checked; every new version is.
alter table northstar.workflow_versions
  add constraint workflow_versions_config_shape
  check (coalesce(
           jsonb_typeof(config) = 'object'
           and jsonb_typeof(config -> 'schema_version') = 'number'
           and config ->> 'schema_version' ~ '^[1-9][0-9]{0,8}$'
           and config -> 'version' = to_jsonb(version)
           and config ->> 'workflow_id' = workflow_id::text
           and octet_length(config::text) <= 1048576,
           false))
  not valid;
