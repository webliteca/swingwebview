---
generated_at: 2026-09-26T14:55:00-07:00
---

# REASONS Canvas: Custom URL Schemes — Linux WebKitGTK Coverage (008-001)

> Source story: `requirements/[User-story-8]serve-pages-from-a-custom-url-scheme.md` → **[STORY-008-001]**.
> Analysis: `spdd/analysis/GGQPA-XXX-202609261430-[Analysis]-serve-pages-from-a-custom-url-scheme.md`.
>
> **Where this sits.** It follows the downloads split ([[23-Browser-Initiated-File-Downloads]] /
> 24 / 25), as [[30-Custom-Url-Schemes-And-Macos-Coverage]] set out:
> - [[30-Custom-Url-Schemes-And-Macos-Coverage]] — the portable Java API, the dispatcher, macOS
>   and the demo. **Landed and verified on macOS.**
> - **this Canvas** — WebKitGTK;
> - [[32-Custom-Url-Schemes-Windows-Coverage]] — WebView2.
>
> **No Java API change.** Everything Canvas 30 built in Java — `WebViewSchemes`, the request and
> response types, `SchemeDispatcher`, the three JNI declarations, and the freeze in
> `EmbeddedWebView` / `OffscreenWebView` — is reused as it is. This Canvas replaces the GTK stub
> bodies of the three exports with a real implementation, teaches the Linux symbol loader to
> tolerate optional symbols, and gives the demo an automatic mode so the Linux engine can be
> checked without a person at the screen.

## REASONS-Implements

**Native**
- `src_c/webkit_loader.h` — **edited**:
  - eight new **mandatory** WebKit symbols (all present since WebKitGTK 2.2);
  - a new **optional** symbol list, `WK_WEBKIT_OPT_SYMS`, gated at compile time by
    `WEBKIT_CHECK_VERSION`, whose members may be null at run time;
  - the `WK_HAS(sym)` test macro.
- `src_c/webkit_loader.cpp` — **edited**: resolve the optional list without failing the load.
- `src_c/webkit_shim.h` — **edited**: redirects for every new symbol, mandatory and optional.
- `src_c/webview_embed.cpp` — **edited**, GTK only:
  - the request table `g_scheme_requests`;
  - `gtk_install_schemes_once()`, called on the pump thread before each new view in
    `gtk_create_engine` and `gtk_off_create_engine`;
  - the URI-scheme callback `gtk_scheme_request_cb`;
  - the responder `gtk_scheme_respond`;
  - the three exports' GTK branches: `available` becomes `JNI_TRUE`, and `respond` calls the
    responder.

**Java**
- `src/ca/weblite/webview/WebViewSchemes.java` — **edited**: class Javadoc only ("macOS and Linux
  serve schemes now; Windows follows in Canvas 32"). No behaviour change.

**Demo**
- `demos/WebViewSchemeDemo/src/ca/weblite/webview/demos/WebViewSchemeDemo.java` — **edited**: an
  automatic mode (`-Dschemedemo.auto=true`).
- `demos/WebViewSchemeDemo/README.md` — **edited**: Linux run instructions, the automatic mode,
  and the Linux limitations.
- `run-linux-scheme-demo.sh` — **edited**: header comment (Linux is supported now), and pass
  `SCHEMEDEMO_AUTO`.
- `run-mac-scheme-demo.sh`, `run-windows-scheme-demo.bat` — **edited**: pass `SCHEMEDEMO_AUTO`
  only.

**Docs**
- `README.md` — **edited**: "Custom URL schemes" platform coverage and the Linux limitations.

**Tests**
- No new Java tests: this Canvas adds no Java logic. The behaviour is checked by the demo's
  automatic mode, run under Xvfb on a machine with WebKitGTK (see Safeguards).

## Decisions (resolved here)

- **D1 · One registration on the default web context.**
  - Every view the library creates comes from `webkit_web_view_new()`, or from
    `webkit_web_view_new_with_related_view()` for popups. So all of them share **WebKit's default
    web context**, and the library never creates its own.
  - Schemes are therefore registered **once**, on `webkit_web_context_get_default()`, and reach
    every view: heavyweight, lightweight, popups and adopted popups (AC11) alike.
  - Both component modes are covered. Lightweight is the supported Linux mode and the one the demo
    runs by default.

- **D2 · When and where it happens.**
  - `gtk_install_schemes_once()` runs **on the GTK pump thread**, immediately before the
    `webkit_web_view_new()` call in `gtk_create_engine` and in `gtk_off_create_engine`. Both already
    run inside `GtkPump::instance().run_sync(...)`.
  - A static `bool` (pump-thread-only, so no lock) makes it run once.
  - It does nothing when `g_scheme_names` is empty, so an application that registers nothing gets
    byte-for-byte the old engine creation (Canvas 30 D2).
  - Java's `freezeForEngine()` calls `webview_scheme_install` before the native create, so the
    names are always in place when the first view is built.
  - Adoption (`existing_web` non-null) skips view creation but still calls the install, which is
    then a no-op.

- **D3 · What each scheme is registered as.** For each name, on the default context:
  1. `webkit_web_context_register_uri_scheme(ctx, name, gtk_scheme_request_cb, nullptr, nullptr)`;
  2. `webkit_security_manager_register_uri_scheme_as_secure(sm, name)` — a secure context (AC4);
  3. `webkit_security_manager_register_uri_scheme_as_cors_enabled(sm, name)` — `fetch` and XHR to
     the scheme are allowed (AC3).

  Not registered as *local*, *display-isolated* or *empty-document*: `demo://app` stays its own
  origin, and pages elsewhere cannot load it as a local resource.

- **D4 · Mandatory symbols.** Added to `WK_WEBKIT_SYMS`, all present since WebKitGTK 2.2 and so on
  every runtime the library already loads (Ubuntu 20.04's 2.28 and newer):
  - `webkit_web_context_get_default`
  - `webkit_web_context_register_uri_scheme`
  - `webkit_web_context_get_security_manager`
  - `webkit_security_manager_register_uri_scheme_as_secure`
  - `webkit_security_manager_register_uri_scheme_as_cors_enabled`
  - `webkit_uri_scheme_request_get_uri`
  - `webkit_uri_scheme_request_finish`
  - `webkit_uri_scheme_request_finish_error`

- **D5 · Optional symbols: a new loader capability.**
  - A new X-macro list, `WK_WEBKIT_OPT_SYMS(X)`, resolved from the WebKit handle by a
    `WK_RESOLVE_OPTIONAL` that **never sets `missing`**. A symbol absent at run time is a null
    pointer, not a load failure.
  - Mandatory symbols behave exactly as today.
  - Members are declared with `decltype(&sym)` like the rest, so each group is wrapped in a
    **compile-time** gate: a header too old to declare a symbol leaves its members out entirely.
    - `#if WEBKIT_CHECK_VERSION(2, 12, 0)`: `webkit_uri_scheme_request_get_http_method`.
    - `#if WEBKIT_CHECK_VERSION(2, 36, 0)`:
      - `webkit_uri_scheme_request_get_http_headers`
      - `webkit_uri_scheme_request_finish_with_response`
      - `webkit_uri_scheme_response_new`
      - `webkit_uri_scheme_response_set_status`
      - `webkit_uri_scheme_response_set_content_type`
      - `webkit_uri_scheme_response_set_http_headers`
      - libsoup: `soup_message_headers_new`, `soup_message_headers_append` and
        `soup_message_headers_foreach`, which exist with these signatures in both libsoup 2 (the
        4.0 API) and libsoup 3 (the 4.1 API).
    - `#if WEBKIT_CHECK_VERSION(2, 40, 0)`: `webkit_uri_scheme_request_get_http_body`.
  - The libsoup symbols resolve through `dlsym` **on the WebKit handle**. The handle's dependency
    tree includes the libsoup that WebKit itself loaded, so there is no new `DT_NEEDED` and no risk
    of loading the other libsoup generation.
  - `WK_HAS(sym)` is `(::g_wk.fn_##sym != nullptr)`. The `##` paste means the shim's redirect of
    `sym` is not expanded inside it. Every call site of an optional symbol sits inside the same
    `#if` as its declaration **and** behind `WK_HAS`.

- **D6 · The request** (`gtk_scheme_request_cb`, called by WebKit on the pump thread):
  1. `id = ++g_scheme_next_id`; `g_object_ref(request)` and store it in `g_scheme_requests` under
     `g_scheme_mutex`.
  2. URL: `webkit_uri_scheme_request_get_uri`.
  3. Method: `get_http_method` when present, else `"GET"`.
  4. Headers: `get_http_headers` plus `soup_message_headers_foreach` into alternating pairs, when
     present, else none.
  5. Body:
     - `get_http_body` present: read the returned `GInputStream` (transfer full) with
       `g_input_stream_read` in 16 KB chunks, up to `kSchemeMaxRequestRead` (16 MB + 1). A null
       stream is an empty body. `bodyAvailable = true`.
     - absent (WebKitGTK < 2.40): an empty body, `bodyAvailable = false` (Canvas 30 D12).
  6. Call `scheme_upcall_request(...)` **directly on the pump thread**. The dispatcher only builds
     the request and submits the handler to its executor, so the pump thread never waits on
     application code (Canvas 30 D5). This is the Linux upcall pattern the dialog and download
     bridges already use; there is no need for the detached thread macOS needs to stay off AppKit
     main.

- **D7 · The response** (`gtk_scheme_respond`, from any thread):
  - Copy the status, pairs and body, then `GtkPump::instance().run_async(...)` onto the pump
    thread. There:
    1. Find and **erase** the id in `g_scheme_requests` under the lock. Absent → drop the answer
       (already answered, Canvas 30 D6).
    2. Wrap the body: `g_bytes_new(data, len)` then `g_memory_input_stream_new_from_bytes`.
    3. **With the 2.36 response API** (`WK_HAS(webkit_uri_scheme_response_new)`):
       1. `webkit_uri_scheme_response_new(stream, len)`;
       2. `set_status(resp, status, nullptr)` — WebKit supplies the reason phrase;
       3. `set_content_type(resp, <the Content-Type pair>)`;
       4. every other pair → `soup_message_headers_new(SOUP_MESSAGE_HEADERS_RESPONSE)` +
          `soup_message_headers_append`, then `set_http_headers(resp, hdrs)`, which takes
          ownership;
       5. `webkit_uri_scheme_request_finish_with_response(req, resp)`, then `g_object_unref(resp)`.
    4. **Without it** (WebKitGTK < 2.36):
       - status 200–299 → `webkit_uri_scheme_request_finish(req, stream, len, contentType)`;
       - any other status → `webkit_uri_scheme_request_finish_error(req, err)` with a `GError` in
         the `webview-scheme` quark domain, `code = status`, message `"HTTP <status>"`. The page
         sees a network error rather than the status; this is documented as a limitation of old
         engines.
    5. `g_object_unref(stream)` and `g_object_unref(req)`.

- **D8 · Cancellation on Linux.**
  - WebKitGTK has **no public signal** telling the application that a scheme request was
    abandoned. So Linux never calls `onSchemeCancelled`; a request ends when it is answered, fails,
    or reaches the dispatcher's 504 after 30 s (Canvas 30 D7, D8). The table entry lives at most
    that long.
  - Finishing a request whose load has been stopped is safe: WebKit's `WebURLSchemeTask` reports
    "already stopped" and ignores it, and our `g_object_ref` keeps the request object valid until
    `gtk_scheme_respond` releases it.
  - Checked by the demo's automatic mode: it navigates away while the slow request is pending,
    and the process must keep running.

- **D9 · Capability.** Under `WEBVIEW_GTK`, `webview_scheme_available()` returns `JNI_TRUE`. The
  mandatory symbols of D4 are resolved in `JNI_OnLoad`, so if the library loaded at all, the
  feature works. The optional symbols only widen what a request carries (D6, D7).

- **D10 · The demo's automatic mode** (`-Dschemedemo.auto=true`, set by `SCHEMEDEMO_AUTO=1`):
  - Java watchdog: if no report arrives within 60 s, print "FAIL: no report" and exit 2.
  - The page, in auto mode (it learns this from `index.html?auto=1`, which Java loads instead of
    `index.html`), runs the checks in order and then sends a JSON report to
    `demo://app/api/report` as a **GET**, URL-encoded in the `r` query parameter. A GET and its URL reach
    Java on every engine, while a POST body does not on WebKitGTK < 2.40, which would lose the report:
    - `script` (the script ran), `origin` (`demo://app`), `secure` (`isSecureContext`),
      `storage` (`localStorage` round trip);
    - `post` (status 200 and the echoed body; on engines without request bodies the echo's
      `received` is `null`, reported as `"post-body-unavailable"` rather than a failure);
    - `slow` (the 2 s answer arrives, and `setInterval` ticks at least 5 times meanwhile — the UI
      thread was never blocked, AC5);
    - `broken` (status 500, AC6);
    - `missing` (`fetch("demo://app/no-such-page")`: answered 404 by the handler — an unknown path
      still reaches Java and its status reaches the page). A different host of the same scheme is
      a different origin, and Canvas 30 D11 grants CORS only to the request's own origin, so a
      cross-host fetch is not a check here.
  - After posting the report, the page starts a new `slow` request and navigates to
    `demo://app/index.html?done=1` without waiting (D8).
  - Java prints each result, then `PASS` and exits 0 if every check passed, else `FAIL` and exits
    1. The exit happens 3 s after the report, so the abandoned request has time to reach native.
  - Manual mode is unchanged.

## R · Requirements

- Give Linux applications the same **own-scheme pages, answered in Java**, that macOS got in
  Canvas 30: one registration, one handler, and the same behaviour in both component modes.
- Keep the library **loading on every WebKitGTK it loads today**. Newer request and response
  features are used where the engine has them and degrade, documented, where it does not.
- Keep **existing Linux applications unchanged**: nothing new runs unless a scheme is registered.
- Make the Linux engine **checkable without a person**: an automatic demo mode that exits 0 only
  when the story's checks pass.

## E · Entities

```mermaid
classDiagram
direction TB

class SchemeDispatcher {
    <<Java, Canvas 30, unchanged>>
    ~onSchemeRequest(long id, String method, String url, String[] headerPairs, byte[] body, boolean bodyAvailable)
    ~onSchemeCancelled(long id)
}
class SharedSchemeState {
    <<native, Canvas 30 D14>>
    +vector~string~ g_scheme_names
    +jobject g_scheme_dispatcher
    +atomic~long long~ g_scheme_next_id
    +mutex g_scheme_mutex
}
class GtkSchemeBridge {
    <<native, WEBVIEW_GTK>>
    +map~long long, WebKitURISchemeRequest*~ g_scheme_requests
    +gtk_install_schemes_once()
    +gtk_scheme_request_cb(WebKitURISchemeRequest*, gpointer)
    +gtk_scheme_respond(long long id, int status, vector~string~ pairs, vector~uchar~ body)
}
class WebKitDefaultContext {
    <<WebKitGTK>>
    +register_uri_scheme(name, callback)
    +security_manager: secure, cors_enabled
}
class WkFns {
    <<webkit_loader>>
    +mandatory: WK_WEBKIT_SYMS
    +optional: WK_WEBKIT_OPT_SYMS (nullable)
    +WK_HAS(sym) bool
}
class GtkPump {
    <<existing>>
    +run_sync(f)
    +run_async(f)
}

GtkSchemeBridge --> SharedSchemeState : reads names, allocates ids
GtkSchemeBridge --> WebKitDefaultContext : registers each scheme once
WebKitDefaultContext --> GtkSchemeBridge : request callback (pump thread)
GtkSchemeBridge --> SchemeDispatcher : onSchemeRequest (JNI)
SchemeDispatcher --> GtkSchemeBridge : webview_scheme_respond → gtk_scheme_respond
GtkSchemeBridge --> GtkPump : run_async (apply on pump thread)
GtkSchemeBridge --> WkFns : calls through; tests WK_HAS
```

## A · Approach

1. **Context-level, once.** Register every scheme on the default web context, on the pump thread,
   just before the first view is created. Every view, popup and adopted popup shares that context,
   so one registration covers them all, and nothing happens when nothing is registered.
2. **Mandatory where old, optional where new.** The register / finish / security-manager API is as
   old as the library's oldest supported engine, so it joins the mandatory list. Method, headers,
   bodies and the response object are newer; they go in a new optional list that resolves to null
   when absent, behind compile-time gates for older headers and `WK_HAS` checks at every call.
3. **Same round trip as macOS, with GTK's threads.** WebKit calls in on the pump thread; the
   request is stored by id and handed to Java at once; Java answers from any thread; the answer is
   marshalled back to the pump thread and applied only if the id is still pending.
4. **No Java logic.** Names, freeze, caps, headers, exactly-once and timeouts stay in Canvas 30's
   tested dispatcher. The native bridge only captures, stores and applies.
5. **Verify with the engine, not only the compiler.** The demo's automatic mode runs every check
   the story names that a page can observe, under Xvfb, and exits with a status a script can read.

## S · Structure

### Inheritance Relationships
1. No new classes or interfaces. The native bridge is free functions and one table in
   `webview_embed.cpp`, inside the existing `#ifdef WEBVIEW_GTK` region and `embed` namespace.

### Dependencies
1. `gtk_create_engine` and `gtk_off_create_engine` → `gtk_install_schemes_once()`, on the pump
   thread, before `webkit_web_view_new()`.
2. `gtk_install_schemes_once()` → the default web context and its security manager, through
   `g_wk`.
3. `gtk_scheme_request_cb` → `scheme_upcall_request` (Canvas 30) → `SchemeDispatcher`.
4. `Java_…_webview_1scheme_1respond` (GTK branch) → `gtk_scheme_respond` →
   `GtkPump::run_async`.
5. `webkit_loader.cpp` → the optional list, from the same WebKit handle as the mandatory one.

### Layered Architecture
1. Public API and dispatch: Java, Canvas 30, unchanged.
2. JNI exports: shared, with per-platform branches.
3. Engine bridge: GTK (this Canvas), Cocoa (Canvas 30), WebView2 (Canvas 32).
4. Symbol loader: mandatory and optional WebKit / libsoup symbols.

## O · Operations

### 1. `src_c/webkit_loader.h` (**edited**)
1. Add the eight D4 symbols to `WK_WEBKIT_SYMS`, keeping its alphabetical order.
2. After the JS-result lists, add:

   ```
   // Optional symbols (Canvas 31 D5): resolved when the runtime has them, null
   // otherwise.  Never fail the load.  Each group is also gated on the headers.
   #if WEBKIT_CHECK_VERSION(2, 12, 0)
   #define WK_OPT_2_12(X) X(webkit_uri_scheme_request_get_http_method)
   #else
   #define WK_OPT_2_12(X)
   #endif
   #if WEBKIT_CHECK_VERSION(2, 36, 0)
   #define WK_OPT_2_36(X) … the seven WebKit symbols and three libsoup symbols of D5 …
   #else
   #define WK_OPT_2_36(X)
   #endif
   #if WEBKIT_CHECK_VERSION(2, 40, 0)
   #define WK_OPT_2_40(X) X(webkit_uri_scheme_request_get_http_body)
   #else
   #define WK_OPT_2_40(X)
   #endif
   #define WK_WEBKIT_OPT_SYMS(X) WK_OPT_2_12(X) WK_OPT_2_36(X) WK_OPT_2_40(X)
   ```
3. Declare the optional members in `WkFns` with the same `WK_DECL_MEMBER`.
4. Define `#define WK_HAS(sym) (::g_wk.fn_##sym != nullptr)`.

### 2. `src_c/webkit_loader.cpp` (**edited**)
1. Add `WK_RESOLVE_OPTIONAL(handle, sym)`: the same `dlsym` assignment as `WK_RESOLVE`, without
   touching `missing`.
2. After the mandatory lists, `WK_WEBKIT_OPT_SYMS(WK_RESOLVE_OPTIONAL_WEBKIT)` on
   `g_webkit_handle`.
3. Update the header comment: mandatory symbols fail the load, optional ones never do.

### 3. `src_c/webkit_shim.h` (**edited**)
1. A redirect for each of the eight mandatory symbols.
2. The optional redirects, inside the same three `WEBKIT_CHECK_VERSION` gates.

### 4. `src_c/webview_embed.cpp` — GTK bridge (**edited**, `#ifdef WEBVIEW_GTK`)
Place it after the shared scheme state of Canvas 30, before the engine structs.
1. `static std::map<long long, WebKitURISchemeRequest *> g_scheme_requests;` guarded by
   `g_scheme_mutex`.
2. `static void gtk_scheme_request_cb(WebKitURISchemeRequest *request, gpointer)` — D6, steps 1–6.
   Build the header pairs with a static `soup_message_headers_foreach` callback that pushes name
   and value into a `std::vector<std::string>*`.
3. `static void gtk_install_schemes_once()` — D2 and D3. Pump thread only; static `bool done`;
   return if `done` or `g_scheme_names` is empty (in that case `done` stays false, which is
   harmless: the names never change after the freeze).
4. `static void gtk_scheme_respond(long long id, int status, std::vector<std::string> pairs,
   std::vector<unsigned char> body)` — D7.
5. In `gtk_create_engine` and `gtk_off_create_engine`, call `gtk_install_schemes_once();` inside
   the pump lambda, immediately before the `existing_web ? existing_web : webkit_web_view_new()`
   choice, with a comment citing Canvas 31 D2.
6. Remove the `__attribute__((unused))` from `scheme_upcall_request` now that GTK calls it (keep it
   on `scheme_upcall_cancelled`, which GTK does not call, D8).

### 5. `src_c/webview_embed.cpp` — the exports (**edited**)
1. `webview_scheme_available`: `JNI_TRUE` under `WEBVIEW_COCOA` **or** `WEBVIEW_GTK`.
2. `webview_scheme_respond`: add the `WEBVIEW_GTK` branch calling
   `embed::gtk_scheme_respond((long long)id, (int)status, pairs, bytes)`.
3. Update the comment above them: macOS and Linux now; Windows in Canvas 32.

### 6. `WebViewSchemes.java` (**edited**, Javadoc only)
The class comment's platform sentence becomes: "macOS and Linux serve schemes now; on Windows
`isSupported()` is false until Canvas 32."

### 7. The demo (**edited**) — D10
1. `WebViewSchemeDemo`:
   - read `Boolean.getBoolean("schemedemo.auto")`;
   - in auto mode, load `demo://app/index.html?auto=1` and start the 60 s watchdog;
   - serve `api/report`: decode the `r` query parameter (`URLDecoder`, UTF-8), print each `name: result` pair, then `PASS`/`FAIL`, answer 200, and exit
     0 or 1 after 3 s on a daemon timer;
   - strip `?…` and `#…` from the path before matching (already done).
2. `app.js`: when `location.search` contains `auto=1`, run the D10 checks in order with promises
   and POST the report; when it contains `done=1`, do nothing. Manual behaviour is unchanged.
3. The three run scripts: pass `"-Dschemedemo.auto=${SCHEMEDEMO_AUTO:+true}"` (shell) and the
   `SCHEMEDEMO_AUTO_FLAG` pattern of `run-windows-pdf-demo.bat` (batch).
4. `run-linux-scheme-demo.sh` header: replace the "arrives with Canvas 31" paragraph with what the
   demo serves, the lightweight default, and `SCHEMEDEMO_AUTO=1`.

### 8. `demos/WebViewSchemeDemo/README.md` and `README.md` (**edited**)
1. Demo README: Linux runs the same checklist; the automatic mode and its exit codes; how to run it
   under Xvfb (`xvfb-run -a env SCHEMEDEMO_AUTO=1 ./run-linux-scheme-demo.sh`).
2. `README.md`, "Custom URL schemes", **Platform coverage** bullet: macOS through a
   `WKURLSchemeHandler`; Linux (both modes) through a URI scheme on WebKitGTK's default web
   context, registered secure and CORS-enabled; Windows next. Add a **Linux notes** sub-bullet:
   - request bodies need WebKitGTK 2.40 (older: `bodyAvailable()` is false and the body is empty);
   - response status and headers need 2.36 (older: 2xx answers keep only their `Content-Type`,
     other statuses reach the page as a network error);
   - WebKitGTK does not report abandoned requests, so Linux never cancels; an unanswered request
     ends at the 30 s timeout;
   - schemes are registered on the default web context, which the standalone `WebView` window
     also uses, but that window remains unsupported.

## N · Norms

1. **Every WebKit and libsoup call goes through `g_wk`.** No new `DT_NEEDED`; CI's
   `readelf -d` check (no webkit2gtk / javascriptcoregtk entries) must still pass, and no libsoup
   entry may appear either.
2. **Optional means both gates.** A call to an optional symbol sits inside its
   `WEBKIT_CHECK_VERSION` block and behind `WK_HAS`. Never call one unguarded.
3. **GTK objects only on the pump thread.** The request callback already runs there; responses
   reach it through `GtkPump::run_async`. Nothing else touches a `WebKitURISchemeRequest`.
4. **Every reference balanced.** One `g_object_ref` per stored request, released exactly once when
   it is answered; every stream, response and `GError` released on every path.
5. JNI exports stay inside the existing `extern "C"` block, with per-platform `#if` branches.
6. Comments cite `Canvas 31 Dn`. Java 8 for the demo changes.
7. Compile warnings: the GTK build stays free of new `-Wall` warnings.

## S · Safeguards

1. **Unchanged when unused:** with no scheme registered, `gtk_install_schemes_once` returns before
   touching WebKit, and engine creation is as before (Canvas 30 D2).
2. **Loads everywhere it loads today:** only symbols present since WebKitGTK 2.2 are mandatory.
   A runtime without an optional symbol loads and serves schemes with the documented reductions;
   it never fails `JNI_OnLoad` because of this Canvas.
3. **Builds against 4.0 and 4.1 headers:** optional symbols are compiled only when the headers
   declare them. All six CI native targets must stay green.
4. **Never blocks the pump thread on Java:** the upcall returns after enqueueing, and responses are
   applied from `run_async`. The only synchronous work on the pump thread is reading an in-memory
   request body of at most 16 MB + 1.
5. **Exactly once, never a dangling request:** an id is erased under the lock before its request is
   finished, so a second answer finds nothing; the stored reference keeps the object valid until
   then; a finish after the load stopped is ignored by WebKit (D8).
6. **Crash-free on navigation away:** the automatic demo navigates away with a request pending,
   and the process keeps running until its own exit.
7. **Verification:** before the Canvas is called done, the automatic demo passes under Xvfb in
   **lightweight** mode on WebKitGTK 4.1, and the manual checklist (including the popup, AC11) is
   run once. Any check that fails on Linux is listed in the README's Linux notes as a known
   limitation, not hidden.
8. **Scope:** no Java API change, no Windows change (Canvas 32), no cancellation upcall on Linux,
   and the standalone `WebView` window remains unsupported.
