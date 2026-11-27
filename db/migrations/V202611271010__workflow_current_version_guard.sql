-- Step 3.1: a workflow's current version only moves forward (data-model.md section 6, workflow-spec.md section 16).
-- Publishing inserts version n+1 and points the workflow at it; nothing else may move the pointer. Once set, it never
-- becomes null again (a business would stop opening leads), and it changes only to the workflow's newest version, so an
-- older version comes back only by being re-published as the newest (rollback, step 3.11). The composite foreign key
-- already keeps it to the workflow's own versions.
--
-- For every role, the owner (migrator) included, like the version triggers (NSW01). Invoker's rights: the newest
-- version is read through RLS in the caller's tenant, which is the workflow's own (tenant_isolation on workflows).
create function northstar.guard_workflow_current_version() returns trigger
language plpgsql
as $$
begin
  if new.current_version_id is not distinct from old.current_version_id then
    return new;
  end if;
  if new.current_version_id is null then
    raise exception 'A published workflow keeps a current version'
      using errcode = 'NSW02';
  end if;
  if new.current_version_id is distinct from (
       select v.id from northstar.workflow_versions v
       where v.tenant_id = new.tenant_id and v.workflow_id = new.id
       order by v.version desc
       limit 1) then
    raise exception 'A workflow''s current version can only move to its newest version'
      using errcode = 'NSW02';
  end if;
  return new;
end
$$;

create trigger workflows_current_version_guard before update of current_version_id on northstar.workflows
  for each row execute function northstar.guard_workflow_current_version();
