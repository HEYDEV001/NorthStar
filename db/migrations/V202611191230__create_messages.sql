-- Step 2.3: messages (data-model.md section 4, integration-specs.md section 2). Every inbound and outbound WhatsApp
-- message of a conversation. Runs as migrator, so app_user starts with select, insert and update; both are narrowed
-- below.
--
-- Dedupe (P1): unique (tenant_id, provider_message_id), so an inbound message is stored once however often Meta
-- delivers it. An outbound message is stored as queued before Meta returns its ID, so provider_message_id may be null
-- only on an outbound message that is queued or failed (several of those can coexist: nulls are distinct).
--
-- A lifecycle table (data-model.md section 12): only the delivery columns change after the insert, and nothing is ever
-- deleted. provider_message_id (set by the send, step 2.6) and media_path (set when the media is stored, step 4.5)
-- are set once: the messages_set_once trigger refuses changing either after it has a value, for every role. Keeping
-- delivery statuses moving forward when Meta's webhooks arrive out of order is step 2.7.
--
-- Retention (data-model.md section 14): body and media are removed 12 months after the conversation's last activity
-- and the row is kept, so neither is required by any check. Clearing them is step 8.6 (a narrow grant or function, and
-- a change to messages_set_once for media_path); until then nobody can update body.
--
-- Bodies are not encrypted at the column level (decided 19 Nov 2026, data-model.md section 15): Supabase encrypts
-- the database at rest, and app_admin cannot select body (below), as the Northstar admin panel has no access to
-- conversations (build-document.md section 3).
create table northstar.messages (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references northstar.tenants (id),
  conversation_id      uuid not null,
  direction            text not null check (direction in ('in', 'out')),
  sender_type          text not null check (sender_type in ('contact', 'ai', 'staff', 'system')),
  sender_membership_id uuid,
  provider_message_id  text check (length(provider_message_id) between 1 and 200),
  type                 text not null
                         check (type in ('text', 'interactive', 'image', 'document', 'audio', 'location', 'template',
                                         'unsupported')),
  body                 text,
  media_path           text,
  template_id          uuid,
  status               text check (status in ('queued', 'sent', 'delivered', 'read', 'failed')),
  error_code           text check (error_code ~ '^[a-z0-9_]{1,64}$'),  -- a fixed code, as actions.last_error_code
  sent_at              timestamptz,  -- inbound: WhatsApp's timestamp of the message
  delivered_at         timestamptz,
  read_at              timestamptz,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz,
  unique (tenant_id, id),
  constraint messages_provider_message_key unique (tenant_id, provider_message_id),
  foreign key (tenant_id, conversation_id) references northstar.conversations (tenant_id, id),
  foreign key (tenant_id, sender_membership_id) references northstar.memberships (tenant_id, id),
  foreign key (tenant_id, template_id) references northstar.message_templates (tenant_id, id),
  -- Inbound messages come from the contact, and only they do.
  constraint messages_inbound_from_contact check ((direction = 'in') = (sender_type = 'contact')),
  -- A staff member's message names them; nobody else's names a member.
  constraint messages_staff_sender check ((sender_type = 'staff') = (sender_membership_id is not null)),
  -- A template message names its template, is outbound, and is the only kind that names one.
  constraint messages_template check ((type = 'template') = (template_id is not null)
                                      and (type <> 'template' or direction = 'out')),
  -- Delivery status is tracked for outbound messages only.
  constraint messages_status_outbound_only check ((direction = 'out') = (status is not null)),
  constraint messages_provider_id_required
    check (provider_message_id is not null or (direction = 'out' and status in ('queued', 'failed'))),
  -- Storage paths start with the tenant's folder (CLAUDE.md, data-model.md section 12), with no '..' segment.
  constraint messages_media_in_tenant_folder
    check (media_path is null
           or (starts_with(media_path, tenant_id::text || '/')
               and length(media_path) > 37
               and media_path !~ '(^|/)\.\.(/|$)'))
);

-- Inbox (data-model.md section 13): a conversation's messages, newest first.
create index messages_inbox on northstar.messages (tenant_id, conversation_id, created_at desc);

create trigger messages_set_updated_at before update on northstar.messages
  for each row execute function northstar.set_updated_at();

create function northstar.messages_set_once() returns trigger
language plpgsql
as $$
begin
  if old.provider_message_id is not null and new.provider_message_id is distinct from old.provider_message_id then
    raise exception 'A message''s provider ID is set once' using errcode = 'NSM01';
  end if;
  if old.media_path is not null and new.media_path is distinct from old.media_path then
    raise exception 'A message''s media path is set once' using errcode = 'NSM01';
  end if;
  return new;
end
$$;

create trigger messages_set_once before update on northstar.messages
  for each row execute function northstar.messages_set_once();

revoke insert, update on northstar.messages from app_user;
grant insert (id, tenant_id, conversation_id, direction, sender_type, sender_membership_id, provider_message_id, type,
              body, media_path, template_id, status, error_code, sent_at, delivered_at, read_at)
  on northstar.messages to app_user;
grant update (status, error_code, sent_at, delivered_at, read_at, provider_message_id, media_path)
  on northstar.messages to app_user;

-- app_admin (the Northstar admin panel, Phase 8) reads every column but body. A column added later is not readable
-- by app_admin until its migration grants it.
revoke select on northstar.messages from app_admin;
grant select (id, tenant_id, conversation_id, direction, sender_type, sender_membership_id, provider_message_id, type,
              media_path, template_id, status, error_code, sent_at, delivered_at, read_at, created_at, updated_at)
  on northstar.messages to app_admin;

alter table northstar.messages enable row level security;
alter table northstar.messages force row level security;
create policy tenant_isolation on northstar.messages for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Staff see and write only the messages of conversations assigned to their own active membership (P11); admins and
-- the system actor see the tenant's messages. Restrictive, so it only narrows tenant_isolation; the webhook role, a
-- missing role and an unknown one see nothing. The assignment is spelled out rather than left to conversations' own
-- policy, so this text alone says who sees a message. Admins and the system actor pass on the role test; for staff
-- the membership lookup runs once per query and each message costs one primary-key probe on conversations.
create policy messages_staff_assigned on northstar.messages
  as restrictive for all to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and exists (
          select from northstar.conversations c
          where c.tenant_id = messages.tenant_id
            and c.id = messages.conversation_id
            and c.assigned_membership_id = (
              select m.id from northstar.memberships m
              where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                and m.status = 'active'))))
  with check (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and exists (
          select from northstar.conversations c
          where c.tenant_id = messages.tenant_id
            and c.id = messages.conversation_id
            and c.assigned_membership_id = (
              select m.id from northstar.memberships m
              where m.tenant_id = current_setting('app.tenant_id', true)::uuid
                and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                and m.status = 'active'))));
