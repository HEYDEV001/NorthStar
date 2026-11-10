-- Step 1.10: workers run as the system actor, with app.user_id set to the nil UUID (TenantContext.SYSTEM_USER_ID). No
-- real user may ever have it, so the system actor can never own a profile or hold a membership.
alter table northstar.user_profiles
  add constraint user_profiles_id_not_nil check (id <> '00000000-0000-0000-0000-000000000000');
alter table northstar.memberships
  add constraint memberships_user_id_not_nil check (user_id <> '00000000-0000-0000-0000-000000000000');
