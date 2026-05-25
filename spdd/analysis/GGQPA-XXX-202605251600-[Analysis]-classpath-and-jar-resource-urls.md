# SPDD Analysis: Classpath / JAR Resource URLs

## Original Business Requirement

(Excerpt — full text preserved verbatim in `requirements/[User-story-5]classpath-and-jar-resource-urls.md`.)

One story (STORY-005-001) implementing approach #2 from the design discussion: an embedded loopback HTTP server that lets `WebView.url(...)`, `EmbeddedWebView.navigate(...)`, and `OffscreenWebView.navigate(...)` accept `classpath:foo/bar.html` and `jar:file:/path/app.jar!/foo/bar.html` URL forms. The Java layer rewrites such URLs to `http://127.0.0.1:<port>/<token>/<resource-path>` before handing them to the native engine; the native engine fetches the resource — and all relative sub-resources (CSS, JS, images, `fetch(...)`, ES module imports) — over loopback, where a single JVM-shared `com.sun.net.httpserver.HttpServer` resolves them against a per-WebView-registered `ClassLoader` (for `classpath:`) or `JarFile` (for `jar:`).

Cross-cutting invariants the contract requires:

- **Per-WebView opt-in gate, default OFF.** New API `setLocalResourcesEnabled(boolean)` / `isLocalResourcesEnabled()` on `WebView`, `EmbeddedWebView`, `OffscreenWebView`, and `WebViewComponent`. While `false` (the default), any `classpath:` or `jar:` URL handed to `url(...)` / `navigate(...)` throws `IllegalArgumentException` synchronously and no server activity occurs. This defends against the address-bar use case where a browser-style application passes user-entered URLs straight to the navigate API: typing `classpath:secrets.properties` does not escape the WebView's bundle without explicit application opt-in. Per-instance flag — an application embedding both a bundled-UI WebView and a browser WebView can independently configure them. The `true → false` transition invalidates that WebView's registered tokens immediately.
- **Two URL forms.** `classpath:foo.html` (Spring-style, resolved against `Thread.currentThread().getContextClassLoader()` captured at navigate time, leading `/` tolerated) and `jar:file:/abs/path/app.jar!/foo.html` (standard Java JAR URL syntax, URL-decoded; `jar:http(s):` rejected with `IllegalArgumentException` regardless of flag state — the unsupported-scheme error fires before / independent of the gate).
- **One JVM-shared server.** Lazily started on first `classpath:`/`jar:` navigation **from an opted-in WebView**, bound to `127.0.0.1` on an OS-chosen ephemeral port, never to `0.0.0.0`/`::`, shut down by a JVM-exit `Runtime.addShutdownHook`. Idle WebView count → 0 does not stop the server. WebViews that never opt in never trigger the server to start at all.
- **128-bit per-navigation token.** From `SecureRandom`, hex-encoded as the first path segment of the rewritten URL. Unknown token → `404 Not Found` (indistinguishable from missing-resource 404). Token comparison is constant-time. Each call to `url(...)`/`navigate(...)` allocates a fresh token; the previous nav's token remains valid for any in-flight sub-resource requests and is cleared at WebView disposal or on the `true → false` flag transition.
- **Path-traversal hardened.** `..` (and any URL-encoded variant) in the post-token path segments → `403 Forbidden` before any `getResourceAsStream` / `JarEntry` lookup.
- **HTTP/1.1 GET + HEAD only.** Other methods → `405 Method Not Allowed` with `Allow: GET, HEAD`. No directory listings (404 on directory GET), no `Range:` support (header ignored, full body streamed), no caching headers, no WebSocket upgrade.
- **MIME types.** `Files.probeContentType` → `URLConnection.guessContentTypeFromName` → static fallback table (`.html`, `.htm`, `.css`, `.js`, `.mjs`, `.json`, `.svg`, `.wasm`) → `application/octet-stream`.
- **Getter preserves the original.** `WebView.url()`, `WebViewComponent.getUrl()` return the caller-supplied `classpath:`/`jar:` string, not the rewritten loopback URL.
- **No new Swing-API surface.** `WebViewComponent.setUrl(String)` transparently inherits the new behaviour by routing through `EmbeddedWebView.navigate(String)` / `OffscreenWebView.navigate(String)`.
- **No native-code touch.** Entirely Java; the C/C++ sources under `src_c/` and `windows/` are not edited.

33 acceptance criteria covering happy path (top-level classpath/jar load, relative URLs, fetch, leading-slash tolerance, getter semantics, both Swing modes), validation (missing-resource 404), security at two layers (path traversal 403, token-confusion 404, bind address, token entropy, scheme rejection, constant-time comparison, no token in logs; AND opt-in gate default-off, classpath rejection when disabled, jar rejection when disabled, mid-life disable revoking tokens, per-WebView granularity, pass-through for unaffected schemes), and lifecycle (singleton server across multiple WebViews, JVM-exit shutdown, capture-time ClassLoader semantics, disposal-releases-token, fresh-token-per-navigation).

## Domain Concept Identification

### Existing Concepts (from codebase)

- **`ca.weblite.webview.WebView`** (`src/ca/weblite/webview/WebView.java`) — in-process top-level webview, no Swing involvement. `url(String)` setter at line 127-133 has two cases: peer not yet created (just stores `this.url = url`) and peer alive (also calls `WebViewNative.webview_navigate(peer, url)`). The eager-store path replays via `show()` at line 314 (`WebViewNative.webview_navigate(peer, url)`) inside the same method that calls `webview_run` and zeroes `peer` (line 320) on exit. **Two rewriting call sites in this class**: line 130 (post-peer setter), line 314 (show-time bind). The `url()` getter at line 139-141 returns the original `this.url` — must stay that way per AC7.

- **`ca.weblite.webview.EmbeddedWebView`** (`src/ca/weblite/webview/EmbeddedWebView.java`) — heavyweight engine wrapper. `navigate(String url)` at line 227-231 is a one-liner: `checkAlive()` then `WebViewNative.webview_embed_navigate(peer, url)`. The pre-attach store of `pendingUrl` lives one layer up in `WebViewHeavyweightComponent`, which replays via `embedded.navigate(pendingUrl)` at line 473 from inside `createPeer()`. So **a single rewriting site inside `EmbeddedWebView.navigate` covers both the post-attach `setUrl` path and the pre-attach replay path** — the cleanest factoring. Explicit `dispose()` at line 593-630 zeros `peer` and calls `webview_embed_destroy`. **Token cleanup hooks here.**

- **`ca.weblite.webview.OffscreenWebView`** (`src/ca/weblite/webview/OffscreenWebView.java`) — lightweight (Linux offscreen) engine wrapper. `navigate(String url)` at line 114-118 mirrors `EmbeddedWebView.navigate` exactly: `checkAlive()` then `WebViewNative.webview_offscreen_navigate(peer, url)`. The `WebViewLightweightComponent` parallel of the heavyweight pre-attach pattern replays at line 413 (`engine.navigate(url)` inside `setUrl`) and again at attach time. **One rewriting site here too.** Explicit `dispose()` at line 320-336 zeros `peer` and calls `webview_offscreen_destroy`. **Token cleanup hooks here.**

- **`ca.weblite.webview.swing.WebViewComponent`** (`src/ca/weblite/webview/swing/WebViewComponent.java`) — abstract Swing base. Declares abstract `setUrl(String)` / `getUrl()` at lines 158-161 implemented by the two concrete subclasses. **No new Swing API surface needed for this story** — the new behaviour rides on the underlying `EmbeddedWebView` / `OffscreenWebView` `navigate(...)` rewriting.

- **`ca.weblite.webview.swing.WebViewHeavyweightComponent`** (line 87) — stores `pendingUrl = "about:blank"` initial value; `setUrl(String url)` at line 112-118 sets `pendingUrl = url` then conditionally calls `embedded.navigate(url)`; `getUrl()` at line 121-123 returns `pendingUrl` (the original). `createPeer()` at line 416 replays `embedded.navigate(pendingUrl)` at line 473. **The Swing layer stores the original string; the underlying view does the rewriting.** No edit needed at this layer.

- **`ca.weblite.webview.swing.WebViewLightweightComponent`** — same shape: `setUrl` at line 410-416, `getUrl` at line 419-421, `addNotify` replay equivalent. Same conclusion: no edit needed at this layer.

- **`ca.weblite.webview.WebViewNative`** (`src/ca/weblite/webview/WebViewNative.java`) — JNI surface. The three navigate entry points (`webview_navigate` at line 84, `webview_embed_navigate` at line 156, `webview_offscreen_navigate` at line 308) accept opaque URL strings and pass them to the native engines verbatim. **Not touched by this story** — the rewriting happens before these are called.

- **`ca.weblite.webview.nativelib.NativeLibraryUtil`** / **`NativeLoader`** / **`BaseJniExtractor`** family — already manages temp-file extraction for native libraries via a similar lifecycle (extract on first use, register a JVM-exit hook). Provides architectural precedent for "JVM-singleton with lazy-init + shutdown-hook teardown" — the new `ClasspathResourceServer` follows the same pattern but for an HTTP socket instead of a temp directory.

- **`com.sun.net.httpserver.HttpServer`** — JDK 1.8 built-in (`pom.xml:39-40` confirms target source/target 1.8). `HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), backlog)` produces a server on the OS-chosen port; `server.createContext(path, handler)` registers a handler; `server.start()` launches the dispatcher; `server.stop(delay)` halts. Default executor is null (synchronous in-thread); a `ThreadPoolExecutor` can be substituted via `server.setExecutor(...)`. No external dep needed.

- **`java.security.SecureRandom`** — JDK built-in token source. `new SecureRandom().nextBytes(byte[16])` → 128 bits, hex-encoded to a 32-char lower-case string.

- **`java.lang.ClassLoader#getResourceAsStream(String)`** — returns `null` for missing resources, an `InputStream` otherwise. Thread-safe.

- **`java.util.jar.JarFile`** — opening is somewhat expensive (file-handle open + central directory scan); concurrent reads from the same `JarFile` are safe per the JDK docs (`JarFile` extends `ZipFile`, whose javadoc explicitly states "this class is thread safe"). One `JarFile` instance per distinct JAR path, cached at first use, closed at WebView disposal.

- **`java.nio.file.Files#probeContentType(Path)`** — OS-dependent, returns `null` for unrecognised extensions on some platforms. Cannot be relied on alone — the fallback table is required.

- **`java.net.URLConnection#guessContentTypeFromName(String)`** — bundled fallback table inside the JDK. Handles `.html`, `.css`, `.gif`, `.jpg`, `.png` and a few others; does **not** know `.mjs` (returns `null`), and on some JDKs returns `text/javascript` for `.js` and on others `application/javascript`. The explicit fallback table in the story (`.html` → `text/html`, `.mjs` → `application/javascript`, `.wasm` → `application/wasm`, etc.) is required to make AC18 deterministic across JDK versions.

- **`Runtime.addShutdownHook(Thread)`** — JDK built-in. Hooks run on JVM-exit, in unspecified order. Must be idempotent and exception-tolerant (an exception in a shutdown hook is logged and swallowed).

- **`java.util.concurrent.ConcurrentHashMap`** — chosen as the token → resolver map type because multiple `WebView`s may register tokens concurrently from different threads, and the HTTP server's dispatcher (whether the default in-thread executor or a pool) reads the map under concurrent request load.

- **JUnit 4.12** (`pom.xml:43-49`) — only test dependency. Existing tests under `test/ca/weblite/webview/` follow a unit-test style without spinning up a real engine (`EvalDispatcherTest.java`, `JavaScriptEvalExceptionTest.java`, `DialogDispatcherTest.java`). The new tests for the server, the rewriter, and the resolvers follow the same shape: unit-level, in-JVM, no native peer.

### New Concepts Required

- **Per-WebView opt-in flag (`localResourcesEnabled`)** — a `boolean` field on `WebView`, `EmbeddedWebView`, `OffscreenWebView`, and `WebViewComponent`, default `false`. Exposed via public `setLocalResourcesEnabled(boolean)` / `isLocalResourcesEnabled()` methods. Checked at the top of `UrlInterceptor.maybeRewrite(...)` before any URL parsing. On `WebViewComponent`, the field is stored locally (mirroring the `pendingUrl` pattern) and propagated to the underlying `EmbeddedWebView` / `OffscreenWebView` at attach time and on every subsequent setter call. The `true → false` transition triggers `ClasspathResourceServer.shared().unregisterAllFor(owner)` to invalidate any tokens that WebView previously registered.

- **`ca.weblite.webview.ClasspathResourceServer`** (package-private) — JVM-singleton manager that owns the loopback `HttpServer` lifetime, the token → resolver registry, and the per-WebView token-set tracking for disposal. Lazily binds the socket on first `register(...)` call from an opted-in WebView; never restarts after shutdown. WebViews that never opt in never cause the server to start.

- **Token → resolver registry** — a `ConcurrentMap<String, Resolver>` inside the server, where `Resolver` is a tagged-union of "classpath via captured `ClassLoader` + base path" and "jar via cached `JarFile` + entry base path". Each `Resolver` exposes a single `InputStream open(String resourcePath)` method that returns null for missing resources. The map is keyed by 32-char hex tokens; comparison happens once per request to look up the resolver (not constant-time at the map level — `ConcurrentHashMap.get` is not, structurally, constant time per the JDK's hashing). The constant-time check happens **after** the lookup: the request-path token bytes are compared byte-by-byte against the registered token bytes via `MessageDigest.isEqual` (which is constant-time) before the resolver is used. For requests whose token isn't in the map at all, the response is also a `404 Not Found` — observably identical, but a same-host attacker might still distinguish via lookup-time timing. This is acceptable because the same-host attacker without the token cannot reach a sub-millisecond timing oracle on `ConcurrentHashMap.get`; the constant-time comparison is belt-and-braces against the easier byte-comparison timing channel.

- **Per-WebView token-set tracking** — a `ConcurrentMap<Object, Set<String>>` keyed by the WebView identity (the `WebView` / `EmbeddedWebView` / `OffscreenWebView` instance), valued by the set of tokens that WebView has registered. On `WebView.show()` cleanup (line 320), `EmbeddedWebView.dispose()` (line 593), `OffscreenWebView.dispose()` (line 320), the WebView's entire set is iterated and removed from the resolver map.

- **`ca.weblite.webview.UrlInterceptor`** (package-private) — stateless helper with a single static method `static String maybeRewrite(String url, Object owner, boolean localResourcesEnabled)`. The `owner` parameter is the WebView / EmbeddedWebView / OffscreenWebView instance issuing the navigate; needed for disposal cleanup. Order of checks at the top of `maybeRewrite`:
  1. If `url` is null or starts with neither `classpath:` nor `jar:`, return `url` unchanged (`==` identity preserved). This fast path runs for every navigate and is the only cost on non-classpath URLs.
  2. If `url` starts with `jar:` and the embedded URL is `http:` or `https:` (not `file:`), throw `IllegalArgumentException("jar URLs with embedded scheme other than file are not supported: <url>")`. This fires regardless of the opt-in flag.
  3. If `localResourcesEnabled` is `false`, throw `IllegalArgumentException("classpath/jar URLs are disabled on this WebView; call setLocalResourcesEnabled(true) to enable them.")`.
  4. Parse and resolve: for `classpath:`, strip the scheme + leading `/`, capture `Thread.currentThread().getContextClassLoader()` (fallback `WebView.class.getClassLoader()`); for `jar:file:`, parse the JAR path and entry path (URL-decoded), validate the JAR exists.
  5. Call `ClasspathResourceServer.shared().register(owner, resolver, basePath)` and return the rewritten URL.

- **Server request handling** — a single `HttpHandler` registered at context `/`. Steps for each request:
  1. Method check — anything other than `GET`/`HEAD` → `405 Method Not Allowed` with `Allow: GET, HEAD`.
  2. Path split — first segment is the token, the rest is the resource path. Empty path or missing token → `404 Not Found`.
  3. Token presence + constant-time compare — `404` on mismatch.
  4. Path traversal check — URL-decode once, split on `/`, reject any segment equal to `..` or `.` or containing NUL → `403 Forbidden`.
  5. Resolver lookup — `null` resource → `404 Not Found`.
  6. Content-Type derivation — `Files.probeContentType` (best-effort, may throw `IOException` on some JDK versions for in-memory paths — caught) → `URLConnection.guessContentTypeFromName` → static fallback table → `application/octet-stream`.
  7. Body stream:
     - `GET`: stream the resource bytes to the response body.
     - `HEAD`: emit the same headers as `GET` (including `Content-Length` if known from `JarEntry.getSize()` or `URLConnection.getContentLengthLong()`; if unknown, omit `Content-Length`), empty body.

- **`HEAD` body-length resolution** — for `JarEntry`-backed resources, `JarEntry.getSize()` gives the precise length without reading bytes. For `ClassLoader.getResource(...)`-backed resources, `URLConnection.getContentLengthLong()` is used where supported (works for `file:` and `jar:` URLs returned by ClassLoader); when it returns `-1`, the server falls back to reading the entire stream into a temporary `ByteArrayOutputStream` to determine length, only for HEAD requests, accepting the memory cost as a rare-path bounded by typical classpath resource size.

- **JVM-exit shutdown hook** — registered once when the server first starts. Idempotent: calling it twice has no effect (the second `stop(...)` is silently absorbed). Calls `server.stop(1)` (1-second graceful drain), then iterates the JarFile cache calling `close()` on each (any exception is logged and swallowed so the hook never throws).

### Conceptual Relationships

- `ClasspathResourceServer` is a JVM-singleton — there is exactly one instance per JVM, holding exactly one listening socket. Multiple `WebView`s share it.
- `UrlInterceptor` is a stateless static helper. It calls `ClasspathResourceServer.shared()` at every invocation; it does not cache the server reference (since the singleton already does).
- Each `WebView` / `EmbeddedWebView` / `OffscreenWebView` instance owns a logical "set of registered tokens" that the server tracks externally (not stored on the WebView itself). When the WebView disposes, the server's external map removes the set.
- Each token in the resolver map is owned by exactly one WebView; tokens are never shared across WebViews. Different WebViews navigating to the same `classpath:foo.html` URL get different tokens and different entries in the map.
- A `JarFile` is shared across all tokens that resolved the same JAR path. The JarFile cache key is the absolute canonical path of the JAR file. The JarFile is closed when **the last** WebView that referenced it disposes (reference-counted by token-set membership), OR at JVM exit, whichever comes first.

### Key Business Rules

- **Default-deny on the opt-in gate.** Every navigate-capable instance starts with `localResourcesEnabled = false`. The gate is the FIRST check in `UrlInterceptor.maybeRewrite` after the fast-path scheme test and the unsupported-`jar:scheme` test, so a disabled WebView pays only a string-prefix comparison for non-matching URLs and a fast scheme-validity + flag-check for matching ones. No URL parsing, no token allocation, no server activity.
- **`jar:http:` rejection precedes the gate.** A `jar:http://...` URL on either an enabled or disabled WebView produces the unsupported-scheme `IllegalArgumentException`, not the disabled-feature exception. The unsupported-scheme error is more actionable for the caller (the URL is intrinsically wrong, not just disabled) so it takes precedence.
- **Flag-flip semantics.** Setting `true` on an already-`true` WebView is a no-op. Setting `false` on an already-`false` WebView is a no-op. Setting `true → false` invalidates all of that WebView's currently registered tokens immediately (synchronous removal from the resolver map); the page in the engine continues rendering already-fetched bytes but any future sub-resource request will 404. Setting `false → true` does NOT re-create tokens (there was nothing to recreate); it simply unblocks future `url(...)` calls.
- **Token uniqueness across the JVM lifetime.** No two registrations ever produce the same 32-char hex string. `SecureRandom` + 128 bits makes this astronomically unlikely; no explicit collision check is required.
- **Token integrity in transit.** Tokens appear only in the URL path on loopback. They are not logged in plaintext (a redacted form like `<token-prefix>****` is used in any stderr output the server emits).
- **Token constant-time comparison.** Once a request's path-segment token is parsed out, the comparison against the registered token MUST use `MessageDigest.isEqual` (constant-time over the input length). `ConcurrentMap.get` is not used as the *comparison* primitive; it's used only as the lookup, after which the comparison runs.
- **Path canonicalisation.** Request paths after token-stripping are URL-decoded exactly once. Re-encoding inside path segments (e.g., `%2e%2e` after the first decode produces `..`) means "decode once, then check segments" is sufficient. Double-encoding (`%252e%252e`) decodes to `%2e%2e` (literal text), which doesn't match `..` — safe.
- **Resource resolution is per-token-scoped.** A token's resolver may serve any resource the registered ClassLoader / JarFile can produce; the token does NOT restrict access to a subdirectory. A page loaded at `classpath:ui/index.html` can `fetch('../config.properties')` and reach anything the ClassLoader can see — this is by design (matches how a `file:` URL with relative links behaves). The token gates off-page (other-process, other-WebView) access; in-page malicious JS is not the threat model.
- **`jar:http:` is rejected at parse time, before any registration.** `IllegalArgumentException` propagates up through `url(...)` / `navigate(...)` — the caller sees it synchronously, no token is allocated, no server activity occurs.
- **`ClassLoader` capture is at navigate-time, not request-time.** The interceptor evaluates `Thread.currentThread().getContextClassLoader()` once, when `url(...)` is called, and that reference is held by the resolver for the WebView's lifetime. AC23 verifies this: a thread `T2` issuing the HTTP request can never substitute its own context loader.
- **Order of operations at the navigate call site.** The rewriting MUST happen AFTER `checkAlive()` (otherwise a navigate on a disposed WebView leaks a registration) and BEFORE the native call. The Java-side cost (~tens of microseconds to allocate a token and update a `ConcurrentHashMap`) is paid on every navigate, classpath or otherwise (one extra string prefix check for non-matching URLs).
- **Idempotency of disposal.** Disposing a WebView twice is safe: the second iteration of the WebView's token-set is empty.

## Strategic Approach

### Solution Direction

A single Java-side helper (`UrlInterceptor`) intercepts every `classpath:` / `jar:` navigation at the three existing navigate boundaries (`WebView.url(...)` show-time and post-peer-setter sites; `EmbeddedWebView.navigate(...)`; `OffscreenWebView.navigate(...)`), rewrites the URL to a loopback HTTP URL with a fresh per-navigation token, and registers the token → resolver mapping with a JVM-singleton `ClasspathResourceServer`. The server is a `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an OS-chosen port, lazily started on first registration, torn down at JVM exit. The native engine receives a normal `http://127.0.0.1:<port>/<token>/<resource-path>` URL, fetches it over loopback, and the server resolves the request against the captured `ClassLoader` (for `classpath:`) or cached `JarFile` (for `jar:`). Relative sub-resources inside the loaded document are issued by the native engine as standard same-origin GETs and resolve through the same scope.

Data flow:

```
caller →  view.navigate("classpath:foo.html")
       →  checkAlive()
       →  UrlInterceptor.maybeRewrite("classpath:foo.html", this)
              → ClasspathResourceServer.shared().register(thisView, loader, "foo.html")
              → returns "http://127.0.0.1:P/T/foo.html"
       →  WebViewNative.webview_*_navigate(peer, "http://127.0.0.1:P/T/foo.html")
       →  native engine GETs the loopback URL
       →  HttpServer handler: method=GET, token=T (constant-time OK),
                              path="foo.html" (no traversal),
                              resolver.open("foo.html") → InputStream → 200 OK + body
       →  page loads, parses <link href="style.css">,
          engine GETs http://127.0.0.1:P/T/style.css against same scope.
```

The library-internal store of the original URL (`WebView.url` field, `WebViewHeavyweightComponent.pendingUrl`, `WebViewLightweightComponent.pendingUrl`) is untouched — getters return the original `classpath:`/`jar:` string per AC7. The rewriting is a pure pre-native-call transformation of the string being handed to the JNI navigate call.

### Key Design Decisions

- **Decision: Per-WebView opt-in gate, default OFF.**
  - *Trade-off A* — opt-in flag, default `false`. New API: `setLocalResourcesEnabled(boolean)`. Bundled-UI apps flip it once at construction; browser-style apps leave it alone and user-typed `classpath:` URLs reject. **Chosen.** The default-deny posture is safe-by-default for the dominant browser-style use case and trivial to opt out of for bundled-UI apps.
  - *Trade-off B* — opt-in via base allowlist (`allowClasspathBase("ui/")`). Finer-grained but more API surface; bundled-UI apps need two calls (one per base they want to expose) instead of one boolean.
  - *Trade-off C* — opt-out, default `true`. Convenient for the bundled-UI case but unsafe-by-default for browser apps; the security gap stays open unless every browser app remembers to flip the flag.
  - *Trade-off D* — separate API method (`openLocal(...)`) that never accepts a String from address-bar code paths. Strongest isolation; biggest API change (callers can't just put `classpath:foo.html` into existing config files / address bars).
  - **Recommendation: A.** Matches the user's stated requirement: safe by default for browser-style apps, one-call opt-in for bundled-UI apps. The setter sits on every navigate-capable surface so callers don't need to reach for an underlying view.

- **Decision: Per-WebView vs JVM-global flag scope.**
  - *Trade-off A* — per-WebView instance. An app mixing a bundled-UI WebView (enabled) with a browser WebView (disabled) Just Works.
  - *Trade-off B* — JVM-global `System.setProperty("ca.weblite.webview.localResourcesEnabled", "true")`. Simpler implementation but cannot mix safe and unsafe WebViews in the same app.
  - **Recommendation: A.** Matches the rest of the library's per-instance configuration model (`setDialogHandler`, `setConsoleOutput`, `addOnBeforeLoad`).

- **Decision: Full ClassLoader access vs pattern-based per-resource gate after opt-in.**
  - *Trade-off A* — once opted in, the captured ClassLoader can serve anything reachable. Matches JavaFX semantics.
  - *Trade-off B* — even after opt-in, only resources matching an explicit pattern (e.g. glob `"ui/**"`) resolve. Defense-in-depth.
  - **Recommendation: A.** Once a page is loaded, its own JS can `fetch('/<token>/anything')` and reach anything in the loader's scope. A per-resource gate doesn't improve the in-page threat model; it only catches the address-bar case, which the opt-in flag already catches. Adding a glob-matching pass per HTTP request would also cost real CPU on every page load.

- **Decision: Rewriting site placement.**
  - *Trade-off A* — rewrite inside the public setter (`WebView.url(String)` / `EmbeddedWebView.navigate(String)` / `OffscreenWebView.navigate(String)`): one site per class, both pre-attach store and post-attach replay paths covered.
  - *Trade-off B* — rewrite at the JNI boundary (`WebViewNative.webview_navigate` etc.): one site per native method, but those are `native static` methods we shouldn't wrap.
  - **Recommendation: A.** For `EmbeddedWebView.navigate` and `OffscreenWebView.navigate` this is a single one-line change. For `WebView`, the setter has two interior call sites (line 130 post-peer setter, line 314 show-time bind) — both call sites must rewrite; factoring those into a private `private String rewriteAndStash(String url)` or just inlining the same `UrlInterceptor.maybeRewrite` call at both sites is the simplest fix. The cleanest factoring is one private helper `private void navigateNative(String original)` inside `WebView` that does `String rewritten = UrlInterceptor.maybeRewrite(original, this); WebViewNative.webview_navigate(peer, rewritten);` — called from both line 130 and 314.

- **Decision: Token → owner association.**
  - *Trade-off A* — pass the WebView identity (`Object owner`) to `UrlInterceptor.maybeRewrite` and to `ClasspathResourceServer.register`; the server tracks `owner → token-set` externally.
  - *Trade-off B* — store the token on the WebView itself (a `String lastClasspathToken` field).
  - **Recommendation: A.** Storing tokens on the WebView would force a public/protected field across three classes for what is conceptually internal bookkeeping. The external map approach keeps the existing WebView APIs unchanged. The map's keys are `WeakReference`-able if we worry about leaking a WebView reference when disposal somehow isn't called — but since each WebView class has an explicit cleanup site that the story already requires us to hook (line 320 of `WebView`, line 629 of `EmbeddedWebView`, line 334 of `OffscreenWebView`), strong references are fine and simpler.

- **Decision: JarFile lifecycle.**
  - *Trade-off A* — open the `JarFile` lazily at first sub-resource request inside the handler, share across tokens that resolved the same JAR path, close at JVM exit only.
  - *Trade-off B* — open the `JarFile` eagerly at `register(...)` time, close at WebView disposal.
  - *Trade-off C* — open eagerly, reference-count, close when the last referencing WebView disposes.
  - **Recommendation: C.** Eager open at register catches "missing JAR file" as an immediate `IllegalArgumentException` from `url(...)` (clear caller-visible error, no async failure later). Reference-count + close-on-last-dispose frees the file handle promptly in long-running JVMs while still amortising the open cost across multiple WebViews loading from the same JAR. The reference count is implicit: the resolver map has N entries pointing at the same `JarFile`; the JarFile cache (keyed by canonical JAR path) is consulted at unregister to count remaining references and close when count hits 0.

- **Decision: How aggressively to evict the previous-navigation token.**
  - *Trade-off A* — never auto-evict; tokens live until WebView disposal (AC24/AC25 explicitly require the previous token to remain valid for "at least 5 seconds" — implicitly satisfied by "until disposal").
  - *Trade-off B* — TTL-based eviction (e.g. 30 seconds after the next navigation).
  - **Recommendation: A.** Simpler. Each WebView typically has 1-3 active classpath nav points over its lifetime; the memory cost of holding the resolver references is negligible. Eviction policy can be added in a follow-up if observed memory pressure justifies it.

- **Decision: HEAD body-length strategy.**
  - *Trade-off A* — for HEAD, always read the whole stream to determine length, then discard.
  - *Trade-off B* — use `JarEntry.getSize()` / `URLConnection.getContentLengthLong()` where available, omit `Content-Length` where not.
  - *Trade-off C* — B, but fall back to A only when the caller is HEAD-specific and the length is unknown.
  - **Recommendation: C.** AC17 requires `Content-Length: 5` for HEAD on a 5-byte body. JarEntry size is precise. For ClassLoader resources, `URLConnection.getContentLengthLong()` works for `file:` and `jar:` URLs; for other (rare) URL types it may return `-1`. The fall-back-to-read approach for HEAD-only is bounded and acceptable since HEAD is rare in practice.

- **Decision: HttpServer executor.**
  - *Trade-off A* — `null` executor: synchronous in-thread handling.
  - *Trade-off B* — `Executors.newCachedThreadPool()`.
  - *Trade-off C* — small fixed pool (e.g. 4 threads).
  - **Recommendation: B.** Pages can issue many parallel sub-resource requests (a page with 20 `<img>` tags will batch up to 6-8 parallel HTTP connections per WebKit's per-origin connection limit). A `null` executor serialises every request on the server's accept loop and would visibly bottleneck page loads. A small fixed pool works but caps parallelism; cached is the safe default given that the threads are short-lived (each request is one stream-copy). The pool's threads are daemon threads (`Thread.setDaemon(true)`) so they don't keep the JVM alive after the shutdown hook runs.

- **Decision: Server bind hardening.**
  - *Trade-off A* — `InetAddress.getByName("127.0.0.1")` only.
  - *Trade-off B* — also bind `::1` (IPv6 loopback) for IPv6-preferred environments.
  - **Recommendation: A.** AC14 explicitly states "not `::`". Native engines on all three target platforms resolve `127.0.0.1` literal without DNS, so IPv6-only environments still reach the server via the IPv4-mapped path. Sticking to IPv4 loopback removes one binding surface area.

- **Decision: `classpath:` leading-slash handling.**
  - The story says `classpath:/foo.html` and `classpath:foo.html` resolve identically (AC6). After stripping `classpath:`, also strip a single leading `/` if present. Multiple leading slashes (`classpath:///foo.html`) are ambiguous; the recommendation is to strip exactly one leading slash, matching Spring's `ClassPathResource` behaviour.

### Alternatives Considered

- **A custom `URLStreamHandlerFactory` registered with `URL.setURLStreamHandlerFactory(...)`.** This was approach #1 in the design discussion that produced this story. Rejected because (a) `URL.setURLStreamHandlerFactory` is a JVM-singleton call that conflicts with any other framework in the same JVM doing the same thing, (b) the native engines don't consult Java's URL handlers anyway — they have their own HTTP / file resolvers — so the handler would only help if we extracted resources to a temp directory first, which has its own boilerplate cost.

- **A native custom-scheme handler per platform** (`WKURLSchemeHandler` on macOS, `webkit_web_context_register_uri_scheme` on Linux, `WebView2 WebResourceRequested` on Windows). Was approach #3 in the design discussion. Rejected for this story: requires ~6 new JNI paths and matching native code, ~1-2 weeks of work. The loopback-server approach (#2) reaches 90% parity at 3-5 days of work and no native-code touch. Approach #3 remains a future option if a same-origin-as-classpath requirement emerges (e.g., for service workers or strict CSP).

- **Per-WebView HTTP server (port per WebView).** Rejected because it multiplies the bind-port pressure on the host and the start-up cost per WebView creation, with no security benefit over the per-WebView token (the token alone suffices for inter-WebView isolation within the host).

- **Server bound to `0.0.0.0` with firewall reliance.** Rejected as a category-error: the right answer is "don't bind off-loopback", not "bind and hope the firewall stops the attacker."

- **In-memory resource cache (read resources once into a `byte[]`, serve from there).** Rejected because (a) memory cost scales with bundled-app size, (b) the native engine already maintains an in-memory HTTP cache that handles the common "load CSS once, reuse" case, (c) the loopback path is fast enough that the cache is unnecessary.

## Risk & Gap Analysis

### Requirement Ambiguities

- **Disposal cleanup for `WebView` (the in-process top-level class).** The story says "interceptor registers tokens against the WebView instance and clears them when `peer` zeroes" (line 119 of the story). The site is `WebView.java:320` — but this site is inside `show()` itself, after `webview_run` returns. The cleanup must be added inside `show()` immediately before or after `peer = 0L`. The Canvas needs to spell out the exact site (line 320 area). No new public dispose method is added to `WebView` (it has no such method today, by design — show() blocks until close).

- **Whether `UrlInterceptor.maybeRewrite` should accept all URLs or only call-time match.** The story says "Returns either the same string unchanged (non-classpath, non-jar URL) or the rewritten loopback URL." This implies a fast path: a one-line `if (url == null || (!url.startsWith("classpath:") && !url.startsWith("jar:"))) return url;` returning identity-preserved. Canvas should make this explicit so the call sites can confidently invoke `UrlInterceptor.maybeRewrite` on every URL without measurable overhead.

- **`classpath:` leading-slash handling — multiple slashes.** Story says `classpath:/foo.html` and `classpath:foo.html` resolve identically (AC6). What about `classpath:///foo.html`? The recommendation is "strip exactly one leading slash"; Canvas should confirm.

- **`classpath:` URL with no path (just `classpath:`).** Story doesn't cover. Canvas should specify behaviour — recommend `IllegalArgumentException("classpath URL has no resource path")` rather than registering an empty-path token.

- **`jar:file:` URL with no entry path (e.g., `jar:file:/tmp/x.jar!/`).** Story doesn't cover. Canvas should specify — recommend `IllegalArgumentException("jar URL has no entry path")` for parity with the `classpath:` empty-path case.

- **`jar:file:` URL pointing at a missing JAR file.** Story implies eager validation (Scope-In says "validates the JAR file exists"). Canvas should specify the exception type — recommend `IllegalArgumentException("JAR file not found: <path>")`, surfaced synchronously from `url(...)` / `navigate(...)`.

- **Constant-time comparison granularity.** AC's non-functional expectation says "constant-time comparison". `MessageDigest.isEqual(byte[], byte[])` is constant-time across the input length **only when both arrays are the same length**. If the request token is shorter (e.g. 31 chars), the comparison short-circuits. Canvas should specify: pad/truncate the request-side bytes to exactly 16 (or whatever the registered token length is) before comparison, OR length-check separately and then `isEqual`. Recommend: length-check first; if mismatch, still spend the same time as a successful 32-char compare by running `isEqual` against a dummy of the registered length, then return `404`.

- **Token format in rewritten URL.** Story says "32 lower-case hex characters" (AC15). Canvas should specify whether tokens carry any non-token discriminator (e.g., the WebView class name) — recommend no, just 32 hex chars, keeps the URL minimal.

- **`HEAD` on a non-existent resource.** Story doesn't specify. Symmetry with `GET` says `404`, empty body, no `Content-Length`. Canvas should specify.

- **Server log location.** Non-functional expectation says "Server log entries — if any — MUST NOT include the token in plaintext." Canvas should specify whether the server logs anything at all by default (recommend: no, silent in normal operation; only logs unexpected exceptions via `java.util.logging` or stderr with the token redacted).

### Edge Cases

- **JAR file deleted while a token is active.** If the user's JAR is at `/tmp/test.jar` and a subprocess deletes it after the WebView started, the next sub-resource request fails. The expected behaviour: `JarFile.getInputStream` raises `IOException`; the server catches it and returns `404 Not Found`. Canvas should specify this is the same code path as "entry missing".

- **WebView issues navigate to a non-`classpath:`/`jar:` URL after a `classpath:` navigation.** E.g., `webView.url("classpath:a.html"); ... webView.url("https://example.com")`. The previous token remains valid. If `https://example.com` later issues `fetch("http://127.0.0.1:P/<prev-token>/whatever")` — cross-origin — the loopback request succeeds. **Is this an issue?** Same-origin policy in the native engine prevents a cross-origin script from reading the loopback response unless the loopback server emits CORS headers (which it doesn't). The browser sees the response but JS cannot read the body across origins. **Documenting** this in the Canvas is enough; no code change required.

- **Browser-style application calls `webView.setLocalResourcesEnabled(true)` once for legitimate reasons, then later receives user-entered URLs.** Once enabled, the gate stops protecting against address-bar input on that WebView. **Mitigation strategy** (out of scope for this story, but worth flagging for caller-side documentation): apps that need both bundled UI and a browser feature should use two separate `WebView` / `WebViewComponent` instances — one enabled (for bundled UI), one disabled (for browser content). The per-WebView granularity supports exactly this pattern. Canvas should mention this in implementation norms / docs.

- **Caller toggles `setLocalResourcesEnabled(true)` immediately before each `url("classpath:...")` and back to `false` after.** This pattern would seem to give "narrow window" protection, but the `true → false` transition invalidates the just-allocated token immediately, breaking sub-resource fetches (`fetch("data.json")`, `<img src="logo.png">`) issued by the page after load. Caller-side anti-pattern; document as such. The right pattern is "enable once at construction, leave enabled for the WebView's life."

- **Concurrent `register(...)` from multiple WebViews.** `ConcurrentHashMap.put` is thread-safe. `JarFile` opening must be guarded — `computeIfAbsent` on the JarFile cache map is the idiomatic primitive.

- **WebView re-navigates to the same `classpath:foo.html` URL twice.** Per the story (AC25 paraphrased), each call gets a fresh token. So the second navigate registers a new token + a new resolver entry pointing to the same `ClassLoader`. Memory grows by one map entry per navigation; only WebView disposal cleans up. For an app that loops `webView.url("classpath:page.html")` thousands of times, the map grows unbounded. **Mitigation**: an optional per-WebView "fold tokens for identical (loader, basePath)" optimisation could deduplicate. Canvas should consider; the simpler approach is to accept the unbounded-growth-per-instance behaviour as a documented limit (real-world apps don't navigate thousands of times to the same URL).

- **`Range:` request header from media-playing page.** The server ignores it and serves full body. A `<video>` tag in a classpath HTML may not be seekable. Canvas should explicitly call this out (acceptable per Scope-Out, but Non-Functional Expectations could capture it more visibly for users).

- **Server-side timeouts.** `HttpServer` has no built-in idle-connection timeout. A slow / hung native-engine request could keep a thread alive indefinitely. Canvas should specify: rely on the native engine's own client-side timeout; the server doesn't impose one.

- **JVM uses a SecurityManager that denies `setURLStreamHandlerFactory` or `HttpServer.create`.** Since we don't call `setURLStreamHandlerFactory`, the first risk is moot. `HttpServer.create` requires `java.net.SocketPermission "127.0.0.1:0", "listen,resolve"` under a SecurityManager. Canvas should note that under a restrictive SecurityManager, `classpath:`/`jar:` navigation will fail with an `AccessControlException` propagating from `register(...)`. JDK 17+ deprecated the SecurityManager so this is a niche concern.

- **Server start-up race.** Two threads concurrently calling `ClasspathResourceServer.shared()` for the first time must produce exactly one server. Double-checked locking via a `synchronized` initializer, or `Holder` idiom (static inner class), is required.

- **Port-already-in-use unlikely.** `0` lets the OS choose; no race here.

- **Thread that captured the context ClassLoader was a temporary one (e.g., a Swing event handler with a custom context loader installed for the duration).** The captured reference is strong; the loader stays referenced for the WebView lifetime. If that loader was supposed to be GC'd, our reference prevents it. **Documented limitation**: holding a `classpath:` token holds the captured ClassLoader against GC. For typical apps this is the application classloader (effectively permanent); for module-system apps using ephemeral loaders, the implication is worth a sentence in Canvas norms.

- **Native engine's request thread vs server's request thread interaction.** The native engine issues HTTP requests from its own networking thread; the server dispatches to its executor (cached thread pool). The Java handler runs on a server thread, calls `ClassLoader.getResourceAsStream(...)` (which may run user code if the loader is a custom one), and streams the result back. No threading interaction with the WebView's UI thread or the EDT. The token-set and resolver map use `ConcurrentHashMap` so server-thread reads and EDT/UI-thread writes don't conflict.

### Technical Risks

- **`ConcurrentHashMap` lookup timing channel for unknown tokens.** Mitigation: the constant-time comparison runs against a registered token even when the map lookup misses — but only after the lookup happens. A skilled timing attacker on the same host could still distinguish "lookup miss" from "lookup hit + compare miss" via hash-function and bucket-walk timing. **Practical impact: negligible** — the attacker would need sub-microsecond timing on local CPU operations, which is below the noise floor of a same-host scheduler. Document as accepted residual risk.

- **Native engine ignoring `127.0.0.1` literal in favour of `localhost` DNS lookup.** All three platforms' engines treat `127.0.0.1` as a literal IP, no DNS, no `/etc/hosts` lookup. Not a real risk; confirmed by docs.

- **WebView2 service-worker registration.** A page served from `http://127.0.0.1:P/<T>/...` can register a service worker. The service worker is scoped to the URL prefix, which includes the token. If the page later re-navigates (new token), the old service worker scope is no longer reachable — effectively dead. **Behaviour-acceptable:** the page will register fresh on next load. Canvas should note that service workers don't persist across navigation tokens; in practice users of bundled-app webviews don't typically use service workers anyway.

- **`HttpServer.create` blocking initialization.** On a slow filesystem (Windows AntiVirus scanning the JDK) the first `HttpServer.create` can take a few hundred ms. Mitigation: the start is lazy — only the first `classpath:` / `jar:` nav pays the cost. Acceptable.

- **JarFile holding a file lock on Windows.** Windows files opened via `JarFile` are locked from being deleted/replaced. Long-lived JarFile references (per Decision C: close at last-WebView disposal) can prevent a developer from rebuilding the app's JAR mid-run. **Mitigation**: documented in Canvas norms; users hitting this can call `WebView.dispose()` to release. Acceptable.

- **Mixed-content / HTTPS mixed-content blocking.** A classpath page served over `http://127.0.0.1` cannot include resources over `https://` without triggering mixed-content warnings? No — the warning fires for `https:` pages including `http:` resources, not the other way around. Loopback HTTP including HTTPS resources is fine. Not a risk.

- **Memory growth from never-evicted tokens for a long-lived WebView that re-navigates thousands of times.** Documented above; mitigation deferred to a follow-up if observed in practice.

- **Bytes-streamed payload large enough to OOM the response buffer.** The handler streams in 8 KiB chunks (or whatever buffer size `InputStream → OutputStream` copy uses); the response body is not buffered fully in memory. No OOM risk for large resources.

- **End-to-end ACs require a running native engine and a real Swing harness.** AC1, AC3, AC5, AC8, AC9 cannot be unit-tested in a headless CI. They are acceptable as manual-integration ACs verifiable via a small demo app under `demos/`. Canvas should call this out and propose the demo location (e.g., `demos/WebViewClasspathDemo`). The other ~17 ACs are fully unit-testable.

### Acceptance Criteria Coverage

| AC# | Description | Addressable? | Gaps / Notes |
|---|---|---|---|
| AC1 | Top-level classpath URL loads from captured ClassLoader (with opt-in) | Partial (manual) | Needs real native engine; suitable as a manual / demo AC. Unit test confirms server returns the correct body for a stub ClassLoader. |
| AC2 | Top-level jar URL loads from named JAR | Partial (manual) | Same — manual exercise via demo + unit test of server resolution. |
| AC3 | Relative URLs from classpath document resolve | Partial (manual) | Real engine required to verify the engine issues the sub-resource GETs. Server-side test confirms relative-path handling correctness. |
| AC4 | Relative URLs from jar document resolve | Partial (manual) | Same as AC3. |
| AC5 | fetch() from classpath page resolves | Partial (manual) | Same. |
| AC6 | Leading slash on classpath URL tolerated | Yes (unit) | Direct rewriter unit test. |
| AC7 | `WebView.url()` returns caller-supplied string | Yes (unit) | Direct view test — call `url("classpath:...")`, then `url()`, assert equality. |
| AC8 | EmbeddedWebView.navigate accepts classpath URLs | Partial (manual) | Real engine needed; covered by AC1 logic essentially. |
| AC9 | OffscreenWebView.navigate accepts classpath URLs | Partial (manual) | Real engine + Linux env needed. |
| AC10 | Missing classpath resource → 404 | Yes (unit) | Server unit test with stub ClassLoader returning null. |
| AC11 | Missing JAR entry → 404 | Yes (unit) | Server unit test with real JarFile of a tmp JAR missing the entry. |
| AC12 | Path traversal → 403 | Yes (unit) | Server unit test with `..` / `%2e%2e` / mixed-case variants. The "without invoking getResourceAsStream" sub-clause needs a stub ClassLoader that asserts it was not called. |
| AC13 | Unknown token → 404 (not 400/403) | Yes (unit) | Direct HTTP probe via `URLConnection`. |
| AC14 | Server binds only to 127.0.0.1 | Yes (unit) | Inspect `HttpServer.getAddress()` after lazy init. |
| AC15 | Token is 128 bits of cryptographic entropy | Yes (unit) | Call rewriter 1000 times, verify distinct + 32-hex-char format. Statistical entropy test is harder; the SecureRandom source is implicit guarantee. |
| AC16 | Non-GET/HEAD → 405 | Yes (unit) | HTTP probe. |
| AC17 | HEAD returns headers without body | Yes (unit) | HTTP probe; `Content-Length` correctness needs the JarEntry.getSize / URLConnection.getContentLengthLong logic. |
| AC18 | Content-Type fallback table | Yes (unit) | HTTP probe with stub ClassLoader providing each extension. |
| AC19 | Server is JVM-singleton | Yes (unit) | Call rewriter twice from different "WebViews" (real instances), inspect bind port. |
| AC20 | Server shuts down at JVM exit | Hard (manual) | Cannot test inside JUnit because shutdown hooks fire on JVM exit, not test end. Acceptable as a manual smoke test via a small main(). Canvas should propose. |
| AC21 | jar URLs with URL-encoded spaces resolve | Yes (unit) | Server unit test with `/tmp/<random>/dir with spaces/test.jar`. |
| AC22 | jar:http URLs throw IllegalArgumentException | Yes (unit) | Direct rewriter unit test. |
| AC23 | ClassLoader captured at url(...) time | Yes (unit) | Server unit test: register from T1 with a marker ClassLoader; issue HTTP request from T2 with a different context loader; verify body matches T1's marker. |
| AC24 | WebView disposal releases the token | Yes (unit) | Register, then call view.dispose() (mock dispose for in-process WebView) or zero peer manually, then probe → 404. The "within 5 seconds" clause is over-generous; cleanup is synchronous. |
| AC25 | Second navigate gets fresh token | Yes (unit) | Direct rewriter test. The "previous remains valid for 5 seconds" is satisfied by "remains valid until dispose"; canvas should formalize that. |
| AC26 | Default state is local resources disabled | Yes (unit) | Trivial — instantiate each view type, assert `isLocalResourcesEnabled()` is `false`. |
| AC27 | classpath URL rejected when disabled | Yes (unit) | Call `url("classpath:...")` on default-state view, expect `IllegalArgumentException`. Verify server was not started by attempting to read its bind address (should be unbound). |
| AC28 | jar URL rejected when disabled | Yes (unit) | Same pattern as AC27 with a `jar:file:` URL. |
| AC29 | Enabling the flag lets classpath URLs succeed | Partial (manual) | The enable-then-load happy path needs the real engine to confirm document loads. The setter-state-flip behaviour is unit-testable. |
| AC30 | Disabling the flag mid-life revokes existing tokens immediately | Yes (unit) | Register, probe (200), flip flag, probe again (404), assert latency < 100 ms (cleanup is synchronous so this is effectively instant). |
| AC31 | Per-WebView granularity — one enabled, one not | Yes (unit) | Two view instances, opposite flag states, verify independent behaviour. |
| AC32 | Non-classpath / non-jar URLs are unaffected by the flag | Yes (unit) | Call `url("https://example.com")` on disabled view, assert no exception and the URL is forwarded to the native call (mockable via a `WebViewNative` test seam, or just observed via `webView.url()` returning the same string). |
| AC33 | jar:http URL throws the unsupported-scheme exception regardless of flag state | Yes (unit) | Two passes, flag false / flag true, both pass the same `jar:http:...` URL; assert exception message identifies the scheme (not the disabled-feature gate) in both cases. |

**Summary**: 25 of 33 ACs fully unit-testable. 7 are manual / integration ACs that need a real native engine and Swing harness (best exercised in a `demos/WebViewClasspathDemo` app, callable from `run-{linux,mac,windows}-classpath-demo.sh`). 1 AC (AC20, JVM-exit shutdown) requires a separate small `main()` smoke test.

**Coverage assessment**: Every AC is addressable. No AC requires functionality the proposed approach cannot provide. The unit-testable surface now covers (a) URL rewriting + server logic, (b) opt-in gate behaviour, (c) per-WebView granularity, (d) flag-flip lifecycle — the entire implementation risk surface. The integration-testable surface (ACs needing a native engine) is bundled into a single demo app and exercised manually.
