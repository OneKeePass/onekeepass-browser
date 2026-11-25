# Native messaging: saving submitted logins

This describes the two native messaging actions the desktop app handles for the
extension's save-login feature (implemented in OneKeePass/desktop
`src-tauri/src/browser_service`, see the matching desktop branch). The database logic is in `onekeepass-core`
(`db_service::browser_extension`); the desktop app only decrypts the request, calls
core and encrypts the reply.

## Encrypted requests

Both requests carry the password the user submitted, so the extension encrypts their
payload with the session keys (NaCl `box`, the same keys used for the app's encrypted
replies). Only these fields are in the clear:

```json
{
  "action": "CheckLoginCredential",
  "request_id": "<uuid>",
  "association_id": "<id>",
  "message": "<base64 box>",
  "nonce": "<base64 24-byte nonce>"
}
```

To read the payload, the app opens `message` with
`box_open(message, nonce, client_session_pub_key, app_session_secret_key)`. The
plaintext is the same replay-protection envelope the app uses for its replies:

```json
{ "seq": 7, "msg": "<payload JSON string>" }
```

`seq` starts at 1 for every new session (new `InitSessionKey`) and goes up by one
per encrypted request. The app must reject a request whose `seq` is not greater than
the last one it accepted in that session, and must reject a request that is not
encrypted.

Replies are encrypted as for every other action (`message_content` + `nonce`) and echo
the `request_id`.

## CheckLoginCredential → LoginCredentialChecked

Payload (`browser_extension::LoginCredentialInput`):

```json
{ "url": "https://example.com", "username": "alice", "password": "…" }
```

`url` is the site's origin, taken by the extension from the browser's record of the
sending tab (not from the page).

The app calls:

```rust
browser_extension::check_login_credential(&browser_enabled_db_keys, &input)
```

and replies with the returned `LoginCredentialCheck`:

```json
{
  "status": "NoEntryForSite | NewUsername | Unchanged | PasswordChanged",
  "site": "https://example.com",
  "update_candidates": [{ "db_key": "", "db_name": "", "entry_uuid": "", "title": "", "username": "" }],
  "save_targets": [{ "db_key": "", "db_name": "", "groups": [{ "group_uuid": "", "name": "", "parent_group_uuid": "" }] }]
}
```

No stored password is ever part of the reply.

## SaveLoginCredential → LoginCredentialSaved

Sent only after the user confirmed Save or Update in the extension's popup. Payload
(`browser_extension::LoginCredentialSaveInfo`):

```json
{
  "db_key": "…",
  "url": "https://example.com",
  "username": "alice",
  "password": "…",
  "entry_uuid": "<uuid or null>",
  "group_uuid": "<uuid or null>",
  "title": "<string or null>"
}
```

`entry_uuid` set: update that entry's password (the old one goes into the entry's
history). `entry_uuid` null: add a new Login entry in `group_uuid` (root when null).

The app calls:

```rust
browser_extension::save_login_credential_to_db(&browser_enabled_db_keys, &info, backup_file_name)
```

(or `save_login_credential` followed by its usual save), then replies with the
returned `LoginCredentialSaveOutcome` (`entry_uuid`, `group_uuid`, `entry_type_uuid`,
`entry_type_name`, `created`) and refreshes its UI as it does after a passkey save.

Core re-validates every save: the database must be one of `browser_enabled_db_keys`,
the url must be http(s), and an entry to update must be an active Login/Card/Bank
entry for that same site and username whose password is not a reference to another
entry. Pass only the databases the user enabled for the browser.

## Errors

On failure, reply with the usual error shape and the same `action` name as the
request (`CheckLoginCredential` / `SaveLoginCredential`), with `request_id` when
possible. The extension shows the error message in the popup for a failed save and
drops the captured login.

## 2FA codes (no new action)

Filling 2FA codes uses the existing actions. With the matching onekeepass-core, the
structs the app already returns carry two new fields:

- `EnabledDatabaseMatchedEntryList`: each `MatchedDbEntries` has `otp_entry_uuids`,
  the matched entries that have a usable TOTP.
- `SelectedEntry`: `BasicEntryCredentialInfo` has `otp` (`token`, `ttl`, `period`)
  when the entry has a usable TOTP. The TOTP secret is never included.

If the app serializes these core structs as they are, nothing else is needed. The
extension sends the code only into a 2FA code field and strips it from login fills.
