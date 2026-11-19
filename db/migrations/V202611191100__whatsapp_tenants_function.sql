-- Step 2.2: routing an inbound WhatsApp webhook to its tenant (integration-specs.md sections 1 and 2, data-model.md
-- section 12). The webhook arrives before any tenant is known, and RLS shows app_user no WhatsApp account outside a
-- tenant scope, so this SECURITY DEFINER function, owned by migrator (BYPASSRLS, as RLS is forced on
-- whatsapp_accounts), maps phone number IDs to tenant IDs and returns nothing else: no account, WABA or display data.
--
-- One delivery can carry changes for several numbers, so it takes an array; only its first 100 elements are looked at
-- (the caller sends at most 100 at a time). Unknown IDs are simply absent from the result. search_path is pinned and
-- every name is fully qualified; only app_user may call it, and the RLS release gate allowlists it. The app calls it in
-- the tenant guard's WhatsApp routing mode, which sets no context and allows only this one statement.
create function northstar.whatsapp_tenants(p_phone_number_ids text[])
returns table (phone_number_id text, tenant_id uuid)
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select w.phone_number_id, w.tenant_id
  from northstar.whatsapp_accounts w
  where w.phone_number_id = any (p_phone_number_ids[1:100])
$$;

revoke all on function northstar.whatsapp_tenants(text[]) from public, anon, authenticated;
grant execute on function northstar.whatsapp_tenants(text[]) to app_user;
