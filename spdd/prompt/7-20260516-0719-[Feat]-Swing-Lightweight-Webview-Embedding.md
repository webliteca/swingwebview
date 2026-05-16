---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: Swing Lightweight WebView Embedding

## R · Requirements
- Embed a native WebView as a **regular lightweight Swing
  component**: the native engine renders into an offscreen
  surface, Java pulls pixels each frame and paints them via
  `paintComponent`. This composites cleanly with arbitrary Swing
  widgets (popups, Z-order, JLayer, JTabbedPane) without the
  heavyweight-peer Z-order trade-off
  (`WebViewLightweightComponent.java:49`,
  `OffscreenWebView.java:24`).
- Forward AWT mouse and keyboard events into the native engine
  so clicks, drags, smooth-scroll, typing, edit keys (Backspace,
  Delete, arrows, Home/End, function keys, modifiers) all work
  (`WebViewLightweightComponent.java:75`,
  `WebViewLightweightComponent.java:125`).
- Repaint pixel surface at ~30Hz to feel responsive
  (`WebViewLightweightComponent.java:51`, `REPAINT_INTERVAL_MS =
  33`).
- Platform support (`README.md ("Platform support" section)`):
  - **Linux** — full: rendering, mouse (click, drag, scroll,
    hover), keyboard (typing, Backspace, Delete, arrows,
    function keys, modifiers).
  - **macOS, Windows** — stubs: native entry points return 0
    from create, so the component silently fails to attach and
    shows its empty Swing background.
- Known limitations documented at `README.md ("Lightweight notes" section)`:
  - No IME / CJK composition — WebKit's IM context is disabled
    because input arrives already-decoded from AWT
    (`WebViewLightweightComponent.java:41`).
  - Right-click context menus and `<select>` dropdowns from
    inside the page log a GDK warning and don't visibly appear
    (`README.md ("Lightweight notes" section)`).
- The lightweight subclass implements the full JS-interaction
  surface defined in
  [[swing-webview-component-mode-selection]]: `eval`,
  `addJavascriptCallback`, `addOnBeforeLoad`, and
  `dispatch(Runnable)`.  These delegate through the offscreen
  GTK engine and share the same `window.<name>(...)` contract
  as the heavyweight path; the earlier "Phase 1: not yet
  wired" stubs are gone.  On macOS / Windows the offscreen
  engine itself returns null from create, so the methods
  silently no-op alongside the rendering path.
- Implement the developer-visibility surface declared in
  [[swing-webview-component-mode-selection]]:
  - `openDevTools(): boolean` — when `debug=true` was set
    before display, opens the WebKitGTK Web Inspector for the
    offscreen WebView in a separate OS window and returns
    `true`.  Backed by the new JNI entry point
    `webview_offscreen_open_devtools`.
  - Console capture — install the canonical JS shim from
    `ConsoleDispatcher.SHIM_JS` via
    `OffscreenWebView.addOnBeforeLoad`, bind
    `__webview_console__` via
    `OffscreenWebView.addJavascriptCallback` to a callback
    that routes the raw payload into
    `ConsoleDispatcher.dispatch`.  Both happen inside
    `addNotify()` immediately after engine creation.
- Definition of Done: documented at `README.md ("Quick start" section)`,
  exercised by the `WebViewHeavyweightDemo` toggle (which
  also shows a lightweight component side-by-side) and the
  `run-linux-demo.sh` script. No automated tests.

## E · Entities
- **WebViewLightweightComponent** (extends `WebViewComponent`,
  `WebViewLightweightComponent.java:49`). Invariants:
  - Opaque, focusable, white background
    (`WebViewLightweightComponent.java:62`).
  - `engine: OffscreenWebView` is null until `addNotify` and
    after `removeNotify`/`dispose`
    (`WebViewLightweightComponent.java:53`).
  - `buffer: BufferedImage` is sized to the current component
    width/height; `pixelArray` is a direct view onto its
    backing `DataBufferInt`
    (`WebViewLightweightComponent.java:200`).
  - `repaintTimer` runs a Swing `Timer` at
    `REPAINT_INTERVAL_MS` (≈30 FPS) calling
    `repaint()` (`WebViewLightweightComponent.java:169`).
  - Owns one `ConsoleDispatcher` instance for the lifetime of
    the component (created in the constructor). Listeners
    may be registered against it before display; the JS shim
    is installed when `addNotify()` creates the engine so
    messages start flowing automatically.
  - `pendingInit: List<String>` and
    `pendingBindings: LinkedHashMap<String, JavascriptCallback>`
    buffer user-supplied init scripts and JS callbacks while
    `engine == null`; replayed on engine creation in the
    same pattern as the heavyweight component.
  - Overrides `openDevTools()` to delegate to
    `OffscreenWebView.openDevTools()`; returns `false` when
    `engine == null`.
  - Overrides `addJavascriptCallback(name, cb)` to reject any
    name starting with `__webview_` (matches the canvas-5
    reserved-prefix norm).
- **OffscreenWebView** (`OffscreenWebView.java:24`) — Low-level
  JNI wrapper for the offscreen engine.  Owns:
  - `peer: long` — native pointer; `0` means unsupported
    platform or disposed (`OffscreenWebView.java:26`,
    `OffscreenWebView.java:38`).
  - `heap: List<Object>` — anchors JNI callbacks
    (`WebViewNativeCallback`s, dispatch wrappers) against GC
    while the native side still holds a function pointer.
    Same invariant as the heavyweight `EmbeddedWebView.heap`
    (`EmbeddedWebView.java:33`).
  - `bindings: Map<String, WebView.JavascriptCallback>` — name
    → Java callback for every bound `window.<name>` shim.
    Mirrors `EmbeddedWebView.bindings`
    (`EmbeddedWebView.java:34`).
  Invariants:
  - `addOnBeforeLoad(js)`, `eval(js)`,
    `addJavascriptCallback(name, cb)`,
    `dispatch(Runnable r)`, and `openDevTools(): boolean`
    are backed by JNI entry points
    `webview_offscreen_init`, `webview_offscreen_eval`,
    `webview_offscreen_bind`, `webview_offscreen_dispatch`,
    and `webview_offscreen_open_devtools` respectively.
  - All five go through `checkAlive` before invoking JNI.
- **WebViewLightweightComponent buffered configuration** —
  alongside the existing `pendingUrl` and `debug` fields the
  subclass holds `pendingInit: List<String>` and
  `pendingBindings: Map<String, WebView.JavascriptCallback>`
  so callers can register init scripts and JS callbacks BEFORE
  `addNotify` creates the engine; both are replayed in
  `addNotify` after `OffscreenWebView.create` returns non-null.
  This satisfies the buffer/replay contract from
  [[swing-webview-component-mode-selection]].
- **GdkInput** (`GdkInput.java:17`) — translation table between
  AWT input constants and GDK constants. Pure functions; no
  state.

## A · Approach
- **Pixel pump, no embed.** Unlike the heavyweight path
  ([[swing-heavyweight-webview-embedding]]), no native window
  is reparented into the Swing hierarchy. WebKit renders into a
  `GtkOffscreenWindow` on the GTK side, this component
  periodically snapshots the resulting cairo surface into a
  `BufferedImage`, and Swing composites the image normally
  (`README.md ("Lightweight notes" section)`).
- **AWT → GDK translation.** Mouse and key events are
  translated in pure Java (`GdkInput`) into GDK button numbers,
  GDK modifier masks, and GDK keysyms; the native side feeds
  these directly into `gtk_main_do_event`. Latin-1 printable
  characters use the char value as the keysym since
  `GDK_KEY_a == 'a' == 0x61`
  (`GdkInput.java:70`).
- **AWT-owned focus.** The component is focusable and
  `requestFocusInWindow()`s on mouse press so subsequent
  keyboard events flow to the lightweight component
  (`WebViewLightweightComponent.java:79`). Tab and Shift-Tab
  are NOT used for Swing focus traversal — they pass through
  to WebKit so the user can tab through form fields
  (`WebViewLightweightComponent.java:127`).
- **Bypass WebKit IM context.** WebKit's input method context
  is disabled native-side because AWT already decodes
  characters before they reach the component. The cost is no
  CJK / IME composition support (`README.md ("Lightweight notes" section)`).
- **Trade-off accepted: per-frame copy.** Each frame the
  native side memcpy's cairo pixels into the Java int array
  via `webview_offscreen_snapshot`. Higher per-frame cost
  than heavyweight compositing, but composites cleanly with
  Swing.
- **JS bridge — reused from the embed engine.** The offscreen
  engine reuses the same `embed::gtk_eval`,
  `embed::gtk_init_script`, `embed::gtk_bind`, and
  `embed::GtkPump::run_async` helpers (and parallel offscreen
  equivalents) that drive the heavyweight engine on Linux.
  The `window.<name>(...)` JS shim and the
  `{name, seq, args}` round-trip envelope from
  `webview_embed.cpp:1791`–`webview_embed.cpp:1801` are
  byte-identical so page authors see the same contract in
  both modes.  The `OffEngine` struct already carries
  `bindings`, `manager`, and `jvm` fields
  (`webview_embed.cpp:887`) — the new JNI entries plug into
  those.
- **DevTools call is single-shot.** No state added on the
  Java side.  The native `webview_offscreen_open_devtools`
  returns 0 if `enable_developer_extras` is FALSE on the
  engine's `WebKitSettings`; otherwise calls
  `webkit_web_inspector_show` on
  `webkit_web_view_get_inspector(...)` and returns 1.  Same
  null-guard + dispatch-to-GTK-pump-thread pattern as the
  embed path's `webview_embed_open_devtools`.
- **Console bridge install is per engine-creation, not per
  navigation.**  Same rationale as the heavyweight path: the
  underlying `webkit_user_script_new` /
  `register_script_message_handler` machinery re-fires for
  every new document at document-start.

## S · Structure
- `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`
  — Swing wrapper, event translation, repaint loop.
- `src/ca/weblite/webview/OffscreenWebView.java` — JNI wrapper
  for the offscreen engine.
- `src/ca/weblite/webview/GdkInput.java` — AWT-to-GDK
  translation tables.
- `src/ca/weblite/webview/WebViewNative.java` — native entry
  points for the offscreen engine
  (`webview_offscreen_create`, `webview_offscreen_snapshot`,
  `webview_offscreen_mouse_*`, `webview_offscreen_key_event`,
  `webview_offscreen_init`, `webview_offscreen_eval`,
  `webview_offscreen_bind`, `webview_offscreen_dispatch`,
  `webview_offscreen_open_devtools`).
- `src/ca/weblite/webview/ConsoleDispatcher.java` (from
  [[swing-webview-component-mode-selection]]) — owned by the
  component; receives raw payloads from the internal
  `__webview_console__` binding callback registered in
  `addNotify`.
- `src_c/webview_embed.cpp` — native implementation (shared
  with embed path on Linux).  Stubs on macOS/Windows.  New
  implementations of the five offscreen JNI methods listed
  above, in the offscreen-engine block around line 880-ff.
- `src_c/ca_weblite_webview_WebViewNative.h` — generated JNI
  header; declares the five new offscreen entries alongside
  the existing ones.

## O · Operations

### 1. Translate Input — GdkInput
File: `src/ca/weblite/webview/GdkInput.java`

1. Responsibility: convert AWT modifier bitmasks, button
   numbers, and `KeyEvent` codes/chars into GDK equivalents.
2. Fields:
   - `GDK_SHIFT_MASK = 1<<0`, `GDK_LOCK_MASK = 1<<1`,
     `GDK_CONTROL_MASK = 1<<2`, `GDK_MOD1_MASK = 1<<3`,
     `GDK_MOD2_MASK = 1<<4`, `GDK_BUTTON1_MASK = 1<<8`,
     `GDK_BUTTON2_MASK = 1<<9`, `GDK_BUTTON3_MASK = 1<<10`,
     `GDK_SUPER_MASK = 1<<26`, `GDK_META_MASK = 1<<28`
     (`GdkInput.java:22`).
3. Methods:
   - `translateModifiers(int awtMods): int`
     - Logic: OR-in each GDK bit for the corresponding
       `InputEvent.*_DOWN_MASK` and `BUTTON*_DOWN_MASK`
       (`GdkInput.java:34`).
   - `translateButton(int awtButton): int`
     - Logic: BUTTON1→1, BUTTON2→2, BUTTON3→3; otherwise pass
       through (`GdkInput.java:47`).
   - `translateKeyCode(int vkCode, char keyChar): int`
     - Logic: look up `vkToGdkKeysym(vkCode)` first
       (`GdkInput.java:68`); if zero, return `keyChar` when it
       is a valid BMP printable char; else return 0
       (`GdkInput.java:70`).
   - `isModifierKey(int vkCode): boolean`
     - Logic: true for SHIFT/CONTROL/ALT/ALT_GRAPH/META/
       CAPS_LOCK/NUM_LOCK/SCROLL_LOCK
       (`GdkInput.java:83`).
   - `vkToGdkKeysym(int vk): int` — private switch table over
     `KeyEvent.VK_*` returning GDK keysym hex values from
     `gdkkeysyms.h` (`GdkInput.java:98`–`GdkInput.java:144`).
4. Constraints / Invariants:
   - The class is `final` with a private constructor — pure
     static utility (`GdkInput.java:17`).
   - The keysym values come from
     `gdk/gdkkeysyms.h`; do not invent new values without a
     reference to that header.

### 2. Offscreen Native Wrapper — OffscreenWebView
File: `src/ca/weblite/webview/OffscreenWebView.java`

1. Responsibility: typed wrapper around the offscreen native
   engine; create, resize, navigate, snapshot, inject input,
   evaluate JS, register JS callbacks, dispatch work onto the
   native thread, dispose.
2. Fields:
   - `peer: long` — native pointer; 0 means disposed/unsupported.
   - `heap: List<Object>` — anchors JNI callbacks and dispatch
     wrappers; same anti-GC pattern as
     `EmbeddedWebView.heap`.
   - `bindings: Map<String, WebView.JavascriptCallback>` —
     name-keyed Java callbacks for every bound JS function;
     same shape as `EmbeddedWebView.bindings`.
3. Methods:
   - `create(int width, int height, boolean debug): OffscreenWebView`
     - Logic: call
       `webview_offscreen_create(max(1,w), max(1,h), debug?1:0)`;
       return `null` if the native side returns 0 (unsupported
       platform).
   - `setSize(int w, int h): OffscreenWebView` —
     `checkAlive`, call
     `webview_offscreen_resize(peer, max(1,w), max(1,h))`.
   - `navigate(String url)` — `webview_offscreen_navigate`.
   - `snapshot(int[] pixels, int w, int h)` — copy pixel
     buffer; caller-owned int[] in 0xAARRGGBB layout.
   - `mouseButton(boolean press, int x, int y, int button, int modifiers, int clickCount)`
     — call `webview_offscreen_mouse_button`.
   - `mouseMotion(int x, int y, int modifiers)`.
   - `mouseScroll(int x, int y, double dx, double dy, int modifiers)`
     — smooth-scroll deltas.
   - `keyEvent(boolean press, int keyval, int modifiers, boolean isModifierKey)`
     — `webview_offscreen_key_event`.
   - `addOnBeforeLoad(String js): OffscreenWebView` —
     `checkAlive`, then
     `WebViewNative.webview_offscreen_init(peer, js)`. Mirrors
     `EmbeddedWebView.addOnBeforeLoad` semantics.
   - `eval(String js): OffscreenWebView` — `checkAlive`, then
     `WebViewNative.webview_offscreen_eval(peer, js)`. Mirrors
     `EmbeddedWebView.eval`.
   - `addJavascriptCallback(String name, WebView.JavascriptCallback cb): OffscreenWebView`
     — `checkAlive`; put `name → cb` into `bindings`;
     construct a `WebViewNativeCallback` whose `invoke(arg, wv)`
     looks up `bindings.get(name)` and calls `c.run(arg)`;
     anchor the callback in `heap`; call
     `WebViewNative.webview_offscreen_bind(peer, name, fn, peer)`.
     This is structurally identical to
     `EmbeddedWebView.addJavascriptCallback`
     (`EmbeddedWebView.java:139`).
   - `dispatch(Runnable r): OffscreenWebView` — `checkAlive`;
     wrap `r` in a self-removing `Runnable` that calls
     `r.run()` then removes itself from `heap`; anchor wrapper
     in `heap`; call
     `WebViewNative.webview_offscreen_dispatch(peer, wrapper)`.
     Mirrors `EmbeddedWebView.dispatch`
     (`EmbeddedWebView.java:160`).
   - `openDevTools(): boolean` — `checkAlive`; call
     `WebViewNative.webview_offscreen_open_devtools(peer)`;
     return `(result == 1)`.
   - `dispose(): void` — zero `peer` then call
     `webview_offscreen_destroy` if non-zero; clear `heap` and
     `bindings`.
4. Constraints / Invariants:
   - All operations except `create` and `dispose` go through
     `checkAlive` which throws after dispose.
   - Pixel layout is fixed at `0xAARRGGBB`, matching
     `BufferedImage.TYPE_INT_ARGB` — callers MUST allocate the
     buffer that way (`OffscreenWebView.java:64`).
   - The bind-shim JS that exposes `window.<name>(...)` is
     installed natively (alongside `embed::gtk_bind` on the
     heavyweight path) so the contract and envelope match the
     heavyweight path exactly (see Approach — JS bridge).
   - JNI callbacks MUST be anchored in `heap` for the
     lifetime of their native registration, or the JVM would
     collect them while the native engine still holds a
     function pointer.  Same invariant as
     `EmbeddedWebView.heap`.

### 3. Construct and Install Listeners — WebViewLightweightComponent ctor
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Responsibility: configure the component for opaque
   painting, install mouse and keyboard listeners, install a
   resize listener.
2. Methods:
   - `WebViewLightweightComponent()`
     - Logic: `setOpaque(true)`, `setBackground(Color.WHITE)`,
       `setFocusable(true)`
       (`WebViewLightweightComponent.java:62`). Install a
       `ComponentAdapter` that calls `resizeNative()` on
       resize. Call `installMouseListeners()` and
       `installKeyListener()`.
   - `installMouseListeners(): void`
     - Logic: add a `MouseAdapter` that on `mousePressed`
       calls `requestFocusInWindow()` and
       `engine.mouseButton(true, x, y, button, modifiers,
       clickCount)`; on `mouseReleased` same with `false`
       (`WebViewLightweightComponent.java:75`). Add a
       `MouseMotionAdapter` forwarding moves and drags to
       `engine.mouseMotion(x, y, modifiers)`
       (`WebViewLightweightComponent.java:94`). Add a
       `MouseWheelListener` translating
       `getPreciseWheelRotation()` to dx/dy with a 40-pixel
       step (Shift = horizontal) and calling
       `engine.mouseScroll(x, y, dx, dy, modifiers)`
       (`WebViewLightweightComponent.java:106`).
   - `installKeyListener(): void`
     - Logic: `setFocusTraversalKeysEnabled(false)` so Tab
       and Shift-Tab reach the page
       (`WebViewLightweightComponent.java:127`). Add a
       `KeyAdapter` whose `keyPressed`/`keyReleased` translate
       the AWT event via `GdkInput.translateKeyCode` /
       `translateModifiers` / `isModifierKey` and call
       `engine.keyEvent(press, keyval, modifiers, isModifier)`.
       Drop the event if keyval is 0
       (`WebViewLightweightComponent.java:131`).
3. Constraints / Invariants:
   - All listener handlers no-op if `engine == null`
     (`WebViewLightweightComponent.java:77`).
   - `setFocusTraversalKeysEnabled(false)` is intentional —
     reversing it breaks tabbing through web forms.

### 4. Attach Engine — addNotify
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Responsibility: create the offscreen engine when the
   component first becomes displayable, install the console
   bridge, replay buffered user configuration, allocate the
   pixel buffer, navigate to the pending URL, and start the
   repaint timer.
2. Methods:
   - `addNotify(): void`
     - Logic: super, then if `engine != null` return (idempotent).
       Compute `w/h` from current size, defaulting to 1.
       Call `OffscreenWebView.create(w, h, debug)`. If null
       (unsupported), leave `engine` null so every subsequent
       op is a no-op and `paintComponent` falls back to the
       Swing background (`WebViewLightweightComponent.java:160`).
       **Install the console bridge BEFORE replaying user
       config**: call
       `engine.addOnBeforeLoad(ConsoleDispatcher.SHIM_JS)`
       once, then
       `engine.addJavascriptCallback("__webview_console__",
       payload -> consoleDispatcher.dispatch(payload))`.  The
       reserved-name reject in the public
       `addJavascriptCallback` is bypassed here because this
       call goes directly through `engine`, not through
       `WebViewLightweightComponent.addJavascriptCallback`.
       Then replay `pendingInit` via `engine.addOnBeforeLoad`
       and `pendingBindings` via
       `engine.addJavascriptCallback`, both in registration
       order.  Allocate the buffer, navigate to `pendingUrl`,
       start a Swing `Timer` at `REPAINT_INTERVAL_MS`
       repeatedly calling `repaint()`
       (`WebViewLightweightComponent.java:167`).
3. Constraints / Invariants:
   - Failure to create the engine is silent — the component
     just shows an empty background. This is intentional so a
     macOS or Windows app that defaults to lightweight does
     not crash; instead the user gets a visible cue
     (empty white background) and the platform mode
     selection in
     [[swing-webview-component-mode-selection]] kicks them
     toward heavyweight.
   - Console listeners registered before `addNotify` are
     already stored in `consoleDispatcher`; the shim install
     above is what starts feeding them, so pre-display
     registration "just works" without an explicit replay
     step on the dispatcher itself.

### 5. Detach Engine — removeNotify
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Responsibility: stop the repaint timer, dispose the
   engine, free the buffer.
2. Methods:
   - `removeNotify(): void`
     - Logic: stop and null `repaintTimer`; capture and null
       `engine` then call `ow.dispose()`; null `buffer` and
       `pixelArray`; super
       (`WebViewLightweightComponent.java:175`).
3. Constraints / Invariants:
   - Engine field is nulled BEFORE `dispose()` so listeners
     firing between dispose and the next event correctly
     no-op via the `engine == null` guards.

### 6. Paint Pixels — paintComponent
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Responsibility: snapshot the current native pixels and
   draw them into Swing's `Graphics`.
2. Methods:
   - `paintComponent(Graphics g): void`
     - Logic: if engine/buffer/pixelArray missing, fall back
       to `super.paintComponent(g)`. Otherwise call
       `engine.snapshot(pixelArray, w, h)` then
       `g.drawImage(buffer, 0, 0, null)`
       (`WebViewLightweightComponent.java:191`).
3. Constraints / Invariants:
   - `pixelArray` is the direct backing array of `buffer`'s
     `DataBufferInt`, so the snapshot writes into the same
     memory `g.drawImage` reads from — no extra copy
     (`WebViewLightweightComponent.java:200`).

### 7. Resize Native — resizeNative
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Responsibility: when the Swing region changes size,
   reallocate the buffer and tell the engine to re-layout.
2. Methods:
   - `resizeNative(): void`
     - Logic: if `engine == null`, return. Compute `w/h` with
       min of 1. If buffer is wrong size, reallocate.
       `engine.setSize(w, h)`
       (`WebViewLightweightComponent.java:206`).

### 8. JS Interaction Surface — eval / addOnBeforeLoad / addJavascriptCallback / dispatch
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Responsibility: implement the JS-interaction subset of the
   `WebViewComponent` abstract contract — same buffer/replay
   semantics that the heavyweight subclass uses in
   [[swing-heavyweight-webview-embedding]].  The earlier
   "Phase 1: not yet wired" stubs are gone.
2. Methods:
   - `addOnBeforeLoad(String js): WebViewComponent`
     - Logic: append `js` to `pendingInit`.  If `engine != null`
       (component is displayable), also call
       `engine.addOnBeforeLoad(js)` so the script applies on
       the next navigation.  Return `this`.
   - `eval(String js): WebViewComponent`
     - Logic: if `engine == null`, no-op (matches the
       abstract contract's "no-op until displayable" — same
       as heavyweight `WebViewHeavyweightComponent.eval`).
       Otherwise call `engine.eval(js)`.  Return `this`.
   - `addJavascriptCallback(String name, WebView.JavascriptCallback cb): WebViewComponent`
     - Logic: reject reserved-prefix names (any
       `name.startsWith("__webview_")`) with
       `IllegalArgumentException` BEFORE mutating any state.
       Then put `name → cb` into `pendingBindings`.  If
       `engine != null`, also call
       `engine.addJavascriptCallback(name, cb)` so the binding
       is live on the current document.  Return `this`.
   - `dispatch(Runnable r): WebViewComponent`
     - Logic: if `engine == null`, no-op (the work has nowhere
       to run yet; `Runnable`s do not buffer because there is
       no defined replay moment for transient work).  Otherwise
       call `engine.dispatch(r)`.  Return `this`.
3. Constraints / Invariants:
   - Buffered configuration is replayed in `addNotify` after
     `OffscreenWebView.create` succeeds AND after the console
     bridge is installed — see Operation 4.  This guarantees
     the JS shim is the first init-script installed so it
     observes every user init-script's console output.
   - On macOS / Windows the create returns null and the
     buffered state stays buffered forever, which is the
     intentional graceful-degradation shape: callers see no
     exception, the component renders blank, and switching to
     heavyweight via the mode-selection property (see
     [[swing-webview-component-mode-selection]]) yields a
     fully working component without code changes.
   - `pendingInit` and `pendingBindings` are never reset by
     these setters — same convention as the heavyweight
     subclass so a re-attach scenario would replay them.
   - `dispatch` does not buffer because a `Runnable` posted
     before the engine exists has no well-defined moment to
     run; calling it before `addNotify` is a programmer
     error that the contract chooses to silently absorb.

### 9. Open Native DevTools — openDevTools
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`,
`src/ca/weblite/webview/OffscreenWebView.java`,
`src_c/webview_embed.cpp`.

1. Responsibility: override `WebViewComponent.openDevTools`
   to delegate to the offscreen engine; provide the JNI
   entry point and the native implementation.
2. Java implementation:
   - `WebViewLightweightComponent.openDevTools(): boolean` —
     if `engine == null` return `false`; otherwise return
     `engine.openDevTools()`.
   - `OffscreenWebView.openDevTools(): boolean` — see
     Operation 2 above.
   - `WebViewNative.webview_offscreen_open_devtools(long peer): int`
     — declared as `native static int`.
3. Native implementation
   (`src_c/webview_embed.cpp`, offscreen-engine block):
   - Look up the engine struct from the `long peer`.
   - Read `webkit_settings_get_enable_developer_extras` on
     the engine's `WebKitSettings`.  If FALSE, return 0.
   - Call `webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web))`.
     If NULL, return 0.
   - Call `webkit_web_inspector_show(inspector)` and return
     1.  Marshal to the GTK pump thread via the existing
     dispatch pattern if not already on it.
4. Constraints / Invariants:
   - The inspector appears as a normal GTK toplevel window
     belonging to the offscreen WebView's process — it does
     NOT participate in the offscreen rendering pipeline and
     is unaffected by the host Swing window's visibility.
     Closing the inspector window via its own close button
     and then calling `openDevTools()` again must re-open
     it; native behaviour is idempotent.

## N · Norms
- AWT event handlers always check `engine == null` and return
  early; do not assume the offscreen engine is alive
  (`WebViewLightweightComponent.java:78`).
- Repaint cadence is 30Hz (`REPAINT_INTERVAL_MS = 33`); if you
  add an explicit invalidate path, prefer it over raising the
  timer frequency to avoid extra CPU.
- Latin-1 printable characters intentionally use the char
  value directly as the GDK keysym
  (`GdkInput.java:70`). Do not special-case ASCII characters
  in `vkToGdkKeysym`.
- The console-bridge install in `addNotify()` MUST happen
  BEFORE replaying `pendingInit` and `pendingBindings`, for
  the same reason as the heavyweight path (Norms in
  [[swing-heavyweight-webview-embedding]]): the shim must
  observe every user init-script's console output, including
  scripts that run before the first navigation.
- The internal `__webview_console__` binding callback
  installed in `addNotify()` must call
  `consoleDispatcher.dispatch(payload)` directly — no other
  Java work on the native thread.
- `webview_offscreen_open_devtools` MUST NOT block the EDT
  indefinitely.  The native side dispatches to the GTK pump
  thread for the actual `webkit_web_inspector_show` call.
- The `window.<name>(...)` bind shim and the
  `{name, seq, args}` round-trip envelope are owned by the
  heavyweight engine code at
  `webview_embed.cpp:1791`–`webview_embed.cpp:1801` and reused
  verbatim by the offscreen path.  Do NOT fork the shim for
  the lightweight engine — page authors expect identical
  semantics across both modes, and the shim is the contract.

## S · Safeguards
- `OffscreenWebView.create` returns `null` for unsupported
  platforms or native failure rather than throwing
  (`OffscreenWebView.java:39`), so the Swing component can
  degrade gracefully (`README.md ("Platform support" section)`).
- `min(1, w/h)` on create and resize prevents zero/negative
  dimensions from reaching the native engine, which would
  trigger GTK assertions
  (`OffscreenWebView.java:38`,
  `WebViewLightweightComponent.java:158`).
- `engine == null` is checked in every event handler before
  injecting input — `paintComponent` also falls back to the
  default Swing background, so a failed attach renders as a
  blank white panel rather than crashing
  (`WebViewLightweightComponent.java:191`).
- `setFocusable(true)` plus `requestFocusInWindow()` on mouse
  press ensures the keyboard listener actually receives
  events (`WebViewLightweightComponent.java:63`,
  `WebViewLightweightComponent.java:79`).
- The pixel buffer is reallocated only when the size actually
  changes (`WebViewLightweightComponent.java:209`) to avoid
  per-frame allocation churn.
- `setDebug` throws `IllegalStateException` after display
  (`WebViewLightweightComponent.java:233`) for the same
  reason as the heavyweight component — the debug flag is
  baked into the native peer at creation time.
- `WebViewLightweightComponent.openDevTools()` returns
  `false` when `engine == null` (component not yet
  displayed, or engine creation failed on an unsupported
  platform) rather than throwing.
- `WebViewLightweightComponent.addJavascriptCallback` rejects
  reserved-prefix names (any name starting with
  `__webview_`) with `IllegalArgumentException` BEFORE
  touching `pendingBindings` or `engine`.
- The internal `__webview_console__` binding callback
  registered in `addNotify()` MUST be cleared on
  `removeNotify()` / `dispose()` via the existing
  `OffscreenWebView.dispose()` `heap.clear` / `bindings.clear`
  step.  A late native message arriving after dispose lands
  on a now-disposed engine and is silently dropped by the
  native side's own peer-validity check.
- The native `webview_offscreen_open_devtools` returns 0
  (never raises a JNI exception) for every failure path so
  the Java side never has to wrap the call in a try/catch.
- `eval`, `addJavascriptCallback`, `addOnBeforeLoad`, and
  `dispatch` are all safe to call on platforms where the
  offscreen engine never creates (macOS / Windows): the
  `engine == null` guard silently absorbs the call and the
  buffered configuration (where applicable) is held
  indefinitely.  This matches the rendering path's
  graceful-degradation invariant — a missing offscreen
  engine never throws, only renders blank.  Java side
  already routes the underlying JNI methods through
  `checkAlive`, so the stub-returns-0 invariant carries
  through; no additional per-method stubs are needed.
- JNI callbacks registered via `addJavascriptCallback` and
  wrappers passed to `dispatch` MUST live inside
  `OffscreenWebView.heap` for the duration of their native
  registration — the GC has no other reachability root.
