-- Step 2.8a: a WhatsApp Business Account (WABA) belongs to one tenant (data-model.md sections 9 and 13). Template,
-- limit and account webhooks name only the WABA ID (entry.id), and they are routed by it, so two tenants holding one
-- WABA could not be told apart. Several numbers of one tenant may share a WABA; another tenant may not use it.
--
-- An exclusion constraint, not a unique one: rows are one per number, so waba_id repeats within a tenant. It needs
-- btree_gist, which db/bootstrap/roles.sql installs as postgres; this migration only checks for it, so a database
-- bootstrapped before step 2.8a stops here with SQLSTATE NSB01 and a message saying what to run, instead of failing on
-- the operator class. The deploy's migration runner prints the SQL state only (never a message), so NSB01 is what an
-- operator sees there (db/bootstrap/README.md). The constraint's GiST index on (waba_id, tenant_id) also serves the
-- routing lookup by WABA ID (northstar.whatsapp_waba_tenants).
do $$
begin
  if not exists (select from pg_extension where extname = 'btree_gist') then
    raise exception 'btree_gist is not installed: run db/bootstrap/roles.sql as postgres, then verify.sql, and migrate again'
      using errcode = 'NSB01';
  end if;
end
$$;

alter table northstar.whatsapp_accounts
  add constraint whatsapp_accounts_one_tenant_per_waba exclude using gist (waba_id with =, tenant_id with <>);
