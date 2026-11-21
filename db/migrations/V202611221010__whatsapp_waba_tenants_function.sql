-- Step 2.8a: routing a WhatsApp webhook change that names only its WABA ID (entry.id), such as a messaging-limit
-- update, to its tenant (integration-specs.md section 2, data-model.md section 12). It arrives before any tenant is
-- known, and RLS shows app_user no WhatsApp account outside a tenant scope, so this SECURITY DEFINER function, owned by
-- migrator (BYPASSRLS, as RLS is forced on whatsapp_accounts), maps WABA IDs to tenant IDs and returns nothing else: no
-- account, number or display data. One tenant per WABA (whatsapp_accounts_one_tenant_per_waba), so each known ID
-- appears once, however many of the tenant's numbers share it.
--
-- As northstar.whatsapp_tenants(): only the first 100 elements are looked at, unknown IDs are absent, search_path is
-- pinned, every name is fully qualified, and only app_user may call it, in the tenant guard's WABA routing mode, which
-- sets no context and allows only this statement. The RLS release gate allowlists it.
create function northstar.whatsapp_waba_tenants(p_waba_ids text[])
returns table (waba_id text, tenant_id uuid)
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select distinct w.waba_id, w.tenant_id
  from northstar.whatsapp_accounts w
  where w.waba_id = any (p_waba_ids[1:100])
$$;

revoke all on function northstar.whatsapp_waba_tenants(text[]) from public, anon, authenticated;
grant execute on function northstar.whatsapp_waba_tenants(text[]) to app_user;
