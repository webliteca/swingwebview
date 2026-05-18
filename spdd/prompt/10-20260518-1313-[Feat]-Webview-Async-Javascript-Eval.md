---
generated_at: 2026-05-18T13:13:00-07:00
---

# REASONS Canvas: Future-Returning JavaScript Eval — Dispatcher and JS Contract

## R · Requirements
- Provide a per-engine fan-out hub (`EvalDispatcher`) that lets every
  WebView surface — standalone in-process `WebView`
  ([[in-process-webview-java-api]]), heavyweight embedded
  `WebViewHeavyweightComponent`
  ([[swing-heavyweight-webview-embedding]]), and lightweight
  `WebViewLightweightComponent`
  ([[swing-lightweight-webview-embedding]]) — round-trip the result of
  a JavaScript snippet back to Java as a
  `CompletableFuture<String>`. The dispatcher owns the in-flight
  `requestId → CompletableFuture<String>` map, the canonical JS
  wrapper template (`SHIM_JS`), the resolver-binding callback
  parsing, and the dispose-time drain that completes pending futures
  exceptionally.
- The dispatcher MUST be usable from BOTH Swing-embedded surfaces (where
  futures complete on the Swing Event Dispatch Thread, matching
  the `ConsoleListener` and `WebViewMouseListener` contracts) AND
  the standalone `WebView` surface (where there is no Swing — the
  future completes inline on the native binding-callback thread).
  This asymmetry is expressed as a single constructor-time
  `marshalToEdt` boolean on the dispatcher.
- The reserved JS binding name `__webview_eval_result__` MUST be
  protected from caller collision by the existing
  `RESERVED_BINDING_PREFIX = "__webview_"` check on
  `WebViewComponent.addJavascriptCallback`
  (`WebViewComponent.java:53`, enforced at
  `WebViewHeavyweightComponent.java:153-158` and
  `WebViewLightweightComponent.java:416-421`). No new guard is
  required — the existing prefix check is the AC9 mechanism.
- A dedicated exception type, `JavaScriptEvalException`, MUST wrap the
  JS-side message for every JS-originated failure: synchronous
  `throw`, rejected Promise, and `JSON.stringify` `TypeError` on the
  return value. Lifecycle violations (dispose / not-yet-shown)
  surface as `IllegalStateException`, not as
  `JavaScriptEvalException`, so callers can distinguish "the page
  said no" from "we have no page".
- The implementation MUST NOT introduce a JSON-parsing dependency (the
  project has none — `pom.xml:43-50` declares only the JUnit test
  dependency). Wire format follows the precedent set by
  `ConsoleDispatcher` (`ConsoleDispatcher.java:64-145`) and
  `WebViewMouseDispatcher` (`WebViewMouseDispatcher.java:77-159`):
  the JS shim base64-encodes a pipe-separated record before posting
  it through the binding, and the Java side uses `indexOf`-based
  slicing on the known-shape native bind envelope.
- The implementation MUST NOT require new JNI entry points. The three
  existing eval entry points (`webview_eval`, `webview_embed_eval`,
  `webview_offscreen_eval`) and the three existing bind entry points
  (`webview_bind`, `webview_embed_bind`, `webview_offscreen_bind`)
  are sufficient. The dispatcher delegates the actual native eval to
  its owner via an injected `EvalSink` (parallel to
  `WebViewMouseDispatcher.FlagSink` at
  `WebViewMouseDispatcher.java:163-180`) so it has no compile-time
  dependency on any of the three engine wrapper classes.
- The JS shim MUST be idempotent across page navigations: each new
  document load installs the shim cleanly and in-flight futures
  whose JS was running in the prior document are NOT resolved by
  the new document (re-entry guarded by
  `window.__webview_eval_installed__`, mirroring
  `ConsoleDispatcher.SHIM_JS` at
  `ConsoleDispatcher.java:67-68`).
- The JS shim MUST be robust against user JS that mutates
  `window.__webview_eval_result__`. The wrapper caches the binding
  reference at the *start* of the wrapped snippet (before invoking
  the user code), so a snippet like `window.__webview_eval_result__
  = null; return 1;` still routes its `1` back through the cached
  reference rather than through whatever the user just overwrote.
- The JS contract (documented on the dispatcher's `SHIM_JS` Javadoc
  and re-stated on each consuming surface):
  - The user snippet runs inside an IIFE: `(function(){ <js> })()`.
    To yield a value, the snippet MUST use `return` (a bare
    expression on its own line is NOT the IIFE's return value).
  - The IIFE's return value (whether sync or a `Promise`) is wrapped
    in `Promise.resolve(...)` before being JSON-stringified, so a
    returned Promise resolves to the future only after it settles.
  - `JSON.stringify(undefined)` returns `undefined` (the JS string,
    not the JSON string); the wrapper coerces `undefined → null`
    before stringifying, so the Java future receives `"null"` for
    `undefined` results.
  - Unserializable return values (DOM nodes, functions, circular
    references): the wrapper either resolves with a degenerate
    string (typically `"{}"` for a DOM node — engine-dependent) or
    completes exceptionally with a `JavaScriptEvalException`
    carrying the underlying `TypeError` message. Either is
    acceptable; the host app does not crash and no native callback
    leaks.
- Definition of Done: an `EvalDispatcherTest` JUnit test under `test/`
  exercises the in-flight map's id allocation, dispose draining,
  malformed-payload silent drop, and `marshalToEdt` toggle (with a
  mock `EvalSink` so no native WebView is required). Integration
  with the live engines is covered by the consuming canvases
  ([[in-process-webview-java-api]],
  [[swing-heavyweight-webview-embedding]],
  [[swing-lightweight-webview-embedding]]) and by the
  `WebViewAsyncEvalDemo` Swing app under `demos/` that those
  canvases ship. README under "Demos" lists the new demo alongside
  the existing context-menu / console demos.
- Out of scope (explicit non-goals, owned elsewhere or deferred):
  - The public `evalAsync(String)` method on `WebView`. Owned by
    [[in-process-webview-java-api]].
  - The abstract `evalAsync(String)` on `WebViewComponent`. Owned
    by [[swing-webview-component-mode-selection]].
  - The concrete `evalAsync(String)` on
    `WebViewHeavyweightComponent` and `EmbeddedWebView`. Owned by
    [[swing-heavyweight-webview-embedding]].
  - The concrete `evalAsync(String)` on
    `WebViewLightweightComponent` and `OffscreenWebView`. Owned by
    [[swing-lightweight-webview-embedding]].
  - The per-engine registration of `EvalDispatcher.SHIM_JS` and the
    `__webview_eval_result__` binding inside `createPeer` /
    `addNotify` / `WebView.show()`. Owned by the three consuming
    canvases above.
  - A blocking `String evalSync(String, long timeoutMs)` convenience
    method. Callers needing synchronous semantics call `.get(...)`
    on the returned future themselves.
  - Returning typed values (`int`, `boolean`, `JsonNode`). The
    future yields the JSON-stringified `String`; callers parse it
    with the JSON library of their choice.
  - Aborting in-page JS when a `CompletableFuture` is cancelled.
    `cancel(true)` marks the Java future cancelled; the in-page
    snippet continues to execute, and its eventual binding
    callback finds either no entry (drained) or a cancelled future
    and silently drops.
  - Navigation-driven drain of in-flight futures. A page navigation
    while a future is in-flight leaves the future pending forever;
    callers who care must apply their own deadline. A future
    canvas may add a navigation listener that drains; not this one.

## E · Entities

- **EvalDispatcher** (new public class,
  `src/ca/weblite/webview/EvalDispatcher.java`). Mirrors
  `ConsoleDispatcher` (`ConsoleDispatcher.java:34-145`) and
  `WebViewMouseDispatcher` (`WebViewMouseDispatcher.java:40-280`)
  line-for-line in shape, with eval-specific state instead of
  listener-list state. Internal-only — the class is `public` for
  cross-package access from `ca.weblite.webview.swing`, NOT part
  of the supported API (Javadoc says so prominently, matching the
  existing wording on `ConsoleDispatcher.java:15-23`).
  Invariants:
  - `CHANNEL_NAME: public static final String` — `"__webview_eval_result__"`.
    Used by every consuming surface when binding the reserved
    resolver callback.
  - `SHIM_JS: public static final String` — the canonical JS wrapper
    template scaffold installed via `addOnBeforeLoad` at peer-attach
    time. Idempotency-guarded with
    `window.__webview_eval_installed__`. See Operation 4 for the
    full text.
  - `pending: ConcurrentHashMap<Long, CompletableFuture<String>>` —
    in-flight map keyed by request id. Allocated at construction;
    never replaced; cleared on `disposeAllPending`.
  - `nextId: AtomicLong` — monotonic request-id sequencer. Initial
    value `0`; first id handed out by `incrementAndGet()` is `1`,
    so `0` is reserved (never used as a key in `pending`).
  - `sink: final EvalSink` — non-null; the engine-specific delivery
    channel injected by the constructor. The dispatcher calls
    `sink.eval(wrappedJs)` to push wrapped snippets into the live
    engine; it never touches `WebViewNative` directly.
  - `marshalToEdt: final boolean` — `true` for embedded surfaces
    (futures complete via `SwingUtilities.invokeLater`); `false` for
    the standalone `WebView` surface (futures complete inline on the
    binding-callback thread). Set at construction; never mutated.
  - `disposeLabel: final String` — non-null; the human-readable
    wrapper-type name folded into the `IllegalStateException`
    message at dispose time (e.g. `"WebView"`, `"EmbeddedWebView"`,
    `"OffscreenWebView"`). Set at construction; never mutated.
  - `disposed: volatile boolean` — `false` initially; flipped to
    `true` by `disposeAllPending()`. Subsequent calls to
    `evalAsync` after the flag flips return an already-failed
    future without touching the engine.

- **EvalDispatcher.EvalSink** (new public nested interface,
  `src/ca/weblite/webview/EvalDispatcher.java`). Functional
  interface; identical role to `WebViewMouseDispatcher.FlagSink`
  (`WebViewMouseDispatcher.java:163-180`). Invariants:
  - Single method `void eval(String js)` — invoked by the
    dispatcher with the fully-wrapped JS snippet. The
    implementation MUST issue the native eval call (and is
    responsible for engine-alive checks, thread marshaling, and
    `IllegalStateException` swallowing as required by the
    consuming surface — the dispatcher trusts the sink).
  - Implementations are typically two-line lambdas in the
    consuming engine wrapper (e.g.
    `js -> { if (peer != 0L) WebViewNative.webview_embed_eval(peer, js); }`).

- **JavaScriptEvalException** (new public class,
  `src/ca/weblite/webview/JavaScriptEvalException.java`). Concrete
  subclass of `RuntimeException`. Invariants:
  - `serialVersionUID: private static final long` — `1L`. Required
    because the class extends `RuntimeException` (which
    implements `Serializable`).
  - Single public constructor `JavaScriptEvalException(String message)`
    that delegates to `super(message)`.
  - No additional state; the JS-side cause is captured verbatim in
    the message. Wrapped via `CompletableFuture.completeExceptionally`,
    so callers retrieve it via `.exceptionally(t -> t.getCause())`
    or `.handle((s, t) -> ...)` — `t` is a
    `CompletionException`, `t.getCause()` is the
    `JavaScriptEvalException`.

## A · Approach
- **Mirror the existing dispatcher pattern.** `ConsoleDispatcher` and
  `WebViewMouseDispatcher` already define the canonical shape: per-engine
  instance, registered eagerly at peer creation, public-but-internal
  visibility, base64-encoded payload posted through a reserved
  `__webview_*` binding, `indexOf`-based parsing on the native bind
  envelope, fan-out hop onto the EDT via
  `SwingUtilities.invokeLater`. `EvalDispatcher` follows the same
  shape; the differences are (a) the per-call state lives in a map
  keyed by request id rather than a flat listener list, and (b) the
  EDT hop is conditional on the `marshalToEdt` flag.
- **No JNI changes.** The native eval entry points are fire-and-forget
  (`webview_eval` Javadoc, `WebViewNative.java:91-94`: "Evaluation
  happens asynchronously, also the result of the expression is
  ignored. Use RPC bindings if you want to receive notifications
  about the results of the evaluation."). The round-trip is built
  entirely in Java + an injected JS shim that posts results through
  the existing bind machinery, exactly as console capture and DOM
  mouse events do.
- **`EvalSink` decouples dispatcher from engine.** The dispatcher
  doesn't know whether it's wrapping `webview_eval`,
  `webview_embed_eval`, or `webview_offscreen_eval`. The consuming
  engine wrapper supplies a `EvalSink` lambda at dispatcher
  construction time; the dispatcher calls it with the wrapped
  snippet and trusts it to do the right thing. Same pattern as
  `WebViewMouseDispatcher.FlagSink`
  (`WebViewMouseDispatcher.java:163-180`).
- **Per-request state ownership.**
  1. `evalAsync(String)` validates non-null, checks `disposed`
     (returns an already-failed future on dispose), allocates `id =
     nextId.incrementAndGet()` and `f = new CompletableFuture<>()`,
     inserts into `pending` BEFORE invoking the sink (defends
     against the theoretical case of synchronous-eval engines —
     see Safeguards), builds the wrapped snippet by string-formatting
     the id into the wrapper template, calls `sink.eval(wrapped)`,
     and returns `f`.
  2. When the JS shim posts back, the resolver callback
     (registered by the consuming surface at peer-create time,
     delegating into `dispatcher.dispatch(rawJson)`) extracts the
     base64 payload from the native bind envelope, decodes UTF-8,
     splits the pipe-separated record into `<id>|<ok>|<rest>`,
     parses the id, removes the entry from `pending` (this is the
     leak-prevention step — every successful resolve drops the
     entry), and either:
     - completes the future with the JSON-stringified value
       (`<rest>` for the `ok` path), OR
     - completes the future exceptionally with
       `JavaScriptEvalException(<rest>)` for the `ok=0` path.
  3. EDT marshaling: if `marshalToEdt` is `true`, the completion
     runs inside `SwingUtilities.invokeLater(...)` so any caller
     `.thenAccept(...)` continuation lands on the EDT
     (matching the existing `ConsoleDispatcher.deliverOnEdt` at
     `ConsoleDispatcher.java:217-225`). If `false`, completion is
     inline on the binding-callback thread.
- **Wire-format choice: base64 + pipe-separated.** Same precedent
  as both existing dispatchers. The JS side calls
  `btoa(unescape(encodeURIComponent(payload)))` to handle UTF-8;
  the Java side uses
  `java.util.Base64.getDecoder().decode(...)` and a `String(bytes,
  "UTF-8")` materialization (same lines as
  `ConsoleDispatcher.decodeBase64Utf8` at
  `ConsoleDispatcher.java:265-274`). The pipe-separated record for
  this dispatcher is `<id>|<okFlag>|<payload>`, where `<okFlag>`
  is `1` (resolved value) or `0` (error message), and `<payload>`
  is either the `JSON.stringify(value)` string or the JS-side
  error message. Pipes inside `<payload>` are fine — the parser
  splits at most twice (`indexOf('|')` twice) and takes the rest
  verbatim.
- **Dispose ordering** (governs `disposeAllPending`):
  1. Flip `disposed = true` so new `evalAsync` calls bail out.
  2. Snapshot `pending.values()` and complete each future
     exceptionally with
     `IllegalStateException(disposeLabel + " disposed")`.
  3. Clear `pending`.
  4. The engine wrapper continues with its existing teardown
     (heap clear, `webview_*_destroy`). The dispatcher does not
     attempt to unbind the resolver — the native destroy invalidates
     the binding wholesale.
- **JS wrapper template** (full text in Operations §4): wraps the
  user's snippet in an IIFE inside another IIFE, captures a stable
  reference to `window.__webview_eval_result__` BEFORE invoking the
  user code (E8 robustness), `Promise.resolve(...)`s the IIFE
  result, `JSON.stringify`s the resolved value (with
  `undefined → null` coercion), base64-encodes the
  `<id>|1|<json>` payload, and posts it back. Sync throws inside
  the outer IIFE land in an outer `try/catch` that posts
  `<id>|0|<message>`; Promise rejections land in the `.catch`
  branch with the same shape. The template is parameterized only by
  the request id, which is appended via `String.format` /
  `String.replace`.

## S · Structure

### Inheritance Relationships
1. `EvalDispatcher` is a concrete `public final` class. It implements
   no interfaces and extends `Object`. Symmetric with
   `ConsoleDispatcher` (`ConsoleDispatcher.java:34`) and
   `WebViewMouseDispatcher` (`WebViewMouseDispatcher.java:40`).
2. `EvalDispatcher.EvalSink` is a `public` static nested
   `@FunctionalInterface`. Implementations are anonymous lambdas in
   the consuming engine wrappers — no named subclass exists in this
   canvas.
3. `JavaScriptEvalException` extends `java.lang.RuntimeException`. No
   subclasses are defined or expected.

### Dependencies
1. `EvalDispatcher` depends on:
   - `java.util.concurrent.CompletableFuture` (Java 8) — pending
     futures.
   - `java.util.concurrent.ConcurrentHashMap` — in-flight map.
   - `java.util.concurrent.atomic.AtomicLong` — id sequencer.
   - `java.util.Base64` (Java 8) — payload decode. Same dependency
     as `ConsoleDispatcher` at `ConsoleDispatcher.java:11`.
   - `javax.swing.SwingUtilities` — EDT hop. Reached ONLY when
     `marshalToEdt` is `true`; the standalone `WebView` constructs
     its dispatcher with `marshalToEdt = false` so the standalone
     surface does not require Swing on the classpath at runtime.
     The import of `SwingUtilities` is unavoidable (Java 8 has no
     `Optional<Class>` import idiom) but `SwingUtilities`
     class-loads lazily — the import alone does not pull Swing in.
   - `ca.weblite.webview.JavaScriptEvalException` — error path.
   - `ca.weblite.webview.EvalDispatcher.EvalSink` — its own nested
     type.
2. `EvalDispatcher` does NOT depend on `WebViewNative`,
   `EmbeddedWebView`, `OffscreenWebView`, `WebView`,
   `WebViewComponent`, or any Swing component class. The
   `EvalSink` indirection is the entire decoupling mechanism.
3. `JavaScriptEvalException` depends only on
   `java.lang.RuntimeException`.

### Layered Architecture
1. **JS layer (page-side):** `EvalDispatcher.SHIM_JS` runs inside
   the embedded page's JavaScript context. Responsibility: wrap
   the user's snippet, await any returned Promise,
   `JSON.stringify` the resolved value, base64-encode the
   pipe-separated record, post via
   `window.__webview_eval_result__(...)`.
2. **Native bind layer:** the engine's native bind machinery
   converts the JS-side call into a `WebViewNativeCallback`
   invocation on the engine's UI thread. Out of scope for this
   canvas — owned by [[in-process-webview-java-api]],
   [[swing-heavyweight-webview-embedding]],
   [[swing-lightweight-webview-embedding]] (and ultimately the
   native code at `src_c/`).
3. **Dispatcher layer:** `EvalDispatcher.dispatch(rawJson)` parses
   the bind envelope, decodes the base64 payload, splits the
   pipe record, looks up the future, optionally hops to the EDT,
   and completes the future.
4. **Future-consumer layer:** the host application's
   `.thenAccept` / `.thenApply` / `.exceptionally` /
   `.handle` continuations. Out of scope of this canvas — purely
   the standard Java 8 `CompletableFuture` contract.

## O · Operations

### 1. Construct the dispatcher — EvalDispatcher constructor
File: `src/ca/weblite/webview/EvalDispatcher.java`

1. Responsibility: build a fresh per-engine fan-out hub with its
   sink, threading flag, and disposal label fixed at construction
   time.
2. Methods:
   - `public EvalDispatcher(EvalSink sink, boolean marshalToEdt, String disposeLabel)`
     - Logic:
       - `if (sink == null) throw new NullPointerException("sink");`
       - `if (disposeLabel == null) throw new NullPointerException("disposeLabel");`
       - Store `this.sink = sink`, `this.marshalToEdt = marshalToEdt`,
         `this.disposeLabel = disposeLabel`.
       - Initialize `this.pending = new ConcurrentHashMap<>();`
       - Initialize `this.nextId = new AtomicLong(0);`
       - `disposed` is `false` by default.
3. Constraints / Invariants:
   - All four fields except `pending` / `nextId` / `disposed` are
     `final` and assigned exactly once. The mutable state lives in
     `pending` (concurrent-safe), `nextId` (atomic), and
     `disposed` (volatile boolean).
   - The constructor is the only legitimate construction site;
     consuming surfaces store the resulting dispatcher in a `final`
     field on the engine wrapper.

### 2. Submit a Snippet — EvalDispatcher.evalAsync
File: `src/ca/weblite/webview/EvalDispatcher.java`

1. Responsibility: allocate request state, wrap the user JS, hand
   it to the engine via the sink, and return the future.
2. Methods:
   - `public CompletableFuture<String> evalAsync(String js)`
     - Logic:
       - `Objects.requireNonNull(js, "js");` — null is a programming
         error, propagate `NullPointerException` synchronously per
         analysis edge case E5.
       - `if (disposed) { CompletableFuture<String> f = new CompletableFuture<>(); f.completeExceptionally(new IllegalStateException(disposeLabel + " disposed")); return f; }`
       - Allocate `long id = nextId.incrementAndGet();`
         (post-increment guarantees `id >= 1`).
       - Allocate `CompletableFuture<String> f = new CompletableFuture<>();`
       - `pending.put(id, f);` — insert BEFORE the eval call.
       - Build the wrapped snippet: `String wrapped = wrap(js, id);`
         where `wrap` is the private helper that returns
         `SHIM_JS` with `__ID__` replaced by `Long.toString(id)` and
         `__USER_JS__` replaced by the user snippet. (See §4 for
         the wrapper template; the substitution is done at call
         time so the template can stay a literal `static final
         String` constant.)
       - Issue the eval: `sink.eval(wrapped);` — if the sink
         throws (e.g. its underlying `checkAlive` triggers because
         the peer was just disposed on another thread), catch the
         exception, remove `id` from `pending`, and complete `f`
         exceptionally with the caught exception. This collapses
         the "sink throws synchronously" race into a normal
         exceptional completion.
       - `return f;`
3. Constraints / Invariants:
   - `evalAsync` is safe to call from any Java thread.
   - The `pending.put` happens-before the `sink.eval`, so even on
     a hypothetical synchronous-eval engine the resolver callback
     can never lose the lookup.
   - Cancellation: `f.cancel(true)` marks the future cancelled but
     does NOT remove the entry from `pending`; the entry is
     dropped when the resolver callback eventually fires (it
     looks up the cancelled future, removes it, and finds
     `cancelled` on `complete` is a no-op). Documented behavior.

### 3. Resolve a Result — EvalDispatcher.dispatch
File: `src/ca/weblite/webview/EvalDispatcher.java`

1. Responsibility: consume the raw JSON the native bind passes to
   the resolver callback, extract the base64 payload, parse the
   `<id>|<ok>|<rest>` record, and complete the matching future.
2. Methods:
   - `public void dispatch(String rawJson)`
     - Logic:
       - `if (rawJson == null) return;` — silent drop, matches
         `ConsoleDispatcher.dispatch` at
         `ConsoleDispatcher.java:206-215`.
       - `String b64 = extractFirstArg(rawJson);` — copy
         `ConsoleDispatcher.extractFirstArg`
         (`ConsoleDispatcher.java:255-263`) verbatim; it scans for
         `"args":[` and pulls out the first double-quoted string.
       - `if (b64 == null) return;` — malformed envelope.
       - `String payload = decodeBase64Utf8(b64);` — copy
         `ConsoleDispatcher.decodeBase64Utf8`
         (`ConsoleDispatcher.java:265-274`) verbatim.
       - `if (payload == null) return;` — base64 decode failure.
       - `int p1 = payload.indexOf('|');`
       - `if (p1 <= 0) return;` — malformed record.
       - `long id; try { id = Long.parseLong(payload.substring(0, p1)); } catch (NumberFormatException e) { return; }`
       - `int p2 = payload.indexOf('|', p1 + 1);`
       - `if (p2 < 0) return;` — malformed record (missing
         payload separator).
       - `String okFlag = payload.substring(p1 + 1, p2);`
       - `String rest = payload.substring(p2 + 1);`
       - `CompletableFuture<String> f = pending.remove(id);`
         — removal is the only success-path leak prevention.
       - `if (f == null) return;` — id was already drained
         (dispose race, or duplicate fire from E6).
       - Determine result:
         - `okFlag.equals("1")` → `complete(f, rest, null);`
         - else → `complete(f, null, new JavaScriptEvalException(rest));`
       - `complete(...)` runs the actual completion, optionally
         on the EDT. See §3a.
3. Constraints / Invariants:
   - The native callback (`WebViewNativeCallback.invoke`) is
     anchored in the consuming engine wrapper's `heap` so it
     cannot be GC'd while the binding is live; this canvas
     does NOT manage that heap (the consuming canvases do).
   - The dispatcher silently drops malformed payloads (matches
     existing dispatcher precedent). Logging is OUT of scope per
     codebase Norms (no `Logger` in dispatcher classes).

### 3a. Complete the Future — EvalDispatcher.complete (private helper)
File: `src/ca/weblite/webview/EvalDispatcher.java`

1. Responsibility: branch on `marshalToEdt` and complete the
   future either inline or on the EDT.
2. Methods:
   - `private void complete(final CompletableFuture<String> f, final String value, final Throwable error)`
     - Logic:
       - `final Runnable r = () -> { if (error != null) f.completeExceptionally(error); else f.complete(value); };`
       - `if (marshalToEdt) { SwingUtilities.invokeLater(r); } else { r.run(); }`
3. Constraints / Invariants:
   - On the EDT-marshaled path, the completion happens-after the
     `pending.remove(id)` in §3, so the future is always removed
     from the map before its continuations fire.
   - `SwingUtilities.invokeLater` does not throw even if Swing has
     not been initialized in the JVM — it schedules onto the EDT
     creation lazily. The standalone surface does NOT pay the
     Swing-init cost because it constructs the dispatcher with
     `marshalToEdt = false`.

### 4. Canonical JS Shim — EvalDispatcher.SHIM_JS
File: `src/ca/weblite/webview/EvalDispatcher.java`

1. Responsibility: provide the canonical, idempotent JS shim that
   wraps every user snippet. The shim is installed once per page
   via `addOnBeforeLoad` (by each consuming canvas's peer-create
   path).
2. Constant:
   - `public static final String SHIM_JS = ...;`

   The shim has two parts: (a) one-time installation guarded by
   `window.__webview_eval_installed__`, which defines helper
   functions `__webview_eval_b64encode(s)` and
   `__webview_eval_wrap(id, fn)` on `window`; (b) the per-call
   wrapping template, applied at `evalAsync` time by the dispatcher
   via string substitution.

   The constant SHIM_JS literal is the one-time installer:

   ```js
   (function(){
     if(window.__webview_eval_installed__)return;
     window.__webview_eval_installed__=true;
     window.__webview_eval_b64=function(s){
       try{return btoa(unescape(encodeURIComponent(s)));}
       catch(e){return '';}
     };
     window.__webview_eval_post=function(id,ok,msg){
       try{
         var sink=window.__webview_eval_result__;
         if(typeof sink!=='function')return;
         var rec=id+'|'+(ok?'1':'0')+'|'+msg;
         sink(window.__webview_eval_b64(rec));
       }catch(e){}
     };
   })();
   ```

   The per-call wrapper template, applied by the dispatcher's
   `wrap(js, id)` private helper:

   ```js
   (function(){
     var __id=__ID__;
     var __sink_post=window.__webview_eval_post;
     try{
       var __r=(function(){__USER_JS__})();
       Promise.resolve(__r).then(function(v){
         try{
           var s=JSON.stringify(v===undefined?null:v);
           if(s===undefined)s='null';
           __sink_post(__id,true,s);
         }catch(e){
           __sink_post(__id,false,(e&&e.message)||String(e));
         }
       },function(e){
         __sink_post(__id,false,(e&&e.message)||String(e));
       });
     }catch(e){
       __sink_post(__id,false,(e&&e.message)||String(e));
     }
   })();
   ```

   - `__ID__` is replaced via `String.replace` with the literal
     `Long.toString(id)` at wrap time.
   - `__USER_JS__` is replaced with the verbatim user snippet
     (no escaping — the inner `function(){ ... }` body accepts
     any valid JS source).
   - The cached `var __sink_post=window.__webview_eval_post;`
     captures the post function BEFORE the user JS runs, so user
     JS that reassigns `window.__webview_eval_result__` or
     `window.__webview_eval_post` cannot reroute the result
     (analysis E8).
   - The outer `try/catch` covers both synchronous throws inside
     the user IIFE and any unexpected wrapper-internal errors.
3. Constraints / Invariants:
   - The shim is idempotent across navigations
     (`window.__webview_eval_installed__` guard mirrors
     `ConsoleDispatcher.java:67-68` and
     `WebViewMouseDispatcher.java:79-80`).
   - The shim runs at document-start (it is installed via
     `addOnBeforeLoad`), so it is in place before any user-page
     script executes.
   - The wrapper template is parameterized ONLY by id and user
     snippet; no escaping is required because both substitutions
     land in positions where any string is syntactically valid
     (numeric literal in `var __id=...`; function-body source in
     the inner IIFE).

### 5. Drain Pending Futures — EvalDispatcher.disposeAllPending
File: `src/ca/weblite/webview/EvalDispatcher.java`

1. Responsibility: complete every still-pending future exceptionally
   on dispose so `.get()`-waiting callers wake up, and reject all
   subsequent `evalAsync` calls without touching the engine.
2. Methods:
   - `public void disposeAllPending()`
     - Logic:
       - `if (disposed) return;` — idempotent.
       - `disposed = true;`
       - For each entry in `pending`:
         - Build `IllegalStateException ise = new IllegalStateException(disposeLabel + " disposed");`
         - Call `entry.getValue().completeExceptionally(ise);`
       - `pending.clear();`
3. Constraints / Invariants:
   - `disposeAllPending` is safe to call from any Java thread.
   - Multiple calls are no-ops after the first (idempotent).
   - The dispatcher does NOT marshal the dispose completions to
     the EDT, even when `marshalToEdt` is `true`. Rationale:
     dispose is called from the engine's tear-down path which is
     itself often on the EDT (e.g.
     `WebViewLightweightComponent.removeNotify`); a re-entrant
     `SwingUtilities.invokeLater` from inside an EDT callback
     just defers to the next EDT cycle, which can race with the
     consuming code's own teardown. Completing inline keeps the
     dispose path deterministic. Continuations of completed
     futures still run on whatever thread Java's
     `CompletableFuture` schedules them on (the completing thread,
     unless the caller used `*Async` variants).

### 6. Marshal-to-EDT Helpers — internal `isHeadless` consideration
File: `src/ca/weblite/webview/EvalDispatcher.java`

1. Responsibility: no separate operation — the `complete` method
   in §3a handles the branching. This operation is a no-op
   placeholder reserved for a future refinement (e.g. detecting
   `GraphicsEnvironment.isHeadless()` and forcing
   `marshalToEdt = false` in that case). The current canvas does
   not implement headless-mode auto-downgrade; the consuming
   canvases set `marshalToEdt` correctly at construction time.

### 7. Construct the Exception — JavaScriptEvalException
File: `src/ca/weblite/webview/JavaScriptEvalException.java`

1. Responsibility: provide a typed exception subclass callers can
   recognize via `instanceof` in `.exceptionally` /
   `.handle` continuations.
2. Methods:
   - `public JavaScriptEvalException(String message)`
     - Logic: `super(message);`
3. Constraints / Invariants:
   - `serialVersionUID = 1L;` is declared as a `private static
     final long` to satisfy `Serializable` and silence the
     `serial` warning. The codebase uses Java 8 source/target and
     does not suppress serial warnings globally, so the field is
     required.
   - The class is final-NOT-but-effectively-final: no subclass is
     defined or expected. Marked plain `public class` (not
     `final`) to match the convention of other exception classes
     in the JDK.

## N · Norms
- **Annotation standards:** no annotations on the dispatcher class
  or its methods (matches `ConsoleDispatcher` and
  `WebViewMouseDispatcher`). `JavaScriptEvalException` carries no
  annotations either.
- **Dependency injection / construction:** the `EvalSink` is
  constructor-injected. Consuming engine wrappers do
  `new EvalDispatcher((js) -> ..., true, "EmbeddedWebView")` once
  in their own constructor (or `createPeer` / `addNotify`) and
  store the result in a `final` field. There is no setter for the
  sink, the marshaling flag, or the disposal label.
- **Exception handling:**
  - The dispatcher's `dispatch(rawJson)` method MUST silently
    drop malformed payloads — return early without throwing. Same
    contract as `ConsoleDispatcher.dispatch`
    (`ConsoleDispatcher.java:206-215`).
  - The dispatcher's `evalAsync` MUST propagate
    `NullPointerException` synchronously for null `js`, but MUST
    catch any exception from `sink.eval(...)` and convert it into
    an exceptional future completion (so callers always observe
    failures through the future, not through a thrown exception
    from `evalAsync` itself — unless the failure was a
    programming error like null input).
  - `JavaScriptEvalException` MUST contain only the JS-side
    message text. It MUST NOT contain native call stacks, peer
    pointers, or any other Java-side context — callers consuming
    the exception treat it as JS-side diagnostic only.
- **Data validation:**
  - The id parser uses `Long.parseLong`, which is sufficient since
    the JS side emits an integer literal via
    `id+'|'+...`. A `NumberFormatException` results in a silent
    drop.
  - The ok-flag check is exact-string `equals("1")`; anything
    else is treated as the error path (defensive against future
    wire-format additions).
- **Logging:** none. The dispatcher emits no log messages. Same
  convention as `ConsoleDispatcher` and
  `WebViewMouseDispatcher`.
- **Documentation standards:**
  - The class Javadoc must lead with the
    `<strong>Internal:</strong> not part of the public API
    surface.` paragraph (verbatim from
    `ConsoleDispatcher.java:15-23`).
  - The `SHIM_JS` constant must carry a Javadoc that documents
    the wrapper contract and the idempotency guarantee.
  - `evalAsync(String)`'s Javadoc must document:
    - The JS contract (use `return` to yield, undefined → null,
      Promise awaiting, exception types).
    - The threading contract (continuations run on EDT or on the
      binding-callback thread depending on the consuming
      surface).
    - That cancelling the future does not abort in-page JS.
  - `JavaScriptEvalException` carries a one-paragraph class
    Javadoc describing when it is thrown and how to retrieve its
    message from a `CompletableFuture` continuation.
- **Java 8 idioms:** `CompletableFuture.failedFuture` (Java 9+) is
  NOT used. The Java-8-compatible
  `new CompletableFuture<>(); f.completeExceptionally(...);
  return f;` form is used throughout.

## S · Safeguards
- **Functional constraints:**
  - Every successful binding callback MUST remove its entry from
    `pending` before completing the future (leak prevention).
    Verified by the `EvalDispatcherTest` test that asserts
    `pending` is empty after a resolved cycle.
  - `evalAsync` MUST insert into `pending` BEFORE invoking the
    sink (race-free synchronous-eval defense), even though the
    documented native eval entry points are asynchronous.
  - The dispatcher's `disposed` flag MUST be checked at the top
    of `evalAsync` (not the bottom) so a `disposeAllPending`
    racing against `evalAsync` cannot insert a future into a
    cleared map.
- **Performance constraints:**
  - One `ConcurrentHashMap` insert and one removal per
    `evalAsync` call. No additional allocations beyond the
    `CompletableFuture` itself and the wrapped JS string.
  - The base64 decode plus three `indexOf` operations on the
    dispatch path complete in microseconds per call (matches
    `ConsoleDispatcher` benchmark profile).
  - No background threads or schedulers are created by the
    dispatcher. All work runs on either the binding-callback
    thread (raw dispatch) or the EDT (post-marshaling).
- **Security constraints:**
  - The reserved binding name `__webview_eval_result__` lives
    under the existing `__webview_` prefix and is therefore
    rejected by the runtime check on
    `WebViewComponent.addJavascriptCallback`
    (`WebViewHeavyweightComponent.java:153-158`,
    `WebViewLightweightComponent.java:416-421`). User code cannot
    register a binding of the same name through the public API.
  - The wrapper template caches `window.__webview_eval_post`
    BEFORE invoking user JS, so user JS that reassigns or deletes
    the binding cannot reroute eval results to attacker-controlled
    JS (analysis E8).
  - `JavaScriptEvalException.getMessage()` must contain only the
    JS-side text. Callers logging the message to user-facing UI
    are responsible for HTML-escaping it; the dispatcher does not
    sanitize.
  - The wrapped JS template does not concatenate the request id
    or user snippet into any HTML context; both substitutions are
    string-into-JS-source and inherit the page's existing CSP.
    CSPs that forbid `eval` will reject the wrapper template
    wholesale — same constraint as the existing fire-and-forget
    `eval` path, documented but not mitigated here.
- **Integration constraints:**
  - `EvalDispatcher` MUST NOT have a compile-time dependency on
    `WebView`, `EmbeddedWebView`, `OffscreenWebView`, or any
    Swing component. The `EvalSink` indirection is the entire
    decoupling mechanism. Violation: the dispatcher becomes
    untestable without a live native peer.
  - `EvalDispatcher` MAY import `javax.swing.SwingUtilities`. The
    `SwingUtilities` class loads lazily and does not transitively
    pull in heavy Swing classes when `marshalToEdt` is `false`.
  - The dispatcher's resolver-binding callback is registered by
    the *consuming* canvas (not this one), and is anchored in
    the consuming engine wrapper's `heap` list to prevent GC.
    This canvas relies on that anchoring; if a consuming canvas
    forgets the `heap.add(...)` step, native callbacks fire into
    a freed Java ref and the JVM SIGSEGVs. Each consuming canvas
    must include a Safeguard reminding implementers.
- **Business rule constraints:**
  - In-flight futures whose page navigates away mid-flight stay
    pending forever (no navigation-driven drain). Callers
    needing a deadline use `.orTimeout` (Java 9+) or
    `.get(timeout, unit)`. Documented behavior; future canvas
    may revisit.
  - `CompletableFuture.cancel(true)` does NOT abort the in-page
    JS. Documented behavior; cancellation is purely a Java-side
    signal.
- **Exception-handling constraints:**
  - JS-originated failures (sync throw, Promise rejection,
    `JSON.stringify` `TypeError`) MUST surface as
    `JavaScriptEvalException`.
  - Lifecycle violations (disposed dispatcher, null `js`) MUST
    surface as `IllegalStateException` or `NullPointerException`
    respectively — NOT as `JavaScriptEvalException`. Callers
    distinguish "the page said no" from "we have no page" via
    `instanceof` on the cause.
  - The dispatcher MUST NOT swallow exceptions from
    `CompletableFuture` continuations registered by the caller —
    those propagate per the standard `CompletableFuture`
    contract and the dispatcher has no involvement.
- **Technical constraints:**
  - Java 8 source / target (`pom.xml:39-41`).
  - No new external dependencies. The codebase has only
    `junit:4.12` in `pom.xml`; the dispatcher uses only `java.util.*`
    and `java.util.concurrent.*` plus `javax.swing.SwingUtilities`.
  - No JNI additions. All round-tripping uses existing native
    entry points via the consuming canvases.
- **Data constraints:**
  - `pending` keys are `Long` boxes of monotonic positive ids
    (`AtomicLong` starts at 0, returns `1, 2, 3, …` via
    `incrementAndGet`). Id reuse is bounded by `Long.MAX_VALUE`
    calls per dispatcher lifetime (~292 years of one call per
    nanosecond) — not a real-world concern.
  - The pipe-separated wire format uses `|` as the record
    separator. The dispatcher's parse path splits at most twice;
    pipes inside the JSON-stringified value or error message
    are preserved verbatim in the third field.
- **API constraints:**
  - `EvalDispatcher` is `public final`. Adding new constructors
    or making the class non-final requires a canvas update.
  - `JavaScriptEvalException` is `public` and effectively final
    (no subclass exists). It exposes only the single
    constructor inherited via `super(message)`.
  - `EvalDispatcher.EvalSink` is `public` and a
    `@FunctionalInterface`; adding new abstract methods to it is
    a binary-compatibility-breaking change and MUST NOT be done.
    Future extensions go through `default` methods (parallel to
    `WebViewMouseListener` evolution policy in
    [[swing-webview-context-menu-and-dom-mouse-events]]).
