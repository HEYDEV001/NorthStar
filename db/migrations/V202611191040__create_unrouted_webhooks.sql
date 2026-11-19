-- Step 2.1a: webhook deliveries that cannot be routed to a tenant (data-model.md section 9, P15, P16), in the private
-- ingest schema that db/bootstrap/roles.sql creates for migrator. Platform-wide, so no tenant_id: the tenant is
-- unknown. These rows are never processed and never copied into a tenant table; a provider retry that arrives after
-- the business connects is routed normally.
--
-- Only app_admin may read them (select only; the Phase 8 admin panel, audited). app_user writes only through an
-- allowlisted, rate-limited SECURITY DEFINER function (step 2.2), so it has no privilege here. Rows are deleted after
-- 30 days by the retention job (step 8.6).
--
-- A bad signature (P15) proves nothing about the body, so its row keeps metadata only: provider, the provider event ID
-- if one could be parsed, the body's size and the time. An unknown account (P16) has a verified body: its row keeps the
-- routing key (phone number or account ID) and the payload, unless the body is over 64 KB (65,536 bytes), in which
-- case the payload is dropped and payload_too_large is set.
create table ingest.unrouted_webhooks (
  id                uuid primary key default gen_random_uuid(),
  provider          text not null check (provider in ('whatsapp', 'razorpay', 'resend')),
  provider_event_id text check (length(provider_event_id) between 1 and 200),
  signature_valid   boolean not null,
  reason            text not null check (reason in ('invalid_signature', 'unknown_account')),
  routing_key       text check (length(routing_key) between 1 and 200),
  body_bytes        int not null check (body_bytes >= 0),
  payload           jsonb,
  payload_too_large boolean not null default false,
  received_at       timestamptz not null default now(),
  constraint unrouted_webhooks_reason_matches_signature check ((reason = 'invalid_signature') = (not signature_valid)),
  constraint unrouted_webhooks_bad_signature_keeps_metadata_only
    check (signature_valid or (payload is null and routing_key is null and not payload_too_large)),
  constraint unrouted_webhooks_unknown_account_keeps_its_key check (not signature_valid or routing_key is not null),
  constraint unrouted_webhooks_payload_cap
    check (not signature_valid
           or (payload_too_large = (body_bytes > 65536) and (payload is null) = payload_too_large))
);

-- A provider retrying an unroutable event stores it once. Unverified rows are not deduplicated: their event IDs are
-- whatever the sender wrote.
create unique index unrouted_webhooks_provider_event on ingest.unrouted_webhooks (provider, provider_event_id)
  where signature_valid;
-- The retention job deletes by age.
create index unrouted_webhooks_received_at on ingest.unrouted_webhooks (received_at);

revoke all on ingest.unrouted_webhooks from public;
grant usage on schema ingest to app_admin;
grant select on ingest.unrouted_webhooks to app_admin;
