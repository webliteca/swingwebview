---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: WebView STDIO / Socket Bridge

## R · Requirements
- Inside a running WebView process, allow an outside controller
  (parent JVM, terminal user, networked client) to evaluate
  JavaScript on the page and to receive JavaScript-originated
  messages — over STDIN/STDOUT or over a TCP socket
  (`WebViewServer.java:24`, `WebViewSocket.java:15`,
  `WebviewSocketServer.java:19`).
- Single-line input is treated as a JS command and `eval()`-ed in
  the WebView (`WebViewServer.java:111`).
- Multi-line input is supported via a boundary protocol:
  `<<<TAG\n...\nTAG\n` accumulates the body and runs it as one JS
  block (`WebViewServer.java:104`, `WebViewServer.java:119`).
  Empty boundary tag means a blank line terminates the body
  (`README.md:76`).
- Three JavaScript callbacks are exposed on every loaded page:
  - `window.postMessageExt(arg)` — emit a message to the
    controller, optionally boundary-wrapped depending on the
    `useMessageBoundaries` flag (`WebViewServer.java:58`).
  - `window.postMessageExtWithBoundary(arg)` — always wrap with
    `<<<Boundary...` markers (`WebViewServer.java:61`).
  - `window.postMessageExtWithoutBoundary(arg)` — never wrap
    (`WebViewServer.java:64`).
- A built-in init script fires `postMessageExtWithoutBoundary(
  '<<<EVENT:load <encoded-url> >>>')` on every
  `DOMContentLoaded` so the upstream client can observe
  navigations (`WebViewServer.java:67`).
- Socket mode (port specified at the CLI) accepts arbitrarily many
  client connections; each gets its own `WebViewSocket`
  controller, all sharing the single WebView
  (`WebviewSocketServer.java:31`).
- Definition of Done: indirectly validated by
  `WebViewCLIClientTest.testEval` exercising the round-trip
  protocol over STDIO (`test/.../WebViewCLIClientTest.java:50`)
  and by manual usage documented at `README.md:62`.

## E · Entities
- **WebViewServer** (`WebViewServer.java:24`) — the actual
  bridge. Holds the `WebView`, the `InputStream`/`OutputStream`,
  a single-threaded output `ExecutorService`, the input thread,
  the message boundary tag, and the `useMessageBoundaries` flag.
  Invariants:
  - `messageBoundary = "Boundary" + System.currentTimeMillis()`
    set at construction (`WebViewServer.java:28`) — unique per
    server instance.
  - `closed` gates the read loop and output writes
    (`WebViewServer.java:32`, `WebViewServer.java:89`).
- **WebViewSocket** (`WebViewSocket.java:15`) — adapter that
  wraps a `java.net.Socket`'s I/O with a `WebViewServer`
  (`WebViewSocket.java:19`).
- **WebviewSocketServer** (`WebviewSocketServer.java:19`) —
  `ServerSocket` accept loop on a dedicated thread, spawns a
  fresh `WebViewSocket` per inbound client
  (`WebviewSocketServer.java:31`).

## A · Approach
- **Line-oriented text protocol.** Easy to drive from a shell,
  trivial to parse, no schema migrations to worry about. The
  cost is fragility around lines that happen to start with
  `<<<` or `[\"<<<EVENT:` — these prefixes are reserved.
- **One JavaScript eval per "command".** Each completed input
  block is forwarded via `webview.dispatch(() ->
  webview.eval(command))` (`WebViewServer.java:164`). Messages
  from JS bubble up via the same channel via the bound
  callbacks installed in `initWebView` (`WebViewServer.java:56`).
- **Single dedicated I/O thread per direction.** A reader thread
  reads STDIN/socket, an `outputService` executor serialises
  writes (`WebViewServer.java:30`, `WebViewServer.java:40`).
  This keeps the WebView dispatch loop free of blocking I/O.
- **Polled non-blocking read.** Rather than blocking on
  `Scanner.hasNextLine`, the read loop sleeps 30ms when
  `input.available() <= 0` (`WebViewServer.java:92`). `[DRIFT]`
  from a typical select/poll-driven server — this is a busy-wait
  pattern that costs ~30ms latency per command but keeps the
  code simple and avoids needing NIO on Java 8.

## S · Structure
- `src/ca/weblite/webview/WebViewServer.java` — the bridge core
  used by both STDIO and socket transports.
- `src/ca/weblite/webview/WebViewSocket.java` — per-connection
  wrapper.
- `src/ca/weblite/webview/WebviewSocketServer.java` — accept
  loop for socket mode.
- The CLI wires this in at `WebViewCLI.java:54` (socket) and
  `WebViewCLI.java:62` (STDIO). See
  [[webview-cli-application]].
- The client side of the protocol lives in
  [[subprocess-webview-client]].

## O · Operations

### 1. Construct Bridge — WebViewServer
File: `src/ca/weblite/webview/WebViewServer.java`

1. Responsibility: bind JS callbacks on the WebView, then start
   the reader thread that consumes commands.
2. Methods:
   - `WebViewServer(WebView webview, InputStream input, OutputStream output)`
     - Logic: assign fields, call `initWebView()` which binds
       `postMessageExt`, `postMessageExtWithBoundary`,
       `postMessageExtWithoutBoundary`, and adds the on-load
       init script (`WebViewServer.java:56`). Start
       `inputThread` running `listen()`; on `IOException` print
       to stderr unless `closed` (`WebViewServer.java:40`).
3. Constraints / Invariants:
   - `initWebView` MUST be called before `webview.show()` so
     the callbacks and init script are buffered and applied at
     `show()` time. The CLI satisfies this by constructing the
     server before calling `webview.show()`
     (`WebViewCLI.java:62`, `WebViewCLI.java:66`).
   - `messageBoundary` is fixed per server instance — a single
     boundary suffices for life since it is randomised by
     `currentTimeMillis` (`WebViewServer.java:28`).

### 2. Configure Boundary Mode — useMessageBoundaries
File: `src/ca/weblite/webview/WebViewServer.java`

1. Responsibility: toggle whether the default
   `postMessageExt` wraps its payloads with boundary markers.
2. Methods:
   - `useMessageBoundaries(boolean use): WebViewServer`
     - Logic: set the flag and return `this`
       (`WebViewServer.java:72`).
3. Constraints / Invariants:
   - The setting applies to subsequent `postMessageExt` calls.
     `postMessageExtWithBoundary` / `postMessageExtWithoutBoundary`
     are always-on / always-off respectively
     (`WebViewServer.java:61`).

### 3. Run Listen Loop — WebViewServer.listen
File: `src/ca/weblite/webview/WebViewServer.java`

1. Responsibility: parse the input stream into commands and
   dispatch each as JS.
2. Methods:
   - `listen(): void`
     - Logic: open `Scanner(input, "UTF-8")`
       (`WebViewServer.java:81`). Write `"\r\n"` once to STDOUT to
       signal readiness (`WebViewServer.java:86`). Loop forever:
       break when `closed`; sleep 30ms if `input.available() <= 0`
       (`WebViewServer.java:92`). While scanner has lines, run a
       state machine: state 0 = top level. A line starting with
       `"<<<"` opens a multi-line block: capture the boundary tag
       from the suffix and switch to state 1
       (`WebViewServer.java:104`). Any other non-empty line is
       passed straight to `executeCommand(line)`
       (`WebViewServer.java:111`). State 1 = accumulate body
       lines until a line equals the boundary, then call
       `executeCommand(currMessage)` and reset
       (`WebViewServer.java:121`).
3. Constraints / Invariants:
   - Empty input lines at the top level are ignored
     (`WebViewServer.java:110`).
   - Empty body messages are not dispatched
     (`WebViewServer.java:122`).
   - The 30ms sleep when no input is available bounds latency
     from incoming command to eval at ~30ms in the steady state.

### 4. Execute Javascript — WebViewServer.executeCommand
File: `src/ca/weblite/webview/WebViewServer.java`

1. Responsibility: forward the assembled JS command onto the
   WebView's dispatch thread for evaluation.
2. Methods:
   - `executeCommand(String command): void`
     - Logic: `webview.dispatch(() -> webview.eval(command))`
       (`WebViewServer.java:164`).
3. Constraints / Invariants:
   - Use `dispatch` not direct `eval` — many bridge calls
     happen on the reader thread, which is not the WebView UI
     thread. See [[in-process-webview-java-api]] section 4.

### 5. Send Outgoing Message — WebViewServer.sendMessage
File: `src/ca/weblite/webview/WebViewServer.java`

1. Responsibility: deliver a message from the WebView back to
   the controller, optionally wrapped in boundary markers.
2. Methods:
   - `sendMessage(String message, boolean addBoundaries): void`
     - Logic: if `addBoundaries`, wrap as
       `"<<<<boundary>\nmsg\nboundary>\n"` else append `"\n"`
       (`WebViewServer.java:142`). Submit a write task to
       `outputService` that writes the UTF-8 bytes and flushes;
       errors print to stderr (`WebViewServer.java:149`).
3. Constraints / Invariants:
   - Writes serialise on the single-threaded
     `outputService` so messages never interleave at the byte
     level (`WebViewServer.java:30`).

### 6. Bind Socket — WebViewSocket
File: `src/ca/weblite/webview/WebViewSocket.java`

1. Responsibility: adapt a TCP socket into a `WebViewServer`
   controller.
2. Methods:
   - `WebViewSocket(Socket sock, WebView webview): WebViewSocket`
     - Logic: build a `WebViewServer` over
       `sock.getInputStream()` / `sock.getOutputStream()`,
       remember the socket so `close()` can close both
       (`WebViewSocket.java:19`).
   - `useMessageBoundaries(boolean): WebViewSocket` — forwards
     to the inner controller (`WebViewSocket.java:25`).
   - `close(): void` — closes the controller then the socket,
     swallowing exceptions (`WebViewSocket.java:30`).

### 7. Accept Loop — WebviewSocketServer
File: `src/ca/weblite/webview/WebviewSocketServer.java`

1. Responsibility: listen on a TCP port and spawn a
   `WebViewSocket` per inbound connection, all sharing one
   `WebView`.
2. Methods:
   - `WebviewSocketServer(int port, WebView webview)`
     - Logic: start a daemon thread that does
       `new ServerSocket(port)`, records the actual local port
       (so `0` callers can discover the bound port), and loops
       on `serverSock.accept()`; each accepted `Socket` becomes
       a `WebViewSocket` added to `sockets`
       (`WebviewSocketServer.java:26`).
   - `getPort(): int` — busy-waits up to the server-thread
     setting `port`, polling every 100ms
     (`WebviewSocketServer.java:52`).
   - `useMessageBoundaries(boolean)` — applies the toggle to
     every existing socket and stores it for future ones
     (`WebviewSocketServer.java:44`).
   - `close(): void` — closes every accepted socket
     (`WebviewSocketServer.java:64`).
3. Constraints / Invariants:
   - `[INFERRED]` `useMessageBoundaries(boolean)` updates the
     flag but the `serverThread` reads `useMessageBoundaries`
     once when accepting; new connections after a toggle pick
     up the new value, existing ones get the explicit
     `setUseMessageBoundaries` propagation.
   - `serverSock.close()` is not called by `close()` —
     `[INFERRED]` the accept loop can leak after `close()`
     unless the JVM exits.

## N · Norms
- Logging here uses `printStackTrace(System.err)` and a
  `java.util.logging.Logger` in `WebviewSocketServer`
  (`WebviewSocketServer.java:37`). Mixed style is `[DRIFT]`
  from `WebViewCLI`'s pure `System.err` pattern but acceptable
  inside this feature.
- Output writes are always serialised onto a single-thread
  executor — never write directly to `output` from a listener.

## S · Safeguards
- `closed` guards both the read loop and the error reporting,
  preventing spurious stack traces during intentional shutdown
  (`WebViewServer.java:46`, `WebViewServer.java:89`).
- Output writes inside a single-thread executor prevent
  byte-level interleaving of concurrent `postMessageExt` calls
  (`WebViewServer.java:30`).
- **No authentication on the socket transport.** Anyone who can
  connect to the configured TCP port can eval arbitrary JS in
  the running WebView (e.g. read cookies, exfiltrate page
  contents, navigate to any URL). The CLI does not bind to
  localhost-only by default — `new ServerSocket(port)` binds to
  all interfaces (`WebviewSocketServer.java:28`). Operational
  guidance: only enable `-port` on trusted networks, or wrap
  with SSH tunnel.
- Empty input lines at the top level and empty boundary-wrapped
  bodies are dropped (`WebViewServer.java:110`,
  `WebViewServer.java:122`), so accidental blank input cannot
  trigger an empty eval.
- Init script wraps the URL in `encodeURIComponent` before
  emitting the synthetic load event so URLs containing special
  characters round-trip cleanly (`WebViewServer.java:67`,
  decoded at `WebViewClient.java:317`).
