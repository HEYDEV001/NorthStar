-- Step 3.3: what a lead's stage implies (data-model.md section 6, workflow-spec.md section 3), for every role. Stage
-- keys are a version's own, so the database cannot check that a stage exists in the lead's config (the engine does);
-- these two rules use only reserved keys (workflow-spec.md section 15), whose meaning is fixed in every config.
--
-- The four closing stages, and only they, close a lead: closed_at is set exactly while the lead is in one, so the
-- one-open-lead-per-contact index frees the contact for a new lead (W2). Cancelled closes a lead since this step.
alter table northstar.leads
  add constraint leads_closed_in_closing_stage
  check ((closed_at is not null) = (stage in ('junk', 'lost', 'completed', 'cancelled')));

-- previous_stage is the stage Needs Human was entered from, set exactly while the lead is in Needs Human, so a hand
-- back returns to exactly that stage. It is never Needs Human itself.
alter table northstar.leads
  add constraint leads_previous_stage_in_needs_human
  check ((previous_stage is not null) = (stage = 'needs_human') and previous_stage is distinct from 'needs_human');
