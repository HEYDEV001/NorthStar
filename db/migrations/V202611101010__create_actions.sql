-- Step 1.10: actions, every side effect the platform performs (workflow-spec.md sections 9 and 10, data-model.md
-- section 6). Runs as migrator, so app_user starts with select, insert and update; both are narrowed below.
--
-- A worker claims a due action (status processing, lease_expires_at set), calls the handler outside any transaction,
-- then records the outcome. last_error_class and last_error_code are fixed codes only: never message bodies, tokens,
-- phone numbers or payment details.
create table northstar.actions (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references northstar.tenants (id),
  lead_id                uuid,  -- nullable: invite emails and admin alerts have no lead; FK to leads in step 1.13
  type                   text not null check (type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$' and length(type) <= 64),
  status                 text not null
                         check (status in ('pending', 'processing', 'succeeded', 'failed', 'needs_review', 'paused',
                                           'skipped', 'cancelled')),
  idempotency_key        text not null check (length(idempotency_key) between 1 and 200),
  mode                   text not null check (mode in ('approval', 'automatic')),
  payload                jsonb not null default '{}',
  result                 jsonb,
  attempts               int not null default 0 check (attempts >= 0),
  next_attempt_at        timestamptz,
  lease_expires_at       timestamptz,
  last_attempt_at        timestamptz,
  last_error_class       text check (last_error_class in ('retryable', 'permanent', 'auth')),
  last_error_code        text check (last_error_code ~ '^[a-z0-9_]{1,64}$'),
  approver_membership_id uuid,
  approved_at            timestamptz,
  stage_entry_id         uuid,  -- unlinked until Phase 3 defines stage entries
  created_at             timestamptz not null default now(),
  updated_at             timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, idempotency_key),
  foreign key (tenant_id, approver_membership_id) references northstar.memberships (tenant_id, id),
  constraint actions_pending_is_scheduled check (status <> 'pending' or next_attempt_at is not null),
  constraint actions_lease_only_while_processing check ((status = 'processing') = (lease_expires_at is not null)),
  constraint actions_approval_complete check ((approver_membership_id is null) = (approved_at is null)),
  constraint actions_error_complete check ((last_error_class is null) = (last_error_code is null))
);

-- Worker pickup and the Failed Actions screen (data-model.md section 13); the partial indexes serve the sweeper.
create index actions_tenant_status_next_attempt on northstar.actions (tenant_id, status, next_attempt_at);
create index actions_due on northstar.actions (next_attempt_at) where status = 'pending';
create index actions_lease_expiry on northstar.actions (lease_expires_at) where status = 'processing';

create trigger actions_set_updated_at before update on northstar.actions
  for each row execute function northstar.set_updated_at();

-- Written once: what the action is, for whom and with which key. Only the lifecycle columns change afterwards, and
-- actions are never deleted (an action that no longer applies is cancelled).
revoke insert, update on northstar.actions from app_user;
grant insert (id, tenant_id, lead_id, type, status, idempotency_key, mode, payload, next_attempt_at, stage_entry_id)
  on northstar.actions to app_user;
grant update (status, result, attempts, next_attempt_at, lease_expires_at, last_attempt_at, last_error_class,
              last_error_code, approver_membership_id, approved_at)
  on northstar.actions to app_user;

alter table northstar.actions enable row level security;
alter table northstar.actions force row level security;
create policy tenant_isolation on northstar.actions for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
