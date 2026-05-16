---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: Swing Heavyweight WebView Embedding

## R · Requirements
- Embed a native WebView inside a Swing UI as a **heavyweight AWT
  child** so a single Java application can mix Swing widgets and
  a real browser engine in-process
  (`WebViewHeavyweightComponent.java:49`, `EmbeddedWebView.java:30`).
- The component must:
  - Be a `JComponent` subclass that callers add to any Swing
    container (`WebViewHeavyweightComponent.java:49`).
  - Create the native peer lazily on first display
    (`addNotify`/first `paint`), and tear it down on
    `removeNotify` (`WebViewHeavyweightComponent.java:142`,
    `WebViewHeavyweightComponent.java:235`).
  - Track Swing visibility transitions (e.g. tab switching in a
    `JTabbedPane`, parent hidden) so the heavyweight peer hides
    when the Swing-side region is not showing
    (`WebViewHeavyweightComponent.java:208`).
  - Replay pending URL, init scripts, and bindings at peer-create
    time (`WebViewHeavyweightComponent.java:152`).
  - Resize the native peer when the Swing region changes
    (`WebViewHeavyweightComponent.java:191`).
- Per-platform behaviour (`README.md ("Platform support" section)`):
  - **macOS** (Cocoa / WKWebView): full fidelity — rendering,
    input, resize, tab visibility all work.
  - **Linux** (WebKitGTK / X11): rendering, mouse, scroll,
    resize, tab switching work. Visible text-input feedback
    (caret blink, character display) is unreliable — see
    `README.md ("Platform support" section)`.
  - **Windows** (WebView2): full fidelity on Windows 11 with the
    Edge WebView2 Runtime installed.
- Implement the developer-visibility surface declared in
  [[swing-webview-component-mode-selection]]:
  - `openDevTools(): boolean` — when `debug=true` was set
    before display, opens the platform's native inspector in
    a separate OS window and returns `true`. Linux calls
    `webkit_web_inspector_show(webkit_web_view_get_inspector(...))`;
    Windows calls `ICoreWebView2::OpenDevToolsWindow()` on
    the WebView2 worker thread; macOS returns `false` because
    no public API exists to programmatically pop the Web
    Inspector (the inspector is reachable via right-click →
    Inspect Element and Safari Develop menu when the
    inspector flags are set at create time).
  - Console capture — install the canonical JS shim from
    `ConsoleDispatcher.SHIM_JS` via `addOnBeforeLoad`, bind
    `__webview_console__` to a callback that routes the raw
    payload into `ConsoleDispatcher.dispatch`. Both happen
    inside `createPeer()` after `EmbeddedWebView.attach`. The
    install is per peer-attach, not per navigation — the
    underlying init-script + script-message-handler machinery
    re-fires automatically on every new document load.
- macOS-only: when `debug=true`, the native `cocoa_create_engine`
  block that already sets `developerExtrasEnabled=YES` MUST
  also call `setInspectable:YES` on the `WKWebView` instance,
  guarded by `respondsToSelector:@selector(setInspectable:)`.
  This exposes the inspector via the Safari Develop menu on
  macOS 13.3+. On macOS 12.x and earlier the selector does
  not exist and is skipped — right-click → Inspect Element
  still works via the existing `developerExtrasEnabled` flag.
- Definition of Done: documented by `README.md ("Heavyweight platform notes" section)` ("Heavyweight
  platform notes") and exercised by the `WebViewHeavyweightDemo`
  (`demos/WebViewHeavyweightDemo/...`). No automated tests cover
  this — it is GUI integration code.

## E · Entities
- **WebViewHeavyweightComponent** (extends `WebViewComponent`,
  `WebViewHeavyweightComponent.java:49`). Invariants:
  - Contains exactly one inner `EmbeddedCanvas`
    (`WebViewHeavyweightComponent.java:62`) added at
    `BorderLayout.CENTER`.
  - `embedded: EmbeddedWebView` is null until first paint and
    after `dispose()` (`WebViewHeavyweightComponent.java:52`,
    `WebViewHeavyweightComponent.java:125`).
  - `pendingUrl`, `pendingInit`, `pendingBindings`, `debug` hold
    configuration applied before the peer exists
    (`WebViewHeavyweightComponent.java:54`).
  - Owns one `ConsoleDispatcher` instance for the lifetime of
    the component (created in the constructor). Listeners may
    be registered against it before display; the JS shim is
    installed when `createPeer()` fires so messages start
    flowing automatically.
  - Overrides `isHeavyweight()` to return `true`
    (`WebViewHeavyweightComponent.java:69`).
  - Overrides `openDevTools()` to delegate to
    `EmbeddedWebView.openDevTools()`; returns `false` when
    `embedded == null`.
  - Overrides `addJavascriptCallback(name, cb)` to reject any
    name starting with `__webview_` (matches the canvas-5
    reserved-prefix norm).
- **EmbeddedCanvas** (inner class, extends `Canvas`,
  `WebViewHeavyweightComponent.java:186`) — the heavyweight peer
  that JAWT can lock to obtain a native window handle. Invariants:
  - `peerAttached: volatile boolean` flips to `true` on the first
    `paint()` and back to `false` in `removeNotify()`
    (`WebViewHeavyweightComponent.java:188`,
    `WebViewHeavyweightComponent.java:236`).
  - `update(g)` calls `paint(g)` so the peer-creation hook in
    `paint` fires the first time AWT decides to refresh
    (`WebViewHeavyweightComponent.java:256`).
- **EmbeddedWebView** (`EmbeddedWebView.java:30`) — Low-level
  wrapper around the native embedded peer. Holds `long peer`,
  a `heap: List<Object>` to anchor JNI callbacks
  (`EmbeddedWebView.java:33`), and the bindings map
  (`EmbeddedWebView.java:34`). Invariants:
  - `peer == 0` means disposed; every public method calls
    `checkAlive()` first (`EmbeddedWebView.java:204`).
  - `attach(Component, debug)` REQUIRES the component to be
    displayable (`EmbeddedWebView.java:53`) and the native
    `webview_embed_create` to return a non-zero pointer
    (`EmbeddedWebView.java:58`).
  - New method `openDevTools(): boolean` — calls the new JNI
    entry point `webview_embed_open_devtools(peer)` and
    returns `(nativeResult == 1)`. `checkAlive` guards it.
    The native call is responsible for marshaling to the
    correct UI thread on platforms that require it
    (Windows: WebView2 worker; macOS: AppKit main).

## A · Approach
- **Heavyweight peer hosts the native view.** AWT/JAWT exposes a
  native window handle (X11 Window on Linux, NSView ancestry on
  macOS, HWND on Windows) only for heavyweight components. The
  embed strategy reparents the native browser as a child of that
  handle, so the host's event loop (Swing/AWT) drives input
  dispatch and visibility — `webview_run()` is never called for
  embedded WebViews (`README.md ("Heavyweight popup notes" section)`,
  `WebViewNative.java:108`).
- **Deferred peer creation on first paint.** The native peer
  cannot be created safely in `addNotify` on macOS because the
  AppKit-side NSView is built asynchronously after `addNotify`
  returns. Hooking peer creation into the first `paint()` gives
  both the EDT tree and the AppKit NSView a chance to exist
  before JAWT is locked (`WebViewHeavyweightComponent.java:243`).
- **Coordinate translation for macOS.** The native side parents
  the WKWebView onto `NSWindow.contentView`, so positioning
  inside Swing requires converting the canvas's location to the
  AWT Window content-pane coordinate space and subtracting the
  window insets (`WebViewHeavyweightComponent.java:171`).
- **Trade-off accepted: heavyweight popup interaction.** Because
  the WebView is a real heavyweight peer, lightweight Swing
  popups (`JComboBox` dropdowns, tooltips, menus) render BEHIND
  it unless the application opts into heavyweight popups via
  `JPopupMenu.setDefaultLightWeightPopupEnabled(false)`
  (`README.md ("Heavyweight popup notes" section)`,
  `WebViewHeavyweightDemo.java:40`).
- **Click-to-focus handled native side on Linux.** The class
  used to install AWT mouse/focus listeners to forward focus
  into the embedded view; they were removed because on Linux
  they caused AWT to alter the canvas's X11 event-mask in a way
  that broke rendering. Focus is now driven from a native-side
  button-press hook that calls `XSetInputFocus` on the popup
  (`WebViewHeavyweightComponent.java:223`).
- **DevTools dispatch is a single-shot native call per
  platform.** No state is added on the Java side beyond what
  is needed to surface the result. The native
  `webview_embed_open_devtools` returns 1 when the platform
  opened (or focused-existing) an inspector window, 0
  otherwise. Per-platform native logic:
  - Linux: short-circuit to 0 when the engine's
    `WebKitWebView` has developer-extras disabled (i.e. was
    created with `debug=false`); otherwise call
    `webkit_web_inspector_show` on the inspector returned by
    `webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web))`
    and return 1. Null-guard the inspector pointer.
  - Windows: marshal a message to the WebView2 worker thread
    via the existing `PostThreadMessage` pattern used by
    `navigate`/`eval`. The worker checks `AreDevToolsEnabled`
    on the settings interface and, when enabled, calls
    `webview->OpenDevToolsWindow()` and signals success back
    via a shared atomic. Return 1 on success, 0 on
    disabled/failure.
  - macOS: return 0 unconditionally. The inspector is
    enabled at create time via `developerExtrasEnabled=YES`
    and (when the selector exists) `setInspectable:YES`, so
    the user can right-click → Inspect Element or connect
    via the Safari Develop menu.
- **Console bridge install is per peer-attach, not per
  navigation.** The same `addOnBeforeLoad(SHIM_JS)` +
  `addJavascriptCallback("__webview_console__", ...)` pair
  that runs once inside `createPeer()` covers every
  subsequent navigation, because the underlying
  `webkit_user_script_new` / `WKUserScript` /
  `AddScriptToExecuteOnDocumentCreated` machinery re-fires at
  document-start for every new document. No re-install on
  page change is required.

## S · Structure
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  — Swing wrapper and lifecycle.
- `src/ca/weblite/webview/EmbeddedWebView.java` — low-level
  embed JNI wrapper.
- `src/ca/weblite/webview/WebViewNative.java:131`–
  `src/ca/weblite/webview/WebViewNative.java:177` — native
  entry points (`webview_embed_create`,
  `webview_embed_navigate`, etc.). Adds one new declaration:
  `native static int webview_embed_open_devtools(long w)`.
- `src/ca/weblite/webview/ConsoleDispatcher.java` (from
  [[swing-webview-component-mode-selection]]) — owned by the
  component; receives raw payloads from the internal
  `__webview_console__` binding callback registered in
  `createPeer`.
- `src_c/webview_embed.cpp`, `windows/webview_embed.cc` — native
  implementations (Linux+macOS and Windows respectively). New
  implementations: the Linux+macOS file gains a function
  exported as `Java_..._webview_1embed_1open_1devtools` that
  switches on the engine kind (Linux vs macOS) for the body;
  the Windows file gains the WebView2-worker-marshalled
  implementation. The macOS `cocoa_create_engine` block is
  extended to call `setInspectable:YES` under
  `respondsToSelector` when `debug=true`.
- `demos/WebViewHeavyweightDemo/...` — interactive demo that
  exercises the trickier scenarios (combo-box popups over the
  WebView, `JTabbedPane` tab visibility).

## O · Operations

### 1. Embedded Native Wrapper — EmbeddedWebView
File: `src/ca/weblite/webview/EmbeddedWebView.java`

1. Responsibility: own a single native embed peer and expose
   the typed JNI operations (`attach`, `setBounds`, `setVisible`,
   `navigate`, `eval`, `addJavascriptCallback`, `dispatch`,
   `pumpEvents`, `dispose`).
2. Fields:
   - `peer: long` — native pointer; `0` means disposed
     (`EmbeddedWebView.java:32`).
   - `heap: List<Object>` — anchors JNI callbacks against GC
     (`EmbeddedWebView.java:33`).
   - `bindings: Map<String, JavascriptCallback>` — current
     bound JS callbacks (`EmbeddedWebView.java:34`).
3. Methods:
   - `attach(Component parent, boolean debug): EmbeddedWebView`
     - Logic: null-check `parent`
       (`EmbeddedWebView.java:50`); require
       `parent.isDisplayable()` (`EmbeddedWebView.java:53`); call
       `WebViewNative.webview_embed_create(parent, debug?1:0)`
       (`EmbeddedWebView.java:57`); throw if zero
       (`EmbeddedWebView.java:58`); wrap the peer in a new
       instance.
   - `setBounds(int x, int y, int w, int h): EmbeddedWebView` —
     `checkAlive`, call `webview_embed_set_bounds`
     (`EmbeddedWebView.java:77`).
   - `setVisible(boolean): EmbeddedWebView` —
     `webview_embed_set_visible(peer, b?1:0)`
     (`EmbeddedWebView.java:88`).
   - `requestFocus(): EmbeddedWebView` —
     `webview_embed_request_focus(peer)`
     (`EmbeddedWebView.java:103`).
   - `navigate(String): EmbeddedWebView` —
     `webview_embed_navigate(peer, url)`
     (`EmbeddedWebView.java:112`).
   - `addOnBeforeLoad(String js): EmbeddedWebView` —
     `webview_embed_init(peer, js)` (`EmbeddedWebView.java:121`).
   - `eval(String js): EmbeddedWebView` —
     `webview_embed_eval(peer, js)` (`EmbeddedWebView.java:130`).
   - `addJavascriptCallback(String name, JavascriptCallback cb)`
     — store `name → cb` in `bindings`; build a
     `WebViewNativeCallback` that looks up the binding by name;
     anchor in `heap`; call `webview_embed_bind`
     (`EmbeddedWebView.java:139`).
   - `dispatch(Runnable r): EmbeddedWebView` — wrap so the
     wrapper removes itself from `heap` after running; anchor
     wrapper in `heap`; call `webview_embed_dispatch`
     (`EmbeddedWebView.java:160`).
   - `pumpEvents(boolean waitForEvent): int` — proxy to
     `webview_embed_pump`; only non-trivial on Linux/GTK where
     the GTK loop is independent of AWT's X11 loop
     (`EmbeddedWebView.java:186`).
   - `dispose(): void` — if `peer != 0`, zero it, call
     `webview_embed_destroy`, clear `heap` and `bindings`
     (`EmbeddedWebView.java:194`).
   - `openDevTools(): boolean` (new)
     - Logic: `checkAlive`; call
       `WebViewNative.webview_embed_open_devtools(peer)`;
       return `(result == 1)`. Does NOT touch `heap` or
       `bindings` — pure side-effect call.
4. Constraints / Invariants:
   - `checkAlive` throws `IllegalStateException` after dispose
     (`EmbeddedWebView.java:204`).
   - `dispose` is idempotent (the `peer != 0` guard,
     `EmbeddedWebView.java:195`).
   - Native callbacks must be anchored in `heap` or the JVM
     would collect them while the native side still holds a
     pointer — same invariant as in [[in-process-webview-java-api]].

### 2. Construct Swing Wrapper — WebViewHeavyweightComponent constructor
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`

1. Responsibility: set up the Swing container so the inner
   heavyweight canvas can host the native peer.
2. Methods:
   - `WebViewHeavyweightComponent()`
     - Logic: `setLayout(new BorderLayout())`; build
       `EmbeddedCanvas`, set its background to WHITE to avoid
       Swing painting over the canvas region; add at
       `BorderLayout.CENTER` (`WebViewHeavyweightComponent.java:60`).
3. Constraints / Invariants:
   - The white background is required: Swing will erase the
     canvas region on repaint events, and a non-opaque or
     transparent fill would flash before the native peer
     repaints over it.

### 3. Buffer and Live Setters
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`

1. Responsibility: implement the abstract `WebViewComponent`
   surface with a buffer/replay pattern.
2. Methods:
   - `setUrl(String url)` — store `pendingUrl`, navigate
     immediately if the peer exists
     (`WebViewHeavyweightComponent.java:73`).
   - `getUrl()` — return `pendingUrl`
     (`WebViewHeavyweightComponent.java:83`).
   - `setDebug(boolean)` — throw
     `IllegalStateException("setDebug must be called before the
     component is displayed.")` if `embedded != null`
     (`WebViewHeavyweightComponent.java:87`).
   - `addOnBeforeLoad(String js)` — append to `pendingInit`
     and, if live, also call `embedded.addOnBeforeLoad`
     (`WebViewHeavyweightComponent.java:98`).
   - `eval(String js)` — no-op until live, then
     `embedded.eval(js)` (`WebViewHeavyweightComponent.java:107`).
   - `addJavascriptCallback(String name, JavascriptCallback cb)`
     — reject reserved-prefix names (any `name.startsWith("__webview_")`)
     with `IllegalArgumentException` BEFORE mutating any
     state; then put in `pendingBindings`; if live, call
     `embedded.addJavascriptCallback` immediately
     (`WebViewHeavyweightComponent.java:115`).
   - `dispose()` — if peer exists, clear field then call
     `embedded.dispose()` (`WebViewHeavyweightComponent.java:124`).
   - `openDevTools(): boolean` — if `embedded == null` return
     `false`; otherwise return
     `embedded.openDevTools()`.
3. Constraints / Invariants:
   - Setters never reset their buffers — `pendingInit` keeps
     growing across calls so a re-attach (if it ever happened)
     could replay them.
   - `getUrl()` returns the buffered URL, not the actually
     rendered URL — sufficient for round-trip but does not
     reflect post-load navigations triggered inside the page.

### 4. Embedded Canvas — EmbeddedCanvas inner class
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`

1. Responsibility: heavyweight peer that AWT can supply a
   native window handle for, and the place where the native
   embed is created/destroyed/sized.
2. Fields:
   - `peerAttached: volatile boolean` — guards the one-shot
     peer creation (`WebViewHeavyweightComponent.java:188`).
3. Methods:
   - Constructor: install a `ComponentAdapter` calling
     `sizeNative()` on resize/move
     (`WebViewHeavyweightComponent.java:191`); install a
     `HierarchyListener` that on `SHOWING_CHANGED` calls
     `embedded.setVisible(isShowing())` and `sizeNative()` if
     now showing (`WebViewHeavyweightComponent.java:208`).
   - `removeNotify(): void` — set `peerAttached = false`, call
     `dispose()`, super (`WebViewHeavyweightComponent.java:235`).
   - `paint(Graphics g): void` — on first call, set
     `peerAttached = true` and call `createPeer()`
     (`WebViewHeavyweightComponent.java:242`).
   - `update(Graphics g)` — forward to `paint(g)` so the
     creation hook fires on the first repaint too
     (`WebViewHeavyweightComponent.java:256`).
4. Constraints / Invariants:
   - Peer is created exactly once per attach; `peerAttached`
     is reset on `removeNotify` so a re-add to the hierarchy
     re-creates the peer.

### 5. Lazy Peer Creation — createPeer
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`

1. Responsibility: build the native peer, replay buffered
   configuration, and size it correctly.
2. Methods:
   - `createPeer(): void`
     - Logic: short-circuit if `embedded != null` or canvas is
       not displayable (`WebViewHeavyweightComponent.java:143`).
       Call `EmbeddedWebView.attach(canvas, debug)`
       (`WebViewHeavyweightComponent.java:147`). If it throws,
       null the field and rethrow
       (`WebViewHeavyweightComponent.java:149`).
       **Install the console bridge BEFORE replaying user
       config so the shim sees every subsequent
       `addOnBeforeLoad`/navigate**: call
       `embedded.addOnBeforeLoad(ConsoleDispatcher.SHIM_JS)`
       once, then
       `embedded.addJavascriptCallback("__webview_console__",
       payload -> consoleDispatcher.dispatch(payload))`. The
       reserved-name reject in the public
       `addJavascriptCallback` is bypassed here because this
       call goes directly through `embedded`, not through
       `WebViewHeavyweightComponent.addJavascriptCallback`.
       Then replay `pendingInit` via
       `embedded.addOnBeforeLoad`
       (`WebViewHeavyweightComponent.java:152`); replay
       `pendingBindings` via
       `embedded.addJavascriptCallback`
       (`WebViewHeavyweightComponent.java:155`). Navigate to
       `pendingUrl` (`WebViewHeavyweightComponent.java:158`).
       Finally call `sizeNative()` to apply current bounds.

### 6. Size Native Peer — sizeNative
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`

1. Responsibility: translate the canvas's Swing-space
   geometry into the native peer's coordinate space.
2. Methods:
   - `sizeNative(): void`
     - Logic: if `embedded == null`, return. Read canvas size;
       skip if either dimension is non-positive
       (`WebViewHeavyweightComponent.java:166`). Walk to the
       containing AWT `Window`; convert the canvas's `(0,0)`
       to window coordinates; subtract `window.getInsets().left/.top`
       to translate from window-frame coords to content-pane
       coords (`WebViewHeavyweightComponent.java:176`); call
       `embedded.setBounds(x, y, w, h)`
       (`WebViewHeavyweightComponent.java:183`).
3. Constraints / Invariants:
   - The native side translates the supplied coords into its
     host `NSView` coordinate space; on macOS where the host
     is `NSWindow.contentView`, this places the WKWebView
     exactly over the canvas region rather than over the
     entire window (comment at
     `WebViewHeavyweightComponent.java:171`).

### 7. Preferred Size — getPreferredSize
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`

1. Responsibility: return a sensible default size when no
   layout manager has assigned one.
2. Methods:
   - `getPreferredSize(): Dimension`
     - Logic: call super; if null or both dimensions <= 0,
       return `new Dimension(800, 600)`
       (`WebViewHeavyweightComponent.java:134`).

### 8. Open Native DevTools — webview_embed_open_devtools
Files: `src/ca/weblite/webview/WebViewNative.java`,
`src_c/webview_embed.cpp`, `windows/webview_embed.cc`

1. Responsibility: per-platform implementation of
   `webview_embed_open_devtools(long w): int` that pops the
   native inspector window (or, on macOS, reports
   unsupported).
2. Java entry point:
   - Declare `native static int webview_embed_open_devtools(long w)`
     in `WebViewNative.java`. Header regeneration is required
     (see [[native-library-loading-and-packaging]]).
3. Linux implementation (`src_c/webview_embed.cpp`):
   - Look up the engine struct from the `long w` peer.
   - Read `webkit_settings_get_enable_developer_extras` on
     the engine's `WebKitSettings`. If FALSE, return 0 — debug
     was not enabled at create time.
   - Call `webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web))`.
     If NULL, return 0.
   - Call `webkit_web_inspector_show(inspector)` and return 1.
   - Marshal to the GTK pump thread via the existing
     `gtk_main_do_event` / dispatch pattern if not already on
     it; do not block the EDT.
4. macOS implementation (`src_c/webview_embed.cpp`,
   `cocoa_*` branch):
   - Return 0 unconditionally. The enable-side work is done
     at create time (see Operation 9).
5. Windows implementation (`windows/webview_embed.cc`):
   - Marshal a worker-thread message via the existing
     `PostThreadMessage` pattern used for `navigate` and
     `eval`. The worker:
     - Reads `controller->get_CoreWebView2(&webview)` (already
       cached at `e->webview`).
     - Reads `webview->get_Settings(&settings)` and checks
       `AreDevToolsEnabled`. If FALSE, signal 0.
     - Calls `webview->OpenDevToolsWindow()`. On success
       signal 1; on HRESULT failure signal 0 and log via
       `WV_LOG`.
   - Block the calling JNI thread on a `std::atomic_flag` /
     condition variable until the worker has reported; then
     return the worker's result. Total wait is bounded by
     normal WebView2 method dispatch (sub-second under load).
6. Constraints / Invariants:
   - The native function MUST be safe to call any number of
     times; platform semantics handle re-invocation (Linux
     and Windows focus the existing inspector window, macOS
     is a no-op).
   - Returns 0 (never throws via JNI) for every failure mode:
     disabled, peer missing, native error, unsupported
     platform. The Java side surfaces the difference via
     `boolean` only.

### 9. macOS Inspector Flag — cocoa_create_engine
File: `src_c/webview_embed.cpp` (`cocoa_create_engine` block,
inside the `if (e->debug)` branch).

1. Responsibility: enable the macOS Web Inspector both via the
   legacy `developerExtrasEnabled` preference and via the
   modern `isInspectable` property when the running OS
   exposes it.
2. Logic:
   - Existing: set `developerExtrasEnabled=YES` on the
     WKWebView's `preferences` (already in place at
     `src_c/webview_embed.cpp:1504-1510`).
   - New: check
     `[e->webview respondsToSelector:@selector(setInspectable:)]`.
     If true, send `setInspectable:YES` to the WKWebView.
   - No-op when the selector is absent (macOS 12.x and
     earlier) — right-click → Inspect Element still works
     via `developerExtrasEnabled` alone.
3. Constraints / Invariants:
   - `respondsToSelector:` gating is mandatory — direct
     invocation would `doesNotRecognizeSelector:` on older
     macOS and abort.
   - This change is wholly inside `if (e->debug) { ... }`;
     `debug=false` runs are entirely unaffected.

## N · Norms
- All AWT/JAWT interaction must respect the rule that the
  native peer is only valid while the host AWT Component is
  displayable. Use the buffer/replay pattern in
  `createPeer()` rather than calling JNI before
  `addNotify()`.
- Avoid installing AWT mouse/focus listeners that touch the
  canvas's X11 event mask. The comment at
  `WebViewHeavyweightComponent.java:223` documents the
  specific Linux failure mode — don't reintroduce them
  without a fix native-side.
- Heavyweight popups are required for the demo's combo-box
  scenario to render correctly
  (`WebViewHeavyweightDemo.java:40`). Document this in any
  new app code that builds on heavyweight embedding.
- The console-bridge install in `createPeer()` MUST happen
  BEFORE replaying `pendingInit` and `pendingBindings`. This
  guarantees the shim is the first init-script installed, so
  any user init-script that itself calls `console.*` is
  observed by the shim. Inverting the order would silently
  drop console output from early user scripts.
- The internal `__webview_console__` binding callback in
  `createPeer` must call `consoleDispatcher.dispatch(payload)`
  directly — it must NOT call any other Java code on the
  native thread. The dispatcher is the one place that hops
  to the EDT.
- `webview_embed_open_devtools` MUST NOT block the EDT
  indefinitely. Windows marshals to the worker thread with
  bounded wait; Linux dispatches to the GTK pump thread.
  Native bugs that would block must be diagnosed and fixed,
  not worked around with timeouts in Java.

## S · Safeguards
- `EmbeddedWebView.attach` validates `parent != null` AND
  `parent.isDisplayable()` AND
  `webview_embed_create` returned a non-zero pointer
  (`EmbeddedWebView.java:50`).
- `WebViewHeavyweightComponent.setDebug` throws
  `IllegalStateException` if called after display
  (`WebViewHeavyweightComponent.java:90`) — the native peer
  bakes the debug flag at create time.
- `removeNotify` always calls `dispose()` before super so the
  native peer is freed before AWT destroys the canvas peer
  (`WebViewHeavyweightComponent.java:236`). Inverting this
  order would leak native peers attached to dead AWT windows.
- `sizeNative()` no-ops on non-positive dimensions so a
  collapsed split-pane region doesn't drive negative bounds
  into the native side (`WebViewHeavyweightComponent.java:167`).
- HierarchyListener only acts on `SHOWING_CHANGED` events to
  avoid running the visibility/resize logic on unrelated
  hierarchy changes (`WebViewHeavyweightComponent.java:211`).
- `EmbeddedWebView.checkAlive` guards every JNI operation
  against use-after-dispose (`EmbeddedWebView.java:204`).
- `WebViewHeavyweightComponent.openDevTools()` returns
  `false` when `embedded == null` rather than throwing.
  Calling it on an undisplayed component is a normal user
  action (e.g. a menu item enabled before the page loads)
  and must not crash the host application.
- `WebViewHeavyweightComponent.addJavascriptCallback` rejects
  reserved-prefix names (any name starting with
  `__webview_`) with `IllegalArgumentException` BEFORE
  touching `pendingBindings` or `embedded`. The exception
  message must name the offending prefix so callers can
  recognise the cause without reading source.
- The internal `__webview_console__` binding callback
  registered in `createPeer()` MUST be removed from
  `bindings`/`heap` when `dispose()` runs. The existing
  `EmbeddedWebView.dispose()` already clears both, so no
  additional cleanup is required — but verify the dispatcher
  reference itself is null'd or unreachable so a late native
  callback (in-flight at dispose time) lands silently rather
  than running a `ConsoleDispatcher.dispatch` after the
  component has gone.
- The native `webview_embed_open_devtools` returns 0 (never
  raises a JNI exception) for every failure path so the Java
  side never has to wrap the call in a try/catch.
