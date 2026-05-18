# Story Decomposition: Future-Returning JavaScript Eval API

## INVEST Analysis

### Abstract Task: "Round-trip JavaScript values from the embedded page back to Java without per-call pre-staging"

**Analysis Dimensions**:
- **Core Responsibility**: Give Java callers (both the standalone in-process `WebView` and the embedded `WebViewComponent`) a single call that runs a JavaScript snippet in the current document and resolves a `CompletableFuture<String>` with the JSON-serialized result. Today every "what's the current X?" query — selection text, scroll position, viewport hit-test, search-in-page state — has to be staged as a `addJavascriptCallback` binding plus a pre-loaded JS shim that the host calls into; the new API removes the per-query staging step and replaces it with a one-liner from Java.
- **Primary Operations**: Submit a JS snippet for evaluation; receive a future that resolves with the snippet's JSON-serialized return value (or `null` for `undefined`); have the future complete exceptionally when the JS throws synchronously, when a returned `Promise` rejects, or when the underlying native peer is not yet displayable.
- **Key Constraints**:
  - Native `webview_eval` / `webview_embed_eval` / `webview_offscreen_eval` are all `void`-returning — the round-trip has to be implemented in Java + an injected JS shim, not by adding a new JNI signature.
  - Must work in both `WebView` (canvas-2, in-process, owns its event loop) and `WebViewComponent` (canvas-5 abstract, canvas-6 heavyweight, canvas-7 lightweight) without changing the native API surface.
  - The reserved `__webview_` / `__swv_` binding-name convention established by STORY-001-002 (console capture) and STORY-002-001 (mouse event channel) applies — the resolver binding must use a namespaced name that cannot collide with user bindings.
  - Promises in the JS expression must be awaited: a snippet that returns `fetch(url).then(r => r.text())` should resolve the Java future with the response body, not with `"[object Promise]"`.
  - JS snippets may be either expressions or statement blocks. The wrapper must accept both (statement blocks return whatever the snippet's final `return` yields when wrapped as an IIFE).
  - Lifecycle: same rule as today's `eval` — the future completes exceptionally with `IllegalStateException` when the peer is not yet alive (pre-`show()` for `WebView`, pre-display for `WebViewComponent`).
- **Technical Complexity**: Medium — a single auto-registered resolver binding, a JS wrapper template (`Promise.resolve((function(){ ... })()).then(ok).catch(err)`), an integer request-id sequencer, and a `ConcurrentMap<Long, CompletableFuture<String>>` of in-flight requests. The JS wrapper is identical across all four surfaces; only the eval delivery path differs.
- **Business Complexity**: Low — well-bounded developer-facing API; no business rules beyond the JS contract.

### INVEST Evaluation

- ✅ **Independent**: Builds on canvases 2, 5, 6, 7 and reuses the existing `addJavascriptCallback` / native binding bridge. No dependency on STORY-001-* or STORY-002-*, though it follows the same reserved-binding convention.
- ✅ **Negotiable**: API shape agreed with user — `CompletableFuture<String> evalAsync(String js)` on both surfaces. Other shapes considered (`evalSync(js, timeoutMs)`, both, or "you decide") were rejected in favour of future-returning as the primitive.
- ✅ **Valuable**: Eliminates the binding+shim ritual for every "ask the page a question" use case. Unlocks scroll-position capture/restore, search-in-page, "is element in viewport", and the host-side half of the user's selection / contextmenu workflows.
- ✅ **Estimable**: One JS wrapper template, one auto-registered binding, a small in-flight map, and a result-routing method on each of the four classes (`WebView`, `WebViewHeavyweightComponent`, `WebViewLightweightComponent`, plus the abstract method on `WebViewComponent`). Each is a known pattern in this codebase.
- ✅ **Small**: 2-4 days of work for the full surface (both standalone and embedded, both subclasses).
- ✅ **Testable**: Futures completing with specific values, futures completing exceptionally on JS throw / Promise rejection / pre-display, and binding-collision absence are all observable from a small harness.

**Conclusion**: Ready as-is — no split needed. The standalone `WebView` and the embedded `WebViewComponent` surfaces are the same feature on two API entry points; splitting would duplicate the JS-contract documentation and the in-flight-map design across two stories. At most three functional points (in-process `evalAsync`, embedded `evalAsync`, future-completion semantics on JS throw / Promise rejection / lifecycle violation) — within INVEST limits.

### Split Strategy

N/A — kept as a single story.

---

## [STORY-003-001] Future-Returning `evalAsync(String js)` for `WebView` and `WebViewComponent`

### Background

The library's existing `eval(String js)` on both `ca.weblite.webview.WebView` (canvas-2) and `ca.weblite.webview.swing.WebViewComponent` (canvas-5) is fire-and-forget: the snippet runs in the current document but the Java caller cannot observe whatever the JS evaluated to. The only way to round-trip a value back today is for the caller to pre-register a Java callback via `addJavascriptCallback("myCallback", arg -> …)` and then `eval` a JS snippet that computes the value and calls `window.myCallback(JSON.stringify(value))`. That ritual is acceptable when the host needs the value at a single, predictable point (e.g. a `contextmenu` handler that pre-stages "current selection" before the click), but it is hostile to ad-hoc queries — every new "what's the page's current X?" question costs a binding registration, a shim, and a request/response correlation if multiple queries can be in flight at once.

This story adds a single new method, `CompletableFuture<String> evalAsync(String js)`, on both the standalone `WebView` and the embedded `WebViewComponent`. The future completes with the JSON-serialized result of evaluating `js` (with `undefined` mapped to a future that completes with `null`). If `js` returns a `Promise`, the future does not resolve until that promise settles — the resolved value is JSON-stringified; a rejection completes the Java future exceptionally with the rejection reason. Synchronous JS errors complete the future exceptionally. Lifecycle violations (calling before the peer exists) complete the future exceptionally with `IllegalStateException`.

Implementation reuses the existing `addJavascriptCallback` bridge: a single reserved-name resolver binding (e.g. `__swv_evalResult__`) is auto-registered on first use; each call assigns a monotonically increasing request id, wraps the user's JS in a template that posts `{id, ok, value}` or `{id, ok:false, error}` back through the binding, and routes the result to the in-flight `CompletableFuture` keyed by id. No new native API is needed.

Key points:
- Business value: replaces an N-shim, N-binding pattern with one method call per query. Every "ask the page a question" use case (scroll position, selection state, viewport hit-test, search highlight count, current URL hash, form values, computed style at a point) becomes a one-liner.
- Relationship with other features: independent from STORY-001-* (DevTools / console) and STORY-002-* (mouse events). Uses the same reserved-`__swv_…` binding-name convention. Coexists with the existing `eval(String js)` — the old method stays untouched for callers who don't want a future.
- Why now: the user reports that the current binding-+-shim dance is the single biggest friction point when wiring real Swing applications to the embedded WebView. Without `evalAsync`, three of the four new feature requests they raised would need per-call JS pre-staging; with it, they collapse to direct Java calls.

### Business Value

- Provide **future-returning JavaScript evaluation** for application developers using either the standalone `WebView` or the embedded `WebViewComponent`, removing the need to pre-stage a Java callback and a JS shim for each round-trip query.
- Support **asynchronous JS workflows** (snippets that return a `Promise`, `await fetch(...)`, etc.) by awaiting the promise on the JS side before resolving the Java future.
- Enable **ad-hoc page introspection** from the host application: scroll-position capture and restore, "is this element in viewport", search-in-page hit counts, current-selection queries from outside a `contextmenu` handler, and any other "what's the page's current X?" query — each as a one-liner.
- Enable **deterministic error surfacing** for failed JS evaluation: today a `ReferenceError` from `eval("foo.bar()")` is silently swallowed by the native engine; with `evalAsync` the Java future completes exceptionally and the host can route the error to its logging / UI exactly the way it would route any other Java exception.

### Dependencies and Assumptions

- **Prerequisites**: Canvases 2, 5, 6, 7 already in place. The `addJavascriptCallback` / native-binding bridge works in all four backends (in-process `WebView`, heavyweight WKWebView / WebKitGTK / WebView2, lightweight WebKitGTK offscreen).
- **Data assumptions**: No persisted state. The in-flight `id → CompletableFuture` map is per-`WebView` / per-`WebViewComponent` instance and is cleared when the peer is destroyed.
- **Integration points**: Reuses the existing `WebViewNative.webview_bind` / `webview_embed_bind` / `webview_offscreen_bind` plumbing and the existing `webview_eval` / `webview_embed_eval` / `webview_offscreen_eval` delivery; no new JNI methods.
- **Business constraints**:
  - Native eval is fire-and-forget; this story implements round-tripping at the Java + injected-JS layer, not at the native layer.
  - JSON.stringify cannot serialize functions, circular references, or DOM nodes faithfully. The story documents this as a known JS-side limitation: callers asking for an unserializable value get a future that completes exceptionally with the underlying `TypeError` from `JSON.stringify`.
  - The reserved binding name `__swv_evalResult__` is owned by this story; callers who register a binding of the same name produce undefined behaviour. The story documents this as part of the API contract.

### Scope In

- **In-process `WebView` (canvas-2)**:
  - New public method `CompletableFuture<String> evalAsync(String js)`.
  - First call to `evalAsync` after `show()` lazily registers the reserved resolver binding `__swv_evalResult__` via the existing `webview_bind` path; subsequent calls reuse it.
  - Per call: assign a monotonically increasing `long id`, allocate a `CompletableFuture<String>`, store it in a `ConcurrentHashMap<Long, CompletableFuture<String>>`, then call `webview_eval` with the wrapped snippet (see "JS contract" below). When the resolver binding fires, look up the future by id, remove it from the map, and complete it.
  - If `evalAsync` is called when `peer == 0`, return an already-failed future carrying `IllegalStateException("WebView not shown")` (parallel to today's "calling `eval` before `show()` is unsafe" documented in canvas-2). Do not call into native.
- **Embedded `WebViewComponent` (canvas-5)**:
  - New `public abstract CompletableFuture<String> evalAsync(String js)` on `WebViewComponent`, with concrete implementations in both `WebViewHeavyweightComponent` (canvas-6) and `WebViewLightweightComponent` (canvas-7).
  - Each subclass delegates to its backing engine (`EmbeddedWebView` / `OffscreenWebView`), which holds the in-flight map and the wrapper-template logic.
  - First call after the native peer is created lazily registers the same reserved resolver binding via the engine's bind path; pre-display calls return an already-failed future carrying `IllegalStateException("WebViewComponent not displayed")`.
  - Future completion is invoked on the Swing EDT in the embedded case, matching the existing `ConsoleListener` and `WebViewMouseListener` contracts (callers can touch Swing state directly from `.thenAccept(...)`).
- **JS contract** (identical across all four surfaces, documented in the canvases):
  - User snippet `<js>` is wrapped as `(function(){ try { var __r = (function(){ <js> })(); Promise.resolve(__r).then(function(v){ window.__swv_evalResult__(JSON.stringify({id: <id>, ok: true, value: v === undefined ? null : v})); }).catch(function(e){ window.__swv_evalResult__(JSON.stringify({id: <id>, ok: false, error: (e && e.message) || String(e)})); }); } catch (e) { window.__swv_evalResult__(JSON.stringify({id: <id>, ok: false, error: (e && e.message) || String(e)})); } })();`
  - Snippets that are bare expressions (e.g. `document.title`) are accepted: the wrapper's IIFE form `(function(){ <js> })()` returns `undefined` for a bare expression, so callers who want a value back must use a `return` statement (e.g. `return document.title;`). The canvases must document this.
  - The resolved value is JSON.stringified before crossing the binding; the Java future's `String` payload is the stringified JSON. `undefined` maps to JSON `null`. Unserializable values (functions, DOM nodes, circular references) cause `JSON.stringify` to throw, which is caught by the inner `.catch` and surfaces as an exceptional Java future.
  - Promise rejections are caught by the inner `.catch` and surfaced as exceptional Java futures with the rejection's `message` (or its stringification when no `message` is present).
- **Concurrency**:
  - `evalAsync` is safe to call from any Java thread.
  - The native eval delivery may need to be marshalled to the WebView's UI thread; the embedded engines already do this internally for `eval`, so `evalAsync` follows the same path.
  - Multiple in-flight `evalAsync` calls do not interfere; each carries its own id.

### Scope Out

- A blocking `String evalSync(String js, long timeoutMs)` convenience method. Callers who need synchronous semantics can call `.get(timeoutMs, MILLISECONDS)` on the returned future themselves; the canvases will mention this in the API documentation but the convenience wrapper is not part of this story.
- Returning typed values (`int`, `boolean`, `JsonNode`, etc.). The future's payload is a JSON-serialized `String`; callers parse it with the JSON library of their choice. Type-safe wrappers can be added in a follow-up story if there is demand.
- A cancellation path that aborts the JS evaluation. The returned `CompletableFuture` supports `cancel(true)` in the Java sense (the future will reject `get` calls), but the in-page JS continues to execute and its eventual binding callback is silently dropped (the in-flight map no longer holds the id).
- The other three feature requests raised alongside this one — richer `DomTarget` selection info, `elementAtPoint(x, y)` API, and selection-changed listener. Each is a separate story.
- Replacing or deprecating the existing fire-and-forget `eval(String js)`. It stays as-is for callers who don't care about the return value (e.g. "scroll to top", "highlight this element") and don't want a future allocation per call.
- Adding `evalAsync` to `EmbeddedWebView` / `OffscreenWebView` as a public surface. Those classes are implementation details of the heavyweight / lightweight components; the public API lives on `WebView` and `WebViewComponent`.

### Acceptance Criteria

#### AC1: Future resolves with JSON-stringified primitive result
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("return 1 + 2;")`,
**Then** the returned `CompletableFuture<String>` completes (within the engine's normal eval latency) with the string `"3"`.

#### AC2: Future resolves with JSON-stringified object result
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("return {a: 1, b: 'two'};")`,
**Then** the returned future completes with the string `{"a":1,"b":"two"}` (the exact whitespace and key order produced by `JSON.stringify`).

#### AC3: Future resolves with `"null"` for `undefined` results
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("return;")` (or any snippet whose IIFE returns `undefined`),
**Then** the future completes with the string `"null"` (the JSON serialization of JavaScript `null`).

#### AC4: Future awaits a returned Promise
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("return new Promise(function(r){ setTimeout(function(){ r('done'); }, 50); });")`,
**Then** the future does not complete until ~50 ms have elapsed, and when it completes its value is the string `"\"done\""`.

#### AC5: Synchronous JS throw completes the future exceptionally
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("foo.bar();")` where `foo` is not defined,
**Then** the future completes exceptionally, the wrapping cause's message contains `foo is not defined` (the engine's `ReferenceError` text), and `future.isCompletedExceptionally()` returns `true`.

#### AC6: Promise rejection completes the future exceptionally
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("return Promise.reject(new Error('boom'));")`,
**Then** the future completes exceptionally and the cause's message contains `boom`.

#### AC7: Calling before display completes the future exceptionally with `IllegalStateException`
**Given** a `WebView` that has not yet been `show()`n (or a `WebViewComponent` that has not yet been added to a visible window),
**When** the application calls `evalAsync("return 1;")`,
**Then** the returned future is already completed exceptionally, the cause is an `IllegalStateException`, and no native call has been issued.

#### AC8: Multiple concurrent `evalAsync` calls each receive their own result
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("return new Promise(function(r){ setTimeout(function(){ r(1); }, 100); });")` and immediately after calls `evalAsync("return 2;")`,
**Then** the second future completes (with `"2"`) before the first, the first future completes ~100 ms later (with `"1"`), and neither future ever receives the other's value.

#### AC9: Reserved resolver binding does not collide with user bindings
**Given** a shown `WebView` (or a displayed `WebViewComponent`) on which the host has called `addJavascriptCallback("foo", ...)` and then `evalAsync("return window.foo ? 'yes' : 'no';")`,
**When** the future completes,
**Then** its value is `"\"yes\""`, no `IllegalStateException` or binding-collision error has been raised, and the host's `foo` binding continues to work for subsequent `addJavascriptCallback`-style invocations.

#### AC10: Embedded futures complete on the EDT
**Given** a displayed `WebViewComponent` (heavyweight or lightweight) and an `evalAsync` call whose continuation records `SwingUtilities.isEventDispatchThread()`,
**When** the future completes and `.thenAccept(continuation)` is fired,
**Then** the recorded value is `true` regardless of which native thread originated the binding callback.

#### AC11: Standalone `WebView` futures complete on the WebView UI thread
**Given** a shown `WebView` and an `evalAsync` call whose continuation records the current thread,
**When** the future completes,
**Then** the continuation runs on the WebView's native UI thread (the same thread that dispatches `dispatch(Runnable)` callbacks); callers who need to hop to another thread arrange that themselves via `.thenApplyAsync(..., executor)`.

#### AC12: Existing fire-and-forget `eval` continues to work unchanged
**Given** a shown `WebView` (or a displayed `WebViewComponent`) on which the host has called `eval("document.title = 'changed';")`,
**When** the snippet executes,
**Then** the document title changes (i.e. the existing `eval` path is unaffected by the addition of `evalAsync`), and no future is created or leaked by the call.

#### AC13: Snippet whose final expression is unserializable completes exceptionally
**Given** a shown `WebView` (or a displayed `WebViewComponent`),
**When** the application calls `evalAsync("return document.body;")` (a DOM node, which `JSON.stringify` cannot faithfully serialize and which throws a `TypeError` if it contains a circular reference),
**Then** the future completes exceptionally with a cause whose message reflects the underlying JS-side serialization failure — the host's app does not crash and no native callback leaks.

### Non-Functional Expectations

- The added wrapper template must not measurably regress eval latency for trivial snippets (a `return 1;` round-trip on a quiet page should complete in the same order of magnitude as a `eval("1+1")` call — single-digit milliseconds on a typical desktop).
- The in-flight `id → CompletableFuture` map must not grow unbounded. After every binding callback the corresponding entry is removed; cancelled futures whose JS eventually still posts a result drop the result silently (no entry exists to route to).
- The reserved binding is registered exactly once per peer lifetime — repeated `evalAsync` calls must not re-bind on every call.
- Disposing the embedded `WebViewComponent` (its `dispose()` path per canvas-6 / canvas-7) must complete every still-pending `evalAsync` future exceptionally with `IllegalStateException("WebViewComponent disposed")` so callers waiting on `.get()` do not hang.

---

## Quality Checks

**STORY-003-001 (Future-Returning `evalAsync`)**:
- ✅ All required sections present (Background, Business Value, Dependencies and Assumptions, Scope In/Out, ACs, Non-Functional Expectations).
- ✅ Each AC uses Given-When-Then with concrete JS snippets and observable Java-side outcomes (string equality, exceptional completion, EDT-thread checks).
- ✅ Business-language ACs (no JNI signatures inside AC bodies — those live in Scope In and the canvases; JS snippets ARE part of the API contract and are therefore appropriate to include verbatim).
- ✅ Covers happy path (primitive / object / undefined / promise), error paths (sync throw, promise rejection, unserializable result, pre-display call), concurrency (interleaved calls), binding-collision safety, EDT marshaling, threading symmetry between standalone and embedded surfaces, and existing-`eval`-untouched regression check.
- ✅ At most 3 functional points (in-process `evalAsync`, embedded `evalAsync`, future-completion semantics including error / lifecycle paths).
- ✅ 2-4 days of work.

## Final INVEST Re-validation

| Property | STORY-003-001 |
|---|---|
| Independent | ✅ (no dependency on STORY-001-* or STORY-002-*) |
| Complete | ✅ (full surface across both API entry points) |
| Valuable | ✅ (collapses N-shim/N-binding patterns into one call) |
| Estimable | ✅ (known JS-shim + binding + in-flight-map pattern) |
| Right-sized | ✅ (~2-4 days) |
| Testable | ✅ (string-equality and exceptional-completion assertions) |

Story passes all six INVEST criteria. Total estimated effort: 2-4 days.
