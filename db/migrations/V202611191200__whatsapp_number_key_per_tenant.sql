-- Step 2.3: a conversation names the business's WhatsApp number it runs on (data-model.md section 4). Its foreign key
-- is composite, as every reference between tenant tables is (data-model.md section 1), so the number must be one of the
-- conversation's own tenant's. phone_number_id is already unique across tenants; this adds the (tenant_id, key) pair
-- the composite foreign key needs.
alter table northstar.whatsapp_accounts
  add constraint whatsapp_accounts_tenant_phone_number_id_key unique (tenant_id, phone_number_id);
