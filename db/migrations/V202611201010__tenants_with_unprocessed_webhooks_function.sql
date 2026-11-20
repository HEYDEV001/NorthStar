-- Step 2.4a: which tenants have webhook events still waiting to be processed (integration-specs.md section 1,
-- data-model.md section 12). The webhook sweep starts without a tenant, and RLS shows app_user no webhook event outside
-- a tenant scope, so this SECURITY DEFINER function, owned by migrator (BYPASSRLS, as RLS is forced on
-- webhook_events), returns tenant IDs and nothing else, as northstar.tenants_with_due_actions() does for actions. The
-- sweep then reads each tenant's rows inside that tenant's scope.
--
-- Waiting means processed_at is still null and the row was received more than p_older_than ago (the grace period, so
-- the job queued with the row has its turn first). The age is measured on the database's clock, the same clock that
-- stamped received_at. Only WhatsApp message rows for now: status rows wait for step 2.7, which replaces this function
-- to include them.
--
-- search_path is pinned and every name is fully qualified; only app_user may call it, and the RLS release gate
-- allowlists it. The app calls it in the tenant guard's unprocessed-webhook scan mode, which sets no context and allows
-- only this one statement.
create function northstar.tenants_with_unprocessed_webhooks(p_older_than interval)
returns table (tenant_id uuid)
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select distinct w.tenant_id
  from northstar.webhook_events w
  where w.processed_at is null
    and w.received_at < now() - p_older_than
    and w.provider = 'whatsapp'
    and w.provider_event_id like 'message:%'
$$;

revoke all on function northstar.tenants_with_unprocessed_webhooks(interval) from public, anon, authenticated;
grant execute on function northstar.tenants_with_unprocessed_webhooks(interval) to app_user;

-- The sweep's reads (data-model.md section 13): only unprocessed rows, so the index stays small however many rows are
-- processed. Led by tenant_id, as every index is; the function above scans it across tenants.
create index webhook_events_unprocessed on northstar.webhook_events (tenant_id, received_at)
  where processed_at is null;
