-- Step 1.7: creating a business. Runs as migrator.

-- The tenant guard's startup placeholder uses the nil UUID (TenantIdentifierResolver.NO_TENANT), so no real tenant may.
alter table northstar.tenants
  add constraint tenants_id_not_nil check (id <> '00000000-0000-0000-0000-000000000000');

-- Slugs appear in chat links and URLs: 3 to 50 characters, lowercase letters and digits, single hyphens between them.
-- The backend validates first; this is the backstop.
alter table northstar.tenants
  add constraint tenants_slug_format check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(slug) between 3 and 50);

-- Whether a slug is already used by any tenant. RLS shows app_user only its own tenant, so checking a new slug before
-- inserting needs this SECURITY DEFINER function, owned by migrator. It answers yes or no and nothing else; a slug's
-- existence is public anyway through chat links. search_path is pinned; only app_user may call it, and the RLS
-- release gate allowlists it.
create function northstar.slug_is_taken(candidate text)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select exists (select from northstar.tenants t where t.slug = candidate)
$$;

revoke all on function northstar.slug_is_taken(text) from public, anon, authenticated;
grant execute on function northstar.slug_is_taken(text) to app_user;
