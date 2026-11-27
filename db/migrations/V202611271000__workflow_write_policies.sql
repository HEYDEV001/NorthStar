-- Step 3.1: only admins and the system actor write workflows (data-model.md sections 6 and 12, workflow-spec.md
-- section 16; build-document.md section 3: staff never edit workflows). The workflow service checks the role first
-- (403 admin_only); these restrictive policies make the database refuse the same writes from any other path. Reads are
-- not limited by role: a workflow holds no personal data.

create policy workflows_write_insert on northstar.workflows
  as restrictive for insert to app_user
  with check (current_setting('app.role', true) in ('admin', 'system'));
create policy workflows_write_update on northstar.workflows
  as restrictive for update to app_user
  using (current_setting('app.role', true) in ('admin', 'system'))
  with check (current_setting('app.role', true) in ('admin', 'system'));
create policy workflows_write_delete on northstar.workflows
  as restrictive for delete to app_user
  using (current_setting('app.role', true) in ('admin', 'system'));

-- A workflow is never deleted (a published one is also held by its versions' foreign key), and app_user changes only
-- its name, its draft and its current version; tenant, template and creation time are written once. updated_at is set
-- by the workflows_set_updated_at trigger, which needs no grant.
revoke update, delete on northstar.workflows from app_user;
grant update (name, draft_config, current_version_id) on northstar.workflows to app_user;

-- A version is published by an admin, recorded as their own active membership, or by the system actor, recorded as
-- nobody (a template at onboarding, step 3.2). The membership subquery is the one the staff policies use.
create policy workflow_versions_publish_insert on northstar.workflow_versions
  as restrictive for insert to app_user
  with check (
    (current_setting('app.role', true) = 'system' and published_by is null)
    or (current_setting('app.role', true) = 'admin'
        and published_by = (
          select m.id from northstar.memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')));
