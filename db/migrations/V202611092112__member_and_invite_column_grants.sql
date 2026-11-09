-- Step 1.8: app_user may update only the columns the member and invite endpoints change (data-model.md section 3).
-- Everything else in these rows is fixed once written. There is still no delete on either table. The updated_at
-- trigger keeps working: column grants apply only to the columns named in an UPDATE.

revoke update on northstar.memberships from app_user;
grant update (role, status, whatsapp_phone) on northstar.memberships to app_user;

revoke update on northstar.invites from app_user;
grant update (revoked_at) on northstar.invites to app_user;
