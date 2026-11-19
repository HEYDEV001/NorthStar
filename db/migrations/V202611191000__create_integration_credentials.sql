-- Step 2.1a: tenant credentials (data-model.md section 9, integration-specs.md section 7), in the private secrets
-- schema that db/bootstrap/roles.sql creates for migrator. Only secrets_user, the worker's own login, may use it, so
-- the API (app_user) cannot read even the ciphertext. secrets has no default privileges: every grant is below.
--
-- The worker encrypts each value with AES-256-GCM before it is sent (step 2.1b); the database sees only ciphertext and
-- the ID of the key that made it. encrypted_secret is a format byte, a 12-byte nonce, the ciphertext and a 16-byte tag,
-- so it is at least 30 bytes for a non-empty secret. rotated_at is when encrypted_secret was last replaced.
create table secrets.integration_credentials (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references northstar.tenants (id),
  provider         text not null check (provider in ('whatsapp', 'razorpay', 'google', 'meta_ads', 'meta_capi')),
  encrypted_secret bytea not null check (octet_length(encrypted_secret) >= 30),
  key_id           text not null check (key_id ~ '^[a-z0-9][a-z0-9-]{0,31}$'),
  expires_at       timestamptz,
  rotated_at       timestamptz,
  created_at       timestamptz not null default now(),
  unique (tenant_id, id)
);

-- Written by the worker when a business connects; afterwards only the secret itself and its timestamps change. No
-- delete until disconnecting is decided (step 7.5).
revoke all on secrets.integration_credentials from public;
grant usage on schema secrets to secrets_user;
grant select on secrets.integration_credentials to secrets_user;
grant insert (id, tenant_id, provider, encrypted_secret, key_id, expires_at, rotated_at)
  on secrets.integration_credentials to secrets_user;
grant update (encrypted_secret, key_id, expires_at, rotated_at) on secrets.integration_credentials to secrets_user;

-- The standard tenant_isolation policy, for this schema's own login instead of app_user (data-model.md section 12):
-- the worker's credential pool sets app.tenant_id exactly as the app's pool does.
alter table secrets.integration_credentials enable row level security;
alter table secrets.integration_credentials force row level security;
create policy tenant_isolation on secrets.integration_credentials for all to secrets_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
