-- Step 1.13: an action's lead, now that leads exist (data-model.md section 6). Composite, so an action can never
-- point at another tenant's lead. lead_id stays nullable for actions with no lead (invite emails, admin alerts).
alter table northstar.actions
  add constraint actions_lead_fk foreign key (tenant_id, lead_id) references northstar.leads (tenant_id, id);
