-- Step 2.9a: a conversation's latest activity, so the inbox can list conversations newest activity first and page
-- through them by cursor (data-model.md sections 4 and 13). Sorting by the newest message of each conversation at read
-- time cannot use an index for keyset paging, so the time is kept on the conversation.
--
-- last_message_at is the database's time of the conversation's latest stored message, inbound or outbound: the
-- inbound job (step 2.4b) and queueing an outbound message (step 2.6a) move it forward in the same transaction as the
-- message, never back. Delivery statuses (step 2.7) do not move it: a status locks only its message, never the
-- conversation, and is not new activity in the thread. Retention (data-model.md section 14) counts 12 months from it.
alter table northstar.conversations add column last_message_at timestamptz;

-- Backfill from the newest message, else the conversation's creation (every conversation the inbound job creates has a
-- message, so the fallback is for fixtures). The updated_at trigger is off meanwhile: the backfill is not a change
-- anyone made to the conversation.
alter table northstar.conversations disable trigger conversations_set_updated_at;
update northstar.conversations c
set last_message_at = coalesce(
  (select max(m.created_at) from northstar.messages m where m.tenant_id = c.tenant_id and m.conversation_id = c.id),
  c.created_at);
alter table northstar.conversations enable trigger conversations_set_updated_at;

alter table northstar.conversations
  alter column last_message_at set default now(),
  alter column last_message_at set not null;

-- The inbox (data-model.md section 13): a tenant's conversations newest activity first, and a staff member's assigned
-- ones (staff queries filter on their own membership, so RLS's conversations_staff_assigned stays the backstop rather
-- than a filter over every row). The id breaks ties, so a cursor of (last_message_at, id) is exact.
create index conversations_inbox on northstar.conversations (tenant_id, last_message_at desc, id desc);
create index conversations_inbox_assigned
  on northstar.conversations (tenant_id, assigned_membership_id, last_message_at desc, id desc);

-- New conversations take the default; only the move forward is written afterwards.
grant update (last_message_at) on northstar.conversations to app_user;
