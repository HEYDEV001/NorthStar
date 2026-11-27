-- Step 3.1: a lead keeps the workflow version it started on (W36; data-model.md section 6, workflow-spec.md section 16).
-- Publishing never touches leads; this makes the database refuse moving one to another version by any other path, for
-- every role, the owner (migrator) included. Step 3.11 replaces it with a rule that allows an admin's explicit
-- migration, which is the only way a lead may change version.
create function northstar.refuse_lead_workflow_version_change() returns trigger
language plpgsql
as $$
begin
  raise exception 'A lead keeps the workflow version it started on'
    using errcode = 'NSL01';
end
$$;

create trigger leads_workflow_version_set_once before update of workflow_version_id on northstar.leads
  for each row when (new.workflow_version_id is distinct from old.workflow_version_id)
  execute function northstar.refuse_lead_workflow_version_change();
