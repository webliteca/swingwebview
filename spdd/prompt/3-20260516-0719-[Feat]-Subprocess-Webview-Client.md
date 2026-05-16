---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: Subprocess WebView Client

## R · Requirements
- Provide a Swing/JavaFX-friendly Java API that runs the native
  WebView in a **child JVM process** and controls it over
  STDIN/STDOUT, so the host application's event loop is not held
  hostage by the WebView (`WebViewClient.java:30`,
  `WebViewCLIClient.java:18`).
- Public API:
  - `WebViewCLIClient.Builder` — fluent builder that takes `title`,
    `url`, `size(w,h)`, optional `onBeforeLoad(StringBuilder)`,
    `resizable(boolean)`, then `build()` (`WebViewCLIClient.java:28`,
    `WebViewClient.java:42`).
  - `client.addLoadListener(listener)` — fires for every page load
    (`WebViewClient.java:135`).
  - `client.addMessageListener(listener)` — fires for every
    `window.postMessageExt(msg)` call from JS
    (`WebViewClient.java:143`).
  - `client.ready().get(timeout)` — `CompletableFuture` that resolves
    on the first load event (`WebViewClient.java:151`).
  - `client.eval(js).thenAccept(result)` —
    `CompletableFuture<String>` resolved with a JSON-encoded value
    (`WebViewClient.java:180`).
  - `client.close()` — terminates the child process and tears down
    the I/O threads (`WebViewCLIClient.java:99`).
- The child JVM is launched with the same JDK, classpath, and
  forwarded `inputArguments` as the host JVM, plus
  `-XstartOnFirstThread` on macOS (`WebViewCLIClient.java:62`).
- Default builder values: title `"Web View"`, url
  `"https://www.codenameone.com"`, 800x600 (`WebViewClient.java:43`).
- Definition of Done:
  - `WebViewCLIClientTest.testEval`
    (`test/.../WebViewCLIClientTest.java:50`) launches the client,
    waits for `ready()`, evals `complete(document.title)`, and
    asserts the returned title string. This is the canonical
    integration test for the round-trip protocol.
  - Demo: `WebViewSwingDemo` (`demos/.../WebViewSwingDemo.java:55`)
    exercises open/close, eval input, and load/message listeners.

## E · Entities
- **WebViewClient** (abstract, `WebViewClient.java:30`) —
  base class holding the I/O streams, dispatch executor, event
  listeners, and the reader-thread state machine.
- **WebViewClient.Builder** (abstract, `WebViewClient.java:42`) —
  fluent builder; subclasses implement `build()` to launch their
  own transport (subprocess, raw streams, etc.).
- **WebViewCLIClient** (concrete, `WebViewCLIClient.java:18`) —
  launches a child JVM running `ca.weblite.webview.WebViewCLI` and
  wires the parent end of the child's STDIN/STDOUT into the base
  class.
- **WebEvent** hierarchy (`WebViewClient.java:78`):
  - **OnLoadWebEvent** carries the loaded URL string
    (`WebViewClient.java:83`).
  - **MessageEvent** carries the raw message payload
    (`WebViewClient.java:94`).
- **EvalRequest / ReadyRequest** —
  `CompletableFuture<String>` / `CompletableFuture<WebViewClient>`
  subclasses used as the return type of `eval` and `ready`
  (`WebViewClient.java:171`, `WebViewClient.java:176`).
- **WebEventListener<T>** (`WebViewClient.java:105`) — functional
  interface, single `handleEvent(T evt)` method.

## A · Approach
- **Out-of-process by design.** Because the in-process
  [[in-process-webview-java-api]] requires its own event loop on
  the main thread, the only way to embed a WebView next to an
  existing Swing/JavaFX UI without the
  [[swing-webview-component-mode-selection]] API was to launch the
  WebView CLI as a child process and talk to it over pipes.
- **Boundary-framed text protocol.** Multi-line JS payloads are
  wrapped in `<<<<requestId\n...js...\nrequestId\n` so the child's
  STDIN parser can re-assemble them (`WebViewClient.java:236`,
  matching the server side in [[webview-stdio-socket-bridge]]). The
  child sends back `postMessageExt` results wrapped in the same way
  (boundary computed once per session, prefix `Boundary` +
  `currentTimeMillis`).
- **Promise-based eval.** Each `eval` injects a per-request UUID
  into the JS, plus a `complete()` helper. A `MessageEvent`
  listener watches for that UUID in the reply, parses the JSON
  array, and completes the `EvalRequest` with the JSON-encoded
  message value (`WebViewClient.java:200`–`WebViewClient.java:235`).
- **Single dispatch thread.** All listener invocations and eval
  state changes are marshalled onto one `dispatchService` thread
  (`WebViewClient.java:32`) so listeners do not have to be
  thread-safe.

## S · Structure
- `src/ca/weblite/webview/WebViewClient.java` — abstract client,
  protocol parser, event dispatch, eval/ready futures.
- `src/ca/weblite/webview/WebViewCLIClient.java` — child-JVM
  spawner, sets up the parent ends of the child's stdio.
- `src/ca/weblite/webview/nanojson/` — bundled JSON parser/writer
  used to encode/decode the eval payloads (`WebViewClient.java:8`).
- `test/ca/weblite/webview/WebViewCLIClientTest.java` — JUnit test
  exercising the eval round-trip.
- `demos/WebViewSwingDemo/...` — interactive Swing demo built on
  this API.

## O · Operations

### 1. Build Webview Builder — WebViewClient.Builder
File: `src/ca/weblite/webview/WebViewClient.java`

1. Responsibility: collect window/URL configuration before launching
   the subprocess.
2. Fields:
   - `title: String` — default `"Web View"`
     (`WebViewClient.java:43`).
   - `url: String` — default `"https://www.codenameone.com"`
     (`WebViewClient.java:44`).
   - `w, h: int` — default 800x600 (`WebViewClient.java:45`).
   - `resizable: boolean` — default `false`
     (`WebViewClient.java:47`).
   - `onBeforeLoad: StringBuilder` — accumulator for init JS
     (`WebViewClient.java:48`).
3. Methods:
   - `title`, `url`, `size`, `resizable`, `onBeforeLoad()` setters
     return `this` for chaining (`WebViewClient.java:52`–
     `WebViewClient.java:75`).
   - `build(): WebViewClient` — abstract; subclasses materialize a
     transport.

### 2. Build Subprocess Builder — WebViewCLIClient.Builder.build
File: `src/ca/weblite/webview/WebViewCLIClient.java`

1. Responsibility: translate the builder state into a `WebViewCLI`
   command line and spawn the child JVM.
2. Methods:
   - `build(): WebViewCLIClient`
     - Logic: assemble the args array `["-title", title, "-w", w,
       "-h", h, "-useMessageBoundaries", "true"]`
       (`WebViewCLIClient.java:32`), append `"-onLoad",
       onBeforeLoad.toString()` if non-empty
       (`WebViewCLIClient.java:38`), append the positional `url`
       (`WebViewCLIClient.java:41`), then `new
       WebViewCLIClient(args)`.
3. Constraints / Invariants:
   - `-useMessageBoundaries true` is always passed so the child's
     STDOUT writes are boundary-wrapped — the protocol parser in
     `WebViewClient.init` depends on this
     (`WebViewClient.java:322`).

### 3. Launch Child JVM — WebViewCLIClient constructor
File: `src/ca/weblite/webview/WebViewCLIClient.java`

1. Responsibility: spawn a child JVM with the right
   `-XstartOnFirstThread`, classpath, and forwarded JVM args, and
   wire its stdio into `WebViewClient.init`.
2. Methods:
   - `WebViewCLIClient(String[] args)`
     - Logic: register a JVM shutdown hook to `destroyForcibly` the
       child (`WebViewCLIClient.java:49`). Read PID, classpath,
       `inputArguments`, JDK path. Build a JVM command of:
       `jvmPath`, `-XstartOnFirstThread` on macOS only
       (`WebViewCLIClient.java:72`), forwarded `inputArguments`,
       `-cp <classpath>`, `ca.weblite.webview.WebViewCLI`, then
       the args from the builder (`WebViewCLIClient.java:71`–
       `WebViewCLIClient.java:82`). Start with `ProcessBuilder`
       (no `inheritIO` — we want the pipes), grab
       `process.getInputStream()`/`getOutputStream()`, call
       `super.init(input, output)`.
3. Constraints / Invariants:
   - Forwarding the parent JVM's input arguments lets users pass
     `-Xmx`, `-D...`, etc. and have them reach the child
     (`WebViewCLIClient.java:67`).
   - On non-macOS platforms, `-XstartOnFirstThread` is omitted
     (`WebViewCLIClient.java:72`).
   - Shutdown hook prevents zombie child processes when the host
     JVM exits abruptly (`WebViewCLIClient.java:49`).

### 4. Read Subprocess Reader Thread — WebViewClient.init
File: `src/ca/weblite/webview/WebViewClient.java`

1. Responsibility: parse the child's STDOUT into `OnLoadWebEvent`
   and `MessageEvent`s using the boundary protocol.
2. Methods:
   - `init(InputStream input, OutputStream output): WebViewClient`
     - Logic: store streams, dispatch one task on
       `dispatchService` to capture `dispatchThread` for later
       `isDispatchThread()` checks (`WebViewClient.java:297`). Start
       `readerThread` running a state machine over `Scanner` lines:
       state 0 = expecting a fresh line; recognise lines starting
       with `"["EVENT:load "` as a load event (URL is URL-decoded
       from the next whitespace-token —
       `WebViewClient.java:314`); recognise lines starting with
       `"<<<"` as the start of a boundary-wrapped message (capture
       the boundary tag and switch to state 1); otherwise fire as a
       message verbatim (`WebViewClient.java:327`). State 1 =
       accumulate lines into `messageBuffer` until a line equals
       the captured boundary, then fire `messageBuffer.toString()`
       as a message and return to state 0.
3. Constraints / Invariants:
   - Reader thread is started here and runs until `close()` is
     called or the child closes its stdout
     (`WebViewClient.java:346`).
   - `readerThread` catches and prints any throwable unless `closed`
     is true — silent shutdown when we initiated the close
     (`WebViewClient.java:340`).
   - All `fireOnLoad` / `fireMessage` calls hop onto the dispatch
     thread before invoking listeners
     (`WebViewClient.java:252`, `WebViewClient.java:270`).

### 5. Register Event Listener — WebViewClient.addEventListener
File: `src/ca/weblite/webview/WebViewClient.java`

1. Responsibility: add a typed event listener thread-safely by
   hopping onto the dispatch thread before mutating the map.
2. Methods:
   - `addEventListener(String type, WebEventListener l): WebViewClient`
     - Logic: if not on dispatch thread, `dispatch(() -> addEventListener(...))`;
       otherwise look up `listeners.get(type)`, lazily create the
       `ArrayList`, and add `l` (`WebViewClient.java:109`).
   - `addLoadListener`, `addMessageListener` — type-safe sugar over
     `addEventListener("load", ...)` / `("message", ...)`
     (`WebViewClient.java:135`).
3. Constraints / Invariants:
   - Listener add/remove is always serialised on
     `dispatchService` — fires are also serialised, so listener
     callbacks do not need their own locking
     (`WebViewClient.java:262`).

### 6. Wait For First Page Load — WebViewClient.ready
File: `src/ca/weblite/webview/WebViewClient.java`

1. Responsibility: return a `CompletableFuture` that resolves the
   first time the WebView fires `load`.
2. Methods:
   - `ready(): ReadyRequest`
     - Logic: create a fresh `ReadyRequest`. If `ready` is already
       true, immediately `complete(this)`
       (`WebViewClient.java:153`). Otherwise register a load
       listener that unregisters itself and completes the future on
       first fire (`WebViewClient.java:156`).
3. Constraints / Invariants:
   - `ready` becomes `true` the first time `fireOnLoad` runs
     (`WebViewClient.java:253`); it stays true for the lifetime of
     the client.

### 7. Run Javascript With Result — WebViewClient.eval
File: `src/ca/weblite/webview/WebViewClient.java`

1. Responsibility: execute JavaScript in the child's WebView and
   return a `CompletableFuture<String>` that resolves with the
   JSON-encoded result.
2. Methods:
   - `eval(String js): EvalRequest`
     - Logic: thunk onto dispatch thread if not already there.
       Construct an internal completion helper:
       `function complete(val){val={evalId:'<uuid>', message:val};
       postMessageExt(val)};` (`WebViewClient.java:202`). If the user
       JS does not call `complete(...)`, append
       `complete('null');` (`WebViewClient.java:196`). Wrap in a
       try/catch that calls `postMessageExt({error, errorType:
       'javascriptError', content: e})` (`WebViewClient.java:199`).
       Wrap the whole thing in an IIFE
       `(function(){ ... })()` (`WebViewClient.java:203`).
   - Register a one-shot message listener that watches for the
     UUID in incoming messages, parses the JSON array, extracts
     the `message` field, JSON-encodes it via
     `JsonWriter.string(messageValue)`, and completes the future
     (`WebViewClient.java:204`–`WebViewClient.java:235`). Parse
     errors complete the future with a synthesised JSON error
     object (`WebViewClient.java:220`).
   - Write the JS to the child stdin wrapped in
     `<<<<requestId\n...\nrequestId\n` (`WebViewClient.java:236`).
3. Constraints / Invariants:
   - Eval results are JSON-encoded strings — callers must
     `JsonParser` them, not `String.equals` the raw value (the
     test asserts the encoded form,
     `WebViewCLIClientTest.java:55`).
   - The request UUID approach allows concurrent evals — each has
     its own listener that filters by UUID
     (`WebViewClient.java:213`).

### 8. Close Webview Client — WebViewClient.close / WebViewCLIClient.close
File: `src/ca/weblite/webview/WebViewClient.java`, `src/ca/weblite/webview/WebViewCLIClient.java`

1. Responsibility: shut down the I/O streams, dispatch executor,
   and (in the CLI subclass) the child process.
2. Methods:
   - `WebViewClient.close(): void`
     - Logic: set `closed = true`, close `input` and `output`
       swallowing exceptions, `dispatchService.shutdown()`
       (`WebViewClient.java:354`).
   - `WebViewCLIClient.close(): void`
     - Logic: call `super.close()` then `process.destroyForcibly()`
       (`WebViewCLIClient.java:99`).
3. Constraints / Invariants:
   - Setting `closed = true` BEFORE closing the streams lets the
     reader thread suppress the inevitable
     `Throwable` printout (`WebViewClient.java:341`).
   - Idempotent: subsequent calls catch their own exceptions; safe
     to call multiple times.

## N · Norms
- Java 8 source/target (`pom.xml:41`). Use `CompletableFuture`, not
  `Future`, for new request types.
- Thread model: any mutation of `listeners` or any listener fire
  must happen on the single dispatch thread. Use
  `dispatch(Runnable)` to hop onto it from arbitrary threads
  (`WebViewClient.java:247`).
- JSON encode/decode goes through the in-tree
  `ca.weblite.webview.nanojson` package — do not pull in another
  JSON library here. Java 8 has no built-in JSON parser, and the
  bundled nanojson is intentionally tiny.
- Tests live under `test/ca/weblite/webview/...`
  (`pom.xml:54`). New tests for this feature belong there.

## S · Safeguards
- Reader thread suppresses errors when `closed` is true
  (`WebViewClient.java:341`) — prevents noisy stacks on intentional
  shutdown.
- `addEventListener` and `removeEventListener` always hop onto the
  dispatch thread before mutating `listeners`, so the map is never
  touched concurrently (`WebViewClient.java:110`,
  `WebViewClient.java:124`).
- Each `eval` listener removes itself on first matching response
  or when the future is already done
  (`WebViewClient.java:209`, `WebViewClient.java:215`), preventing
  listener leaks across many `eval` calls.
- The eval JS is wrapped in `try { ... } catch(e) {
  postMessageExt({error, errorType:'javascriptError', content:e})}`
  (`WebViewClient.java:199`) so JS-side exceptions become a
  structured message rather than hanging the future.
- JSON parse failures in the response handler synthesise an error
  object instead of throwing inside the dispatch thread
  (`WebViewClient.java:220`).
- Shutdown hook ensures child JVMs are killed when the host exits
  (`WebViewCLIClient.java:49`).
- Unbounded request retention: `EvalRequest`s are never timed out
  by the library — callers must use `.get(timeout, ...)` or
  `orTimeout` to bound waits. The test uses 5s timeouts
  (`WebViewCLIClientTest.java:53`).
