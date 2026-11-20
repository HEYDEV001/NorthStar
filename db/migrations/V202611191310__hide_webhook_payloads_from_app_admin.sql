-- Step 2.3: app_admin (the Northstar admin panel, Phase 8) reads every column of webhook_events but payload. A WhatsApp
-- payload carries the same message text as messages.body for 30 days (data-model.md section 14), and the admin panel
-- has no access to conversations (build-document.md section 3; decided 19 Nov 2026, data-model.md section 15). Its
-- webhook-errors view needs only the IDs, times and error codes. A column added later is not readable by app_admin
-- until its migration grants it.
revoke select on northstar.webhook_events from app_admin;
grant select (id, tenant_id, provider, provider_event_id, received_at, processed_at, error)
  on northstar.webhook_events to app_admin;
