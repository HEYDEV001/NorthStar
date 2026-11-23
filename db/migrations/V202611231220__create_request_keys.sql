-- Step 2.10: request_keys (data-model.md section 3), the store behind a request's Idempotency-Key header (P21; decided
-- 23 Nov 2026, the step 2.10 plan). Manual lead entry is its first user; step 3.9 and later writes reuse it with their
-- own operation codes.
--
-- One row per member, operation and key: the first request with a key inserts its row in the same transaction as the
-- record it creates, so a second request with the same key waits on the unique index until the first commits or rolls
-- back, then either finds the row (a repeat, answered with the record named by target_id) or claims the key itself.
-- request_hash is SHA-256 of the request as normalised by the app, so the same key with another request is told apart
-- (422 idempotency_key_reused) without storing what the request said. target_id names the record the request created,
-- in the table its operation names ('lead.create': leads); like audit_log.target_id it has no foreign key.
--
-- Insert-only: a key, once used, never changes. Admins and the system actor see the tenant's keys; staff only their own
-- (a key is scoped to the member who sent it). Runs as migrator, so app_user gets select, insert and update; update is
-- revoked, and delete is never granted.
create table northstar.request_keys (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references northstar.tenants (id),
  membership_id   uuid not null,
  operation       text not null check (operation in ('lead.create')),
  idempotency_key text not null check (idempotency_key ~ '^[A-Za-z0-9_.:-]{1,100}$'),
  request_hash    text not null check (request_hash ~ '^[0-9a-f]{64}$'),
  target_id       uuid not null,
  created_at      timestamptz not null default now(),
  unique (tenant_id, id),
  constraint request_keys_one_per_key unique (tenant_id, membership_id, operation, idempotency_key),
  foreign key (tenant_id, membership_id) references northstar.memberships (tenant_id, id)
);

revoke update on northstar.request_keys from app_user;

alter table northstar.request_keys enable row level security;
alter table northstar.request_keys force row level security;
create policy tenant_isolation on northstar.request_keys for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Staff see and write only their own keys (P11); admins and the system actor see the tenant's. Restrictive, so it only
-- narrows tenant_isolation; the webhook role, a missing role and an unknown one see nothing.
create policy request_keys_staff_own on northstar.request_keys
  as restrictive for all to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and membership_id = (
          select m.id from northstar.memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')))
  with check (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and membership_id = (
          select m.id from northstar.memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')));
