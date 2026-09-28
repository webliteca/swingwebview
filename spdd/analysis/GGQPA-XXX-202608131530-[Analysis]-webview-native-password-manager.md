# SPDD Analysis: Built-in Password Manager for the Embedded WebView

## Original Business Requirement

The complete, verbatim requirement is the three-story decomposition in
`requirements/[User-story-6]webview-native-password-manager.md`
(STORY-006-001 macOS + Java contract + shared JS, STORY-006-002 Linux
libsecret, STORY-006-003 Windows Credential Manager). It is preserved in
that file and not re-pasted here to avoid divergence; this analysis treats
that file as the authoritative requirement and references its ACs by number.

In one paragraph: the embedded engines (`WKWebView`, `WebKitGTK`,
`WebView2`) withhold the browser "offer to save this password / autofill it
next time" experience from third-party host apps — concretely, an Okta
login inside `WebViewComponent` on macOS forces the user to retype the
password every time. The feature builds the library's own password manager:
injected JS detects login-form submissions and the library shows a Swing
"Save password?" prompt; on approval the credential is written to the
OS-native secret store (Keychain / libsecret / Windows Credential Manager);
on a later page load a stored credential for the same origin is auto-filled.
A public Java API on `WebViewComponent` (enable/disable, get/save/delete,
plus overridable store and save-policy seams) makes it deterministic for
hosts and headless tests. Credentials are keyed by page origin
(scheme+host+port); autofill is exact-origin only.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **`WebViewComponent`** (`src/ca/weblite/webview/swing/WebViewComponent.java`)
  — abstract base + static factory (`create()` picks HEAVYWEIGHT on
  mac/win, LIGHTWEIGHT on linux). **Owns all per-component handler/dispatcher
  state** (`consoleDispatcher`, `mouseDispatcher`, `dialogDispatcher`,
  `popupDispatcher`), each constructed eagerly and surviving native
  peer create/destroy. This is where the new password-manager state and
  public API attach — as a sibling dispatcher + `final` methods on the base,
  never re-implemented per subclass. This exactly matches how
  `setDialogHandler` / `getDialogHandler` are `final` on the base and delegate
  to `dialogDispatcher`.
- **Dispatcher pattern** (`DialogDispatcher`, `ConsoleDispatcher`,
  `WebViewMouseDispatcher`, `EvalDispatcher`, `FunctionDispatcher`) — the
  established "per-component fan-out hub between the native/JS layer and a
  Java handler, with EDT marshaling and exception isolation" shape. The new
  feature introduces a `PasswordDispatcher` in this family. Two flavours
  already exist and matter for the design decision below:
  - **Blocking** (`DialogDispatcher`): `SwingUtilities.invokeAndWait`, native
    thread parked until the handler returns. Used because `alert`/`confirm`/
    `prompt` are *synchronous* JS contracts.
  - **Non-blocking** (`ConsoleDispatcher`, `WebViewMouseDispatcher`):
    `invokeLater`, fire-and-forget to the EDT. Used for events with no JS
    return value.
- **`addOnBeforeLoad(String js)`** (`WebViewComponent.java:286`) — the
  existing **document-start user-script injection** mechanism. Every
  dispatcher bootstraps by injecting its `SHIM_JS` here; it is replayed on
  every new document and on peer re-attach. Maps natively to
  `WKUserScript` (macOS, `webview_embed.cpp:5691`),
  `webkit_user_script_new(... INJECT_AT_DOCUMENT_START)` (Linux,
  `:1897`), and `AddScriptToExecuteOnDocumentCreated` (Windows,
  `webview_embed.cc:2155`). **The detection/fill script is injected through
  this existing path — no new injection plumbing is required.**
- **`addJavascriptFunction(name, fn)`** (`WebViewComponent.java:350`,
  `FunctionDispatcher`) — the **deadlock-free, value-returning** JS→Java
  channel: page calls `window.<name>(arg)` → `Promise`, and the Java handler
  runs **on a background worker pool, never the engine UI thread**
  (`FunctionDispatcher.java:141`). This is the natural home for a keychain
  *read* on the autofill path, because keychain I/O can block and must not
  sit on the UI/EDT thread.
- **`addJavascriptCallback(name, cb)`** — one-way JS→Java message channel
  (no return), used by console/mouse. Natural home for the *save* capture
  (page posts a submission; no synchronous answer needed).
- **Reserved-binding convention** — `RESERVED_BINDING_PREFIX = "__webview_"`
  (`WebViewComponent.java:59`); public binding registration rejects this
  prefix, internal channels bypass the check. Existing reserved names:
  `__webview_console__`, `__webview_dom_event`, `__webview_eval_result__`,
  `__webview_fn_call__`. The feature adds new reserved names in this family.
- **JNI callback registration pattern** — `Engine::<name>_callback` jobject
  global ref, set via `NewGlobalRef` in a `..._set_<name>_callback` function,
  deleted on destroy; native→Java invocation attaches the thread defensively,
  resolves the method ID per-call, calls, then `ExceptionCheck/Clear`
  (canonical: `fire_dialog_alert`, `webview_embed.cpp:475`). The
  native-mediated password channel (below) follows this shape.
- **Native-stamped frame URL** — the dialog selectors read the *committed*
  frame URL natively from the engine (`webView.URL.absoluteString` on macOS)
  and hand it to Java as `pageUrl`/`frameUrl`. This is the only trusted
  source of origin, and it is the pivot of the security design below.

### New Concepts Required

- **Credential** (`WebViewCredential`) — the `{origin, username, password}`
  value object. New public POJO; value-equality on `{origin, username}`,
  password redacted from `toString`/`equals`.
- **Origin** — normalised `scheme + host + port` (default port implied by
  scheme). The credential key and the anti-phishing boundary. A small
  normalisation concept, new to this codebase (no existing URL/origin
  utility was found).
- **Credential store** (`WebViewCredentialStore` seam + `NativeCredentialStore`
  default) — the storage abstraction. New. Backed per-platform by the
  OS-native secret store. **Greenfield on the native side**: a repo-wide
  grep found zero existing `SecItem` / `libsecret` / `CredWrite` usage, and
  `Security.framework` / `libsecret` / `wincred` are not yet linked.
- **Save policy** (`WebViewSavePasswordHandler` + `WebViewSavePasswordEvent`)
  — the "should this captured submission be saved?" decision, default =
  Swing prompt, overridable/programmatic. New, mirrors `WebViewDialogHandler`.
- **Password dispatcher** (`PasswordDispatcher`) — new per-component hub
  routing native captures/lookups to the store and the save policy.
- **Detection/fill script** — the shared injected JS that locates login
  fields, reports submissions, and writes filled values + fires
  `input`/`change`. New; injected via existing `addOnBeforeLoad`.

### Key Business Rules

- **Origin-exact matching** governs `WebViewCredential` ↔ page: a credential
  for origin X is only ever offered on origin X (STORY-006-001 AC6, AC7; and
  the Linux/Windows mirrors). Scheme and port are part of the key.
- **Overwrite, not duplicate** on `{origin, username}` (AC10); multiple
  distinct usernames per origin are all retained, and `find` returns the
  most-recently-saved (AC11).
- **Enable/disable gates the automatic behaviour only** — disabling stops the
  prompt and autofill, but the programmatic get/save/delete API still
  functions (AC12).
- **Password is secret material** — stored only in the OS store; never
  logged, printed, redacted in `toString`, never written to a library file
  (AC18 and the "password never in output" ACs on all three platforms).
- **Store and save-policy are replaceable seams; getters never return null;
  `setX(null)` restores the default** (AC14, AC16, AC17) — identical
  invariant discipline to `getDialogHandler() != null`.
- **A non-password form submission must never trigger "Save password?"**
  (STORY-006-001 NFE) — the detector keys on the presence of a password
  field.

## Strategic Approach

### Solution Direction

Clone the **dispatcher + reserved-channel + `addOnBeforeLoad`** architecture
already proven by the dialog/console features, and attach a new
`PasswordDispatcher` and public API to the abstract `WebViewComponent` base
exactly as `dialogDispatcher` is attached. The data flows are:

- **Save**: injected detection JS observes a login submission and posts the
  captured `{username, password}` over a reserved channel → the **native
  message handler stamps the committed frame origin** → `PasswordDispatcher`
  → EDT (`invokeLater`, non-blocking) → `WebViewSavePasswordHandler`
  (default Swing prompt) → on `SAVE`, write to the store **off the EDT/UI
  thread**.
- **Autofill**: on document load, the injected JS requests a credential; the
  **native layer supplies the trusted committed origin**; Java looks up the
  store **on a worker thread** and, on a hit and with the manager enabled,
  pushes `{username, password}` back into the page (which sets the field
  values and dispatches `input`/`change`).
- **Store**: `NativeCredentialStore` calls **process-global** JNI store
  primitives implemented per-platform (Keychain / libsecret / Credential
  Manager). Because the secret store is process-global, these primitives need
  no per-engine handle.

The public Java surface (`WebViewCredential`, `WebViewCredentialStore`,
`WebViewSavePasswordHandler`, `WebViewSavePasswordEvent`, and the
`WebViewComponent` methods) is designed once in STORY-006-001 and unchanged
by the Linux/Windows stories, which add only native store + native channel
wiring — the same split the dialog feature used.

### Key Design Decisions

- **Non-blocking dispatch (`invokeLater`), NOT the dialog's blocking
  `invokeAndWait`.** → Trade-off: the dialog dispatcher parks the native JS
  thread because `alert`/`confirm`/`prompt` are synchronous JS contracts. A
  login submission has **no synchronous JS contract** — the form submits and
  navigates regardless of whether we save — and autofill is a page-load side
  effect, not a JS return value. Blocking the engine thread on a Swing prompt
  here would be gratuitous (it would freeze the page during navigation) and
  risk the very deadlocks the dialog Safeguards warn about. **Recommendation:
  model `PasswordDispatcher` on the non-blocking `WebViewMouseDispatcher` /
  `ConsoleDispatcher` shape, not `DialogDispatcher`.** Keychain I/O runs on a
  worker (the `addJavascriptFunction` pool or a dedicated single-thread
  executor), never the EDT or the engine UI thread.

- **Origin is stamped natively; it is NEVER accepted as a JS-supplied
  argument.** → This is the security crux. A reserved channel binding is a
  real global function the page can call **directly**, bypassing our injected
  script — so if the origin were a channel argument, a page at `evil.com`
  could request `bank.com`'s credential. `location.origin` read inside our
  script is genuinely unforgeable (`[Unforgeable]` in WebIDL), but the
  *channel* is not. Therefore the trusted origin must come from the engine,
  exactly as the dialog selectors already read `webView.URL.absoluteString`
  natively. **Recommendation: the save/fill channel is native-mediated per
  platform** (the message handler reads the committed frame URL and hands it
  to Java), which is the real reason each platform needs native work beyond
  the store. Any origin value that arrives from JS is ignored.

- **Autofill = JS-initiated ping + native origin-stamp + Java-push via
  `eval`; no general navigation callback is needed.** → Given the previous
  decision, the fill path cannot trust a JS-supplied origin. A repo search
  confirmed there is **no** general navigation/load-finished callback and no
  `getUrl` in the Java API (only popup events carry a natively-stamped
  `pageUrl`). Rather than build a navigation subsystem, resolve the origin the
  same way the dialog selectors already do: the injected script, once the DOM
  is ready and a login form is present, posts a one-way "fill request" on a
  **native-mediated** reserved channel; the **native message handler reads the
  engine's committed URL at the moment the message arrives**
  (`webView.URL` / `webkit_web_view_get_uri` / WebView2 `get_Source`) — which
  is by construction the document that just ran the script — and hands that
  trusted origin to Java. Java looks up the store on a worker and, on a hit
  with the manager enabled, pushes the credential back by `eval`-ing a fill
  entrypoint (`window.__webview_pw_fill__(user, pass)`) that only *writes*
  values into the detected fields and never returns a credential (so a page
  calling it directly can only fill its own form with attacker-chosen text —
  harmless). This works for redirect chains (Okta) because the origin is read
  per-message from the committed document, not tracked across navigations.
  **This closes the earlier open question** — no navigation/load callback has
  to be added; the resolution is stamp-at-message-time, mirroring dialog
  `frameUrl`.

- **Store primitives are process-global static JNI, greenfield native.** →
  macOS: add `-framework Security` to `build-mac.sh`, use `SecItem*`
  generic-password items namespaced to the library. Linux: add **libsecret**,
  and — to match the existing runtime-`dlopen` convention for WebKitGTK
  (`webkit_loader.cpp`) rather than hard-linking — `dlopen` libsecret so a
  missing keyring degrades gracefully instead of failing to load the `.so`.
  Windows: `wincred.h` (`CredWriteW`/`CredReadW`/`CredDeleteW`) via Advapi32.
  Trade-off: static (no engine handle) keeps the store independent of any
  component's lifecycle, which matches a machine-global secret store and lets
  the programmatic API work before any page loads.

- **Reuse `addOnBeforeLoad` for injection; add reserved channel name(s) in
  the `__webview_` family.** → No new injection infrastructure; the script is
  replayed per-document automatically. Likely names: a save-capture channel
  and a fill entrypoint. The `RESERVED_BINDING_PREFIX` guard already prevents
  application code from colliding with them.

### Alternatives Considered

- **Reuse the blocking `DialogDispatcher` channel for the save prompt.** →
  Rejected: it would freeze the page/engine during the prompt for no
  contract reason and invites the documented `invokeAndWait` deadlocks.
- **Pure-JS channel with a JS-supplied origin (`addJavascriptFunction`
  taking `origin`).** → Rejected on security grounds: the reserved binding is
  page-callable directly, so a JS-supplied origin is spoofable and would
  allow cross-origin credential theft.
- **Inject the script into an isolated content world** (WKContentWorld /
  WebKitScriptWorld) so the reserved binding is not page-callable, then trust
  a JS-supplied origin. → Rejected for this iteration: content-world support
  is uneven across the three engines (well-supported on WKWebView 11+,
  partial on WebKitGTK, awkward on WebView2's
  `AddScriptToExecuteOnDocumentCreated`), so it cannot deliver a uniform
  contract. Native origin-stamping is uniform and strictly safer. Worth
  revisiting later as defense-in-depth.
- **A library-managed encrypted file instead of the OS store.** → Already
  rejected in the requirement (the user chose OS-native). Noted only to
  record that it would put master-key management on the library, which the
  OS store avoids.
- **Standalone `WebView` (non-embedded) coverage.** → Deferred, same
  boundary the dialog feature drew; the feature targets `WebViewComponent`.

## Risk & Gap Analysis

### Requirement Ambiguities

- **Trusted committed-URL signal for autofill — RESOLVED.** The requirement
  says "autofill on page load". A repo search confirmed no general
  navigation/load callback and no `getUrl` exists, so the design does not
  depend on one: the native message handler stamps the engine's committed URL
  at the instant the injected script posts its fill request (mirroring dialog
  `frameUrl`). The remaining Canvas task is only to specify the per-engine URL
  read (`webView.URL` / `webkit_web_view_get_uri` / WebView2 `get_Source`),
  not to introduce a navigation subsystem. AC4/AC5 are no longer gated.
- **What counts as a "login submission" in a SPA / redirect flow.** The
  requirement acknowledges best-effort for non-`submit` login buttons and
  explicitly scopes out *guaranteed* multi-step (identifier-first) capture.
  The Canvas must pin the heuristic (real `submit` event + a best-effort
  synthetic-submit observer) and keep the "no password field ⇒ no prompt"
  invariant.
- **"Most-recently-saved" ordering** (`find` when multiple usernames exist,
  AC11) requires a recency signal. The OS stores don't all preserve insertion
  order natively; the Canvas must specify how recency is derived (e.g. a
  stored timestamp attribute) so AC11 is deterministic.
- **Origin string canonical form.** Whether the stored/returned origin string
  omits the default port (`https://example.com`, not `:443`) must be fixed so
  AC7's equality checks and the store key are unambiguous.

### Edge Cases

- **Autofilled credential is readable by page scripts.** Once filled into the
  DOM, any script on the page (including third-party/XSS) can read the
  password field — this is exactly browser autofill's baseline exposure, not
  a regression, but it must be documented and kept in scope-bounds (fill only
  the single origin-matched credential Java chose to send).
- **Redirect chains (Okta).** The motivating flow bounces across SSO
  redirects; each committed document is a separate origin/URL. Save capture
  works per page; cross-page username↔password correlation is explicitly
  out of scope (documented limitation).
- **Locked / absent keyring on Linux.** No Secret Service provider, or a
  locked login keyring: `save` must fail gracefully, `find` returns empty,
  page load/submission still work (STORY-006-002 AC12).
- **iframe / cross-origin subframe login.** Matching is exact top-frame
  origin only; a login inside a cross-origin iframe is out of scope and must
  not fill with the top origin's credential.
- **Two components / simultaneous prompts.** Each component has its own
  dispatcher; EDT serialises prompts. Non-blocking dispatch means no engine
  thread is parked while they queue.
- **Manager toggled or store swapped mid-session** — the enabled flag and
  store reference are read per-operation (volatile), like the dialog handler.
- **Password containing channel/JSON metacharacters or newlines** — the
  save-capture payload encoding must be binary-safe (the console channel's
  base64 precedent applies) so a password with `|`, quotes, or newlines
  round-trips intact.

### Technical Risks

- **Native origin-stamping must hook the right thread/URL** on each engine
  (`WKScriptMessageHandler` message → `webView.URL`; WebKitGTK
  `script-message-received` → `webkit_web_view_get_uri`; WebView2
  `WebMessageReceived` → `args->get_Source`/`webview->get_Source`). Getting
  the *committed* (not pending) URL is the correctness-critical detail.
  Mitigation: mirror the dialog selectors' proven URL read; add a focused
  test page that asserts origin under redirect.
- **Keychain/keyring I/O latency** blocking a UI/EDT thread. Mitigation: all
  store calls on a worker; the design already routes reads through the
  worker-pool function channel and writes after the EDT prompt returns.
- **Greenfield native linkage** (`Security.framework`, libsecret dlopen,
  Advapi32) across three build scripts and the runtime-dlopen convention on
  Linux. Mitigation: additive build changes; libsecret dlopen keeps the `.so`
  loadable where the library is absent.
- **JNI safety on native callbacks** — global-ref lifecycle, per-call method
  ID resolution, `ExceptionCheck/Clear`, attach/detach symmetry. Mitigation:
  copy the `fire_dialog_*` helpers verbatim in shape.
- **Password material on the JVM heap** (Java `String` immutability means it
  lingers until GC). Mitigation: minimise retention, never log, redact
  `toString`; a `char[]`-based path is possible but the requirement's
  "minimise, don't guarantee zeroisation" bar is met by discipline. Note as a
  known limitation.
- **Header staleness** — `ca_weblite_webview_WebViewNative.h` is
  javah-generated and already partial; new natives are implemented as mangled
  `JNIEXPORT` directly. Low risk, matches current practice.

### Acceptance Criteria Coverage

STORY-006-001 (macOS + contract + shared JS):

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Submission triggers Swing save prompt | Yes | Detection JS + native-stamped origin + non-blocking prompt |
| 2 | Approve → stored in Keychain | Yes | `SecItemAdd`, worker-thread write |
| 3 | Decline → nothing stored | Yes | Save-policy `DONT_SAVE` path |
| 4 | Autofill on next load | Yes | Native origin stamped at fill-request message time; Java-push via eval |
| 5 | Autofill fires input/change | Yes | Fill entrypoint dispatches events |
| 6 | Origin-exact (evil.com not filled) | Yes | Native origin, exact match |
| 7 | Scheme+port part of key | Yes | Origin normalisation rule must be fixed |
| 8 | Programmatic save→get | Yes | Store API |
| 9 | Programmatic delete | Yes | `SecItemDelete` |
| 10 | Overwrite existing username | Yes | Upsert semantics |
| 11 | Multiple usernames retained; find = most recent | Partial | Needs a recency attribute in the store |
| 12 | Disable suppresses UI, API still works | Yes | Enabled flag gates automation only |
| 13 | Custom save-handler, no UI | Yes | Policy seam |
| 14 | Custom in-memory store | Yes | Store seam |
| 15 | Callbacks on EDT | Yes | Dispatcher marshals |
| 16 | Getters never null | Yes | Default instances |
| 17 | setX(null) restores default | Yes | Invariant discipline |
| 18 | Password never in output | Yes | Redaction + no logging |
| 19 | No credential ⇒ no fill, no error | Yes | Lookup-miss path |
| 20 | Save-handler exception isolated | Yes | Try/catch → default uncaught handler |

STORY-006-002 (Linux): AC1–AC13 all addressable; **AC4** shares the
committed-URL gate; **AC12** (graceful degradation) needs the dlopen-libsecret
approach; parity **AC11** requires identical field values across
heavyweight/lightweight (single shared JS + single dispatcher makes this
natural).

STORY-006-003 (Windows): AC1–AC13 all addressable; **AC10** (Edge bar
suppressed) needs `put_IsPasswordAutosaveEnabled(FALSE)` at engine creation;
**AC3/AC4** share the committed-URL gate via WebView2's `get_Source`.

**Overall:** every AC is addressable, and the previously-flagged cross-cutting
dependency (a trusted committed-URL signal for autofill) is resolved by
stamping origin at fill-request message time — no navigation subsystem is
required. Everything maps cleanly onto the existing dispatcher /
`addOnBeforeLoad` / reserved-channel / native-callback / JNI patterns plus the
greenfield native store per platform. The remaining genuinely-open items are
smaller and local to the Canvas: the origin canonical-form rule (AC7), the
recency attribute for `find` ordering (AC11), and the SPA/synthetic-submit
detection heuristic — all specifiable without new infrastructure.

---

# Addendum Analysis: STORY-006-004 (Autofill-Consent Handler) and STORY-006-005 (Enumerate-All)

This addendum extends the analysis above to the two follow-on stories that
reverse Scope-Out items 006-001 originally deferred. It treats the shipped
code (`PasswordDispatcher`, `WebViewCredentialStore`, `NativeCredentialStore`,
`WebViewComponent` password API, and the three native store bodies) as the
authoritative baseline and analyses only the deltas.

## Shipped baseline the deltas build on

- **`PasswordDispatcher`** (`src/ca/weblite/webview/PasswordDispatcher.java`)
  — holds `store` (volatile), `handler` (the save-policy, volatile),
  `enabled` (volatile), and a single-thread `io` executor
  (`webview-password-io`, daemon). Two native-facing entrypoints:
  - `dispatchLoginSubmitted(...)` → `invokeLater(runPrompt)` → consults the
    **save** handler on the EDT → on SAVE, `io.execute(safeSave)`.
  - `dispatchFillRequested(frameUrl)` → `io.execute(doFill)` →
    `store.find(origin)` → `source.eval("window.__webview_pw_fill__(...)")`.
    **`doFill` consults no policy — this is the exact gap 006-004 fills.**
- **`WebViewCredentialStore`** — `save`, `find(origin)`, `findAll(origin)`,
  `delete(origin, username)`. Implementations must be thread-safe and must
  degrade to "no result" rather than throw. **No no-argument enumerate — the
  exact gap 006-005 fills.**
- **`NativeCredentialStore`** delegates to process-global JNI primitives
  `webview_cred_store_{save,find,delete,available}`; `find`/`findAll` already
  wrap a JNI find that returns an array. The three native bodies each already
  enumerate namespaced items (macOS two-phase `SecItemCopyMatching`; Windows
  `CredEnumerateW` with a per-origin filter; Linux libsecret lookup).

## STORY-006-004 — strategic direction

**This is a pure, additive symmetry fix on the fill path — no native, no JS.**
The save path already proves the exact shape: a policy interface with a Swing
default, a component setter/getter delegating to the dispatcher, EDT
invocation, and try/catch exception isolation forwarding to the default
uncaught-exception handler. 006-004 mirrors it on the fill side.

### Key decisions

- **Where the gate goes.** `doFill` currently runs entirely on the `io`
  worker: it does `store.find(origin)` (correctly off the EDT, because
  keychain reads can block), then evals. The consent policy must run on the
  **EDT** (it may show UI / a modal re-auth), exactly like `runPrompt`. So
  `doFill`'s shape becomes: (1) `store.find(origin)` on the `io` worker as
  today; (2) if present, marshal `{source, origin, username}` to the EDT via
  `invokeLater` and consult the fill handler; (3) on `FILL`, eval the
  write-only fill entrypoint. The eval itself is already safe to issue from
  the EDT (it is a fire-and-forget `source.eval`). This keeps the blocking
  keychain read off the EDT while putting only the (host-controlled) consent
  decision on it — the same split the save path uses (worker for I/O, EDT for
  policy).
- **Default must be FILL (hard backward-compat constraint).** Existing
  006-001/002/003 autofill ACs assert fields are filled with no prompt. So
  `WebViewFillPasswordHandler.DEFAULT` returns `FILL` unconditionally and is
  the dispatcher's initial handler. The **`CONFIRM`** constant is the opt-in
  browser-style Swing prompt; it reuses the `SwingUtilities.getWindowAncestor`
  + `JOptionPane.showConfirmDialog` recipe from
  `WebViewSavePasswordHandler.DEFAULT`, showing origin + username only.
- **Password-free event.** `WebViewFillPasswordEvent` carries `source`,
  `origin`, `username` — deliberately **no password accessor**. This is a
  security improvement over reusing `WebViewSavePasswordEvent` (which exposes
  the password): the fill decision needs only identity, so the password is
  never handed to host consent code. `doFill` already has the full credential
  in hand; it simply does not put the password into the event.
- **Disabled-manager short-circuit stays first.** `doFill` already returns
  early when `disposed || !enabled`; the handler is consulted only after that,
  so AC10 (disabled ⇒ no confirmation, no fill) holds for free.
- **Re-auth is a seam, not an implementation.** The story ships the decision
  point + a Swing-confirm default; a biometric/Windows-Hello/Touch-ID check is
  a host-supplied handler (or a later story). No `LAContext`/Windows Hello
  code in this story — that keeps it Java-only and cross-platform.

### Edge cases

- **Handler throws** → caught in `doFill`, forwarded to
  `Thread.getDefaultUncaughtExceptionHandler` (the existing `forward` helper);
  nothing filled, engine responsive (AC11).
- **Handler blocks on a modal** → it is on the EDT, not the native engine
  thread; the native side already returned after posting the fill request, so
  nothing native is parked (the fill path was already non-blocking).
- **Programmatic reads unaffected** — `getCredential`/`getCredentials`/
  `getAllCredentials` call the store directly and never touch the fill
  handler (AC9).
- **Multiple fill requests** (SPA late-inserted form posts `F` again) — each
  request re-consults the handler; a host that shows a modal should expect one
  prompt per request, matching today's one-eval-per-request behaviour.

### AC coverage — STORY-006-004

| AC# | Description | Addressable? | Notes |
|-----|-------------|--------------|-------|
| 1 | Default still autofills, no prompt | Yes | `DEFAULT` returns FILL; dispatcher initial handler |
| 2 | CONFIRM shows confirmation, no password | Yes | `CONFIRM` Swing prompt, origin+username only |
| 3 | Approve → filled | Yes | FILL → eval fill entrypoint |
| 4 | Decline → nothing filled, no error | Yes | DONT_FILL → skip eval |
| 5 | Programmatic DONT_FILL handler | Yes | Policy seam, no UI |
| 6 | Event exposes no password | Yes | `WebViewFillPasswordEvent` has no password accessor |
| 7 | Handler on EDT | Yes | `invokeLater` marshal in `doFill` |
| 8 | Getter non-null; setter null restores default | Yes | Same invariant discipline as save handler |
| 9 | Programmatic read not gated | Yes | Read API bypasses the fill handler |
| 10 | Disabled manager ⇒ no fill regardless of handler | Yes | `!enabled` early-return precedes handler |
| 11 | Handler exception isolated | Yes | try/catch → `forward` |

## STORY-006-005 — strategic direction

**One new no-argument enumerate that lifts an existing native capability into
the public contract.** The Java surface gains `WebViewCredentialStore.findAll()`
(all origins) and `WebViewComponent.getAllCredentials()`; the native layer
gains one process-global JNI primitive (`webview_cred_store_find_all`) with
three platform bodies that reuse each platform's already-present enumeration.

### Key decisions

- **Return full credentials, not just origins.** `getAllCredentials()` returns
  `List<WebViewCredential>` (origin+username+password), from which a host
  derives the origin list for a manager UI. A separate `savedOrigins()` is not
  added (Scope-Out) — one method, richest result, no new exposure surface
  beyond what `find`/`findAll` already return to host Java.
- **Reuse the per-platform enumerate primitive.**
  - **macOS** (`src_c/webview_embed.cpp`, Canvas 26): the existing
    `webview_cred_store_find` already does a two-phase read (phase-1
    attributes via `kSecMatchLimitAll`, phase-2 per-item secret). `find_all`
    is the same two-phase read with the **service-namespace-only** query (drop
    the per-origin service filter, keep the `kSecClassGenericPassword` +
    library-namespace scoping). The two-phase split is what avoids the
    `errSecParam` bug the shipped code already documents — reuse it verbatim.
  - **Windows** (`windows/webview_embed.cc`, Canvas 28): `webview_cred_store_find`
    already calls `CredEnumerateW` with a `"<svc>:*"`-style filter and decodes
    the `b64url(origin)|b64url(username)` target names. `find_all` uses the
    **namespace-prefix filter** (`"<namespace>:*"`) and decodes every match —
    the decode/blob-parse path is identical; only the filter widens.
  - **Linux** (`src_c/webview_embed_linux.*` per Canvas 27): libsecret schema
    search (`secret_password_search` / `secret_service_search_sync` over the
    `SecretSchema` with only the library-namespace attribute bound) returns
    every entry; map each to `{origin, username, password}` from the schema
    attributes + secret. Same graceful-degradation (`available()` false ⇒
    empty) contract.
- **Recency ordering reused.** Every platform already stamps `savedAtMillis`
  in the value blob (`millis\npassword`) and orders origin-scoped `findAll`
  most-recent-first. `find_all` applies the same sort across the full set, so
  ordering/dedup semantics are identical to origin-scoped `findAll` (AC2, AC3).
- **`InMemoryCredentialStore`** enumerates its map (all origins), applying the
  same most-recent-first ordering it already uses per-origin (AC6).
- **No page-JS exposure.** Enumerate-all is a host-Java call only; there is no
  reserved channel or injected global that returns it. The fill script stays
  write-only and origin-scoped (AC9). This is a pure host-side capability.

### Edge cases / risks

- **Native enumerate cannot be locally compiled for Windows/Linux** (macOS dev
  machine) — same constraint the shipped Windows body hit. Mitigation: mirror
  the existing `find` body's structure exactly (only the query filter widens),
  and rely on the CI native build matrix (all-platforms jar + per-arch native
  jobs) to validate compilation, as the shipped code did.
- **Large stores** — enumerate returns the full set in one call (Scope-Out:
  paging). Acceptable for a personal password store; noted.
- **Namespace collisions** — the query is scoped to the library service
  namespace exactly as `find` is, so it never returns Safari/Edge/other-app
  items (the shipped `find` already guarantees this scoping).
- **Empty / unavailable store** → empty list, never throw (AC4, AC8),
  matching the store contract.

### AC coverage — STORY-006-005

| AC# | Description | Addressable? | Notes |
|-----|-------------|--------------|-------|
| 1 | Enumerate across multiple origins | Yes | `find_all` namespace-scoped query |
| 2 | Most-recently-saved first | Yes | Reuse `savedAtMillis` sort across full set |
| 3 | Multiple usernames per origin | Yes | Enumerate returns each `{origin,username}` |
| 4 | Empty store ⇒ empty list | Yes | No-match ⇒ empty, non-null |
| 5 | Deletion reflected | Yes | Enumerate re-reads store |
| 6 | In-memory store enumerates | Yes | Map enumeration |
| 7 | Native round-trip per platform | Yes | Three native bodies reuse existing enumerate |
| 8 | Graceful degradation | Yes | `available()` false ⇒ empty |
| 9 | No page-JS exposure | Yes | Host-Java-only; fill script stays write-only |
| 10 | Password never logged | Yes | Redacting `toString`; no logging on enumerate path |

## Overall

Both stories map cleanly onto shipped patterns with **no new infrastructure**:
006-004 is a Java-only fill-side mirror of the existing save-side policy
(dispatcher tweak + one enum + one password-free POJO + one interface with
DEFAULT/CONFIRM + two component methods); 006-005 is one no-argument store
method + one component method + one JNI primitive whose three bodies each widen
an already-present per-origin enumerate to the namespace scope. The only
genuinely non-local risk is native enumerate compilation on Windows/Linux,
mitigated by structural parity with the shipped `find` bodies and the CI native
matrix.
