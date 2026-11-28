-- Step 3.3: the key timeline rows reference (data-model.md sections 6, 10 and 13). A timeline row names its lead and
-- its contact; the composite foreign key (tenant_id, contact_id, lead_id) to this key makes the contact always the
-- lead's own, in the lead's own tenant, as leads_conversation_fk does for a lead's conversation. contact_id and id never
-- change, so moving a lead (a no-key update) never conflicts with the rows that reference it.
alter table northstar.leads
  add constraint leads_contact_lead_key unique (tenant_id, contact_id, id);
