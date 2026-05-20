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
       native first-responder status.
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
- macOS sync main-thread dispatch (`embed::cocoa_run_on_main` in
  `src_c/webview_embed.cpp`) MUST NOT use
  `dispatch_sync(dispatch_get_main_queue(), …)` from the EDT.
  AppKit's main thread routinely parks inside
  `-[LWCToolkit doAWTRunLoopImpl]`'s private CFRunLoop while it
  waits for the EDT to answer a JNI callback (the AX
  `accessibilityFocusedUIElement` query during a VoiceOver
  focus-changed event is the canonical trigger, but any
  AppKit→Java upcall through `doAWTRunLoopImpl` qualifies —
  IME callbacks, AWT-EDT round-trips). That private runloop
  runs only in `@"AWTRunLoopMode"` and does NOT drain the main
  dispatch queue; combined with the EDT blocking on
  `dispatch_sync` waiting for the main thread, this is an
  unconditional EDT-↔-main deadlock. The fix — required for
  every sync main-thread bridge in this codebase — is to use
  `-[NSObject performSelectorOnMainThread:withObject:
  waitUntilDone:YES modes:]` with a mode array that includes
  `@"AWTRunLoopMode"` alongside the normal AppKit modes
  (`kCFRunLoopDefaultMode`, `NSEventTrackingRunLoopMode`,
  `NSModalPanelRunLoopMode`). This matches the JDK's own
  `sun.lwawt.macosx.ThreadUtilities.javaModes` set and is the
  canonical "talk to AppKit from a JNI call on the EDT"
  pattern. The wrapper class
  (`WebViewAwtMainBridge` / `performWork:`) is registered once
  per JVM via `std::call_once`, symmetric with the existing
  swizzle / `WebviewEmbedDelegate` once-only registrations.
  See the bug report "WebViewHeavyweightComponent deadlocks the
  EDT when the WebView peer is created during macOS
  accessibility focus events" for the original trace. The
  async helper `cocoa_run_on_main_async` is unaffected — it
  uses `dispatch_async_f` and never blocks the EDT, so a queued
  block sitting in the main queue while
  `doAWTRunLoopImpl` runs is latency, not deadlock.

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
