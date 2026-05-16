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

## S · Structure
- `src/ca/weblite/webview/WebView.java` — the public API class.
- `src/ca/weblite/webview/WebViewNative.java` — JNI surface
  (`webview_create`, `webview_run`, `webview_navigate`, etc.)
  invoked from `WebView`.
- `src/ca/weblite/webview/WebViewNativeCallback.java` — native
  callback interface invoked by the zserge engine for each bound
  JavaScript callback.

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
   configuration, navigate, and run the blocking event loop.
2. Methods:
   - `show(): void`
     - Logic: assign
       `peer = WebViewNative.webview_create(0, 0)`
       (`WebView.java:228`); set bounds via
       `webview_set_bounds(peer, 0, 0, w, h, 0)`
       (`WebView.java:229`); replay each init JS via
       `webview_init(peer, js)` (`WebView.java:230`); set the title
       via `webview_set_title(peer, title)` (`WebView.java:233`);
       for every binding, create a `WebViewNativeCallback`
       lambda, anchor it in `heap`, and call
       `webview_bind(peer, key, fn, peer)`
       (`WebView.java:234`–`WebView.java:243`); navigate via
       `webview_navigate(peer, url)` (`WebView.java:244`); finally
       call `webview_run(peer)` (`WebView.java:245`), which blocks
       until the window closes.
3. Constraints / Invariants:
   - On macOS, the calling JVM **must** have been started with
     `-XstartOnFirstThread` or this method will fail / misbehave;
     callers are responsible for arranging this themselves (e.g. by
     relaunching the JVM with the flag before invoking `show()`).
   - `show()` is one-shot — after the event loop returns the native
     peer is invalid. There is no `close()` or `destroy()` exposed
     here; closing the window destroys the peer natively and the
     `WebView` instance becomes effectively dead.

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
