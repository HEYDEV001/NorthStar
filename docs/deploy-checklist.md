# Deploy checklist

Settings every hosted environment (staging, production) must have before the API serves traffic. Local
development gets the same settings from `supabase/config.toml` and `.env.local`. Check each item when an
environment is created and after any change to its Supabase project or app configuration.

## Supabase Auth

- [ ] Supabase email confirmation must be ON in every hosted environment; invite acceptance trusts the token's email.

## Database

- [ ] `db/bootstrap/roles.sql` has run and been verified on the project (hosted `postgres` is not a full superuser).
- [ ] Flyway uses a direct or session-pooler connection; the app uses the transaction pooler with `prepareThreshold=0`.

## App configuration

- [ ] `NORTHSTAR_APP_URL` is set to the environment's frontend address; invite links are built from it.
