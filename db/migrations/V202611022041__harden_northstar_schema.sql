-- Flyway creates the northstar schema before this runs (schemas=northstar, createSchemas=true).
-- Every Northstar table lives here. The schema is not exposed through Supabase's Data API, and the
-- browser-facing roles get no access to it or to anything created in it later (data-model.md section 1).

revoke all on schema northstar from public, anon, authenticated;

alter default privileges in schema northstar revoke all on tables from public, anon, authenticated;
alter default privileges in schema northstar revoke all on sequences from public, anon, authenticated;
