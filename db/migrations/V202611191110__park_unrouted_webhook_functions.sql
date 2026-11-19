-- Step 2.2: the only way app_user writes to ingest.unrouted_webhooks (data-model.md sections 9 and 12, P15, P16).
-- app_user has no privilege on the ingest schema at all; these two SECURITY DEFINER functions, owned by migrator, are
-- its only path in. Each is as narrow as its case:
--
--   park_invalid_signature  takes no payload, event ID or routing key at all: a bad signature proves nothing about the
--                           body, so its row keeps metadata only (provider, body size, time).
--   park_unknown_account    a verified delivery for a phone number or account no tenant has: keeps the routing key and
--                           the payload, which the function itself drops (payload_too_large) above 64 KB (65,536 bytes).
--                           A provider retry is stored once (the partial unique index on verified event IDs).
--
-- Anyone can POST to a webhook URL, so both are rate-limited here as well as in each API instance: across every
-- instance, at most 60 invalid-signature rows and 600 unknown-account rows per minute. That bounds how fast junk can
-- grow the table, whatever the number of instances. A per-reason transaction advisory lock makes the count exact; the
-- app's in-memory limit in front keeps the lock uncontended. Over the cap nothing is stored and the outcome says so.
--
-- Each returns one row: outcome ('stored', 'duplicate' or 'rate_limited') and the new row's ID when stored (null
-- otherwise), so the app can log the ID. search_path is pinned and names are fully qualified; only app_user may call
-- them, and the RLS release gate allowlists them. The app calls each in its own tenant-guard mode, which sets no context
-- and allows only that one statement.

-- The caps count a reason's rows in the last minute.
create index unrouted_webhooks_reason_received_at on ingest.unrouted_webhooks (reason, received_at);

create function northstar.park_invalid_signature(p_provider text, p_body_bytes int)
returns table (outcome text, unrouted_id uuid)
language plpgsql
volatile
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
  v_id uuid;
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtext('ingest.unrouted_webhooks'), pg_catalog.hashtext('invalid_signature'));
  if (select pg_catalog.count(*)
      from (select
            from ingest.unrouted_webhooks u
            where u.reason = 'invalid_signature' and u.received_at > pg_catalog.now() - interval '1 minute'
            limit 60) recent) >= 60 then
    return query select 'rate_limited'::text, null::uuid;
    return;
  end if;
  insert into ingest.unrouted_webhooks (provider, signature_valid, reason, body_bytes)
  values (p_provider, false, 'invalid_signature', p_body_bytes)
  returning id into v_id;
  return query select 'stored'::text, v_id;
end
$$;

create function northstar.park_unknown_account(
  p_provider text, p_provider_event_id text, p_routing_key text, p_body_bytes int, p_payload jsonb)
returns table (outcome text, unrouted_id uuid)
language plpgsql
volatile
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
  v_too_large boolean := p_body_bytes > 65536;
  v_id uuid;
begin
  if p_provider_event_id is null or p_routing_key is null or p_payload is null then
    raise exception 'park_unknown_account needs an event ID, a routing key and a payload' using errcode = '22004';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtext('ingest.unrouted_webhooks'), pg_catalog.hashtext('unknown_account'));
  -- A retry is a duplicate even while the cap is reached.
  if exists (select
             from ingest.unrouted_webhooks u
             where u.provider = p_provider and u.provider_event_id = p_provider_event_id and u.signature_valid) then
    return query select 'duplicate'::text, null::uuid;
    return;
  end if;
  if (select pg_catalog.count(*)
      from (select
            from ingest.unrouted_webhooks u
            where u.reason = 'unknown_account' and u.received_at > pg_catalog.now() - interval '1 minute'
            limit 600) recent) >= 600 then
    return query select 'rate_limited'::text, null::uuid;
    return;
  end if;
  insert into ingest.unrouted_webhooks
    (provider, provider_event_id, signature_valid, reason, routing_key, body_bytes, payload, payload_too_large)
  values (p_provider, p_provider_event_id, true, 'unknown_account', p_routing_key, p_body_bytes,
          case when v_too_large then null else p_payload end, v_too_large)
  on conflict (provider, provider_event_id) where signature_valid do nothing
  returning id into v_id;
  if v_id is null then
    return query select 'duplicate'::text, null::uuid;
    return;
  end if;
  return query select 'stored'::text, v_id;
end
$$;

revoke all on function northstar.park_invalid_signature(text, int) from public, anon, authenticated;
grant execute on function northstar.park_invalid_signature(text, int) to app_user;
revoke all on function northstar.park_unknown_account(text, text, text, int, jsonb) from public, anon, authenticated;
grant execute on function northstar.park_unknown_account(text, text, text, int, jsonb) to app_user;
