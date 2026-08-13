# WebViewPasswordDemo

A runnable Swing app that exercises the built-in **password manager** on
`WebViewComponent` (see the "Password manager" section of the project
[`README.md`](../../README.md)).

The demo starts a tiny embedded HTTP server on `127.0.0.1` and serves a
login form there, so the manager sees a **real origin**
(`http://127.0.0.1:<port>`) — a `data:` / `about:blank` page has no origin
and would be skipped by the origin-keyed manager.

## What it demonstrates

- **Capture + save prompt** — type a username + password and submit the
  form; the framework's Swing "Save password?" prompt appears. Approving
  stores the credential in the active store.
- **Autofill on load** — click **Reload** after saving; the username and
  password fields auto-fill for the page's origin.
- **Programmatic API** — the **Save (demo/demo)**, **Get**, and
  **Delete demo** buttons call `saveCredential` / `getCredential` /
  `getCredentials` / `deleteCredential` directly.
- **Enable/disable gating** — the **Enabled** checkbox toggles
  `setPasswordManagerEnabled`; when off, no prompt/autofill fires but the
  programmatic buttons still work.
- **Store swap** — the **Store** combo switches between the OS-native
  `NativeCredentialStore` (Keychain on macOS) and an
  `InMemoryCredentialStore`.

Passwords are never printed to the log pane (only the username and a
redacted marker).

## Coverage

macOS is wired in this release (Keychain). On Linux and Windows the
programmatic API works and the store degrades gracefully, but automatic
capture / autofill activate once the per-platform native channel and
secret store land (Canvases 24 / 25).

## Running

Build `dist/WebView.jar` once from the project root (e.g. run any
`run-<platform>-demo.sh` / `.bat`), then from this directory:

```
ant run
```
