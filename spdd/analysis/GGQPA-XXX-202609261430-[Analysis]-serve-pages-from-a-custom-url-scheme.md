# SPDD Analysis: Serve Pages From A Custom URL Scheme

## Original Business Requirement

# Story Decomposition: Serve Pages From A Custom URL Scheme

## INVEST Analysis

### Abstract Task: "An Application's Own Address Space, Answered In Java"

**Analysis Dimensions**:
- **Core Responsibility**: Let an application register a **custom URL scheme** such as `aaf` and
  answer every request to it (`aaf://db/books`, `aaf://db/app.js`) **from Java**, so its own pages
  load without a local HTTP server.
  - The address bar shows the application's own address rather than `http://127.0.0.1:53817/…`.
  - No port is opened on the machine.
  - Such pages behave like pages from a secure site: scripts can `fetch` other addresses in the same
    scheme, and storage works.
- **Primary Operations**:
  - register a scheme and its handler before any WebView exists;
  - answer a request with a status, headers and a body;
  - receive the request's method, headers and body;
  - find out whether this build and machine support custom schemes.
- **Key Constraints**:
  - It must work on all three engines (WebView2, WebKitGTK, WKWebView), in the component mode each
    platform supports: lightweight on Linux, heavyweight on macOS and Windows.
  - Engines fix their schemes when the first WebView is created, so registration happens before
    that.
  - A handler answers asynchronously and never blocks the UI thread.
  - A handler that fails must produce an error page, never a crash.
  - The web's own schemes cannot be claimed.
  - There is no new link-time dependency on Linux.
- **Technical Complexity**: High. It needs three native implementations and a request/response
  round trip between native code and Java.
- **Business Complexity**: Low. One registration, one handler contract.

### INVEST Evaluation
- ✅ **Independent**: builds on the existing engine wrappers only.
- ✅ **Negotiable**:
  - whether streaming bodies are needed now;
  - the exact set of refused scheme names;
  - whether request bodies are required on older Linux engines.
- ✅ **Valuable**: an application can ship a local UI with its own address, and no local port or
  token.
- ✅ **Estimable**: one registration and one request path per engine.
- ⚠️ **Small**: about 5 days, across three engines. It is one story, as print-to-PDF was (story 7),
  because the value is a portable API. A scheme that works on one platform is not something an
  application can build on.
- ✅ **Testable**: a page loaded from the scheme can be checked for its address, its content, the
  requests it made, and what the handler received.

**Conclusion**: **Ready as-is — one story**, at the upper bound.

### Split Strategy
Not applicable — no split needed.

---

## [STORY-008-001] Serve pages from a custom URL scheme

### Background

Applications built on this library show their own pages in a WebView: a settings page, a report,
a data browser. Today the only way to serve such a page with its images, scripts and data calls is
a local HTTP server on `127.0.0.1`. That has costs:
- the address bar shows `http://127.0.0.1:53817/…`;
- any other program on the machine can reach the port;
- the application has to guard the port with a token;
- the page's origin looks like every other local server's.

The Agentic App Framework is the first to need this. It wants its local-database browser to live
at `aaf://db/`, with nothing listening on a port.

Every engine can hand requests for a custom scheme to the application instead of the network, but
each through a different API, and each fixes its schemes when the first WebView is created. This
story adds **one** portable way to do it:
- the application registers a scheme and a handler at start-up;
- every request to that scheme reaches the handler in Java, with the method, URL, headers and body;
- the handler answers with a status, headers and body, whenever it is ready.

### Business Value
- Provide **application developers** with their own URL scheme for local pages, answered in Java,
  with no local server, port or token.
- Support **local UIs that look like the application's own**: the address bar shows `aaf://db/`,
  and bookmarks and history keep it.
- Enable **portable code**: the same registration and handler on Windows, macOS and Linux.

### Dependencies and Assumptions
- **Prerequisites**: none.
- **Data assumptions**: the application knows its scheme names at start-up.
- **Integration points**:
  - WebView2 on Windows: custom scheme registration when the environment is created, then request
    interception.
  - WebKitGTK on Linux: URI scheme registration on the web context.
  - WKWebView on macOS: a scheme handler on the view's configuration.
- **Business constraints**: no new mandatory runtime dependency. Linux keeps loading WebKit at
  runtime.

### Scope In
- **Registering a scheme and its handler** before the first WebView is created. The scheme is 2–32
  characters: a lowercase letter first, then lowercase letters, digits, `+`, `-` or `.`.
- **The handler receives** the method, the full URL, the request headers and the request body (if
  any).
- **The handler answers** with a status code, response headers (including `Content-Type`) and body
  bytes. It may answer later, from any thread.
- **The scheme behaves like a secure origin**:
  - `aaf://db/` is its own origin;
  - scripts on it can `fetch` other `aaf://db/…` addresses;
  - `localStorage` works;
  - it is treated as a secure context.
- **Popups** opened from such a page, including popups adopted into tabs, load from the same
  scheme.
- **A handler that throws, or never answers**, gives the page an error response (500, or 504 after
  30 seconds), and nothing crashes.
- **Refusing** registration of the web's own schemes and schemes the engines reserve: `http`,
  `https`, `file`, `data`, `blob`, `about`, `javascript`, `ws`, `wss`, `ftp`.
- **Refusing** registration after the first WebView exists, with a clear reason.
- **`isCustomSchemeSupported()`**: whether the loaded native library provides the operation.
- **The component each platform supports**: lightweight on Linux, heavyweight on macOS and Windows.
- **A demo** that serves a two-file page (HTML plus a script that fetches JSON) from `demo://`.

### Scope Out
- Streaming responses (a body arrives whole).
- Changing or removing a scheme after start-up.
- Serving the scheme to the standalone (non-Swing) `WebView` window.
- Service workers on the scheme.
- Opening the scheme in the user's external browser, or registering it with the operating system.

### Acceptance Criteria

#### AC1: A page loads from the scheme
**Given** an application that registered `demo` with a handler serving `<h1>Hello</h1>` as
`text/html` for `demo://app/index.html`
**When** a component navigates to `demo://app/index.html`
**Then** the page shows "Hello", and the component's current address is `demo://app/index.html`.

#### AC2: A page's own requests reach the handler
**Given** `demo://app/index.html` includes `<script src="app.js">` and `<img src="logo.png">`
**When** it loads
**Then** the handler receives `demo://app/app.js` and `demo://app/logo.png`, and the script runs and
the image shows.

#### AC3: fetch works, with its body
**Given** a script on `demo://app/` that runs `fetch("demo://app/api/save", {method: "POST", body:
'{"n":3}'})`
**When** it runs
**Then** the handler receives method `POST` and a body of `{"n":3}`, and the script receives the
handler's JSON answer with status 200.

#### AC4: The page is a secure origin with storage
**Given** a page on `demo://app/`
**When** it checks `window.isSecureContext` and stores and reads back a `localStorage` value
**Then** `isSecureContext` is true, the value reads back, and `location.origin` is `demo://app`.

#### AC5: A slow answer does not block the UI
**Given** a handler that answers 2 seconds after it is called, from its own thread
**When** the page requests it
**Then** the application's event thread keeps running other work meanwhile, and the page receives
the answer.

#### AC6: A failing handler gives an error, not a crash
**Given** a handler that throws for `demo://app/broken`
**When** the page fetches it
**Then** the page receives status 500, and the WebView and application keep running.

#### AC7: A handler that never answers times out
**Given** a handler that never answers `demo://app/hang`
**When** the page fetches it
**Then** after 30 seconds the page receives status 504.

#### AC8: The web's own schemes cannot be claimed
**Given** an application at start-up
**When** it registers `https` or `file`
**Then** registration is refused with "“https” cannot be registered: it is one of the web's own
schemes."

#### AC9: Registering too late is refused
**Given** a component that has already been displayed
**When** the application registers a new scheme
**Then** registration is refused with "Custom schemes must be registered before the first WebView
is created."

#### AC10: An older native library says so
**Given** an application running against a native library built before this feature
**When** it asks whether custom schemes are supported, and then registers one
**Then** it is told no, and registration fails with "Custom URL schemes are not available in this
version of the native library" rather than crashing.

#### AC11: Popups keep the scheme
**Given** a page on `demo://app/` that opens `demo://app/detail.html` in a new window, adopted into
a tab
**When** the popup loads
**Then** it shows the handler's page for `demo://app/detail.html`.

#### AC12: Unregistered schemes are untouched
**Given** `demo` is registered
**When** a component navigates to `https://example.com/`
**Then** the page loads from the network as before, and the handler is not called.

#### Non-Functional Expectations
- A page of 20 small files loads from the scheme about as fast as from a local HTTP server.
- Many requests in flight at once, for example 50 images, are each answered once, with no answer
  given to the wrong request.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **Engine wrappers and components**:
  - `EmbeddedWebView` (heavyweight: macOS, Windows);
  - `OffscreenWebView` (lightweight: Linux);
  - `WebViewComponent` with its Heavyweight / Lightweight subclasses.

  Every capability so far is **per component**. Pending fields such as the user agent or credential
  store are applied when the peer attaches. There is **no global, before-the-first-WebView
  configuration** anywhere in the library, and this story introduces the first.
- **Where each engine is created**, which is where schemes must be installed:
  - **macOS**:
    - A fresh `WKWebViewConfiguration` per engine, with nothing set between its creation and
      `initWithFrame:configuration:`. A scheme handler fits exactly there.
    - Popups are built from the configuration WebKit hands the delegate, which is a copy of the
      opener's, so a handler set on the opener is inherited.
    - Adopted popups replace only the user-content controller.
  - **Linux**:
    - Every view is created with `webkit_web_view_new` (or `_with_related_view` for popups), so all
      share **WebKit's default web context**, and the library never creates its own.
    - A context-level registration (a URI scheme on the default context) therefore reaches every
      view, popups included. It must happen once, on the GTK pump thread, before the first load.
  - **Windows**:
    - Each engine creates **its own** WebView2 environment on its own STA thread, with **null
      options**.
    - Popups reuse the opener's environment.
    - Custom schemes are declared in the environment options, so every environment created must
      carry the same scheme list. Registering "before the first WebView" is what guarantees every
      environment sees the same list.
- **Native-to-Java calls that need an answer**:
  - Dialogs, downloads and popups call into Java on a native or worker thread, and a dispatcher
    (`DialogDispatcher`, `DownloadDispatcher`) runs the application's handler.
  - The native side waits in a platform-specific way:
    - Linux: synchronously on the GTK pump thread.
    - macOS: a copied completion handler, with the JNI call on a detached thread and the answer
      applied back on the main queue.
    - Windows: event-args deferral, the JNI call on a thread, and the answer applied back on the
      engine thread.
  - Handlers today run **on the EDT** via `invokeAndWait`. That is right for a dialog and wrong for
    a scheme request, which may do I/O and must not block the UI (AC5).
- **Completing once, from any thread** (`PdfPrinting.Request`, `pdf_finish`): the PDF feature's
  pattern is:
  - a global-ref'd Java callback;
  - an exactly-once guard;
  - `pdf_finish` attaching to whichever thread completes;
  - a per-feature `…_available()` native export, probed in a `try`/`catch (Throwable)`, so an
    older library reports "not supported" instead of crashing.

  A scheme response is the mirror image: Java completes, and native must deliver it to the engine
  on the right thread.
- **The Linux symbol loader** (`webkit_loader.h` X-macro list, `webkit_shim.h` defines):
  - Every listed symbol is **mandatory**, and one missing symbol fails `JNI_OnLoad` for the whole
    library.
  - Version-gated lists exist only at *compile* time (`#if WEBKIT_MINOR_VERSION`).
  - There is **no runtime-optional symbol mechanism** today.
- **CI**: all six native targets are compiled on every push. The Linux build requires WebKitGTK 4.1
  headers and fails if the `.so` links WebKit directly, and Windows uses WebView2 SDK 1.0.2592.51.
  Java unit tests run headless on Linux, and **natives are never executed in CI**. Engine
  behaviour is verified with the demo scripts.
- **Test pattern**: stub components override protected peer hooks, and dispatchers are unit-tested
  without a display (JUnit 4, Java 8 per `spdd/norms.md`).

### New Concepts Required

- **Scheme registry**: an application-wide set of scheme names and their handlers, frozen when the
  first engine is created. Every engine reads the same list at creation time, which is the one
  global piece of configuration in the library.
- **Scheme request**: what native code captures and hands to Java:
  - an id that native code can later resolve;
  - the method, URL and headers;
  - the body, where the engine can provide one.
- **Scheme response**: the status, headers and body bytes, delivered by the application from any
  thread, at most once per request.
- **Scheme handler**: the application's code. It runs **off the UI thread**, on the library's own
  executor, and receives a request plus a way to answer.
- **Request lifetime guard**: every request ends exactly once, in one of these ways:
  - answered;
  - failed (500 when the handler throws);
  - timed out (504 after 30 s);
  - cancelled (the page navigated away or the view was closed, and native told Java).
- **Per-engine responder**: the native half that turns a request id and a response back into the
  engine's own completion call, on the thread that engine demands.
- **Runtime-optional WebKit symbols (Linux)**: symbols resolved when present and tolerated when
  absent. This is needed because request bodies (WebKitGTK 2.40) and custom status or headers
  (2.36) are newer than the oldest WebKitGTK the library loads.

### Key Business Rules

- **Before the first WebView, or never** (AC9): engines fix their schemes at creation, so
  registering after any engine exists is refused with a clear reason.
- **The web's own schemes cannot be claimed** (AC8): `http`, `https`, `file`, `data`, `blob`,
  `about`, `javascript`, `ws`, `wss`, `ftp`. Names follow the story's rule (a lowercase letter,
  then lowercase letters, digits, `+`, `-`, `.`, 2–32 characters).
- **Exactly one answer per request**, whatever happens (AC5–AC7): late, duplicate or post-cancel
  answers are ignored.
- **The handler never runs on the UI thread** (AC5). The response is applied on the engine's own
  thread.
- **A secure, same-origin scheme** (AC4): each registered scheme is declared secure, and
  CORS-enabled for its own origin, on every engine.
- **Unregistered schemes are untouched** (AC12). Only registered names are intercepted.
- **Popups keep the scheme** (AC11): by inheritance on macOS and Linux, and by environment reuse on
  Windows.
- **Implicit — `about:blank` and data URLs still work**. Registering a scheme never changes how any
  other scheme loads.
- **Implicit — a missing capability is reported, not crashed**:
  - no library support gives AC10's sentence;
  - a Linux engine too old for bodies delivers requests with an empty body and a flag saying the
    body was unavailable.

## Strategic Approach

### Solution Direction

Add a small **Java scheme layer**, a native **scheme bridge** per engine, and one global
registration step.

1. **Java**:
   - A registry the application fills at start-up, with a static entry point like
     `isPdfPrintingSupported()`. It is frozen by the first engine creation.
   - A dispatcher that receives native requests, runs handlers on its own executor, enforces
     exactly-once, the 500 on a throw and the 504 on a timeout, and hands the response back to
     native.
2. **Native**, per engine, installed at the engine-creation points found above:
   - **macOS**: set one handler object per registered scheme on the configuration before
     `initWithFrame:`. A task becomes a request id. The response is applied on the main queue, and
     a stopped task becomes a cancellation.
   - **Linux**: once, on the pump thread and before the first view:
     - register each scheme on the default context;
     - declare it secure and CORS-enabled through the context's security manager.

     A request is held by id, and its response is finished on the pump thread with a stream plus
     status and headers where the engine offers them.
   - **Windows**: pass environment options carrying one custom-scheme registration per scheme
     (secure, with an authority, allowed only from its own origin) to every environment creation.
     Add a resource-request filter for each scheme, take a deferral per request, and answer with a
     built response on the engine thread.
3. **The probe and the demo**: a `…_available()` export alongside the PDF one, and a demo serving
   HTML plus a script that fetches JSON from `demo://`.

Data flow:

page requests `demo://app/x` → engine → native bridge (request id, method, URL, headers, body) → JNI
→ Java dispatcher → handler on the executor → response → JNI → native responder → engine thread →
page.

### Key Design Decisions

- **Global registration vs per component.**
  - A per-component API would read naturally but cannot be honoured: WebView2 fixes schemes per
    environment, and WebKitGTK per shared context.

  → **A static, application-wide registration**, frozen at the first engine creation. It is the
  library's first global setting. The freeze makes the rule explicit (AC9) instead of silently
  ignoring late registrations.
- **Where the handler runs.**
  - The EDT, like dialogs, would block the UI on any I/O.
  - The native thread would block GTK or WebView2.

  → **The library's own executor**, a small pool of daemon threads. It uses the async-completion
  model the PDF feature already established (a future plus exactly-once), reversed in direction.
- **How the response reaches native.**
  - A synchronous return from the upcall would pin a native thread for the handler's whole
    duration. On Linux that is the GTK pump thread, which would freeze every view.

  → **Asynchronous.** The upcall returns immediately, and Java later calls a native "respond"
  export with the request id. Native keeps a table of pending requests keyed by id, and a response
  for an unknown id (already cancelled or answered) is dropped.
- **Linux compatibility.**
  - Making the new-API symbols mandatory would stop the library loading on older WebKitGTK builds
    that it loads today.

  → **Mandatory**: the scheme symbols available since WebKitGTK 2.0 (register, request URI, path,
  finish, finish-error, and the security manager).
  → **Runtime-optional**: request method, headers and body (2.12 / 2.36 / 2.40) and the response
  object with status and headers (2.36). These are added through a new optional-symbol list that
  tolerates absence.

  On an engine without the response object, a 200 answer carries only its content type, and any
  other status becomes an error page. That is documented as a Linux limitation for old engines.
- **Windows environment options.** The environment is created with null options today. →
  **Implement the options object** (the SDK's `ICoreWebView2EnvironmentOptions` family, up to
  version 4 for scheme registrations) carrying the frozen scheme list. Every other option stays at
  its default, so existing behaviour is unchanged when no scheme is registered. When nothing is
  registered, **keep passing null**, which means zero change for existing applications.
- **Cancellation.** WebKit and WebView2 can abandon a request when the page navigates away. →
  Native notifies Java, and Java drops the request, so a late answer is ignored and a hanging
  handler is timed out for bookkeeping only. It never answers a cancelled request.
- **The body size cap.** Request and response bodies are held whole in memory, since streaming is
  out of scope. → Set a documented cap: a 64 MB response, and a 16 MB request, which is refused
  with 413 above that. This keeps a mistaken handler from exhausting memory.
- **Canvas split, following Canvases 23–25.** → **Canvas 30**: the Java API, the dispatcher, the
  macOS native and the demo. **Canvas 31**: Linux coverage (symbols, context registration,
  responder). **Canvas 32**: Windows coverage (environment options, filter, deferral).

### Alternatives Considered

- **A loopback HTTP server inside the library**: rejected. It is what the story exists to remove
  (a port, a token, a `127.0.0.1` address).
- **Intercepting `http://app.local/…` instead of a custom scheme**: rejected. WebKitGTK and
  WKWebView do not let an application answer `http` requests, and the address bar would still
  show `http`.
- **Per-component scheme sets**: rejected. Not possible on WebView2 or WebKitGTK without separate
  contexts per component, which would break shared cookies and storage between components.
- **Running handlers on the EDT**: rejected by AC5.
- **A synchronous JNI round trip**: rejected. It would block the GTK pump thread, and with it
  every view, for the handler's duration.

## Risk & Gap Analysis

### Requirement Ambiguities

- **`localStorage` and `isSecureContext` on WKWebView** (AC4): WebKit treats an app-registered
  scheme as its own origin, but whether `localStorage` persists and `isSecureContext` is true for
  it on every macOS version needs verification with the demo. The Canvas should state the macOS
  floor and treat a failure there as a documented limitation, not a silent pass.
- **Request bodies on older Linux engines** (AC3): WebKitGTK < 2.40 cannot give the handler a POST
  body. The proposal is to deliver an empty body plus an "unavailable" flag, and document it. The
  alternative is refusing POST.
- **What "the first WebView" means**: the first component *attached*, or the first *constructed*?
  Proposed: **the first engine created** (the peer, not the Java object), so an application can
  construct components and then register before any is shown.
- **Timeout value** (AC7: 30 s): fixed or configurable? Proposed: fixed, with the value named in
  the API docs.
- **Headers the handler may set**: some are engine-controlled (for example `Content-Length`). The
  proposal is that the library sets `Content-Length` itself and ignores a handler's value.

### Edge Cases

- **Two components on different threads** asking at once: the request ids are global and the
  native table is locked.
- **The view closed with requests pending**: every pending request for that engine is cancelled
  and dropped, and a late answer is ignored.
- **A redirect** (a handler answering 302 with `Location`): WebView2 and WebKit handle it
  differently. Proposed: supported where the engine honours it, and documented as
  engine-dependent.
- **A handler answering twice**: the second answer is ignored.
- **The same scheme registered twice**: refused with a sentence, never silently replaced.
- **Uppercase in a URL** (`DEMO://app/`): engines lower-case the scheme, so matching is by the
  lower-case name.
- **The standalone `WebView` window** (Scope Out): not intercepted. The registry is read only by
  component engines, and that is documented.

### Technical Risks

- **Three native implementations verified only by compilation in CI.** macOS and Windows behaviour
  can only be checked by hand with the demo. *Mitigation*: keep all logic that can be tested in
  Java (dispatcher, exactly-once, timeouts, name rules, freeze), and keep native bridges thin.
- **The macOS thread rules**: a scheme task's methods must be used on the main thread, and a task
  must not be touched after it stops (WebKit throws).
- **The Windows thread rules**: deferrals are completed on the engine thread.
- **The Linux thread rules**: GTK objects are touched only on the pump thread.

  *Mitigation*: every responder marshals to the engine's thread, and checks the request is still
  live under the table lock.
- **Linux optional symbols**: a new loader capability (optional resolution) touches the symbol
  machinery every feature depends on. *Mitigation*: a separate list; mandatory symbols behave
  exactly as today.
- **Windows options object**: implementing COM options interfaces by hand must answer every
  getter with the SDK default, or it changes unrelated behaviour. Keeping null when no scheme is
  registered contains the risk to applications that use the feature.
- **Memory**: whole bodies in memory, bounded by the caps.
- **The macOS ordering constraint (D15 of Canvas 29)**: registering schemes before natives are
  preloaded must not change native load order. The registry is pure Java until the first engine
  asks for it.

### Acceptance Criteria Coverage

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | A page loads from the scheme, and the address shows it | Yes | All three engines; address is the engine's own URL |
| 2 | A page's scripts and images reach the handler | Yes | Sub-resources go through the same interception |
| 3 | fetch with a POST body | Partial | Linux < 2.40 cannot supply the body (documented limitation) |
| 4 | Secure origin, storage | Partial | Declared secure on all; macOS `localStorage` / `isSecureContext` to verify with the demo |
| 5 | A slow answer does not block the UI | Yes | Library executor plus async respond |
| 6 | A throwing handler gives 500 | Yes | Dispatcher, unit-tested |
| 7 | A hanging handler gives 504 at 30 s | Yes | Dispatcher timer, unit-tested |
| 8 | Web schemes refused | Yes | Registry rule, unit-tested |
| 9 | Too late is refused | Yes | Freeze at first engine creation, unit-tested with a stub |
| 10 | Older native library says so | Yes | `…_available()` probe in try/catch, like PDF |
| 11 | Popups keep the scheme | Yes | Inherited config (macOS), shared context (Linux), shared environment (Windows) |
| 12 | Other schemes untouched | Yes | Only registered names are intercepted |
| NF | 20 files about as fast as HTTP | Yes | No network stack; bounded by handler speed |
| NF | 50 concurrent requests, no mix-ups | Yes | Global ids, a locked table, exactly-once |
