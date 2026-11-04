-- Only admins may write memberships and invites (data-model.md section 3). Staff keep read access through
-- tenant_isolation. Restrictive policies are ANDed with tenant_isolation, so they narrow it and never widen it.
-- One policy per command: a restrictive policy for all commands would also remove staff's read access.
-- Invite acceptance (step 1.9) runs in an allowlisted SECURITY DEFINER function, so it is not affected.

create policy memberships_admin_insert on northstar.memberships as restrictive for insert to app_user
  with check (current_setting('app.role', true) = 'admin');
create policy memberships_admin_update on northstar.memberships as restrictive for update to app_user
  using (current_setting('app.role', true) = 'admin')
  with check (current_setting('app.role', true) = 'admin');
-- app_user has no delete privilege today; this keeps deletes admin-only if one is ever granted.
create policy memberships_admin_delete on northstar.memberships as restrictive for delete to app_user
  using (current_setting('app.role', true) = 'admin');

create policy invites_admin_insert on northstar.invites as restrictive for insert to app_user
  with check (current_setting('app.role', true) = 'admin');
create policy invites_admin_update on northstar.invites as restrictive for update to app_user
  using (current_setting('app.role', true) = 'admin')
  with check (current_setting('app.role', true) = 'admin');
create policy invites_admin_delete on northstar.invites as restrictive for delete to app_user
  using (current_setting('app.role', true) = 'admin');
