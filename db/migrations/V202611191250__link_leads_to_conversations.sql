-- Step 2.3: a lead's conversation, now that conversations exist (data-model.md section 6; unlinked since step 1.13).
-- Composite with the contact, so a lead can only point at its own contact's conversation in its own tenant, as a
-- workflow's current version must be its own. conversation_id stays nullable, as since step 1.13; a null is not checked.
alter table northstar.leads
  add constraint leads_conversation_fk
  foreign key (tenant_id, contact_id, conversation_id) references northstar.conversations (tenant_id, contact_id, id);
