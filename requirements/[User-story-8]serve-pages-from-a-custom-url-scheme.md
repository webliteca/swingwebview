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
