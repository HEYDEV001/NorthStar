# Phase 2 gate runbook

How to prove Phase 2 on real WhatsApp (step 2.11): "Real message in, reply out; duplicate webhook creates nothing"
(`backend-roadmap.md`, `build-document.md` section 19), and with it that an inbound message creates one contact and
lead, staff can take over, opt-out stops automated messages, and the window and template rules hold. Staging is paused,
so the gate runs on one Mac: local Supabase, the API and the worker, Meta's free test number, and a Cloudflare quick
tunnel, which needs no account. Allow about an hour, most of it in Meta's dashboard the first time.

```
your phone ⇄ WhatsApp ⇄ Meta ── webhooks ──▶ quick tunnel ─▶ filter :8088 ─▶ API :8080 ─▶ local Postgres
                          ▲                                                                    │
                          └──────────────── sends (Graph API) ◀── worker :8081 ◀── jobs ───────┘
```

Run every command from the repo root in zsh, unless it says otherwise. Keep one terminal for the checks: the variables
and helpers below live only in it.

## Rules for this run

- **Secrets never reach the screen, the shell history, another program's arguments, or a chat.** They are typed at
  prompts that show nothing, kept in `.env.local` (mode 600, never committed) or in one shell variable, and passed on
  through standard input. Do not paste one into a command.
- **Pause every clipboard-history app** (Raycast, Alfred, Maccy, Paste and the like) before the first secret, and clear
  the clipboard after each with `pbcopy < /dev/null`.
- **The tunnel reaches only the webhook.** A quick tunnel publishes everything on the port it points at, and local
  Supabase signs tokens with a key built into its CLI, so anyone could make a token the local API accepts. The filter
  (`scripts/local/webhook-only.conf`) passes GET and POST on `/webhooks/whatsapp` and answers 404 to everything else.
  Never point the tunnel at 8080.
- **Run the real app:** `spring-boot:run`, never `spring-boot:test-run`, whose class path holds the tests' fake WhatsApp
  sender.
- **Opt-out is final.** Opting back in does not exist yet, so STOP is only ever sent from a synthetic number (below),
  never from your phone, and the gate can be repeated without resetting the database.

## 1. Tools and database

Docker Desktop, the Supabase CLI, JDK 21 and jq are already in use here. The tunnel needs one more tool:
`brew install cloudflared`.

```
supabase start
docker exec -i supabase_db_NorthStar psql -U postgres -d postgres < db/bootstrap/verify.sql   # every line PASS
cd backend && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./mvnw -q initialize flyway:migrate && cd ..
```

If `verify.sql` prints a FAIL, or the roles are new, do `db/bootstrap/README.md` (Local Supabase) first.

`.env.local` needs, besides the database settings: `SECRETS_DB_USER` and `SECRETS_DB_PASSWORD`,
`NORTHSTAR_CREDENTIAL_KEYS` and `NORTHSTAR_CREDENTIAL_ACTIVE_KEY` (a key for this machine only, never a staging one),
and the worker's email settings (`NORTHSTAR_EMAIL_PROVIDER=mailpit`, `NORTHSTAR_EMAIL_FROM`,
`NORTHSTAR_INVITE_TOKEN_KEY`). `.env.example` describes each; section 2 has a helper that makes the generated ones.

## 2. WhatsApp secrets into .env.local

`NORTHSTAR_WHATSAPP_APP_SECRET` signs every delivery; `NORTHSTAR_WHATSAPP_VERIFY_TOKEN` answers Meta's handshake. Define
these two helpers; they write the file and print only the setting's name:

```
save_env() {   # save_env NAME VALUE: replaces NAME's line in .env.local
  ( umask 077; { grep -v "^$1=" .env.local; printf '%s=%s\n' "$1" "$2"; } > .env.local.new ) && mv .env.local.new .env.local
  chmod 600 .env.local; echo "$1 saved"
}
put_secret() { # put_secret NAME: asks for the value without showing it
  local value; printf '%s (not shown): ' "$1"; IFS= read -rs value; echo
  [ -n "$value" ] && save_env "$1" "$value"
}
```

1. **App secret.** Meta App Dashboard → App settings → Basic → App secret → Show, copy. Then `put_secret
   NORTHSTAR_WHATSAPP_APP_SECRET`, paste, Return. `pbcopy < /dev/null`.
2. **Verify token.** Make one; nobody needs to see it:
   `save_env NORTHSTAR_WHATSAPP_VERIFY_TOKEN "$(openssl rand -hex 32)"`
3. **Only if it is missing** (`grep -c '^NORTHSTAR_CREDENTIAL_KEYS=.' .env.local` prints 0), a key for stored tokens. Never
   replace one that is there: every token stored under it becomes unreadable.
   `save_env NORTHSTAR_CREDENTIAL_KEYS "local-1:$(openssl rand -base64 32)"` and
   `save_env NORTHSTAR_CREDENTIAL_ACTIVE_KEY local-1`. The same way for `NORTHSTAR_INVITE_TOKEN_KEY`
   (`openssl rand -base64 32`).

## 3. The API and the worker

Two terminals, both in `backend/` with `JAVA_HOME` as above. Start the second once the first is up. Both listen on this
machine only; on a Mac, Docker's `host.docker.internal` reaches the API there.

```
NORTHSTAR_WORKER_ENABLED=false ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
NORTHSTAR_WORKER_ENABLED=true ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=8081"
```

```
curl -s http://127.0.0.1:8080/actuator/health; echo   # {"groups":["liveness","readiness"],"status":"UP"}
curl -s http://127.0.0.1:8081/actuator/health; echo
```

The API refuses to start, naming the setting, if a WhatsApp secret is missing. The worker picks up jobs within 15
seconds, so wait that long before reading a result.

## 4. The filter and the tunnel

Two more terminals, from the repo root:

```
scripts/local/gate filter
cloudflared tunnel --url http://127.0.0.1:8088
```

The first `filter` downloads nginx (pinned by version and digest). cloudflared prints an address ending in
`trycloudflare.com`. It is new on every run and stops working when cloudflared
does. In the checks terminal:

```
TUNNEL=https://<the address>
code() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }

code "$TUNNEL/api/v1/me"                                     # 404: the API is not reachable through the tunnel
code "$TUNNEL/actuator/health"                               # 404
code -X DELETE "$TUNNEL/webhooks/whatsapp"                   # 404: only GET and POST pass
code "$TUNNEL/webhooks/whatsapp"                             # 403: the API answered, and refuses a handshake without the token
code -X POST -H 'Content-Type: application/json' -d '{}' "$TUNNEL/webhooks/whatsapp"   # 401: an unsigned delivery (P15)
code http://127.0.0.1:8080/api/v1/me                         # 401: the API itself, on this machine only
```

**Stop here if `/api/v1/me` through the tunnel is anything but 404.**

## 5. Meta

In the App Dashboard, open the test app (the one with Meta's free test number): WhatsApp in the left menu, or, for an app
made with the "Connect with customers through WhatsApp" use case, Use cases → Customize. Meta's documentation calls the
WhatsApp Business Account the "Messaging account" in places. Menus move; what matters is named here.

1. **API Setup:** note the test number's Phone number ID and the WhatsApp Business Account ID, and add your own phone under
   "To" (Meta may send it a code). Expect the test number to write only to numbers on that list; the pages below do not
   say so, or how many it holds, so check it there.
2. **Access token:** on the same page, Generate access token. It is a temporary token, which stops by itself in under 24
   hours (Meta: it "expires quickly and is not suitable for development purposes"). That is what this run wants. Keep it
   in one variable, typed unseen:

   ```
   PHONE_NUMBER_ID=<Phone number ID>; WABA_ID=<WhatsApp Business Account ID>; DISPLAY_NUMBER=<the test number, +1555...>
   read -rs "META_TOKEN?Meta access token (not shown): "; echo; pbcopy < /dev/null
   ```

3. **Callback URL:** WhatsApp → Configuration (Webhooks). Callback URL: what `echo "$TUNNEL/webhooks/whatsapp"` prints.
   Verify token: put it on the clipboard with the line below, paste it, Verify and save, then clear the clipboard. The
   API's log shows `webhook.verification provider=whatsapp status=200`.

   ```
   sed -n 's/^NORTHSTAR_WHATSAPP_VERIFY_TOKEN=//p' .env.local | tr -d '\n' | pbcopy
   ```

4. **Field:** on the same page, subscribe to `messages`. It carries delivery statuses too. (Its Test link sends a made-up
   number's message, which the API parks as `webhook.unknown_account`: harmless, and not a check.)
5. **The account:** Meta needs each WhatsApp Business Account subscribed to the app as well. The token goes through
   standard input, never into curl's arguments:

   ```
   graph() { printf 'header = "Authorization: Bearer %s"\n' "$META_TOKEN" | curl -sS -K - "$@"; echo; }
   graph -X POST "https://graph.facebook.com/v26.0/$WABA_ID/subscribed_apps"   # {"success":true}
   graph "https://graph.facebook.com/v26.0/$WABA_ID/subscribed_apps"           # lists the app
   ```

**System user tokens** are for staging and onboarding, not this run: Business settings → Users → System users, with the
app and the WhatsApp account assigned, and the permissions `whatsapp_business_messaging`,
`whatsapp_business_management` and `business_management`. They last for months or without end, so one has to be revoked
when it is no longer needed.

## 6. A business and an admin

Local Auth, with the publishable key only (`supabase status` also prints secret keys, so it is never run bare):

```
APIKEY="$(supabase status -o env 2>/dev/null | sed -n 's/^PUBLISHABLE_KEY="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p')"
EMAIL=gate-admin@example.com
read -rs "PASSWORD?A new local password (not shown): "; echo
auth() { EMAIL="$EMAIL" PASSWORD="$PASSWORD" jq -n '{email: env.EMAIL, password: env.PASSWORD}' |
  curl -sS -X POST "http://127.0.0.1:54321/auth/v1/$1" -H "apikey: $APIKEY" -H 'Content-Type: application/json' --data @-; }

auth signup | jq '{id, email}'
```

Open http://127.0.0.1:54324 (Mailpit) and click the link in the email. The page it lands on does not load, as there is no
frontend yet; the address is confirmed all the same. (If Auth refuses the address, use another: Mailpit catches them
all.) Then sign in. The token lasts an hour; run the first line again when a call answers 401.

```
TOKEN="$(auth 'token?grant_type=password' | jq -r .access_token)"
api() {   # api METHOD PATH [JSON]: the answer, then its status
  local -a data; data=(); [ -z "${3-}" ] || data=(--data "$3")
  { printf 'header = "Authorization: Bearer %s"\n' "$TOKEN"; printf 'header = "Idempotency-Key: %s"\n' "${KEY:-$(uuidgen)}"; } |
    curl -sS -K - -X "$1" -H 'Content-Type: application/json' "${data[@]}" -w '\n%{http_code}\n' "http://127.0.0.1:8080/api/v1$2"
}

api POST /businesses '{"name": "Phase 2 gate"}'
TENANT="$(api GET /me | head -1 | jq -r .business.id)"; echo "$TENANT"
```

On a later run the user and business are already there: sign in and set `TENANT`.

## 7. Connect the number and seed the workflow

```
printf '%s' "$META_TOKEN" | scripts/local/gate connect --tenant=$TENANT --phone-number-id=$PHONE_NUMBER_ID \
  --waba-id=$WABA_ID --display-number=$DISPLAY_NUMBER
scripts/local/gate seed-workflow --tenant=$TENANT
```

`connect` prints `connected ... number=added token=stored`. Running it again, for example with a new token tomorrow, prints
`number=kept token=replaced`. Sends that paused because the old token had expired stay paused (resuming them is step
7.5); a new message goes out. A number belongs to one business, so the test number can be connected to only one.

`seed-workflow` publishes `DEV SEED (Phase 2 gate): stage New only`, so messages open leads before Phase 3. It is in this
local database only.

## The checks

Read-only SQL, in a session that cannot write, as `postgres` (which sees every business):

```
DB=supabase_db_NorthStar
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"   # the checks look at this run only, so they hold on every run
sql() { docker exec -i -e PGOPTIONS='-c default_transaction_read_only=on' $DB psql -U postgres -d postgres -X -P pager=off -v ON_ERROR_STOP=1 -v t="$TENANT" -v since="$SINCE" "$@"; }
SYNTHETIC() { printf '910000%06d' $((0x$(openssl rand -hex 4) % 1000000)); }   # a new sender nobody has: no Indian number starts with 0
hook() { scripts/local/gate webhook-twice --url="$TUNNEL/webhooks/whatsapp" --phone-number-id=$PHONE_NUMBER_ID --waba-id=$WABA_ID "$@"; }
```

### 1. A real message in: one contact, one lead, one conversation

Send "Hi" from your phone to the test number, wait 15 seconds:

```
sql <<'SQL'
select count(distinct ct.id) as contacts, count(distinct l.id) as open_leads, min(l.stage) as stage, min(l.origin) as origin,
       count(distinct c.id) as conversations, count(distinct m.id) as messages
from northstar.contacts ct
left join northstar.leads l on l.contact_id = ct.id and l.closed_at is null
left join northstar.conversations c on c.contact_id = ct.id
left join northstar.messages m on m.conversation_id = c.id and m.direction = 'in' and m.created_at >= :'since'
where ct.tenant_id = :'t' and ct.normalized_phone not like '+910000%';
select processed_at is not null as processed, error from northstar.webhook_events
where tenant_id = :'t' and provider_event_id like 'message:%' and received_at >= :'since' order by received_at desc limit 1;
SQL
```

Expect `1 | 1 | new | direct | 1 | 1`, on a later run too (one contact, lead and conversation, and one message in this run),
then `t |` (processed, no error). The API logged `webhook.received ... stored=1`.

No `webhook.received` line at all: Meta sent nothing. Check the field and the subscription in section 5. Meta's
troubleshooting page also says "Make sure your app is in Live mode; some webhooks will not be sent if your app is in Dev
mode", without naming which; the test number is meant for development, so try the app's current mode first, then Live.
`webhook.unknown_account`: the number is not the one connected in section 7.

Keep your conversation for the next checks:

```
CONV="$(api GET '/conversations?limit=100' | head -1 | jq -r '[.items[] | select(.contact.phone | startswith("+910000") | not)][0].id')"; echo "$CONV"
```

### 2. The same webhook twice creates nothing the second time

```
counts() { sql -At <<'SQL'
select (select count(*) from northstar.contacts where tenant_id = :'t'), (select count(*) from northstar.leads where tenant_id = :'t'),
       (select count(*) from northstar.conversations where tenant_id = :'t'), (select count(*) from northstar.messages where tenant_id = :'t'),
       (select count(*) from northstar.webhook_events where tenant_id = :'t'), (select count(*) from northstar.events where tenant_id = :'t');
SQL
}
counts; hook --from="$(SYNTHETIC)"; sleep 20; counts
```

Expect `first=200 second=200`, and each of the six numbers (contacts, leads, conversations, messages, webhook events,
engine events) exactly one higher. The API logged `stored=1 duplicates=0`, then `stored=0 duplicates=1`.

### 3. A reply out

```
api POST /conversations/$CONV/messages '{"type": "text", "text": "Reply from the Northstar gate"}'
```

Expect 201 and, within 15 seconds, the text on your phone. Then:

```
sql <<'SQL'
select sender_type, sender_membership_id is not null as by_member, status, sent_at is not null as sent, provider_message_id is not null as accepted, error_code
from northstar.messages where tenant_id = :'t' and direction = 'out' order by created_at desc limit 1;
SQL
```

Expect `staff | t | sent` (or further), `t | t`, and no error code. Optional (P21): send it twice with one key, and only one
message arrives; the second answer is 200 with the same ID.

```
KEY="gate-$(uuidgen)"; for i in 1 2; do api POST /conversations/$CONV/messages '{"type": "text", "text": "Once only"}' | tail -1; done; unset KEY
```

### 4. Delivery statuses move forward

Open the chat on your phone, so it is delivered and read, wait 15 seconds:

```
sql <<'SQL'
select m.status, m.delivered_at is not null as delivered, m.read_at is not null as read,
       (select string_agg(e.payload ->> 'status', ' → ' order by e.received_at) from northstar.events e
        where e.type = 'message.status' and e.payload ->> 'message_id' = m.id::text) as reported
from northstar.messages m where m.tenant_id = :'t' and m.direction = 'out' order by m.created_at desc limit 1;
SQL
```

Expect `read | t | t` and `sent → delivered → read`. Meta may report read without delivered, or in another order; the
status never moves back. With read receipts off on the phone it stops at `delivered`.

### 5. Take over stops automated messages, and handing back resumes them

```
auto() { scripts/local/gate queue-automated-text --tenant=$TENANT "$@"; }

auto --conversation=$CONV --text='Automated, before'        # result=queued
sleep 20                                                    # it arrives on your phone; a take over would cancel it unsent
api POST /conversations/$CONV/take-over                     # 200, "automationPaused": true
auto --conversation=$CONV --text='Automated, taken over'    # result=refused_automation_paused; nothing arrives
api POST /conversations/$CONV/messages '{"type": "text", "text": "A person, taken over"}'   # 201; arrives
api POST /conversations/$CONV/hand-back                     # 200, "automationPaused": false
auto --conversation=$CONV --text='Automated, handed back'   # result=queued; arrives
```

```
sql <<'SQL'
select count(*) filter (where body = 'Automated, taken over') as stored_while_taken_over,
       count(*) filter (where body in ('Automated, before', 'Automated, handed back') and status <> 'failed') as automated_sent
from northstar.messages where tenant_id = :'t' and created_at >= :'since';
select action, metadata from northstar.audit_log
where tenant_id = :'t' and action like 'conversation.%' and created_at >= :'since' order by created_at;
select payload ->> 'action' as staff_action, payload ->> 'reason' as reason from northstar.events
where tenant_id = :'t' and type = 'staff.action' and received_at >= :'since' order by received_at;
SQL
```

Expect `0 | 2`; `conversation.taken_over` then `conversation.handed_back`; and `take_over`, `hand_back`.

### 6. Outside the 24-hour window, text is refused

A message dated 25 hours ago, from a sender nobody has, leaves its conversation outside the window without touching the
database:

```
OLD_FROM="$(SYNTHETIC)"; hook --from=$OLD_FROM --hours-ago=25 --text='Yesterday'; sleep 20
OLD="$(api GET '/conversations?limit=100' | head -1 | jq -r --arg p "+$OLD_FROM" '.items[] | select(.contact.phone == $p) | .id')"
api POST /conversations/$OLD/messages '{"type": "text", "text": "Too late"}'
```

Expect 409 and `"code":"window_closed"`. Then:

```
sql -v p="+$OLD_FROM" <<'SQL'
select c.window_expires_at < now() as closed, (select count(*) from northstar.messages m where m.conversation_id = c.id and m.direction = 'out') as replies
from northstar.conversations c join northstar.contacts ct on ct.id = c.contact_id where ct.tenant_id = :'t' and ct.normalized_phone = :'p';
SQL
```

Expect `t | 0`.

### 7. Inside the window, a template is refused

```
api POST /conversations/$CONV/messages "{\"type\": \"template\", \"templateId\": \"$(uuidgen)\"}"
```

Expect 409 and `"code":"window_open_use_text"`: inside the window a reply is text, whatever the template.

### 8. STOP opts out, and a later automated message is refused

All from one synthetic sender, so no real phone is opted out:

```
STOP_FROM="$(SYNTHETIC)"; hook --from=$STOP_FROM --text='Hello'; sleep 20
STOPPED="$(api GET '/conversations?limit=100' | head -1 | jq -r --arg p "+$STOP_FROM" '.items[] | select(.contact.phone == $p) | .id')"
hook --from=$STOP_FROM --text='STOP'; sleep 20
auto --conversation=$STOPPED --text='Automated, after STOP'                             # result=cancelled_contact_opted_out
api POST /conversations/$STOPPED/messages '{"type": "text", "text": "Are you sure?"}'   # 409 contact_opted_out
```

```
sql -v p="+$STOP_FROM" <<'SQL'
select ct.consent_state, ct.opt_out_at is not null as recorded, l.stage, l.closed_at is null as still_open
from northstar.contacts ct left join northstar.leads l on l.contact_id = ct.id where ct.tenant_id = :'t' and ct.normalized_phone = :'p';
select m.status as message, m.error_code, a.status as action, a.cancel_reason
from northstar.messages m join northstar.actions a on a.payload ->> 'messageId' = m.id::text
where m.tenant_id = :'t' and m.body = 'Automated, after STOP' and m.created_at >= :'since' order by m.created_at desc limit 1;
SQL
```

Expect `opted_out | t | new | t` (opting out does not move the lead), then `failed | contact_opted_out | cancelled |
contact_opted_out`, and nothing sent to anyone.

## Record the result

In the roadmap, under step 2.11: the date, the commit, and each check's outcome. A check that fails stops Phase 3 until it
passes. Never paste a token, a phone number or message text into the record.

| Check | Expected | Result |
| --- | --- | --- |
| Tunnel | `/api/v1/me` through it is 404 | |
| 1 | One contact, lead and conversation | |
| 2 | 200 and 200, counts up by one | |
| 3 | 201, and it arrives | |
| 4 | sent → delivered → read | |
| 5 | Automated refused while taken over, resumes after | |
| 6 | 409 `window_closed` | |
| 7 | 409 `window_open_use_text` | |
| 8 | Opted out; automated send cancelled | |

## Afterwards

1. **Stop the tunnel:** Ctrl-C in cloudflared's terminal. Check it is gone: `code "$TUNNEL/webhooks/whatsapp"` prints 000, or
   an error page's 530. Then Ctrl-C in the filter's, the worker's and the API's.
2. **Stop Meta's deliveries:** `graph -X DELETE "https://graph.facebook.com/v26.0/$WABA_ID/subscribed_apps"` while the token
   still works, and unsubscribe `messages` in the dashboard. Meta retries deliveries that fail for up to 7 days; with the
   account unsubscribed it has none to retry.
3. **The token:** the temporary token stops by itself within 24 hours; there is nothing to revoke. `unset META_TOKEN TOKEN
   PASSWORD` in the checks terminal. A system user token, if one was used, must be revoked: Meta's "Install Apps,
   Generate, Refresh, and Revoke Tokens" page describes the `oauth/revoke` call, which ends a token at once. If a secret
   was ever shown, reset it: the app secret under App settings → Basic, then section 2 again.
4. **The secrets:** `sed -i '' '/^NORTHSTAR_WHATSAPP_/d' .env.local`, unless the next run is soon (the API needs both to
   start with the worker off). Clear the clipboard, and turn clipboard history back on.
5. The connected number, its encrypted token (expired), the seed workflow and the test contacts stay in the local database;
   nothing needs resetting before the next run. The next run starts at section 3 with a new tunnel address and a new
   token: sections 4, 5 (callback URL, token) and `connect` again, then the checks.

## If something goes wrong

| What you see | Why | Do |
| --- | --- | --- |
| `refused code=database_not_local` | `DB_URL` in `.env.local` is not this machine's | Point it at `127.0.0.1:54322` |
| `refused code=database_login_failed` | A role's password differs from `.env.local` | `db/bootstrap/README.md`, then try again |
| `refused code=number_taken` | Another local business has the test number | Use that business |
| Verify and save fails | Tunnel, filter or API down, or a wrong token | The checks in section 4; copy the token again |
| `webhook.signature_invalid` | The app secret is not this app's | Section 2 again, then restart the API |
| A reply stays `queued` and the action is `paused` | The token expired (`integrations.status` is `error`) | `connect` with a new token, then send a new reply |
| A reply fails with a Meta code | Often a phone that is not on the recipient list | Section 5, step 1 |
| A call answers 401 | The local token lasts an hour | Section 6, the sign-in line |

## Sources

Checked 24 Nov 2026. Where a step says "check it there", these pages do not say.

- [Meta: WhatsApp Cloud API, get started](https://developers.facebook.com/documentation/business-messaging/whatsapp/get-started)
  (API Setup, the temporary token, system user tokens and their permissions)
- [Meta: using authorization tokens](https://developers.facebook.com/blog/post/2022/12/05/auth-tokens/) (the temporary
  token's lifetime)
- [Meta: create a test webhook endpoint](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/set-up-whatsapp-echo-bot)
  (callback URL, verify token, the `messages` field)
- [Meta: webhooks overview](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/overview/)
  (permissions, retries for up to 7 days, Live and Dev mode)
- [Meta: managing webhooks](https://developers.facebook.com/documentation/business-messaging/whatsapp/solution-providers/manage-webhooks/)
  (`subscribed_apps`)
- [Meta: install apps, generate, refresh, and revoke tokens](https://developers.facebook.com/docs/business-management-apis/system-users/install-apps-and-generate-tokens)
  (`oauth/revoke`)
- [Cloudflare: Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) (no account, a new
  address on every run, testing only)
