-- Step 2.8b: one row per Meta template in a tenant (data-model.md section 13). The sync and Meta's template webhooks
-- find a template by Meta's ID, so two rows with one ID could not be told apart. Meta's template IDs are unique, so a
-- second tenant with the same ID would be a fixture or a mistake, not routing; the key is per tenant, as every key here
-- starts with tenant_id. Drafts and onboarding's rows have no ID yet and are not affected.
create unique index message_templates_provider_template_key
  on northstar.message_templates (tenant_id, provider_template_id)
  where provider_template_id is not null;
