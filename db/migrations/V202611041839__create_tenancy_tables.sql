-- Tenancy and access (data-model.md section 3). Runs as migrator, so the default privileges from
-- V202611022252 apply: app_user gets select, insert and update, app_admin gets select. Exceptions are set below.
--
-- Child rows reference (tenant_id, id) on their parent, so a row can never point at another tenant's row
-- (data-model.md section 1). The RLS release gate checks this for every foreign key between tenant tables.

-- updated_at maintenance. A normal invoker function: it only touches the row being written.
create function northstar.set_updated_at() returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end
$$;


-- tenants: one row per client business. Platform-wide (no tenant_id); app_user sees only its own row, and only an
-- admin can change settings.
create table northstar.tenants (
  id              uuid primary key default gen_random_uuid(),
  name            text not null,
  slug            text not null unique,
  industry        text,
  timezone        text not null default 'Asia/Kolkata',
  locale          text,
  brand_tone      text,
  legal_name      text,
  gstin           text,
  state_code      char(2),
  billing_address jsonb,
  approval_mode   boolean not null default true,
  quiet_hours     jsonb,
  status          text not null default 'onboarding'
                  check (status in ('onboarding', 'active', 'paused', 'suspended', 'closed')),
  plan_id         uuid,  -- references plans(id) once Phase 8 creates plans
  activated_at    timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz
);

create trigger tenants_set_updated_at before update on northstar.tenants
  for each row execute function northstar.set_updated_at();

-- status, plan_id and activated_at belong to billing and Northstar admin; slug never changes once chat links use it.
revoke insert, update on northstar.tenants from app_user;
grant insert (id, name, slug, industry, timezone, locale, brand_tone, legal_name, gstin, state_code, billing_address,
              approval_mode, quiet_hours)
  on northstar.tenants to app_user;
grant update (name, industry, timezone, locale, brand_tone, legal_name, gstin, state_code, billing_address,
              approval_mode, quiet_hours)
  on northstar.tenants to app_user;

alter table northstar.tenants enable row level security;
alter table northstar.tenants force row level security;

create policy tenant_select on northstar.tenants for select to app_user
  using (id = current_setting('app.tenant_id', true)::uuid);
create policy tenant_insert on northstar.tenants for insert to app_user
  with check (id = current_setting('app.tenant_id', true)::uuid);
create policy tenant_update on northstar.tenants for update to app_user
  using (id = current_setting('app.tenant_id', true)::uuid and current_setting('app.role', true) = 'admin')
  with check (id = current_setting('app.tenant_id', true)::uuid and current_setting('app.role', true) = 'admin');


-- user_profiles: one row per Supabase Auth user. Platform-wide. id equals auth.users.id; there is no foreign key,
-- because migrator cannot reference auth.users on Supabase, so the service creates profiles only from the verified
-- token's user ID.
create table northstar.user_profiles (
  id                uuid primary key,
  full_name         text,
  phone             text check (phone ~ '^\+[1-9][0-9]{6,14}$'),
  avatar_path       text,
  is_platform_admin boolean not null default false,
  created_at        timestamptz not null default now(),
  deleted_at        timestamptz
);

-- is_platform_admin is set only by Northstar, never through app_user.
revoke insert, update on northstar.user_profiles from app_user;
grant insert (id, full_name, phone, avatar_path) on northstar.user_profiles to app_user;
grant update (full_name, phone, avatar_path) on northstar.user_profiles to app_user;


-- memberships: a user's access to one tenant. One active membership per user for MVP (D4); invited and deactivated
-- rows don't count, so a tenant claim in the token can be added later without a migration.
create table northstar.memberships (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references northstar.tenants (id),
  user_id        uuid not null references northstar.user_profiles (id),
  role           text not null check (role in ('admin', 'staff')),
  status         text not null check (status in ('invited', 'active', 'deactivated')),
  whatsapp_phone text check (whatsapp_phone ~ '^\+[1-9][0-9]{6,14}$'),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, user_id)
);

create unique index memberships_one_active_per_user on northstar.memberships (user_id) where status = 'active';

create trigger memberships_set_updated_at before update on northstar.memberships
  for each row execute function northstar.set_updated_at();

alter table northstar.memberships enable row level security;
alter table northstar.memberships force row level security;
create policy tenant_isolation on northstar.memberships for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);


-- user_profiles RLS needs memberships, so it comes after it. Read: your own profile and the members of the current
-- tenant. Write: your own profile only.
alter table northstar.user_profiles enable row level security;
alter table northstar.user_profiles force row level security;

create policy profile_select on northstar.user_profiles for select to app_user
  using (id = current_setting('app.user_id', true)::uuid
         or exists (select from northstar.memberships m
                    where m.user_id = user_profiles.id
                      and m.tenant_id = current_setting('app.tenant_id', true)::uuid));
create policy profile_insert on northstar.user_profiles for insert to app_user
  with check (id = current_setting('app.user_id', true)::uuid);
create policy profile_update on northstar.user_profiles for update to app_user
  using (id = current_setting('app.user_id', true)::uuid)
  with check (id = current_setting('app.user_id', true)::uuid);


-- invites: only the token's hash is stored. One pending invite per tenant and email, compared case-insensitively.
create table northstar.invites (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references northstar.tenants (id),
  email       text not null,
  role        text not null check (role in ('admin', 'staff')),
  token_hash  text not null unique,  -- global: acceptance looks the invite up by hash before any tenant is known
  invited_by  uuid not null,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default now() + interval '7 days',
  accepted_at timestamptz,
  revoked_at  timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, invited_by) references northstar.memberships (tenant_id, id),
  check (accepted_at is null or revoked_at is null)
);

create unique index invites_one_pending_per_email on northstar.invites (tenant_id, lower(email))
  where accepted_at is null and revoked_at is null;

alter table northstar.invites enable row level security;
alter table northstar.invites force row level security;
create policy tenant_isolation on northstar.invites for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);


-- staff_skills: used by assignment rules. Removing a skill is a hard delete.
create table northstar.staff_skills (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references northstar.tenants (id),
  membership_id uuid not null,
  skill         text not null,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, membership_id, skill),
  foreign key (tenant_id, membership_id) references northstar.memberships (tenant_id, id)
);

grant delete on northstar.staff_skills to app_user;

alter table northstar.staff_skills enable row level security;
alter table northstar.staff_skills force row level security;
create policy tenant_isolation on northstar.staff_skills for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);


-- staff_leave: never deleted; a cancelled leave keeps its row with cancelled_at and cancelled_by.
create table northstar.staff_leave (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references northstar.tenants (id),
  membership_id uuid not null,
  starts_on     date not null,
  ends_on       date not null,
  reason        text,
  created_by    uuid not null,
  created_at    timestamptz not null default now(),
  cancelled_at  timestamptz,
  cancelled_by  uuid,
  unique (tenant_id, id),
  foreign key (tenant_id, membership_id) references northstar.memberships (tenant_id, id),
  foreign key (tenant_id, created_by) references northstar.memberships (tenant_id, id),
  foreign key (tenant_id, cancelled_by) references northstar.memberships (tenant_id, id),
  check (ends_on >= starts_on),
  check ((cancelled_at is null) = (cancelled_by is null))
);

create index staff_leave_by_member on northstar.staff_leave (tenant_id, membership_id, starts_on);

alter table northstar.staff_leave enable row level security;
alter table northstar.staff_leave force row level security;
create policy tenant_isolation on northstar.staff_leave for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);


-- audit_log: insert-only. actor_user_id is global (user_profiles); target_id points at many tables, so it has no
-- foreign key.
create table northstar.audit_log (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references northstar.tenants (id),
  actor_user_id uuid references northstar.user_profiles (id),
  action        text not null,
  target_table  text,
  target_id     uuid,
  metadata      jsonb,
  ip_hash       text,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id)
);

create index audit_log_by_time on northstar.audit_log (tenant_id, created_at desc);

revoke update on northstar.audit_log from app_user;

alter table northstar.audit_log enable row level security;
alter table northstar.audit_log force row level security;
create policy tenant_isolation on northstar.audit_log for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
