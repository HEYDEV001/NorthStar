# Northstar Data Model

Oct 22, 2026 · @Abhishek

## 1. Conventions

The schema runs on Supabase Postgres (Mumbai), managed by Flyway migrations in `db/migrations/`; every rule below applies to every table unless the table says otherwise.

| Topic | Rule |
| --- | --- |
| Schema | All Northstar tables, and Flyway's `flyway_schema_history`, live in the `northstar` schema. It is not listed in Supabase's Data API schemas, and `anon` and `authenticated` have no access to it, so no table is reachable with the publishable key. Integration secrets use a separate private schema (section 9) |
| Primary keys | `id uuid primary key default gen_random_uuid()` |
| Tenant scope | Every business-owned table has `tenant_id uuid not null references tenants(id)`, including join and history tables |
| Tenant foreign keys | A row in a tenant table references another tenant table by `(tenant_id, x_id)` → parent `(tenant_id, id)`, so it can never point at another tenant's row; every parent has `unique (tenant_id, id)`. Foreign keys to platform-wide tables (`tenants`, `user_profiles`) are plain. The RLS release gate (section 12) fails on any other foreign key between tenant tables |
| Timestamps | `created_at timestamptz not null default now()`, `updated_at timestamptz` (trigger-maintained); stored in UTC, shown in the tenant timezone |
| Money | `bigint` in paise (₹1 = 100) with `currency char(3) default 'INR'`; never floats |
| Tax rates | `numeric(5,2)` percent (e.g. 18.00) |
| Phones | E.164 text (`+919876543210`) in `normalized_phone` |
| Enumerations | `text` + `check` constraint listing allowed values (easier to migrate than Postgres enum types) |
| Flexible fields | `jsonb` only for business-specific extras (`requirements`, `config`); anything queried or joined is a typed column |
| Soft delete | `deleted_at timestamptz` on contacts, leads, catalogue items, users; financial records are never deleted |
| Optimistic locking | `version int not null default 1` on leads, quotes, appointments; updates check and increment it |
| Append-only | `timeline_events`, `audit_log`, `webhook_events`, `lead_score_history`, `payments` status history: insert only |
| Secrets | Never in tables readable by tenants; credentials stored encrypted, referenced by id |
| Naming | snake\_case, plural table names, `<table>_id` foreign keys |

## 2. At a glance

About 60 tables in nine groups; the lead sits at the centre and every group links to it.

&#91;embedded content: table groups · 9 groups around the lead\]

Tenancy links to everything through tenant\_id; webhook\_events is the one table whose tenant is resolved after arrival.

### Full entity-relationship diagram

All 59 tables and their foreign keys. Every table also carries `tenant_id`; tenant links are drawn only for tables with no other parent, to keep the diagram readable.

```mermaid
erDiagram
  %% Tenancy and platform billing
  tenants ||--o{ memberships : has
  user_profiles ||--o{ memberships : "belongs via"
  tenants ||--o{ invites : sends
  memberships ||--o{ staff_skills : has
  memberships ||--o{ staff_leave : takes
  plans ||--o{ subscriptions : "priced by"
  tenants ||--o{ subscriptions : has
  subscriptions ||--o{ subscription_invoices : bills
  tenants ||--o{ usage_alerts : receives

  %% Contacts and messages
  tenants ||--o{ contacts : owns
  contacts ||--o{ contact_sources : "arrived from"
  contacts ||--o{ conversations : has
  conversations ||--o{ messages : contains
  tenants ||--o{ message_templates : owns
  message_templates ||--o{ messages : "used by"
  tenants ||--o{ faq_entries : owns

  %% Attribution
  tenants ||--o{ chat_links : creates
  chat_links ||--o{ link_clicks : logs
  contacts ||--o{ ad_referrals : "came via"
  tenants ||--o{ ad_spend_daily : tracks
  chat_links ||--o{ contact_sources : tags
  ad_referrals ||--o{ contact_sources : tags

  %% Workflow and leads
  tenants ||--o{ workflows : configures
  workflows ||--o{ workflow_versions : publishes
  contacts ||--o{ leads : "has"
  conversations ||--o{ leads : "talks in"
  workflow_versions ||--o{ leads : runs
  ad_referrals |o--o{ leads : sources
  chat_links |o--o{ leads : sources
  link_clicks }o--o| leads : "matched to"
  memberships |o--o{ leads : "assigned to"
  leads ||--o{ lead_fields : stores
  messages |o--o{ lead_fields : "extracted from"
  leads ||--o{ lead_score_history : scores
  leads ||--o{ events : receives
  leads ||--o{ actions : runs
  leads ||--o{ timers : schedules

  %% Commercial
  tenants ||--o{ catalogue_items : sells
  leads ||--o{ quotes : gets
  quotes ||--o{ quote_versions : versions
  quote_versions ||--o{ quote_lines : lists
  catalogue_items |o--o{ quote_lines : "copied into"
  leads ||--o{ payments : pays
  quote_versions ||--o{ payments : "accepted as"
  payments ||--o{ invoices : "invoiced by"
  tenants ||--o{ invoice_sequences : numbers
  tenants ||--o{ invoice_templates : designs
  invoice_templates |o--o{ invoices : formats
  invoices ||--o{ invoice_lines : lists
  invoices ||--o{ credit_notes : "cancelled by"
  payments ||--o{ refunds : refunds
  credit_notes |o--o| refunds : documents
  tenants ||--o{ cancellation_policies : sets

  %% Scheduling
  memberships ||--o{ calendar_connections : connects
  memberships ||--o{ busy_blocks : "is busy"
  leads ||--o{ slot_holds : holds
  memberships ||--o{ slot_holds : "held for"
  payments |o--o| slot_holds : secures
  leads ||--o{ appointments : books
  memberships ||--o{ appointments : "works"
  appointments ||--o{ appointment_assignments : "reassigned via"
  leads ||--o{ tasks : has
  appointments |o--o{ tasks : includes
  memberships ||--o{ tasks : owns

  %% Integrations
  tenants ||--o{ integrations : connects
  integration_credentials ||--o{ integrations : authorises
  integration_credentials ||--o{ calendar_connections : authorises
  tenants ||--o{ whatsapp_accounts : owns
  tenants ||--o{ sheet_mappings : maps
  tenants ||--o{ sync_outbox : queues
  tenants ||--o{ sheet_conflicts : reviews
  leads ||--o{ meta_events : reports
  tenants |o--o{ webhook_events : "resolved to"

  %% Timeline and audit
  leads ||--o{ timeline_events : records
  leads ||--o{ notes : has
  leads ||--o{ attachments : stores
  tenants ||--o{ audit_log : audits
  tenants ||--o{ usage_daily : meters
```

Notation: `||--o{` one to many, `|o--o{` optional one to many, `|o--o|` optional one to one, `}o--o|` many to optional one.

## 3. Tenancy and access

A tenant is one client business; users come from Supabase Auth and get access to a tenant only through a membership row.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `tenants` | id, name, slug (text), industry (text), timezone (text, default 'Asia/Kolkata'), locale (text), brand\_tone (text), legal\_name (text), gstin (text), state\_code (char(2)), billing\_address (jsonb), approval\_mode (bool, default true), quiet\_hours (jsonb), status (text), plan\_id (uuid), activated\_at, created\_at, updated\_at | slug unique; status in ('onboarding','active','paused','suspended','closed'), default 'onboarding'; not active until paid or trial override. Platform-wide with RLS: `app_user` sees and inserts only `id = app.tenant_id`, and updates it only when `app.role = 'admin'`. `app_user` can write only the settings columns (name, industry, timezone, locale, brand\_tone, legal\_name, gstin, state\_code, billing\_address, approval\_mode, quiet\_hours); never status, plan\_id, activated\_at or slug. plan\_id gets its foreign key when `plans` exists (Phase 8). Checks: id is never the nil UUID (the tenant guard's startup placeholder); slug is 3–50 characters of lowercase letters and digits with single hyphens. Reserved slugs are refused by the backend |
| `user_profiles` | id (= auth.users.id), full\_name, phone, avatar\_path, is\_platform\_admin (bool), created\_at, deleted\_at | One row per Supabase Auth user; is\_platform\_admin only settable by Northstar (no insert or update privilege for `app_user`). Platform-wide with RLS: `app_user` reads its own profile and those of current-tenant members, and inserts or updates only its own. No foreign key to `auth.users` (`migrator` cannot reference it on Supabase); the service creates profiles only from the verified token's user ID |
| `memberships` | id, tenant\_id, user\_id, role (text), status (text), whatsapp\_phone (text), created\_at, updated\_at | unique (tenant\_id, user\_id); unique (tenant\_id, id); one active membership per user: unique (user\_id) where status = 'active' (D4; invited and deactivated rows don't count, and accepting an invite fails if the user is already active elsewhere); role in ('admin','staff'); status in ('invited','active','deactivated') ('invited' is unused for now: invites create the membership only when accepted, step 1.9); no delete. Insert, update and delete require `app.role = 'admin'` (restrictive policies); staff can read. Admins manage member records, including `whatsapp_phone`. `app_user` may update only role, status and whatsapp\_phone. A business always keeps an active admin (P14): the `keep_an_active_admin` triggers lock the tenant's admin changes (transaction advisory lock) and refuse, with SQLSTATE NSA01, any update or delete that would leave none; they require READ COMMITTED or SERIALIZABLE (NSA02 under REPEATABLE READ) |
| `invites` | id, tenant\_id, email (text), role, token\_hash (text), invited\_by (membership), created\_at, expires\_at, accepted\_at, revoked\_at | Only the token's hash is stored, unique across tenants; expires 7 days after creation; one pending invite per tenant and email: unique (tenant\_id, lower(email)) where accepted\_at and revoked\_at are null (plain text + lower(), since citext is not installed by default on Supabase); never both accepted and revoked. Insert, update and delete require `app.role = 'admin'` (restrictive policies); staff can read; acceptance goes through the allowlisted `northstar.accept_invite` function (section 12), which sets accepted\_at and creates or reactivates the membership. token\_hash starts as the hash of a throwaway token that is never sent; the invite email action sets the real one at send time through `northstar.set_invite_token` (section 12). `app_user` may update only revoked\_at |
| `staff_skills` | id, tenant\_id, membership\_id, skill (text), created\_at | unique (tenant\_id, membership\_id, skill); used by assignment rules; hard delete allowed |
| `staff_leave` | id, tenant\_id, membership\_id, starts\_on (date), ends\_on (date), reason (text), created\_by (membership), created\_at, cancelled\_at, cancelled\_by (membership) | ends\_on ≥ starts\_on; cancelled\_at and cancelled\_by set together; never deleted (cancel instead); overlapping appointments flagged on insert |

## 4. Contacts, conversations and messages

A contact is one phone number per tenant; it can have many leads over time but one ongoing WhatsApp conversation.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `contacts` | id, tenant\_id, normalized\_phone, name, email, is\_repeat\_customer (bool), consent\_state (text), opted\_in\_at, opt\_out\_at, attributes (jsonb), created\_at, updated\_at, deleted\_at | unique (tenant\_id, normalized\_phone); normalized\_phone is E.164; consent\_state in ('unknown','opted\_in','opted\_out'); no delete (soft delete) |
| `contact_sources` | id, tenant\_id, contact\_id, source (text), chat\_link\_id, ad\_referral\_id, first\_seen\_at | One row per distinct source a contact arrived from |
| `conversations` | id, tenant\_id, contact\_id, channel (text), whatsapp\_phone\_number\_id (text), status (text), ai\_paused (bool), assigned\_membership\_id, last\_inbound\_at, window\_expires\_at, needs\_human (bool), created\_at, updated\_at | unique (tenant\_id, contact\_id, channel); window\_expires\_at = last\_inbound\_at + 24 h |
| `messages` | id, tenant\_id, conversation\_id, direction (text), sender\_type (text), sender\_membership\_id, provider\_message\_id (text), type (text), body (text), media\_path, template\_id, status (text), error\_code, sent\_at, delivered\_at, read\_at, created\_at | unique (tenant\_id, provider\_message\_id); direction in ('in','out'); sender\_type in ('contact','ai','staff','system'); status in ('queued','sent','delivered','read','failed') |
| `message_templates` | id, tenant\_id, name, language, category (text), body, variables (jsonb), provider\_template\_id, status (text), approved\_at | unique (tenant\_id, name, language); status in ('draft','submitted','approved','rejected','paused'); category as returned by Meta |
| `faq_entries` | id, tenant\_id, question, answer, is\_active, updated\_by, updated\_at | Only source the AI may answer from |

## 5. Attribution

Every lead points to exactly one origin: a Meta ad referral, a chat-link click, or direct.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `chat_links` | id, tenant\_id, code (text), name, source (text), utm (jsonb), prefill\_text, qr\_path, is\_active, created\_by, created\_at | code unique platform-wide (used in `northstar.com/c/<code>`); source in ('website','email','instagram','facebook','other') |
| `link_clicks` | id, tenant\_id, chat\_link\_id, ref\_code (text), clicked\_at, page\_url, utm (jsonb), user\_agent\_hash, matched\_lead\_id | ref\_code unique; matched when the pre-filled message arrives; raw IP not stored |
| `ad_referrals` | id, tenant\_id, contact\_id, ctwa\_click\_id (text), source\_id (ad id), source\_type, source\_url, headline, campaign\_id, campaign\_name, adset\_id, ad\_name, received\_at | Filled from the WhatsApp referral payload; campaign names enriched from Marketing API |
| `ad_spend_daily` | id, tenant\_id, date, campaign\_id, adset\_id, ad\_id, spend\_paise, impressions, clicks, pulled\_at | unique (tenant\_id, date, ad\_id); for cost-per-qualified-lead reports |

## 6. Workflow and leads

The lead is the central record: it belongs to a contact, runs on one workflow version, and every other business table hangs off it.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `workflows` | id, tenant\_id, name, template (text), current\_version\_id, draft\_config (jsonb), created\_at, updated\_at | One active workflow per tenant in MVP |
| `workflow_versions` | id, tenant\_id, workflow\_id, version (int), config (jsonb), published\_by (membership, null when the system publishes), published\_at | unique (tenant\_id, workflow\_id, version); published versions only (drafts live in `workflows.draft_config`); immutable after insert: `app_user` may only insert and read (insert-only in the RLS release gate), and triggers refuse update, delete and truncate for every role, the owner included (SQLSTATE NSW01). A workflow's `current_version_id` must be one of its own versions (composite FK) |
| `leads` | id, tenant\_id, contact\_id, conversation\_id, workflow\_version\_id, stage (text), previous\_stage (text), stage\_entered\_at, origin (text), ad\_referral\_id, chat\_link\_id, service (text), event\_date (date), event\_time (time), location (text), location\_geo (point), budget\_paise (bigint), requirements (jsonb), score (smallint), band (text), score\_reasons (jsonb), junk\_rule\_id (text), lost\_reason (text), assigned\_membership\_id, amount\_due\_paise (bigint), version (int), created\_at, updated\_at, closed\_at, deleted\_at | stage must exist in the version config (checked by the engine, not the database); band in ('hot','warm','cold','junk'); origin in ('meta\_ad','chat\_link','direct','manual'); score 0–100; one open lead per contact (section 13), and every closing stage (Junk, Lost, Completed) sets closed\_at; staff see only their assigned leads (section 12); no delete (P6 soft delete). conversation\_id, ad\_referral\_id and chat\_link\_id get their composite FKs with their tables (steps 2.3 and 7.1) |
| `lead_fields` | id, tenant\_id, lead\_id, field (text), value (jsonb), confidence (numeric(3,2)), source\_message\_id, set\_by (text), created\_at | Every extracted/edited field value with provenance; latest per field = current |
| `lead_score_history` | id, tenant\_id, lead\_id, score, band, reasons (jsonb), model, prompt\_version, created\_at | Append-only |
| `events` | id, tenant\_id, lead\_id, type (text), dedupe\_key (text), payload (jsonb), received\_at, processed\_at | unique (tenant\_id, dedupe\_key); engine input log |
| `actions` | id, tenant\_id, lead\_id (nullable), type (text), status (text), idempotency\_key (text), mode (text), payload (jsonb), result (jsonb), attempts (int), next\_attempt\_at, lease\_expires\_at, last\_attempt\_at, last\_error\_class (text), last\_error\_code (text), approver\_membership\_id, approved\_at, stage\_entry\_id, created\_at, updated\_at | unique (tenant\_id, idempotency\_key); status in ('pending','processing','succeeded','failed','needs\_review','paused','skipped','cancelled'); mode in ('approval','automatic'); last\_error\_class in ('retryable','permanent','auth') and last\_error\_code a fixed code (never message text, tokens or personal data), both set or both null. lead\_id is null for actions with no lead (invite emails, admin alerts); its foreign key to leads arrives with leads (step 1.13). stage\_entry\_id is unlinked until stage entries are defined (Phase 3). attempts counts attempts that use the retry budget (an auth pause gives its attempt back); a pending action always has next\_attempt\_at; lease\_expires\_at is set exactly while processing. Written once except the lifecycle columns (status, result, attempts, next\_attempt\_at, lease\_expires\_at, last\_attempt\_at, last\_error\_class, last\_error\_code, approver\_membership\_id, approved\_at); never deleted |
| `timers` | id, tenant\_id, lead\_id, type (text), fire\_at, job\_id (text), status (text), created\_at | Mirror of scheduled JobRunr jobs so the engine can cancel them; status in ('scheduled','fired','cancelled') |

JobRunr's `jobrunr_*` tables live in the separate `jobrunr` schema; they hold no tenant data beyond IDs in job arguments. Flyway creates them as `migrator` (JobRunr's own table creation is off), mirroring the schema the pinned JobRunr version builds, and only `jobrunr_user` may use them (section 12).

## 7. Commercial

Quotes are versioned, payments form a ledger keyed by Razorpay IDs, and invoices are numbered per financial year and never deleted.

`payments`, `invoices` and `refunds` are lifecycle tables (section 12): only their declared status and processing columns can be updated. Phase 5 adds a trigger on each that allows only forward status transitions, so a status can never move back.

Invoices, receipt vouchers and credit notes store a snapshot of the supplier's legal name, GSTIN, state code and address at the moment of issue, because those `tenants` columns stay editable (Phase 5).

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `catalogue_items` | id, tenant\_id, name, description, unit\_price\_paise, hsn\_sac (text), tax\_rate (numeric(5,2)), is\_active, created\_at, updated\_at, deleted\_at | Changing a price never changes sent quotes |
| `quotes` | id, tenant\_id, lead\_id, current\_version\_id, status (text), created\_at | status in ('open','accepted','closed') |
| `quote_versions` | id, tenant\_id, quote\_id, version (int), subtotal\_paise, discount\_paise, tax\_paise, total\_paise, valid\_until (date), status (text), discount\_approved\_by, pdf\_path, sent\_at, accepted\_at, created\_by, created\_at | unique (quote\_id, version); status in ('draft','needs\_review','sent','accepted','changes\_requested','superseded','expired'); only latest sent version acceptable |
| `quote_lines` | id, tenant\_id, quote\_version\_id, catalogue\_item\_id, description, qty (numeric), unit\_price\_paise, hsn\_sac, tax\_rate, line\_total\_paise | Copied values, not references, so history stays exact |
| `payments` | id, tenant\_id, lead\_id, quote\_version\_id, kind (text), amount\_paise, status (text), razorpay\_link\_id, razorpay\_link\_url, razorpay\_payment\_id, link\_expires\_at, paid\_at, failure\_reason, created\_at, updated\_at | unique (tenant\_id, razorpay\_payment\_id) where not null; kind in ('full','advance','partial','balance','fee'); status in ('pending','paid','failed','expired','refunded','partially\_refunded','refund\_due') |
| `invoice_sequences` | id, tenant\_id, financial\_year (text, e.g. '2026-27'), prefix, next\_number (int) | unique (tenant\_id, financial\_year); incremented in the same transaction as invoice insert, so no gaps |
| `invoices` | id, tenant\_id, lead\_id, payment\_id, number (text), financial\_year, issue\_date, place\_of\_supply (char(2)), supplier\_state (char(2)), subtotal\_paise, cgst\_paise, sgst\_paise, igst\_paise, total\_paise, status (text), template\_id, pdf\_path, sent\_at, cancelled\_at, created\_at | unique (tenant\_id, number); status in ('draft','issued','partially\_paid','paid','overdue','cancelled'); no delete grant |
| `invoice_lines` | id, tenant\_id, invoice\_id, description, hsn\_sac, qty, unit\_price\_paise, tax\_rate, taxable\_paise, tax\_paise | Copied values |
| `credit_notes` | id, tenant\_id, invoice\_id, number, reason, amount\_paise, tax\_paise, issue\_date, pdf\_path, created\_by | unique (tenant\_id, number) |
| `refunds` | id, tenant\_id, payment\_id, amount\_paise, policy\_snapshot (jsonb), status (text), requested\_by, approved\_by, approved\_at, razorpay\_refund\_id, processed\_at, credit\_note\_id | status in ('requested','approved','rejected','processing','processed','failed') |
| `invoice_templates` | id, tenant\_id, name, docx\_path, field\_mapping (jsonb), is\_default, created\_by, created\_at | One default per tenant (partial unique index) |
| `cancellation_policies` | id, tenant\_id, rules (jsonb), is\_active, updated\_at | e.g. hours-before thresholds → refund % |

## 8. Scheduling

The platform's appointments table is the master schedule; Google Calendar event IDs are stored alongside so every change can be mirrored.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `calendar_connections` | id, tenant\_id, membership\_id (null = business calendar), google\_calendar\_id, credential\_id, status (text), last\_synced\_at | status in ('connected','error','disconnected') |
| `busy_blocks` | id, tenant\_id, membership\_id, starts\_at, ends\_at, source (text), google\_event\_id, is\_private (bool), synced\_at | Free/busy cache from Google; private events stored as busy only, no title |
| `slot_holds` | id, tenant\_id, lead\_id, membership\_id, starts\_at, ends\_at, payment\_id, expires\_at, status (text) | status in ('held','converted','released'); exclusion constraint prevents overlapping active holds per staff |
| `appointments` | id, tenant\_id, lead\_id, kind (text), starts\_at, ends\_at, location, assigned\_membership\_id, status (text), google\_event\_ids (jsonb), version (int), created\_at, updated\_at, cancelled\_at | kind in ('call','site\_visit','job','meeting'); status in ('scheduled','in\_progress','done','cancelled','rescheduled'); exclusion constraint on (assigned\_membership\_id, tstzrange) for active rows |
| `appointment_assignments` | id, tenant\_id, appointment\_id, from\_membership\_id, to\_membership\_id, reason (text), changed\_by, changed\_at | Append-only reassignment history |
| `tasks` | id, tenant\_id, lead\_id, appointment\_id, title, owner\_membership\_id, due\_at, status (text), completed\_at, created\_by, created\_at | status in ('open','done','overdue','cancelled') |

Staff leave lives in `staff_leave` (section 3).

## 9. Integrations, sync and webhooks

Credentials sit in a server-only table that tenants can never read; everything leaving the platform for Sheets or Meta goes through an outbox.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `integrations` | id, tenant\_id, provider (text), status (text), account\_label, scopes (text\[\]), credential\_id, last\_success\_at, last\_error, last\_error\_at, created\_at, updated\_at | unique (tenant\_id, provider) except calendars; provider in ('whatsapp','razorpay','google','meta\_ads','meta\_capi') |
| `integration_credentials` | id, tenant\_id, provider, encrypted\_secret (bytea), key\_id (text), expires\_at, rotated\_at | **Private schema**, no RLS access for app users; read only by the backend service role; encrypted with a managed key |
| `whatsapp_accounts` | id, tenant\_id, waba\_id, phone\_number\_id, display\_phone, display\_name, quality\_rating, messaging\_limit, verified\_at | unique (phone\_number\_id) platform-wide; routes inbound webhooks to the tenant |
| `sheet_mappings` | id, tenant\_id, spreadsheet\_id, tab (text), entity (text), field\_map (jsonb), inbound\_fields (text\[\]), last\_synced\_at, status | entity in ('leads','payments') |
| `sync_outbox` | id, tenant\_id, target (text), entity, entity\_id, operation (text), payload (jsonb), status (text), attempts, next\_attempt\_at, last\_error, created\_at | target in ('sheets','meta\_capi'); processed in batches by the worker |
| `sheet_conflicts` | id, tenant\_id, entity\_id, field, sheet\_value, platform\_value, detected\_at, resolved\_by, resolved\_at | Review list for protected-field edits |
| `meta_events` | id, tenant\_id, lead\_id, event\_name (text), event\_id (text), value\_paise, ctwa\_click\_id, sent\_at, response (jsonb), status | unique (tenant\_id, lead\_id, event\_name); Meta does not deduplicate business-messaging events, so Northstar sends each once |
| `webhook_events` | id, provider, provider\_event\_id, tenant\_id (nullable until resolved), signature\_valid (bool), payload (jsonb), received\_at, processed\_at, error | unique (provider, provider\_event\_id); append-only; tenant resolved from phone\_number\_id / account |

## 10. Timeline, audit and usage

The customer timeline is one append-only table that every module writes to, so the dashboard shows a lead's whole history from a single query.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `timeline_events` | id, tenant\_id, lead\_id, contact\_id, type (text), actor\_type (text), actor\_membership\_id, ref\_table (text), ref\_id (uuid), summary (text), data (jsonb), occurred\_at | Append-only; actor\_type in ('ai','system','staff','contact'); types include stage\_changed, message, quote\_sent, payment\_paid, invoice\_issued, appointment\_set, reassigned, approval, call\_logged, note, delivery |
| `notes` | id, tenant\_id, lead\_id, body, author\_membership\_id, created\_at | Staff notes and logged calls (with `kind` = 'note' or 'call') |
| `attachments` | id, tenant\_id, lead\_id, path, mime\_type, size\_bytes, kind (text), uploaded\_by, created\_at | Delivery evidence, documents; files in Supabase Storage under `tenant_id/` prefix |
| `audit_log` | id, tenant\_id, actor\_user\_id, action (text), target\_table, target\_id, metadata (jsonb), ip\_hash, created\_at | Settings, roles, deletes, exports, approvals, force moves; append-only. IDs and fixed codes only, never names, emails or phones; a change records `{"from", "to"}` codes in metadata (e.g. `member.role_changed`, `member.deactivated`, `member.reactivated`) |
| `usage_daily` | id, tenant\_id, date, messages\_in, messages\_out, templates\_sent, ai\_calls, ai\_input\_tokens, ai\_output\_tokens, storage\_bytes, pdfs\_generated | unique (tenant\_id, date); feeds plan limits and the admin panel |

## 11. Platform billing

These tables track Northstar's own subscription with each business, separate from the business's payments from its customers.

| Table | Columns (type) | Constraints / notes |
| --- | --- | --- |
| `plans` | id, code (text), name, price\_paise, billing\_period (text), limits (jsonb: messages, ai\_calls, staff\_seats, storage\_gb), is\_active | Platform-wide, no tenant\_id; readable by all, writable by Northstar admin only |
| `subscriptions` | id, tenant\_id, plan\_id, status (text), current\_period\_start, current\_period\_end, trial\_ends\_at, grace\_ends\_at, setup\_fee\_paise, created\_at, updated\_at | status in ('trial','active','past\_due','grace','read\_only','cancelled') |
| `subscription_invoices` | id, tenant\_id, subscription\_id, number, period\_start, period\_end, amount\_paise, gst\_paise, status, razorpay\_payment\_id, pdf\_path, issued\_at, paid\_at | Northstar's invoices to the business |
| `usage_alerts` | id, tenant\_id, metric (text), threshold\_pct (int), notified\_at | Alerts at 70%, 90%, 100% of plan limits |

## 12. Row Level Security and tenant isolation

The React app never queries tables directly; Spring Boot connects as a restricted role and sets the tenant on every transaction, and RLS rejects any row from another tenant even if application code has a bug.

**Roles**

| Role | Used by | Can bypass RLS |
| --- | --- | --- |
| `app_user` | Spring Boot API and worker for all tenant work | No |
| `app_admin` | Northstar admin panel queries across tenants (separate code path, audited) | Yes, read-mostly |
| `migrator` | Flyway only | Yes |
| `jobrunr_user` | JobRunr's private connection pool; may use only the `jobrunr` schema, nothing in `northstar` | No |
| Supabase `service_role` key | Never in the app runtime; kept for emergencies | Yes |

**Role setup and privileges**

- The four roles are created once per environment by `db/bootstrap/roles.sql`, run as `postgres`, because Flyway must log in as `migrator` and roles belong to the whole database server. Logins and passwords are set per environment with psql's `\password`; no password is stored in the repo.
- `migrator` owns the `northstar` and `jobrunr` schemas and every object in them, and every Flyway migration runs as `migrator` in every environment, tests included.
- Default privileges on new tables: `app_user` gets select, insert and update; `app_admin` gets select. Delete is never a default: a table that allows hard deletes grants it to `app_user` in its own migration, and financial records and append-only tables never do. JobRunr's tables are outside these defaults: only `jobrunr_user` has privileges on them (full DML, as JobRunr deletes its own finished jobs), and `app_user` and `app_admin` have none. The RLS release gate checks both directions.
- Neither app role can read or change `flyway_schema_history`.

**Per-transaction context** (set by a Spring interceptor from the verified Supabase token and membership lookup; workers set it from the job's tenant\_id):

```sql
select set_config('app.tenant_id', :tenant_id, true);   -- true = local to this transaction
select set_config('app.user_id',   :user_id,   true);
select set_config('app.role',      :role,      true);
```

How the backend enforces it (step 1.4):

- A tenant-aware transaction manager runs these with bind parameters on the transaction's own connection as the transaction begins, read-only transactions included. Without a tenant context, the transaction is refused before anything opens.
- The app's datasource is wrapped in a guard: a connection refuses any statement until a tenant transaction has begun on it, and commit, rollback and close reset it. A query outside a tenant transaction therefore throws `MissingTenantContextException` before it reaches Postgres; it never returns an empty result. The one exception is Hibernate's schema validation at startup, allowed on the startup thread only and closed before the web server accepts requests.
- Every repository call requires a tenant context first, and Hibernate's `@TenantId` filters tenant entities from the same context, on top of RLS.
- At login the tenant is not yet known. `northstar.current_membership()` (`SECURITY DEFINER`, owned by `migrator`, no arguments, `search_path` pinned, executable by `app_user` only) returns the active membership of `app.user_id` and nothing else. The backend calls it on every request in a lookup mode that sets only `app.user_id` and allows that one statement; the tenant, user and role it returns become the request's context. It is never cached, so deactivation takes effect on the next request (P13).
- Creating a business needs to know whether a slug is used by any tenant, which RLS hides. `northstar.slug_is_taken(candidate text)` (`SECURITY DEFINER`, owned by `migrator`, `search_path` pinned, executable by `app_user` only) answers yes or no and nothing else.
- Accepting an invite happens before the invitee has a tenant. `northstar.accept_invite(p_token_hash text, p_email text)` (`SECURITY DEFINER`, owned by `migrator`, `search_path` pinned, executable by `app_user` only) runs in an acceptance mode that sets only `app.user_id` and allows that one statement. Like the two functions above it relies on `migrator`'s BYPASSRLS, since RLS is forced on these tables. It locks the invite row by token hash and checks, in this order: not found, email mismatch (case-insensitive, against the token's email), used, revoked, expired, and an active membership elsewhere (D4), so a wrong account learns nothing about the invite's state. On success it writes only in the invite's own tenant: the user's profile if new, a new active membership or the reactivated deactivated one with the invite's role, `accepted_at`, and an `invite.accepted` audit entry (metadata `{"from": "deactivated", "to": "active"}` on reactivation, none otherwise). It returns an outcome code plus, on success, the tenant, membership and role; the D4 unique-index error inside it becomes the `active_elsewhere` outcome. Revoke and accept both lock the invite row, so a revoke racing an accept serialises.
- The invite email (step 1.12) sets the invite's token at send time, as the system actor, which the admin-only policies deny. `northstar.set_invite_token(p_invite_id uuid, p_token_hash text)` (`SECURITY DEFINER`, owned by `migrator`, `search_path` pinned, executable by `app_user` only) sets `token_hash` only when `app.role = 'system'`, only on an invite in `app.tenant_id` that is neither accepted, revoked nor expired, and only to a well-formed SHA-256 hash; it returns whether it changed a row and nothing else. The raw token is derived in the worker and exists only in the email.
- The job sweeper (step 1.10) starts without a tenant. `northstar.tenants_with_due_actions()` (`SECURITY DEFINER`, owned by `migrator`, no arguments, `search_path` pinned, executable by `app_user` only) returns the IDs of tenants with actions due to run and nothing else; it runs in a scan mode that sets no context and allows that one statement. Each tenant's actions are then read inside that tenant's scope.
- Workers run each job inside its tenant's scope as the **system actor**: `app.role = 'system'` and `app.user_id` set to the nil UUID, which no profile or membership may have (check constraints). The system actor is created only in the jobs package and refused on any thread serving a web request. The admin-only restrictive policies deny it, so workers cannot change memberships, invites or tenant settings; audit entries it writes name no user. A job with no tenant, or with a tenant that does not exist, fails before any query (P10).
- The context is not inherited by other threads, virtual threads or async tasks, and it is cleared after every unit of work.

**Policy applied to every tenant table** (generated by a migration helper so none is missed):

```sql
alter table leads enable row level security;
alter table leads force row level security;

create policy tenant_isolation on leads
  for all to app_user
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

**Staff visibility** (staff see only their own leads and appointments) is enforced in the service layer from the membership role, with an additional policy on `leads`, `appointments`, `conversations` checking `assigned_membership_id` when `app.role = 'staff'`. The policy on `leads` (step 1.13) is restrictive, so it only narrows `tenant_isolation`, and the RLS release gate holds its exact text; `conversations` and `appointments` get the same pattern:

```sql
create policy leads_staff_assigned on leads
  as restrictive for all to app_user
  using (
    current_setting('app.role', true) in ('admin', 'system')
    or (current_setting('app.role', true) = 'staff'
        and assigned_membership_id = (
          select m.id from memberships m
          where m.tenant_id = current_setting('app.tenant_id', true)::uuid
            and m.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            and m.status = 'active')))
  with check ( /* the same expression */ );
```

Admins and the system actor see the tenant's leads. Staff see and change only leads assigned to their own active membership: they cannot take a colleague's lead, hand their own to someone else, or create an unassigned one. A missing or unknown role sees nothing.

**Release gates**

- A CI test (the RLS release gate) scans a declared list of Northstar schemas (`northstar`, plus any schema a later phase adopts) and fails the build if:
  - a table with `tenant_id` has it nullable or not `uuid`, has RLS not enabled or not forced, or lacks the `tenant_isolation` policy exactly as written above;
  - any other policy on a tenant table is permissive, or is not declared with its exact text (extra policies, such as the admin-only write policies and the staff policy, must be restrictive, because permissive policies combine with OR);
  - a table without `tenant_id` is not declared platform-wide (for example `tenants`, `user_profiles`, `plans`, JobRunr's tables);
  - a view lacks `security_invoker`, or a materialized view exists (both would read with the owner's rights);
  - a `SECURITY DEFINER` function is not on the declared allowlist;
  - an append-only table gives `app_user` more than the rules below allow.
- Append-only tables come in two tiers:
  - **Insert-only** (`workflow_versions`, `timeline_events`, `audit_log`, `lead_score_history`, `credit_notes`): `app_user` has no update, delete or truncate.
  - **Lifecycle** (`webhook_events`, `payments`, `invoices`, `refunds`): no delete or truncate, and no table-level update. The table's migration revokes table-level update first, then grants column-level update on a declared list of status and processing columns only; the gate fails if any other column is updatable. Amounts, numbers, lines and parties never change.
- A cross-tenant test creates two tenants and asserts zero rows leak through API, jobs, exports and webhooks.
- No missing `app.tenant_id` ever returns rows: `current_setting(..., true)` returns null, so the policy matches nothing.

**Storage:** private buckets; object paths start with `tenant_id/`; files served only through short-lived signed URLs created by the backend.

## 13. Key indexes and constraints

Every index starts with `tenant_id`; the unique constraints below are what make webhooks, jobs and payments duplicate-safe.

**Duplicate-safety constraints**

| Constraint | Prevents |
| --- | --- |
| `webhook_events (provider, provider_event_id)` unique | Same webhook processed twice |
| `messages (tenant_id, provider_message_id)` unique | Duplicate stored message |
| `contacts (tenant_id, normalized_phone)` unique | Duplicate contact |
| `events (tenant_id, dedupe_key)` unique | Same engine event applied twice |
| `actions (tenant_id, idempotency_key)` unique | Same quote, link, invoice or message sent twice |
| `payments (tenant_id, razorpay_payment_id)` unique | Double ledger entry |
| `invoices (tenant_id, number)` unique + sequence row lock | Duplicate or skipped invoice numbers |
| `meta_events (tenant_id, lead_id, event_name)` unique | Duplicate conversion events |
| Exclusion on `appointments` / `slot_holds` per staff time range | Double-booking a staff member |
| Partial unique: one open lead per contact (`leads (tenant_id, contact_id) where closed_at is null and deleted_at is null`) | Two active leads for one person |
| Partial unique: one active membership per user (`memberships (user_id) where status = 'active'`; the one index not led by `tenant_id`, because it spans tenants by design) | A user active in two businesses (D4) |
| Partial unique: one pending invite per email (`invites (tenant_id, lower(email)) where accepted_at is null and revoked_at is null`) | Duplicate pending invites |
| `invites (token_hash)` unique | Two invites sharing a token |

**Query indexes**

- `leads (tenant_id, stage, updated_at desc)`: pipeline board and filters
- `leads (tenant_id, assigned_membership_id, stage)`: staff views
- `leads (tenant_id, band, created_at desc)`: score dashboard
- `leads (tenant_id, origin, ad_referral_id)` and `(tenant_id, chat_link_id)`: source reports
- `messages (tenant_id, conversation_id, created_at desc)`: inbox
- `timeline_events (tenant_id, lead_id, occurred_at desc)`: customer timeline
- `actions (tenant_id, status, next_attempt_at)`: worker pickup and Failed Actions screen
- `appointments (tenant_id, assigned_membership_id, starts_at)`: calendars
- `payments (tenant_id, status, link_expires_at)`: expiry and reminders
- `sync_outbox (status, next_attempt_at)`: sync worker

## 14. Data retention

These are proposed defaults to confirm with legal and tax advisers; a nightly job applies them per tenant.

| Data | Keep for | Then |
| --- | --- | --- |
| Message bodies and media | 12 months after last activity on the conversation | Body and media removed; metadata kept for reporting |
| Raw webhook payloads | 30 days | Payload removed; row with IDs kept for dedupe |
| Leads, timeline, quotes, appointments | Life of the subscription | Exported and deleted on tenant closure after a 90-day grace |
| Invoices, credit notes, payments, refunds | As required by GST record-keeping rules (commonly 72 months; CA to confirm) | Archived read-only; never hard-deleted before the period ends |
| Audit log | 3 years | Deleted |
| Usage counters | 3 years | Aggregated monthly |
| Backups | Daily, kept 7 days (Supabase Pro default) + monthly snapshot kept 12 months | Expire automatically |

**Deletion requests:** a contact's personal data can be erased on verified request; financial records keep the minimum fields the law requires, with the contact's name replaced by a reference.

## 15. Open questions

- [x] Can a contact have more than one open lead at a time (e.g. two separate shoots)? No: one open lead per contact, enforced by the partial unique index in section 13; Junk, Lost and Completed set `closed_at`, so a returning contact starts a new lead (W2) (decided 13 Nov 2026).
- [x] Should staff log in with phone OTP as well as email? No: email only for MVP (decided 2 Nov 2026).
- [ ] Retention periods in section 14: confirm with legal and CA
- [ ] Store message bodies encrypted at the column level, or rely on database encryption at rest?
- [ ] Multiple workflows per tenant (e.g. separate flows for two service lines) in MVP, or one?
- [ ] Invoice numbering format per tenant (prefix, financial-year reset) confirmed with each client's CA
