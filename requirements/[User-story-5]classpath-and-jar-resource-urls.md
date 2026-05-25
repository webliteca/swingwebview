# Story Decomposition: Classpath / JAR Resource URLs

## INVEST Analysis

### Abstract Task: "Let a caller navigate any `WebView`, `EmbeddedWebView`, or `OffscreenWebView` to a `classpath:`-prefixed or `jar:file:.../app.jar!/...` URL and have the embedded page (and its relative-URL sub-resources) served from the JVM's classpath."

**Analysis Dimensions**:
- **Core Responsibility**: Every navigate call site in the library today (`WebView.url(String)`, `EmbeddedWebView.navigate(String)`, `OffscreenWebView.navigate(String)`) hands its URL string verbatim to the native engine. The native engines understand `http(s):`, `file:`, `data:` and nothing else — `classpath:` and `jar:` go to the engine's error page. This task adds a Java-side interception layer that recognises those two URL forms, lazily starts a single embedded HTTP loopback server in the JVM, registers a per-WebView path token that maps to the caller's `ClassLoader` (or to the resolved JAR file), and rewrites the user-supplied URL to `http://127.0.0.1:<port>/<token>/<resource-path>` before it reaches the native engine. The embedded page sees a `http://127.0.0.1:...` origin, so relative sub-resource fetches (CSS, JS, images, `fetch(...)`) come back through the same loopback server and resolve against the same classpath / JAR scope.
- **Primary Operations**:
  1. Detect `classpath:` and `jar:` URLs at every navigate entry point and route them through a single shared rewriting helper.
  2. Lazily start (and at JVM-exit shut down) a single `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an OS-chosen ephemeral port, shared across every `WebView` / `EmbeddedWebView` / `OffscreenWebView` in the JVM.
  3. For each navigate that uses one of the two new URL forms, allocate a fresh 128-bit token, capture `Thread.currentThread().getContextClassLoader()` (for `classpath:`) or resolve the JAR file (for `jar:`), and register the token → resolver mapping with the server. Token registration is per-navigation, not per-WebView lifetime (a follow-up nav to a different `classpath:` URL gets a fresh token; the previous token is left in place so any pending sub-resource requests still complete, and is invalidated when the WebView is disposed).
  4. Resolve incoming HTTP requests against the registered resolver, stream bytes back with a content-type derived from `Files.probeContentType` / `URLConnection.guessContentTypeFromName` (with explicit fallbacks for `.html`, `.htm`, `.css`, `.js`, `.mjs`, `.json`, `.svg`, `.wasm`), and respond with 404 for unknown tokens or missing resources.
  5. On WebView disposal, deregister all tokens belonging to that WebView so the classpath stops being reachable.
- **Key Constraints**:
  - The server **only** binds to `127.0.0.1` — never `0.0.0.0`, never `::` — so no off-host process can reach it.
  - Each WebView gets a fresh random 128-bit token (`java.security.SecureRandom`) per navigation. A request with an unrecognised token returns `404 Not Found`, never `400`, so an attacker probing the port cannot distinguish "wrong token" from "wrong path".
  - The classpath resolver uses **the context ClassLoader captured at `url(...)` call time**, not the resolver's own ClassLoader. This matches Spring / Jakarta convention and lets the caller's module-system / framework loader contribute resources.
  - `..` segments in the request path are rejected (`403 Forbidden`) before being handed to the ClassLoader, so a malicious page cannot escape the classpath scope into arbitrary disk reads via `getResourceAsStream("../../../etc/passwd")`-style requests.
  - The rewriting must happen at all three navigate call sites in the library (`WebView`, `EmbeddedWebView`, `OffscreenWebView`). The rewriting logic itself lives in a single shared helper class — no duplication.
  - The `HttpServer` lifecycle: lazily started on **first** classpath/jar navigation in the JVM, shared by every subsequent navigation across every WebView, shut down at JVM exit via a shutdown hook. Idle WebView count → 0 does **not** shut down the server (start-up cost amortises across the JVM run; the port stays put for any later WebView).
  - This story is for the in-library navigate paths. The Swing API surface (`WebViewComponent.setUrl(String)`) calls those paths via `EmbeddedWebView` / `OffscreenWebView`, so it transparently inherits the new behaviour without any new Swing-side API.
- **Technical Complexity**: Medium. JDK's `com.sun.net.httpserver` covers the server. Classpath resolution is one `getResourceAsStream` call. JAR resolution is a `JarFile` open + `getEntry` + `getInputStream`. The trickiest piece is making the rewriting cross-cut three navigate call sites without leaking server lifecycle responsibility into any single one of them, and making the per-navigation token registration thread-safe with concurrent navigates from different WebViews.
- **Business Complexity**: Low. The user-facing semantics are "load this bundled resource"; JavaFX WebView already establishes the prior art and user expectations.

### INVEST Evaluation

- ✅ **Independent**: Depends only on the existing navigate plumbing already in `WebView`, `EmbeddedWebView`, `OffscreenWebView`. No story-level dependency on outstanding work.
- ✅ **Negotiable**: Specific defaults agreed with user — single per-JVM server, thread-context ClassLoader at navigate time, random 128-bit per-WebView token, bound to `127.0.0.1`. All locked-in.
- ✅ **Valuable**: Closes a documented gap with JavaFX WebView. Lets apps bundle their UI inside their distribution JAR (the common case for desktop apps using `jpackage`, `jlink`, `Shrinkwrap`, `Capsule`) and navigate to it directly without first extracting to disk.
- ✅ **Estimable**: One server class, one rewriting helper, three two-line interception sites at navigate call points. ~3-5 days.
- ✅ **Small**: 3-5 days; all the work lives in `src/ca/weblite/webview/` with no native-code touch. Single story is appropriate — splitting by URL form (`classpath:` vs `jar:`) would force the second story to wait for the first's server infrastructure, and splitting by navigate site would force the second navigate path to wait for the first to land before its tests could pass. The work is one cohesive unit.
- ✅ **Testable**: The HTTP server, the URL rewriter, and the classpath resolver are all reachable from unit tests in `test/ca/weblite/webview/` without spawning a native WebView. The end-to-end navigate behaviour is observable from a small Swing harness via `WebViewComponent.setUrl("classpath:foo.html")` and `evalAsync("document.title")`.

**Conclusion**: Ready as a single story. No splitting required.

---

## [STORY-005-001] Classpath and JAR Resource URLs via Embedded Loopback Server

### Background

JavaFX WebView users can navigate directly to URLs of the form `jar:file:/path/app.jar!/foo.html` or use Java URL stream handlers to load `classpath:`-style resources, because JavaFX's `WebEngine` participates in Java's `URLStreamHandler` infrastructure. swingwebview cannot do this today — its navigate call sites pass the URL string verbatim to native engines (WKWebView on macOS, WebKitGTK on Linux, WebView2 on Windows) that have no notion of Java's classpath or of JAR-internal URLs. Any caller who has packaged HTML / JS / CSS inside their application JAR has two unappealing options today:

1. Extract every needed resource to a temp directory on startup, build a `file:` URL pointing at the extracted root, and clean up on shutdown. Works but adds boilerplate to every app, and the extraction has to be done eagerly or with a custom protocol handler in the page's JS — neither pleasant.
2. Run a separate HTTP server in the JVM and load resources over `http://localhost:...`. Works but requires every app to roll the server itself, opens an ad-hoc port per app, and ties the page's `Origin` to whatever port the app happened to grab.

This story adopts strategy 2 but lifts the server **into the library**, so the boilerplate disappears. A single `com.sun.net.httpserver.HttpServer` is lazily started on first `classpath:` / `jar:` navigation, shared across every WebView in the JVM, bound to `127.0.0.1` on an OS-chosen ephemeral port, and torn down by a JVM-exit shutdown hook. Each call to `WebView.url(String)` / `EmbeddedWebView.navigate(String)` / `OffscreenWebView.navigate(String)` that passes a `classpath:` or `jar:` URL is rewritten on the Java side to `http://127.0.0.1:<port>/<token>/<resource-path>` before reaching the native engine; the native engine sees a normal `http:` URL and follows it back into the JVM where the resource is served.

Two URL forms are accepted, each resolved differently:

- **`classpath:foo/bar.html`** — `foo/bar.html` is resolved against `Thread.currentThread().getContextClassLoader()` (captured at the moment `url(...)` is called). Leading slash is tolerated and stripped (`classpath:/foo.html` and `classpath:foo.html` resolve identically). This matches Spring's `ClassPathResource` convention.
- **`jar:file:/abs/path/app.jar!/foo/bar.html`** — the JAR file at `/abs/path/app.jar` is opened with `java.util.jar.JarFile` and `foo/bar.html` is read from inside it. The standard Java `jar:` URL syntax is honoured, including `jar:file:/path%20with%20spaces/app.jar!/inner.html` URL-encoded forms. JARs over `http(s):` (`jar:https://example.com/app.jar!/...`) are out of scope.

Access control has two layers. The **first layer is a per-WebView opt-in flag**, defaulting to **OFF**. Without explicitly calling `setLocalResourcesEnabled(true)` on a given WebView, any `classpath:` or `jar:` URL passed to `url(...)` / `navigate(...)` on that WebView throws `IllegalArgumentException` and no server activity occurs. This protects the common case of applications that use the WebView as a general-purpose browser — passing user-entered address-bar URLs straight to `url(...)` — from accidentally exposing the application's bundled resources to arbitrary user input. Bundled-UI applications opt their UI-loading WebView in once at construction; browser-style applications never call the setter and the WebView simply rejects `classpath:` / `jar:` URLs. The opt-in is **per-WebView instance**: an application embedding both a bundled-UI WebView and a browser WebView in the same JVM can enable local resources on the first and leave them disabled on the second.

The **second layer** is the loopback-server access control already described: each navigation on an opted-in WebView generates a fresh 128-bit token from `SecureRandom`. The rewritten URL embeds the token as the first path segment. A request whose first path segment does not match a registered token returns `404 Not Found` — never `400 Bad Request` and never a different code that would let an off-port attacker fingerprint the server. Because the server binds only to `127.0.0.1`, only processes on the same host can reach it at all; the token defends against same-host processes belonging to other users or to lower-privilege code in the same user account.

The threat model: layer 1 (the opt-in flag) defends against **user-supplied URLs reaching the classpath via the application's own WebView API surface** — the address-bar use case. Layer 2 (loopback bind + per-WebView token) defends against **off-host and same-host-other-process attackers**. Once a WebView has been opted in by the application, **its loaded page is trusted**: an in-page malicious script could enumerate the captured ClassLoader via `fetch('/<token>/...')` requests, but the application is responsible for only opting in WebViews that load known-trusted content. This matches how `file:` URLs work on every other UI toolkit.

Relative-URL sub-resources Just Work: the native engine sees the page as having origin `http://127.0.0.1:<port>` and base path `/<token>/<dir>/`, so `<img src="logo.png">` inside `classpath:ui/index.html` issues a GET for `/<token>/ui/logo.png`, which the server resolves against the same classpath scope. `fetch("data.json")`, `<link rel=stylesheet href="style.css">`, ES module imports, all follow the same path.

Out of scope for this story: write methods (PUT, POST, DELETE all return 405 Method Not Allowed); directory listings (a GET on `classpath:foo/` returns 404, not an index); byte-range requests for media streaming (`Range:` header is ignored and the full body is served — adequate for HTML/CSS/JS/JSON/images, suboptimal but functional for large media files); custom URI schemes registered with the native engine (that was approach #3 in the design discussion); `data:` URI optimisation for tiny payloads.

Key points:
- Business value: closes a documented JavaFX-WebView parity gap; lets apps bundle their UI in their JAR with no per-app server boilerplate.
- Relationship with other features: orthogonal to dialog handling (Canvas 11/12/13), DevTools (Canvas 9), async eval (Canvas 10). The interception lives at the navigate boundary; once the URL has been rewritten, everything downstream works unchanged.
- Why now: directly requested follow-up to the design-feasibility discussion that produced approaches #1/#2/#3; the user selected #2.

### Business Value

- Provide a **drop-in JavaFX-WebView replacement for the bundled-resources case**: callers who today write `webEngine.load(getClass().getResource("ui.html").toExternalForm())` can write `webView.setLocalResourcesEnabled(true).url("classpath:ui.html")` and get the same behaviour, including relative sub-resource resolution.
- Provide a **uniform URL form** that works identically across `WebView` (in-process top-level), `EmbeddedWebView` (Swing heavyweight), and `OffscreenWebView` (Swing lightweight), with no per-platform / per-component branching at the call site.
- Provide a **safe-by-default posture** for applications that use the WebView as a general-purpose browser: user-entered `classpath:` / `jar:` URLs are rejected with a clear exception unless the application has explicitly opted that WebView in. The address-bar exposure mistake — possible in JavaFX WebView, which has no such gate — is impossible by default here.
- Provide **loopback-isolated** resource serving: the embedded port is not reachable off-host and, within the host, individual classpath scopes are not enumerable by other processes without the per-WebView token.
- Provide a **zero-boilerplate** path for bundled-UI apps: a single `setLocalResourcesEnabled(true)` call at WebView construction is the entire opt-in; the caller does not start, stop, or even see the HTTP server; lifetime is managed by the library.

### Dependencies and Assumptions

- **Prerequisites**: No outstanding stories. The existing navigate plumbing in `WebView`, `EmbeddedWebView`, `OffscreenWebView` is the integration point.
- **Data assumptions**: No persisted state. The `ClassLoader` reference captured at navigate time is held for the lifetime of the issuing WebView (or until that WebView re-navigates to a different `classpath:`/`jar:` URL, at which point the prior token is left registered for in-flight sub-resource requests and is cleared at WebView disposal). Same for `JarFile` handles — opened lazily on first sub-resource request, closed on WebView disposal.
- **Integration points**:
  - `com.sun.net.httpserver.HttpServer` (JDK built-in, no extra dependency).
  - `java.security.SecureRandom` for token generation.
  - `java.lang.ClassLoader#getResourceAsStream(String)` for classpath resolution.
  - `java.util.jar.JarFile` + `JarEntry#getInputStream` for `jar:` resolution.
  - `java.nio.file.Files#probeContentType(Path)` and `java.net.URLConnection#guessContentTypeFromName(String)` for MIME detection, plus an explicit fallback table for `.html`, `.htm`, `.css`, `.js`, `.mjs`, `.json`, `.svg`, `.wasm`.
- **Business constraints**:
  - `setLocalResourcesEnabled(boolean)` MUST default to `false` on every WebView / `EmbeddedWebView` / `OffscreenWebView` / `WebViewComponent` instance. While the flag is `false`, any `url(...)` / `navigate(...)` call with a URL starting with `classpath:` or `jar:` MUST throw `IllegalArgumentException` synchronously, and no server activity (no lazy start, no token allocation, no map mutation) occurs.
  - The flag is **per-instance**. Different WebViews in the same JVM can independently be enabled or disabled.
  - When the flag transitions from `true` to `false` on an already-running WebView, **every token registered by that WebView MUST be invalidated immediately**. The currently loaded page's already-fetched bytes continue to render in the engine's memory, but any subsequent sub-resource fetch from the page returns `404 Not Found` from the server (because the token is no longer in the resolver map). This is the conservative interpretation: disabling means disabling for real, not "next navigation only".
  - The server MUST bind to `127.0.0.1` only. Binding to `0.0.0.0` or `::` is a security-relevant defect.
  - The server MUST be a JVM-singleton — no per-WebView server, no port-per-WebView.
  - Tokens MUST come from `SecureRandom` (not `Random`, not `Math.random()`) and MUST be at least 128 bits of entropy.
  - The captured `ClassLoader` reference for a `classpath:` URL MUST be `Thread.currentThread().getContextClassLoader()` evaluated at the moment the `url(...)` / `navigate(...)` call is made, NOT the resolver class's own loader. If the context loader is null (rare — only happens in some embedded JVM hosts), the fallback is `WebView.class.getClassLoader()`.
  - `..` (and URL-encoded equivalents `%2e%2e`, `..%2f`, `%2f..`) in the request path after token-stripping MUST cause a `403 Forbidden` response without ever invoking `getResourceAsStream`.
  - The JVM-exit shutdown hook MUST close the server cleanly (drain in-flight requests, close listening socket) and MUST be idempotent — repeated shutdown calls do not raise.

### Scope In

- New public per-instance opt-in API, present on every navigate-capable surface:
  - `WebView.setLocalResourcesEnabled(boolean enabled)` — returns `this` for chaining; default state is `false`.
  - `WebView.isLocalResourcesEnabled()` — returns the current flag state.
  - `EmbeddedWebView.setLocalResourcesEnabled(boolean enabled)` / `isLocalResourcesEnabled()` — same shape, default `false`.
  - `OffscreenWebView.setLocalResourcesEnabled(boolean enabled)` / `isLocalResourcesEnabled()` — same shape, default `false`.
  - `WebViewComponent.setLocalResourcesEnabled(boolean enabled)` / `isLocalResourcesEnabled()` — concrete `final` methods on the abstract base that store the flag locally (`protected` field), propagate to the underlying `EmbeddedWebView` / `OffscreenWebView` if attached, and replay at attach time (same pattern as `pendingUrl`, `pendingInit`, `pendingBindings`). No new abstract method.
  - The setter accepts both `true` and `false` at any point in the WebView's life — including after pages have already loaded. The `true → false` transition invalidates this WebView's registered tokens as described in Dependencies.
- New public API on `ca.weblite.webview.WebView`:
  - `WebView.url(String)` continues to accept any string; when the string starts with `classpath:` or `jar:` AND the WebView has local resources enabled, the new interception path runs before the native engine is told anything. When the string starts with `classpath:` or `jar:` AND local resources are NOT enabled, the call throws `IllegalArgumentException` with a message identifying the disabled-feature gate.
  - `WebView.url()` (the getter) returns the **original** caller-supplied URL string (`classpath:foo.html`), not the rewritten loopback URL. The rewritten URL is an internal detail.
- New behaviour on `ca.weblite.webview.EmbeddedWebView.navigate(String)`: identical interception with identical opt-in gating, identical getter semantics.
- New behaviour on `ca.weblite.webview.OffscreenWebView.navigate(String)`: identical interception with identical opt-in gating, identical getter semantics.
- New behaviour on `ca.weblite.webview.swing.WebViewComponent.setUrl(String)` / `getUrl()`: transparently inherits the above through the `EmbeddedWebView` / `OffscreenWebView` it owns. Per-component opt-in flag mirrored to the underlying view.
- New internal class `ca.weblite.webview.ClasspathResourceServer` (package-private):
  - Singleton, accessed via `ClasspathResourceServer.shared()`. Lazily creates the `HttpServer` on first access.
  - Methods `String register(ClassLoader loader, String basePath)` and `String register(File jarFile, String entryPath)` returning the rewritten `http://127.0.0.1:<port>/<token>/<resource-path>` URL.
  - Method `void unregister(String token)` to invalidate a token (used at WebView disposal).
  - The `HttpServer` is bound to `127.0.0.1:0` (OS-chosen port) and uses the JDK default executor (single-threaded suffices for loopback; the dispatcher can pool if a benchmark shows contention).
  - JVM-exit `Runtime.addShutdownHook` closes the server.
- New internal class `ca.weblite.webview.UrlInterceptor` (package-private):
  - Single static entry `static String maybeRewrite(String url, Object owner, boolean localResourcesEnabled)` invoked from each of the three navigate call sites. The `owner` is the WebView / `EmbeddedWebView` / `OffscreenWebView` instance issuing the navigate (used by the server for per-WebView token tracking and disposal cleanup). Returns either the same string unchanged (non-classpath, non-jar URL) or the rewritten loopback URL.
  - If `url` starts with `classpath:` or `jar:` AND `localResourcesEnabled` is `false`, throws `IllegalArgumentException("classpath/jar URLs are disabled on this WebView; call setLocalResourcesEnabled(true) to enable them.")`. No token is allocated, no server state mutates.
  - For `classpath:` URLs (when enabled): strips the scheme, strips a leading `/` if present, captures `Thread.currentThread().getContextClassLoader()` (or `WebView.class.getClassLoader()` if null), and calls `ClasspathResourceServer.shared().register(owner, loader, basePath)`.
  - For `jar:file:...!/...` URLs (when enabled): parses out the JAR path and the entry path (URL-decoded), validates the JAR file exists, and calls `ClasspathResourceServer.shared().register(owner, jarFile, entryPath)`.
  - For `jar:` URLs whose embedded URL is not `file:` (e.g. `jar:http://...`), throws `IllegalArgumentException` — out of scope. This check fires regardless of the opt-in flag state, so callers see the same error for an unsupported `jar:` form whether or not they've opted in.
- Server request handling:
  - Path format `/<token>/<resource-path...>` — token is the first segment, the rest is the resource path inside the registered scope.
  - Unknown token → `404 Not Found`, empty body, `Content-Type: text/plain; charset=utf-8`.
  - Missing resource → `404 Not Found`.
  - `..` segment in resource-path (or URL-encoded equivalents) → `403 Forbidden`, empty body.
  - Non-GET/HEAD method → `405 Method Not Allowed` with `Allow: GET, HEAD` response header.
  - On success → `200 OK`, full body streamed, `Content-Type` from `Files.probeContentType` then `URLConnection.guessContentTypeFromName` then the static fallback table then `application/octet-stream`.
  - `HEAD` requests return the same headers as `GET` but with empty body and correct `Content-Length`.
- WebView disposal hook:
  - `WebView` does not have an explicit dispose method today — its peer is zeroed when `webview_run` returns, in `show()` (around `WebView.java:314`+). The interceptor registers tokens against the WebView instance and clears them when `peer` zeroes.
  - `EmbeddedWebView.dispose()` and `OffscreenWebView.dispose()` (existing methods) clear tokens belonging to that view.
  - Token clearing is non-fatal — if disposal misses a token, the JVM-exit shutdown hook still closes the whole server.

### Scope Out

- Write methods on the server (PUT, POST, DELETE, PATCH) — all return `405 Method Not Allowed`. Future stories may add scoped write-back for development workflows; not now.
- Directory listings — `GET /<token>/foo/` where `foo` is a directory returns `404 Not Found`, not an HTML index page.
- `Range:` request header support — the server ignores `Range:` and serves the full body. Adequate for the typical HTML/JS/CSS/JSON/image bundled-app use case; large `<video>` files will not seek smoothly. A follow-up story can add range support if needed.
- `If-None-Match` / `ETag` / `If-Modified-Since` / `Last-Modified` caching headers — the server does not emit them. Resources are re-streamed in full on every request. The native engine's in-memory HTTP cache mitigates the cost for the common case.
- WebSocket upgrade support — the server is HTTP/1.1 GET/HEAD only.
- `jar:http://` and `jar:https://` (remote JARs) — out of scope. Only `jar:file:` is honoured.
- A custom URI scheme registered with the native engine (e.g. `app:` on WKWebView via `WKURLSchemeHandler`, `jar:` on WebKitGTK via `webkit_web_context_register_uri_scheme`, `WebResourceRequested` on WebView2) — that was approach #3 in the design discussion; not chosen for this story.
- Per-resource MIME-type override API. Callers needing a specific MIME type (e.g. forcing `application/wasm` for a `.wasm` resource) get it via the built-in fallback table for known extensions. Custom override hooks can be added later if a caller hits an unsupported extension.
- Configuration knobs (bind address, port range, executor pool, classpath search roots) on `WebView` / `EmbeddedWebView` / `OffscreenWebView`. The defaults are the only behaviour. A follow-up story can add knobs if real-world deployment surfaces a need.
- Documentation in `README.md` of the new URL forms (a follow-up doc story).

### Acceptance Criteria

**ACs 1 through 25 assume `setLocalResourcesEnabled(true)` has been called on the WebView before the `url(...)` / `navigate(...)` call under test.** ACs 26 onward cover the opt-in gate itself.

#### AC1: Top-level classpath URL loads from the captured ClassLoader
**Given** a `WebView` with `setLocalResourcesEnabled(true)` whose surrounding code has placed `index.html` containing `<title>hi</title>` on the calling thread's context classpath, and the WebView has not yet been shown,
**When** the caller invokes `webView.url("classpath:index.html").show()` and JS observes `document.title`,
**Then** the observed value is `"hi"` and the native engine sees the page origin as `http://127.0.0.1:<port>` (some OS-chosen port).

#### AC2: Top-level jar URL loads from the named JAR
**Given** an arbitrary JAR file at `/tmp/test.jar` containing the entry `pages/welcome.html` whose body is `<title>from-jar</title>`,
**When** the caller invokes `webView.url("jar:file:/tmp/test.jar!/pages/welcome.html").show()` and JS observes `document.title`,
**Then** the observed value is `"from-jar"`.

#### AC3: Relative URLs inside a classpath document resolve from the same scope
**Given** a `WebView` loaded with `classpath:ui/index.html` whose body contains `<link rel="stylesheet" href="style.css"><script src="app.js">…document.title=window.K;</script>` and the classpath also contains `ui/style.css` (any contents) and `ui/app.js` (`window.K = "from-rel";`),
**When** JS observes `document.title` after page load,
**Then** the observed value is `"from-rel"` and the server log shows three successful GETs: `/<token>/ui/index.html`, `/<token>/ui/style.css`, `/<token>/ui/app.js`.

#### AC4: Relative URLs inside a jar document resolve from the same JAR
**Given** the same scenario as AC3, but with `/tmp/test.jar` containing `ui/index.html`, `ui/style.css`, `ui/app.js` instead of those being on the classpath, and the navigate URL `jar:file:/tmp/test.jar!/ui/index.html`,
**When** JS observes `document.title`,
**Then** the observed value is `"from-rel"`.

#### AC5: fetch() from a classpath page resolves through the same scope
**Given** a `WebView` loaded with `classpath:ui/index.html` whose body contains `<script>fetch('data.json').then(r=>r.json()).then(j=>document.title=j.message);</script>` and the classpath contains `ui/data.json` with body `{"message":"hello"}`,
**When** JS observes `document.title` after the promise resolves,
**Then** the observed value is `"hello"`.

#### AC6: Leading slash on classpath URL is tolerated
**Given** the same classpath setup as AC1 (`index.html` resolvable as `index.html` against the context loader),
**When** the caller invokes `webView.url("classpath:/index.html").show()`,
**Then** the page loads successfully and `document.title` is `"hi"` — `classpath:/index.html` and `classpath:index.html` are treated identically.

#### AC7: WebView.url() getter returns the caller-supplied string, not the rewritten URL
**Given** a `WebView` after `webView.url("classpath:ui/index.html")`,
**When** the caller invokes `webView.url()` (the getter),
**Then** the return value is exactly `"classpath:ui/index.html"` — not `"http://127.0.0.1:<port>/<token>/ui/index.html"`.

#### AC8: EmbeddedWebView.navigate accepts classpath URLs
**Given** a `WebViewHeavyweightComponent` attached to a visible `JFrame`, with `index.html` on the calling thread's classpath containing `<title>embed-cp</title>`,
**When** the caller invokes `component.setUrl("classpath:index.html")` and JS observes `document.title` after attach,
**Then** the observed value is `"embed-cp"`.

#### AC9: OffscreenWebView.navigate accepts classpath URLs
**Given** a `WebViewLightweightComponent` attached to a visible `JFrame` on Linux, with `index.html` on the calling thread's classpath containing `<title>off-cp</title>`,
**When** the caller invokes `component.setUrl("classpath:index.html")` and JS observes `document.title` after attach,
**Then** the observed value is `"off-cp"`.

#### AC10: Missing classpath resource returns 404 (page shows native engine's error)
**Given** a `WebView` whose context classpath does **not** contain `does/not/exist.html`,
**When** the caller invokes `webView.url("classpath:does/not/exist.html").show()`,
**Then** the embedded HTTP server returns `404 Not Found` for the resource request, and the native engine displays its standard "page not found" error (the WebView does not crash and remains responsive to further navigations).

#### AC11: Missing JAR entry returns 404
**Given** `/tmp/test.jar` exists but does not contain the entry `missing.html`,
**When** the caller invokes `webView.url("jar:file:/tmp/test.jar!/missing.html").show()`,
**Then** the embedded HTTP server returns `404 Not Found` and the engine shows its error page.

#### AC12: Path traversal is rejected with 403
**Given** a `WebView` loaded with any registered classpath token (e.g. via `webView.url("classpath:index.html")`),
**When** an attacker page (or a probe) directly issues `GET http://127.0.0.1:<port>/<token>/../etc/passwd` or `GET http://127.0.0.1:<port>/<token>/%2e%2e/secret`,
**Then** the server responds `403 Forbidden` without calling `getResourceAsStream` (which is observable by the absence of a "ClassLoader lookup" log entry — implementations may verify this via a wrapping ClassLoader in a unit test).

#### AC13: Unknown token returns 404 (not 400 or 403)
**Given** the embedded HTTP server is running with at least one valid registered token,
**When** a probe issues `GET http://127.0.0.1:<port>/0123456789abcdef0123456789abcdef/index.html` with a token that was never registered,
**Then** the server responds `404 Not Found`, identical in body and status to a "registered token but missing resource" response — a probe cannot distinguish "wrong token" from "right token, missing path".

#### AC14: Server binds only to 127.0.0.1
**Given** a `WebView` after `webView.url("classpath:index.html")` has triggered server start-up,
**When** an inspector reads the server's bind address,
**Then** the address is `127.0.0.1` and the address is not `0.0.0.0` and not `::` — connections from any non-loopback interface (or from `::1` if not explicitly bound) MUST be impossible at the socket level.

#### AC15: Token is 128 bits of cryptographic entropy
**Given** the rewriting helper is invoked 1000 times with the same classpath URL `classpath:foo.html`,
**When** the 1000 returned URLs are compared,
**Then** all 1000 tokens are distinct, each token is exactly 32 lower-case hex characters (128 bits), and no token is a prefix of any other (this is automatic for fixed-length, but explicit in the AC for clarity).

#### AC16: Non-GET/HEAD methods return 405
**Given** the server is running with any valid registered token,
**When** a probe issues `POST http://127.0.0.1:<port>/<token>/anything`,
**Then** the server responds `405 Method Not Allowed` with the `Allow: GET, HEAD` response header set.

#### AC17: HEAD returns headers without body
**Given** a `WebView` after `webView.url("classpath:index.html")` (5-byte body, say `<h1/>`),
**When** a probe issues `HEAD http://127.0.0.1:<port>/<token>/index.html`,
**Then** the response is `200 OK`, the body is empty, `Content-Length` is `5`, and `Content-Type` is `text/html` (possibly with `; charset=…`).

#### AC18: Content-Type is set from a sensible fallback table for known extensions
**Given** classpath resources `a.html`, `b.css`, `c.js`, `d.mjs`, `e.json`, `f.svg`, `g.wasm` are loaded in turn,
**When** each is served by the embedded HTTP server,
**Then** the `Content-Type` response header is `text/html`, `text/css`, `application/javascript` (or `text/javascript`), `application/javascript` (or `text/javascript`), `application/json`, `image/svg+xml`, `application/wasm` respectively — independent of whether `Files.probeContentType` happens to know about them on the local OS.

#### AC19: Server is a JVM-singleton across multiple WebViews
**Given** the first `WebView.url("classpath:a.html")` call binds the server to port `P`,
**When** the caller subsequently creates a second `WebView` (or `WebViewHeavyweightComponent`) and calls `url("classpath:b.html")`,
**Then** the second WebView's rewritten URL uses the same port `P` — no second server is created, no second port is bound.

#### AC20: Server shuts down at JVM exit
**Given** a JVM that has started the server via at least one `classpath:` navigation,
**When** the JVM exits normally (`System.exit(0)` or natural shutdown after the last non-daemon thread dies),
**Then** the listening socket on port `P` is released before the JVM terminates (a subsequent `nc -z 127.0.0.1 P` from a follow-up process succeeds within 1 second of JVM exit, proving the port was freed cleanly).

#### AC21: jar URLs with URL-encoded spaces resolve
**Given** a JAR at `/tmp/dir with spaces/test.jar` containing the entry `index.html` (body `<title>spaced</title>`),
**When** the caller invokes `webView.url("jar:file:/tmp/dir%20with%20spaces/test.jar!/index.html").show()`,
**Then** the page loads successfully and `document.title` is `"spaced"`.

#### AC22: jar:http URLs throw IllegalArgumentException
**Given** any WebView before `url(...)` is called,
**When** the caller invokes `webView.url("jar:http://example.com/app.jar!/foo.html")`,
**Then** the call throws `IllegalArgumentException` with a message identifying the unsupported scheme — out of scope per this story.

#### AC23: ClassLoader is captured at url(...) call time, not at request time
**Given** thread `T1` invokes `webView.url("classpath:resource.html")` while `T1`'s context classpath contains `resource.html` (`<title>T1</title>`), and thread `T2` (which has a different classpath without `resource.html`) actually issues the HTTP request to the loopback server some time later,
**When** the loopback server resolves the request,
**Then** the served body is `<title>T1</title>` — the resolution uses `T1`'s captured ClassLoader, not the requesting thread's.

#### AC24: WebView disposal releases the token
**Given** a `WebView` registered a token via `webView.url("classpath:index.html")` and the loopback server has logged that token as registered,
**When** the `WebView`'s native peer is destroyed (top-level: window closed; embedded: `dispose()`; offscreen: `dispose()`),
**Then** within 5 seconds, a fresh `GET http://127.0.0.1:<port>/<that-token>/index.html` returns `404 Not Found` (because the token is no longer registered) — exactly the same response as for a never-registered token.

#### AC25: Second navigate to a different classpath URL gets a fresh token
**Given** a `WebView` has just navigated to `classpath:first.html` and received rewritten URL `http://127.0.0.1:P/<T1>/first.html`,
**When** the caller then calls `webView.url("classpath:second.html")`,
**Then** the rewritten URL has a token `T2 != T1`, and `T1` remains valid for at least 5 seconds after the second navigation (so any pending sub-resource requests from the first page still resolve), and is invalidated at WebView disposal.

#### AC26: Default state is local resources disabled
**Given** a freshly constructed `WebView` (or `EmbeddedWebView` / `OffscreenWebView` / any `WebViewComponent` subclass) on which `setLocalResourcesEnabled(...)` has never been called,
**When** the caller invokes `isLocalResourcesEnabled()`,
**Then** the return value is `false`.

#### AC27: classpath URL is rejected when local resources are disabled
**Given** a `WebView` in its default state (`isLocalResourcesEnabled()` returns `false`),
**When** the caller invokes `webView.url("classpath:index.html")`,
**Then** the call throws `IllegalArgumentException`, the exception's message mentions `setLocalResourcesEnabled`, the embedded HTTP server is NOT started (port 0 is not bound), and no token is allocated.

#### AC28: jar URL is rejected when local resources are disabled
**Given** a `WebView` in its default state,
**When** the caller invokes `webView.url("jar:file:/tmp/test.jar!/index.html")`,
**Then** the call throws `IllegalArgumentException` referencing `setLocalResourcesEnabled`, the embedded HTTP server is NOT started, and no token is allocated.

#### AC29: Enabling the flag lets classpath URLs succeed
**Given** a `WebView` on which `setLocalResourcesEnabled(false)` has been the state since construction, and `index.html` is on the classpath,
**When** the caller invokes `webView.setLocalResourcesEnabled(true).url("classpath:index.html").show()` and JS observes `document.title`,
**Then** the navigation proceeds normally and produces the same result as AC1.

#### AC30: Disabling the flag mid-life revokes existing tokens immediately
**Given** a `WebView` after `setLocalResourcesEnabled(true)` and `url("classpath:index.html")` (which registered token `T`), the page has loaded successfully and `T` resolves to a 200 response,
**When** the caller invokes `webView.setLocalResourcesEnabled(false)`,
**Then** a subsequent `GET http://127.0.0.1:<port>/<T>/index.html` returns `404 Not Found` within 100 ms of the setter returning, and `isLocalResourcesEnabled()` returns `false`.

#### AC31: Per-WebView granularity — one enabled, one not
**Given** two `WebView`s in the same JVM where `webView1.setLocalResourcesEnabled(true)` and `webView2` is left in its default disabled state, and `index.html` is on the classpath,
**When** the caller invokes `webView1.url("classpath:index.html")` (which succeeds) and then `webView2.url("classpath:index.html")` (which throws),
**Then** `webView1`'s navigation produces a valid rewritten URL and a token in the server's resolver map, AND `webView2`'s call throws `IllegalArgumentException` without affecting `webView1`'s token registration in any way.

#### AC32: Non-classpath / non-jar URLs are unaffected by the flag
**Given** a `WebView` in its default state (`isLocalResourcesEnabled()` returns `false`),
**When** the caller invokes `webView.url("https://example.com")` or `webView.url("data:text/html,<h1>hi</h1>")` or `webView.url("file:///tmp/foo.html")`,
**Then** the call succeeds (no exception thrown) and the URL is passed verbatim to the native engine — the gate only affects `classpath:` and `jar:` schemes.

#### AC33: jar:http URL throws the unsupported-scheme exception regardless of flag state
**Given** a `WebView` in either the disabled or enabled state,
**When** the caller invokes `webView.url("jar:http://example.com/app.jar!/foo.html")`,
**Then** the call throws `IllegalArgumentException` whose message identifies the unsupported `jar:http:` form — and crucially, **this is the same message in both states**. The opt-in gate's message ("classpath/jar URLs are disabled...") does NOT mask the more specific scheme-unsupported message when the flag is off.

### Non-Functional Expectations

- The first `classpath:` / `jar:` navigation in the JVM incurs the server start-up cost (one-shot, well under 100 ms on a warm JVM). Subsequent navigations reuse the existing server.
- The server's executor must not block any caller thread for longer than the resource read itself. A serial executor (the JDK default) is acceptable for the loopback use case; if a real-world bundled-app workload shows queueing delays, a small fixed thread pool can be substituted in a follow-up.
- The server's worst-case memory footprint for a typical bundled app (a few HTML/CSS/JS files, < 10 MB total) should stay below 1 MB resident overhead — i.e. the server holds no in-memory cache of resource bodies; each request re-streams from the ClassLoader / JarFile.
- The token comparison MUST be a constant-time comparison (`MessageDigest.isEqual` or hand-rolled XOR-OR over bytes) — not `String.equals` — so a same-host attacker cannot use timing side-channels to brute-force the token byte-by-byte.
- All four URL-encoded forms of `..` (`..`, `%2e%2e`, `%2E%2E`, `.%2e`, etc.) must be normalised and rejected before the request path is handed to the ClassLoader. A single URL-decode + canonicalisation pass is sufficient.
- Server log entries — if any — MUST NOT include the token in plaintext (use a redacted prefix like `<token-prefix>****`). Tokens leaking through stderr logs would defeat the per-WebView isolation.
- The opt-in flag MUST be checked **before** any URL parsing on the `classpath:` / `jar:` path, so that constructing a malformed `classpath:` URL on a disabled WebView produces the disabled-feature exception (not a parse error). Exception: `jar:http:` (AC33) is rejected with its own message regardless of flag state, because the unsupported-scheme error is more actionable than the disabled-feature error.

---

## Quality Checks

**STORY-005-001 (Classpath + JAR Resource URLs)**:
- ✅ All required sections present (Background, Business Value, Dependencies and Assumptions, Scope In, Scope Out, Acceptance Criteria, Non-Functional Expectations).
- ✅ ACs use Given-When-Then with concrete inputs (`classpath:index.html`, `jar:file:/tmp/test.jar!/pages/welcome.html`, `<title>hi</title>`) and observable outcomes (`document.title` equals specific string, HTTP status equals specific code).
- ✅ Business-language ACs — public API surface (`WebView.url`, `EmbeddedWebView.navigate`, `OffscreenWebView.navigate`, `WebViewComponent.setUrl`, `setLocalResourcesEnabled`) appears because that is the caller-visible contract. Internal class names (`ClasspathResourceServer`, `UrlInterceptor`) appear only in Scope-In / Dependencies, never inside AC bodies.
- ✅ Covers happy path (AC1-AC9: classpath top-level, jar top-level, relative URLs, fetch, leading-slash tolerance, getter, embedded and offscreen modes), validation / business rules (AC10-AC11: missing-resource 404; AC16-AC18: HTTP method handling and Content-Type), error / security conditions (AC12: traversal; AC13: token confusion; AC14: bind address; AC15: token entropy; AC22: scheme rejection; AC27-AC33: opt-in gate, default-off, mid-life disable, per-WebView granularity, pass-through for unaffected schemes), and lifecycle invariants (AC19-AC20: singleton server, shutdown; AC23-AC25: capture-time semantics, disposal, fresh-token-per-nav).
- ✅ At most three core functional points: (1) URL rewriting at the three navigate sites with per-WebView opt-in gating; (2) embedded HTTP server lifecycle; (3) classpath / JAR resolution.
- ✅ 3-5 days of work (one server class, one rewriting helper, three two-line interception sites, one opt-in flag plumbed through three views + one component, no native-code touch).

## Final INVEST Re-validation

| Property | STORY-005-001 |
|---|---|
| Independent | ✅ (no story-level dependency; touches only Java files in `src/ca/weblite/webview/`) |
| Complete | ✅ (full URL rewriting + server lifecycle + per-WebView token + ClassLoader & JarFile resolvers + disposal hook) |
| Valuable | ✅ (closes a documented JavaFX-WebView parity gap; removes per-app server boilerplate) |
| Estimable | ✅ (HttpServer + helper + 3 navigate interception sites; clear AC coverage) |
| Right-sized | ✅ (3-5 days; entirely Java, no native-code touch) |
| Testable | ✅ (server, rewriter, resolvers all unit-testable in `test/ca/weblite/webview/`; end-to-end observable via `WebViewComponent.setUrl` + `evalAsync`) |

Story passes INVEST.
