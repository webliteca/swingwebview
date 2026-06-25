---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: In-Process WebView Java API

## R · Requirements
- Provide a fluent Java API (`ca.weblite.webview.WebView`) to open a
  top-level native browser window in the current process, configure
  it, register JavaScript callbacks, evaluate JS, and run its event
  loop (`WebView.java:41`).
- Callers must be able to set: URL, window title, size (width x
  height), resizable flag, and one or more `addOnBeforeLoad(js)`
  snippets that run on every navigation (`WebView.java:60`,
  `WebView.java:105`, `WebView.java:126`, `WebView.java:141`,
  `WebView.java:83`).
- Callers must be able to register named JavaScript callbacks that
  appear under `window.<name>` in the browser and receive a single
  string argument (`WebView.java:176`).
- Callers must be able to run arbitrary JavaScript via `eval(js)`
  after the window is shown (`WebView.java:201`) and dispatch
  arbitrary `Runnable`s onto the WebView's UI thread
  (`WebView.java:213`).
- Callers must be able to run JavaScript with a future-returning
  round-trip via `evalAsync(String js): CompletableFuture<String>`,
  yielding the JSON-stringified result (with `undefined → null`,
  Promise-awaiting, and JS-side errors surfaced as exceptional
  completions carrying a `JavaScriptEvalException`). The dispatcher
  implementation, JS shim contract, and exception type are owned by
  [[webview-async-javascript-eval]]; this canvas owns only the
  `WebView`-side surface (the public method, the per-instance
  dispatcher field, and the eager registration of the shim and the
  resolver binding inside `show()`). Threading: continuations
  complete inline on the WebView's native UI thread (the same
  thread that `dispatch(Runnable)` callbacks land on); callers
  needing EDT delivery wrap with
  `.thenAcceptAsync(continuation, SwingUtilities::invokeLater)`.
  This is the `marshalToEdt = false` branch of the dispatcher
  contract documented in [[webview-async-javascript-eval]].
- `show()` is the terminal call: it creates the native peer, applies
  all queued configuration, navigates to the URL, then enters the
  WebView's event loop, blocking until the window closes
  (`WebView.java:227`).
- Usage example: the class Javadoc (`WebView.java:33`); the README no
  longer documents this entry point (Swing embedding via
  [[swing-webview-component-mode-selection]] is the recommended path).
- Definition of Done: no automated tests cover this class directly;
  the class is exercised indirectly through the Swing embedding
  components that wrap the same native peer.
- Known limitation documented in the class Javadoc
  (`WebView.java:33`): `show()` blocks the calling thread, the
  WebView wants to run on the main thread, and it does not play
  nicely with other GUI toolkits. Callers needing Swing integration
  must use the [[swing-webview-component-mode-selection]] embedding
  API instead.

## E · Entities
- **WebView** (`WebView.java:41`) — the high-level wrapper around a
  zserge-style WebView native peer. Holds a `long peer` (native
  pointer, 0 until `show()` is called — `WebView.java:45`), buffered
  configuration (URL, title, size, resizable, fullscreen — defaults
  at `WebView.java:48`–`WebView.java:52`), pending init scripts
  (`onBeforeLoad: List<String>` — `WebView.java:53`), and the binding
  map `bindings: Map<String, JavascriptCallback>`
  (`WebView.java:54`).
- **WebView.JavascriptCallback** (`WebView.java:69`) — a functional
  interface with `void run(String arg)`. Implementations receive a
  JSON-array argument string assembled by the zserge native engine
  from the actual JS-side call arguments.
- **heap** (`WebView.java:167`) — `ArrayList` used to anchor native
  callback objects so the JVM does not garbage-collect them while
  the native side still holds a function pointer. Anything passed
  across the JNI boundary that may be invoked later (JS callbacks,
  dispatch Runnables) must be added to `heap`.
- **evalDispatcher: final EvalDispatcher** (new) — per-instance
  fan-out hub for `evalAsync` round-tripping. Constructed in the
  `WebView` constructor with `marshalToEdt = false` and
  `disposeLabel = "WebView"`. Its `EvalSink` lambda is a two-line
  closure over `this` that issues
  `WebViewNative.webview_eval(peer, wrappedJs)` when `peer != 0`,
  silently no-ops otherwise (the `peer == 0` case is handled
  earlier by `evalAsync` itself, so the sink-level guard is
  belt-and-suspenders). The dispatcher's full semantics — in-flight
  `id → CompletableFuture<String>` map, JS wrapper template,
  base64-decoded payload parsing, dispose drain — are owned by
  [[webview-async-javascript-eval]]; this canvas owns only the
  fact that `WebView` constructs one with these specific
  parameters.

## A · Approach
- **Two-phase configuration.** Setter methods buffer their values
  when `peer == 0` and apply them immediately when `peer != 0`
  (`WebView.java:105` for URL, `WebView.java:126` for title,
  `WebView.java:176` for bindings, `WebView.java:83` for init JS).
  `show()` is the moment the peer is created and all buffered state
  is flushed into the native side.
- **Direct JNI.** Every operation routes through the static
  `WebViewNative` JNI entry points — there is no abstraction layer
  between this class and the zserge engine. See
  [[native-library-loading-and-packaging]] for how the underlying
  `webview` shared library is loaded.
- **Single event loop, single thread.** `show()` calls
  `WebViewNative.webview_run(peer)` and never returns until the
  window closes (`WebView.java:245`). Concurrent Java work must be
  marshalled via `dispatch(Runnable)` (`WebView.java:213`).
- **Future-returning eval delegates to EvalDispatcher.**
  `evalAsync(String js)` short-circuits with an already-failed
  future when `peer == 0` (covers the pre-`show()` and
  post-window-close cases) and otherwise hands `js` straight to
  `evalDispatcher.evalAsync(js)`. The dispatcher inserts a future
  into its in-flight map, wraps the snippet, and invokes the
  injected `EvalSink` lambda, which in turn issues
  `WebViewNative.webview_eval(peer, wrappedJs)`. When the JS shim
  posts the result back through the
  `__webview_eval_result__` binding, the dispatcher's
  registered callback (see Operation §5) routes it to the
  matching future. See [[webview-async-javascript-eval]] for the
  dispatcher contract.

## S · Structure
- `src/ca/weblite/webview/WebView.java` — the public API class.
- `src/ca/weblite/webview/WebViewNative.java` — JNI surface
  (`webview_create`, `webview_run`, `webview_navigate`, etc.)
  invoked from `WebView`.
- `src/ca/weblite/webview/WebViewNativeCallback.java` — native
  callback interface invoked by the zserge engine for each bound
  JavaScript callback.
- `src/ca/weblite/webview/EvalDispatcher.java` — owned by
  [[webview-async-javascript-eval]]. `WebView` depends on it
  for `evalAsync`, via constructor injection of an `EvalSink`
  lambda.
- `src/ca/weblite/webview/JavaScriptEvalException.java` — owned
  by [[webview-async-javascript-eval]]. Surfaces in the
  exceptional completion of `evalAsync` futures when the JS side
  fails (sync throw, Promise rejection, serialization failure).

## O · Operations

### 1. Configure WebView Fluent Setters
File: `src/ca/weblite/webview/WebView.java`

1. Responsibility: collect window/runtime configuration prior to
   `show()`, and propagate to the native peer if it already exists.
2. Methods:
   - `url(String url): WebView`
     - Logic: store `this.url = url`. If `peer != 0` also call
       `WebViewNative.webview_navigate(peer, url)`
       (`WebView.java:106`).
   - `title(String title): WebView`
     - Logic: store and, when live, call
       `WebViewNative.webview_set_title(peer, title)`
       (`WebView.java:126`).
   - `size(int w, int h): WebView`
     - Logic: store width/height. The native peer is only resized via
       `setBounds` in `show()` — runtime resize after `show()` is not
       wired here (`WebView.java:141`).
   - `resizable(boolean b): WebView`
     - Logic: store on `resizable` field; not currently forwarded to
       native (the native peer's `webview_set_bounds` flags are
       always `0` — `WebView.java:229`). `[DRIFT]` from the
       documented API: the flag has no native effect today.
   - `addOnBeforeLoad(String js): WebView`
     - Logic: if `peer != 0`, immediately call
       `WebViewNative.webview_init(peer, js)`; otherwise append to
       `onBeforeLoad` (`WebView.java:83`).
3. Constraints / Invariants:
   - All setters return `this` for chaining.
   - Pre-`show()` configuration is buffered; post-`show()` setters
     apply immediately.

### 2. Bind Javascript Callback — WebView.addJavascriptCallback
File: `src/ca/weblite/webview/WebView.java`

1. Responsibility: expose a Java callable as `window.<name>(arg)` in
   the running page.
2. Methods:
   - `addJavascriptCallback(String name, JavascriptCallback callback): WebView`
     - Logic: put `name → callback` into `bindings`
       (`WebView.java:178`). If `peer != 0`, construct a
       `WebViewNativeCallback` that looks up `bindings.get(name)` and
       calls its `run(arg)`; add the callback object to `heap` to
       prevent GC; call
       `WebViewNative.webview_bind(peer, name, fn, peer)`
       (`WebView.java:181`).
3. Constraints / Invariants:
   - The native callback closes over `name` (not `callback`), so
     replacing a binding by name does work in principle. However the
     `show()` path adds a NEW native callback per binding without
     unbinding the old — `[INFERRED]` re-binding after `show()` will
     leak native callback registrations.

### 3. Evaluate Javascript — WebView.eval
File: `src/ca/weblite/webview/WebView.java`

1. Responsibility: run JavaScript in the current document.
2. Methods:
   - `eval(String js): WebView`
     - Logic: delegate to `WebViewNative.webview_eval(peer, js)`
       (`WebView.java:202`).
3. Constraints / Invariants:
   - Must be called after `show()` has populated `peer`. Calling
     before `show()` results in `WebViewNative.webview_eval` being
     called with `peer == 0` — `[INFERRED]` likely SIGSEGV or no-op
     on the native side.
   - Evaluation is asynchronous and the result is ignored — use a
     JS callback (binding) to round-trip values back.

### 4. Dispatch UI Work — WebView.dispatch
File: `src/ca/weblite/webview/WebView.java`

1. Responsibility: marshal a `Runnable` onto the WebView's UI
   thread.
2. Methods:
   - `dispatch(Runnable r): WebView`
     - Logic: add `r` to `heap` (anti-GC), call
       `WebViewNative.webview_dispatch(peer, () -> { r.run();
       heap.remove(r); }, 0)` (`WebView.java:213`).
3. Constraints / Invariants:
   - The wrapper Runnable removes `r` from `heap` after running so
     short-lived dispatches don't leak.

### 5. Show WebView — WebView.show
File: `src/ca/weblite/webview/WebView.java`

1. Responsibility: create the native peer, apply buffered
   configuration, install the eval-shim and resolver binding for
   `evalAsync`, navigate, run the blocking event loop, and drain
   pending eval futures on return.
2. Methods:
   - `show(): void`
     - Logic: assign
       `peer = WebViewNative.webview_create(0, 0)`
       (`WebView.java:228`); set bounds via
       `webview_set_bounds(peer, 0, 0, w, h, 0)`
       (`WebView.java:229`); install the eval-shim FIRST via
       `webview_init(peer, EvalDispatcher.SHIM_JS)` so the
       installer always runs at document-start before any user
       init script (idempotency is guarded inside the shim itself
       by `window.__webview_eval_installed__`); then replay each
       user init JS via `webview_init(peer, js)`
       (`WebView.java:230`); set the title via
       `webview_set_title(peer, title)` (`WebView.java:233`);
       register the resolver binding by going directly through
       `WebViewNative.webview_bind`: construct a
       `WebViewNativeCallback` lambda whose `invoke(arg, wv)` calls
       `evalDispatcher.dispatch(arg)`, anchor it in `heap` (to
       prevent GC while the native side holds a function pointer),
       and call
       `webview_bind(peer, EvalDispatcher.CHANNEL_NAME, fn, peer)`;
       then for every user binding, create a
       `WebViewNativeCallback` lambda, anchor it in `heap`, and call
       `webview_bind(peer, key, fn, peer)`
       (`WebView.java:234`–`WebView.java:243`); navigate via
       `webview_navigate(peer, url)` (`WebView.java:244`); call
       `webview_run(peer)` (`WebView.java:245`), which blocks
       until the window closes; AFTER `webview_run` returns,
       set `peer = 0L` and call
       `evalDispatcher.disposeAllPending()` so any still-pending
       `evalAsync` futures complete exceptionally with
       `IllegalStateException("WebView disposed")` and any
       subsequent `evalAsync`, `eval`, or `addJavascriptCallback`
       call observes `peer == 0` and behaves as if `show()` had
       never been called.
3. Constraints / Invariants:
   - On macOS, the calling JVM **must** have been started with
     `-XstartOnFirstThread` or this method will fail / misbehave;
     callers are responsible for arranging this themselves (e.g. by
     relaunching the JVM with the flag before invoking `show()`).
   - `show()` is one-shot — after the event loop returns the native
     peer is invalid. There is no `close()` or `destroy()` exposed
     here; closing the window destroys the peer natively and the
     `WebView` instance becomes effectively dead. The
     post-`webview_run` `peer = 0L` plus
     `evalDispatcher.disposeAllPending()` makes this lifecycle
     state observable to all entry points: post-close `eval` /
     `evalAsync` / `addJavascriptCallback` calls become safe
     no-ops or already-failed futures instead of crashing the JVM
     with a SIGSEGV on a freed native handle. This is an
     INCIDENTAL fix to the pre-existing `[INFERRED]` "calling
     `eval` after `show()` returns is likely SIGSEGV" gap noted
     under Operation §3.

### 6. Evaluate Javascript Asynchronously — WebView.evalAsync
File: `src/ca/weblite/webview/WebView.java`

1. Responsibility: submit a JS snippet for evaluation and return
   a `CompletableFuture<String>` that completes with the
   JSON-stringified result, with `undefined → null`, Promise-awaiting,
   and JS-side errors surfaced as exceptional completions. The
   future's continuations run on the WebView's native UI thread.
2. Methods:
   - `evalAsync(String js): CompletableFuture<String>`
     - Logic: `Objects.requireNonNull(js, "js");` — null is a
       programming error, propagate synchronously per
       [[webview-async-javascript-eval]] Norms.
       If `peer == 0L`, allocate a fresh
       `CompletableFuture<String>` and complete it exceptionally
       with `IllegalStateException("WebView not shown")`; return
       it without touching the dispatcher (this covers both
       pre-`show()` and post-window-close states, the latter
       guaranteed by Operation §5's
       post-`webview_run` `peer = 0L`). Otherwise, delegate to
       `evalDispatcher.evalAsync(js)` and return the dispatcher's
       returned future verbatim.
3. Constraints / Invariants:
   - The returned future's continuations
     (`.thenAccept` / `.thenApply` / `.exceptionally` / `.handle`)
     run on whatever native thread the resolver binding fires on
     — the same thread that `dispatch(Runnable)` callbacks land
     on. This is the `marshalToEdt = false` branch of the
     dispatcher contract documented in
     [[webview-async-javascript-eval]]. Callers needing EDT
     delivery must wrap with
     `.thenAcceptAsync(continuation, SwingUtilities::invokeLater)`.
   - `evalAsync` is safe to call from any Java thread; the
     dispatcher's `pending` map is `ConcurrentHashMap`-backed and
     the id sequencer is an `AtomicLong`.
   - The user snippet must use `return` to yield a value (the
     wrapper template wraps it in an IIFE); a bare expression on
     its own line is NOT the IIFE's return value. See
     [[webview-async-javascript-eval]] for the full JS contract.
   - Cancellation: `future.cancel(true)` marks the future
     cancelled but does NOT abort the in-page JS. The eventual
     binding callback finds the cancelled future, removes it
     from the in-flight map, and silently drops the result.

## N · Norms
- Java 8 source/target (`pom.xml:41`). Anonymous-inner-class style
  is used for callbacks even where lambdas would work
  (`WebView.java:181`) to stay debuggable on older JDKs.
- All native interaction goes through `WebViewNative` static
  methods; do not call `System.loadLibrary` from this class — that's
  the responsibility of [[native-library-loading-and-packaging]].
- No logging here; the class is intentionally a thin wrapper over
  the native engine. `[DRIFT]` only in the sense that other classes
  use `Logger`.

## S · Safeguards
- The `heap` field anchors live callbacks so GC cannot collect them
  while the native engine still owns a function pointer
  (`WebView.java:167`, `WebView.java:190`, `WebView.java:214`).
  Without this, the JVM would dereference a freed object on the
  next JS-side invocation.
- `addJavascriptCallback` checks `peer == 0` to decide between
  buffering and immediate bind (`WebView.java:177`) — but the
  buffered branch never adds to `heap` because the `show()` path
  re-creates a fresh callback later (`WebView.java:234`).
- There are NO guards against calling `eval`, `dispatch`, or
  `addJavascriptCallback`(live branch) before `show()`. Callers must
  respect the lifecycle described above.
- `addOnBeforeLoad` is the only safe way to inject "run on every
  page" JS; `eval` only affects the currently loaded document
  (`WebView.java:198`).
- The `evalDispatcher` field is `final` and is held by `WebView` for
  the instance's entire lifetime. Its resolver-binding callback
  (constructed in Operation §5 and added to `heap`) remains
  reachable as long as `heap` is — same anti-GC discipline as
  every other native callback this class registers.
  Forgetting the `heap.add(fn)` step on the resolver-binding
  callback would let the JVM dereference a freed Java object on
  the next eval result; the operation spec mandates it explicitly.
- Post-`show()` lifecycle is now observable to every entry
  point. After `webview_run` returns, Operation §5 zeroes
  `peer` and calls `evalDispatcher.disposeAllPending()`. As a
  result: `eval(js)` becomes a no-op
  (`WebViewNative.webview_eval(0, js)` is not issued — Operation
  §3 should be read in light of this, but for safety future
  edits to `eval` SHOULD add an explicit `if (peer == 0L)
  return this;` early-out); `evalAsync(js)` returns an
  already-failed future; `addJavascriptCallback(name, cb)` falls
  through the `peer == 0` branch and just buffers (a buffer that
  will never be replayed since `show()` is one-shot). This is a
  small `[DRIFT]` correction to the pre-existing
  `[INFERRED] likely SIGSEGV` note under Operation §3 — the new
  peer-zeroing makes the SIGSEGV case unreachable for callers
  who interact with this class only via its public API.
- The standalone `WebView` surface does NOT marshal
  `evalAsync` future completions to the Swing EDT. There is no
  Swing involved in the standalone path and the WebView owns the
  main thread for its event loop. Callers wanting EDT delivery
  arrange it themselves via
  `.thenAcceptAsync(continuation, SwingUtilities::invokeLater)`.
  This is the documented
  [[webview-async-javascript-eval]] `marshalToEdt = false`
  branch; the analogous embedded surfaces
  ([[swing-heavyweight-webview-embedding]] and
  [[swing-lightweight-webview-embedding]]) take the
  `marshalToEdt = true` branch so callers there receive
  continuations on the EDT directly.

## Addendum · Async JavaScript Functions integration (see [[webview-async-javascript-functions]])

Wires the value-returning JS→Java function API (Canvas 14:
`FunctionDispatcher`, `JavascriptFunction`, `AsyncJavascriptFunction`)
into the standalone `WebView`, mirroring the existing `EvalDispatcher`/`evalAsync`
integration. No native changes.

- **Construct.** the standalone `WebView` holds a `final FunctionDispatcher`
  constructed with a `FunctionDispatcher.FunctionSink` whose `eval`
  and `addOnBeforeLoad` delegate to `webview_eval`/`webview_init` (guarded so they no-op
  when the peer is absent).
- **Install at peer bring-up.** Alongside the eval bridge, install
  `FunctionDispatcher.SHIM_JS` in `show()` (after the eval shim, before replaying buffered onBeforeLoad scripts so the base shim precedes per-name wrappers) and bind the reserved
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
