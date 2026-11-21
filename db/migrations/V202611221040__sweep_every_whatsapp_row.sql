-- Step 2.8a: the webhook sweep's tenant scan (step 2.4a, data-model.md section 12) counts every WhatsApp row, now that
-- every kind stored is processed: messages, statuses (2.7) and the account-level changes routed by WABA ID (2.8a). A
-- row of a kind the job does not know is closed with a fixed code instead of waiting, so a kind stored before its
-- processing exists can never be retried forever. Same signature, owner, search_path and grants; only the rows it
-- counts change.
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
$$;

revoke all on function northstar.tenants_with_unprocessed_webhooks(interval) from public, anon, authenticated;
grant execute on function northstar.tenants_with_unprocessed_webhooks(interval) to app_user;
