-- Step 2.5: a business's own opt-out keywords, on top of the platform's (W32; data-model.md section 3,
-- workflow-spec.md section 13 Opt-out). A tenant setting, like approval_mode and quiet_hours. A message opts its
-- sender out only when its whole text, normalised, equals a platform or tenant keyword normalised the same way, so
-- keywords may be stored as typed; one that normalises to nothing never matches.
--
-- No settings path writes it yet, so app_user gets no insert or update grant (tenants stays column-restricted to the
-- columns it had). The settings step adds the grant, its validation and the endpoint; until then an operator sets it
-- as migrator.
alter table northstar.tenants
  add column opt_out_keywords text[] not null default '{}';

alter table northstar.tenants
  add constraint tenants_opt_out_keywords_valid
    check (cardinality(opt_out_keywords) <= 20 and array_position(opt_out_keywords, null) is null);
