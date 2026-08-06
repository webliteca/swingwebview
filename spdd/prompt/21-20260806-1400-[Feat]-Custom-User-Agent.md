---
generated_at: 2026-08-06T14:00:00-07:00
---

# REASONS Canvas: Custom User-Agent for the Embedded WebView

## R · Requirements

- Let callers override the embedded WebView's **User-Agent** so web
  apps that gate on the UA string accept the embedded browser. Expose
  `WebViewComponent.setUserAgent(String)` / `getUserAgent()`, wired to
  each engine's native custom-UA facility so the override changes the
  **actual HTTP `User-Agent` request header** (not merely the
  JS-visible `navigator.userAgent`). This is the header a server sees.

- **Why.** WKWebView's default UA omits the `Version/… Safari/…`
  tokens (`Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)
  AppleWebKit/605.1.15 (KHTML, like Gecko)`), so UA-sniffing sites
  reject it as "unsupported browser". A JS `navigator.userAgent` shim
  cannot fix server-side sniffing; only an engine-level custom UA can.

- **Contract.**
  - `setUserAgent(String ua)` — store the UA and apply it to the
    engine. `null` or empty **restores the engine default** (clears
    the override). May be called **before display** (stored, applied
    at peer attach, so the first request carries it) or **after**
    (applied live; takes effect on the next navigation — engines do
    not retro-rewrite the current page's request).
  - `getUserAgent()` — returns the pending/applied override, or
    `null` when none is set (engine default in force).
  - Accessor style follows `setUrl`/`getUrl` (get-style), not the
    no-`get` event style.

- **Backward compatible.** When `setUserAgent` is never called,
  `pendingUserAgent` is `null`, the native setter is never invoked,
  and the engine's default UA is used — byte-for-byte today's
  behaviour.

- Definition of Done:
  - `wv.setUserAgent("…Safari…")` before display makes the first and
    subsequent navigations send that `User-Agent` header on all three
    engines.
  - `setUserAgent(null)` / `setUserAgent("")` reverts to the engine
    default.
  - `getUserAgent()` reflects the last set value (`null` when unset).
  - A headless `WebViewComponentUserAgentTest` verifies the Java
    store/reset/get contract without a native peer.
  - README gains a "Custom user agent" subsection.

- Out of scope: per-request UA switching; UA-Client-Hints
  (`Sec-CH-UA`) customisation; spoofing `navigator.userAgent` from
  Java (that is a caller-side `addOnBeforeLoad` concern); the
  standalone in-process `WebView` class (embedded surface only, same
  boundary as Canvas 15/18).

## E · Entities

- **WebViewComponent** (modified). Gains:
  - `protected String pendingUserAgent = null;` (adjacent to
    `pendingUrl`/`debug`).
  - `public WebViewComponent setUserAgent(String ua)` — normalise
    empty to `null`, store, and apply live to the peer if attached.
  - `public String getUserAgent()` — return `pendingUserAgent`.
- **WebViewHeavyweightComponent** (modified). In `createPeer()`, after
  `EmbeddedWebView.attach`/`adopt` and **before** the initial
  `navigate`, apply `pendingUserAgent` when non-null via
  `embedded.setUserAgent(...)`. Live setter path when already attached.
- **WebViewLightweightComponent** (modified). Same at the
  `addNotify()` engine-create site, before `engine.navigate`.
- **EmbeddedWebView** (modified). `public EmbeddedWebView
  setUserAgent(String ua)` → `checkAlive()`;
  `WebViewNative.webview_embed_set_user_agent(peer, ua)`.
- **OffscreenWebView** (modified). `public OffscreenWebView
  setUserAgent(String ua)` → `webview_offscreen_set_user_agent(peer,
  ua)`.
- **WebViewNative** (modified). `native static void
  webview_embed_set_user_agent(long w, String ua);` and `native static
  void webview_offscreen_set_user_agent(long peer, String ua);`.
- **`src_c/webview_embed.cpp`** (modified — macOS + Linux),
  **`windows/webview_embed.cc`** (modified — Windows): per-engine
  setters + JNI bridges.
- **WebViewComponentUserAgentTest** (new), **README.md** (modified).

```mermaid
classDiagram
direction TB
class WebViewComponent {
  #pendingUserAgent String
  +setUserAgent(String) WebViewComponent
  +getUserAgent() String
}
class EmbeddedWebView { +setUserAgent(String) EmbeddedWebView }
class OffscreenWebView { +setUserAgent(String) OffscreenWebView }
class WebViewNative {
  +webview_embed_set_user_agent(long, String)$ void
  +webview_offscreen_set_user_agent(long, String)$ void
}
WebViewComponent ..> EmbeddedWebView : applies UA
WebViewComponent ..> OffscreenWebView : applies UA
EmbeddedWebView ..> WebViewNative : JNI
OffscreenWebView ..> WebViewNative : JNI
```

## A · Approach

1. **Pending-then-apply, mirroring `pendingUrl`.** The UA is caller
   state that must survive the peer create/destroy cycle. Store it on
   the component; apply at peer attach (before the first `navigate`)
   and live on subsequent `setUserAgent` calls when the peer exists.
   No override → native setter never called → engine default.

2. **Empty means default.** `setUserAgent(null|"")` stores `null`;
   the native setter, when invoked with `null`, clears the engine
   override (macOS `customUserAgent = nil`; GTK
   `webkit_settings_set_user_agent(settings, NULL)` restores default;
   WebView2 `put_UserAgent(L"")` — empty restores default).

3. **Per-engine native facility.**
   - macOS WKWebView: `-[WKWebView setCustomUserAgent:]` with an
     `NSString*` (or `nil` to clear).
   - Linux WebKitGTK: `webkit_settings_set_user_agent(
     webkit_web_view_get_settings(web), ua_or_null)`.
   - Windows WebView2: `ICoreWebView2Settings2::put_UserAgent(
     wide(ua))` (query the `_2` settings interface;
     no-op if unavailable on an old runtime).
   Each takes effect on the **next** navigation, so apply before the
   first `navigate` in the attach path.

4. **JNI mechanics.** UTF-8 in, per-engine string conversion,
   exception-free (never throw across JNI). A `null` jstring maps to
   the clear path.

## S · Structure

### Inheritance Relationships
1. `WebViewComponent` (abstract) gains one field + two concrete
   methods; abstract surface unchanged.
2. `EmbeddedWebView` / `OffscreenWebView` each gain one `setUserAgent`.

### Dependencies
1. `WebViewComponent.setUserAgent` → subclass engine wrapper
   (`EmbeddedWebView`/`OffscreenWebView`).
2. Engine wrappers → `WebViewNative` natives → per-engine setter.
3. `createPeer()` / `addNotify()` → apply pending UA before navigate.

### Layered Architecture
1. Native engine layer (`src_c/webview_embed.cpp`,
   `windows/webview_embed.cc`): setters + JNI bridges.
2. JNI surface (`WebViewNative`): two decls.
3. Engine wrapper layer (`EmbeddedWebView`/`OffscreenWebView`).
4. Component API layer (`WebViewComponent`).
5. Wiring layer (`createPeer`/`addNotify`).

## O · Operations

### 1. Extend WebViewComponent
File: `src/ca/weblite/webview/swing/WebViewComponent.java`
1. Add `protected String pendingUserAgent = null;` near `pendingUrl`.
2. `public WebViewComponent setUserAgent(String ua)`: normalise
   `ua == null || ua.isEmpty()` → `null`; store; if the peer is
   attached, apply live (subclass hook — see Ops 2/3). Return `this`.
   Javadoc: `null`/empty restores the engine default; before display
   it is applied to the first request, after display it takes effect
   on the next navigation; changes the HTTP `User-Agent` header.
3. `public String getUserAgent()`: return `pendingUserAgent`.
4. To let the base `setUserAgent` apply live without knowing the
   engine type, add `protected void applyUserAgentToPeer(String ua)
   { }` (no-op default) overridden by the two subclasses.

### 2. Wire heavyweight
File: `WebViewHeavyweightComponent.java`
1. In `createPeer()`, after the engine is obtained and **before**
   `embedded.navigate(pendingUrl)` (and before the adopt-skip guard's
   navigate), `if (pendingUserAgent != null)
   embedded.setUserAgent(pendingUserAgent);`.
2. Override `applyUserAgentToPeer(String ua)`:
   `EmbeddedWebView e = embedded; if (e != null) e.setUserAgent(ua);`.

### 3. Wire lightweight
File: `WebViewLightweightComponent.java`
1. In `addNotify()`, after the engine is created and before
   `engine.navigate(pendingUrl)`, apply `pendingUserAgent` when
   non-null.
2. Override `applyUserAgentToPeer(String ua)` against `engine`.

### 4. EmbeddedWebView / OffscreenWebView setters
1. `EmbeddedWebView.setUserAgent(String ua)`: `checkAlive()`;
   `WebViewNative.webview_embed_set_user_agent(peer, ua)`; return
   `this`.
2. `OffscreenWebView.setUserAgent(String ua)`:
   `WebViewNative.webview_offscreen_set_user_agent(peer, ua)`; return
   `this`.

### 5. WebViewNative decls
File: `WebViewNative.java`
1. `native static void webview_embed_set_user_agent(long w, String ua);`
2. `native static void webview_offscreen_set_user_agent(long peer, String ua);`
   Block comment: `ua == null` clears the override (engine default);
   takes effect on the next navigation; never throws via JNI.

### 6. macOS + Linux native
File: `src_c/webview_embed.cpp`
1. macOS `cocoa_set_user_agent(Engine*, const char* ua_or_null)`:
   on main thread, `[e->webview setCustomUserAgent: ua ? ns_str(ua) :
   nil]`.
2. Linux `gtk_set_user_agent(Engine*, ...)` / `gtk_off_set_user_agent`:
   `WebKitSettings* s = webkit_web_view_get_settings(WEBKIT_WEB_VIEW(
   e->web)); webkit_settings_set_user_agent(s, ua /* NULL ok */);`.
3. JNI bridges
   `Java_..._webview_1embed_1set_1user_1agent` /
   `..._webview_1offscreen_1set_1user_1agent` in the existing
   `extern "C"` block: `GetStringUTFChars` (null-safe), dispatch per
   `#ifdef`, `ReleaseStringUTFChars`.

### 7. Windows native
File: `windows/webview_embed.cc`
1. `set_user_agent(Engine*, const char* ua_or_null)`: query
   `ICoreWebView2Settings2` from the controller's settings;
   `put_UserAgent(widen(ua ? ua : ""))`; no-op if the `_2` interface
   is unavailable.
2. JNI bridge `Java_..._webview_1embed_1set_1user_1agent`.

### 8. Test + README
1. `test/ca/weblite/webview/WebViewComponentUserAgentTest.java`
   (StubComponent, overriding `applyUserAgentToPeer` to record):
   default `getUserAgent()==null`; `setUserAgent("x")` →
   `getUserAgent()=="x"`; `setUserAgent(null)` and `setUserAgent("")`
   → `null`; chaining returns `this`; live-apply hook fires when the
   stub reports an attached peer.
2. README "Custom user agent" subsection: the API, the null-resets
   semantics, the "changes the HTTP header (unlike a JS shim)" note,
   and the next-navigation timing.

## N · Norms

- **Mirror `pendingUrl`** lifecycle: store on the component, apply at
  attach before first navigate, and live afterwards.
- **`null`/empty == engine default** at every layer; never send an
  empty UA to the engine as an override.
- **`getUserAgent()` returns `null` when unset** (not `""`).
- **JNI never throws**; null jstring is the clear path; UTF-8
  conversion released on every path.
- **Java 8 target**; get-style accessors (`setUserAgent`/
  `getUserAgent`) matching `setUrl`/`getUrl`.
- **No new dependency; no JS shim; no reserved binding.**

## S · Safeguards

- **Backward compatibility (never-relax):** unset UA → native setter
  not invoked → engine default unchanged.
- **Reset semantics:** `setUserAgent(null)` and `setUserAgent("")`
  both clear the override on every engine (macOS `nil`, GTK `NULL`,
  WebView2 empty string).
- **Timing:** applied before the first `navigate` at attach so the
  initial request carries it; live changes affect the next
  navigation only (documented; engines do not rewrite in-flight
  requests).
- **Headless-safe:** `setUserAgent`/`getUserAgent` before the peer
  exists only touch the `pendingUserAgent` field; no
  `HeadlessException`.
- **WebView2 old-runtime tolerance:** absent `ICoreWebView2Settings2`
  → silent no-op, not a crash.
- **Native coverage status (mirrors Canvas 15/18):** the Java
  contract (Ops 1–5, 8) plus the macOS + Linux native setters (Op 6)
  and the Windows setter (Op 7) are this canvas's deliverable. The
  native code is pattern-faithful to the existing engine setters but
  the generating sandbox has **no native toolchain**, so all three
  per-engine setters MUST be built and exercised on-device (confirm
  the `User-Agent` header via an echo endpoint) before release.

## REASONS-Implements
- `src/ca/weblite/webview/swing/WebViewComponent.java`
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
- `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`
- `src/ca/weblite/webview/EmbeddedWebView.java`
- `src/ca/weblite/webview/OffscreenWebView.java`
- `src/ca/weblite/webview/WebViewNative.java`
- `src_c/webview_embed.cpp` (macOS + Linux)
- `windows/webview_embed.cc` (Windows)
- `test/ca/weblite/webview/WebViewComponentUserAgentTest.java`
- `README.md`
