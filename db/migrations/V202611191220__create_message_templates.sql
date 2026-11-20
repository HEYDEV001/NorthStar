-- Step 2.3: WhatsApp message templates (data-model.md section 4, integration-specs.md section 2), the only messages
-- allowed outside the 24-hour window (W33). Runs as migrator, so app_user starts with select, insert and update; both
-- are narrowed below. Template sync and the dependent-action failures are step 2.8 (W34).
--
-- A template is identified by its name and language, which never change (another name or language is another row);
-- category is stored as Meta returns it. Templates are never deleted.
create table northstar.message_templates (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references northstar.tenants (id),
  name                 text not null check (length(name) between 1 and 512),
  language             text not null check (length(language) between 1 and 16),
  category             text,
  body                 text,
  variables            jsonb,
  provider_template_id text check (length(provider_template_id) between 1 and 64),
  status               text not null default 'draft'
                         check (status in ('draft', 'submitted', 'approved', 'rejected', 'paused')),
  approved_at          timestamptz,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz,
  unique (tenant_id, id),
  constraint message_templates_name_language_key unique (tenant_id, name, language)
);

create trigger message_templates_set_updated_at before update on northstar.message_templates
  for each row execute function northstar.set_updated_at();

revoke insert, update on northstar.message_templates from app_user;
grant insert (id, tenant_id, name, language, category, body, variables, provider_template_id, status, approved_at)
  on northstar.message_templates to app_user;
grant update (body, variables, category, provider_template_id, status, approved_at)
  on northstar.message_templates to app_user;

alter table northstar.message_templates enable row level security;
alter table northstar.message_templates force row level security;
create policy tenant_isolation on northstar.message_templates for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Staff read templates (they reply with one outside the window); only admins (settings) and the system actor
-- (onboarding and the sync of step 2.8) write them (build-document.md section 3). Restrictive, so they only narrow
-- tenant_isolation. Templates hold no personal data, so reads are not limited by role.
create policy message_templates_write_insert on northstar.message_templates
  as restrictive for insert to app_user
  with check (current_setting('app.role', true) in ('admin', 'system'));
create policy message_templates_write_update on northstar.message_templates
  as restrictive for update to app_user
  using (current_setting('app.role', true) in ('admin', 'system'))
  with check (current_setting('app.role', true) in ('admin', 'system'));
create policy message_templates_write_delete on northstar.message_templates
  as restrictive for delete to app_user
  using (current_setting('app.role', true) in ('admin', 'system'));
