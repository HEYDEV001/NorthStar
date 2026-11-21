-- Step 2.8a: the scheduled WhatsApp sync (integration-specs.md section 2, Quality and limits; data-model.md section 12)
-- starts without a tenant, like the job sweeper and the webhook sweep. This SECURITY DEFINER function, owned by migrator
-- (BYPASSRLS, as RLS is forced on both tables), returns the IDs of tenants whose WhatsApp integration is connected with
-- a credential and who have at least one WhatsApp number, and nothing else. Each tenant's sync then runs inside that
-- tenant's own scope.
--
-- search_path is pinned and every name is fully qualified; only app_user may call it, in the tenant guard's WhatsApp
-- tenant scan mode, which sets no context and allows only this statement. The RLS release gate allowlists it.
create function northstar.tenants_with_whatsapp()
returns table (tenant_id uuid)
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select i.tenant_id
  from northstar.integrations i
  where i.provider = 'whatsapp'
    and i.status = 'connected'
    and i.credential_id is not null
    and exists (select from northstar.whatsapp_accounts w where w.tenant_id = i.tenant_id)
  order by i.tenant_id
$$;

revoke all on function northstar.tenants_with_whatsapp() from public, anon, authenticated;
grant execute on function northstar.tenants_with_whatsapp() to app_user;
