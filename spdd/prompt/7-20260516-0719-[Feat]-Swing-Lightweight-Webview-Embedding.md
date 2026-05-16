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
- The lightweight subclass currently does **not** wire init
  scripts, `eval`, or JS callbacks — phase 1 is rendering and
  input only (`WebViewLightweightComponent.java:244`).
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
- **OffscreenWebView** (`OffscreenWebView.java:24`) — Low-level
  JNI wrapper for the offscreen engine. Owns a `long peer`;
  `peer == 0` means unsupported platform or disposed
  (`OffscreenWebView.java:26`, `OffscreenWebView.java:38`).
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

## S · Structure
- `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`
  — Swing wrapper, event translation, repaint loop.
- `src/ca/weblite/webview/OffscreenWebView.java` — JNI wrapper
  for the offscreen engine.
- `src/ca/weblite/webview/GdkInput.java` — AWT-to-GDK
  translation tables.
- `src/ca/weblite/webview/WebViewNative.java:180`–
  `src/ca/weblite/webview/WebViewNative.java:242` — native
  entry points for the offscreen engine
  (`webview_offscreen_create`, `webview_offscreen_snapshot`,
  `webview_offscreen_mouse_*`, `webview_offscreen_key_event`).
- `src_c/webview_embed.cpp` — native implementation (shared
  with embed path on Linux). Stubs on macOS/Windows.

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
   dispose.
2. Fields:
   - `peer: long` — native pointer; 0 means disposed/unsupported
     (`OffscreenWebView.java:26`).
3. Methods:
   - `create(int width, int height, boolean debug): OffscreenWebView`
     - Logic: call
       `webview_offscreen_create(max(1,w), max(1,h), debug?1:0)`
       (`OffscreenWebView.java:37`); return `null` if the
       native side returns 0 (unsupported platform)
       (`OffscreenWebView.java:39`).
   - `setSize(int w, int h): OffscreenWebView` —
     `checkAlive`, call
     `webview_offscreen_resize(peer, max(1,w), max(1,h))`
     (`OffscreenWebView.java:49`).
   - `navigate(String url)` — `webview_offscreen_navigate`
     (`OffscreenWebView.java:57`).
   - `snapshot(int[] pixels, int w, int h)` — copy pixel
     buffer; caller-owned int[] in 0xAARRGGBB layout
     (`OffscreenWebView.java:68`).
   - `mouseButton(boolean press, int x, int y, int button, int modifiers, int clickCount)`
     — call `webview_offscreen_mouse_button`
     (`OffscreenWebView.java:77`).
   - `mouseMotion(int x, int y, int modifiers)`
     (`OffscreenWebView.java:85`).
   - `mouseScroll(int x, int y, double dx, double dy, int modifiers)`
     — smooth-scroll deltas (`OffscreenWebView.java:91`).
   - `keyEvent(boolean press, int keyval, int modifiers, boolean isModifierKey)`
     — `webview_offscreen_key_event`
     (`OffscreenWebView.java:99`).
   - `dispose(): void` — zero `peer` then call
     `webview_offscreen_destroy` if non-zero
     (`OffscreenWebView.java:108`).
4. Constraints / Invariants:
   - All operations except `create` and `dispose` go through
     `checkAlive` which throws after dispose
     (`OffscreenWebView.java:116`).
   - Pixel layout is fixed at `0xAARRGGBB`, matching
     `BufferedImage.TYPE_INT_ARGB` — callers MUST allocate the
     buffer that way (`OffscreenWebView.java:64`).

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
   component first becomes displayable, allocate the pixel
   buffer, and start the repaint timer.
2. Methods:
   - `addNotify(): void`
     - Logic: super, then if `engine != null` return (idempotent).
       Compute `w/h` from current size, defaulting to 1.
       Call `OffscreenWebView.create(w, h, debug)`. If null
       (unsupported), leave `engine` null so every subsequent
       op is a no-op and `paintComponent` falls back to the
       Swing background (`WebViewLightweightComponent.java:160`).
       Allocate the buffer, navigate to `pendingUrl`, start a
       Swing `Timer` at `REPAINT_INTERVAL_MS` repeatedly
       calling `repaint()`
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

### 8. Buffered API Stubs — eval / addOnBeforeLoad / addJavascriptCallback
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Responsibility: implement the abstract `WebViewComponent`
   surface, but in phase 1 these are intentional no-ops
   (`WebViewLightweightComponent.java:242`).
2. Methods:
   - `addOnBeforeLoad(String js)` — return `this` only
     (`WebViewLightweightComponent.java:243`).
   - `eval(String js)` — return `this` only
     (`WebViewLightweightComponent.java:249`).
   - `addJavascriptCallback(String name, JavascriptCallback cb)`
     — return `this` only
     (`WebViewLightweightComponent.java:254`).
3. Constraints / Invariants:
   - `[DRIFT]` — these are documented as "Phase 1: not yet
     wired through the offscreen path." The abstract
     contract in [[swing-webview-component-mode-selection]]
     promises the same behaviour as the heavyweight path.
     Callers that need bindings or JS eval today must use
     the heavyweight component.

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
