# SPDD Analysis: Swing WebView DevTools + Console Access API

## Original Business Requirement

(Excerpt — full text preserved verbatim in `requirements/[User-story-1]swing-webview-devtools-and-console-api.md`.)

Two stories under one [User-story-1] decomposition:

- **STORY-001-001 — Open Native DevTools Window from WebViewComponent.** Adds `boolean openDevTools()` to `WebViewComponent` and both subclasses. Linux heavyweight + offscreen calls `webkit_web_inspector_show(webkit_web_view_get_inspector(...))`. Windows heavyweight calls `ICoreWebView2::OpenDevToolsWindow()`. macOS sets `developerExtrasEnabled=YES` and `isInspectable=YES` at create time and returns `false` from `openDevTools()` (user opens via right-click → Inspect Element / Safari Develop menu). All paths require `setDebug(true)` to have been called before display; `openDevTools()` returns `false` otherwise. Idempotent. Safe from the EDT. Native additions: `webview_embed_open_devtools(long peer): int` and `webview_offscreen_open_devtools(long peer): int` (1 = opened, 0 = unsupported/disabled). 8 ACs.

- **STORY-001-002 — Capture JavaScript Console Messages from WebViewComponent.** Adds public type `ca.weblite.webview.ConsoleMessage` (level, text, sourceUrl, lineNumber, toString), enum `ConsoleMessage.Level` (LOG/INFO/WARN/ERROR/DEBUG), interface `ConsoleListener`, and methods `addConsoleListener` / `removeConsoleListener` / `setConsoleOutput(PrintStream)` / `getConsoleOutput()` on `WebViewComponent`. Implementation injects a JS shim at document-start that overrides the five `console.*` methods to post structured payloads through an internal binding (`__webview_console__`) while still calling the originals, so platform DevTools and stdout sinks keep working. Java fans out on the EDT. Console capture works regardless of the `debug` flag. 12 ACs.

Both stories apply to `WebViewComponent` plus its `WebViewHeavyweightComponent` and `WebViewLightweightComponent` subclasses. Standalone in-process `WebView` is explicitly out of scope.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **`WebViewComponent`** (`src/ca/weblite/webview/swing/WebViewComponent.java`) — abstract base for both Swing embedding modes. Currently exposes `setUrl`/`getUrl`/`setDebug`/`addOnBeforeLoad`/`eval`/`addJavascriptCallback`/`dispose`. The new API attaches here.
- **`WebViewHeavyweightComponent`** — buffered/replay pattern: pending config is stored until `createPeer()` fires from the canvas's first `paint`, at which point `EmbeddedWebView.attach(canvas, debug)` produces a live native peer and the pending list is replayed. The new API follows the same buffer/replay pattern.
- **`WebViewLightweightComponent`** — analogous lifecycle with `addNotify` instead of paint-trigger; uses `OffscreenWebView.create(w, h, debug)`. **Notable**: its `addOnBeforeLoad`/`eval`/`addJavascriptCallback` are currently **no-op stubs** ("Phase 1: not yet wired through the offscreen path", `WebViewLightweightComponent.java:244-258`). This is a hard blocker for STORY-001-002 on the lightweight component (see Risk & Gap Analysis).
- **`EmbeddedWebView`** (`src/ca/weblite/webview/EmbeddedWebView.java`) — JNI wrapper around the heavyweight peer. Has `bind` / `init` / `eval` / `dispatch` plumbing already in place; `addJavascriptCallback` registers a Java `WebViewNativeCallback`, native side stores it, JS calls cause native to invoke `WebViewNativeCallback.invoke(String, long)` back into Java. **Critical**: the existing callback invocation thread is whichever native UI thread the engine runs on (WebKitGTK pump thread on Linux, AppKit main on macOS, the dedicated WebView2 worker on Windows) — **not** the EDT. Console listener fan-out must explicitly hop to the EDT.
- **`OffscreenWebView`** (`src/ca/weblite/webview/OffscreenWebView.java`) — JNI wrapper around the lightweight offscreen peer. Has only `setSize` / `navigate` / `snapshot` / mouse / key methods. **Missing entirely**: `init`, `eval`, `bind`. These are unimplemented native-side as well (no `webview_offscreen_init` / `webview_offscreen_bind` / `webview_offscreen_eval` in `WebViewNative.java:189-242`).
- **`WebViewNative`** — JNI entry-point declarations. Both new natives (`webview_embed_open_devtools`, `webview_offscreen_open_devtools`) live here, and for the lightweight side this is also where the missing `webview_offscreen_init` and `webview_offscreen_bind` would need to land.
- **`WebViewNativeCallback`** — single-method Java interface that the native side calls back into for each JS-originated invocation. Reused as-is for the internal `__webview_console__` binding.
- **Existing `external` script-message bridge** — every engine already injects `window.external={invoke: s => postMessage(s)}` at document-start and routes the resulting message back to Java. The console capture pipeline is the same pattern with a different shim and a different binding name.
- **`debug` flag** — baked at create time in all three engines (`EmbeddedWebView.attach(canvas, debug)`, `OffscreenWebView.create(w, h, debug)`). `setDebug` throws if called after display (canvas-6 §3, canvas-7 implicit). `openDevTools` honours this rule rather than trying to flip the flag at runtime.
- **Native debug-mode toggles already wired**:
  - Linux heavyweight: `webkit_settings_set_enable_developer_extras(s, TRUE)` and `webkit_settings_set_enable_write_console_messages_to_stdout(s, TRUE)` (`src_c/webview_embed.cpp:578-579`).
  - Linux offscreen: same pair (`src_c/webview_embed.cpp:923-924`).
  - macOS: `developerExtrasEnabled=YES` via WKWebView preferences (`src_c/webview_embed.cpp:1504-1510`). **`isInspectable=YES` is NOT currently set** — required for macOS 13.3+ to expose the right-click → Inspect Element path and Safari Develop menu connection.
  - Windows: `settings->put_AreDevToolsEnabled(TRUE)` (`windows/webview_embed.cc:323`).

### New Concepts Required

- **`ConsoleMessage`** — immutable value type (`level`, `text`, `sourceUrl`, `lineNumber`). Conceptually a structured form of a single `console.*` invocation, decoupled from any specific engine. `lineNumber == -1` and `sourceUrl == null` are the legal "unknown" sentinels — different engines and different shim strategies will produce these with varying fidelity.
- **`ConsoleMessage.Level`** — closed enum of the five intercepted levels. The contract: one enum value per intercepted `console.*` method; no `TRACE` / `ASSERT` etc. (those are explicit Scope Out).
- **`ConsoleListener`** — single-callback subscriber for `ConsoleMessage` events. Semantically a `Consumer<ConsoleMessage>` but kept as a named interface for API stability and Javadoc anchoring.
- **Console output stream** — at most one `PrintStream` per component, set via `setConsoleOutput`. Internally just another listener; conceptually it's a "one-shot global sink" abstraction over the same fan-out path.
- **Internal `__webview_console__` binding** — a private channel name reserved on every engine for console capture. It is a concept the user must never collide with; treated as part of this library's reserved binding namespace.
- **JS console shim** — a small piece of JavaScript injected at document-start that wraps each of the five `console.*` methods so that:
  1. The original implementation is still invoked (so native DevTools and stdout sinks keep working — AC10).
  2. A structured payload (level + stringified args + source/line from `new Error().stack` parse) is posted through the binding.
  3. The shim is recursion-safe (re-entering `console.log` from inside the shim is suppressed).
  4. Circular references in argument values are tolerated.

### Conceptual Relationships

- `WebViewComponent` *owns* a listener registry and at most one output stream. The registry's lifetime equals the component's lifetime; listeners survive across `dispose`/re-attach if and when re-attach is ever supported (currently not — `dispose` is terminal).
- Pre-display listener registration is buffered the same way URL / init scripts / bindings are buffered. There is no separate pending-listeners list because listeners are stored in the same registry from creation; they only start receiving messages once the native peer is created and the internal `__webview_console__` binding + JS shim are in place.
- Each native engine exposes exactly one bridge through which the JS shim posts messages: WKScriptMessageHandler (macOS), WebKitUserContentManager script-message-handler (Linux), WebMessageReceived (Windows). The internal `__webview_console__` binding rides the same bridge as the existing `external` channel but with a separate name so the two don't interfere.
- `openDevTools` is a stateless action — no observable state is added to the component by calling it. The boolean return is informational (caller can grey out a "Show DevTools" menu item on macOS).
- `setDebug` and `openDevTools` are coupled by the create-time-debug-flag invariant: `openDevTools()` returns `false` if `setDebug(true)` was not called before display. This is the same coupling that already exists between `setDebug` and the native developer-extras flag in canvases 6/7.

### Key Business Rules

- **Native DevTools window is platform-owned**: the library never tries to dock, reparent, or skin the inspector window. (Reinforces canvas-6/7 norms about heavyweight peer ownership.)
- **`debug` is a static lifecycle property**: it is set once before display and cannot change afterward. `openDevTools` does not violate this — it only acts on what was decided at create.
- **Console capture is universal**: a release build (debug=false) still delivers `ConsoleMessage` events to registered listeners. The two features are decoupled: capture is via the engine's message bridge (always available) rather than via the developer-extras flag.
- **All Java callbacks land on the EDT**: this matches Swing convention and lets listeners touch Swing state directly (e.g. update a `JTextPane`).
- **Original `console.*` is preserved**: the JS shim must invoke the original method before posting, never instead of it. This guarantees that stdout (Linux debug mode), native DevTools' Console panel, and any page-level `console` monitoring tooling continue to receive output untouched (AC10).
- **Shim idempotency across navigations**: each new document load reinstalls the shim cleanly. Existing `webview_embed_init` / `WKUserScript` / `AddScriptToExecuteOnDocumentCreated` mechanics handle this — they all fire document-start on every navigation. No bookkeeping required on the Java side beyond registering the script once.
- **Reserved-name discipline**: callers cannot bind a JS callback under the name `__webview_console__`; this is a reserved internal channel. (The existing `external` name has the same status, undocumented.)

## Strategic Approach

### Solution Direction

**Two parallel cuts through the same per-engine plumbing, sharing one Java-side fan-out path.**

For STORY-001-001 (DevTools):
- Add `webview_embed_open_devtools(long): int` and `webview_offscreen_open_devtools(long): int` to `WebViewNative.java`, implemented per engine in `src_c/webview_embed.cpp` (macOS + Linux heavyweight + Linux offscreen) and `windows/webview_embed.cc` (Windows). macOS implementation just returns 0 (the only public-API path is right-click; the inspector is already enabled via the new `isInspectable` toggle at create time). Linux paths call `webkit_web_inspector_show(webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web)))`. Windows path calls `e->webview->OpenDevToolsWindow()` on the WebView2 worker thread (marshal via the existing `PostThreadMessage` mechanism the engine already uses for navigate/eval).
- Java side: `boolean openDevTools()` in both subclasses calls through to `EmbeddedWebView.openDevTools()` / `OffscreenWebView.openDevTools()` (new methods). Returns `false` when the peer is null (not yet displayed); otherwise returns `nativeReturn == 1`. macOS heavyweight always returns `false`.
- macOS preference flip: in `cocoa_create_engine` extend the existing `if (e->debug)` block at `src_c/webview_embed.cpp:1504-1510` to also set `isInspectable` on the WKWebView when the selector exists (gate with `respondsToSelector:` so we keep building / running on macOS 12.x and earlier, where the SPI/property is unavailable).

For STORY-001-002 (Console capture):
- The mechanism is *identical to the existing `external` channel*. Each engine already installs a script-message handler and an injected JS shim — we add a second, parallel one with a different name (`__webview_console__`) and a different injected source (the console wrapper).
- Heavyweight path: `EmbeddedWebView` already has `addOnBeforeLoad` and `addJavascriptCallback`. The component-level layer installs the shim via `addOnBeforeLoad` and registers a single internal callback via `addJavascriptCallback` that parses the incoming JSON-ish payload into a `ConsoleMessage` and dispatches to listeners. **No new native code is required on heavyweight** — both Linux and macOS heavyweight + Windows heavyweight already expose the necessary bridge through the embed API.
- Lightweight path: **this requires new native plumbing** — `OffscreenWebView` and the underlying offscreen engine have no `init`/`bind` today. New JNI entry points: `webview_offscreen_init(long, String)` and `webview_offscreen_bind(long, String, WebViewNativeCallback, long)`. Native side in the offscreen engine (`src_c/webview_embed.cpp:880-` block) installs a WebKitUserContentManager + script-message-handler exactly as the embed path already does (`src_c/webview_embed.cpp:547-583` is the model). `OffscreenWebView` and `WebViewLightweightComponent` are updated to drop the "not yet wired" stubs and actually call these.
- Java fan-out: a new package-private `ConsoleDispatcher` class lives in `ca.weblite.webview` and is owned per-component. It exposes `add/removeListener`, `setOutputStream`, `getOutputStream`, and `dispatch(rawJson)`. `dispatch` parses level + text + sourceUrl + lineNumber out of the payload, constructs a `ConsoleMessage`, and uses `SwingUtilities.invokeLater` (or runs inline if already on the EDT) to deliver to every registered listener, with each listener invocation wrapped in try/catch so one bad listener can't break the chain (AC12).
- JS shim implementation: a single self-installing IIFE that captures the five originals at document-start, replaces each with a wrapper that (1) calls the original, (2) builds a `[level, text, sourceUrl, lineNumber]` array using a recursion-safe stringifier and a stack-parser, and (3) posts via `window.webkit.messageHandlers.__webview_console__.postMessage` (Linux/macOS) or `window.chrome.webview.postMessage` with a name tag (Windows). The shim re-entry guard uses a per-call flag so calling `console.log` inside a listener (in JS) doesn't loop.

### Key Design Decisions

- **Reuse the existing message-bridge pattern vs. inventing a new transport.** Trade-off: the existing pattern (script-message handler + `addScriptToExecuteOnDocumentCreated` / `WKUserScript` / `webkit_user_script_new`) is already proven in the codebase, exercises a code path that's already validated across all three platforms, and adds zero new native concepts on heavyweight. The alternative — using each platform's structured console-capture API (`Runtime.consoleAPICalled` on Windows CDP, `WebPage::console-message-sent` Web Extension on Linux, no native equivalent on macOS) — would give richer messages on Windows but require platform-specific paths everywhere and a Web Extension `.so` on Linux. **Recommendation: reuse the bridge.** Uniform implementation, smaller blast radius, and AC10's "original console.* still fires" requirement is automatically met because we don't consume the call native-side.

- **Listener invocation on the EDT vs. on the native thread with documented contract.** Trade-off: invoking on the EDT means callbacks are slightly delayed (one `invokeLater` hop, ~ms) but listeners can directly touch Swing state. Native-thread invocation is faster but pushes the burden onto callers, who would invariably forget and produce thread-violation bugs. **Recommendation: EDT.** Matches the story's Scope In and AC7. Listeners that need to do heavy work should push to their own executor — this is the same advice that applies to every Swing listener.

- **macOS `openDevTools` — public API only.** Trade-off: returning `false` is uglier than uniform `true`. Using `_WKInspector` SPI would let us open it programmatically and match Linux/Windows. **Recommendation: public API only**, as already chosen in the story. The SPI is undocumented, may break on any macOS minor release, and would invalidate the library's "no private APIs" implicit contract. Returning `false` is honest about what's supported.

- **`isInspectable` guarded by `respondsToSelector`.** macOS 13.3+ exposes `isInspectable` as a public BOOL property on `WKWebView`; earlier macOS versions do not. **Recommendation: dynamic guard.** Use `respondsToSelector:@selector(setInspectable:)` before calling — keeps the binary running on macOS 12.x without weak-linking. The right-click → Inspect Element path is already available without `isInspectable` on those older OS versions; only the Safari Develop-menu connection is unavailable, and that's an acceptable degradation.

- **Lightweight offscreen JS bridge: add now vs. defer story.** Trade-off: adding `webview_offscreen_init` and `webview_offscreen_bind` is genuine native work (estimated ~half-day on top of the story baseline). Deferring means STORY-001-002 ships heavyweight-only and the lightweight `addConsoleListener` is documented as no-op until offscreen plumbing lands. **Recommendation: add now.** The two native methods are a near-direct port of the existing embed-path `webkit_user_script_new` / `register_script_message_handler` flow already in `src_c/webview_embed.cpp:547-583` (the offscreen engine *already* uses a `WebKitUserContentManager` at `:917` via `webkit_web_view_new_with_user_content_manager` — actually verify which constructor is used). It also unblocks the existing `addJavascriptCallback`/`addOnBeforeLoad`/`eval` stubs that have been deferred since Phase 1, which is a longstanding canvas-7 known limitation. The work belongs in this story rather than a separate "fix lightweight JS bridge" story because (a) STORY-001-002 needs it anyway, (b) the canvas-7 update is happening here regardless.

- **`PrintStream` formatting line stability.** The story specifies the format `[<LEVEL>] <source>:<line> <text>\n`. **Recommendation: lock this format in the canvas as a public contract.** Callers piping to log analyzers will grep this; changes are visible behavior changes requiring a major version bump.

- **Argument stringification: client-side (JS) vs. server-side (Java).** Trade-off: doing it in JS is cheaper and produces exactly what `String(arg)` would (matching AC11's example). Doing it in Java means transporting a JSON-encoded array and re-coercing, which gives less control over circular references and date formatting. **Recommendation: JS-side**, using a recursion-safe `String()` join with `try/catch` around each argument. Matches AC11 and is what every existing WKWebView-console-capture library does.

- **One reserved binding name vs. per-message-type names.** Trade-off: a single `__webview_console__` channel transports payloads tagged with a level field; or five channels (`__webview_console_log__`, etc.). **Recommendation: single channel.** Smaller native footprint, simpler shim, and the level is just one element of the payload anyway.

### Alternatives Considered

- **Windows-only: use `Runtime.consoleAPICalled` via WebView2's CDP API.** Gives structured payloads with full argument types, stack traces, and execution context IDs natively. Rejected as the *primary* mechanism because it would force a per-platform implementation split and we'd still need the JS shim for macOS and Linux. We could *also* support it as a Windows-specific optimisation later — noted for canvas-6 future-work, not in this story.
- **Linux: ship a Web Extension `.so` that hooks `WebKitWebPage::console-message-sent`.** Gives the same structured payload as Windows CDP. Rejected: adds a new build artifact, requires shipping the `.so` in the right path for the loader to find, and breaks across WebKitGTK ABI bumps. The shim approach has none of these problems.
- **macOS: use `_WKInspector` SPI to open the inspector programmatically.** Rejected per the macOS-openDevTools decision above.
- **Java listener API as `Consumer<ConsoleMessage>` instead of named interface.** Rejected because the story explicitly specified `ConsoleListener`, and a named interface gives a stable Javadoc anchor and better debuggability than a lambda type.
- **EventListener style with explicit `enabled` flag.** Rejected as feature-creep — listeners are already cheap to add/remove; an enabled flag would be a fourth method on the API surface for no observable benefit over `removeConsoleListener`.

## Risk & Gap Analysis

### Requirement Ambiguities

- **Stack-frame source detection on macOS.** The JS shim parses `new Error().stack` to extract source URL and line number. The exact format of `Error.stack` differs between JavaScriptCore (WKWebView), WebKit2 (Linux), and Blink (WebView2). The story doesn't specify whether `sourceUrl`/`lineNumber` are best-effort or guaranteed. **Resolution direction**: treat them as best-effort, document that they may be `null`/`-1` on some platforms or for some pages (e.g. evaluated via `eval()` or anonymous inline `<script>`), and add a Scope-Out note. AC1 mentions "reflect the location" — we'll honour that on the common case (file URL + line) and degrade to `null`/`-1` for anonymous code.
- **`setConsoleOutput` PrintStream charset.** Not specified. **Resolution direction**: use the stream's existing encoding; do not impose UTF-8 via `OutputStreamWriter`. Document this in the canvas Safeguards.
- **`getConsoleOutput()` return value when never set.** Story says "returns the current redirect stream or `null`". `null` is the unambiguous initial value. No ambiguity once locked.
- **Listener-list mutation during iteration.** If a listener's `onMessage` calls `removeConsoleListener(self)` (or adds a new one), does the change apply to this message or only the next? **Resolution direction**: snapshot the listener list before iterating each message, so mutations during fan-out only take effect for the *next* message. Matches the story's NF expectation "removing a listener must take effect for the very next console message".

### Edge Cases

- **`console.log(undefined)` and `console.log()` with no arguments.** Should they produce a `ConsoleMessage` with text `"undefined"` and `""` respectively, or be suppressed? **Decision needed**: emit both, with `String(undefined) == "undefined"` and `""` for no args. (Matches AC11's stringification rule.)
- **Page calls `console.log` before the shim is installed.** The shim is installed at document-start via `WKUserScript` / `webkit_user_script_new` / `AddScriptToExecuteOnDocumentCreated`, which all guarantee execution before any page script. Edge case: synchronous `<script>` in the same `<head>` that runs immediately after document-start — should still be after the shim because document-start fires before any page script. No mitigation required, but acceptance test should cover.
- **Page overrides `console.log` after the shim is installed.** The page replaces `window.console.log = function(){}`. The shim's reference to the original is captured by closure, so the page override doesn't affect the shim's interception — but the shim *calls* the page-installed wrapper through `window.console.log`, not the original. Decision: shim should call the *original* it captured, not the current `window.console.log`, to keep interception independent of page-side monkey-patching. Document in the canvas Safeguards.
- **`PrintStream` write failure.** Setting a stream that throws on write must not break the listener pipeline. **Resolution direction**: wrap `PrintStream.println` in try/catch in the internal redirect listener; on IO failure clear `getConsoleOutput()` silently. (`PrintStream` itself swallows IO exceptions by design, but we should still guard.)
- **Disposed component receives a late callback from native.** If `dispose()` clears the listener registry but a native message arrives after disposal (race between `dispose` on EDT and a pending native callback in-flight), the callback should be silently dropped. The existing `EmbeddedWebView.bindings` map is cleared on dispose; same pattern applies.
- **`openDevTools()` on Linux while a previous inspector window has been closed by the user.** `webkit_web_inspector_show` should re-open it. Native behaviour: idempotent. AC7 covers this implicitly but the "previous closed by user" sub-case should be in the AC test.
- **`openDevTools()` on Windows where DevTools shortcut was used (F12) to open already.** `OpenDevToolsWindow()` should focus the existing window. Documented WebView2 behaviour.
- **macOS `isInspectable` on macOS 12 or earlier**: the selector doesn't exist; gated by `respondsToSelector`. Right-click → Inspect Element still works (the `developerExtrasEnabled` flag is sufficient for in-process inspection on older macOS).

### Technical Risks

- **Lightweight offscreen JS bridge gap.** The Linux offscreen engine in `src_c/webview_embed.cpp` (around line 880-924) already installs a `WebKitUserContentManager` (via `webkit_web_view_new_with_user_content_manager` — verify), so adding `register_script_message_handler` and `webkit_user_script_new` is a small extension. But it has not been done yet, and the corresponding `webview_offscreen_init` / `webview_offscreen_bind` JNI entry points need to be added to `WebViewNative.java`, the JNI header regenerated, and `OffscreenWebView.java` + `WebViewLightweightComponent.java` updated to drop their no-op stubs. **Impact**: roughly +0.5 day on STORY-001-002. **Mitigation**: handle it inside this story; canvas-7 already lists this as a known gap.
- **EDT marshaling correctness across all engines.** The native callback into `WebViewNativeCallback.invoke` happens on:
  - macOS: the AppKit main thread (WKScriptMessageHandler delivers on main).
  - Linux heavyweight: the GTK pump thread.
  - Linux offscreen: the GTK pump thread.
  - Windows: the dedicated WebView2 worker thread (which `PostThreadMessage`'s back the user content message into the worker's loop).
  None of these is the EDT. Every callback path must explicitly call `SwingUtilities.invokeLater`. **Mitigation**: do the marshal in `ConsoleDispatcher.dispatch` (one place to get right) and unit test by asserting `SwingUtilities.isEventDispatchThread()` inside listeners (AC7).
- **Windows `OpenDevToolsWindow` thread affinity.** WebView2 methods must be called from the thread the controller was created on. Existing `EmbeddedWebView.eval` / `navigate` already marshal via `PostThreadMessage`; the new `webview_embed_open_devtools` must do the same. **Mitigation**: copy the existing pattern; don't invent a new marshalling primitive.
- **Linux `webkit_web_view_get_inspector` returning NULL.** Possible if the engine creation failed mid-setup. **Mitigation**: null-check before `webkit_web_inspector_show`, return 0 from the JNI on null.
- **JSON serialization in the JS shim.** The payload `[level, text, sourceUrl, lineNumber]` is sent as a string. Encoding via `JSON.stringify` on the JS side and parsing in Java is the obvious choice, but adds a JSON dependency on the Java side. **Mitigation**: use a simple manually-formatted payload (e.g. pipe-separated with length-prefixed text) to avoid needing a JSON parser. The existing `external` bridge currently does ad-hoc string parsing too (`engine_on_message` in `src_c/webview_embed.cpp:1336-1361` extracts `"name":"..."` by substring). Recommendation: same approach. Specific format to lock in the canvas.
- **`Error.stack` format brittleness.** Different engines produce different stack formats. **Mitigation**: heuristic regex matching the common patterns (`at <fn> (URL:line:col)` for V8/Blink, `<fn>@URL:line:col` for JavaScriptCore/WebKit). Failure mode is sourceUrl=null, lineNumber=-1 — graceful degradation.
- **Shim recursion when a listener (in JS) calls `console.log`.** A wrapped `console.log` that itself calls `console.log` during the post step would loop. **Mitigation**: per-call boolean guard in the shim, captured in a closure variable, set true before posting and false in finally.
- **Backwards compatibility of `WebViewComponent` abstract method addition.** Adding abstract methods to a public abstract class breaks any third-party subclass. **Mitigation**: provide default implementations on `WebViewComponent` (return empty / no-op) and have both first-party subclasses override. Same shape as `isHeavyweight()` which already has a default. Note that the contract may want them abstract — decision in canvas-5: make `openDevTools` non-abstract with default `return false`, and `addConsoleListener`/`removeConsoleListener`/`setConsoleOutput`/`getConsoleOutput` non-abstract with default `ConsoleDispatcher`-backed implementations so any future subclass inherits the API for free.
- **Heavyweight `addJavascriptCallback` binding-name collision.** A user who calls `addJavascriptCallback("__webview_console__", ...)` could overwrite the internal channel. **Mitigation**: reject reserved names at the component layer with `IllegalArgumentException`. Documented Norm.

### Acceptance Criteria Coverage

#### STORY-001-001 (DevTools)

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1   | Linux heavyweight opens inspector when debug=true | Yes | Hits `webkit_web_inspector_show`; verify the inspector's GtkWindow doesn't reparent under our X11 popup the way the WebView itself does. |
| 2   | Windows heavyweight opens DevTools when debug=true | Yes | Marshal through WebView2 worker thread (same as `navigate`/`eval`). |
| 3   | macOS reports false but right-click works | Yes | New `isInspectable=YES` toggle needed; gate with `respondsToSelector`. Right-click context menu is already enabled by `developerExtrasEnabled=YES`. |
| 4   | Linux lightweight opens inspector when debug=true | Yes | Lightweight engine already enables developer-extras when debug=true (`:924`); just need the JNI entry point and `OffscreenWebView.openDevTools()` method. |
| 5   | Returns false when debug=false | Yes | Native side can short-circuit on the debug flag, or Java side checks. Recommend: native checks (single source of truth). |
| 6   | Returns false before display | Yes | Java side: peer is null. |
| 7   | Idempotent across repeated calls | Yes | All three SDKs handle re-call gracefully (Linux + Windows focus existing window; macOS no-op). |
| 8   | Safe from the EDT | Yes | Marshal native call asynchronously where needed; method returns immediately. |

#### STORY-001-002 (Console capture)

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1   | Listener receives each console.log call | Yes (heavyweight) / **Partial (lightweight)** | Lightweight requires the new offscreen bind/init plumbing. |
| 2   | All 5 levels preserved | Yes | Level tag in the payload. |
| 3   | Multiple listeners receive same message | Yes | Linked-list iteration with snapshot. |
| 4   | Removed listener stops receiving | Yes | Snapshot-before-iterate ensures correctness within the same message tick. |
| 5   | PrintStream sink formatted lines | Yes | Lock format `[LEVEL] source:line text\n` in canvas. |
| 6   | PrintStream redirect can be cleared | Yes | Internal redirect listener add/remove on setter call. |
| 7   | Callbacks on EDT | Yes | `SwingUtilities.invokeLater` (or inline if already EDT) in dispatcher. |
| 8   | Pre-display listeners still receive | Yes | Listener registry is populated immediately; the JS shim + binding install at create time and start delivering. |
| 9   | Works with debug=false | Yes | Capture path is independent of the developer-extras flag. |
| 10  | Original console.* still fires for stdout/DevTools | Yes | Shim calls original before posting. |
| 11  | Non-string args stringified | Yes | JS-side `String(arg)` join with try/catch per arg. |
| 12  | Listener exception doesn't break pipeline | Yes | try/catch around each listener in dispatcher; route exception through Thread.currentThread().getUncaughtExceptionHandler(). |

**Coverage**: 8/8 + 12/12 with the offscreen JS-bridge work folded in.

**Open questions to confirm before canvas update** (none are blocking — defaults stated above):
1. Confirm the `[LEVEL] source:line text\n` format is the canonical `toString()` and `PrintStream` line format.
2. Confirm reserved binding name `__webview_console__` (or alternative).
3. Confirm payload format for the JS→native message (pipe-separated, vs. minimal-JSON).
4. Confirm lightweight offscreen JS bridge is in scope for this story (recommended yes).
