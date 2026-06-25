---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: Swing WebView Component & Mode Selection

## R · Requirements
- Provide an abstract Swing component (`WebViewComponent extends
  JComponent`) that exposes a uniform WebView embedding API
  regardless of whether the underlying implementation is a
  heavyweight peer or an offscreen-rendered lightweight component
  (`WebViewComponent.java:42`).
- Public abstract API: `setUrl`/`getUrl`, `setDebug`,
  `addOnBeforeLoad`, `eval`, `evalAsync`,
  `addJavascriptCallback`, `dispatch`, `dispose`,
  `isHeavyweight`
  (`WebViewComponent.java:122`–`WebViewComponent.java:170`).
  The `evalAsync(String js): CompletableFuture<String>` method
  is the embedded counterpart of `WebView.evalAsync` (see
  [[in-process-webview-java-api]]) and MUST be implemented by
  every subclass (currently `WebViewHeavyweightComponent` per
  [[swing-heavyweight-webview-embedding]] and
  `WebViewLightweightComponent` per
  [[swing-lightweight-webview-embedding]]). Future continuations
  on the returned future complete on the Swing Event Dispatch
  Thread, matching the existing `ConsoleListener` and
  `WebViewMouseListener` contracts — this is the
  `marshalToEdt = true` branch of the dispatcher contract
  documented in [[webview-async-javascript-eval]]. The standalone
  in-process `WebView` surface
  ([[in-process-webview-java-api]]) takes the opposite
  `marshalToEdt = false` branch since it has no Swing in
  the picture; the asymmetry is intentional and documented in
  both canvases.
- Public non-abstract API (defaulted on the base class so any
  future subclass inherits without rework):
  - `openDevTools(): boolean` — opens the platform's native
    DevTools / Web Inspector in a separate OS window; returns
    `true` when an inspector window was actually opened, `false`
    when unsupported, disabled, or not yet displayed. Default
    implementation on `WebViewComponent` returns `false`; the
    two first-party subclasses override.
  - `addConsoleListener(ConsoleListener)` /
    `removeConsoleListener(ConsoleListener)` /
    `setConsoleOutput(PrintStream)` /
    `getConsoleOutput(): PrintStream` — structured JavaScript
    `console.*` capture. Default implementations on
    `WebViewComponent` delegate to a per-component
    `ConsoleDispatcher` (see Entities) so every subclass
    inherits the API for free; subclasses only need to install
    the JS shim and route the internal `__webview_console__`
    binding into `ConsoleDispatcher.dispatch(rawPayload)`.
- `WebViewComponent.create()` chooses the right implementation for
  the platform automatically: heavyweight on macOS / Windows,
  lightweight on Linux (`WebViewComponent.java:71`,
  `WebViewComponent.java:90`).
- Callers may override the default by setting the system property
  `ca.weblite.webview.mode` to `"heavyweight"`/`"heavy"` or
  `"lightweight"`/`"light"` (case-insensitive); unrecognised values
  fall back to the platform default and emit a warning to STDERR
  (`WebViewComponent.java:93`).
- `WebViewComponent.create(Mode mode)` lets callers pick
  explicitly without going through the property
  (`WebViewComponent.java:76`).
- Definition of Done: documented in `README.md ("Choosing a mode" section)` ("Embedding
  WebView Directly in Swing") and exercised by the
  `WebViewHeavyweightDemo` (`demos/WebViewHeavyweightDemo/...`).
  No automated tests cover this factory.

## E · Entities
- **WebViewComponent.Mode** (`WebViewComponent.java:45`) — enum
  with two values:
  - `HEAVYWEIGHT` — native peer as a heavyweight AWT child. See
    [[swing-heavyweight-webview-embedding]] for the
    implementation invariants.
  - `LIGHTWEIGHT` — offscreen-rendered, painted into a regular
    Swing component. See
    [[swing-lightweight-webview-embedding]].
- **WebViewComponent** (`WebViewComponent.java:42`) — abstract
  base extending `JComponent`. Subclasses must implement every
  abstract method; `isHeavyweight()` defaults to `false` and is
  overridden to `true` only by
  `WebViewHeavyweightComponent.isHeavyweight()`
  (`WebViewHeavyweightComponent.java:69`).
- **MODE_PROPERTY** (`WebViewComponent.java:58`) — public
  string constant `"ca.weblite.webview.mode"` used as the system
  property name.
- **ConsoleMessage** (new, public, immutable; package
  `ca.weblite.webview`). Read-only fields/accessors:
  - `level: ConsoleMessage.Level` — one of
    `LOG, INFO, WARN, ERROR, DEBUG`.
  - `text: String` — the space-joined JS-side stringified
    arguments of the `console.*` call. May be empty for
    zero-arg calls.
  - `sourceUrl: String` — URL of the JS source that issued the
    call, or `null` when not detectable (anonymous inline
    script, `eval`, missing stack frame).
  - `lineNumber: int` — line within `sourceUrl`, or `-1` when
    not detectable.
  - `toString(): String` — canonical formatted line; see Norms
    for the exact format.
- **ConsoleMessage.Level** (new, public nested enum) — closed
  set `LOG, INFO, WARN, ERROR, DEBUG`. One value per intercepted
  `console.*` method; `trace`/`assert`/`table`/`group` are
  explicitly out of scope.
- **ConsoleListener** (new, public functional interface;
  package `ca.weblite.webview`). Single method
  `onMessage(ConsoleMessage)`.
- **ConsoleDispatcher** (new, **public but documented as
  internal**; package `ca.weblite.webview`). Public because
  the consumer classes live in `ca.weblite.webview.swing` and
  Java has no cross-package-but-non-public access modifier;
  matches the existing "public class, de-facto internal"
  pattern used by `EmbeddedWebView`, `OffscreenWebView`, and
  `WebViewNativeCallback`. Class-level Javadoc must flag it as
  internal and direct users to the public
  `WebViewComponent.addConsoleListener` / `setConsoleOutput`
  surface. Per-component fan-out hub owned by every
  `WebViewComponent` instance. Invariants:
  - Holds a `List<ConsoleListener>` and at most one
    `PrintStream` output sink (`null` when unset).
  - Snapshots the listener list before each fan-out so a
    listener mutating the list during `onMessage` only takes
    effect for the NEXT message (matches the "removal takes
    effect for the next message" non-functional requirement).
  - Marshals every fan-out onto the EDT via
    `SwingUtilities.invokeLater`, or runs inline when already
    on the EDT.
  - Wraps each individual listener invocation in try/catch;
    a thrown exception is routed through
    `Thread.getDefaultUncaughtExceptionHandler` (or printed to
    STDERR if none) and the remaining listeners still receive
    the message.
  - Exposes `dispatch(String rawPayload)` for the subclass
    bridge to call from any thread; the dispatcher parses the
    canonical payload format (see Norms) into a
    `ConsoleMessage` and runs the fan-out as above.

## A · Approach
- **Platform default has rationale baked in.** Linux is forced to
  lightweight because the heavyweight path on WebKitGTK reparented
  under a foreign-toolkit X11 parent has unreliable visible
  text-input feedback (caret blink, characters as typed). See
  `README.md ("Platform support" section)` and the inline comment at
  `WebViewComponent.java:106`. macOS/Windows get heavyweight
  because the lightweight path is currently a stub on those
  platforms (`README.md ("Platform support" section)`, `WebViewLightweightComponent.java:43`).
- **Override is a system property, not a constructor flag.** A
  property keeps the call sites in callers identical across all
  platforms — `WebViewComponent.create()` — while still letting
  ops/devs flip the toggle from launchers
  (`README.md ("Quick start" section)`).
- **Configuration replay.** The abstract API allows callers to
  call `setUrl`, `addOnBeforeLoad`, `addJavascriptCallback`
  BEFORE the component is displayable; each concrete subclass is
  responsible for buffering and replaying on attach. The factory
  itself stays free of any such state.
- **DevTools is platform-owned; we don't dock it.** Every
  supported engine ships its own inspector window — Safari Web
  Inspector on macOS, WebKitGTK Web Inspector on Linux,
  Chromium DevTools on Windows. `openDevTools()` returns a
  boolean rather than a handle because the inspector is the
  platform's window and we don't attempt to skin, reparent, or
  dock it inside the Swing UI. macOS specifically has no public
  API to programmatically pop the inspector — the contract is
  to enable the developer-extras / `isInspectable` flags so
  right-click → Inspect Element and the Safari Develop menu
  both work, and to return `false` from `openDevTools()` to
  reflect that no programmatic open happened.
- **Console capture rides the existing message bridge.** Every
  engine already runs a `webkit_user_script` /
  `WKUserScript` / `AddScriptToExecuteOnDocumentCreated` shim
  that installs `window.external.invoke` and a paired
  script-message handler. The console-capture pipeline is the
  same pattern with a reserved internal binding name
  (`__webview_console__`) and a small JS shim that wraps the
  five `console.*` methods to (a) still call the original
  implementation it captured by closure (so the platform's
  native DevTools Console panel and stdout sinks keep
  receiving output untouched), (b) post a canonical
  pipe-separated payload (see Norms) through the binding, and
  (c) suppress shim re-entry so a listener calling
  `console.log` doesn't loop.  In addition the shim
  subscribes to `window`'s `error` and `unhandledrejection`
  events and emits them as `ERROR`-level `ConsoleMessage`s
  so uncaught script errors and rejected promises surface
  through the same listener pipeline (they bypass the
  console.* wrappers because the engine's internal error
  reporter doesn't go through `window.console.error()`).
- **EDT marshaling for listeners.** Native callbacks land on
  whichever native UI thread the engine runs on (AppKit main
  on macOS, GTK pump thread on Linux heavyweight and offscreen,
  the dedicated WebView2 worker on Windows). `ConsoleDispatcher`
  is the one place that hops to the EDT, so every listener
  receives `onMessage` on the EDT regardless of which
  subclass produced the message — matches Swing convention
  and lets listeners touch Swing state directly.
- **`evalAsync` is part of the embedding API surface and
  delegates per-subclass to the engine wrapper's `evalAsync`.**
  Heavyweight calls into the backing `EmbeddedWebView.evalAsync`
  (see [[swing-heavyweight-webview-embedding]]); lightweight
  calls into the backing `OffscreenWebView.evalAsync` (see
  [[swing-lightweight-webview-embedding]]). The per-engine
  `EvalDispatcher` instance owned by the backing wrapper is
  constructed with `marshalToEdt = true` and the appropriate
  `disposeLabel`, so future-continuation callbacks
  (`.thenAccept`, `.thenApply`, `.exceptionally`, `.handle`)
  land on the EDT. This matches the existing `ConsoleListener`
  and `WebViewMouseListener` EDT contracts on this canvas, so
  callers can touch Swing state directly from continuations
  without an additional thread hop. The dispatcher class, JS
  shim contract, and `JavaScriptEvalException` type are owned
  by [[webview-async-javascript-eval]].

## S · Structure
- `src/ca/weblite/webview/swing/WebViewComponent.java` — abstract
  class, `Mode` enum, factory, platform/property resolution.
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  — heavyweight subclass; implementation details in
  [[swing-heavyweight-webview-embedding]].
- `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`
  — lightweight subclass; implementation details in
  [[swing-lightweight-webview-embedding]].
- `src/ca/weblite/webview/ConsoleMessage.java` (new) — public
  immutable value type + nested `Level` enum.
- `src/ca/weblite/webview/ConsoleListener.java` (new) — public
  functional interface.
- `src/ca/weblite/webview/ConsoleDispatcher.java` (new, public
  but Javadoc-marked internal) — listener registry, EDT
  marshaling, payload parsing. Owns the canonical JS shim
  source string as a compile-time constant
  `ConsoleDispatcher.SHIM_JS` so each subclass installs the
  same shim by referencing one location.
- `README.md ("Choosing a mode" section)` — user-facing platform/mode matrix and override
  documentation.
- `src/ca/weblite/webview/EvalDispatcher.java` — owned by
  [[webview-async-javascript-eval]]. Both subclasses depend on
  it for `evalAsync`; this canvas only declares the abstract
  method signature.
- `src/ca/weblite/webview/JavaScriptEvalException.java` — owned
  by [[webview-async-javascript-eval]]. Surfaces in the
  exceptional completion of `evalAsync` futures returned by
  either subclass when the JS side fails (sync throw, Promise
  rejection, serialization failure).

## O · Operations

### 1. Define Mode Enum — WebViewComponent.Mode
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: enumerate the two embedding strategies.
2. Fields:
   - `HEAVYWEIGHT, LIGHTWEIGHT` (`WebViewComponent.java:49`).
3. Constraints / Invariants:
   - Order is API: `valueOf` and `values()` must keep returning
     these names for callers that persist the choice.

### 2. Resolve Default Platform Mode — WebViewComponent.resolveDefaultMode
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: pick a `Mode` based on the system property
   override and, failing that, the host OS.
2. Methods:
   - `resolveDefaultMode(): Mode`
     - Logic: read the system property `MODE_PROPERTY`, trim,
       lowercase. If `"heavyweight"`/`"heavy"` return
       `HEAVYWEIGHT`; if `"lightweight"`/`"light"` return
       `LIGHTWEIGHT`; otherwise if non-empty emit a STDERR
       warning and fall through (`WebViewComponent.java:93`).
       Read `os.name` and return `LIGHTWEIGHT` when it contains
       `"linux"`, `"nix"`, or `"nux"`
       (`WebViewComponent.java:109`); otherwise return
       `HEAVYWEIGHT`.
3. Constraints / Invariants:
   - Property check is case-insensitive and accepts both full
     names and the abbreviations `heavy`/`light`
     (`WebViewComponent.java:95`).
   - Unrecognised property values log a warning to STDERR but
     never throw — the platform default kicks in
     (`WebViewComponent.java:100`).
   - The Linux substring check covers `"linux"`, generic
     `"nix"`, and `"nux"` (Unix variants) — see comment for the
     reason (`WebViewComponent.java:106`).

### 3. Create WebViewComponent — WebViewComponent.create
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: instantiate the concrete subclass for a given
   mode (or the resolved default).
2. Methods:
   - `create(): WebViewComponent` — delegate to
     `create(resolveDefaultMode())` (`WebViewComponent.java:71`).
   - `create(Mode mode): WebViewComponent`
     - Logic: switch on `mode`. `HEAVYWEIGHT` →
       `new WebViewHeavyweightComponent()`; `LIGHTWEIGHT` →
       `new WebViewLightweightComponent()`; default throws
       `IllegalArgumentException` (`WebViewComponent.java:77`).
3. Constraints / Invariants:
   - The factory is the only blessed entry point; constructing
     either subclass directly is allowed (per
     `README.md ("Choosing a mode" section)`) but loses platform-aware defaulting.

### 4. Declare Embedding API Surface
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: define the abstract contract every embedding
   implementation must honour.
2. Methods:
   - `setUrl(String url): WebViewComponent` — buffered before
     display, applied immediately when live
     (`WebViewComponent.java:123`).
   - `getUrl(): String` — returns the pending or current URL
     (`WebViewComponent.java:126`).
   - `setDebug(boolean debug): WebViewComponent` — must be
     called BEFORE display; subclasses throw
     `IllegalStateException` if called later
     (`WebViewComponent.java:129`).
   - `addOnBeforeLoad(String js): WebViewComponent` — buffered
     and replayed on attach (`WebViewComponent.java:135`).
   - `eval(String js): WebViewComponent` — no-op until
     displayable (`WebViewComponent.java:141`).
   - `evalAsync(String js): CompletableFuture<String>` — run
     `js` and yield a future that completes with the
     JSON-stringified result (`undefined → null`, Promise-
     awaiting). When called before the component is displayed
     (engine not yet alive), the returned future is already
     completed exceptionally with `IllegalStateException`; no
     native call is issued. When the JS side throws or returns a
     rejecting Promise, the future completes exceptionally with
     `JavaScriptEvalException` carrying the JS-side message.
     Future continuations complete on the Swing EDT
     (`marshalToEdt = true` branch of
     [[webview-async-javascript-eval]]), matching the existing
     `ConsoleListener` and `WebViewMouseListener` contracts.
     The exact dispatcher wiring, JS shim, exception type, and
     lifecycle of pending futures on dispose are all owned by
     [[webview-async-javascript-eval]]; the concrete subclass
     implementations are owned by
     [[swing-heavyweight-webview-embedding]] and
     [[swing-lightweight-webview-embedding]] respectively.
     `evalAsync` is safe to call from any Java thread. The user
     snippet must use `return` to yield a value — the wrapper
     wraps it in an IIFE; a bare expression is not the IIFE's
     return value. See [[webview-async-javascript-eval]] for the
     full JS contract.
   - `addJavascriptCallback(String name, WebView.JavascriptCallback cb): WebViewComponent`
     — buffered and replayed on attach
     (`WebViewComponent.java:148`).
   - `dispatch(Runnable r): WebViewComponent` — marshal `r`
     onto the native WebView's UI thread. No-op until the
     component is displayable; transient work is not
     buffered. Both the heavyweight and lightweight subclasses
     delegate to the underlying engine
     (`EmbeddedWebView.dispatch` /
     `OffscreenWebView.dispatch`).
   - `dispose(): void` — release native resources; component is
     unusable afterward (`WebViewComponent.java:157`).
   - `isHeavyweight(): boolean` — defaults to `false`; only
     `WebViewHeavyweightComponent` overrides to `true`
     (`WebViewComponent.java:118`).
3. Constraints / Invariants:
   - "Pending configuration is applied as soon as the component
     becomes displayable" is contract, not implementation
     detail — every subclass must satisfy it
     (`WebViewComponent.java:38`).
   - `setDebug` is only valid before display because the native
     engines bake the debug flag into the peer at creation time
     (e.g. `EmbeddedWebView.attach(canvas, debug)` at
     `EmbeddedWebView.java:57`).

### 5. Declare DevTools + Console API Surface
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: declare the developer-visibility API and
   provide a default ConsoleDispatcher-backed implementation
   for the console-listener methods so subclasses inherit the
   API automatically.
2. Methods (all non-abstract on `WebViewComponent`):
   - `openDevTools(): boolean`
     - Default logic: return `false`. Subclasses override:
       heavyweight delegates to
       `EmbeddedWebView.openDevTools()`; lightweight delegates
       to `OffscreenWebView.openDevTools()`. Both subclass
       implementations return `false` when the native peer
       does not yet exist (i.e. component not displayed) or
       when the native call returned 0.
   - `addConsoleListener(ConsoleListener listener): void`
     - Logic: delegate to the component's
       `ConsoleDispatcher.addListener(listener)`. Null
       listeners must be rejected with
       `NullPointerException`.
   - `removeConsoleListener(ConsoleListener listener): void`
     - Logic: delegate to
       `ConsoleDispatcher.removeListener(listener)`. Removing
       an unregistered listener is silently ignored.
   - `setConsoleOutput(PrintStream stream): void`
     - Logic: delegate to
       `ConsoleDispatcher.setOutputStream(stream)`. Passing
       `null` clears the redirect. The stream's charset is
       respected as-is — the dispatcher writes through
       `PrintStream.print(String) + println()`, never wraps
       it in an `OutputStreamWriter`.
   - `getConsoleOutput(): PrintStream`
     - Logic: delegate to
       `ConsoleDispatcher.getOutputStream()`. Returns `null`
       when never set or after a `setConsoleOutput(null)`
       call.
   - `addJavascriptCallback(String name, ...)` — overrides
     must reject reserved names: any name starting with the
     prefix `__webview_` throws
     `IllegalArgumentException("name is reserved for internal
     use: ...")`. This guards the internal
     `__webview_console__` channel and any future internal
     channels under the same prefix.
3. Constraints / Invariants:
   - The four console-listener methods MUST NOT change the
     observable order of message delivery for a given
     subscriber: messages arrive in source order, listener
     fan-out is in registration order, and the same message
     reaches every then-registered listener exactly once.
   - `openDevTools` is idempotent: repeated calls while the
     peer is alive are safe and return whatever the native
     call returned each time. Each subclass must marshal
     the underlying native call asynchronously where required
     (Windows: WebView2 worker thread; macOS: AppKit main)
     so calling from the EDT never blocks beyond a normal
     native UI dispatch.

## N · Norms
- The system property name `ca.weblite.webview.mode` is part of
  the public surface — do not rename it. Document any new
  property values in `README.md ("Choosing a mode" section)`.
- The lightweight↔heavyweight platform matrix lives in
  `README.md ("Platform support" section)` and must be kept in sync with what
  `resolveDefaultMode` actually returns and what each subclass
  actually supports.
- The `__webview_` prefix on JS binding names is reserved for
  this library's internal channels — currently
  `__webview_console__`. Callers attempting to bind a name in
  this namespace MUST be rejected with
  `IllegalArgumentException`. New internal channels MUST use
  the same prefix.
- The canonical `ConsoleMessage.toString()` and
  `PrintStream` line format is
  `[<LEVEL>] <source>:<line> <text>` terminated by `\n`. When
  `sourceUrl == null` use the literal substitution `<unknown>`
  for the source portion; when `lineNumber == -1` omit the
  `:<line>` suffix entirely (so a message with both unknown
  collapses to `[LOG] <unknown> hello`). This format is part
  of the public contract — callers grep these lines from log
  pipelines. Any change is a breaking change.
- The JS → native payload format for the
  `__webview_console__` channel is pipe-separated with a
  length-prefixed text body, exactly as:
  `<level>|<sourceUrl>|<lineNumber>|<textLength>|<text>`.
  Field semantics:
  - `<level>` is one of the five strings `LOG`, `INFO`,
    `WARN`, `ERROR`, `DEBUG` (uppercase, matching the enum
    names).
  - `<sourceUrl>` is the raw URL string with no escaping, or
    the empty string when not detectable. Pipes inside a URL
    are not possible per RFC 3986 — no escaping required.
  - `<lineNumber>` is a decimal integer or the literal
    string `-1`.
  - `<textLength>` is the byte length of the UTF-8 encoding
    of `<text>`.
  - `<text>` follows verbatim for exactly `<textLength>`
    bytes; may contain pipes, newlines, or anything else.
  This format is deliberately not JSON to avoid a JSON parser
  on the Java side and to be robust against pipes / newlines /
  control characters inside log messages.
- `ConsoleDispatcher.SHIM_JS` is the single source of truth
  for the injected JS shim. If you change the payload format
  above, update the shim source AND the parser in
  `ConsoleDispatcher` in the same commit.
- Listener callbacks MUST be invoked on the EDT. The
  dispatcher is the only place that enforces this; subclass
  bridges call `ConsoleDispatcher.dispatch` from native
  threads without thread-checking.

## S · Safeguards
- Unrecognised system property values are warned about but never
  fatal (`WebViewComponent.java:100`). This avoids breaking
  callers when typo'd or when a future value is unknown to an
  older library version.
- The Linux-default-to-lightweight decision is hardcoded with a
  written comment explaining the underlying GTK/X11 frame-clock
  reason (`WebViewComponent.java:106`) so future maintainers
  understand it isn't arbitrary.
- The default `isHeavyweight()` returning `false` means an
  unknown future subclass is treated as lightweight by callers
  that branch on this — safer assumption since it implies
  "behaves like a normal Swing component."
- The `__webview_` reserved-name reject in
  `addJavascriptCallback` MUST fire before any state is
  mutated — no partial registration on rejection. The exception
  message MUST name the offending prefix so callers can
  recognise the cause without reading source.
- A listener throwing from `onMessage` MUST NOT prevent
  subsequent listeners from receiving the same message.
  `ConsoleDispatcher` wraps every `onMessage` call in try/catch
  and routes the exception through
  `Thread.getDefaultUncaughtExceptionHandler()`. If no handler
  is installed, the exception's stack trace is printed to
  STDERR. This matches Swing convention for event-listener
  exceptions.
- `setConsoleOutput(null)` MUST be idempotent — calling it
  when no stream is set is a no-op, not an error. Same for
  `removeConsoleListener` on an unregistered listener.
- `PrintStream` write failures inside the redirect listener
  MUST NOT propagate. `PrintStream` itself swallows
  `IOException` from its underlying writer, but the dispatcher
  also wraps the call in try/catch as a belt-and-braces guard
  against subclasses of `PrintStream` that re-throw.
- The default `openDevTools()` on `WebViewComponent` returns
  `false` rather than throwing `UnsupportedOperationException`
  so a future subclass that doesn't override the method still
  behaves consistently with the macOS "enabled but not
  programmatically openable" semantics.
- The abstract `evalAsync(String): CompletableFuture<String>`
  signature on `WebViewComponent` is part of the binary
  contract. Future extensions (variants, options, typed
  result wrappers) MUST be added as new methods or overloads —
  modifying the existing signature is a
  binary-compatibility-breaking change for every host
  application compiled against today's library and is forbidden.
  The same rule applied to `addConsoleListener` /
  `addWebViewMouseListener` (added as new methods, never by
  altering an existing one) is the precedent.
- The embedded surface marshals `evalAsync` future completions
  to the EDT (the `marshalToEdt = true` branch of
  [[webview-async-javascript-eval]]). The standalone
  in-process `WebView` surface ([[in-process-webview-java-api]])
  does NOT — that's the `marshalToEdt = false` branch. Both
  are correct in context; the asymmetry is documented in both
  canvases. Callers wanting EDT delivery from the standalone
  surface arrange it themselves via
  `.thenAcceptAsync(continuation, SwingUtilities::invokeLater)`.
- Calling `evalAsync` before the component is displayed MUST
  return an already-failed future whose cause is an
  `IllegalStateException` and MUST NOT issue a native call.
  Concrete subclasses implement this per their own
  peer-creation lifecycle: heavyweight checks the `embedded`
  field
  ([[swing-heavyweight-webview-embedding]]); lightweight
  checks the `engine` field
  ([[swing-lightweight-webview-embedding]]).
  Symmetric with the existing pre-display contract for `eval`
  ("no-op until displayable") except that `evalAsync` cannot
  silently no-op — callers expect a future to complete one way
  or another.

## Addendum · Async JavaScript Functions integration (see [[webview-async-javascript-functions]])

Wires the value-returning JS→Java function API (Canvas 14:
`FunctionDispatcher`, `JavascriptFunction`, `AsyncJavascriptFunction`)
into the abstract `WebViewComponent` (two abstract `addJavascriptFunction` overloads; concretes delegate to their engine), mirroring the existing `EvalDispatcher`/`evalAsync`
integration. No native changes.

- **Construct.** the abstract `WebViewComponent` (two abstract `addJavascriptFunction` overloads; concretes delegate to their engine) holds a `final FunctionDispatcher`
  constructed with a `FunctionDispatcher.FunctionSink` whose `eval`
  and `addOnBeforeLoad` delegate to the engine wrapper (guarded so they no-op
  when the peer is absent).
- **Install at peer bring-up.** Alongside the eval bridge, install
  `FunctionDispatcher.SHIM_JS` by the concrete subclasses' engines and bind the reserved
  `FunctionDispatcher.INBOUND_CHANNEL` to a `WebViewNativeCallback`
  that routes into `functionDispatcher.dispatch(arg)`. The callback is
  anchored in the surface's `heap` (JNI lifecycle norm) so the native
  global ref stays reachable.
- **Public API.** Two overloads, `addJavascriptFunction(String,
  JavascriptFunction)` and `addJavascriptFunction(String,
  AsyncJavascriptFunction)`, delegate to
  `functionDispatcher.registerSync` / `registerAsync`. The reserved
  `__webview_` prefix and JS-identifier validity are enforced by the
  dispatcher (the Swing components additionally fast-fail the reserved
  prefix at the call site, matching `addJavascriptCallback`).
- **Teardown.** `functionDispatcher.disposeAll()` is called from the
  surface's existing dispose path (next to
  `evalDispatcher.disposeAllPending()`), shutting down the worker pool.
- **Out of scope:** `FunctionDispatcher` and the two functional
  interfaces themselves — owned by [[webview-async-javascript-functions]].
