# Story Decomposition: Built-in Password Manager for the Embedded WebView

## INVEST Analysis

### Abstract Task: "Give `WebViewComponent` a browser-grade password manager — auto-detect login submissions and offer to save them, auto-fill saved credentials on page load, back everything with the OS-native secret store — with a deterministic Java API for hosts and tests, uniform across macOS, Linux, and Windows"

**Analysis Dimensions**:
- **Core Responsibility**: A page running inside `WebViewComponent` presents login forms. Today the embedded engines (`WKWebView`, `WebKitGTK`, `WebView2`) do **not** give the embedding app the "offer to save this password / autofill it next time" experience that Safari, Chrome, and Edge give their own users — that behaviour is a browser-privileged feature the raw engine withholds. This task builds that experience into the library itself:
  - **Save**: injected JavaScript watches the page for login-form submissions, extracts the username + password + page origin, and the library shows a browser-style Swing "Save password?" prompt. On confirm, the credential is written to the **OS-native secret store** (macOS Keychain, Linux libsecret / Secret Service, Windows Credential Manager) so it is encrypted at rest and unlocked by the user's OS login — the library never rolls its own crypto.
  - **Fill**: on page load, if a credential exists for the page's origin, the library injects the username + password into the detected login fields automatically, reproducing "save once, autofill forever".
  - **Programmatic control**: a public Java API on `WebViewComponent` lets a host enable/disable the manager, and get / save / delete credentials directly, so applications can drive it deterministically and unit tests can exercise it headlessly.
- **Primary Operations**:
  1. Inject a credential-detection + credential-fill script into every document at document-start on each backend, and receive the captured submission back over a message channel.
  2. On a captured submission, marshal to the Swing EDT, ask the active save-password policy (default: a Swing "Save password?" prompt) whether to store it, and on approval write it to the credential store keyed by origin.
  3. On document load, look up the store by origin and, if a credential is present and the manager is enabled, inject it into the page's login fields.
  4. Read / write / delete credentials in the OS-native secret store via a per-platform native backend, behind a single Java `WebViewCredentialStore` seam.
  5. Expose a public Java API on `WebViewComponent` (enable/disable, get/save/delete, store override, save-policy override, credential + event POJOs) shared by all backends.
- **Key Constraints**:
  - Passwords are secret material. They must be stored **only** in the OS-native secret store (encrypted at rest by the OS), never in plaintext files, logs, or the page's persisted DOM. In-memory exposure on the JVM heap must be minimised and never written to disk by the library.
  - Credentials are keyed by **page origin** = scheme + host + port (e.g. `https://example.okta.com` implies port 443). Autofill for one origin must never fire on a different origin (anti-phishing baseline).
  - The save prompt and the autofill must be **overridable / disableable**: a host may turn the whole manager off, supply its own credential store (e.g. in-memory for tests), or supply its own save-policy (return SAVE/DON'T-SAVE programmatically without showing UI) so headless tests stay viable.
  - The detection JavaScript must reuse the **reserved-binding / message-channel** infrastructure each backend already has (the same channel used for console capture, DOM mouse events, and dialog dispatch) — no new public JS binding is exposed to page authors.
  - Autofill must run at a lifecycle point where the login fields exist but the library's write does not fight the page's own scripts unnecessarily; a best-effort model (fill on `DOMContentLoaded`, re-attempt on late-inserted forms within a bounded window) is acceptable and must be specified.
  - The native secret-store calls must not block the engine's UI thread for an unbounded time; store I/O is marshaled off the UI thread where the platform requires it.
- **Technical Complexity**: High overall, split across a shared core (Java API + detection/fill JavaScript + the store seam) and three platform-shaped native pieces:
  - macOS: inject a `WKUserScript` at document-start, receive captures via a `WKScriptMessageHandler`, and implement the credential store against **Security.framework** Keychain (`SecItemAdd` / `SecItemCopyMatching` / `SecItemDelete`, generic-password items keyed by origin).
  - Linux: inject via `webkit_user_content_manager_add_script` + a script-message handler, and implement the store against **libsecret** (`secret_password_store_sync` / `secret_password_lookup_sync` / `secret_password_clear_sync`) with a stable schema keyed by origin.
  - Windows: inject via `AddScriptToExecuteOnDocumentCreated` + `WebMessageReceived`, and implement the store against the **Windows Credential Manager** (`CredWrite` / `CredRead` / `CredDelete`, `CRED_TYPE_GENERIC`, target name derived from origin; the blob is DPAPI-protected by the OS).
- **Business Complexity**: Medium — the user-visible semantics (offer to save, autofill on return) are familiar from every browser, but the login-form heuristics (which field is username, which is password, when a "submission" happened in a SPA) carry real ambiguity that the ACs must pin down.

### INVEST Evaluation (whole feature)

- ✅ **Independent**: Builds only on the existing per-backend message-channel plumbing (canvases 2 / 6 / 7 and the dialog/console dispatch precedent). No dependency on unshipped work.
- ✅ **Negotiable**: Defaults agreed with the user — OS-native store, auto-detect + Swing save prompt, auto-fill on load, origin = scheme+host+port. The save-policy and store are override seams, so teams can renegotiate UX without touching the contract.
- ✅ **Valuable**: Without this, users of any app embedding the WebView must retype passwords on every login (the concrete Okta pain that motivated the feature). With it, the embedded WebView behaves like a real browser.
- ✅ **Estimable**: Each backend has a well-documented secret-store API and script-injection API. The Java side is one store interface + one save-policy interface + two POJOs + the component methods.
- ⚠️ **Small (as a single story)**: Combined, the work spans three native secret stores, three script-injection paths, the shared JavaScript, and the public Java API — realistically 9-13 days. **This exceeds the 1-5 day INVEST target.** Splitting is required (see below).
- ✅ **Testable**: The programmatic API (inject an in-memory store + a programmatic save-policy) makes save/fill observable from headless unit tests; the default UX is observable from a Swing harness.

**Conclusion**: Needs splitting along platform-backend boundaries, exactly like the Browser-Initiated UI Dialogs feature. The shared Java contract + shared detection/fill JavaScript are designed once and co-delivered with macOS (the platform whose absence of this feature the user actually hit). Linux and Windows coverage stories add their native secret-store + injection wiring while conforming to the same contract.

### Split Strategy

Split by **technical dependency / native backend**, because:

- Each platform's secret store is a distinct native API (Security.framework Keychain / libsecret Secret Service / Windows Credential Manager), and each platform's document-start script injection + message channel is distinct (WKUserScript+WKScriptMessageHandler / user-content-manager+script-message / AddScriptToExecuteOnDocumentCreated+WebMessageReceived). Wiring each is independent of the others.
- The Java contract + the detection/fill JavaScript are shared and must be designed once — that is STORY-006-001's primary responsibility, co-delivered with macOS because macOS is the platform the user is blocked on.
- Each story delivers independent value: STORY-006-001 alone gives macOS users working save + autofill; STORY-006-002 alone extends it to Linux; STORY-006-003 alone extends it to Windows. None regresses the others.
- Each story is 2-5 days, within INVEST sizing.

Story dependency graph:

```
STORY-006-001 (Java API contract + shared detection/fill JS + macOS Keychain)
        │
        ├──► STORY-006-002 (Linux libsecret) — depends on the Java contract + shared JS from 006-001
        │
        ├──► STORY-006-003 (Windows Credential Manager) — depends on the Java contract + shared JS from 006-001
        │
        ├──► STORY-006-004 (Autofill-consent handler) — Java-contract-only; depends on 006-001; cross-platform by construction
        │
        └──► STORY-006-005 (Enumerate-all credentials) — extends the store seam; Java contract + native enumerate on macOS/Linux/Windows
```

STORY-006-002 and STORY-006-003 can be developed in parallel once STORY-006-001 has landed. STORY-006-004 and STORY-006-005 are follow-on enhancements that build on the shipped contract (006-001) and, for 006-005's native enumerate bodies, the per-platform stores from 006-002 / 006-003. **Both reverse a Scope-Out item that STORY-006-001 originally deferred**: 006-004 reverses "Biometric / re-authentication gating before autofill", and 006-005 reverses "A credential-management UI … Hosts build their own on top of the programmatic API" by supplying the missing enumerate-all primitive that a management UI needs.

---

## [STORY-006-001] Password-Manager Java API, Shared Detection/Fill Script, and macOS Keychain Coverage

### Background

Every mainstream browser offers to save a password after a successful login and autofills it on the next visit. The embedded engines this library wraps deliberately withhold that behaviour from third-party host apps: `WKWebView` never shows Safari's "Save Password" prompt (it is a Safari-private capability), and while it can surface iCloud Keychain items it only does so for a signed app under narrow conditions. The concrete consequence a user hit: logging into an Okta page inside `WebViewComponent` on macOS requires retyping the full password every single time, even though Safari and Chrome autofill it.

This story builds the library's own password manager and delivers it on macOS. It does three things at once:

1. **Designs and exposes the cross-platform Java contract**: a `WebViewCredential` POJO, a `WebViewCredentialStore` seam (default = OS-native store, overridable for tests), a `WebViewSavePasswordHandler` policy (default = Swing "Save password?" prompt, overridable/programmatic), a captured-submission event POJO, and the enable/disable + get/save/delete methods on `WebViewComponent`. This is the exact contract STORY-006-002 (Linux) and STORY-006-003 (Windows) conform to.
2. **Authors the shared detection + fill JavaScript** injected at document-start on every backend. It (a) locates the login form (a `<input type="password">` and its associated username field), (b) on form submission posts `{origin, username, password}` back to Java over the existing reserved message channel, and (c) on load, when Java hands it a credential for the origin, writes the username + password into the detected fields and dispatches the appropriate input events so the page's own validation sees them.
3. **Implements the macOS backend**: inject the shared script via `WKUserScript` at document-start, receive captures via a `WKScriptMessageHandler`, and implement `WebViewCredentialStore` against the macOS **Keychain** (Security.framework generic-password items keyed by origin).

The chosen design mirrors the shipped `WebViewDialogHandler` feature: a policy interface with a sensible Swing default plus a store seam, both overridable per component instance, so the happy path "just works" and tests stay deterministic.

Key points:
- Business value: restores the "save once, autofill forever" experience inside the embedded WebView, eliminating password retyping. macOS is delivered first because that is where the user is blocked.
- Relationship with other features: reuses the reserved `__webview_`-prefixed message channel and the EDT-marshaling dispatch precedent set by console capture (canvas 2), DOM mouse events (canvas 9), and the dialog handler (canvas 11). No new public JS binding is exposed to page authors.
- Why now: the raw engine will never provide this; the user is retyping an Okta password on every login today.

### Business Value

- Provide **working "Save password?" capture** on macOS for login forms inside `WebViewComponent`, storing to the encrypted OS Keychain on the user's approval.
- Provide **working autofill on page load** on macOS, so a returning user's saved username + password appear pre-filled without retyping.
- Provide a **single Java contract** (`WebViewCredential`, `WebViewCredentialStore`, `WebViewSavePasswordHandler`, component methods) that the Linux and Windows stories conform to, so the embedded page behaves identically regardless of platform.
- Provide a **secure-by-default storage backend** (OS Keychain) so host apps get encrypted-at-rest credential storage without writing any crypto.
- Provide a **deterministic override path** (inject an in-memory store + a programmatic save-policy) so unit tests and headless environments exercise save/fill without spawning UI or touching the real Keychain.

### Dependencies and Assumptions

- **Prerequisites**: Canvases 5, 6, 7 (mode selection, heavyweight, lightweight) and the existing per-backend message-channel + document-start injection infrastructure used by console capture and the dialog handler. The macOS heavyweight engine already creates a `WKWebView`, attaches a `WKScriptMessageHandler`, and a `WKUserContentController` (`e->manager`) — this story adds a `WKUserScript` and a new message name to that same controller, and a Keychain-backed store.
- **Data assumptions**: The only persisted state is the credentials themselves, held in the OS Keychain — never in a library file. A credential is `{origin, username, password}`. At most one credential per `{origin, username}` pair; saving a new password for an existing `{origin, username}` overwrites it.
- **Integration points**: macOS — `WKUserContentController.addUserScript:` (document-start injection), `WKScriptMessageHandler` (capture channel), and Security.framework Keychain (`SecItemAdd`, `SecItemCopyMatching`, `SecItemUpdate`, `SecItemDelete`) for `kSecClassGenericPassword` items whose service is a library-namespaced constant and whose account encodes the origin.
- **Business constraints**:
  - **Origin** = scheme + host + port, with the default port implied by scheme (`https`→443, `http`→80). `https://example.okta.com` and `https://example.okta.com:443` are the same origin; `https://a.com` and `https://b.com` are different; `http://a.com` and `https://a.com` are different.
  - Autofill for origin X must **never** fire on any origin other than X.
  - The manager is **enabled by default** on macOS. When disabled, neither the save prompt nor autofill fires, but the programmatic API still works.
  - The library must never log, print, or persist the password anywhere other than the OS Keychain. Passwords must not appear in `System.out`/`System.err`, in exception messages, or in any file the library writes.
  - The save-policy callback and any UI it shows run on the Swing EDT. The default prompt is modal to the host `JFrame` (`SwingUtilities.getWindowAncestor(component)`), consistent with the dialog handler.

### Scope In

- New public POJO `ca.weblite.webview.WebViewCredential`, immutable, with public accessors `String origin()`, `String username()`, `String password()`; value-equality on `{origin, username}` (password excluded from `equals`/`hashCode`); `toString()` that redacts the password (never prints it).
- New public interface `ca.weblite.webview.WebViewCredentialStore` — the storage seam:
  - `void save(WebViewCredential credential)` — insert or overwrite the credential for `{origin, username}`.
  - `java.util.Optional<WebViewCredential> find(String origin)` — the credential to autofill for an origin; when multiple usernames are stored for the origin, returns the most-recently-saved one.
  - `java.util.List<WebViewCredential> findAll(String origin)` — all credentials stored for an origin (usernames + passwords), most-recently-saved first.
  - `boolean delete(String origin, String username)` — remove one credential; returns whether anything was removed.
  - The default implementation, `NativeCredentialStore`, is backed by the OS-native secret store (Keychain on macOS via this story). A host may install a different store (e.g. an in-memory one for tests).
- New public POJO `ca.weblite.webview.WebViewSavePasswordEvent`, immutable — `WebViewComponent source()`, `String origin()`, `String username()`, `String password()` (the captured submission); `toString()` redacts the password.
- New public interface `ca.weblite.webview.WebViewSavePasswordHandler` with a single method invoked on the EDT:
  - `SavePasswordDisposition onLoginSubmitted(WebViewSavePasswordEvent event)` returning an enum `SavePasswordDisposition { SAVE, DONT_SAVE }`. Default impl: a Swing prompt ("Save password for `<origin>`?", showing the username, OK/Cancel) modal to the host `JFrame`; OK → `SAVE`, Cancel/close → `DONT_SAVE`. A host may override to decide programmatically without UI.
- New public methods on `WebViewComponent`:
  - `WebViewComponent setPasswordManagerEnabled(boolean enabled)` — master switch for auto-detect save + autofill. Enabled by default. Disabling stops the prompt and autofill; the programmatic methods below still work.
  - `boolean isPasswordManagerEnabled()`.
  - `WebViewComponent setCredentialStore(WebViewCredentialStore store)` — replace the store; `null` reinstalls the default `NativeCredentialStore`.
  - `WebViewCredentialStore getCredentialStore()` — never null; returns the default when none set.
  - `WebViewComponent setSavePasswordHandler(WebViewSavePasswordHandler handler)` — replace the save-policy; `null` reinstalls the default Swing-prompt handler.
  - `WebViewSavePasswordHandler getSavePasswordHandler()` — never null.
  - `void saveCredential(WebViewCredential credential)` — programmatic save (bypasses the prompt; writes to the store).
  - `java.util.Optional<WebViewCredential> getCredential(String origin)` — programmatic lookup (delegates to the store's `find`).
  - `java.util.List<WebViewCredential> getCredentials(String origin)` — programmatic list for an origin.
  - `boolean deleteCredential(String origin, String username)` — programmatic delete.
- Shared detection/fill JavaScript (authored once, used by all backends), covering: locating a password field and its associated username field (the text/email/tel input immediately preceding the password field within the same form, else the form's first such input, else a page-level heuristic when there is no `<form>`); posting `{origin, username, password}` on `submit` (and on a synthetic submit for SPA login buttons that don't fire a real `submit`, best-effort); and, on receiving a credential from Java, setting the fields' `.value` and dispatching `input` + `change` events so framework-driven forms observe the change. The script uses the reserved message channel; it exposes no new page-visible global.
- macOS backend: inject the shared script as a document-start `WKUserScript`; register a `WKScriptMessageHandler` for the capture message; implement `NativeCredentialStore` against the Keychain via JNI (`SecItem*`), with items namespaced to this library so it never collides with Safari's iCloud Keychain items.
- The autofill request is driven from Java after page load: when the manager is enabled and `store.find(origin)` returns a credential, the credential is handed to the injected script to fill.

### Scope Out

- Linux libsecret backend — STORY-006-002.
- Windows Credential Manager backend — STORY-006-003.
- The standalone in-process `WebView` class — this story targets embedded `WebViewComponent` only (same boundary the dialog-handler story drew). Adding the API to standalone `WebView` is a follow-up if requested.
- A credential-management UI (a "manage saved passwords" list/editor window). Hosts build their own on top of the programmatic API; the library ships only the save prompt.
- Multi-step / identifier-first login flows where username and password are on separate pages (e.g. Okta's split username→password screens) as a *guaranteed* capture. Best-effort capture is in scope (each page's fields are detected independently); guaranteeing cross-page correlation is out of scope for this story and noted as a known limitation.
- Passkeys / WebAuthn, TOTP/2FA codes, passphrase generation, and password-strength evaluation.
- Syncing credentials across machines (the OS Keychain may sync via iCloud on its own; the library neither adds nor prevents that beyond choosing a non-synchronizable item attribute where specified).
- Biometric / re-authentication gating before autofill (Touch ID prompt per fill). Out of scope; the OS unlocks the Keychain at login.
- Cross-origin or subdomain credential matching (e.g. offering `login.example.com` creds on `example.com`). Matching is exact-origin only in this story.

### Acceptance Criteria

#### AC1: Login submission triggers a Swing "Save password?" prompt on macOS
**Given** a `WebViewComponent` on macOS with the password manager enabled (default) and no custom save-handler, loaded at `https://example.com/login` with a form containing a username input and `<input type="password">`,
**When** the user types username `alice` and password `s3cret` and submits the form,
**Then** a Swing prompt appears modal to the host `JFrame` asking to save the password for `https://example.com`, showing the username `alice`.

#### AC2: Approving the prompt stores the credential in the Keychain
**Given** the setup of AC1 with the save prompt showing,
**When** the user clicks the prompt's OK/Save button,
**Then** the credential `{origin: "https://example.com", username: "alice", password: "s3cret"}` is retrievable afterwards via `getCredential("https://example.com")` (username `alice`, password `s3cret`).

#### AC3: Declining the prompt stores nothing
**Given** the setup of AC1 with the save prompt showing,
**When** the user clicks Cancel / closes the prompt,
**Then** `getCredential("https://example.com")` returns an empty result and nothing is written to the store.

#### AC4: Saved credential autofills on next load
**Given** a `WebViewComponent` on macOS with the manager enabled and a stored credential `{https://example.com, alice, s3cret}`,
**When** the component loads `https://example.com/login` containing an empty username input and `<input type="password">`,
**Then** after load the username field's value is `alice` and the password field's value is `s3cret`, without the user typing anything.

#### AC5: Autofill fires `input`/`change` so page validation sees the values
**Given** the setup of AC4 where the page records `oninput` of the password field into `document.title`,
**When** autofill populates the fields,
**Then** `document.title` reflects that an `input` event fired for the password field (the page observed the change, not just a silent DOM mutation).

#### AC6: Autofill is origin-exact — a different origin is not filled
**Given** a `WebViewComponent` on macOS with a stored credential for `https://example.com`,
**When** the component loads `https://evil.com/login` with the same field layout,
**Then** neither field is filled and `getCredential("https://evil.com")` returns empty.

#### AC7: Port and scheme are part of the origin key
**Given** a stored credential for `https://example.com` (implied port 443),
**When** the component loads `http://example.com/login` (scheme http) and separately `https://example.com:8443/login` (explicit non-default port),
**Then** neither page is autofilled, because neither origin equals `https://example.com`.

#### AC8: Programmatic save then get round-trips
**Given** a `WebViewComponent` on macOS,
**When** the host calls `saveCredential(new WebViewCredential("https://svc.example.com", "bob", "pw123"))` and then `getCredential("https://svc.example.com")`,
**Then** the returned credential has username `bob` and password `pw123`.

#### AC9: Programmatic delete removes the credential
**Given** a `WebViewComponent` on macOS with a stored credential `{https://svc.example.com, bob, pw123}`,
**When** the host calls `deleteCredential("https://svc.example.com", "bob")`,
**Then** the call returns `true` and a subsequent `getCredential("https://svc.example.com")` returns empty.

#### AC10: Saving a new password for an existing username overwrites it
**Given** a stored credential `{https://svc.example.com, bob, oldpw}`,
**When** the host calls `saveCredential(new WebViewCredential("https://svc.example.com", "bob", "newpw"))`,
**Then** `getCredential("https://svc.example.com")` returns password `newpw`, and there is exactly one credential for `{https://svc.example.com, bob}` (no duplicate).

#### AC11: Multiple usernames for one origin are all retained
**Given** a `WebViewComponent` on macOS,
**When** the host saves `{https://svc.example.com, bob, pw1}` and `{https://svc.example.com, carol, pw2}`,
**Then** `getCredentials("https://svc.example.com")` returns both credentials, and `getCredential("https://svc.example.com")` returns the most-recently-saved one (`carol`).

#### AC12: Disabling the manager suppresses prompt and autofill but keeps the API
**Given** a `WebViewComponent` on macOS with `setPasswordManagerEnabled(false)` and a stored credential `{https://example.com, alice, s3cret}`,
**When** the component loads `https://example.com/login` and the user submits a login form,
**Then** no autofill occurs, no save prompt appears, yet `getCredential("https://example.com")` still returns `alice`/`s3cret` (programmatic API unaffected).

#### AC13: Custom save-handler decides programmatically without UI
**Given** a `WebViewComponent` on macOS with `setSavePasswordHandler(e -> SavePasswordDisposition.SAVE)`,
**When** the user submits a login form with username `dan` / password `pw` at `https://example.com/login`,
**Then** no Swing prompt appears and `getCredential("https://example.com")` returns `dan`/`pw` within a bounded time.

#### AC14: Custom in-memory store replaces the Keychain for tests
**Given** a `WebViewComponent` on macOS with `setCredentialStore(inMemoryStore)` where `inMemoryStore` is a caller-supplied `WebViewCredentialStore`,
**When** a login submission is approved for saving,
**Then** the credential is written to `inMemoryStore` (observable directly on that object) and nothing is written to the OS Keychain.

#### AC15: Save-handler and store callbacks run on the EDT
**Given** a `WebViewComponent` on macOS with a custom save-handler and a custom store that each record `SwingUtilities.isEventDispatchThread()`,
**When** a login submission occurs and is approved,
**Then** the save-handler was invoked on the EDT (recorded `true`).

#### AC16: getCredentialStore / getSavePasswordHandler never return null
**Given** a freshly constructed `WebViewComponent` on macOS with nothing set,
**When** the host calls `getCredentialStore()` and `getSavePasswordHandler()`,
**Then** both return non-null default instances (the `NativeCredentialStore` and the Swing-prompt handler).

#### AC17: setCredentialStore(null) / setSavePasswordHandler(null) restore defaults
**Given** a `WebViewComponent` on macOS with a custom store and custom handler installed,
**When** the host calls `setCredentialStore(null)` and `setSavePasswordHandler(null)`,
**Then** `getCredentialStore()` returns a `NativeCredentialStore` and `getSavePasswordHandler()` returns the default Swing-prompt handler.

#### AC18: Password never appears in library output
**Given** a `WebViewComponent` on macOS with a stored credential `{https://example.com, alice, s3cret}` and autofill exercised,
**When** the host inspects `WebViewCredential.toString()`, `WebViewSavePasswordEvent.toString()`, and the library's stdout/stderr during save and fill,
**Then** the literal password `s3cret` appears in none of them (POJO `toString()` redacts it; the library logs no password).

#### AC19: No credential for the origin means no autofill and no error
**Given** a `WebViewComponent` on macOS with the manager enabled and an empty store,
**When** the component loads `https://example.com/login` with a login form,
**Then** neither field is filled, no prompt appears, and no exception is raised.

#### AC20: Save-handler exception does not crash the WebView
**Given** a `WebViewComponent` on macOS with a save-handler whose `onLoginSubmitted` throws a `RuntimeException`,
**When** the user submits a login form,
**Then** the exception surfaces via standard EDT uncaught-exception handling, nothing is stored, the WebView stays responsive, and the host app does not crash.

#### AC21: Re-submitting an unchanged credential does not re-prompt to save
**Given** a `WebViewComponent` with the manager enabled and a stored credential `{https://example.com, alice, s3cret}` (e.g. just autofilled),
**When** the user submits the login form with username `alice` and password `s3cret` (the same, unchanged credential),
**Then** no "Save password?" prompt appears and nothing new is written — the manager only offers to save a credential that is new or whose password changed.

#### AC22: A changed password still prompts to save
**Given** a `WebViewComponent` with the manager enabled and a stored credential `{https://example.com, alice, s3cret}`,
**When** the user submits the login form with username `alice` and a **different** password `n3wpw`,
**Then** the "Save password?" prompt appears (a changed password is offered for saving), and approving it updates the stored password to `n3wpw`.

### Non-Functional Expectations

- Passwords are stored only in the OS Keychain (encrypted at rest, unlocked by the user's macOS login). The library writes no plaintext credential file and prints no password.
- The bridge from the WebKit message thread to the EDT (for the save prompt) must not deadlock the WebKit UI thread, consistent with the constraint the dialog-handler feature already documents.
- Keychain I/O must not block the AppKit main thread for an unbounded period; where the platform allows, store operations are performed off the UI thread.
- Autofill is best-effort: it must populate fields that exist at `DOMContentLoaded` and should make a bounded re-attempt for forms inserted shortly after load, without busy-looping indefinitely.
- The detection heuristic must not fire a save prompt when no password field was involved (e.g. a search form submission must never trigger "Save password?").
- The injected script must expose no new page-visible global or callable that page JavaScript could use to exfiltrate stored credentials; the fill direction only ever writes the single origin-matched credential Java chose to send.

---

## [STORY-006-002] Linux libsecret Coverage for the Password Manager

### Background

STORY-006-001 establishes the password-manager Java contract, the shared detection/fill JavaScript, and the macOS Keychain backend. This story extends the feature to Linux — both `WebViewHeavyweightComponent` (X11-reparented WebKitGTK) and `WebViewLightweightComponent` (offscreen WebKitGTK), which share the same WebKit engine and injection model.

On Linux the two moving parts are:

- **Script injection + capture channel**: WebKitGTK injects document-start scripts via `webkit_user_content_manager_add_script` (a `WebKitUserScript` created with `WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START`) and delivers page→app messages via a registered script-message handler (`webkit_user_content_manager_register_script_message_handler` + the `script-message-received` signal). This is the same content-manager the console-capture and dialog features already use, so the shared JS from STORY-006-001 is injected here unchanged and its capture messages are received over the existing channel.
- **Secret store**: Linux has no single "OS Keychain"; the standard is the freedesktop **Secret Service** accessed through **libsecret** (GNOME Keyring, KWallet via its Secret Service interface, etc.). The store is implemented against `secret_password_store_sync` / `secret_password_lookup_sync` / `secret_password_clear_sync` with a stable `SecretSchema` whose attributes carry the library namespace, the origin, and the username. Secrets are encrypted at rest by the keyring and unlocked with the user's login keyring.

This story implements `NativeCredentialStore` for Linux against libsecret and wires the shared script + capture channel into the heavyweight and lightweight engines, so Linux callers get the same save + autofill behaviour and the same Java API semantics as macOS.

Key points:
- Business value: brings "save once, autofill forever" to Linux desktops, where the offscreen/reparented WebKit gives no built-in password UX at all.
- Relationship with other features: reuses the JNI callback-into-Java-from-the-GTK-thread pattern already used by `ConsoleDispatcher` and the dialog dispatch; no new JNI infrastructure.
- Why now: STORY-006-001 lands the contract; without this story `setPasswordManagerEnabled` / `saveCredential` do nothing on Linux.

### Business Value

- Provide **working "Save password?" capture** and **autofill on load** in both `WebViewHeavyweightComponent` and `WebViewLightweightComponent` on Linux.
- Provide a **libsecret-backed `NativeCredentialStore`** so Linux credentials are stored encrypted in the user's login keyring (GNOME Keyring / KWallet), never in a plaintext file.
- Fulfil the **cross-platform contract** from STORY-006-001: `saveCredential` / `getCredential` / `deleteCredential`, the save prompt, and autofill behave identically on Linux and macOS.

### Dependencies and Assumptions

- **Prerequisites**: STORY-006-001 complete (the `WebViewCredential`, `WebViewCredentialStore`, `WebViewSavePasswordHandler`, `WebViewSavePasswordEvent` types, the `WebViewComponent` methods, and the shared detection/fill JavaScript must already exist). Canvases 6 and 7 (heavyweight + lightweight engines) in place.
- **Data assumptions**: Same credential model as macOS; the only persisted state is the libsecret-stored secrets keyed by `{origin, username}` plus a library-namespace attribute.
- **Integration points**: WebKitGTK `WebKitUserContentManager` (`webkit_user_content_manager_add_script`, `webkit_user_content_manager_register_script_message_handler`, `script-message-received`) — the same manager instance the existing features use; and **libsecret** (`secret_password_store_sync`, `secret_password_lookup_sync`, `secret_password_clear_sync`, `SecretSchema`). libsecret is resolved the same way the codebase already resolves WebKitGTK symbols (runtime lookup; no hard SONAME pin beyond what the build documents).
- **Business constraints**:
  - The libsecret store must present the same origin-keying semantics as macOS (scheme+host+port, default port implied). The `SecretSchema` attributes are `service` (library namespace constant), `origin`, and `username`.
  - libsecret's synchronous calls may block on the Secret Service D-Bus round-trip; they must be invoked off the GTK main-loop thread (or on a worker) so the engine's UI thread is not parked on keyring I/O.
  - If **no Secret Service provider is available** (headless box with no keyring daemon, no D-Bus session), `NativeCredentialStore` operations fail gracefully: `save` reports failure without crashing, `find` returns empty, and the save prompt / autofill simply no-op. Tests that need determinism inject an in-memory store as on macOS.
  - The capture handler runs on the GTK main thread; the save-policy must run on the EDT. The bridge marshals GTK→EDT, waits for the disposition, and proceeds — using the same deadlock-free pattern as the dialog dispatch.

### Scope In

- Native (Linux, shared by heavyweight + lightweight): inject the shared STORY-006-001 detection/fill script at document-start via the engine's `WebKitUserContentManager`, and register a script-message handler that receives `{origin, username, password}` captures and forwards them to the Java password dispatcher over the existing JNI callback bridge.
- Native (Linux): implement the `NativeCredentialStore` operations (`save` / `find` / `findAll` / `delete`) against libsecret with a stable `SecretSchema`, exposed to Java through the same JNI entry points the macOS store uses (one native store, three platform bodies).
- Native (Linux): drive autofill by handing the origin-matched credential from Java to the injected script via the content-manager message channel, identically in both modes.
- Java: **no new public API** — all types come from STORY-006-001. Any internal dispatcher/JNI glue needed to route captures and store calls on Linux is internal.
- Behaviour-symmetric across heavyweight and lightweight: same injection site, same capture channel, same store, same default Swing prompt.

### Scope Out

- Windows coverage — STORY-006-003.
- macOS — STORY-006-001.
- A specific keyring backend requirement (GNOME Keyring vs KWallet): the library targets the freedesktop Secret Service via libsecret and works with whichever provider implements it; it does not ship or require a particular daemon.
- Prompting the user to unlock a locked login keyring: if the keyring is locked, libsecret/the Secret Service prompts per its own policy; the library adds no unlock UI of its own.
- The multi-step login and other limitations already listed as out-of-scope in STORY-006-001.

### Acceptance Criteria

#### AC1: Login submission triggers the save prompt on Linux heavyweight
**Given** a `WebViewHeavyweightComponent` on Linux with the manager enabled and no custom handler, loaded at `https://example.com/login` with a username input and `<input type="password">`,
**When** the user submits username `alice` / password `s3cret`,
**Then** a Swing "Save password?" prompt appears modal to the host `JFrame`, showing origin `https://example.com` and username `alice`.

#### AC2: Login submission triggers the save prompt on Linux lightweight
**Given** the same setup as AC1 but in `WebViewLightweightComponent`,
**When** the user submits the login form,
**Then** the same Swing "Save password?" prompt appears, with no `gdk_window_move_to_rect: 'window->transient_for'` warning attributable to this prompt.

#### AC3: Approving stores the credential in the login keyring (both modes)
**Given** a `WebViewComponent` on Linux (each mode in turn) with a Secret Service provider available and the save prompt showing,
**When** the user approves,
**Then** `getCredential("https://example.com")` afterwards returns username `alice` / password `s3cret`, and the secret is present in the user's keyring under the library namespace.

#### AC4: Saved credential autofills on next load (both modes)
**Given** a `WebViewComponent` on Linux (each mode in turn) with a stored credential `{https://example.com, alice, s3cret}`,
**When** the component loads `https://example.com/login` with empty username + password fields,
**Then** after load the username field reads `alice` and the password field reads `s3cret`, and the page observes `input`/`change` events for the filled fields.

#### AC5: Autofill is origin-exact on Linux
**Given** a `WebViewComponent` on Linux (each mode in turn) with a stored credential for `https://example.com`,
**When** it loads `https://evil.com/login` with the same layout,
**Then** neither field is filled.

#### AC6: Programmatic save/get/delete round-trip via libsecret (both modes)
**Given** a `WebViewComponent` on Linux (each mode in turn) with the default `NativeCredentialStore` and a Secret Service available,
**When** the host calls `saveCredential({https://svc.example.com, bob, pw123})`, then `getCredential("https://svc.example.com")`, then `deleteCredential("https://svc.example.com", "bob")`,
**Then** the get returns `bob`/`pw123`, the delete returns `true`, and a final `getCredential` returns empty.

#### AC7: Overwrite semantics match macOS
**Given** a stored credential `{https://svc.example.com, bob, oldpw}` on Linux,
**When** the host saves `{https://svc.example.com, bob, newpw}`,
**Then** `getCredential` returns `newpw` and exactly one credential exists for `{https://svc.example.com, bob}`.

#### AC8: Custom in-memory store bypasses libsecret
**Given** a `WebViewComponent` on Linux (each mode in turn) with `setCredentialStore(inMemoryStore)`,
**When** a login submission is approved,
**Then** the credential is written to `inMemoryStore` and nothing is written to the Secret Service.

#### AC9: Disabling the manager suppresses prompt and autofill (both modes)
**Given** a `WebViewComponent` on Linux (each mode in turn) with `setPasswordManagerEnabled(false)` and a stored credential for `https://example.com`,
**When** the page loads and a login form is submitted,
**Then** no autofill and no prompt occur, but `getCredential("https://example.com")` still returns the stored credential.

#### AC10: Handler callback runs on the EDT (both modes)
**Given** a `WebViewComponent` on Linux (each mode in turn) with a save-handler recording `SwingUtilities.isEventDispatchThread()`,
**When** a login submission occurs,
**Then** the recorded value is `true`.

#### AC11: Heavyweight and lightweight produce identical behaviour
**Given** the same login page and the same custom handler + custom store in `WebViewHeavyweightComponent` and `WebViewLightweightComponent` on Linux,
**When** an identical submit sequence runs,
**Then** both modes capture identical event field values (origin, username, password) and, on autofill, write identical field values.

#### AC12: Graceful degradation when no Secret Service is available
**Given** a `WebViewComponent` on Linux with the default `NativeCredentialStore` on a system with no available Secret Service provider,
**When** the host calls `saveCredential(...)` and later `getCredential(...)`,
**Then** the save fails without throwing to the point of crashing the app, `getCredential` returns empty, and page load / submission still work (the manager simply cannot persist).

#### AC13: Password never appears in library output on Linux
**Given** a `WebViewComponent` on Linux with a stored credential and autofill exercised,
**When** the host inspects the POJO `toString()`s and the library's stdout/stderr,
**Then** the literal password appears in none of them.

### Non-Functional Expectations

- libsecret's synchronous Secret Service calls must not park the GTK main-loop thread; keyring I/O runs off the UI thread so page rendering and event pumping continue.
- The GTK→EDT marshal for the save prompt must be deadlock-free under the heavyweight model where the GTK pump runs on its own thread separate from AWT's X11 thread, matching the dialog-handler constraint.
- Heavyweight and lightweight must produce byte-identical captured field values and identical stored/filled values for the same page input — no silent divergence between engines.
- The library writes no plaintext credential file on Linux; the only persistence is the Secret Service.

---

## [STORY-006-003] Windows Credential Manager Coverage for the Password Manager

### Background

STORY-006-001 establishes the password-manager Java contract, the shared detection/fill JavaScript, and macOS. STORY-006-002 adds Linux. This story extends the feature to Windows, where `WebViewHeavyweightComponent` uses Microsoft WebView2 (embedded Edge/Chromium).

WebView2 already has its own Edge password manager, but it is off by default and, more importantly, it stores into the user's Edge profile and is not exposed through the library's Java API — a host calling `saveCredential(...)` or expecting the library's uniform save prompt would find Windows behaving differently from macOS and Linux. To fulfil the cross-platform contract, Windows uses the **library's own** manager, exactly as the other platforms do, rather than Edge's.

The two moving parts on Windows are:

- **Script injection + capture channel**: WebView2 injects document-start scripts via `ICoreWebView2::AddScriptToExecuteOnDocumentCreated` and delivers page→app messages via `ICoreWebView2::add_WebMessageReceived` (the page calls `window.chrome.webview.postMessage(...)`). The codebase already shims `window.external.invoke` onto this channel and registers a `WebMessageReceived` handler, so the shared STORY-006-001 script is injected here and its captures arrive over the existing message pipe.
- **Secret store**: Windows credentials go in the **Windows Credential Manager** via `CredWrite` / `CredRead` / `CredDelete` (`CRED_TYPE_GENERIC`), with the target name derived from the library namespace + origin + username. The stored blob is protected by the OS (DPAPI, per-user) — no library-managed crypto.

This story implements `NativeCredentialStore` for Windows against the Credential Manager and wires the shared script + capture channel into the WebView2 engine, so Windows gets the same save + autofill behaviour and the same Java API semantics as macOS and Linux.

Key points:
- Business value: makes `setPasswordManagerEnabled` / `saveCredential` / the save prompt / autofill behave identically on Windows, using the library's manager rather than Edge's profile store.
- Relationship with other features: the WebView2 message channel and `AddScriptToExecuteOnDocumentCreated` are already used for the `window.external.invoke` shim and dialog dispatch; this story adds one more document-start script and reuses the same `WebMessageReceived` handler routing.
- Why now: closes the last platform gap in the cross-platform password-manager contract.

### Business Value

- Provide **handler-and-store consistency on Windows** — the library's own "Save password?" prompt, autofill on load, and `saveCredential`/`getCredential`/`deleteCredential` behave the same as macOS and Linux.
- Provide a **Credential-Manager-backed `NativeCredentialStore`** so Windows credentials are stored in the OS credential vault (DPAPI-protected), never in a plaintext file, and independent of the Edge profile.
- Make **programmatic and headless testing** work on Windows via the same in-memory-store / programmatic-handler seams.

### Dependencies and Assumptions

- **Prerequisites**: STORY-006-001 complete (Java types + shared script). STORY-006-002 is not a strict prerequisite, but any internal password dispatcher it introduces is reused here; landing 006-002 first reduces duplicated internal scaffolding.
- **Data assumptions**: Same credential model; the only persisted state is Credential-Manager entries keyed by a target name encoding library namespace + origin + username.
- **Integration points**: `ICoreWebView2::AddScriptToExecuteOnDocumentCreated`, `ICoreWebView2::add_WebMessageReceived` (already wired in `windows/webview_embed.cc`), and the Win32 Credential Management API (`CredWriteW`, `CredReadW`, `CredDeleteW`, `CredFree`) with `CRED_TYPE_GENERIC` and `CRED_PERSIST_LOCAL_MACHINE`/`CRED_PERSIST_ENTERPRISE` per-user persistence.
- **Business constraints**:
  - The Credential-Manager store must present the same origin-keying semantics as the other platforms (scheme+host+port, default port implied). The target name is a stable, collision-free encoding of `{namespace, origin, username}`; `CredEnumerate` with the namespace prefix backs `findAll` / `find`.
  - Windows' own WebView2 (Edge) password autosave stays **off** (`ICoreWebView2Settings4::put_IsPasswordAutosaveEnabled(FALSE)`, the default) so Edge's prompt never competes with the library's prompt.
  - The `WebMessageReceived` callback runs on the WebView2 worker thread; the save-policy must run on the EDT. The bridge marshals worker→EDT, waits for the disposition, and proceeds without parking the worker queue beyond the prompt's lifetime — the same discipline the dialog handler uses on Windows.
  - `CredRead`/`CredWrite` are fast local calls but still run off the message-pump-critical path where practical.

### Scope In

- Native (Windows): inject the shared STORY-006-001 detection/fill script via `AddScriptToExecuteOnDocumentCreated`, and route its `{origin, username, password}` captures through the existing `WebMessageReceived` handler to the Java password dispatcher.
- Native (Windows): implement the `NativeCredentialStore` operations (`save` / `find` / `findAll` / `delete`) against the Windows Credential Manager, exposed through the same JNI entry points as the macOS and Linux stores.
- Native (Windows): drive autofill by handing the origin-matched credential from Java to the injected script via `PostWebMessageAsJson` / `ExecuteScript` on the WebView2 instance.
- Native (Windows): ensure Edge's built-in password autosave is disabled so it does not double-prompt.
- Java: **no new public API** — all types come from STORY-006-001; internal glue only.
- README documentation: note that on Windows the library's own password manager is used (not Edge's profile store) and credentials live in the Windows Credential Manager.

### Scope Out

- macOS — STORY-006-001. Linux — STORY-006-002.
- Using or importing Edge's existing saved passwords from the user's Edge profile — the library uses its own Credential-Manager namespace; it neither reads nor writes Edge's store.
- Roaming/enterprise credential sync policies beyond choosing a per-user persistence flag.
- The multi-step login and other limitations already listed as out-of-scope in STORY-006-001.

### Acceptance Criteria

#### AC1: Login submission triggers the library's save prompt on Windows
**Given** a `WebViewHeavyweightComponent` on Windows 11 (WebView2 Runtime installed) with the manager enabled and no custom handler, loaded at `https://example.com/login` with a username input and `<input type="password">`,
**When** the user submits username `alice` / password `s3cret`,
**Then** the library's Swing "Save password?" prompt appears modal to the host `JFrame` (not Edge's built-in save-password bar), showing origin `https://example.com` and username `alice`.

#### AC2: Approving stores the credential in the Windows Credential Manager
**Given** the setup of AC1 with the prompt showing,
**When** the user approves,
**Then** `getCredential("https://example.com")` returns `alice`/`s3cret`, and a corresponding `CRED_TYPE_GENERIC` entry exists in the Windows Credential Manager under the library namespace.

#### AC3: Saved credential autofills on next load
**Given** a `WebViewHeavyweightComponent` on Windows with a stored credential `{https://example.com, alice, s3cret}`,
**When** the component loads `https://example.com/login` with empty fields,
**Then** after load the username field reads `alice`, the password field reads `s3cret`, and the page observes `input`/`change` events for the filled fields.

#### AC4: Autofill is origin-exact on Windows
**Given** a `WebViewHeavyweightComponent` on Windows with a stored credential for `https://example.com`,
**When** it loads `https://evil.com/login` with the same layout,
**Then** neither field is filled.

#### AC5: Programmatic save/get/delete round-trip via Credential Manager
**Given** a `WebViewHeavyweightComponent` on Windows with the default `NativeCredentialStore`,
**When** the host calls `saveCredential({https://svc.example.com, bob, pw123})`, then `getCredential(...)`, then `deleteCredential("https://svc.example.com", "bob")`,
**Then** the get returns `bob`/`pw123`, the delete returns `true`, and a final `getCredential` returns empty.

#### AC6: Overwrite semantics match the other platforms
**Given** a stored credential `{https://svc.example.com, bob, oldpw}` on Windows,
**When** the host saves `{https://svc.example.com, bob, newpw}`,
**Then** `getCredential` returns `newpw` and exactly one Credential-Manager entry exists for `{https://svc.example.com, bob}`.

#### AC7: Multiple usernames for one origin are all retained
**Given** a `WebViewHeavyweightComponent` on Windows,
**When** the host saves `{https://svc.example.com, bob, pw1}` and `{https://svc.example.com, carol, pw2}`,
**Then** `getCredentials("https://svc.example.com")` returns both, and `getCredential(...)` returns the most-recently-saved (`carol`).

#### AC8: Custom in-memory store bypasses the Credential Manager
**Given** a `WebViewHeavyweightComponent` on Windows with `setCredentialStore(inMemoryStore)`,
**When** a login submission is approved,
**Then** the credential is written to `inMemoryStore` and nothing is written to the Windows Credential Manager.

#### AC9: Disabling the manager suppresses prompt and autofill
**Given** a `WebViewHeavyweightComponent` on Windows with `setPasswordManagerEnabled(false)` and a stored credential for `https://example.com`,
**When** the page loads and a login form is submitted,
**Then** no autofill and no prompt occur, but `getCredential("https://example.com")` still returns the stored credential.

#### AC10: Edge's built-in save-password bar does not appear
**Given** a `WebViewHeavyweightComponent` on Windows with the default handler,
**When** the user submits a login form,
**Then** only the library's Swing prompt appears; Edge's own "Save password?" bar does not appear (Edge password autosave is disabled).

#### AC11: Handler callback runs on the EDT
**Given** a `WebViewHeavyweightComponent` on Windows with a save-handler recording `SwingUtilities.isEventDispatchThread()`,
**When** a login submission occurs,
**Then** the recorded value is `true`.

#### AC12: Password never appears in library output on Windows
**Given** a `WebViewHeavyweightComponent` on Windows with a stored credential and autofill exercised,
**When** the host inspects the POJO `toString()`s and the library's stdout/stderr,
**Then** the literal password appears in none of them.

#### AC13: Save-handler exception completes cleanly without crashing WebView2
**Given** a `WebViewHeavyweightComponent` on Windows with a save-handler whose `onLoginSubmitted` throws,
**When** the user submits a login form,
**Then** the exception surfaces via EDT uncaught-exception handling, nothing is stored, the WebView2 stays responsive, and the host app does not crash.

### Non-Functional Expectations

- The marshal from the WebView2 worker thread to the EDT and back must not require the worker queue to service EDT-dependent messages while it waits, matching the dialog-handler discipline.
- Credential-Manager entries are per-user and DPAPI-protected by the OS; the library writes no plaintext credential file and prints no password.
- Edge's own password autosave must be disabled from engine creation so it never competes with the library prompt.
- The Windows behaviour (library manager, Credential-Manager storage) must be documented in `README.md` so callers understand credentials are not shared with the Edge profile.

---

## [STORY-006-004] Autofill-Consent Handler (Confirm / Re-Authenticate Before Fill)

### Background

STORY-006-001 shipped the password manager with a deliberately asymmetric consent model. The **save** direction is gated: a captured login submission is routed through a `WebViewSavePasswordHandler` policy (default: a Swing "Save password?" prompt) that decides SAVE vs DON'T-SAVE before anything is written. The **fill** direction is *not* gated: on page load, when the manager is enabled and a credential exists for the origin, the library injects the username + password into the detected fields automatically, with no policy consulted and no confirmation shown.

That silent autofill is fine for many hosts, but it diverges from what mainstream browsers actually do. Chrome, Edge, and Safari's password managers typically **confirm** a fill and, on managed or sensitive systems, **re-authenticate** the user (OS login password, Windows Hello, Touch ID / biometric) before revealing or filling a stored password. A host embedding `WebViewComponent` today has no seam to insert that step: the fill path runs from store lookup straight to injection.

This story adds the missing seam — a fill-side policy exactly symmetric to the save-side one — so a host can require confirmation and/or an OS re-auth before autofill, and can decline a fill. It **explicitly reverses** the STORY-006-001 Scope-Out item *"Biometric / re-authentication gating before autofill (Touch ID prompt per fill). Out of scope; the OS unlocks the Keychain at login."* — not by shipping a biometric implementation itself, but by providing the decision point at which a host (or a future platform enhancement) can perform that gating.

Key points:
- Business value: brings the embedded WebView's autofill into line with browser-grade "confirm before fill" behaviour and unblocks hosts that must re-authenticate the user before a credential is revealed.
- Relationship with other features: mirrors `WebViewSavePasswordHandler` one-for-one (policy interface + Swing default + component setter/getter, invoked on the EDT), reusing the same dispatch discipline.
- Why now: with save + autofill shipped and being adopted, the silent-fill behaviour is the next gap between "works" and "behaves like a real browser's password manager".

### Business Value

- Provide a **fill-consent decision point** so a host can show a browser-style "Use saved password for `<origin>`?" confirmation before autofill occurs.
- Provide a **re-authentication seam** so a host can require an OS credential / biometric (Touch ID, Windows Hello) check and decline the fill if it fails — without the library shipping or mandating any particular biometric mechanism.
- Preserve the **zero-configuration happy path**: hosts that do nothing keep today's automatic autofill (no behavioural regression).
- Keep the fill decision **password-free**: the decision is made from the origin + username only; the stored password is never handed to the consent policy.

### Dependencies and Assumptions

- **Prerequisites**: STORY-006-001 complete (the `PasswordDispatcher` autofill path, `WebViewComponent` password API, and the shared fill script exist). No dependency on 006-002 / 006-003; being Java-contract-only, this story is cross-platform by construction and needs no native change.
- **Data assumptions**: Same credential model. The fill-consent event exposes `{source, origin, username}` — never the password.
- **Integration points**: purely internal — the existing `PasswordDispatcher.doFill(origin)` path and the `WebViewComponent` API surface. No native or JS change.
- **Business constraints**:
  - The consent policy is consulted **only** on the automatic autofill path (page-load fill). The programmatic API (`getCredential` / `getCredentials` / `getAllCredentials` / `saveCredential` / `deleteCredential`) is host-initiated trusted code and is **not** gated by this handler.
  - The handler runs on the Swing EDT (so it may show UI or block on a modal re-auth), consistent with `WebViewSavePasswordHandler`.
  - Backward compatibility is a **hard requirement**: the default handler must return FILL, so every STORY-006-001/002/003 autofill AC continues to pass unchanged.
  - The consent policy must never receive the password; only origin + username are exposed to it.

### Scope In

- New public enum `ca.weblite.webview.FillPasswordDisposition { FILL, DONT_FILL }`.
- New public POJO `ca.weblite.webview.WebViewFillPasswordEvent`, immutable — `WebViewComponent source()`, `String origin()`, `String username()`. It carries **no password** (no accessor, no field exposed via `toString()`); `toString()` shows origin + username only.
- New public interface `ca.weblite.webview.WebViewFillPasswordHandler` with a single method invoked on the EDT:
  - `FillPasswordDisposition onAutofillRequested(WebViewFillPasswordEvent event)`.
  - `WebViewFillPasswordHandler DEFAULT` — returns `FILL` unconditionally (preserves today's automatic autofill).
  - `WebViewFillPasswordHandler CONFIRM` — a ready-made opt-in policy that shows a Swing confirmation ("Use the saved password for `<origin>`?", showing the username, never the password) modal to the host window; OK → `FILL`, Cancel/close → `DONT_FILL`. Hosts install this (or their own, e.g. one that performs a biometric check) to require confirmation.
- New public methods on `WebViewComponent`:
  - `WebViewComponent setFillPasswordHandler(WebViewFillPasswordHandler handler)` — replace the fill policy; `null` reinstalls `WebViewFillPasswordHandler.DEFAULT`.
  - `WebViewFillPasswordHandler getFillPasswordHandler()` — never null.
- Autofill dispatch change: on the page-load fill path, after the origin-matched credential is found, the dispatcher consults the fill handler on the EDT with `{source, origin, username}`; it injects the credential only if the disposition is `FILL`, and does nothing (no injection, no error) on `DONT_FILL`. A handler exception is caught and forwarded to the default EDT uncaught-exception handling; nothing is filled and the WebView stays responsive.

### Scope Out

- Shipping an actual biometric / OS-credential implementation (Touch ID, Windows Hello, `LAContext`, `CredUIPromptForWindowsCredentials`). The story supplies the decision seam and a Swing-confirm default; a concrete biometric check is the host's to implement inside a custom handler (or a later story).
- Gating the **programmatic** read API (`getCredential`/`getCredentials`/`getAllCredentials`) — those are trusted host calls, not automatic fills.
- Per-field or per-credential fill selection UI (choosing among multiple usernames at fill time). Autofill continues to use the store's origin-`find` (most-recently-saved) result; richer selection is out of scope.
- Any change to the save-side policy, the shared JS, or the native layer.

### Acceptance Criteria

#### AC1: Default behaviour still autofills automatically (no regression)
**Given** a `WebViewComponent` with the manager enabled, a stored credential `{https://example.com, alice, s3cret}`, and no fill-handler installed,
**When** the component loads `https://example.com/login` with empty username + password fields,
**Then** after load the username field reads `alice` and the password field reads `s3cret`, with no confirmation shown — identical to STORY-006-001 AC4.

#### AC2: CONFIRM handler shows a confirmation before filling
**Given** a `WebViewComponent` with a stored credential `{https://example.com, alice, s3cret}` and `setFillPasswordHandler(WebViewFillPasswordHandler.CONFIRM)`,
**When** the component loads `https://example.com/login`,
**Then** a Swing confirmation appears modal to the host window asking to use the saved password for `https://example.com`, showing username `alice` and **not** showing the password.

#### AC3: Approving the confirmation fills the fields
**Given** the setup of AC2 with the confirmation showing,
**When** the user approves it,
**Then** the username field reads `alice` and the password field reads `s3cret`.

#### AC4: Declining the confirmation fills nothing
**Given** the setup of AC2 with the confirmation showing,
**When** the user cancels / closes it,
**Then** neither field is filled, no exception is raised, and the page is left as loaded.

#### AC5: A custom handler decides programmatically without UI
**Given** a `WebViewComponent` with a stored credential for `https://example.com` and `setFillPasswordHandler(e -> FillPasswordDisposition.DONT_FILL)`,
**When** the component loads `https://example.com/login`,
**Then** no prompt appears and neither field is filled.

#### AC6: The fill event never exposes the password
**Given** a `WebViewComponent` with a stored credential `{https://example.com, alice, s3cret}` and a custom fill-handler that records the event it receives,
**When** autofill is requested on load,
**Then** the recorded `WebViewFillPasswordEvent` exposes origin `https://example.com` and username `alice`, offers no accessor returning the password, and its `toString()` does not contain `s3cret`.

#### AC7: The fill handler runs on the EDT
**Given** a `WebViewComponent` with a fill-handler recording `SwingUtilities.isEventDispatchThread()`,
**When** autofill is requested on load,
**Then** the recorded value is `true`.

#### AC8: getFillPasswordHandler never returns null; setter null restores default
**Given** a freshly constructed `WebViewComponent`,
**When** the host calls `getFillPasswordHandler()`, then installs a custom handler, then calls `setFillPasswordHandler(null)` and `getFillPasswordHandler()` again,
**Then** the first call returns the non-null `DEFAULT`, and after the null reset the getter returns the `DEFAULT` handler again.

#### AC9: The programmatic read API is not gated by the fill handler
**Given** a `WebViewComponent` with a stored credential `{https://example.com, alice, s3cret}` and `setFillPasswordHandler(e -> FillPasswordDisposition.DONT_FILL)`,
**When** the host calls `getCredential("https://example.com")`,
**Then** it returns `alice`/`s3cret` — the DONT_FILL policy suppresses automatic autofill but does not block trusted host reads.

#### AC10: Disabling the manager suppresses fill regardless of handler
**Given** a `WebViewComponent` with `setPasswordManagerEnabled(false)`, a stored credential for `https://example.com`, and `setFillPasswordHandler(WebViewFillPasswordHandler.CONFIRM)`,
**When** the component loads `https://example.com/login`,
**Then** no confirmation appears and neither field is filled (the disabled manager short-circuits before the handler is consulted).

#### AC11: A fill-handler exception does not crash the WebView
**Given** a `WebViewComponent` with a fill-handler whose `onAutofillRequested` throws a `RuntimeException`,
**When** autofill is requested on load,
**Then** the exception surfaces via standard EDT uncaught-exception handling, nothing is filled, and the WebView stays responsive.

### Non-Functional Expectations

- The default configuration must not change any observable autofill behaviour shipped by STORY-006-001/002/003 — the fill handler is additive and defaults to FILL.
- The consent policy must be given only origin + username; the password must not be reachable from `WebViewFillPasswordEvent`.
- The handler is invoked on the EDT and may block on a modal confirmation or re-auth; the native engine thread must not be parked waiting on it (the fill path is already non-blocking off the native thread).

---

## [STORY-006-005] Enumerate-All Credentials for a Management UI

### Background

STORY-006-001 designed every credential **read** around a known origin: `WebViewCredentialStore.find(origin)` and `findAll(origin)`, surfaced as `getCredential(origin)` / `getCredentials(origin)` on `WebViewComponent`. That is exactly what autofill needs, but it makes one thing impossible: a host cannot ask *"which origins / credentials do I have saved?"* without already knowing every origin. Every mainstream browser exposes a password-management surface (Chrome's `chrome://password-manager`, Edge's `edge://settings/passwords`, Safari's Passwords pane) that lists **all** saved logins across **all** sites. A host embedding `WebViewComponent` cannot build that over the library's native OS store today, because there is no enumerate-all operation.

STORY-006-001 explicitly deferred this: its Scope-Out list includes *"A credential-management UI (a 'manage saved passwords' list/editor window). Hosts build their own on top of the programmatic API; the library ships only the save prompt."* The programmatic API it shipped, however, is insufficient to build one — it can add, edit (`saveCredential`), and delete (`deleteCredential`) for a known origin, but it cannot enumerate. This story supplies the missing primitive so that Scope-Out reversal is actually achievable: an all-origins enumeration, exposed on both the store seam and the component.

The native backends already contain the enumeration primitive; the Java contract simply never surfaced it. macOS's Keychain lookup already runs a two-phase `SecItemCopyMatching` and can pass `kSecMatchLimitAll` over the library's service namespace; Windows' `webview_cred_store_find` already calls `CredEnumerateW` with a namespace filter; Linux libsecret supports schema-wide search (`secret_password_search` / `secret_service_search`). This story lifts that capability into the public contract and implements the all-origins variant on each platform.

Key points:
- Business value: lets a host build a browser-grade "manage saved passwords" screen (list every saved login, then edit/delete via the existing API) over the library's native OS store.
- Relationship with other features: extends the `WebViewCredentialStore` seam and `WebViewComponent` API from 006-001; reuses each platform's existing enumeration primitive from 006-001 (macOS), 006-002 (Linux), 006-003 (Windows).
- Why now: with save/fill shipped, "manage what's saved" is the natural next capability, and it is the one primitive missing to make the 006-001 management-UI Scope-Out reversible.

### Business Value

- Provide an **enumerate-all** operation so a host can list every saved credential across every origin — the data a Chrome-style password-manager UI needs.
- **Complete the management surface**: combined with the existing `saveCredential` (add/edit) and `deleteCredential` (remove), enumerate-all lets a host implement full list/edit/delete over the native OS store.
- Preserve **cross-platform parity**: the enumerate-all semantics (ordering, dedup, degradation when no store is available) match across macOS, Linux, and Windows.
- Keep the **security posture** unchanged: enumeration returns credentials to trusted host Java only; page JavaScript still has no path to read any stored credential, and passwords are still never logged.

### Dependencies and Assumptions

- **Prerequisites**: STORY-006-001 (Java contract + macOS store) for the macOS enumerate body and the Java surface; STORY-006-002 (Linux libsecret) and STORY-006-003 (Windows Credential Manager) for the Linux and Windows enumerate bodies respectively. The Java contract change is independent; each native body lands on its platform's canvas.
- **Data assumptions**: Same credential model; the only persisted state remains the OS-native secret store entries under the library namespace. Enumerate-all reads exactly those entries.
- **Integration points**: `WebViewCredentialStore` + `WebViewComponent`; macOS Keychain (`SecItemCopyMatching` with `kSecMatchLimitAll` over the library service namespace); Windows Credential Manager (`CredEnumerateW` over the namespace prefix); Linux libsecret (`secret_password_search` / `secret_service_search` over the `SecretSchema`). `InMemoryCredentialStore` enumerates its in-memory map.
- **Business constraints**:
  - Enumerate-all returns **every** credential under the library namespace across **all** origins, most-recently-saved first (same recency contract as origin-scoped `findAll`).
  - It returns full `WebViewCredential` objects (origin + username + password) to the calling host Java, exactly as origin-scoped `find`/`findAll` already do — no new exposure surface; page JS still cannot reach it.
  - When the backing store is unavailable (no Secret Service on Linux, etc.), enumerate-all degrades to an empty list, never throwing — consistent with the existing store contract.
  - Passwords are still never logged or printed by the enumerate path.

### Scope In

- Extend `ca.weblite.webview.WebViewCredentialStore` with `java.util.List<WebViewCredential> findAll()` (no argument) — every credential across all origins under the library namespace, most-recently-saved first, as an unmodifiable list (empty when none / store unavailable).
- New public method on `WebViewComponent`: `java.util.List<WebViewCredential> getAllCredentials()` — delegates to the active store's no-argument `findAll()`.
- `InMemoryCredentialStore`: implement `findAll()` by enumerating its map, honouring the most-recently-saved-first ordering.
- `NativeCredentialStore`: implement `findAll()` via a new process-global JNI primitive that enumerates all namespaced entries; provide the three platform bodies:
  - macOS (Canvas 26): `SecItemCopyMatching` with `kSecMatchLimitAll` filtered to the library service namespace, two-phase (attributes then per-item secret) exactly as the origin-scoped find already does.
  - Linux (Canvas 27): libsecret schema-wide search over the `SecretSchema` (library-namespace attribute), returning every match.
  - Windows (Canvas 28): `CredEnumerateW` over the namespace target-prefix (the origin-scoped find already enumerates with a per-origin filter; this uses the namespace-wide filter).
- Ordering + dedup semantics identical to origin-scoped `findAll` (most-recently-saved first; at most one credential per `{origin, username}`).

### Scope Out

- A shipped management-UI window. This story supplies the enumerate primitive; the actual list/editor UI remains the host's to build (still out of scope per 006-001).
- A `savedOrigins()`-only variant returning just origins without credentials — `getAllCredentials()` returns full credentials, from which a host derives the origin list; a separate origins-only method is not added.
- Cross-origin search, fuzzy matching, or paging of results — enumerate-all returns the full set in one call.
- Any change to autofill, the save/fill handlers, or the shared JS.

### Acceptance Criteria

#### AC1: Enumerate-all returns credentials across multiple origins
**Given** a `WebViewComponent` whose store holds `{https://a.example, alice, pw1}`, `{https://b.example, bob, pw2}`, and `{https://c.example, carol, pw3}`,
**When** the host calls `getAllCredentials()`,
**Then** the returned list contains all three credentials (each origin + username + password), regardless of origin.

#### AC2: Enumerate-all is most-recently-saved first
**Given** a `WebViewComponent` where the host saves `{https://a.example, alice, pw1}` then `{https://b.example, bob, pw2}` then `{https://c.example, carol, pw3}` in that order,
**When** the host calls `getAllCredentials()`,
**Then** the list is ordered most-recently-saved first (`carol`, then `bob`, then `alice`).

#### AC3: Multiple usernames on one origin are all enumerated
**Given** a `WebViewComponent` whose store holds `{https://svc.example, bob, pw1}` and `{https://svc.example, carol, pw2}`,
**When** the host calls `getAllCredentials()`,
**Then** both credentials for `https://svc.example` appear in the result.

#### AC4: Empty store enumerates to an empty list
**Given** a `WebViewComponent` with an empty store,
**When** the host calls `getAllCredentials()`,
**Then** it returns an empty list (not null) and raises no exception.

#### AC5: Deletion is reflected in enumerate-all
**Given** a `WebViewComponent` whose store holds `{https://a.example, alice, pw1}` and `{https://b.example, bob, pw2}`,
**When** the host calls `deleteCredential("https://a.example", "alice")` and then `getAllCredentials()`,
**Then** the result contains only `{https://b.example, bob, pw2}`.

#### AC6: In-memory store enumerates for tests
**Given** a `WebViewComponent` with `setCredentialStore(inMemoryStore)` where two credentials for different origins have been saved,
**When** the host calls `getAllCredentials()`,
**Then** it returns both credentials from `inMemoryStore`, and nothing is read from the OS-native store.

#### AC7: Native round-trip — save two origins then enumerate (per platform)
**Given** a `WebViewComponent` on macOS / Linux / Windows (each in turn) with the default `NativeCredentialStore` and a working OS secret store,
**When** the host saves `{https://svc1.example, u1, p1}` and `{https://svc2.example, u2, p2}` and then calls `getAllCredentials()`,
**Then** both credentials are returned from the OS-native store with their correct usernames and passwords.

#### AC8: Enumerate-all degrades gracefully when the store is unavailable
**Given** a `WebViewComponent` on a system whose OS secret store is unavailable (e.g. no Secret Service on Linux),
**When** the host calls `getAllCredentials()`,
**Then** it returns an empty list without throwing.

#### AC9: Enumerate-all does not expose credentials to page JavaScript
**Given** a `WebViewComponent` with stored credentials and a loaded page,
**When** page JavaScript attempts to reach a stored credential (there is no injected global or channel that returns one),
**Then** the page cannot obtain any credential; enumerate-all is reachable only from host Java via `getAllCredentials()`.

#### AC10: Password never appears in library output during enumeration
**Given** a `WebViewComponent` with stored credentials,
**When** the host calls `getAllCredentials()` and inspects the returned objects' `toString()` and the library's stdout/stderr,
**Then** no literal password appears (POJO `toString()` redacts it; the enumerate path logs no password).

### Non-Functional Expectations

- Enumerate-all's ordering, dedup, and graceful-degradation semantics must be identical across macOS, Linux, and Windows and consistent with the existing origin-scoped `findAll`.
- The enumerate path performs OS secret-store I/O off the engine UI thread where the platform requires it, matching the existing store-I/O discipline.
- The library writes no plaintext credential file and prints no password during enumeration.
- Page JavaScript gains no new capability — enumerate-all is host-Java-only; the injected fill script remains write-only and origin-scoped.

---

## Quality Checks

**STORY-006-001 (Java API + shared JS + macOS Keychain)**:
- ✅ All required sections present (Background, Business Value, Dependencies and Assumptions, Scope In/Out, Acceptance Criteria, Non-Functional Expectations).
- ✅ ACs use Given-When-Then with concrete inputs (`alice`/`s3cret`, `https://example.com` vs `https://evil.com` vs `https://example.com:8443`) and observable outcomes (`getCredential(...)` returns specific values, fields read specific strings, password absent from output).
- ✅ Business-language ACs — public API names (`setPasswordManagerEnabled`, `saveCredential`, `WebViewSavePasswordHandler`) appear as the user-visible contract; no JNI signatures or ObjC selectors inside AC bodies (native APIs appear only in Background / Dependencies / Scope-In).
- ✅ Covers happy path (save prompt, autofill), business rules (origin-exact, overwrite, multiple usernames), control (enable/disable, custom store, custom handler), and error/security (handler exception, password never logged).
- ✅ At most three core functional points (Java API, shared detection/fill JS, macOS native + Keychain store).
- ✅ 4-5 days of work.

**STORY-006-002 (Linux libsecret)**:
- ✅ All required sections present.
- ✅ ACs run "in each mode in turn" for heavyweight + lightweight; parity AC (AC11) checks identical behaviour; graceful-degradation AC (AC12) covers the no-keyring case.
- ✅ Business-language ACs; libsecret / WebKitGTK symbol names appear only in Background / Dependencies / Scope-In.
- ✅ No new public API — conforms to the STORY-006-001 contract.
- ✅ At most two core functional points (WebKitGTK script injection + capture, libsecret store).
- ✅ 3-4 days of work.

**STORY-006-003 (Windows Credential Manager)**:
- ✅ All required sections present.
- ✅ Concrete inputs/outputs; AC10 explicitly asserts Edge's built-in bar does not appear; AC13 covers handler-exception cleanup.
- ✅ Business-language ACs; COM / Win32 names appear only in Background / Dependencies / Scope-In.
- ✅ No new public API — conforms to the STORY-006-001 contract.
- ✅ Two core functional points (WebView2 script injection + capture, Credential-Manager store).
- ✅ 2-3 days of work.

**STORY-006-004 (Autofill-consent handler)**:
- ✅ All required sections present.
- ✅ ACs use Given-When-Then with concrete inputs (`alice`/`s3cret`, `https://example.com`) and observable outcomes (fields filled or not, confirmation shown or not, EDT recorded `true`, password absent from event).
- ✅ Business-language ACs; the public contract (`WebViewFillPasswordHandler`, `FillPasswordDisposition`, `setFillPasswordHandler`) is the user-visible surface. No native names in ACs (there are none — Java-contract-only).
- ✅ Explicitly reverses the 006-001 Scope-Out item "Biometric / re-authentication gating before autofill" by supplying the decision seam (AC1 guarantees the default is a no-regression auto-fill).
- ✅ Covers backward-compat default (AC1), opt-in confirm (AC2-4), programmatic decision (AC5), password-free event (AC6), EDT (AC7), null-restore (AC8), ungated read API (AC9), disabled-manager short-circuit (AC10), exception isolation (AC11).
- ✅ One core functional point (a fill-side policy symmetric to the save-side one).
- ✅ 1-2 days of work.

**STORY-006-005 (Enumerate-all credentials)**:
- ✅ All required sections present.
- ✅ ACs use Given-When-Then with concrete multi-origin fixtures and observable outcomes (ordering, dedup, deletion reflected, empty→empty, graceful degradation, password absent from output).
- ✅ Business-language ACs; the public contract (`getAllCredentials`, `WebViewCredentialStore.findAll()`) is the surface; native enumerate APIs appear only in Background / Dependencies / Scope-In.
- ✅ Explicitly reverses the 006-001 Scope-Out item "A credential-management UI … Hosts build their own on top of the programmatic API" by supplying the one missing primitive (all-origins enumeration).
- ✅ Covers multi-origin enumerate (AC1), recency ordering (AC2), multi-username (AC3), empty (AC4), deletion (AC5), in-memory (AC6), native per-platform round-trip (AC7), graceful degradation (AC8), no page-JS exposure (AC9), password never logged (AC10).
- ✅ Two core functional points (the Java enumerate-all seam, the per-platform native enumerate bodies).
- ✅ 2-3 days of work.

## Final INVEST Re-validation

| Property | STORY-006-001 | STORY-006-002 | STORY-006-003 |
|---|---|---|---|
| Independent | ✅ (designs the API + shared JS; depends only on existing engine plumbing) | ✅ (depends only on the 006-001 contract; native code independent) | ✅ (depends only on the 006-001 contract; native code independent of Linux's) |
| Complete | ✅ (full Java contract + shared JS + working macOS save/fill/store) | ✅ (full Linux coverage, both modes, libsecret store) | ✅ (full Windows coverage, Credential-Manager store) |
| Valuable | ✅ (fixes the concrete macOS retyping pain; ships the contract) | ✅ (brings save/autofill to Linux desktops) | ✅ (uniform library manager on Windows, independent of Edge profile) |
| Estimable | ✅ (POJOs + two interfaces + component methods + one ObjC injection + Keychain JNI + shared JS) | ✅ (one content-manager injection + libsecret JNI, reusing the JS + contract) | ✅ (one AddScriptToExecuteOnDocumentCreated + Credential-Manager JNI, reusing the JS + contract) |
| Right-sized | ✅ (4-5 days) | ✅ (3-4 days) | ✅ (2-3 days) |
| Testable | ✅ (in-memory store + programmatic handler drive save/fill headlessly; Swing harness shows default UX) | ✅ (both-mode ACs + parity AC + graceful-degradation AC) | ✅ (in-memory store + programmatic handler; Edge-bar-suppression AC) |

| Property | STORY-006-004 | STORY-006-005 |
|---|---|---|
| Independent | ✅ (Java-contract-only; depends only on the 006-001 fill path; no native change) | ✅ (Java seam is independent; each native enumerate body builds on that platform's shipped store) |
| Complete | ✅ (policy interface + Swing default/confirm + component seam + gated dispatch) | ✅ (enumerate-all on the store + component + all three native bodies + in-memory) |
| Valuable | ✅ (browser-grade confirm/re-auth before fill; unblocks re-auth-required hosts) | ✅ (supplies the one primitive a Chrome-style manage-passwords UI needs) |
| Estimable | ✅ (one enum + one POJO + one interface with two default impls + two component methods + one dispatch tweak) | ✅ (one no-arg store method + one component method + one JNI primitive with three platform bodies + in-memory impl) |
| Right-sized | ✅ (1-2 days) | ✅ (2-3 days) |
| Testable | ✅ (default-fill no-regression AC + programmatic DONT_FILL + EDT + password-free event, all headless) | ✅ (in-memory enumerate + per-platform native round-trip + ordering/dedup/degradation ACs) |

All five stories pass INVEST.
