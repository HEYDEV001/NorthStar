-- Step 2.7: the webhook sweep's tenant scan (step 2.4a, data-model.md section 12) includes WhatsApp status rows, now
-- that they are processed. Same signature, owner, search_path and grants; only the rows it counts change. Status rows
-- stored since step 2.2 (processed_at and error null) are then found by the first sweep after this deploys and queued
-- under the last retry step, as rows with no failure recorded are (integration-specs.md section 1).
create or replace function northstar.tenants_with_unprocessed_webhooks(p_older_than interval)
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
    and (w.provider_event_id like 'message:%' or w.provider_event_id like 'status:%')
$$;

revoke all on function northstar.tenants_with_unprocessed_webhooks(interval) from public, anon, authenticated;
grant execute on function northstar.tenants_with_unprocessed_webhooks(interval) to app_user;
