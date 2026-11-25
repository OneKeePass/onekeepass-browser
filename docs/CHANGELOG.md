# Changelog

All notable changes to the OneKeePass Browser Extension are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Added

- **Save new and changed logins.** After you sign in, sign up or change a password on a site, the extension offers to add the login to your database or update the saved password. It asks only once the login has gone through, never after a failed login or for a login that is already saved. Updates keep the old password in the entry's history. Can be turned off in **Settings**, or per site with **Never for this site**. Needs a desktop app version that supports it.

- **Fill 2FA codes.** On a site's one-time code page (including codes split into one box per digit), click the code field: the list shows only the site's entries that have a 2FA code, with the entry you just signed in with first. Picking one fills its current code. Only the generated code leaves the desktop app, never the TOTP secret. Needs a desktop app built with the matching onekeepass-core.

### Changed

- **Redesigned popups.** The entry list, save-login, passkey, message and settings popups share a new look: one card with its own header (logo, title, close button; drag it by the header), smooth entrance and exit animations (off when the system asks for reduced motion), letter avatars, search for long lists, 2FA / passkey badges, and a dark variant that follows the system color scheme.
- **Keyboard use of the entry list.** With the focus still in the login field, use ↑ / ↓ to choose an entry, Enter to fill it and Escape to close the list.
- **Save prompt.** Shows the username (editable for a new entry) and the password as dots only; the password itself never reaches the popup.

### Fixed

- **Passkeys and passwords are no longer mixed up.** The password fill list no longer shows entries that hold only a passkey, and picking an entry from the password list always fills the password, even while a site is also asking for a passkey in the background.

### Security

- Logins are offered for saving only on https sites (and `localhost`), only from the page's own frame (never from another site's embedded login frame), never in private windows, and only after a real key press or click by the user.
- The requests that carry a submitted password to the desktop app are encrypted with the session keys, with replay protection. The desktop app compares the password with the stored entries itself; stored passwords never come back to the browser.

## [1.4.0] - 2026-06-15

### Added

- **Disable OneKeePass on specific sites.** Login-field detection can now be turned off per site. Use the quick **Disable on this site** button in the toolbar popup (with an optional **Include subdomains** choice), or manage the full list under **Settings → Disabled Sites**. Passkey support is unaffected and stays controlled by its own setting.
- **Redetect login fields button.** A button in the popup re-scans the current page for login fields — useful when a login form only appears after interacting with the page.

### Changed

- **Smarter URL matching.** More reliable handling of the current page URL when matching against stored entries. When no entry matches, the extension now suggests adding the site's URL/origin to a login entry's **URL or Additional URLs** field, with a clearer on-screen message.
- **Better login-field detection.** Detects login inputs inside Shadow DOM (including fields deep in a shadow root) and on sites that place their login panel in an iframe (e.g. Charles Schwab). Shows the OneKeePass icon in auto-focused inputs on page load, and skips injecting into irrelevant/blank sub-frames for better performance.
- **Clearer connection status.** The toolbar badge greys out when the desktop app is not connected, clicking the field icon now surfaces the **Connect** prompt, and the repeated "App is not running" message has been reduced.

### Fixed

- Improved handling of non-standard buttons — only falls back to `role=button` when a real button element is not found.
- Fixes for Flutter-based sites.

### Security

- **Clickjacking / autofill hardening** to reduce the risk of credentials being filled into hidden or spoofed fields.

## [1.3.0] - 2026-05-19

### Added

- **Custom icons.** An entry's custom icon stored in the KeePass database is now shown in the extension's entry list.

### Changed

- More robust framed message handling for content-script ↔ background communication.

## [1.2.0] - 2026-04-04

### Added

- **Passkey (WebAuthn/FIDO2) support for Chrome and Firefox.** OneKeePass can now act as a passkey provider, storing and using passkeys directly from your KeePass database:
  - **Registration** — a guided "Save Passkey" popup to choose the database, group, and entry to store a new passkey in.
  - **Sign-in** — a popup listing matching passkeys for the site, with the extension completing authentication.
  - **Enable/disable setting** — a settings dialog to turn passkey support on or off (enabled by default).
  - Helpful messages when no matching passkey is found or no database is open.
- **User guide** documenting setup and usage.

## [1.1.0] - 2026-01-25

### Added

- **Iframe-based popup architecture.** The entry-list, message box, and popup-action UIs now render inside an isolated iframe connected to the content script over a persistent MessageChannel, improving reliability and isolating the UI from host-page styles.

### Security

- Fixed wildcard `*` `targetOrigin` when resolving WebAuthn credentials.
- The native app now validates the `origin` field sent by the extension.
- Scoped the Firefox `host_permissions` instead of granting access to all HTTP/HTTPS URLs, and hardened the encrypted native-messaging channel against replay.
- Fixed a permissive Content Security Policy (`wasm-unsafe-eval`).
- Stricter re-association — no longer auto-allowed based only on the browser name.

## [1.0.0] - 2025-11-06

Initial public release.

### Added

- **Password autofill.** Automatic detection of username and password fields (in both the light DOM and Shadow DOM), with a OneKeePass icon shown inside detected fields.
- **Entry-list popup.** Matching entries from open databases, grouped by database name, with click-to-fill.
- **Encrypted native messaging** to the OneKeePass desktop application (NaCl shared-key encryption), with a one-time pairing/association approval flow.
- **Redetect login fields** action, and draggable entry-list and message popups.
- Localized message texts.
- Chrome and Firefox builds.

[1.4.0]: https://github.com/jeyasankar/onekeepass-browser-cljs/releases/tag/v1.4.0
[1.3.0]: https://github.com/jeyasankar/onekeepass-browser-cljs/releases/tag/v1.3.0
[1.2.0]: https://github.com/jeyasankar/onekeepass-browser-cljs/releases/tag/v1.2.0
[1.1.0]: https://github.com/jeyasankar/onekeepass-browser-cljs/releases/tag/v1.1.0
[1.0.0]: https://github.com/jeyasankar/onekeepass-browser-cljs/releases/tag/v1.0.0
