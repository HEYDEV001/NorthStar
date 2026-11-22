-- Step 2.8c: a template's queued messages (W34; data-model.md section 13). When Meta changes a template's status, its
-- sends still waiting to go out are found by their queued messages: paused, resumed or failed with the template. Small,
-- as a message leaves it once it is sent or fails.
create index messages_queued_by_template on northstar.messages (tenant_id, template_id) where status = 'queued';
