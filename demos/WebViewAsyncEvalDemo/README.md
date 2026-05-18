# WebViewAsyncEvalDemo

Interactive Swing demo that exercises
`WebViewComponent.evalAsync(String)` — the future-returning JavaScript
evaluation API.

The demo is designed for **manual verification** against the
acceptance criteria in
`requirements/[User-story-3]async-javascript-eval.md`.

## Running

After building `dist/WebView.jar` (e.g. by running any of the
top-level `run-*-demo.sh` / `.bat` scripts once), launch via Ant from
this directory:

```
cd demos/WebViewAsyncEvalDemo
ant run
```

`ant clean` removes the local `build/` output.

## What you should see

A single window with:

- A `WebViewComponent` (top) loading an inline HTML page with a
  scroll region and a paragraph carrying `data-line="42"`.
- A monospaced text area (bottom) showing each future's resolution
  prefixed with the originating button's label and a wall-clock
  millisecond timing.
- A row of buttons across the bottom: each one fires an `evalAsync`
  covering one of the acceptance criteria.

Click each button and watch the log pane.  Example output:

```
[1+2] (3 ms) → 3
[object] (2 ms) → {"a":1,"b":"two"}
[undefined] (2 ms) → null
[Promise] (253 ms) → "done"
[sync throw] (4 ms) ✗ JavaScriptEvalException: foo is not defined   (JavaScriptEvalException as expected)
[reject] (3 ms) ✗ JavaScriptEvalException: boom   (JavaScriptEvalException as expected)
[scroll position] (2 ms) → [0,240]
[selection text] (1 ms) → "Select some text in this paragraph"
[data-line] (1 ms) → "42"
```

## AC mapping (STORY-003-001)

| AC# | How to verify in this demo |
|-----|----------------------------|
| AC1 | Click **1+2** → log shows `[1+2] (~3 ms) → 3` (a JSON-stringified primitive). |
| AC2 | Click **object** → `[object] → {"a":1,"b":"two"}` (exact JSON.stringify output). |
| AC3 | Click **undefined** → `[undefined] → null` (the wrapper coerces `undefined → null`). |
| AC4 | Click **Promise** → log shows `[Promise] (~250 ms) → "done"` — the future waits for the Promise to settle. |
| AC5 | Click **sync throw** → log shows `✗ JavaScriptEvalException: foo is not defined`; the message mentions `foo is not defined` (the engine's `ReferenceError`). |
| AC6 | Click **reject** → log shows `✗ JavaScriptEvalException: boom`. |
| AC7 | Click **pre-display fail** → log shows `isCompletedExceptionally=true` and `cause: IllegalStateException: WebViewComponent not displayed`.  No native call is issued. |
| AC8 | Click **2× concurrent** → log shows `[fast] → "fast"` *before* `[slow] → "slow"`; the `slow` line arrives ~200 ms later.  Neither future receives the other's value. |
| AC9 | Try registering a colliding name yourself: `wv.addJavascriptCallback("__webview_eval_result__", arg -> {})`.  Expected: `IllegalArgumentException` from the existing reserved-prefix guard (no log entry — the demo's button bar doesn't include this; see the `WebViewConsoleDemo` for the same guard's behavior on `__webview_console__`). |
| AC10 | Click **EDT check** → log shows `continuation on EDT? true   thread="AWT-EventQueue-0"` (matching the existing `ConsoleListener` / `WebViewMouseListener` EDT contracts). |
| AC11 | Lightweight / heavyweight on a Swing component always EDT-marshals — this AC is about the standalone `WebView` surface (no Swing involved); use a standalone `WebView` instance in a separate harness to verify continuations run on the WebView's native UI thread instead. |
| AC12 | Click **legacy eval** → page background briefly flashes yellow then clears; log shows `[legacy] eval() fire-and-forget issued`.  Confirms the original `eval(String)` path is unaffected by the new dispatcher. |
| AC13 | Edit the demo to add a button that calls `wv.evalAsync("return document.body;")` and observe the log.  Expected: either `→ "{}"` (degenerate stringification of the DOM node — engine-dependent) or `✗ JavaScriptEvalException: ...` (when the engine throws on circular refs).  Either is acceptable; the app does not crash and no native callback leaks. |

## Forcing a specific mode

By default `WebViewComponent.create()` picks lightweight on Linux and
heavyweight on macOS/Windows.  Override with the system property:

```
java -Dca.weblite.webview.mode=heavyweight ...    # heavyweight everywhere
java -Dca.weblite.webview.mode=lightweight ...    # lightweight (Linux only)
```

The demo's stderr always logs the resolved mode at startup so you can
confirm which path you're exercising.

## Limitations

- macOS / Windows lightweight: `OffscreenWebView.create` returns
  `null` on these platforms, so `evalAsync` permanently returns a
  failed future with
  `IllegalStateException("WebViewComponent not displayed")`.  This is
  the lightweight stub falling through to the same pre-display path as
  AC7; see the lightweight notes in the top-level README.
- A `CompletableFuture.cancel(true)` only marks the Java future
  cancelled — the in-page JS continues to execute and its eventual
  binding callback finds either no entry (drained) or a cancelled
  future and silently drops.
- A page navigation while a future is in-flight leaves the future
  pending forever.  Callers needing a deadline use `.orTimeout(...)`
  (Java 9+) or `.get(timeout, unit)`.
