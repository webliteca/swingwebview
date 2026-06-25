# WebViewAsyncCallbackDemo

Shows `addJavascriptFunction` — the Java-friendly way to expose a
**value-returning** function to JavaScript, with **no JavaScript glue**.

```java
wv.addJavascriptFunction("reverse", (String arg) ->
    new StringBuilder(arg).reverse().toString());
// in the page:  const r = await window.reverse("abc");   // "cba"
```

That's the whole API. The page sees `window.reverse` as a normal function
returning a Promise; your Java lambda produces the result.

## Why not a synchronous `addJavascriptCallback` that returns a value?

It would block the engine UI thread (AppKit main thread on macOS, the
WebView2 worker on Windows, the GTK main thread on Linux) until Java
produced the value, and the moment the handler touched the Swing EDT you
would deadlock — the hazard
`requirements/[User-story-4]eliminate-edt-appkit-sync-deadlock-on-macos.md`
exists to prevent. `addJavascriptFunction` is deadlock-free by
construction: the page gets a Promise, the synchronous handler runs on a
background thread, and the result is delivered back through a
non-blocking `eval`.

## Two flavors

- **Synchronous** (`JavascriptFunction`, `String run(String)`): the
  library runs your handler on a background worker thread, so it can block
  (DB call, file IO, `Thread.sleep`) without freezing the UI. Return value
  resolves the Promise; throwing rejects it.
- **Asynchronous** (`AsyncJavascriptFunction`,
  `CompletableFuture<String> run(String)`): for work that is already a
  future. The Promise settles when the future completes.

Both are overloads of `addJavascriptFunction`. A lambda returning a
`String` binds to the sync overload; one returning a `CompletableFuture`
binds to the async overload. Type the lambda parameter explicitly
(`(String arg) -> ...`) so the compiler can tell them apart; a throw-only
lambda needs a cast (`(JavascriptFunction) (String arg) -> { throw ... }`).

Results are **strings** (string passthrough, like `evalAsync`). For
structured data, return JSON text and `JSON.parse` it in the page.

## Running

```
./run-mac-async-callback-demo.sh      # macOS (Intel or Apple Silicon)
./run-linux-async-callback-demo.sh    # Linux (lightweight mode)
```

Override the JDK with `JAVA_HOME=/path/to/jdk ./run-mac-async-callback-demo.sh`.

## What you should see

A split window: the WebView on top (input + buttons), the Java log below.
Click the buttons and watch the in-page log:

- **reverse** / **upper** — Java returns a transformed string.
- **slowEcho (1.5s)** — the handler sleeps 1.5s on a worker thread; the
  window stays fully responsive (drag it during the wait). The Java log
  shows the handler thread is `webview-fn-…`, not the EDT.
- **fail (rejects)** — the handler throws; the Promise rejects and the
  `.catch` fires.
- **asyncLookup** — an `AsyncJavascriptFunction` returning a future.
- **5× concurrent** — overlapping calls; each result returns to its own
  caller, slow ones simply settle later.

> **Why a localhost HTTP server?** The embedded macOS engine navigates
> with `WKWebView`'s `loadRequest:`, which silently refuses `data:` URLs.
> The demo serves its HTML over `http://localhost` so it loads like any
> remote site. (The library also handles `data:` URLs correctly now via
> `loadHTMLString:` — this demo keeps the server so it runs identically on
> every platform.)
