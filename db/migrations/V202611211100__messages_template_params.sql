-- Step 2.6a: a template message's parameter values (data-model.md section 4, integration-specs.md section 2). The send
-- action's payload carries the message ID only, never text, so the values a template is filled with live on the
-- message, next to body, where retention clears them (data-model.md section 14, step 8.6).
--
-- Set only on a template message: a JSON array of strings (positional {{1}}, {{2}} ...) or a JSON object of strings
-- (named {{first_name}} ...), as Meta's send request takes them, and at most 8 KB as a backstop. Strict mode, so an
-- array inside the array is a non-string element rather than unwrapped; silent, so the branch for the other JSON type
-- is false rather than an error.
--
-- Written once, like body: app_user may insert it and never update it. app_admin cannot select it (data-model.md
-- section 15): the messages table's select for app_admin is granted column by column, and this migration grants it
-- nothing.
alter table northstar.messages add column template_params jsonb;

alter table northstar.messages add constraint messages_template_params check (
  template_params is null
  or (type = 'template'
      and octet_length(template_params::text) <= 8192
      and ((jsonb_typeof(template_params) = 'array'
            and not jsonb_path_exists(template_params, 'strict $[*] ? (@.type() != "string")', '{}', true))
        or (jsonb_typeof(template_params) = 'object'
            and not jsonb_path_exists(template_params, 'strict $.* ? (@.type() != "string")', '{}', true)))));

grant insert (template_params) on northstar.messages to app_user;
