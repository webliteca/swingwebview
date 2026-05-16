# Story Decomposition: Swing WebView DevTools + Console Access API

## INVEST Analysis

### Abstract Task: "Cross-platform developer-visibility API for embedded WebViews"

**Analysis Dimensions**:
- **Core Responsibility**: Give Swing applications hosting `WebViewComponent` developer-grade visibility into the embedded WebView's JavaScript runtime — both visual (native DevTools window) and programmatic (structured `console.*` event stream).
- **Primary Operations**: Open DevTools window (idempotent), subscribe to console messages (listener add/remove), redirect formatted console output to a `PrintStream` sink.
- **Key Constraints**:
  - `openDevTools` requires `debug=true` set before display (existing canvas-6 / canvas-7 rule that bakes the debug flag at create time).
  - macOS WKWebView has no public API to programmatically pop the Web Inspector; the public path is to enable the inspector flag and let the user right-click → Inspect Element (or Safari Develop menu).
  - Console listener callbacks must be dispatched on the Swing EDT so listeners can touch Swing state directly.
  - `WebViewLightweightComponent` is Linux-only today; its lightweight (offscreen) native engine needs the same plumbing.
- **Technical Complexity**: Medium — three native backends (WebKitGTK, WebView2, WKWebView) plus one offscreen backend (WebKitGTK offscreen) each need a small addition; console capture reuses the existing bind / script-message bridge with an injected JS shim.
- **Business Complexity**: Low — small, well-bounded developer-facing API surface.

### INVEST Evaluation

- ✅ **Independent**: Sits on top of existing canvases 5/6/7; introduces no new external dependencies; both stories can ship without the other.
- ✅ **Negotiable**: API shape agreed (structured `ConsoleListener` + `PrintStream` convenience, boolean return from `openDevTools`); macOS `openDevTools` semantics fixed at "returns false, enable flag toggled so user can right-click".
- ✅ **Valuable**: Every embedded-WebView developer needs at least one of these capabilities during development.
- ✅ **Estimable**: Each platform's native call is a single SDK API; console capture is a known JS-shim-plus-message-bridge pattern that already exists in the codebase for the `external` channel.
- ⚠️ **Small (combined)**: DevTools + console capture as one story is ~4-6 days. **Split** into two stories of 1-3 days each.
- ✅ **Testable**: Listener fan-out and open/no-open booleans are straightforward to assert from a small Swing harness.

**Conclusion**: Needs splitting (by feature, not by technical layer).

### Split Strategy

Split by **feature capability**, not by technical layer:

- **STORY-001-001 — DevTools Window**: thin wrapper around each platform's "show inspector" SDK call, plus a settings toggle on macOS.
- **STORY-001-002 — Console Listener + PrintStream**: structured console event stream consumable from Java, including a `PrintStream` convenience sink.

Both stories deliver independent value, are independently testable, and have no cross-dependency at the story level (they touch overlapping files but don't block one another).

---

## [STORY-001-001] Open Native DevTools Window from WebViewComponent

### Background

Developers who embed a WebView in their Swing application need to inspect the live DOM, Network panel, JavaScript debugger, Sources, and Console of the page they're rendering. All three native backends ship a full Web Inspector / DevTools, but currently a Swing app has no way to surface that inspector — debug builds enable the inspector flag (so right-click → Inspect Element works on Linux and now on macOS, and F12 works on Windows), but the developer has no programmatic way to pop it from a menu item, a keystroke, or a startup hook.

This story exposes a single cross-platform call that opens the platform's native DevTools in a separate OS-level window. The window is the platform's own — Safari Web Inspector on macOS, WebKitGTK inspector on Linux, Chromium DevTools on Windows — so users get the full, familiar tooling without us re-implementing any of it.

Key points:
- Business value: lets app developers add a "Tools → Web Inspector" menu item or keyboard shortcut to their app.
- Relationship with other features: builds on the existing `debug` flag plumbed through `WebViewComponent.setDebug` (canvas-5) and the embed engine constructors (canvas-6 / canvas-7). Adds nothing to the standalone in-process `WebView` (canvas-2).
- Why now: the embedded mode is shipped and usable; without an inspector hook, developers using it must drop to native debugging.

### Business Value

- Provide **on-demand native developer tools access** for application developers using `WebViewComponent`.
- Support **debugging the embedded page** (DOM, network, JS console, breakpoints) without leaving the host Swing application.
- Enable **production app diagnostic builds** where an internal-only menu item can surface the inspector for support sessions.

### Dependencies and Assumptions

- **Prerequisites**: Canvases 5, 6, 7 (mode selection + heavyweight + lightweight) already in place. The `setDebug(boolean)` API and its "must be called before display" contract already exist.
- **Data assumptions**: No persisted state.
- **Integration points**: Linux WebKitGTK's `WebKitWebInspector`; Windows WebView2's `ICoreWebView2`; macOS WKWebView's `developerExtrasEnabled` and `isInspectable` settings.
- **Business constraints**: macOS does not expose a public API to open the inspector programmatically; on macOS the call enables the inspector and returns `false`. Documented limitation, not a defect.

### Scope In

- New public method `boolean openDevTools()` on `WebViewComponent` (abstract) and both subclasses.
- Native call additions: `webview_embed_open_devtools(long peer)` and `webview_offscreen_open_devtools(long peer)`, each returning `int` (1 = opened, 0 = unsupported/disabled).
- On Linux heavyweight + offscreen: call `webkit_web_inspector_show(webkit_web_view_get_inspector(view))` when `debug=true`.
- On Windows heavyweight: call `ICoreWebView2::OpenDevToolsWindow()` when `debug=true` (i.e. `AreDevToolsEnabled=TRUE`).
- On macOS heavyweight: set `developerExtrasEnabled=YES` + `isInspectable=YES` at create time when `debug=true`; `openDevTools()` is a no-op that returns `false` (but the inspector is reachable via right-click → Inspect Element and Safari Develop menu).
- `openDevTools()` returns `false` if `setDebug(true)` was not called before display (debug flag is baked at create time per existing canvas rule).
- `openDevTools()` is callable repeatedly; re-opening when the inspector window is already up is idempotent (platform handles focus-vs-create).
- Threading: `openDevTools()` may be called from the EDT; the implementation marshals to the appropriate native thread internally.

### Scope Out

- Programmatic close of the DevTools window (not all platforms expose it).
- Customising which DevTools panel opens first (always the platform default).
- Docking the inspector inside the Swing window — all platforms open it as a separate OS window.
- Adding `openDevTools()` to the standalone in-process `WebView` class.
- Toggling the debug flag at runtime — debug remains a create-time setting (separate story if ever needed).
- macOS programmatic open via private `_WKInspector` SPI — explicitly rejected in design (public APIs only).

### Acceptance Criteria

#### AC1: Opens inspector window on Linux heavyweight when debug is enabled
**Given** a `WebViewHeavyweightComponent` created with `setDebug(true)` and added to a visible JFrame on Linux,
**When** the application calls `wv.openDevTools()`,
**Then** the WebKitGTK Web Inspector appears in a separate OS window showing the current page's DOM, and the method returns `true`.

#### AC2: Opens DevTools window on Windows heavyweight when debug is enabled
**Given** a `WebViewHeavyweightComponent` created with `setDebug(true)` and added to a visible JFrame on Windows,
**When** the application calls `wv.openDevTools()`,
**Then** the Chromium DevTools window appears as a separate OS window attached to the embedded WebView2, and the method returns `true`.

#### AC3: macOS reports unsupported but enables inspector access
**Given** a `WebViewHeavyweightComponent` created with `setDebug(true)` and added to a visible JFrame on macOS,
**When** the application calls `wv.openDevTools()`,
**Then** the method returns `false` and no inspector window opens automatically; **and** when the user right-clicks the WebView contents, the context menu offers "Inspect Element" which opens the Safari Web Inspector in a separate window.

#### AC4: Opens inspector on Linux lightweight when debug is enabled
**Given** a `WebViewLightweightComponent` created with `setDebug(true)` and added to a visible JFrame on Linux,
**When** the application calls `wv.openDevTools()`,
**Then** the WebKitGTK Web Inspector appears in a separate OS window inspecting the offscreen WebView's page, and the method returns `true`.

#### AC5: Returns false when debug was not enabled before display
**Given** a `WebViewComponent` displayed without ever calling `setDebug(true)` (or with `setDebug(false)`),
**When** the application calls `wv.openDevTools()`,
**Then** the method returns `false` and no inspector window opens on any platform.

#### AC6: Returns false when called before the component is displayed
**Given** a `WebViewComponent` instance that has been constructed but not yet added to a visible window,
**When** the application calls `wv.openDevTools()`,
**Then** the method returns `false` and no inspector window opens.

#### AC7: Idempotent across repeated calls
**Given** a debug-enabled, displayed `WebViewComponent` on Linux or Windows where `openDevTools()` has already been called once,
**When** the application calls `wv.openDevTools()` a second time while the previous inspector window is still open,
**Then** the method returns `true`, the existing inspector window receives focus (or a new one opens — platform-defined), and no error or duplicate state results.

#### AC8: Safe to call from the EDT
**Given** the Swing Event Dispatch Thread is running a UI handler,
**When** that handler calls `wv.openDevTools()`,
**Then** the call returns without blocking the EDT for longer than a normal native UI call (no deadlock, no exception), and the inspector window appears asynchronously.

### Non-Functional Expectations

- Calling `openDevTools()` must not crash the host application on any of the three supported platforms regardless of debug state or display state.
- Returning `false` must not leak any native resources.

---

## [STORY-001-002] Capture JavaScript Console Messages from WebViewComponent

### Background

The embedded WebView's page can emit arbitrary `console.log` / `console.info` / `console.warn` / `console.error` / `console.debug` calls, and today none of them are visible to the host Java application unless the user has manually opened the platform's DevTools window. For production diagnostics, logging integration, custom in-app developer consoles, and automated tests of pages running inside the WebView, the host needs to observe those calls structurally — with the level, message text, source URL, and line number preserved.

This story adds a structured, level-aware console capture pipeline. The page's `console.*` methods are intercepted (without breaking native DevTools output) and every call is delivered to registered Java listeners as a `ConsoleMessage` value object on the Swing EDT. A convenience method redirects the same messages to a `PrintStream` for callers who just want them on `System.out` or in a logfile.

Key points:
- Business value: lets apps log WebView console output to their own logging system, build a docked dev console as a JComponent, or assert on console output in automated UI tests.
- Relationship with other features: independent from STORY-001-001; can ship in either order. Reuses the existing native message bridge (`webview_embed_bind` / `webview_offscreen_bind` + WKScriptMessageHandler / WebKitUserContentManager / WebView2 WebMessageReceived) — no new native API needed.
- Why now: same as the DevTools story — without it, embedding apps can't see what the page is doing.

### Business Value

- Provide **structured visibility into the embedded page's JavaScript console output** for application developers using `WebViewComponent`.
- Support **integration with the host application's logging system** (SLF4J, JUL, log4j, custom) by forwarding console messages to a `PrintStream` or directly via listener.
- Enable **automated testing** of pages hosted in the WebView by allowing tests to assert on console messages emitted by the page.
- Enable **in-app developer consoles** built as ordinary Swing components fed by the listener stream.

### Dependencies and Assumptions

- **Prerequisites**: Canvases 5, 6, 7 (mode selection + heavyweight + lightweight) already in place. The existing native message bridges (`webview_embed_bind` on heavyweight, `webview_offscreen_*` plumbing on lightweight) are functional.
- **Data assumptions**: No persisted state. Listener registry is per-component-instance.
- **Integration points**: WKWebView's `WKScriptMessageHandler` + `WKUserScript` (already used for the `external` channel); WebKitGTK's `WebKitUserContentManager.register_script_message_handler` (already used for the `external` channel); WebView2's `AddScriptToExecuteOnDocumentCreated` + `WebMessageReceived` (already used for the `external` channel).
- **Business constraints**: Console capture must work regardless of the `debug` flag — i.e., a release build with `debug=false` must still deliver console messages to listeners.

### Scope In

- New public type `ca.weblite.webview.ConsoleMessage` with read-only fields/accessors: `Level level`, `String text`, `String sourceUrl` (nullable), `int lineNumber` (`-1` if unknown), and a `toString()` that produces a stable formatted line (e.g. `[WARN] foo.js:42 something happened`).
- New public enum `ca.weblite.webview.ConsoleMessage.Level` with values `LOG`, `INFO`, `WARN`, `ERROR`, `DEBUG`.
- New public interface `ca.weblite.webview.ConsoleListener` with a single method `void onMessage(ConsoleMessage message)`.
- New public methods on `WebViewComponent` (abstract; implemented by both subclasses):
  - `void addConsoleListener(ConsoleListener listener)`
  - `void removeConsoleListener(ConsoleListener listener)`
  - `void setConsoleOutput(PrintStream stream)` — `null` clears the redirect.
  - `PrintStream getConsoleOutput()` — returns the current redirect stream or `null`.
- Implementation injects a JavaScript shim at document-start that overrides `window.console.log/info/warn/error/debug` to post structured messages through an internal named binding (e.g. `__webview_console__`) to the native side, while still calling the original `console.*` so the platform's native DevTools and stdout sinks continue to receive output untouched.
- The native side forwards each message to Java, which fans out to all registered listeners and, if set, writes a formatted line to the `PrintStream`.
- All listener `onMessage` callbacks are invoked on the Swing EDT.
- Listeners added before the component is displayed are remembered and bound when the native peer is created.
- Console capture is active regardless of the `debug` flag.

### Scope Out

- Capturing uncaught JavaScript exceptions or unhandled promise rejections (`window.onerror`, `unhandledrejection`) — separate concern, separate story.
- Capturing `console.table`, `console.group`, `console.trace`, `console.assert`, `console.count`, `console.dir`, etc. — only the five common level methods are intercepted.
- Capturing stack traces for messages — only `sourceUrl` + `lineNumber` are exposed.
- Faithful argument serialization for non-string arguments — non-strings are coerced via `String(arg)` join on the JS side; circular references are tolerated by skipping (no exception thrown into the page).
- Adding the listener API to the standalone in-process `WebView` class.
- A built-in `JConsoleView` Swing widget — that's a downstream user-built component this API enables, not part of this story.

### Acceptance Criteria

#### AC1: Listener receives each console.log call from the page
**Given** a `WebViewComponent` with a registered `ConsoleListener` and loaded with a page that calls `console.log("hello", 42)`,
**When** the page executes the console call,
**Then** the listener's `onMessage` is invoked exactly once with a `ConsoleMessage` whose `level` is `LOG`, `text` is `"hello 42"`, and whose `sourceUrl` and `lineNumber` reflect the location of the call.

#### AC2: Levels are preserved across all five intercepted methods
**Given** a `WebViewComponent` with a registered listener and a page that calls `console.log`, `console.info`, `console.warn`, `console.error`, and `console.debug` once each,
**When** the page executes all five calls,
**Then** the listener receives exactly five `ConsoleMessage` callbacks in source order with levels `LOG`, `INFO`, `WARN`, `ERROR`, `DEBUG` respectively.

#### AC3: Multiple listeners all receive the same message
**Given** a `WebViewComponent` with three listeners registered via `addConsoleListener` and a page that calls `console.log("x")` once,
**When** the page executes the call,
**Then** all three listeners receive a `ConsoleMessage` with the same content; the order in which listeners are invoked is the order they were added.

#### AC4: Removed listener stops receiving messages
**Given** a `WebViewComponent` with two listeners registered, after which one of them is removed via `removeConsoleListener`,
**When** the page calls `console.log("after remove")`,
**Then** only the remaining listener is invoked; the removed listener receives no callback.

#### AC5: PrintStream sink receives formatted lines
**Given** a `WebViewComponent` for which `setConsoleOutput(printStream)` has been called with a `PrintStream` capturing into a buffer, and a page that calls `console.warn("careful")`,
**When** the page executes the call,
**Then** a line matching the pattern `[WARN] <source>:<line> careful` (terminated by `\n`) appears in the captured buffer.

#### AC6: PrintStream redirect can be cleared
**Given** a `WebViewComponent` with a `PrintStream` previously set via `setConsoleOutput`, where `setConsoleOutput(null)` has subsequently been called,
**When** the page executes a `console.log` call,
**Then** nothing is written to the previously-set stream, and `getConsoleOutput()` returns `null`.

#### AC7: Listener callbacks fire on the EDT
**Given** a `WebViewComponent` with a registered listener whose `onMessage` records `SwingUtilities.isEventDispatchThread()`,
**When** the page calls `console.log("test")`,
**Then** the recorded value is `true` regardless of which native thread originated the message.

#### AC8: Listeners registered before display still receive messages
**Given** a `WebViewComponent` instance constructed and assigned a `ConsoleListener` before being added to a JFrame,
**When** the component is subsequently displayed and the loaded page calls `console.log("ready")`,
**Then** the listener receives the message exactly once.

#### AC9: Console capture works with debug disabled
**Given** a `WebViewComponent` with `setDebug(false)` (or default) and a registered listener,
**When** the loaded page calls `console.error("oops")`,
**Then** the listener receives a `ConsoleMessage` with level `ERROR` and text `"oops"`.

#### AC10: Original console.* still fires for DevTools / stdout
**Given** a Linux `WebViewComponent` running with `debug=true` (which enables `enable_write_console_messages_to_stdout`) and a registered listener,
**When** the loaded page calls `console.log("dual")`,
**Then** the listener receives the message **and** the line still appears on the host process's stdout (the interception does not consume the original call).

#### AC11: Non-string arguments are stringified
**Given** a `WebViewComponent` with a registered listener and a page that calls `console.log({a: 1}, [2, 3], 4)`,
**When** the page executes the call,
**Then** the listener receives a single `ConsoleMessage` whose `text` is a space-joined string-coerced representation of the arguments (e.g. `"[object Object] 2,3 4"` or equivalent — the exact stringification follows JavaScript's default coercion).

#### AC12: Listener exception does not break the pipeline
**Given** a `WebViewComponent` with two registered listeners where the first throws a `RuntimeException` inside `onMessage`,
**When** the page calls `console.log("test")`,
**Then** the second listener still receives the message, and the exception from the first listener is reported via standard EDT uncaught-exception handling but does not crash the WebView or the host app.

### Non-Functional Expectations

- Console capture must not produce a measurable framerate regression on the lightweight component during pages with chatty console output (e.g. a page that calls `console.log` once per animation frame must still render at the lightweight component's normal snapshot rate).
- The injected JS shim must be idempotent across page navigations — each new page load installs the shim cleanly and listeners continue to receive messages without re-registration.
- Removing a listener must take effect for the very next console message (no stale-listener invocations after `removeConsoleListener` returns).

---

## Quality Checks

**STORY-001-001 (DevTools Window)**:
- ✅ All required sections present.
- ✅ Each AC uses Given-When-Then with concrete platform names and observable outcomes.
- ✅ Business-language ACs (no JNI signatures, no SDK method names inside AC bodies — those live in Scope In and the canvases).
- ✅ Covers happy path (per-platform), edge cases (debug off, before display), repeated calls, and EDT-safety.
- ✅ At most 3 functional points (open on Linux / Windows / macOS).
- ✅ 1-2 days of work.

**STORY-001-002 (Console Capture)**:
- ✅ All required sections present.
- ✅ Each AC uses Given-When-Then with concrete listener / message expectations.
- ✅ Business-language ACs.
- ✅ Covers happy path (single + multi-listener), level fidelity, removal, PrintStream redirect, EDT marshaling, pre-display registration, debug-off operation, dual-output preservation, error isolation.
- ✅ At most 3 functional points (listener API, PrintStream sink, EDT marshaling).
- ✅ 2-3 days of work.

## Final INVEST Re-validation

| Property | STORY-001-001 | STORY-001-002 |
|---|---|---|
| Independent | ✅ (no dependency on -002) | ✅ (no dependency on -001) |
| Complete | ✅ | ✅ |
| Valuable | ✅ (menu/keystroke inspector access) | ✅ (logging + tests + custom UIs) |
| Estimable | ✅ (single SDK call per platform) | ✅ (known JS-shim pattern) |
| Right-sized | ✅ (~1-2 days) | ✅ (~2-3 days) |
| Testable | ✅ (boolean + observable window) | ✅ (listener callback assertions) |

Both stories pass all six INVEST criteria. Total estimated effort: 3-5 days for both.
