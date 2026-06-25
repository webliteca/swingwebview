# WebViewAsyncCallbackDemo

A runnable answer to the recurring request: *"can `addJavascriptCallback`
return a result to JavaScript?"*

A **synchronous**, value-returning binding is the wrong tool: it would
block the engine's UI thread (AppKit main thread on macOS, the WebView2
worker on Windows, the GTK main thread on Linux) until Java produced the
value, and the moment the Java handler needs the Swing EDT you get a
classic two-thread deadlock — the exact hazard
`requirements/[User-story-4]eliminate-edt-appkit-sync-deadlock-on-macos.md`
was written to remove.

This demo shows the **deadlock-free** alternative: an asynchronous,
**Promise-returning** call. It is the mirror image of
`WebViewComponent.evalAsync` (Java→JS with an async result) run in the
opposite direction (JS→Java with an async result), and it uses **only
the existing public API** — `addJavascriptCallback`, `addOnBeforeLoad`,
and `eval`. No library changes.

## How it works

```
JS:   const r = await window.callJava('reverse', 'abc');   // r === 'cba'
```

1. A document-start shim (installed via `addOnBeforeLoad`) defines
   `window.callJava(method, arg)`. It assigns a request id, stores the
   Promise's `{resolve, reject}`, and posts `<id>|<method>|<arg>`
   (base64) through a normal **void** binding, then returns the Promise.
2. The Java callback (`__rpc_call`) receives the request on the native
   UI thread, immediately hops **off** it onto a background worker
   (`CompletableFuture.supplyAsync`), and runs the actual logic there —
   so the UI thread is never held.
3. When the work finishes, Java calls `eval("window.__rpc_resolve('…')")`
   with the base64 result. `eval` is asynchronous and non-blocking.
4. The shim's `__rpc_resolve` looks up the pending Promise by id and
   settles it.

Nothing ever blocks one thread waiting on the other, so there is no
deadlock. The base64-on-a-single-string-channel convention is copied
verbatim from `ca.weblite.webview.EvalDispatcher`, which is why the demo
needs no JSON-parsing dependency.

## Running

From the repo root:

```
./run-mac-async-callback-demo.sh      # macOS (Intel or Apple Silicon)
./run-linux-async-callback-demo.sh    # Linux (lightweight mode)
```

Override the JDK with `JAVA_HOME=/path/to/jdk ./run-mac-async-callback-demo.sh`.
Each script builds the native lib (if stale), builds `dist/WebView.jar`,
compiles this demo, and launches it.

## What you should see

A split window: the WebView on top (a heading, a text field pre-filled
with `Hello, WebView`, a row of five buttons, and an in-page log box),
and a Java-side log pane below it. Clicking a button logs the round trip
in **both** panes — the in-page log shows the awaited result, the Java
pane shows the thread hops (`bind on AppKit/native thread` →
`worker thread` → `resolve`), which is the visible proof that the work
never runs on, or blocks, the UI thread.

> **Blank window / content slivered to the left edge?** This is a
> heavyweight-component limitation, not a bug in this page (the HTML
> renders perfectly in a normal browser). On macOS the native WKWebView
> attaches *asynchronously*, but `WebViewHeavyweightComponent` only
> positions it on a resize/move event and does **not** re-run its
> `sizeNative()` when the async attach completes. The first (synchronous)
> sizing call no-ops because the native view isn't ready yet, so a simple
> frame that never gets a later resize leaves the WebView at a zero/stale
> frame — hence the blank/strip, and why the symptom varies run to run.
> This demo works around it by nudging the layout a few times in the
> first second after the window appears. The proper fix is in the library
> (re-size on attach completion); if you still see a blank WebView,
> dragging the window edge a few pixels forces it to appear.

Click the buttons and watch the in-page log:

- **reverse** / **upper** — Java returns a transformed string; the
  `await`ed value appears in the log.
- **slowEcho (1.5s)** — Java sleeps 1.5s on the worker thread before
  resolving. The window stays fully responsive the whole time (drag it,
  click other buttons) — the proof that nothing is blocked. A
  synchronous binding would freeze the UI here, or deadlock.
- **fail (rejects)** — the Java handler throws; the Promise rejects and
  the `.catch` fires with the message.
- **5× concurrent** — five overlapping calls; each result returns to its
  own caller (ids keep them from cross-talking), slow ones simply settle
  later.

## Extending to structured results

The channel is string-typed for clarity. For structured data, return
JSON from `invokeMethod(...)` on the Java side and `JSON.parse` it in the
shim's `__rpc_resolve` (the one spot that touches the value). The
transport (ids, base64, the resolve hop) stays identical.

## Making it first-class

If you want this without the boilerplate, the natural library addition is
`addJavascriptCallbackAsync(name, Function<String, CompletableFuture<?>>)`
— the sibling of `evalAsync`, reusing the same `EvalDispatcher`
reserved-channel plumbing. That is a behavior change, so per `CLAUDE.md`
it goes through `/spdd-prompt-update` + `/spdd-generate` against
`spdd/prompt/10-…-Webview-Async-Javascript-Eval.md`, not a hand-edit.
