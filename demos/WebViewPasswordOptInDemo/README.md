# WebViewPasswordOptInDemo — Chrome-style opt-in autofill

A demo of a **user-opt-in** password fill, the way a browser like Chrome
does it: the login fields stay empty on load, and the saved password is
filled only after the user focuses a field, picks an account from a
chooser, and passes a re-authentication step.

## The flow

1. **Nothing fills automatically.** The demo installs a fill-consent
   handler that returns `DONT_FILL`, so the library's built-in silent
   autofill never fires.
2. **Focus a login field** (username or password) → a dropdown chooser
   appears anchored under the field, listing the saved account(s) for the
   page's origin (username + a masked password — never the real secret).
3. **Pick an account** → a simulated "Unlock passwords" dialog stands in
   for the OS re-auth (Touch ID / Windows Hello / login password) that
   Chrome performs before revealing a saved password.
4. **On approval**, the username and password are filled in; Cancel fills
   nothing.

## How it works (public API only)

This is a **host-driven** pattern built entirely on `WebViewComponent`'s
public API — no library changes:

- `setFillPasswordHandler(e -> FillPasswordDisposition.DONT_FILL)` disables
  the automatic autofill so the flow is genuinely opt-in.
- `addOnBeforeLoad(...)` injects a document-start focus detector that
  reports a focused login field's rectangle over a bound callback
  registered with `addJavascriptCallback(...)`.
- `getCredentials(origin)` populates the chooser.
- After the simulated unlock, the demo fills by evaluating the library's
  write-only entrypoint `window.__webview_pw_fill__('<b64url user>',
  '<b64url pass>')` via `eval(...)`, reusing the library's own field
  detection and `input`/`change` event dispatch.

Saved accounts are seeded into an `InMemoryCredentialStore`, so the demo
touches no real OS Keychain and is repeatable. Passwords are never printed.

## Run it

macOS:

```sh
./run-mac-password-optin-demo.sh
```

Windows (JDK 8+ and Visual Studio with the C++ workload; a WebView2
Runtime ships with Windows 11):

```bat
run-windows-password-optin-demo.bat
```

Linux (JDK 8+, `g++`/`pkg-config`, GTK3 + WebKitGTK 4.0/4.1 dev packages;
defaults to lightweight, pass `heavyweight` to force the other mode):

```sh
./run-linux-password-optin-demo.sh            # lightweight (default)
./run-linux-password-optin-demo.sh heavyweight
```

Each launcher builds the platform native library and `dist/WebView.jar`,
then compiles and runs the demo. The `WebViewPasswordOptInDemo` class is
the same on every platform — it uses only the cross-platform public API,
so the opt-in flow is identical.

## Relationship to `WebViewPasswordDemo`

`WebViewPasswordDemo` shows the built-in behaviour: silent autofill plus an
optional **load-time** `WebViewFillPasswordHandler.CONFIRM` prompt. This
demo shows the **field-click** opt-in a browser uses — a chooser under the
field and a re-auth before fill.
