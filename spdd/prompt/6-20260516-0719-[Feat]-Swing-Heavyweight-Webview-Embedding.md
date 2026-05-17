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
- Visual focus cooperation (macOS only): when the user shifts
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
  - New method `executeEditingCommand(EditingCommand cmd): EmbeddedWebView`
    — `checkAlive`, then call
    `WebViewNative.webview_embed_execute_editing_command(peer, cmd.nativeId)`.
    Pure side-effect; does not touch `heap` or `bindings`.
    Native side is responsible for marshaling to the correct
    UI thread (AppKit main / GTK main / WebView2 worker).
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
  `respondsToSelector` when `debug=true`. Both native sources
  also gain a function exported as
  `Java_..._webview_1embed_1execute_1editing_1command` that
  marshals to the correct UI thread (AppKit main / GTK main /
  WebView2 worker) and invokes the per-platform editing-command
  primitive (`[NSApp sendAction:to:nil from:webview]` /
  `webkit_web_view_execute_editing_command` /
  `webview->ExecuteScript("document.execCommand(...)")`).
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
     If it is a `javax.swing.text.JTextComponent`, query the
     native first-responder state via
     `embedded.isNativeFirstResponder()`. If the WebView IS
     the native first responder, override the Swing deferral
     and continue with the WebView dispatch — the user has
     clicked into the WebView and is interacting with it
     even though AWT's focus owner stayed on the Swing
     text widget. Only if the WebView is NOT the native
     first responder do we return `false` to defer to
     Swing's own Cut/Copy/Paste bindings on the focused
     text widget (preserving sibling `JTextField` behaviour
     when the user has genuinely clicked back on it).
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
5. Native swizzle (`src_c/webview_embed.cpp`, Cocoa branch):
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
   resigns) is handled implicitly:
   - User clicks `JTextField` → AWT moves focus to
     `JTextField` → AWT calls AppKit
     `makeFirstResponder:` on its NSView → WKWebView
     resigns first responder → swizzled
     `resignFirstResponder` fires → callback with
     `became = false` → `handleNativeFocusChange(false)` →
     caret restored on `suppressedCaretOwner`.
9. Constraints / Invariants:
   - The swizzle MUST be installed exactly once per JVM —
     guard with `std::call_once`. `method_setImplementation`
     is destructive on re-application and would corrupt
     the original-IMP save.
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
