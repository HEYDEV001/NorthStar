-- Step 2.9c: taking a conversation over and handing it back (build-document.md sections 3 and 15, the per-conversation
-- kill switch; workflow-spec.md section 1, principle 5; data-model.md section 4).
--
-- ai_paused (unused since step 2.3) becomes automation_paused: it stops more than the AI. While it is set, nothing
-- automated (the engine's or the AI's sends) is sent to the conversation's contact: taking over cancels the automated
-- sends already queued, queueing refuses new ones (step 2.9c), and the engine (Phase 3) and the AI (Phase 4) check it
-- before they act. Staff replies are unaffected.
--
-- Who took the conversation over and when: set together, cleared together by handing it back. A conversation taken
-- over has automation paused; automation can later be paused for other reasons (no step does yet), so the check is one
-- way only. The member is the conversation's own tenant's (composite foreign key).
alter table northstar.conversations rename column ai_paused to automation_paused;

alter table northstar.conversations
  add column taken_over_by_membership_id uuid,
  add column taken_over_at               timestamptz,
  add constraint conversations_taken_over_by_fk
    foreign key (tenant_id, taken_over_by_membership_id) references northstar.memberships (tenant_id, id),
  add constraint conversations_taken_over_complete
    check ((taken_over_by_membership_id is null) = (taken_over_at is null)),
  add constraint conversations_taken_over_is_paused check (taken_over_at is null or automation_paused);

-- Admins take over any conversation, staff their own (conversations_staff_assigned decides which rows they may update);
-- the service sets the member and time from the caller, never from the request.
grant update (taken_over_by_membership_id, taken_over_at) on northstar.conversations to app_user;
