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
    (`WebViewHeavyweightComponent.java:208`). Visibility is not
    driven by transitions alone: the peer's *initial* visibility
    is seeded from the component's current showing state at
    peer-create time, so a peer created while its Swing region
    is not showing (e.g. the component is built inside a
    not-selected `JTabbedPane` tab or other not-showing nested
    container) starts hidden instead of defaulting to visible.
    Without this seed the heavyweight peer would paint over
    whatever region is actually showing — most visibly on macOS,
    where the native attach is asynchronous and can complete
    after the tab has already been deselected.
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
  - `evalAsync(String js): CompletableFuture<String>` — concrete
    implementation of the abstract method declared on
    [[swing-webview-component-mode-selection]]. The heavyweight
    `WebViewHeavyweightComponent.evalAsync` short-circuits with
    an already-failed future carrying
    `IllegalStateException("WebViewComponent not displayed")`
    when `embedded == null`; otherwise delegates to
    `embedded.evalAsync(js)`. The backing `EmbeddedWebView` owns
    a per-peer `EvalDispatcher` (constructed with
    `marshalToEdt = true` and
    `disposeLabel = "EmbeddedWebView"`) that holds the in-flight
    futures, the JS wrapper template, and the dispose drain. The
    dispatcher class, JS shim contract, and
    `JavaScriptEvalException` type are owned by
    [[webview-async-javascript-eval]]. Future continuations
    complete on the Swing EDT, matching the existing
    `ConsoleListener` and `WebViewMouseListener` contracts.
- macOS-only: when `debug=true`, the native `cocoa_create_engine`
  block that already sets `developerExtrasEnabled=YES` MUST
  also call `setInspectable:YES` on the `WKWebView` instance,
  guarded by `respondsToSelector:@selector(setInspectable:)`.
  This exposes the inspector via the Safari Develop menu on
  macOS 13.3+. On macOS 12.x and earlier the selector does
  not exist and is skipped — right-click → Inspect Element
  still works via the existing `developerExtrasEnabled` flag.
- Clipboard & editing-command shortcuts: pressing the standard
  platform shortcut modifier + `C` / `V` / `X` / `A` MUST
  perform Copy / Paste / Cut / Select-All against whatever has
  the in-page focus inside the native WebView whenever the
  user is "interacting with" the WebView. "Interacting with"
  the WebView means: the user's current input target is the
  WebView, which we detect via the native first-responder
  state on macOS — if AppKit's first responder is the
  `WKWebView` (or any inner view of it), the WebView is the
  active target regardless of which Swing component holds the
  AWT focus owner. Concretely: if a sibling `JTextField` in
  the same window happened to be the last Swing component to
  receive focus, but the user has since clicked into the
  WebView (so AppKit promoted `WKWebView` to first responder
  while AWT's focus owner stayed on the `JTextField`),
  Cmd+C MUST copy from the WebView's selection — NOT from
  the `JTextField`. This bidirectional asymmetry between the
  AWT focus chain and the AppKit responder chain is the
  defining wrinkle of the AWT-embedded WKWebView setup; the
  dispatcher's gating logic exists to bridge it.
- Visual focus cooperation (macOS + Windows): when the user shifts
  interaction to the WebView (WKWebView becomes the native
  first responder), the previously-focused Swing
  `JTextComponent` (if any) MUST have its caret hidden so
  the user gets a visual cue that typing now lands in the
  WebView. When the user shifts back to the Swing component
  (WKWebView resigns first responder — which happens
  automatically when AWT moves focus to a Swing component,
  because AWT calls AppKit `makeFirstResponder:` on its
  NSView), the previously-suppressed caret MUST be restored.
  The cooperation is one-directional in implementation but
  bidirectional in observable behaviour: the
  WebView-became-FR direction needs an explicit Cocoa hook
  because AWT doesn't observe AppKit responder changes; the
  WebView-resigned-FR direction is driven by AWT's own
  focus → AppKit sync as a side-effect of the user clicking
  the Swing component, after which our resign hook restores
  caret state. Crucially: the WebView-became-FR handler MUST
  NOT call `requestFocusInWindow()` on the WebView component
  — AWT would then call AppKit `makeFirstResponder:` on its
  NSView and kick WKWebView right back out of first
  responder, cutting off keyboard input to the page.
   The modifier is `Cmd` on macOS and `Ctrl`
  on Linux / Windows; the component detects it via
  `Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()`
  rather than hardcoding either mask. Implementation lives
  in the heavyweight component (`KeyEventDispatcher` installed
  in `addNotify` / removed in `removeNotify`) plus a new
  cross-platform JNI entry point
  `webview_embed_execute_editing_command(long peer, int cmdId)`:
  - **macOS** (WKWebView): on the AppKit main thread, call
    `[NSApp sendAction:@selector(cut:|copy:|paste:|selectAll:)
    to:nil from:webview]`. Targeting `nil` resolves against
    the first responder, which under a focused contentEditable
    or `<input>` is the in-page element — directly addressing
    the `WKWebView` would short-circuit that resolution.
  - **Linux** (WebKitGTK): on the GTK main thread, call
    `webkit_web_view_execute_editing_command(WEBKIT_WEB_VIEW(e->web),
    WEBKIT_EDITING_COMMAND_CUT | _COPY | _PASTE | _SELECT_ALL)`.
  - **Windows** (WebView2): on the WebView2 worker thread, call
    `webview->ExecuteScript("document.execCommand('cut'|'copy'|'paste'|'selectAll')")`.
    WebView2 has no first-class editing-command IPC, and
    `document.execCommand` reliably routes through the focused
    element's clipboard handlers. If a follow-up confirms that
    WebView2's own `AcceleratorKeyPressed` default already
    covers Ctrl+C/V/X/A end-to-end on every supported Windows
    build, the Windows native body MAY be a no-op stub that
    returns immediately — but the JNI entry point must still
    exist so the Java caller is platform-uniform.
- Popup dismissal on click into the WebView: when a Swing
  `JPopupMenu`, `JMenu`, `JComboBox` dropdown, or any other
  `JPopupMenu`-based popup is currently visible and the user
  presses any mouse button (left, right, middle) anywhere inside
  the heavyweight WebView's native area, the popup MUST close,
  matching the standard Swing dismiss-on-outside-click behavior
  that `BasicPopupMenuUI.MouseGrabber` provides for clicks on
  pure-Swing widgets. The native peer (X11 child window on
  Linux, `WKWebView` NSView on macOS, WebView2 child HWND on
  Windows) receives mouse events directly from the OS without
  going through AWT's event queue, so `MouseGrabber`'s
  `AWTEventListener` never sees the press and the popup would
  otherwise stay open — surprising to users and inconsistent
  with the rest of the Swing UI. The click itself MUST still
  reach the WebView for normal handling (link clicks, text
  selection, form interaction, focus grab) — only the
  popup-dismiss side-effect is restored. The lightweight
  component (`WebViewLightweightComponent`) is unaffected
  because its mouse events flow through AWT and `MouseGrabber`
  sees them naturally.
- **No sync EDT↔AppKit-main bridge.** `src_c/webview_embed.cpp`
  MUST NOT contain any synchronous EDT→AppKit-main-thread
  dispatch primitive. Every per-engine native operation runs
  via `cocoa_run_on_main_async` (or inlines when already on
  main); any AppKit-thread state that the EDT needs to read
  is mirrored into an atomic on the `Engine` struct, updated
  from the main thread, and read lock-free. Mutual-wait
  deadlocks of the form "EDT parks waiting for AppKit-main
  while AppKit-main is parked in `invokeAndWait` waiting for
  EDT" — including the path through accessibility, IME,
  focus-change, or any other AppKit→Java upcall via
  `LWCToolkit.doAWTRunLoopImpl` — are structurally impossible
  in this codebase as a consequence.
- **Async engine attach with deferred completion**
  (macOS-specific behaviour, cross-platform API).
  `EmbeddedWebView.attach(parent, debug)` is a synchronous
  factory on every platform — it returns immediately with a
  non-null `EmbeddedWebView` and MUST return within 50 ms on
  macOS regardless of AppKit-side activity (accessibility,
  IME, focus tracking, etc.). On macOS the WKWebView creation,
  host-NSView discovery, `addSubview:`, and configuration run
  asynchronously on the AppKit main thread after the C++
  `Engine` struct is allocated. On Windows and Linux the
  attach completes synchronously inside the JNI call as
  today. A new `WebViewAttachListener` API
  (`addOnAttachComplete`) reports the deferred outcome on the
  EDT — `onAttached` on success, `onAttachFailed(cause)` on
  AppKit-side failure. The listener API exists on all
  platforms; on Windows / Linux the listener fires on the
  next EDT tick because the engine is already in `ATTACHED`
  state when the constructor returns. Pre-attach operations
  (`setUrl`, `addOnBeforeLoad`, `addJavascriptCallback`,
  `eval`, `setBounds`, `setVisible`, `requestFocus`,
  `executeEditingCommand`) MUST NOT throw on a `PENDING`
  engine — they queue and replay automatically. The
  `EmbeddedWebView.attach(...)` Java signature does not
  change; existing callers (including
  `WebViewHeavyweightComponent.createPeer`) MUST continue to
  work without modification.
- **Async engine destroy with safe queued-op handling**
  (macOS-specific behaviour). `EmbeddedWebView.dispose()`
  MUST return within 50 ms on macOS regardless of AppKit-side
  activity. On macOS the `removeFromSuperview`, AppKit
  object releases, and `delete e` move into a
  `cocoa_run_on_main_async` block. A `destroyed: std::atomic<bool>`
  flag on the `Engine` lets any queued async op that fires
  after destroy short-circuit cleanly. `EmbeddedWebView.dispose()`
  remains idempotent. Late `evalAsync` calls on a disposed
  engine continue to return an already-failed future carrying
  `IllegalStateException("EmbeddedWebView has been disposed.")`,
  as today; the future-completion path does not regress.
- **Cached first-responder probe via KVO.**
  `EmbeddedWebView.isNativeFirstResponder()` MUST NOT make a
  synchronous main-thread hop on any call. On macOS the
  responder state is mirrored into an
  `std::atomic<bool> is_first_responder` field on the
  `Engine`, maintained by a KVO observer on the host
  `NSWindow`'s `firstResponder` key path; each observation
  fires on the AppKit main thread, walks the responder chain
  exactly as the previous synchronous probe did, and writes
  the atomic. The cache MUST correctly reflect focus on inner
  WebKit content views (e.g. an `<input>` inside the page) —
  the existing `WKWebView` `becomeFirstResponder` /
  `resignFirstResponder` swizzle is insufficient for this
  alone because those methods do not fire for focus
  transitions to the private content view. Behaviour visible
  through the editing-shortcut dispatcher and any other
  caller of `isNativeFirstResponder` MUST be indistinguishable
  from the previous synchronous implementation outside of
  deadlock conditions.
- **Deadlock-repro demo runs indefinitely.**
  `demos/WebViewDeadlockRepro` (the reproducer for the v1.0.5
  sync-bridge deadlock) MUST run for at least 60 seconds with
  its synthetic `Cmd-C` loop without the EDT watchdog firing.
  An extended attach → focus → `Cmd-C` → dispose stress loop
  MUST run for at least 5 minutes without watchdog firing,
  native crash, or Address-Sanitizer-reported use-after-free
  attributable to the destroy path.
- Definition of Done: documented by `README.md ("Heavyweight platform notes" section)` ("Heavyweight
  platform notes") and exercised by the `WebViewHeavyweightDemo`
  (`demos/WebViewHeavyweightDemo/...`). No automated tests cover
  this — it is GUI integration code. The sync-deadlock
  elimination work additionally MUST keep the
  `demos/WebViewDeadlockRepro` long-run loop passing on macOS;
  see Operation 14 below for the test plan.

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
  - Overrides `evalAsync(String js)` to short-circuit with an
    already-failed future
    (`IllegalStateException("WebViewComponent not displayed")`)
    when `embedded == null`, otherwise delegate to
    `embedded.evalAsync(js)`. No state is held on the heavyweight
    component itself — the per-peer `EvalDispatcher` lives on
    `EmbeddedWebView` and is constructed during `attach`.
  - Overrides `addJavascriptCallback(name, cb)` to reject any
    name starting with `__webview_` (matches the canvas-5
    reserved-prefix norm).
  - `editingShortcutDispatcher: KeyEventDispatcher` —
    `null` whenever the component is not currently displayable.
    Non-null exactly between `addNotify()` and the matching
    `removeNotify()`. When non-null it is also registered on
    `KeyboardFocusManager.getCurrentKeyboardFocusManager()`,
    and `removeNotify()` MUST unregister it. The dispatcher
    intercepts platform-shortcut + C/V/X/A `KEY_PRESSED`
    events whose AWT focus owner is the component or a
    descendant, forwards them to
    `EmbeddedWebView.executeEditingCommand`, and returns
    `true` to consume the event. Short-circuits to `false`
    when `embedded == null` so a late event during teardown
    cannot call JNI on a disposed peer.
  - Registers a native click callback on `EmbeddedWebView` in
    `createPeer()` (via `embedded.setClickCallback(...)`) whose
    handler marshals to the EDT and calls
    `MenuSelectionManager.defaultManager().clearSelectedPath()`
    to dismiss any open Swing popup. The callback is cleared
    implicitly by `EmbeddedWebView.dispose()` (which calls
    `setClickCallback(null)` before tearing down the native
    peer), so the component does not manage its lifecycle
    explicitly. The handler MUST be scoped narrowly: only the
    popup-dismiss side-effect, no focus mutation, no JNI calls
    into the embedded peer.
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
  (`EmbeddedWebView.java:33`), the bindings map
  (`EmbeddedWebView.java:34`), and a `final evalDispatcher:
  EvalDispatcher` field that owns the in-flight
  `requestId → CompletableFuture<String>` map for `evalAsync`.
  The dispatcher is constructed inside `attach` with
  `marshalToEdt = true` and `disposeLabel = "EmbeddedWebView"`
  and an `EvalSink` lambda that calls
  `WebViewNative.webview_embed_eval(peer, wrappedJs)` when
  `peer != 0L`. The dispatcher class is owned by
  [[webview-async-javascript-eval]]. Invariants:
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
  - New method `executeEditingCommand(EditingCommand cmd): EmbeddedWebView`
    — `checkAlive`, then call
    `WebViewNative.webview_embed_execute_editing_command(peer, cmd.nativeId)`.
    Pure side-effect; does not touch `heap` or `bindings`.
    Native side is responsible for marshaling to the correct
    UI thread (AppKit main / GTK main / WebView2 worker).
  - New method `setClickCallback(WebViewClickCallback cb): EmbeddedWebView`
    — `checkAlive`; when `cb` is non-null anchor it in `heap`
    so the JVM doesn't collect it while the native side holds
    a global ref; delegate to
    `WebViewNative.webview_embed_set_click_callback(peer, cb)`.
    Passing `null` clears any prior callback. `dispose()` calls
    `setClickCallback(null)` BEFORE `webview_embed_destroy`
    runs, symmetric with the existing `setFocusCallback(null)`
    cleanup, so a late native click event during teardown
    cannot fire into a freed global ref.
- **WebViewClickCallback** (new public functional interface,
  `src/ca/weblite/webview/WebViewClickCallback.java`). Single
  method `void invoke()` — fired once per native mouse-button
  press inside the embedded WebView, on any button (left,
  right, middle). Mirrors the shape of the existing
  `WebViewFocusCallback` (no payload — purely a notification).
  Invoked from a native thread; implementations MUST marshal
  to the EDT themselves before touching any Swing state.
- **EditingCommand** (new public enum,
  `src/ca/weblite/webview/EditingCommand.java`). Stable
  contract between Java and the JNI bridge — the integer IDs
  MUST match what the native side dispatches on.
  - Values: `CUT(1)`, `COPY(2)`, `PASTE(3)`, `SELECT_ALL(4)`.
  - Each value carries a `private final int nativeId`
    exposed via `int getNativeId()`. The integer IDs are
    part of the JNI ABI: renumbering them is a breaking
    change across native + Java boundaries and MUST be
    avoided. New commands (e.g. `UNDO`, `REDO`) MUST be
    appended with new IDs rather than reusing or shifting
    existing ones.
- **WebViewAttachListener** (new public functional interface,
  `src/ca/weblite/webview/WebViewAttachListener.java`). Two
  methods: `void onAttached(EmbeddedWebView webView)` and
  `void onAttachFailed(EmbeddedWebView webView, Throwable cause)`.
  Documented to fire on the Swing EDT. Invariants:
  - Listeners MAY be registered before or after attach
    resolves. Registration after resolution fires the
    corresponding callback on the next EDT tick (via
    `SwingUtilities.invokeLater`), never inline on the
    calling thread.
  - Multiple listeners are supported; they fire in
    registration order.
  - The `cause` argument on `onAttachFailed` carries an
    actionable message identifying the failure step (e.g.
    "WKWebView allocation failed", "host NSView not found").
    Implementations MAY downcast to a more specific
    exception type if a richer contract is exposed in a
    future revision.
  - Listener-thrown exceptions propagate to AWT's
    uncaught-exception handler (same convention as every
    other EDT-marshalled callback in this canvas);
    implementations SHOULD catch their own exceptions if
    they do not want default AWT behaviour.
- **AttachState** (new public enum,
  `src/ca/weblite/webview/AttachState.java`). Values:
  `PENDING`, `ATTACHED`, `FAILED`. Exposed via
  `EmbeddedWebView.getAttachState(): AttachState`.
  Invariants:
  - State transitions occur only on the EDT.
  - `PENDING → ATTACHED` and `PENDING → FAILED` are the only
    legal transitions; `ATTACHED` and `FAILED` are terminal.
  - On Windows and Linux, the engine enters `ATTACHED`
    synchronously during the `EmbeddedWebView` constructor.
  - On macOS, the engine enters `ATTACHED` or `FAILED`
    asynchronously after `cocoa_create_engine`'s async
    epilogue completes — driven by a JNI callback that
    marshals onto the EDT before flipping the state.
- **EmbeddedWebView additions for async attach / destroy and
  responder cache** (extends the existing entity above).
  Invariants:
  - `attachState: AttachState` — initialised based on the
    platform-specific return shape of `webview_embed_create`
    (see Operation 1 and Operation 14).
  - `attachFailure: Throwable` — null until `FAILED`
    transition.
  - `attachListeners: List<WebViewAttachListener>` —
    registered listeners. Cleared after the resolve dispatch
    fires (no further callbacks once the engine resolves);
    a fresh list is rebuilt only if a caller registers
    additional listeners post-resolution (each of which
    fires immediately on the next EDT tick).
  - New method `addOnAttachComplete(WebViewAttachListener listener)`:
    appends to `attachListeners` (when `PENDING`) or
    schedules an immediate `invokeLater` dispatch (when
    `ATTACHED` / `FAILED`). MUST be safe to call before or
    after resolution. Returns `this` for chaining.
  - New method `getAttachState(): AttachState`: read the
    current state. Useful for tests and for callers that
    prefer polling over listeners.
  - The pre-existing `peer` field is non-zero from the moment
    `EmbeddedWebView` is constructed (the C++ `Engine` struct
    allocation is still synchronous on every platform); only
    the AppKit-side state inside the struct is deferred on
    macOS. `checkAlive()` keeps its existing
    `peer == 0L` semantics — it is NOT used to gate
    pre-attach calls on `PENDING`. Methods that today call
    `checkAlive()` continue to call it; they remain safe
    because the native lambdas they enqueue null-check
    `e->webview` / `e->manager` at fire time.
- **C++ Engine struct additions for async lifecycle and
  responder cache** (`src_c/webview_embed.cpp`). Invariants:
  - `is_first_responder: std::atomic<bool>` — mirrored
    AppKit-main-thread state. Written by the KVO observer
    callback only; read by `cocoa_is_first_responder` from
    any thread. Default-initialised to `false`.
  - `destroyed: std::atomic<bool>` — set to `true` inside
    the destroy async lambda before any AppKit teardown
    runs. Read by every other async-on-main lambda at fire
    time as a belt-and-suspenders short-circuit; the
    primary correctness guarantee is dispatch-queue FIFO
    ordering plus EDT-only enqueueing, but the flag closes
    any hypothetical non-EDT-enqueue race and provides a
    clean signal for the `cocoa_eval` late-failure path
    (see Operation 14).
  - `kvo_observer: id` — strong reference to the KVO observer
    target (an `NSObject` subclass owned by this engine).
    Registered against the host `NSWindow`'s `firstResponder`
    key path when the WKWebView gains a non-nil window;
    unregistered before the engine struct is freed. May be
    re-registered against a new window if the WKWebView's
    `window` property changes at runtime.
  - `observed_window: id` — weak/unretained back-reference
    to the NSWindow the observer is currently registered
    against. Used by destroy / window-change to call
    `removeObserver:forKeyPath:` against the right target;
    never `release`d (it's an unowned back-pointer).

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
- **ABI-correct Objective-C struct-return dispatch on macOS.**
  The generic typed `objc_msgSend` dispatch helper used for
  native AppKit calls (`src_c/webview_embed.cpp`) is only valid
  for selectors whose return value comes back in registers and
  for passing struct-by-value *arguments*. Selectors that
  *return* a struct larger than 16 bytes — notably `NSRect` /
  `CGRect` (32 bytes), as produced by the `-[NSView bounds]`
  query used to position the WKWebView on the
  `NSWindow.contentView` host path in `cocoa_set_bounds` — MUST
  be dispatched through an architecture-correct struct-return
  variant: `objc_msgSend_stret` on `x86_64`, and plain
  `objc_msgSend` on `arm64` (which has no `objc_msgSend_stret`
  and returns large structs via the `x8` register). On the
  `x86_64` SysV ABI a large struct return is passed by a hidden
  pointer in the first integer-argument register, so dispatching
  such a selector through plain `objc_msgSend` shifts the
  receiver/selector registers by one and the runtime dereferences
  the stack return-buffer pointer as the receiver — a SIGSEGV in
  `objc_msgSend`. This is why heavyweight embedding crashed on
  Intel Macs (issue #36) whenever the host view resolved to
  `NSWindow.contentView` — the path taken under the JetBrains
  Runtime, which exposes no per-Canvas AWT `NSView`. The
  per-Canvas AWT `NSView` path (`host_is_awt == true`) never
  queries `bounds` and is unaffected. This is a pure
  ABI-correctness requirement: observable geometry and
  positioning behavior is unchanged on every platform that
  already worked.
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
- **Eval-bridge install is per peer-attach AND lives inside
  `EmbeddedWebView.attach` rather than the heavyweight
  component's `createPeer`.** Rationale: the per-peer
  `EvalDispatcher` is owned by `EmbeddedWebView`, so co-locating
  the dispatcher construction, the `addOnBeforeLoad(EvalDispatcher.SHIM_JS)`
  call, and the
  `addJavascriptCallback(EvalDispatcher.CHANNEL_NAME, fn)`
  registration inside `attach` keeps engine concerns inside the
  engine wrapper and avoids leaking a dispatcher accessor onto
  the public API. The heavyweight component's `createPeer`
  therefore needs no new eval-specific steps — the eval bridge
  is wired automatically by the act of constructing the
  `EmbeddedWebView`. The resolver-callback closure references
  the dispatcher directly (it was constructed two lines above)
  and is anchored in the same `heap` list as every other
  native callback.
- **Heavyweight `evalAsync` flow end-to-end.**
  `WebViewHeavyweightComponent.evalAsync(js)` → null-check
  `embedded` → delegate to `embedded.evalAsync(js)` →
  `EvalDispatcher.evalAsync(js)` allocates a request id and a
  `CompletableFuture`, inserts into the pending map, wraps the
  user snippet via the SHIM_JS wrapper template, and invokes
  the `EvalSink` lambda → the sink issues
  `WebViewNative.webview_embed_eval(peer, wrappedJs)` (when
  `peer != 0L`). When the JS shim posts the result back, the
  native engine fires the resolver binding callback (registered
  inside `attach`) which calls `evalDispatcher.dispatch(arg)` →
  the dispatcher parses the envelope, extracts the request id,
  removes the future from the pending map, hops to the EDT via
  `SwingUtilities.invokeLater`, and completes the future. See
  [[webview-async-javascript-eval]] for the full dispatcher
  contract.
- **Editing-command shortcuts driven from Java, not from
  AppKit / X11.** The standard platform Cut/Copy/Paste/Select-All
  shortcuts (`Cmd+C/V/X/A` on macOS, `Ctrl+C/V/X/A` elsewhere)
  do not work out-of-the-box on any platform when the WebView
  is embedded as a heavyweight AWT peer:
  - On macOS, AWT's NSEvent dispatch consumes
    `Cmd`-modified key events for its own key-equivalent
    handling before WKWebView's `performKeyEquivalent:` is
    given a chance.
  - On Linux, the focused X11 window is the embedded
    WebKitGTK widget (after `XSetInputFocus`), but AWT does
    not route a corresponding `KeyEvent` back through the
    Swing focus owner, and the GTK side never installs a
    key-binding for the AWT shortcut.
  - On Windows, WebView2 *usually* handles Ctrl+C/V/X/A
    natively, but the AWT focus owner may still intercept
    them in some configurations.
  Two alternatives were rejected:
  1. Install a synthetic AppKit `Edit` menu into
     `NSApp.mainMenu`. Functional but mutates the host
     application's menu bar — a destructive side-effect for
     library code. It is also macOS-only, so the cross-platform
     problem still needs a different mechanism for Linux and
     Windows.
  2. Install a Swing `KeyListener` on the embedded `Canvas`.
     Unreliable: with the heavyweight peer holding native
     focus, AWT's focus owner is the canvas, but the
     `KeyListener` runs after AWT's own shortcut consumption.
  Chosen mechanism: a single `KeyEventDispatcher` registered
  on `KeyboardFocusManager.getCurrentKeyboardFocusManager()`
  in `addNotify()` and unregistered in `removeNotify()`. This
  sits ABOVE the AWT focus-owner-level dispatch, so it sees
  modifier + C/V/X/A `KEY_PRESSED` events before AWT can
  consume them. The dispatcher gates on the AWT focus owner
  being the component or a descendant, calls
  `EmbeddedWebView.executeEditingCommand(cmd)`, and returns
  `true` to consume the event. The same pattern is reusable
  in the lightweight component (see
  [[swing-lightweight-webview-embedding]]); that integration
  is a separate Canvas update.
- **Editing-command JNI bridge dispatches the action directly
  to the engine.** Initial design used
  `[NSApp sendAction:@selector(...) to:nil from:webview]` so
  AppKit would walk the responder chain to the inner focused
  element. **That doesn't work for the AWT-embedded case**:
  the WKWebView is parented under `NSWindow.contentView` (an
  AWT-owned NSView), and even after the user clicks inside the
  WebView, AppKit's first responder is not reliably the
  WKWebView — AWT installs its own NSView keyDown handling on
  contentView and the AppKit first responder ends up on AWT's
  view rather than the WKWebView (consistent with the
  pre-existing "AWT keeps system focus until told otherwise"
  comment on `EmbeddedWebView.requestFocus`). With `to:nil`,
  `sendAction` walks the chain starting at first responder
  and never reaches WKWebView, so `copy:` / `paste:` / etc.
  no-op. The fix is to **send the action directly to the
  WKWebView**: `[webview cut:nil]` / `[webview copy:nil]` /
  `[webview paste:nil]` / `[webview selectAll:nil]`.
  WKWebView's implementations of these four standard
  `NSResponder` selectors delegate to the WebKit page's
  current selection / focused element internally — they do
  NOT require WKWebView to be first responder. Guard with
  `respondsToSelector:` so older SDKs without one of the
  selectors fail gracefully. Linux uses
  `webkit_web_view_execute_editing_command`, which already
  targets the focused frame's selection internally. Windows
  uses `document.execCommand` via `ExecuteScript` for the
  same focus-aware semantics.
- **Native click notification for Swing popup dismissal.**
  Heavyweight native peers receive mouse events directly from
  the OS, so Swing's `BasicPopupMenuUI.MouseGrabber`
  `AWTEventListener` never sees clicks that land inside the
  WebView. Without explicit cooperation, an open `JPopupMenu` /
  `JMenu` / `JComboBox` dropdown stays open when the user
  clicks in the WebView — surprising behavior that breaks the
  principle of least astonishment relative to the rest of the
  Swing UI. The fix is a thin native hook per platform that
  fires a Java callback once per native mouse-button press; the
  Java handler marshals to the EDT and calls
  `MenuSelectionManager.defaultManager().clearSelectedPath()`
  to dismiss any open popup. The click itself still flows to
  the WebView for normal handling — only the popup-dismiss
  side-effect is restored. Per-platform hook site:
  - **Linux** (WebKitGTK): extend the existing
    `gtk_gesture_multi_press_new` "pressed" handler that
    already runs the click-to-focus grab; fire the click
    callback alongside the focus-grab work, do not replace it.
  - **macOS** (WKWebView): swizzle `-[WKWebView mouseDown:]`,
    `-[WKWebView rightMouseDown:]`, and
    `-[WKWebView otherMouseDown:]` analogously to the existing
    first-responder swizzle. Call the original IMP first so
    WebKit's normal click handling is unaffected, then look up
    the engine and fire the callback. Use the existing
    `g_webview_map` so swizzles installed in this process do
    not affect unrelated `WKWebView`s; install the three
    selector swizzles once per JVM via `std::call_once`.
  - **Windows** (WebView2): hook `WM_PARENTNOTIFY` in the
    existing `EmbedWndProc`. Windows posts `WM_PARENTNOTIFY` to
    the parent HWND when a direct child receives `WM_LBUTTONDOWN`,
    `WM_RBUTTONDOWN`, `WM_MBUTTONDOWN`, or `WM_XBUTTONDOWN`; the
    low word of `wParam` identifies which. Fire the callback for
    any of those four cases, then forward to `DefWindowProc`.
  The hook is the canonical mechanism for surfacing in-WebView
  user input back to Swing for purposes AWT's event queue would
  normally handle.
- **No sync EDT→AppKit-main bridge in the macOS path.** The
  previous design (v1.0.5, PR #30) routed every EDT→main hop
  through `-[NSObject performSelectorOnMainThread:withObject:
  waitUntilDone:YES modes:]` with `AWTRunLoopMode` in the
  modes array, expecting that to break the
  EDT-parked-while-main-parked-in-AWT-doAWTRunLoopImpl cycle.
  It does break that one specific ordering, but it does NOT
  close the inverse cycle: if our `cocoa_run_on_main` block
  has already begun executing on the main thread and the
  block's body drives an AppKit primitive (e.g. `addSubview:`)
  that synchronously rendezvous with the EDT through
  AppKit accessibility / IME / `LWCToolkit.invokeAndWait`,
  the EDT is still parked on the original `performSelector`'s
  semaphore and the cycle closes. The fix is to eliminate
  the sync EDT→main bridge entirely. Three sync sites
  (`cocoa_is_first_responder`, `cocoa_create_engine`,
  `cocoa_destroy_engine`) are converted to async-or-cached
  equivalents; once all three are gone, the
  `cocoa_run_on_main` helper, the `WebViewAwtMainBridge`
  Objective-C class, and the `performWork:` selector are
  removed. The three conversion strategies are documented
  in the three following Approach entries.
- **Cached first-responder via KVO on
  `NSWindow.firstResponder`.** The previous sync probe
  walked the responder chain on the AppKit main thread on
  every call; each call was a deadlock surface for the
  editing-shortcut dispatcher's hot path. The replacement
  mirrors the responder state into an `std::atomic<bool>`
  field on the `Engine`. A KVO observer on the host
  `NSWindow`'s `firstResponder` key path fires on the main
  thread for every focus transition anywhere in the
  window's responder hierarchy; the observer callback walks
  the responder chain using the same logic the previous
  sync probe used (which correctly handles inner WebKit
  content views by walking upward) and writes the atomic.
  The atomic read in `cocoa_is_first_responder` is now
  lock-free with no thread hop. Trade-off accepted:
  swizzling `WKWebView`'s own `becomeFirstResponder` /
  `resignFirstResponder` (already done elsewhere in this
  canvas for the focus-callback path) was considered as the
  sole cache-update mechanism but rejected — focus on
  inner content views (`<input>`, contentEditable) does not
  pass through `WKWebView`'s own responder methods, so the
  cache would be wrong. KVO on the window's firstResponder
  observes every transition. The KVO observer's lifecycle
  is tied to the engine and the WKWebView's current window:
  registered lazily on the first non-nil window (the
  WKWebView's `window` property is observed in case it is
  initially nil, which is the AppKit policy during view
  hierarchy construction), unregistered on destroy, and
  re-registered against any new window if the WKWebView
  ever moves between windows at runtime.
- **Async engine attach with FIFO-queue-ordered pre-attach
  ops.** The previous design ran all of WKWebView creation,
  host-view discovery, `addSubview:`, and configuration
  synchronously on the AppKit main thread with the EDT
  parked on a semaphore. `addSubview:` triggers AppKit
  accessibility metadata queries against the AWT host view,
  which the JDK answers via `LWCToolkit.invokeAndWait` —
  and the EDT is parked above, so the cycle closes. The
  fix splits `cocoa_create_engine` into a synchronous
  prologue (allocate the C++ `Engine` struct, take the JAWT
  lock long enough to retain `surface_layers`, install the
  `EvalDispatcher` and the eval-bridge JNI globals on the
  Java side) and an async epilogue (`cocoa_run_on_main_async`)
  that does the AppKit-side setup. The JNI entry point
  returns immediately after the prologue with a valid C++
  `Engine` pointer; the AppKit-side success/failure resolves
  later and is posted back to the EDT via a JNI callback
  that flips `EmbeddedWebView.attachState` and fires any
  registered `WebViewAttachListener`s. **Pre-attach op
  ordering is solved by the macOS main dispatch queue
  itself.** `dispatch_get_main_queue()` is a documented
  serial FIFO queue — blocks fire one at a time in enqueue
  order. The attach epilogue is the first block enqueued;
  every subsequent `cocoa_navigate` / `cocoa_eval` /
  `cocoa_init_script` / `cocoa_set_bounds` /
  `cocoa_request_focus` / `cocoa_set_visible` /
  `cocoa_execute_editing_command` call enqueues its own
  async block and they fire in registration order, AFTER
  the attach block, with `e->webview` and `e->manager`
  populated. Each per-op block already null-checks the
  field at fire time and silently no-ops if cleared (for
  the destroy-side race), so the implementation pattern
  already handles the pre-attach case at zero additional
  cost. No Java-level pre-attach buffer is introduced in
  `EmbeddedWebView`; the pre-paint buffer in
  `WebViewHeavyweightComponent` (its `pendingUrl` /
  `pendingInit` / `pendingBindings` fields) keeps working
  exactly as today, layered above this lower-level mechanism.
  The one operation that previously ran synchronously on the
  calling thread — `cocoa_bind` (mutates `e->bindings`) —
  MUST be converted to an async-on-main lambda so it
  enqueues after the attach block (FIFO) and serialises
  against the script-message-handler delegate's reads
  (which also run on main). Alternatives considered:
  - Java-level pre-attach buffer in `EmbeddedWebView`
    (mirroring the heavyweight component's pattern).
    Rejected as the primary mechanism — duplicates the
    FIFO-queue ordering invariant that the macOS main queue
    already provides. Kept available as a fallback if
    testing reveals a queue-ordering edge case.
  - Keep `cocoa_bind` sync with a `std::mutex` around
    `e->bindings`. Rejected — converting to async keeps
    the "every per-engine native op is async" invariant
    uniform and trivially serialises against the
    script-message-handler's reads.
  - Move to a `CompletableFuture<EmbeddedWebView>` attach
    factory. Rejected — every existing caller would have
    to be rewritten; the listener API serves the same
    purpose with no break.
- **`EmbeddedWebView.attach(...)` stays a synchronous Java
  factory.** The Java signature is unchanged across all
  platforms. On Windows / Linux, attach completes
  synchronously inside the JNI call as today and the
  returned `EmbeddedWebView` is in `ATTACHED` state by the
  time it is observable. On macOS, attach returns in
  `PENDING` state and resolves asynchronously. Pre-attach
  method calls on a `PENDING` engine MUST NOT throw — they
  enqueue native async blocks that fire after the attach
  epilogue, exactly as post-attach calls do. The contract
  the caller sees is: "after `attach(...)` returns, the
  `EmbeddedWebView` is fully usable; setUrl / addBinding /
  eval / etc. all work; if you want a definite signal that
  the native view is on-screen, register a
  `WebViewAttachListener`."
- **Synchronous failure path preserved for C++ allocation
  failure.** If the C++ `Engine` struct allocation itself
  fails (out-of-memory, JAWT lock failure, missing JAWT on
  the platform), `webview_embed_create` returns `0` and
  `EmbeddedWebView.attach(...)` throws
  `IllegalStateException` synchronously, exactly as today.
  Only the AppKit-side phase is moved async. The new
  listener-driven failure path covers the AppKit-side
  failures (WKWebView allocation returns nil, no hostable
  NSView discovered) that did not previously exist as a
  distinct failure mode.
- **Async engine destroy with `destroyed` flag belt-and-
  suspenders.** The previous design ran `removeFromSuperview`
  and view-hierarchy teardown synchronously on the AppKit
  main thread with the EDT parked. `removeFromSuperview`
  triggers the same AppKit accessibility metadata path as
  `addSubview:`, so the destroy side has the same deadlock
  surface. The fix moves the entire AppKit teardown
  (`removeFromSuperview`, host-view release, surface-layers
  release, webview release, config release, KVO observer
  unregistration) and the C++ `delete e` call into a
  `cocoa_run_on_main_async` lambda. The JNI entry returns
  immediately. The Java-side teardown sequence (drain
  `evalDispatcher`, clear focus / click callbacks via
  `setFocusCallback(null)` / `setClickCallback(null)`, set
  `peer = 0L`, then `webview_embed_destroy`) is preserved
  unchanged — those steps run on the calling thread (EDT)
  BEFORE the async destroy lambda is enqueued, exactly as
  today. **Use-after-free safety**: a `destroyed:
  std::atomic<bool>` flag on the `Engine` is set to `true`
  at the top of the destroy lambda before any AppKit
  teardown runs. Every other async-on-main lambda
  (navigate / eval / init / setBounds / setVisible /
  requestFocus / executeEditingCommand) reads the flag at
  fire time and short-circuits cleanly if `true`. **The
  primary correctness guarantee is FIFO dispatch-queue
  ordering plus EDT-only enqueueing** — no async lambda is
  ever enqueued after destroy is, so no lambda fires after
  destroy fires. The flag is belt-and-suspenders against
  (a) destroy being called from a non-EDT thread
  (hypothetical), (b) future code changes that violate the
  EDT-only-enqueue invariant, and (c) the `cocoa_eval`
  late-completion path that needs to report "WebView
  disposed" back to a `CompletableFuture`. Trade-off:
  callers who relied on "after `dispose()` returns, the
  WKWebView is fully detached from the view hierarchy"
  will see the detachment happen up to one main-loop tick
  later. No current caller depends on this — the demos
  and `WebViewHeavyweightComponent` do not observe AppKit
  state from Java after dispose — but is documented for
  future readers. Alternative considered: a generation
  counter (`std::atomic<uint64_t>`) instead of a flag.
  Rejected as over-engineering — engines in this library
  are never reused, and the flag is sufficient for the
  current contract. The counter design can be reintroduced
  later if an engine-reset operation is ever needed.

## S · Structure
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  — Swing wrapper and lifecycle.
- `src/ca/weblite/webview/EmbeddedWebView.java` — low-level
  embed JNI wrapper.
- `src/ca/weblite/webview/WebViewNative.java:131`–
  `src/ca/weblite/webview/WebViewNative.java:177` — native
  entry points (`webview_embed_create`,
  `webview_embed_navigate`, etc.). Adds two new declarations:
  `native static int webview_embed_open_devtools(long w)` and
  `native static void webview_embed_execute_editing_command(long w, int cmdId)`.
- `src/ca/weblite/webview/EditingCommand.java` (new) — public
  enum with `CUT(1)`, `COPY(2)`, `PASTE(3)`, `SELECT_ALL(4)`
  and `int getNativeId()`. The enum is the only thing callers
  pass through `EmbeddedWebView.executeEditingCommand`.
- `src/ca/weblite/webview/WebViewClickCallback.java` (new) —
  public `@FunctionalInterface` with `void invoke()`. Passed
  through `EmbeddedWebView.setClickCallback`; invoked by the
  per-platform native click hook once per mouse-button press
  inside the heavyweight WebView's native surface. Mirrors the
  shape of the existing `WebViewFocusCallback`.
- `src/ca/weblite/webview/WebViewAttachListener.java` (new) —
  public functional interface with two methods:
  `void onAttached(EmbeddedWebView)` and
  `void onAttachFailed(EmbeddedWebView, Throwable)`. Registered
  on `EmbeddedWebView` via the new `addOnAttachComplete`
  method; the callbacks fire on the Swing EDT. On Windows /
  Linux the listener fires synchronously on the next EDT tick
  because attach completes inside the constructor; on macOS it
  fires asynchronously once the AppKit-side setup resolves.
- `src/ca/weblite/webview/AttachState.java` (new) — public
  enum with values `PENDING`, `ATTACHED`, `FAILED`. Returned
  by `EmbeddedWebView.getAttachState()`. State transitions
  are EDT-only.
- `src/ca/weblite/webview/ConsoleDispatcher.java` (from
  [[swing-webview-component-mode-selection]]) — owned by the
  component; receives raw payloads from the internal
  `__webview_console__` binding callback registered in
  `createPeer`.
- `src/ca/weblite/webview/EvalDispatcher.java` (owned by
  [[webview-async-javascript-eval]]) — owned per-engine by
  `EmbeddedWebView`; receives raw payloads from the internal
  `__webview_eval_result__` binding callback registered in
  `EmbeddedWebView.attach`. Provides `SHIM_JS`, `CHANNEL_NAME`,
  `evalAsync`, `dispatch`, and `disposeAllPending`.
- `src/ca/weblite/webview/JavaScriptEvalException.java` (owned
  by [[webview-async-javascript-eval]]) — surfaces in the
  exceptional completion of `evalAsync` futures when the JS
  side fails (sync throw, Promise rejection, serialization
  failure).
- `src_c/webview_embed.cpp`, `windows/webview_embed.cc` — native
  implementations (Linux+macOS and Windows respectively). New
  implementations: the Linux+macOS file gains a function
  exported as `Java_..._webview_1embed_1open_1devtools` that
  switches on the engine kind (Linux vs macOS) for the body;
  the Windows file gains the WebView2-worker-marshalled
  implementation. The macOS `cocoa_create_engine` block is
  extended to call `setInspectable:YES` under
  `respondsToSelector` when `debug=true`. Both native sources
  also gain a function exported as
  `Java_..._webview_1embed_1execute_1editing_1command` that
  marshals to the correct UI thread (AppKit main / GTK main /
  WebView2 worker) and invokes the per-platform editing-command
  primitive (`[NSApp sendAction:to:nil from:webview]` /
  `webkit_web_view_execute_editing_command` /
  `webview->ExecuteScript("document.execCommand(...)")`). Both
  sources also gain a function exported as
  `Java_..._webview_1embed_1set_1click_1callback` that stores
  a JNI global ref on the engine; the per-platform native
  mouse-press hook (gtk-gesture extension on Linux,
  `mouseDown:`/`rightMouseDown:`/`otherMouseDown:` swizzle on
  macOS, `WM_PARENTNOTIFY` on Windows) reads that ref and
  invokes the Java callback's `invoke()` method.
  macOS-specific structural changes for the sync-deadlock
  elimination work (see Operation 14): the
  `cocoa_run_on_main` synchronous helper, the
  `WebViewAwtMainBridge` Objective-C class, the
  `performWork:` selector, and the `ensure_awt_main_bridge`
  / `awt_bridge_box` / `awt_main_bridge_perform_impl`
  scaffolding are removed; the `g_awt_main_bridge_cls` /
  `g_awt_main_bridge_target` / `g_awt_main_bridge_modes` /
  `g_awt_main_bridge_once` file-scope statics are removed.
  `cocoa_create_engine` is split into a synchronous prologue
  + `cocoa_run_on_main_async` epilogue; `cocoa_destroy_engine`
  becomes fully async; `cocoa_is_first_responder` reads an
  atomic on the `Engine` struct. The `Engine` struct gains
  `is_first_responder: std::atomic<bool>`, `destroyed:
  std::atomic<bool>`, `kvo_observer: id`, and
  `observed_window: id` fields. `cocoa_bind` becomes async
  on main (FIFO-ordered after the attach epilogue) instead
  of mutating `e->bindings` synchronously on the calling
  thread. The JNI export for `webview_embed_create` keeps
  its `jlong`-returning signature on every platform; the
  macOS side returns the freshly-allocated `Engine*` after
  the synchronous prologue. A new JNI callback channel
  (declared on `WebViewNative`) carries the AppKit-side
  attach success / failure outcome from the async epilogue
  back to the EDT.
- `demos/WebViewHeavyweightDemo/...` — interactive demo that
  exercises the trickier scenarios (combo-box popups over the
  WebView, `JTabbedPane` tab visibility, and on-demand
  instantiation of additional WebView instances in the same
  JVM via a "+ New WebView Tab" toolbar button — the issue
  #21 repro for the macOS `WebviewEmbedDelegate` once-per-JVM
  registration constraint).

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
   - `evalDispatcher: final EvalDispatcher` (new) — per-engine
     fan-out hub for `evalAsync` round-tripping. Constructed
     inside `attach` after the peer is created, with
     `marshalToEdt = true`, `disposeLabel = "EmbeddedWebView"`,
     and an `EvalSink` lambda that issues
     `WebViewNative.webview_embed_eval(peer, wrappedJs)` when
     `peer != 0L` (no-op otherwise — the dispatcher's own
     `disposed` flag and `EmbeddedWebView.evalAsync`'s
     pre-check already handle the dead-peer case; the sink's
     `peer != 0L` guard is belt-and-suspenders against a
     destroy that races a dispatch). The dispatcher class
     itself is owned by [[webview-async-javascript-eval]].
3. Methods:
   - `attach(Component parent, boolean debug): EmbeddedWebView`
     - Logic: null-check `parent`
       (`EmbeddedWebView.java:50`); require
       `parent.isDisplayable()` (`EmbeddedWebView.java:53`); call
       `WebViewNative.webview_embed_create(parent, debug?1:0)`
       (`EmbeddedWebView.java:57`); throw if zero
       (`EmbeddedWebView.java:58`); wrap the peer in a new
       instance.
       **Install the eval bridge BEFORE returning** so it is
       in place before the heavyweight component's `createPeer`
       starts replaying user config: construct the
       `EvalDispatcher` (storing it in the `evalDispatcher`
       field) with the `EvalSink` lambda described in Fields;
       call `webview_embed_init(peer, EvalDispatcher.SHIM_JS)`
       to register the wrapper-installer at document-start
       (idempotency is guarded inside the shim itself by
       `window.__webview_eval_installed__`); register the
       resolver binding by constructing a
       `WebViewNativeCallback` whose `invoke(arg, wv)` calls
       `evalDispatcher.dispatch(arg)`, anchoring it in `heap`
       (anti-GC), and calling
       `WebViewNative.webview_embed_bind(peer,
       EvalDispatcher.CHANNEL_NAME, fn, peer)`. The
       reserved-name guard on the higher-level
       `WebViewHeavyweightComponent.addJavascriptCallback` is
       bypassed because this registration goes directly through
       the `WebViewNative` JNI call rather than through any
       component-level method — same pattern as the existing
       console / mouse bridges register from
       `WebViewHeavyweightComponent.createPeer`.
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
   - `evalAsync(String js): CompletableFuture<String>` (new) —
     `Objects.requireNonNull(js, "js");` — null is a programming
     error, propagate synchronously per
     [[webview-async-javascript-eval]] Norms. If `peer == 0L`,
     allocate a fresh `CompletableFuture<String>` and complete
     it exceptionally with
     `IllegalStateException("EmbeddedWebView has been disposed.")`
     (matching the message string the existing `checkAlive`
     produces) and return it without touching the dispatcher.
     Otherwise delegate to `evalDispatcher.evalAsync(js)` and
     return the dispatcher's future verbatim. Does NOT call
     `checkAlive` because `checkAlive` throws and the
     `evalAsync` contract is that lifecycle failures arrive as
     exceptional futures, not as thrown exceptions. The
     dispatcher handles the full JS contract, error mapping,
     in-flight map, and EDT marshaling.
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
   - `dispose(): void` — if `peer != 0`, FIRST call
     `evalDispatcher.disposeAllPending()` so every pending
     `evalAsync` future completes exceptionally with
     `IllegalStateException("EmbeddedWebView disposed")` (and
     the dispatcher's `disposed` flag flips so subsequent
     `evalAsync` calls return an already-failed future without
     touching the engine); then zero `peer`, call
     `webview_embed_destroy`, clear `heap` and `bindings`
     (`EmbeddedWebView.java:194`). The order matters: drain
     pending futures BEFORE clearing `heap`, because the
     resolver-binding callback (anchored in `heap`) could
     otherwise be GC'd between the drain and the native
     destroy — the dispatcher's drain marks the futures
     `cancelled`-by-exception in a single atomic snapshot, so
     any callback that fires after the drain finds an empty
     pending map and silently drops.
   - `executeEditingCommand(EditingCommand cmd): EmbeddedWebView` (new)
     - Logic: null-check `cmd` (throw `NullPointerException`
       with a message naming the parameter); `checkAlive`;
       call
       `WebViewNative.webview_embed_execute_editing_command(peer, cmd.getNativeId())`;
       `return this`. Pure side-effect; does NOT touch
       `heap` or `bindings`. The native call is responsible
       for marshalling to the correct UI thread; the Java
       caller MUST be allowed to invoke this from the EDT
       without blocking.
   - `openDevTools(): boolean` (new)
     - Logic: `checkAlive`; call
       `WebViewNative.webview_embed_open_devtools(peer)`;
       return `(result == 1)`. Does NOT touch `heap` or
       `bindings` — pure side-effect call.
   - `addOnAttachComplete(WebViewAttachListener listener): EmbeddedWebView` (new)
     - Null-check `listener` (throw `NullPointerException`
       with a message naming the parameter).
     - If `attachState == PENDING`, append the listener to
       `attachListeners`.
     - If `attachState == ATTACHED`, schedule
       `SwingUtilities.invokeLater` that calls
       `listener.onAttached(this)`. MUST NOT fire inline on
       the calling thread.
     - If `attachState == FAILED`, schedule
       `SwingUtilities.invokeLater` that calls
       `listener.onAttachFailed(this, attachFailure)`. MUST
       NOT fire inline.
     - Returns `this` for chaining. Pure side-effect; does
       NOT touch `heap` or `bindings`. Safe to call before
       or after attach resolves and from the EDT only — the
       method MAY assume EDT thread (document this) so the
       state read and the list append do not need explicit
       synchronisation.
   - `getAttachState(): AttachState` (new)
     - Read and return `attachState`. EDT-only. Useful for
       tests and for callers that prefer polling over
       listeners.
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
   - `evalAsync(String js): CompletableFuture<String>` — if
     `embedded == null`, allocate a fresh
     `CompletableFuture<String>` and complete it exceptionally
     with `IllegalStateException("WebViewComponent not displayed")`,
     return it. Otherwise delegate to `embedded.evalAsync(js)`
     and return its future verbatim. No buffering: pre-display
     calls fail rather than queue, because a future demands a
     definite resolution and there is no defined moment to
     resolve a buffered pre-display call. The dispatcher inside
     `embedded` handles the in-flight map, the JS contract, the
     `JavaScriptEvalException` mapping, and the EDT marshaling
     (per the `marshalToEdt = true` branch of
     [[webview-async-javascript-eval]]).
   - `addJavascriptCallback(String name, JavascriptCallback cb)`
     — reject reserved-prefix names (any `name.startsWith("__webview_")`)
     with `IllegalArgumentException` BEFORE mutating any
     state; then put in `pendingBindings`; if live, call
     `embedded.addJavascriptCallback` immediately
     (`WebViewHeavyweightComponent.java:115`).
   - `dispatch(Runnable r)` — no-op until live; once
     `embedded != null`, delegate to `embedded.dispatch(r)`.
     Transient work is not buffered: a `Runnable` posted
     before display has no defined moment to run.
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
       Call `sizeNative()` to apply current bounds.
       **Finally, seed the peer's initial visibility from the
       component's current showing state**: call
       `embedded.setVisible(isShowing())`. This closes the
       create-while-hidden race — a peer created while its Swing
       region is not showing (e.g. built inside a not-selected
       `JTabbedPane` tab) must not default to visible. The
       `EmbeddedCanvas` `HierarchyListener` only fires on
       `SHOWING_CHANGED` *transitions* and any hide-transition
       that occurred before `embedded` was assigned was dropped
       (the listener early-returns when `embedded == null`), so
       seeding here is the only path that hides such a peer. On
       macOS the native dispatch queue is FIFO, so the seeded
       `setVisible(false)` is ordered after the asynchronous
       attach epilogue's `addSubview:` and reliably hides the
       WKWebView even though the attach completed after the tab
       was deselected.

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

### 10. Editing-Command Shortcut Dispatch
Files:
- `src/ca/weblite/webview/EditingCommand.java` (new enum)
- `src/ca/weblite/webview/EmbeddedWebView.java`
  (`executeEditingCommand` — see Operation 1)
- `src/ca/weblite/webview/WebViewNative.java`
  (new `webview_embed_execute_editing_command` declaration)
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  (dispatcher install / uninstall + dispatch logic)
- `src_c/webview_embed.cpp` (cocoa + gtk native bodies)
- `windows/webview_embed.cc` (WebView2 native body)

1. Responsibility: turn AWT platform-shortcut + C/V/X/A
   `KEY_PRESSED` events whose focus owner is inside the
   `WebViewHeavyweightComponent` into the correct
   editing-command invocation against whatever has in-page
   focus inside the native WebView, on every supported
   platform.
2. EditingCommand enum:
   - Declared `public enum EditingCommand` in package
     `ca.weblite.webview` with values `CUT(1)`, `COPY(2)`,
     `PASTE(3)`, `SELECT_ALL(4)`.
   - `private final int nativeId` set by the constructor;
     public accessor `int getNativeId()`.
   - Numeric IDs are part of the JNI ABI: they MUST match
     the switch statement in the native bodies. Reusing or
     shifting an existing ID is a breaking change.
3. Java entry point declaration (`WebViewNative.java`):
   - `native static void webview_embed_execute_editing_command(long w, int cmdId)`.
   - Header regeneration is required (see
     [[native-library-loading-and-packaging]]).
4. Dispatcher install / remove in
   `WebViewHeavyweightComponent`:
   - Add field
     `private KeyEventDispatcher editingShortcutDispatcher`
     (default `null`).
   - Override `addNotify()`: call `super.addNotify()` first;
     if `editingShortcutDispatcher == null`, build a new
     `KeyEventDispatcher` (lambda or inner class) with the
     logic in step 5; assign to the field; register via
     `KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(...)`.
   - Override `removeNotify()`: if
     `editingShortcutDispatcher != null`, call
     `KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(editingShortcutDispatcher)`,
     then null the field. Call `super.removeNotify()` last
     (matching the existing `dispose()`-before-super ordering
     used by `EmbeddedCanvas.removeNotify`).
5. Dispatcher logic (executed for every AWT key event in the
   JVM, MUST short-circuit cheaply for events it does not
   handle):
   - Return `false` immediately if
     `e.getID() != KeyEvent.KEY_PRESSED`.
   - Compute `int shortcutMask =
     Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()`
     (Java 8 compatible — the project targets 1.8 in
     `pom.xml`, so `getMenuShortcutKeyMaskEx` is unavailable).
     Cache it in a `static final` field on the dispatcher
     class to avoid re-reading on every key event.
   - Return `false` if
     `(e.getModifiers() & shortcutMask) != shortcutMask`.
   - Switch on `e.getKeyCode()`:
     - `VK_C` → `EditingCommand.COPY`
     - `VK_V` → `EditingCommand.PASTE`
     - `VK_X` → `EditingCommand.CUT`
     - `VK_A` → `EditingCommand.SELECT_ALL`
     - default → return `false`.
   - Return `false` if `embedded == null` — the component is
     mid-attach or mid-teardown; let Swing's default handler
     run.
   - Return `false` if the component is not currently showing
     (`isShowing()`).
   - Return `false` if the component's window ancestor is
     not focused
     (`SwingUtilities.getWindowAncestor(this).isFocused()`).
     `KeyEventDispatcher` fires for every key event in the
     JVM; this gate keeps a key press in another window from
     triggering an editing command in this WebView.
   - Resolve the AWT focus owner via
     `KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()`.
     Compute two boolean signals:
     - `focusInWebView`: true iff the AWT focus owner is
       non-null and is either this component or descended
       from it (`focusOwner == this ||
       SwingUtilities.isDescendingFrom(focusOwner, this)`).
       This is the standard signal on Linux lightweight,
       where the component calls
       `requestFocusInWindow()` on mouse-pressed; it may
       also be true on Windows when AWT focus is on the
       embedded heavyweight `Canvas`.
     - `nativeFocusOnWebView`:
       `embedded.isNativeFirstResponder()`. macOS-specific
       in practice (Linux / Windows native bodies stub to
       return 0); on macOS this is the ONLY reliable signal
       that the user is interacting with the WebView,
       because AWT's focus owner stays on whichever Swing
       component last had focus while the WKWebView holds
       native first-responder status. The macOS native body
       MUST NOT perform a synchronous main-thread hop on
       this call — it MUST read the cached
       `is_first_responder` atomic on the `Engine` struct,
       maintained by the KVO observer on
       `NSWindow.firstResponder` (see Operation 14). The
       dispatcher's hot path on every editing keystroke is
       therefore a lock-free atomic load with no thread
       hop; the previous synchronous probe (which is the
       sync site the `WebViewDeadlockRepro` demo wedges on)
       is gone.
   - **Default to deferring to Swing.** If both signals are
     false, return `false` so AWT delivers the event to its
     focus owner via the normal dispatch path. This is the
     conservative gate that keeps a sibling `JTextField` (or
     any other Swing component) working when the user is
     interacting with it rather than the WebView. The
     earlier "defer iff focus owner is a `JTextComponent`,
     otherwise dispatch" gate was too permissive: any
     non-text focus owner (a `JFrame`'s content pane, a
     `null` focus owner during a focus transition, the AWT
     focus owner being weirdly out-of-sync with native
     focus on Windows) caused Ctrl+V to hijack to the
     WebView even when the user was nowhere near it.
   - Only when at least one of the two signals is true
     do we call `embedded.executeEditingCommand(cmd)` and
     return `true` to consume the event.
   - **Do NOT also require focus owner to be `this` or a
     descendant.** On macOS the heavyweight peer holds the
     real keyboard focus natively, and AWT's focus owner
     stays on whatever Swing component had it before (often
     the root pane of the JFrame, which is an ANCESTOR of
     this component, so an `isDescendingFrom(focusOwner, this)`
     check returns false and the dispatcher silently bails
     out — the exact failure mode that the first iteration
     of this Canvas hit on macOS).
   - Otherwise call
     `embedded.executeEditingCommand(cmd)` and return `true`
     to consume the event.
6. macOS native body (`src_c/webview_embed.cpp`,
   `cocoa_*` branch):
   - Marshal to the AppKit main thread via
     `cocoa_run_on_main_async` (the existing helper used by
     `cocoa_navigate` / `cocoa_eval`).
   - Switch on `cmdId`: build the appropriate `SEL` from
     `sel("cut:")`, `sel("copy:")`, `sel("paste:")`,
     `sel("selectAll:")`. Default branch returns without
     dispatching.
   - Send the action **directly to the WKWebView**, NOT via
     the responder chain. Use the existing `msg<>()` helper:
     `msg<void, id>(e->webview, action, (id)nullptr)`.
     Guard with `respondsToSelector:` so a missing selector
     on an older WebKit fails silently instead of aborting
     the process. Do NOT use
     `[NSApp sendAction:to:nil from:webview]` — see Approach
     for the AWT first-responder mismatch that makes
     responder-chain dispatch unreliable in this embedded
     setup.
7. Linux native body (`src_c/webview_embed.cpp`,
   `gtk_*` branch):
   - Marshal to the GTK main thread via the existing
     `gtk_run_on_main_async` (or equivalent dispatch) used by
     `gtk_navigate`.
   - Switch on `cmdId`: pick
     `WEBKIT_EDITING_COMMAND_CUT` /
     `WEBKIT_EDITING_COMMAND_COPY` /
     `WEBKIT_EDITING_COMMAND_PASTE` /
     `WEBKIT_EDITING_COMMAND_SELECT_ALL` (the WebKitGTK
     header defines these as `const char*` macros).
   - Call
     `webkit_web_view_execute_editing_command(WEBKIT_WEB_VIEW(e->web), command)`.
8. Windows native body (`windows/webview_embed.cc`):
   - Marshal to the WebView2 worker thread via the existing
     `PostThreadMessage` pattern.
   - Switch on `cmdId` to a string literal
     `"document.execCommand('cut')"`,
     `"document.execCommand('copy')"`,
     `"document.execCommand('paste')"`,
     `"document.execCommand('selectAll')"`.
   - Call `webview->ExecuteScript(js, nullptr)` (no
     callback — fire-and-forget; logging on failure
     `HRESULT` only).
   - If a follow-up confirms WebView2 already handles these
     shortcuts natively when the embedded HWND has focus,
     the implementation MAY become an early return after the
     thread-marshal — but the JNI entry MUST stay so the Java
     caller is unchanged.
9. Constraints / Invariants:
   - The native function is `void` and MUST NOT raise a JNI
     exception for any failure path (unsupported `cmdId`,
     missing engine pointer, native error). The Java side
     does NOT wrap calls in try/catch.
   - The integer `cmdId` contract is fixed:
     `1=CUT, 2=COPY, 3=PASTE, 4=SELECT_ALL`. Any future
     command MUST use a new positive integer; existing IDs
     MUST NOT be reused.

### 11. Bidirectional Focus Cooperation
Files:
- `src/ca/weblite/webview/WebViewFocusCallback.java` (new
  functional interface)
- `src/ca/weblite/webview/EmbeddedWebView.java`
  (`isNativeFirstResponder()`, `setFocusCallback(...)`)
- `src/ca/weblite/webview/WebViewNative.java` (two new JNI
  declarations)
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  (focus callback install + caret-suppression handler)
- `src_c/webview_embed.cpp` (WKWebView class swizzle + engine
  map + JNI bodies)
- `src_c/ca_weblite_webview_WebViewNative.h` + Windows header
  (declarations)
- `windows/webview_embed.cc` (no-op stubs)

1. Responsibility: bridge the AWT focus chain and the
   AppKit responder chain so (a) `Cmd+C/V/X/A` works in the
   WebView even when AWT focus owner is a sibling
   `JTextComponent`, and (b) visual focus indicators
   (`JTextComponent` carets) reflect where the user is
   actually interacting.
2. New public Java functional interface
   `ca.weblite.webview.WebViewFocusCallback`:
   - Single method `void invoke(boolean became)`. `became`
     is `true` when WKWebView gained native first responder,
     `false` when it resigned.
3. `EmbeddedWebView` additions:
   - `isNativeFirstResponder(): boolean` — JNI sync query;
     returns `false` if `peer == 0`. Implemented natively as
     a walk up the NSView superview chain from the
     `NSWindow.firstResponder` looking for the WKWebView
     (so both the WKWebView itself and any inner WebKit
     content view count as "the WebView is focused").
   - `setFocusCallback(WebViewFocusCallback cb): EmbeddedWebView`
     — anchors `cb` in `heap` (so JVM doesn't GC it while
     native holds a global ref) and calls
     `WebViewNative.webview_embed_set_focus_callback(peer, cb)`.
     Passing `null` clears any prior callback.
4. New JNI declarations on `WebViewNative`:
   - `native static int webview_embed_is_native_first_responder(long w)`.
     Returns 1 if the engine's WKWebView (or one of its
     descendants) is the current `firstResponder` of its
     `NSWindow`, 0 otherwise. macOS-only behaviour;
     Linux / Windows return 0.
   - `native static void webview_embed_set_focus_callback(long w, WebViewFocusCallback cb)`.
     Stores a JNI global ref to `cb` on the engine. Passing
     `null` clears + deletes any prior global ref.
5. Per-platform focus-event source:
   - **macOS**: WKWebView class swizzle. See step 5a below.
   - **Windows**: hook
     `ICoreWebView2Controller::add_GotFocus` and
     `add_LostFocus` during engine creation; the registered
     `FocusHandler` instances call `fire_focus_callback`
     with `became=true` and `became=false` respectively.
     `set_focus_callback` stores the Java callback's JNI
     global ref on the Engine; the FocusHandler reads it.
     `destroy_engine` releases the global ref BEFORE the
     WebView2 worker tears down so LostFocus during
     teardown does not invoke a freed callback.
   - **Linux**: no current implementation; the offscreen
     WebKitGTK widget does not have an AppKit-style focus
     event that maps to "user shifted interaction to the
     web view." Acceptable for now: the lightweight engine
     on Linux is in-process and already gets AWT focus
     correctly via `requestFocusInWindow()`.

5a. Native swizzle (`src_c/webview_embed.cpp`, Cocoa branch):
   - Maintain a process-global `std::map<id, Engine *>
     g_webview_to_engine` guarded by `g_webview_map_mutex`.
     Populated in `cocoa_create_engine` after WKWebView
     alloc; cleared in `cocoa_destroy_engine`.
   - On first engine creation (guard with `std::call_once`),
     swizzle `-[WKWebView becomeFirstResponder]` and
     `-[WKWebView resignFirstResponder]` via
     `class_getInstanceMethod` +
     `method_setImplementation`. Save the original IMPs in
     statics so the swizzled implementations can call
     through.
   - Swizzled implementations: call the original IMP first
     (capturing the BOOL result); if it returned YES, look
     up the receiver in `g_webview_to_engine` (under the
     mutex). If an `Engine *` is found AND that engine has
     a non-null `focus_callback` global ref, invoke the
     Java callback on a thread that's attached to the JVM.
     **Marshal to the EDT via the callback itself**: the
     Java callback is a small lambda that schedules
     `handleNativeFocusChange(became)` via
     `SwingUtilities.invokeLater`, so the JNI call only
     needs to invoke a non-EDT callback method.
   - The swizzle affects all WKWebViews in the process,
     including any unrelated ones. The engine-map lookup
     returns null for those, and we just skip the callback —
     the original implementation is still called, so
     behaviour for non-our WKWebViews is unaffected.
6. `Engine` struct (Cocoa) additions:
   - `jobject focus_callback` — JNI global ref to the
     registered `WebViewFocusCallback`, or `nullptr`. Set
     by `webview_embed_set_focus_callback`; deleted on
     replace and on `cocoa_destroy_engine`.
7. `WebViewHeavyweightComponent` integration:
   - Add fields:
     - `private javax.swing.text.JTextComponent suppressedCaretOwner` (null when no caret is suppressed).
     - `private boolean originalCaretVisible` — value to
       restore when WKWebView resigns.
   - In `createPeer()` after `EmbeddedWebView.attach`
     succeeds, call
     `embedded.setFocusCallback(became -> SwingUtilities.invokeLater(() -> handleNativeFocusChange(became)))`.
     (The lambda must be anchored — `EmbeddedWebView.heap`
     handles this via `setFocusCallback`.)
   - `handleNativeFocusChange(boolean became)` runs on the
     EDT:
     - If `became == true`:
       - If `suppressedCaretOwner != null`, return
         (idempotent; we already suppressed a caret in a
         previous transition).
       - Query the AWT focus owner. If it is a
         `JTextComponent`, record it as
         `suppressedCaretOwner`, record its
         `getCaret().isVisible()` as
         `originalCaretVisible`, and call
         `getCaret().setVisible(false)`.
     - If `became == false`:
       - If `suppressedCaretOwner != null`, call
         `getCaret().setVisible(originalCaretVisible)` to
         restore, then null the field.
8. Reverse direction (Swing component gains focus → WKWebView
   resigns) is handled implicitly on macOS:
   - User clicks `JTextField` → AWT moves focus to
     `JTextField` → AWT calls AppKit
     `makeFirstResponder:` on its NSView → WKWebView
     resigns first responder → swizzled
     `resignFirstResponder` fires → callback with
     `became = false` → `handleNativeFocusChange(false)` →
     caret restored on `suppressedCaretOwner`.
   - **On Windows the same implicit path does not work.**
     When the WebView2 HWND holds Win32 keyboard focus and
     the user clicks a Swing widget rendered inside the
     AWT JFrame's HWND area (e.g. the URL `JTextField` in
     the toolbar), AWT moves its Java-side focus owner to
     the `JTextField`, but Win32 keyboard focus stays on
     the WebView2 HWND because AWT does not automatically
     `SetFocus` away from a focused child HWND. Subsequent
     keystrokes (`Ctrl+V` etc.) still route to WebView2 via
     Win32, bypassing AWT and the editing-shortcut
     dispatcher entirely. The fix is a Windows-only
     "release native focus" path described in Operation 12
     below.

### 12. Release Native Keyboard Focus on AWT Focus Move (Windows)
Files:
- `src/ca/weblite/webview/EmbeddedWebView.java`
  (`releaseNativeFocus()` method)
- `src/ca/weblite/webview/WebViewNative.java` (one new
  JNI declaration)
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  (global `KeyboardFocusManager` property listener that
  calls `releaseNativeFocus()` when AWT focus moves to a
  non-WebView component)
- `src_c/webview_embed.cpp` (no-op body on macOS / Linux)
- `windows/webview_embed.cc` (Win32 `SetFocus` body with
  `AttachThreadInput` for the cross-thread case)
- Headers: declarations on both `src_c` and `windows` header.

1. Responsibility: ensure Win32 keyboard focus follows AWT
   focus on Windows. When the user shifts AWT focus away
   from the embedded WebView, force Win32 to deliver
   subsequent keyboard input to the AWT-owned parent HWND
   instead of the WebView2 child HWND.
2. New JNI declaration on `WebViewNative`:
   `native static void webview_embed_release_native_focus(long w)`.
   Returns void; never throws.
3. New method on `EmbeddedWebView`:
   `releaseNativeFocus(): EmbeddedWebView` — `checkAlive`,
   then call the JNI entry. Pure side-effect; does not
   touch `heap` or `bindings`.
4. Native bodies:
   - macOS (Cocoa branch): no-op. AppKit already handles
     focus correctly via the responder chain — when AWT
     moves focus to a Swing component it calls
     `makeFirstResponder:` on its own NSView, which kicks
     WKWebView out of first responder. The swizzled
     `resignFirstResponder` hook fires and restores Swing
     caret state. No native action needed here.
   - Linux (GTK branch): no-op. AWT/X11 focus handling is
     adequate for the heavyweight path on Linux today;
     re-introducing native focus mutation from Java would
     risk the rendering regression documented at
     `WebViewHeavyweightComponent.java:223`.
   - Windows (WebView2): dispatch to the WebView2 worker
     thread via the existing
     `embed_win::dispatch_to_thread` helper. On the worker
     thread: obtain the AWT parent HWND from `e->parent`;
     attach the worker thread's input state to the AWT
     thread (via `AttachThreadInput(workerTid, parentTid,
     TRUE)`); call `SetFocus(e->parent)`; detach. The
     `AttachThreadInput` step is mandatory — Win32 focus
     is per-thread, and `SetFocus` from a thread that does
     not own the target HWND silently no-ops without the
     attach.
5. `WebViewHeavyweightComponent` integration:
   - Add field `private PropertyChangeListener focusOwnerListener`
     (null until `addNotify`).
   - In `addNotify()`, after the editing-shortcut dispatcher
     is installed, build a `PropertyChangeListener` for the
     `"focusOwner"` property and register it on
     `KeyboardFocusManager.getCurrentKeyboardFocusManager()`.
     The listener:
     - Reads the new value (the new focus owner).
     - Returns immediately if `embedded == null` or the new
       value is `null`.
     - Returns immediately if the new value is `this` or a
       descendant of `this` (focus moved INTO the WebView;
       no native release needed).
     - Returns immediately if the new value's window
       ancestor is not this component's window (focus
       moved to an unrelated window).
     - Otherwise calls `embedded.releaseNativeFocus()`.
   - In `removeNotify()`, before the editing-shortcut
     dispatcher is unregistered, unregister the
     `focusOwnerListener` and null the field.
6. Constraints / Invariants:
   - The listener is global (fires for every focus change
     in the JVM). Short-circuit checks MUST be cheap to
     avoid burdening unrelated focus traffic.
   - `releaseNativeFocus()` is `void` and MUST NOT raise a
     JNI exception; failure paths (bad HWND, no
     controller, AttachThreadInput failure) log via the
     existing `WV_LOG` pattern and return without
     throwing.
   - The listener MUST be unregistered in `removeNotify()`
     to avoid leaking the component through the
     `KeyboardFocusManager`'s strong reference (same
     lifecycle norm as the editing-shortcut dispatcher).
9. Constraints / Invariants:
   - The swizzle MUST be installed exactly once per JVM —
     guard with `std::call_once`. `method_setImplementation`
     is destructive on re-application and would corrupt
     the original-IMP save.
   - The `WebviewEmbedDelegate` Objective-C class — the
     `WKScriptMessageHandler` used inside
     `cocoa_create_engine` to receive
     `window.external.invoke` messages — MUST be allocated
     and registered exactly once per JVM, using the same
     `std::call_once` pattern as the focus swizzle above,
     with the resulting `Class` cached in a file-scope
     static. Every subsequent engine creation re-uses the
     cached `Class` and instantiates a fresh delegate
     object via `[Class new]` (then
     `objc_setAssociatedObject(..., "eng", ...)` to bind
     the per-engine receiver). `objc_allocateClassPair`
     returns `Nil` when a class with the requested name is
     already registered, so an unguarded re-allocation on
     the second engine creation would feed a null `Class`
     to `objc_registerClassPair` and crash the JVM with
     SIGSEGV — see issue #21. `class_addProtocol`,
     `class_addMethod`, and `objc_registerClassPair` MUST
     run only inside the `std::call_once` body.
   - The Engine ↔ WKWebView map MUST be guarded by a
     mutex — `becomeFirstResponder` can fire on the AppKit
     main thread while `cocoa_create_engine` /
     `cocoa_destroy_engine` run on EDT-driven native code.
   - `handleNativeFocusChange` MUST run on the EDT. The
     native callback path itself does NOT need to be on
     the EDT; the Java-side lambda invokes
     `SwingUtilities.invokeLater` to marshal.
   - The handler MUST NOT call `requestFocusInWindow()` or
     any other AWT method that would propagate focus to
     AppKit — that would kick WKWebView back out of first
     responder AND cut off keyboard input to the page.
     Caret suppression is purely a Swing-side cosmetic
     change.
   - Caret restoration on `became == false` MUST happen
     even if AWT focus has since moved to a different
     `JTextComponent`. The handler restores the
     previously-recorded caret (whichever was suppressed
     on the prior `became == true`), not the current focus
     owner's caret.
   - `setFocusCallback(null)` clears the global ref and
     deletes it cleanly. `EmbeddedWebView.dispose()` also
     clears via `setFocusCallback(null)` BEFORE
     `webview_embed_destroy` so the swizzled hooks don't
     fire into a freed callback.

### 13. Native Click Notification for Popup Dismissal
Files:
- `src/ca/weblite/webview/WebViewClickCallback.java` (new
  functional interface)
- `src/ca/weblite/webview/EmbeddedWebView.java`
  (`setClickCallback(WebViewClickCallback cb)` + cleanup in
  `dispose()`)
- `src/ca/weblite/webview/WebViewNative.java` (one new JNI
  declaration)
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  (click callback registration in `createPeer()` and the
  `handleNativeClick()` EDT handler)
- `src_c/webview_embed.cpp` (Linux gtk-gesture extension +
  macOS `mouseDown:` / `rightMouseDown:` / `otherMouseDown:`
  swizzles + JNI dispatcher)
- `src_c/ca_weblite_webview_WebViewNative.h` + Windows header
  (declarations)
- `windows/webview_embed.cc` (`WM_PARENTNOTIFY` hook in
  `EmbedWndProc` + Windows JNI body)

1. Responsibility: turn user mouse-button presses anywhere
   inside the embedded heavyweight WebView's native surface
   into a Swing-side popup-dismiss action, so an open
   `JPopupMenu` / `JMenu` / `JComboBox` dropdown closes when
   the user clicks into the WebView — matching the standard
   Swing dismiss-on-outside-click behavior that
   `BasicPopupMenuUI.MouseGrabber` provides for clicks on
   pure-Swing widgets. The click is still delivered to the
   WebView for its normal handling (link navigation, text
   selection, form interaction, focus grab); only the
   popup-dismiss side-effect that AWT's event queue would
   normally deliver is restored via a native hook.
2. New public Java functional interface
   `ca.weblite.webview.WebViewClickCallback`:
   - Single method `void invoke()`. No payload — purely a
     notification. Mirrors the shape of `WebViewFocusCallback`.
   - Documented to be invoked from a native thread;
     implementations MUST marshal to the EDT themselves
     before touching Swing state.
3. `EmbeddedWebView` additions:
   - `setClickCallback(WebViewClickCallback cb): EmbeddedWebView`
     — `checkAlive`; if `cb` is non-null anchor it in `heap`;
     call
     `WebViewNative.webview_embed_set_click_callback(peer, cb)`.
     Passing `null` clears any prior callback.
   - `dispose()` modification: BEFORE the existing
     `webview_embed_destroy` call (and after / next to the
     existing `setFocusCallback(null)` clear), call
     `setClickCallback(null)`. Wrap in `try { ... } catch
     (Throwable ignored) {}` exactly like the existing
     focus-callback clear, so a JNI exception during teardown
     cannot prevent destroy.
4. New JNI declaration on `WebViewNative`:
   `native static void webview_embed_set_click_callback(long w, WebViewClickCallback cb)`.
   Stores a JNI global ref to `cb` on the engine. Passing
   `null` clears + deletes any prior global ref. Returns
   `void`; MUST NOT raise a JNI exception.
5. Per-platform native event source:
   - **Linux** (`src_c/webview_embed.cpp`, `gtk_*` branch):
     extend the existing `gtk_gesture_multi_press_new`
     "pressed" handler that already runs the X11 + GTK focus
     grab on every mouse-button press in the WebView. After
     the existing focus-grab work (do NOT replace or move
     it), call `fire_click_callback(eng)`. Add a
     `fire_click_callback(Engine *e)` function that mirrors
     the `fire_focus_callback` shape on the cocoa branch but
     takes no boolean argument (the Java callback's `invoke()`
     method has signature `()V`). Add `jobject click_callback`
     to the Linux `Engine` struct (default `nullptr`). Add
     `gtk_set_click_callback(Engine *e, JNIEnv *env, jobject cb)`
     that deletes any previous global ref and installs a new
     one via `NewGlobalRef`, or leaves the field `nullptr` when
     `cb` is `null`. Clean up the global ref in
     `gtk_destroy_engine` BEFORE destroying the GtkWidget so
     a late press cannot fire into a freed ref. The fire
     happens on the GTK main thread; the Java-side callback
     is responsible for marshalling to the EDT.
   - **macOS** (`src_c/webview_embed.cpp`, `cocoa_*` branch):
     swizzle `-[WKWebView mouseDown:]`,
     `-[WKWebView rightMouseDown:]`, and
     `-[WKWebView otherMouseDown:]` analogously to the
     existing first-responder swizzle (see Operation 11.5a).
     Use the same `g_webview_map` lookup so unrelated
     `WKWebView` instances are unaffected. Install all three
     selector swizzles in a single `std::call_once` block.
     Each swizzled implementation MUST call the original IMP
     first (so WebKit's normal click handling — text
     selection, link clicks, form interaction, focus grab —
     is unaffected), then look up the engine and invoke
     `fire_click_callback(eng)`. Saved-IMP statics follow the
     same pattern as the existing `g_orig_becomeFirstResponder`
     / `g_orig_resignFirstResponder` plumbing. Add
     `jobject click_callback` to the cocoa `Engine` struct.
     Add `cocoa_set_click_callback(Engine *e, JNIEnv *env, jobject cb)`.
     Clean up the global ref in `cocoa_destroy_engine` BEFORE
     the engine map entry is removed.
     Reuse `fire_focus_callback`'s JNIEnv-attach pattern for
     `fire_click_callback` (look up the engine's JavaVM,
     attach the current thread if needed, find the `invoke`
     method, call it, detach if we attached).
   - **Windows** (`windows/webview_embed.cc`): hook
     `WM_PARENTNOTIFY` in the existing `EmbedWndProc`. Windows
     posts `WM_PARENTNOTIFY` to the parent HWND when a direct
     child receives `WM_LBUTTONDOWN`, `WM_RBUTTONDOWN`,
     `WM_MBUTTONDOWN`, or `WM_XBUTTONDOWN`; the low word of
     `wParam` identifies which message triggered it. The
     parent HWND in our setup is `e->child` (the
     `WebViewEmbedChild` window we create in `engine_thread`),
     and the WebView2 child HWND is its direct child, so
     mouse-down events bubble through `WM_PARENTNOTIFY` to us.
     Add a `case WM_PARENTNOTIFY:` in `EmbedWndProc` that
     switches on `LOWORD(wp)` for the four down-message
     constants, calls `fire_click_callback(e)` for any of
     them, then falls through to `DefWindowProc`. Add
     `jobject click_callback` to the Windows `Engine` struct.
     Add `win_set_click_callback(Engine *e, JNIEnv *env, jobject cb)`.
     Cleanup in the destroy path BEFORE the worker thread
     teardown (symmetric to the existing focus_callback
     cleanup at the same spot).
6. JNI dispatcher (`Java_..._webview_1embed_1set_1click_1callback`):
   - `src_c/webview_embed.cpp`: under `#ifdef WEBVIEW_COCOA`
     dispatch to `embed::cocoa_set_click_callback`; under
     `#ifdef WEBVIEW_GTK` dispatch to
     `embed::gtk_set_click_callback`; `#else` no-op fallback
     that silently drops the call. Pattern mirrors the
     existing `Java_..._webview_1embed_1set_1focus_1callback`
     dispatcher.
   - `windows/webview_embed.cc`: Windows JNI body calls
     `win_set_click_callback`.
7. `WebViewHeavyweightComponent` integration:
   - In `createPeer()` AFTER the existing `setFocusCallback`
     call: also call `embedded.setClickCallback(...)` with a
     callback that schedules `handleNativeClick()` on the EDT
     via `SwingUtilities.invokeLater`. The lambda capture is
     anchored by `EmbeddedWebView.heap` via `setClickCallback`.
   - New private method `handleNativeClick(): void` on the
     EDT: call
     `javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath()`.
     Nothing else — no focus mutation, no JNI calls into the
     embedded peer, no logging in the hot path.
   - No `addNotify` / `removeNotify` work is required for the
     click callback. Lifecycle is owned end-to-end by
     `EmbeddedWebView`: `createPeer()` installs it,
     `dispose()` (called from `EmbeddedCanvas.removeNotify`)
     clears it via the embedded peer's own teardown.
8. Constraints / Invariants:
   - The Java callback MUST be safe to invoke from a native
     thread. The implementation marshals to the EDT
     internally (via `SwingUtilities.invokeLater`); callers do
     NOT need to register from the EDT or pre-marshal.
   - `webview_embed_set_click_callback` is `void` and MUST
     NOT raise a JNI exception. Bad inputs (null engine
     pointer, JNI lookup failure) fall through silently —
     same contract as `webview_embed_set_focus_callback`.
   - The callback fires for every mouse button (left, right,
     middle). Standard Swing closes popups on any outside
     click, so the WebView must match. On Linux this is
     achieved by leaving the existing
     `gtk_gesture_single_set_button(GTK_GESTURE_SINGLE(click), 0)`
     in place (button 0 = any). On macOS this requires
     swizzling all three `mouseDown:` / `rightMouseDown:` /
     `otherMouseDown:` selectors. On Windows
     `WM_PARENTNOTIFY` already covers all four button-down
     messages.
   - The Linux gesture handler also performs focus-grab work
     (XSetInputFocus on the WebKitWebView's X11 window,
     `gtk_widget_grab_focus`); the click-callback fire is
     added ALONGSIDE that work, not instead of it. The
     existing focus behavior MUST be preserved exactly —
     this Operation only adds a call, it does not modify the
     focus path.
   - The macOS `mouseDown:` swizzle (and its
     `rightMouseDown:` / `otherMouseDown:` siblings) MUST
     call the original IMP FIRST so WebKit's normal click
     handling is unaffected; the click callback fires after
     the IMP returns.
   - Cleanup ordering: `EmbeddedWebView.dispose()` calls
     `setClickCallback(null)` BEFORE `webview_embed_destroy`,
     symmetric with the existing `setFocusCallback(null)`
     cleanup. A late native click event during teardown must
     land on a `nullptr` callback field and silently no-op.
   - The macOS swizzle MUST be installed exactly once per
     JVM — guard with `std::call_once`.
     `method_setImplementation` is destructive on
     re-application and would corrupt the original-IMP save
     for any of the three selectors.
   - The Java handler MUST be scoped narrowly:
     `MenuSelectionManager.defaultManager().clearSelectedPath()`
     and nothing else. Adding focus mutations or JNI calls
     here would re-introduce the focus tangles documented in
     Operations 11 and 12.
   - `clearSelectedPath()` is a no-op when no popup is
     currently selected, so the callback can fire on every
     click without worrying about the current popup state.

### 14. Eliminate Sync EDT↔AppKit Bridge (macOS)
Files:
- `src/ca/weblite/webview/WebViewAttachListener.java` (new
  public functional interface)
- `src/ca/weblite/webview/AttachState.java` (new public
  enum)
- `src/ca/weblite/webview/EmbeddedWebView.java` (new
  `attachState` / `attachFailure` / `attachListeners`
  fields, `addOnAttachComplete`, `getAttachState`, and the
  internal JNI callback that resolves the attach state on
  the EDT; no signature change to the existing `attach`
  factory)
- `src/ca/weblite/webview/WebViewNative.java` (one new
  JNI entry point for the attach-completion callback
  registration; signature is platform-uniform, native
  bodies on Windows / Linux fire the callback immediately)
- `src_c/webview_embed.cpp` (the three sync-site removals
  plus the helper teardown)
- `windows/webview_embed.cc` (immediate-fire attach
  callback)
- `demos/WebViewDeadlockRepro/...` (extension to drive the
  long-run attach → focus → Cmd-C → dispose stress loop
  for the 5-minute test)

1. Responsibility: structurally eliminate the class of
   EDT↔AppKit-main mutual-wait deadlock from the macOS
   heavyweight WebView implementation by removing every
   synchronous EDT→AppKit-main dispatch primitive in
   `src_c/webview_embed.cpp`. Three concrete sync sites
   are converted; the synchronous helper plumbing is then
   removed. The Java-visible API surface is augmented with
   a deferred-completion listener (`WebViewAttachListener`)
   that fires on the EDT, but `EmbeddedWebView.attach(...)`
   remains a synchronous factory so existing callers are
   unaffected. Cross-platform: the new Java API exists on
   every platform; on Windows / Linux the listener fires
   immediately because attach is already synchronous.

2. Cached first-responder via KVO on
   `NSWindow.firstResponder` (closes
   `cocoa_is_first_responder` sync site):
   - Add `is_first_responder: std::atomic<bool>` to the
     `Engine` struct, default-initialised `false`.
   - Define a small Objective-C class
     (`WebViewKvoObserver` or similar) whose
     `observeValueForKeyPath:ofObject:change:context:`
     method recomputes the responder state by walking the
     responder chain from `[window firstResponder]` upward
     through `superview` looking for `e->webview` (same
     walk the previous synchronous probe used,
     `webview_embed.cpp:2004-2019`). The observer holds a
     back-pointer to its owning `Engine` via
     `objc_setAssociatedObject` or a direct field; it
     writes the result into `e->is_first_responder` with
     `store(value)`.
   - Observer lifecycle: register lazily on the first
     non-nil window. Because the WKWebView's `window`
     property may be transiently nil at the moment of
     `addSubview:` (AppKit policy during view-hierarchy
     construction), observe the WKWebView's own `window`
     key path with a one-shot KVO; on the first non-nil
     window, register the firstResponder observer on that
     window and recompute the atomic immediately, then
     either leave the window observer in place (to handle
     subsequent window changes — see below) or
     short-circuit further triggers as appropriate.
   - Window-change handling: if the WKWebView ever moves
     between windows (e.g. user drags the JFrame across
     screens, or a re-parenting operation), the
     `window`-keypath observer fires again; the
     implementation unregisters the firstResponder
     observer from the previous window's
     `observed_window` (if any) and registers it on the
     new window, then recomputes the atomic.
   - Refactor `cocoa_is_first_responder` to a single line:
     `return e ? e->is_first_responder.load() : 0;`. No
     thread hop, no responder-chain walk on the EDT side.
   - Observer registration runs inside the attach epilogue
     (which is `cocoa_run_on_main_async` after the changes
     in step 3); observer unregistration runs inside the
     destroy lambda (step 4) BEFORE any view release. On
     window-change the unregister/register happens on the
     main thread within the observer callback itself.
   - The existing swizzled `becomeFirstResponder` /
     `resignFirstResponder` hooks on `WKWebView` are NOT
     repurposed for the cache; they stay scoped to the
     existing focus-callback delivery (Operation 11).
     Rationale per Approach: those swizzles do not fire
     for focus on inner WebKit content views.

3. Async engine attach (closes `cocoa_create_engine` sync
   site):
   - Split `cocoa_create_engine` into:
     - Synchronous prologue (runs on the calling thread,
       typically EDT): allocate the C++ `Engine` struct
       with `new Engine()`; record `e->jvm`,
       `e->debug`. Acquire the JAWT lock long enough to
       retain `surface_layers` (existing
       `JawtLock` pattern); on JAWT lock failure return
       0 (matches today's `delete e; return nullptr;`
       synchronous-failure path). After the prologue
       succeeds, `webview_embed_create` returns the
       `Engine*` cast to `jlong` immediately.
     - Async epilogue (`cocoa_run_on_main_async`, runs
       on the AppKit main thread later): install the
       focus / click / first-responder swizzles
       (existing `std::call_once` guards apply); alloc
       and init the WKWebView; register in
       `g_webview_map`; find the host NSView; call
       `[host setWantsLayer:YES]`; call
       `[host addSubview:e->webview]`; install the
       script-message-handler delegate and the
       `external.invoke` shim; if `debug`, set
       `developerExtrasEnabled` / `setInspectable:`;
       register the KVO observer per step 2; finally
       post the success outcome back to the EDT (see
       step 6).
   - On any AppKit-side failure inside the async
     epilogue (WKWebView alloc returned nil, no
     hostable NSView discovered), post a failure
     outcome to the EDT carrying an actionable message
     identifying the failure step, then proceed to
     release whatever AppKit objects were allocated so
     far, drop the entry from `g_webview_map` (if it
     was inserted), and `delete e`. The C++ `Engine`
     struct MUST NOT leak on the async-failure path.
   - The synchronous return of a non-zero `Engine*`
     before the async epilogue runs is the macOS
     behavioural change. Windows and Linux retain their
     existing synchronous-create semantics inside their
     own JNI bodies.

4. Async engine destroy (closes `cocoa_destroy_engine`
   sync site):
   - Add `destroyed: std::atomic<bool>` to the `Engine`
     struct, default `false`.
   - Pre-destroy work on the calling thread (EDT)
     remains as today and runs BEFORE the async lambda
     is enqueued: drop `e->webview` from
     `g_webview_map` under `g_webview_map_mutex`;
     release the `focus_callback` JNI global ref;
     release the `click_callback` JNI global ref.
     Rationale: these refs must be cleared before the
     swizzled responder / mouse hooks could fire into a
     freed Java ref.
   - The remaining teardown moves into a
     `cocoa_run_on_main_async` lambda:
     `e->destroyed.store(true)` first; unregister the
     KVO firstResponder observer from
     `e->observed_window` (if any) and the
     `window`-keypath observer from `e->webview`; clear
     `e->kvo_observer` / `e->observed_window`; call
     `[e->webview removeFromSuperview]`; release
     `e->host_view` / `e->surface_layers` /
     `e->webview` / `e->config`; walk `e->bindings`
     releasing each Java global ref (the JNI attach /
     detach pattern existing destroy uses); `delete e`.
     The JNI entry returns immediately after enqueueing
     the lambda.
   - Every other async-on-main lambda
     (`cocoa_navigate` / `cocoa_eval` /
     `cocoa_init_script` / `cocoa_set_bounds` /
     `cocoa_set_visible` / `cocoa_request_focus` /
     `cocoa_execute_editing_command` / `cocoa_bind`)
     reads `e->destroyed.load()` at the top of the
     lambda and short-circuits cleanly if `true`. For
     `cocoa_eval` specifically, the late-fire short-
     circuit MUST NOT raise an exception or write to a
     freed pointer; the corresponding
     `CompletableFuture<String>` is already drained
     exceptionally by `EmbeddedWebView.dispose()` via
     `evalDispatcher.disposeAllPending()` before the
     JNI destroy call, so the native-side short-circuit
     is belt-and-suspenders.

5. `cocoa_bind` async conversion (required by step 3):
   - The existing `cocoa_bind(Engine *e, Binding *b) {
     e->bindings[b->name] = b; }` mutates `e->bindings`
     synchronously on the calling thread (EDT). Under
     async attach the script-message-handler delegate
     might read `e->bindings` on the main thread before
     the EDT's write is visible. Wrap the body in
     `cocoa_run_on_main_async([=] { … })` so the map
     write runs on main and serialises against the
     delegate's reads. Pre-attach `addJavascriptCallback`
     calls thus enqueue after the attach epilogue (FIFO),
     are picked up by the delegate at JS-call time, and
     the existing
     `WebViewHeavyweightComponent.createPeer` replay
     order (bindings before navigate) is preserved.

6. Attach-completion JNI bridge:
   - Declare a JNI entry point on `WebViewNative` for
     registering an attach-completion callback against a
     peer, with a Java-side signature shaped like
     `webview_embed_set_attach_callback(long peer, Object
     callback)`. The Java callback object is a small
     internal type owned by `EmbeddedWebView`; its
     `onResolved(boolean ok, String failureMessage)`
     method is invoked from the native side via JNI.
   - macOS native body: store a JNI global ref to the
     callback on the `Engine`. The async attach epilogue,
     on success, calls the global ref's `onResolved(true,
     null)`; on failure, calls `onResolved(false,
     failureMessage)`. The native call attaches the
     current thread to the JVM if needed (same pattern as
     `fire_focus_callback`) and detaches after. The
     callback in turn marshals to the EDT via
     `SwingUtilities.invokeLater` — either inside the JNI
     callback's Java body, or by having the C++ side
     enqueue an `invokeLater` directly. Either is valid;
     the listener firing rule (Norms) is that
     `WebViewAttachListener` callbacks fire on the EDT.
   - Windows / Linux native bodies: fire `onResolved(true,
     null)` immediately during `webview_embed_create`
     (the synchronous path), so the Java side observes
     `ATTACHED` as soon as the constructor returns.
   - Java side: `EmbeddedWebView` constructor registers
     the callback BEFORE returning from `attach(...)`.
     The callback flips `attachState` from `PENDING` to
     `ATTACHED` or `FAILED`, captures `attachFailure` on
     failure, and fires every registered listener via
     `SwingUtilities.invokeLater` in registration order.

7. Helper removal (after steps 2–6 land):
   - Remove the `cocoa_run_on_main` synchronous helper
     and the entire `WebViewAwtMainBridge` /
     `performWork:` / `ensure_awt_main_bridge` /
     `awt_bridge_box` / `awt_main_bridge_perform_impl`
     scaffolding from `src_c/webview_embed.cpp`. Remove
     the file-scope statics
     `g_awt_main_bridge_cls`,
     `g_awt_main_bridge_target`, `g_awt_main_bridge_modes`,
     `g_awt_main_bridge_once`. Grep `src/`, `src_c/`,
     `windows/`, and `demos/` for `cocoa_run_on_main`
     (without `_async`) and `WebViewAwtMainBridge` /
     `performWork:` — there MUST be zero matches in
     production code after this step.

8. Deadlock-repro long-run test
   (`demos/WebViewDeadlockRepro/...`):
   - Extend the demo with a stress mode that runs an
     attach → wait for `onAttached` → request focus →
     simulate Cmd-C → dispose loop for at least 5
     minutes. The existing 5-second EDT watchdog stays
     in place; the demo MUST exit cleanly with a zero
     status code after the configured duration without
     the watchdog firing.
   - Run locally under Address Sanitizer (`ASAN_OPTIONS`
     compatible with the existing Mac demo runner); any
     UAF attributable to the destroy path MUST surface
     during this test.

9. Constraints / Invariants:
   - Every per-engine native operation on macOS MUST run
     via `cocoa_run_on_main_async` (or inline when
     already on main). No new synchronous EDT→main
     dispatch primitive may be introduced in
     `src_c/webview_embed.cpp`.
   - State that the EDT needs to read about the AppKit
     main thread MUST be cached in an `std::atomic` on
     the `Engine` struct, written from the main thread.
     Direct synchronous reads of AppKit-thread state
     from the EDT are forbidden.
   - `dispatch_get_main_queue()` ordering (serial FIFO)
     is load-bearing for pre-attach op replay. Any change
     that uses a different queue, multiple queues, or
     introduces concurrent dispatch breaks the
     no-Java-buffer invariant and must be redesigned.
   - The KVO observer MUST be unregistered before any
     view release in the destroy lambda. AppKit logs a
     warning (and may crash in some configurations) if
     an observed object is released while observers are
     still registered.
   - The synchronous return shape of `webview_embed_create`
     is preserved: it returns the C++ `Engine*` as
     `jlong`, or 0 on synchronous prologue failure. Java
     callers continue to throw `IllegalStateException`
     on a zero return.
   - The Windows and Linux native paths are NOT modified
     by this Operation, except for the immediate-fire
     attach-completion callback in step 6.

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
- The editing-shortcut `KeyEventDispatcher` MUST return
  `true` for every event it forwards to
  `EmbeddedWebView.executeEditingCommand`, so AWT does not
  also deliver the same event to the focus owner. Returning
  `false` after forwarding would let Swing's default
  `Ctrl+C`/`Ctrl+V` action (e.g. on a focused `JTextField`
  sibling — but the dispatcher only acts when the focus owner
  is inside the WebView, so this is more about consuming
  AWT's own native-shortcut consumption path) run a second
  time.
- The editing-shortcut dispatcher MUST detect the platform
  shortcut modifier via
  `Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()` and
  compare it against `KeyEvent.getModifiers()`. The Ex
  variants (`getMenuShortcutKeyMaskEx` /
  `getModifiersEx`) are Java 10+ only; the project's Maven
  source/target is Java 8 (see `pom.xml` properties), so the
  legacy modifier API is the correct choice. Hardcoding
  `InputEvent.CTRL_MASK` or `InputEvent.META_MASK` is wrong
  — it would break the other platform.
- The editing-shortcut dispatcher MUST be registered in
  `addNotify()` and unregistered in `removeNotify()`. A
  dispatcher left registered across a removeNotify would
  keep the WebViewHeavyweightComponent reachable from the
  focus manager's strong reference and prevent both Java GC
  and native peer release.
- `WebViewNative.webview_embed_execute_editing_command` is
  `void` and MUST NOT raise a JNI exception. All failure
  paths (bad `cmdId`, null engine, native call failure) MUST
  fall through silently or log via the existing
  `WV_LOG`/`fprintf(stderr, ...)` pattern. Java callers must
  not need to wrap the call in try/catch.
- The native click hook (`WebViewClickCallback` registered via
  `EmbeddedWebView.setClickCallback`) is the canonical
  mechanism for surfacing user mouse input from inside the
  heavyweight WebView back to Swing for purposes that AWT's
  event queue would normally handle — Swing popup dismissal
  today; future needs (focus negotiation, analytics, custom
  drag-source detection) should reuse the same callback rather
  than adding new native hooks per use case. Keep the Java
  handler narrowly scoped so the call cost is bounded for
  every WebView click.
- macOS sync EDT→AppKit-main bridge in
  `src_c/webview_embed.cpp` is PROHIBITED. No code path
  in the macOS portion of `webview_embed.cpp` may block the
  EDT waiting for the AppKit main thread — not via
  `dispatch_sync(dispatch_get_main_queue(), …)`, not via
  `-[NSObject performSelectorOnMainThread:withObject:
  waitUntilDone:YES modes:]`, not via any custom semaphore /
  condition-variable rendezvous, and not via any new
  helper that has those semantics. The previous Norm (which
  mandated `performSelectorOnMainThread:modes:` with
  `AWTRunLoopMode`) is retired by Operation 14 because it
  only closes one ordering of the deadlock cycle: it does
  NOT prevent the case where our own main-thread block has
  already begun executing and its body (e.g.
  `[host addSubview:e->webview]`) synchronously rendezvous
  with the EDT through AppKit accessibility / IME /
  `LWCToolkit.invokeAndWait`, with the EDT still parked on
  the original `performSelector` semaphore. The structural
  fix is to eliminate every sync EDT→main hop. Specifically:
  - Every per-engine native operation MUST run via
    `cocoa_run_on_main_async` (or inline when already on
    main), and `cocoa_run_on_main_async` MUST remain
    asynchronous (`dispatch_async_f` to
    `dispatch_get_main_queue()`); queued blocks sitting in
    the main queue while `doAWTRunLoopImpl` runs are
    latency, not deadlock.
  - Any AppKit-main-thread state that the EDT needs to
    read MUST be mirrored into an `std::atomic` on the
    `Engine` struct, written from the main thread (e.g. by
    a KVO observer callback, a swizzled responder hook,
    or the destroy lambda) and read lock-free from the
    EDT. The cached first-responder flag
    (`is_first_responder`) is the canonical example.
  - Pre-attach op ordering is provided by
    `dispatch_get_main_queue()`'s documented FIFO serial
    semantics. Any change that uses a different queue,
    introduces a concurrent dispatch queue, or otherwise
    breaks the FIFO-after-attach invariant is forbidden
    by this Norm because it would force the
    re-introduction of an explicit pre-attach buffer (or
    worse, a sync bridge).
  - The synchronous helper `cocoa_run_on_main` and the
    `WebViewAwtMainBridge` / `performWork:` /
    `ensure_awt_main_bridge` scaffolding MUST NOT be
    reintroduced. They were removed by Operation 14 step
    7; any code-review observation that they are
    "missing" should be redirected to the documented
    async pattern.
  Rationale and history: the v1.0.5 fix from PR #30
  (commit `dcda4cf`) introduced the
  `performSelectorOnMainThread:modes:` mechanism to close
  one specific ordering. The demo at
  `demos/WebViewDeadlockRepro` reproduces the inverse
  ordering it does NOT close, where the EDT enters
  `cocoa_run_on_main` for `cocoa_is_first_responder` while
  the AppKit main thread is parked in `invokeAndWait`
  waiting for the EDT to drain a JS callback. The
  user-story decomposition in
  `requirements/[User-story-4]eliminate-edt-appkit-sync-deadlock-on-macos.md`
  and the analysis in
  `spdd/analysis/GGQPA-XXX-202605201900-[Analysis]-edt-appkit-sync-deadlock-elimination.md`
  document the full reasoning.

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
- The heavyweight peer must never paint while its Swing region
  is not showing. Because the `HierarchyListener` reacts only to
  `SHOWING_CHANGED` *transitions* (and drops any transition that
  fires before `embedded` is assigned), transition tracking
  alone does not cover the create-while-hidden case. `createPeer`
  therefore seeds the peer's visibility from `isShowing()` as its
  final step so a peer attached inside a not-showing region
  (not-selected `JTabbedPane` tab, hidden ancestor) starts hidden
  rather than defaulting to visible. This matters most on macOS,
  where the asynchronous native attach can complete after the
  region has been deselected; the seeded `setVisible(false)` is
  FIFO-ordered after the attach epilogue and hides the peer
  without requiring the user to toggle the tab to force a
  `SHOWING_CHANGED`.
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
- The editing-shortcut `KeyEventDispatcher` MUST short-circuit
  to `false` when `embedded == null` so a key event that
  arrives during teardown (between `dispose()` clearing the
  field and `removeNotify` unregistering the dispatcher) does
  not call JNI on a disposed peer. The dispatcher MUST also
  short-circuit when the AWT focus owner is `null` or not a
  descendant of the component — keystrokes typed into a
  sibling `JTextField` or another window MUST continue to use
  Swing's default handling untouched.
- `EmbeddedWebView.executeEditingCommand` rejects a `null`
  `EditingCommand` argument with `NullPointerException` BEFORE
  calling `checkAlive`, so callers get a clear error rather
  than the JNI layer mishandling an undefined `cmdId`. The
  exception message MUST name the parameter (`"cmd"`).
- `EmbeddedWebView.setClickCallback(null)` clears the JNI
  global ref and deletes it cleanly. `EmbeddedWebView.dispose()`
  calls `setClickCallback(null)` BEFORE `webview_embed_destroy`
  (symmetric with the existing `setFocusCallback(null)` clear)
  so a late native click event in flight during teardown lands
  on a `nullptr` callback field and silently no-ops instead of
  invoking JNI on a freed global ref.
- The Java click handler in `WebViewHeavyweightComponent`
  (`handleNativeClick`) MUST call
  `MenuSelectionManager.defaultManager().clearSelectedPath()`
  and nothing else. Touching focus, the embedded peer, or any
  other Swing state from this handler risks re-introducing the
  focus tangles documented in Operations 11 and 12, and the
  handler runs on every WebView click so any extra work pays
  per-click cost.
- The `__webview_eval_result__` resolver binding callback
  constructed in `EmbeddedWebView.attach` MUST be anchored in
  the same `heap` list as every other native callback this
  canvas registers. Forgetting `heap.add(fn)` lets the JVM
  dereference a freed Java ref on the next eval result —
  exactly the same failure mode as for console-channel and
  mouse-channel callbacks, with the same fix.
- The per-peer `EvalDispatcher` lifecycle is tied to
  `EmbeddedWebView`: constructed in `attach`, drained on
  `dispose`. The dispatcher's `disposed` flag flips inside
  `disposeAllPending()` BEFORE `EmbeddedWebView.dispose` zeroes
  `peer` and clears `heap`, so any pending JS callback firing
  during destroy finds either an empty `pending` map (drained)
  or a still-alive resolver callback that silently drops —
  never a SIGSEGV. Inverting the order (clearing heap before
  draining) would let a late callback run on a dead future
  reference; the existing dispose contract enforces drain-first.
- The heavyweight `evalAsync` completes futures on the EDT (per
  [[webview-async-javascript-eval]] `marshalToEdt = true`).
  Callers may continue with Swing-touching code directly from
  `.thenAccept(...)` / `.thenApply(...)` / `.exceptionally(...)`
  / `.handle(...)` without an additional
  `SwingUtilities.invokeLater`. This is the symmetric
  counterpart of the standalone `WebView` surface's
  `marshalToEdt = false` branch documented in
  [[in-process-webview-java-api]]; the asymmetry is
  intentional because the standalone surface has no Swing in
  the picture.
- `WebViewAttachListener` callbacks MUST fire on the Swing
  EDT and MUST NOT fire inline on the registering thread,
  even when attach has already resolved at registration time.
  `addOnAttachComplete` on a `PENDING` engine appends to
  `attachListeners` and returns; the resolve dispatch fires
  every listener via `SwingUtilities.invokeLater` in
  registration order. `addOnAttachComplete` on an `ATTACHED`
  or `FAILED` engine schedules `SwingUtilities.invokeLater`
  with the appropriate callback; inline firing on the
  calling thread would let listener bodies that register
  further listeners re-enter the dispatch code path with
  half-initialised state and is a source of subtle bugs.
- `EmbeddedWebView` state transitions (`PENDING → ATTACHED`,
  `PENDING → FAILED`) are EDT-only. The macOS native
  attach-completion callback marshals to the EDT before
  flipping `attachState`, capturing `attachFailure` (on
  failure), and firing listeners. State reads
  (`getAttachState`) are also documented EDT-only; this
  removes the need to volatile-qualify `attachState` and
  matches the existing Swing-thread-confinement convention
  used throughout this canvas.
- Pre-attach method calls on a `PENDING` engine MUST NOT
  throw. `setUrl`, `addOnBeforeLoad`, `addJavascriptCallback`,
  `eval`, `setBounds`, `setVisible`, `requestFocus`,
  `executeEditingCommand` all enqueue native async-on-main
  blocks; each block null-checks `e->webview` / `e->manager`
  at fire time and silently no-ops if cleared (the existing
  destroy-side race pattern). The `EmbeddedWebView.checkAlive()`
  guard continues to check `peer == 0L`, which is non-zero
  during `PENDING` (the C++ struct allocation is synchronous);
  callers therefore see no behavioural difference from the
  pre-async-attach contract.
- The KVO observer for `NSWindow.firstResponder` MUST be
  registered lazily on the first non-nil window (the
  WKWebView's `window` property may be transiently nil at
  `addSubview:` time per AppKit policy) and MUST be
  unregistered from its current `observed_window` before any
  view release in the destroy lambda. AppKit logs a warning
  (and may crash in some configurations) if an observed
  object is released while observers are still registered.
  The observer MUST also be moved (unregistered from the old
  window, registered against the new window) if the
  WKWebView's `window` property changes at runtime; the atomic
  cache MUST be recomputed immediately after the move.
- The `Engine.destroyed: std::atomic<bool>` MUST be set to
  `true` as the FIRST action inside the destroy lambda,
  before any AppKit teardown runs. Every other
  `cocoa_run_on_main_async` lambda (navigate / eval /
  init_script / set_bounds / set_visible / request_focus /
  execute_editing_command / bind) MUST read `destroyed` at
  the top of its body and short-circuit cleanly (no JNI
  exception, no callback into Java, no AppKit call) if `true`.
  The primary correctness guarantee is dispatch-queue FIFO
  ordering plus EDT-only enqueueing; the flag is
  belt-and-suspenders against (a) destroy-from-non-EDT,
  (b) future code changes that violate the
  EDT-only-enqueue invariant, and (c) the `cocoa_eval`
  late-completion path that needs a clean signalling channel.
- `EmbeddedWebView.dispose()` ordering (Java-side) MUST be
  preserved under async destroy: drain the `EvalDispatcher`
  first, clear focus / click callbacks (try/catch),
  set `peer = 0L`, then call `webview_embed_destroy`. The
  drain runs on the EDT and completes every pending
  `evalAsync` future exceptionally before the native destroy
  enqueues its lambda; the `peer = 0L` flip ensures
  subsequent `EmbeddedWebView.evalAsync` calls return an
  already-failed future without touching the dispatcher.
  Native-side late `cocoa_eval` short-circuits are therefore
  exercised only in pathological orderings; they remain
  the correct defensive design.
- `EmbeddedWebView.attach(...)` synchronous-throw contract is
  preserved for C++ allocation failure. If the C++ `Engine`
  struct allocation itself fails (out-of-memory, JAWT lock
  failure, or any synchronous-prologue error),
  `webview_embed_create` returns `0` and the Java factory
  throws `IllegalStateException` exactly as today — no
  partial `EmbeddedWebView` instance is observable. Only
  AppKit-side failures (which by definition only occur on
  macOS in the async epilogue) route through the
  `WebViewAttachListener.onAttachFailed` callback. Callers
  who use `attach(...)` without registering a listener
  MUST be able to discover async failure via
  `getAttachState() == FAILED`; documenting this avoids
  the trap where AppKit-side failures are silently swallowed.
- The C++ `Engine` struct MUST NOT leak on async-attach
  failure. If the async epilogue fails after `new Engine()`
  succeeded (e.g. WKWebView allocation returned nil, no
  hostable NSView discovered), the failure path MUST
  release any AppKit objects already allocated, drop the
  entry from `g_webview_map` if it was inserted, post the
  failure outcome to the EDT, and finally `delete e`. This
  is symmetric with the synchronous `!ok → delete e` path
  the previous design used; the new path just runs in the
  async lambda instead of inline.
- `cocoa_bind`'s async conversion MUST preserve the
  registration-order semantics relied upon by
  `WebViewHeavyweightComponent.createPeer` (which iterates
  `pendingBindings` BEFORE calling `navigate(pendingUrl)`).
  The main dispatch queue's FIFO serial ordering provides
  this automatically; any change that breaks FIFO order
  (e.g. routing bind through a different queue) would
  re-introduce a race between binding registration and
  page-load JS calls that the existing canvas code already
  documents around.
