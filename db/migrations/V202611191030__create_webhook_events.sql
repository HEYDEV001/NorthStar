-- Step 2.1a: webhooks routed to a tenant (data-model.md section 9, integration-specs.md section 1). The handler
-- verifies the signature and resolves the tenant before inserting, so tenant_id is never null and every row here is
-- verified; anything it cannot route goes to ingest.unrouted_webhooks instead.
--
-- Dedupe (P1): provider event IDs are globally unique, so (provider, provider_event_id) is unique across tenants and a
-- second copy inserts nothing (insert ... on conflict do nothing). error is a fixed code, as actions.last_error_code.
-- A lifecycle table (data-model.md section 12): only processed_at and error change after the insert, and nothing is
-- ever deleted. received_at is stamped by the database. Clearing payload after 30 days is step 8.6.
create table northstar.webhook_events (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references northstar.tenants (id),
  provider          text not null check (provider in ('whatsapp', 'razorpay', 'resend')),
  provider_event_id text not null check (length(provider_event_id) between 1 and 200),
  payload           jsonb not null,
  received_at       timestamptz not null default now(),
  processed_at      timestamptz,
  error             text check (error ~ '^[a-z0-9_]{1,64}$'),
  unique (tenant_id, id),
  constraint webhook_events_provider_event_key unique (provider, provider_event_id)
);

revoke insert, update on northstar.webhook_events from app_user;
grant insert (id, tenant_id, provider, provider_event_id, payload) on northstar.webhook_events to app_user;
grant update (processed_at, error) on northstar.webhook_events to app_user;

alter table northstar.webhook_events enable row level security;
alter table northstar.webhook_events force row level security;
create policy tenant_isolation on northstar.webhook_events for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
