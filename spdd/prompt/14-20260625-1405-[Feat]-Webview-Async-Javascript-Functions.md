---
generated_at: 2026-06-25T14:05:00-07:00
---

# REASONS Canvas: Value-Returning JavaScript Functions — Dispatcher and Java API

## R · Requirements
- Let Java developers expose a Java method to the page as a real,
  value-returning JavaScript function **without writing any
  JavaScript**. In the page the registered function appears as a
  global that returns a Promise: `const result = await
  window.<name>(arg)`. This is the inverse direction of the
  future-returning eval feature ([[webview-async-javascript-eval]]):
  there, Java calls JS and awaits a result; here, JS calls Java and
  awaits a result.
- Provide a per-engine fan-out hub (`FunctionDispatcher`) usable by
  every WebView surface — standalone in-process `WebView`
  ([[in-process-webview-java-api]]), heavyweight embedded
  `WebViewHeavyweightComponent`
  ([[swing-heavyweight-webview-embedding]]), and lightweight
  `WebViewLightweightComponent`
  ([[swing-lightweight-webview-embedding]]). The dispatcher owns the
  `name → handler` registry, a background worker pool for synchronous
  handlers, the canonical JS shim (`SHIM_JS`), the per-name wrapper
  installer, the inbound-call parsing, the outbound resolve, and the
  dispose drain.
- Two registration overloads, both named `addJavascriptFunction`,
  exposed on every surface (and on the abstract `WebViewComponent`):
  - `addJavascriptFunction(String name, JavascriptFunction fn)` — the
    handler is **synchronous** (`String run(String arg) throws
    Exception`). The library runs it on a background worker thread so
    a slow handler can never block, or deadlock against, the engine
    UI thread. This is the headline API for the
    JavaScript-allergic developer.
  - `addJavascriptFunction(String name, AsyncJavascriptFunction fn)` —
    the handler returns a `CompletableFuture<String>` for
    inherently-asynchronous work. The dispatcher resolves the page
    Promise when the future completes.
  - Overload resolution is unambiguous because the two functional
    interfaces have different abstract-method return types: a lambda
    `arg -> "x"` binds to `JavascriptFunction`; a lambda
    `arg -> someCompletableFuture` binds to `AsyncJavascriptFunction`.
- Result encoding is **string passthrough**, consistent with
  `evalAsync` ([[webview-async-javascript-eval]]): the handler returns
  a `String`, and the page Promise resolves to that exact string. For
  structured results the handler returns JSON text and the page
  `JSON.parse`s it. No JSON-serialization dependency is added (the
  project has none — `pom.xml`).
- Error propagation: if a synchronous handler throws, or an
  asynchronous handler's future completes exceptionally, the page
  Promise **rejects** with an `Error` whose message is the Java-side
  exception message. Successful completion **resolves** with the
  returned string.
- The reserved channel names MUST live under the existing
  `RESERVED_BINDING_PREFIX = "__webview_"` so they are protected from
  caller collision by the existing guard on
  `WebViewComponent.addJavascriptCallback`
  (`WebViewComponent.java:57`, enforced at
  `WebViewHeavyweightComponent.java` and
  `WebViewLightweightComponent.java`). Concretely: an inbound binding
  `__webview_fn_call__` and a page-side resolver
  `window.__webview_fn_resolve__`. In addition, the public
  `addJavascriptFunction` MUST itself reject a `name` that starts with
  `__webview_` (and reject a `name` that is not a valid JS identifier),
  so a caller cannot shadow the internal channels or inject JS through
  the per-name wrapper.
- The implementation MUST NOT introduce a JSON-parsing dependency. Wire
  format follows the precedent set by `EvalDispatcher`
  (`EvalDispatcher.java`), `ConsoleDispatcher`, and
  `WebViewMouseDispatcher`: the JS shim base64-encodes a
  pipe-separated record before posting it through the binding, and the
  Java side uses `indexOf`-based slicing on the known-shape native bind
  envelope.
- The implementation MUST NOT require new JNI entry points and MUST NOT
  touch native code. The existing eval entry points (`webview_eval`,
  `webview_embed_eval`, `webview_offscreen_eval`), bind entry points
  (`webview_bind`, `webview_embed_bind`, `webview_offscreen_bind`),
  and init entry points (`webview_init`, `webview_embed_init`,
  `webview_offscreen_init`) are sufficient. The dispatcher delegates
  the actual native calls to its owner via an injected sink (parallel
  to `WebViewMouseDispatcher.FlagSink`, which already carries both
  `eval` and `addOnBeforeLoad`) so it has no compile-time dependency on
  any engine wrapper class.
- The JS shim MUST be idempotent across page navigations (re-entry
  guarded by `window.__webview_fn_installed__`, mirroring
  `EvalDispatcher.SHIM_JS` and `ConsoleDispatcher.SHIM_JS`). Per-name
  wrappers are (re)installed at document-start on every navigation
  because they are registered via the `addOnBeforeLoad` sink; a
  registration made while a page is already loaded ALSO takes effect
  immediately on the current document via a one-shot `eval` of the same
  wrapper.
- The JS shim MUST be robust against user JS that mutates the resolver
  binding: the per-call invoke helper caches a stable reference to the
  inbound binding at install time, mirroring `EvalDispatcher`'s
  cache-before-user-JS defense.
- Threading / deadlock contract (the reason this feature exists rather
  than a synchronous value-returning callback): a synchronous,
  value-returning `addJavascriptCallback` would block the engine UI
  thread until Java produced the value, deadlocking against the Swing
  EDT — the precise hazard removed by
  [[eliminate-edt-appkit-sync-deadlock-on-macos]]. This feature is
  deadlock-free by construction: the inbound binding callback returns
  immediately, synchronous handlers run on a background pool, and the
  result is delivered back to the page through a non-blocking `eval`
  (NOT `evalAsync`, and never `.get()`).
- Definition of Done: a `FunctionDispatcherTest` JUnit test under
  `test/` exercises name registration (sync + async), reserved-name
  and invalid-name rejection, inbound-envelope parsing, that a
  synchronous handler runs OFF the calling thread, async-handler
  resolve, handler-throw → reject, malformed-payload silent drop, and
  dispose draining — all with a mock sink so no native WebView is
  required. Integration with the live engines is covered by the
  consuming canvases. The `WebViewAsyncCallbackDemo` under `demos/` is
  updated (by hand, out of scope for generation) to use
  `addJavascriptFunction`, and the README's API/Demos section mentions
  the new method.
- Out of scope (explicit non-goals, owned elsewhere or deferred):
  - The public `addJavascriptFunction(...)` methods on `WebView`.
    Owned by [[in-process-webview-java-api]].
  - The abstract `addJavascriptFunction(...)` on `WebViewComponent`.
    Owned by [[swing-webview-component-mode-selection]].
  - The concrete `addJavascriptFunction(...)` on
    `WebViewHeavyweightComponent` and `EmbeddedWebView`. Owned by
    [[swing-heavyweight-webview-embedding]].
  - The concrete `addJavascriptFunction(...)` on
    `WebViewLightweightComponent` and `OffscreenWebView`. Owned by
    [[swing-lightweight-webview-embedding]].
  - The per-engine registration of `FunctionDispatcher.SHIM_JS` and the
    `__webview_fn_call__` binding inside `createPeer` / `addNotify` /
    `WebView.show()`. Owned by the consuming canvases above. THIS
    canvas owns `FunctionDispatcher`, `JavascriptFunction`, and
    `AsyncJavascriptFunction` only.
  - Multi-argument JS functions. The function takes a single string
    argument this iteration; callers needing multiple values pack a
    JSON string and unpack it Java-side.
  - Typed / auto-JSON-decoded results. The page Promise resolves to a
    `String`; callers parse it with the JSON tool of their choice.
  - A synchronous blocking `String callSync(...)` convenience. The
    whole point is to avoid blocking the UI thread.
  - Aborting an in-flight Java handler when the page drops/ignores the
    returned Promise. The handler runs to completion; its result is
    discarded if no resolver remains.

## E · Entities

- **FunctionDispatcher** (new public class,
  `src/ca/weblite/webview/FunctionDispatcher.java`). Mirrors
  `EvalDispatcher` (`EvalDispatcher.java`) in shape and visibility,
  with a `name → handler` registry and a worker pool instead of an
  in-flight future map. Internal-only — the class is `public` for
  cross-package access from `ca.weblite.webview.swing`, NOT part of
  the supported API (Javadoc leads with the verbatim
  `<strong>Internal:</strong> not part of the public API surface.`
  paragraph used by `EvalDispatcher`/`ConsoleDispatcher`).
  Invariants:
  - `INBOUND_CHANNEL: public static final String` —
    `"__webview_fn_call__"`. The reserved binding name the consuming
    surface registers; JS posts `<id>|<name>|<arg>` (base64) through
    it.
  - `SHIM_JS: public static final String` — the canonical base shim
    installed once per page via `addOnBeforeLoad`. Defines the b64
    helpers, the pending-resolver map, `window.__webview_fn_invoke`,
    and `window.__webview_fn_resolve__`. Idempotency-guarded with
    `window.__webview_fn_installed__`. Full text in Operation 5.
  - `handlers: ConcurrentHashMap<String, AsyncJavascriptFunction>` —
    the registry. A synchronous `JavascriptFunction` is adapted to an
    `AsyncJavascriptFunction` at registration time (see Approach) so
    the inbound dispatch path is uniform. Allocated at construction;
    never replaced; cleared on `disposeAll`.
  - `worker: final ExecutorService` — daemon-threaded cached pool used
    to run synchronous handlers off the engine UI thread. Created at
    construction; shut down on `disposeAll`.
  - `sink: final FunctionSink` — non-null; the engine-specific
    delivery channel (eval + addOnBeforeLoad) injected by the
    constructor. The dispatcher never touches `WebViewNative` directly.
  - `disposeLabel: final String` — non-null; folded into messages /
    thread names (e.g. `"EmbeddedWebView"`).
  - `disposed: volatile boolean` — `false` initially; flipped by
    `disposeAll()`. Subsequent `registerSync`/`registerAsync` calls
    after the flag flips are no-ops.
  - `NAME_PATTERN: a compiled validity check` — a registered name must
    match `^[A-Za-z_$][A-Za-z0-9_$]*$` and must not start with
    `__webview_`. Enforced in `registerSync`/`registerAsync`.

- **FunctionDispatcher.FunctionSink** (new public nested
  `@FunctionalInterface`-style interface — note it has TWO methods, so
  it is NOT annotated `@FunctionalInterface`; modeled on
  `WebViewMouseDispatcher.FlagSink` which likewise carries `eval` and
  `addOnBeforeLoad`). Invariants:
  - `void eval(String js)` — issue a fire-and-forget native eval of
    the given snippet on the live engine. Used for the outbound
    resolve and for the one-shot install of a per-name wrapper on the
    already-loaded document. The implementation owns engine-alive
    checks and `IllegalStateException` swallowing.
  - `void addOnBeforeLoad(String js)` — register a document-start
    script so per-name wrappers survive navigations. Same swallowing
    contract.

- **JavascriptFunction** (new public top-level interface,
  `src/ca/weblite/webview/JavascriptFunction.java`).
  `@FunctionalInterface`. Single method
  `String run(String arg) throws Exception`. Synchronous handler; the
  returned `String` resolves the page Promise, a thrown exception
  rejects it. The library guarantees `run` is invoked off the engine
  UI thread.

- **AsyncJavascriptFunction** (new public top-level interface,
  `src/ca/weblite/webview/AsyncJavascriptFunction.java`).
  `@FunctionalInterface`. Single method
  `CompletableFuture<String> run(String arg)`. The dispatcher resolves
  the page Promise when the future completes normally, rejects it when
  the future completes exceptionally. `run` is invoked on the inbound
  binding-callback thread and MUST return promptly with a future
  (it MUST NOT block).

## A · Approach
- **Mirror the existing dispatcher pattern.** `EvalDispatcher`,
  `ConsoleDispatcher`, and `WebViewMouseDispatcher` define the
  canonical shape: per-engine instance, registered eagerly at peer
  creation, public-but-internal visibility, base64-encoded payload
  over a reserved `__webview_*` channel, `indexOf`-based parsing on
  the native bind envelope, sink-decoupled from the engine.
  `FunctionDispatcher` follows the same shape; the differences are
  (a) state is a `name → handler` registry plus a worker pool rather
  than an in-flight future map, and (b) the matching of a call to its
  pending resolver happens in JS (the page owns the request id), so
  Java is essentially stateless per call beyond routing and echoing
  the id back.
- **Unify sync and async at registration.** `registerSync(name,
  JavascriptFunction fn)` adapts the synchronous handler into an
  `AsyncJavascriptFunction` by submitting `fn.run(arg)` to the worker
  pool and returning the resulting future (a thrown checked/unchecked
  exception completes the future exceptionally). `registerAsync(name,
  AsyncJavascriptFunction fn)` stores the handler directly. Both store
  into `handlers`, validate the name, and install the per-name wrapper
  via the sink. The inbound dispatch path then deals only with
  `AsyncJavascriptFunction`.
- **No JNI / native changes.** Built entirely on existing primitives,
  exactly as console capture, DOM mouse events, and async eval are:
  `addOnBeforeLoad` installs the base shim and per-name wrappers;
  `bind` carries the inbound channel; `eval` delivers the outbound
  resolve. The native eval entry points are fire-and-forget
  (`WebViewNative.java`: "Evaluation happens asynchronously … the
  result … is ignored. Use RPC bindings if you want to receive
  notifications"), which is why the round-trip is built in Java + JS.
- **`FunctionSink` decouples dispatcher from engine.** The dispatcher
  does not know whether it wraps `webview_eval`/`webview_embed_eval`/
  `webview_offscreen_eval` (and the matching `*_init`). The consuming
  surface supplies a sink at construction; the dispatcher trusts it to
  do alive-checks, marshaling, and `IllegalStateException` swallowing.
  Same role as `WebViewMouseDispatcher.FlagSink`.
- **Registration flow** (`registerSync` / `registerAsync`):
  1. Validate: `name` non-null, matches `NAME_PATTERN`, does not start
     with `__webview_`; handler non-null. Violations throw
     `IllegalArgumentException` (name) / `NullPointerException`
     (nulls) synchronously — these are programming errors.
  2. If `disposed`, no-op return.
  3. Put the (adapted) handler into `handlers`.
  4. Build the per-name wrapper JS (`wrapperFor(name)`), register it
     via `sink.addOnBeforeLoad(wrapper)` (future navigations) AND
     `sink.eval(wrapper)` (current document) so the function is usable
     immediately and after reloads.
- **Inbound dispatch flow** (`dispatch(rawJson)`):
  1. Extract the base64 arg from the native bind envelope
     (`extractFirstArg`, copied from `EvalDispatcher`/
     `ConsoleDispatcher`), decode UTF-8 (`decodeBase64Utf8`, copied).
  2. Split the record `<id>|<name>|<arg>` with two `indexOf('|')`
     calls; the third field (`arg`) is taken verbatim and MAY contain
     pipes.
  3. Look up `handlers.get(name)`. If absent (unregistered or
     post-dispose), resolve the page Promise with a rejection
     (`<id>|0|no such function: <name>`) so the caller's `await` does
     not hang.
  4. Invoke `handler.run(arg)`; on the returned future register a
     completion that calls `resolve(id, ok, payload)`:
     - normal completion → `resolve(id, true, value)` (value may be
       `null`; coerce to the empty string for transport).
     - exceptional completion → `resolve(id, false, message)` where
       message is the exception's message (or its class name when the
       message is null).
- **Outbound resolve** (`resolve(long id, boolean ok, String
  payload)`): build `<id>|<okFlag>|<payload>`, base64-encode it, and
  call `sink.eval("window.__webview_fn_resolve__('" + b64 + "')")`.
  The base64 alphabet (`A–Za–z0–9+/=`) is safe inside a single-quoted
  JS string, so no escaping is required. `eval` is asynchronous and
  non-blocking — the resolve hop never waits on the page, preserving
  the deadlock-free property.
- **Wire-format choice: base64 + pipe-separated**, same precedent as
  the existing dispatchers. JS uses
  `btoa(unescape(encodeURIComponent(s)))`; Java uses
  `Base64.getDecoder()/getEncoder()` with `new String(bytes,"UTF-8")`.
- **Dispose ordering** (`disposeAll`):
  1. `if (disposed) return;` then `disposed = true`.
  2. `handlers.clear();`
  3. `worker.shutdownNow();` — in-flight synchronous handlers are
     interrupted; their results, if any, are dropped (no resolver
     remains after native teardown). The dispatcher does NOT attempt
     to unbind the inbound channel — native destroy invalidates it.

## S · Structure

### Inheritance Relationships
1. `FunctionDispatcher` is a concrete `public final` class extending
   `Object`, implementing no interfaces. Symmetric with
   `EvalDispatcher` (`EvalDispatcher.java`).
2. `FunctionDispatcher.FunctionSink` is a `public static` nested
   interface with two methods (`eval`, `addOnBeforeLoad`).
   Implementations are anonymous inner classes / lambdas-with-two-
   methods... — since it has two abstract methods it is implemented as
   an anonymous inner class in the consuming wrappers (cannot be a
   single lambda). Modeled on `WebViewMouseDispatcher.FlagSink`.
3. `JavascriptFunction` and `AsyncJavascriptFunction` are
   `public @FunctionalInterface` top-level interfaces extending
   nothing. No named implementations exist in this canvas (callers
   pass lambdas / method references).

### Dependencies
1. `FunctionDispatcher` depends on:
   - `java.util.concurrent.CompletableFuture` (Java 8).
   - `java.util.concurrent.ConcurrentHashMap` — the registry.
   - `java.util.concurrent.ExecutorService` /
     `java.util.concurrent.Executors` — the worker pool.
   - `java.util.concurrent.ThreadFactory` — daemon worker threads.
   - `java.util.Base64` (Java 8) — payload encode/decode.
   - `java.util.regex.Pattern` — name validation.
   - `ca.weblite.webview.JavascriptFunction`,
     `ca.weblite.webview.AsyncJavascriptFunction` — handler types.
   - `ca.weblite.webview.FunctionDispatcher.FunctionSink` — its own
     nested type.
2. `FunctionDispatcher` does NOT depend on `WebViewNative`,
   `EmbeddedWebView`, `OffscreenWebView`, `WebView`,
   `WebViewComponent`, or any Swing class. (Unlike `EvalDispatcher`,
   it does not even import `javax.swing.SwingUtilities` — handlers run
   on the worker pool, not the EDT, and the resolve hop is a plain
   `eval`.) The `FunctionSink` indirection is the entire decoupling
   mechanism.
3. `JavascriptFunction` depends only on `java.lang`.
   `AsyncJavascriptFunction` depends on
   `java.util.concurrent.CompletableFuture`.

### Layered Architecture
1. **JS layer (page-side):** `FunctionDispatcher.SHIM_JS` plus the
   per-name wrappers run in the page. Responsibility: expose
   `window.<name>(arg)` returning a Promise, allocate a request id,
   stash the `{resolve,reject}` pair, post `<id>|<name>|<arg>`
   (base64) through `window.__webview_fn_call__`, and settle the
   stashed Promise when `window.__webview_fn_resolve__` is invoked.
2. **Native bind / eval layer:** the engine converts the JS call into
   a `WebViewNativeCallback` on the engine UI thread, and converts the
   outbound `eval` into a script run in the page. Out of scope — owned
   by the consuming canvases and the native code at `src_c/`.
3. **Dispatcher layer:** `FunctionDispatcher.dispatch(rawJson)` parses
   the inbound envelope, routes to the handler, runs it off-thread
   (sync) or awaits its future (async), and resolves via the sink.
4. **Handler layer:** the host application's `JavascriptFunction` /
   `AsyncJavascriptFunction` lambdas. Pure Java; out of scope of the
   transport.

## O · Operations

### 1. Construct the dispatcher — FunctionDispatcher constructor
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: build a per-engine hub with its sink and disposal
   label, and start the worker pool.
2. Methods:
   - `public FunctionDispatcher(FunctionSink sink, String disposeLabel)`
     - Logic:
       - `if (sink == null) throw new NullPointerException("sink");`
       - `if (disposeLabel == null) throw new NullPointerException("disposeLabel");`
       - Store `this.sink`, `this.disposeLabel`.
       - `this.handlers = new ConcurrentHashMap<String, AsyncJavascriptFunction>();`
       - `this.worker = Executors.newCachedThreadPool(threadFactory);`
         where `threadFactory` returns daemon threads named
         `"webview-fn-" + disposeLabel + "-" + n`.
       - `disposed` defaults `false`.
3. Constraints: `sink`, `disposeLabel`, `worker` are `final`. The
   worker pool uses daemon threads so it never blocks JVM exit.

### 2. Register a synchronous handler — FunctionDispatcher.registerSync
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: validate and store a synchronous handler, adapting
   it to the async path, and install its page-side wrapper.
2. Methods:
   - `public void registerSync(String name, JavascriptFunction fn)`
     - Logic:
       - `validateName(name);` (Operation 7) and
         `if (fn == null) throw new NullPointerException("fn");`
       - `if (disposed) return;`
       - Adapt: build an `AsyncJavascriptFunction` whose `run(arg)`
         does `CompletableFuture.supplyAsync(() -> { try { return
         fn.run(arg); } catch (RuntimeException|Error e) { throw e; }
         catch (Exception e) { throw new CompletionException(e); } },
         worker)`. (Wrap checked exceptions in `CompletionException`
         so the future completes exceptionally with the original
         cause.)
       - `handlers.put(name, adapted);`
       - `installWrapper(name);` (Operation 6).
3. Constraints: the adapter MUST submit to `worker` so `fn.run` never
   executes on the inbound binding-callback (engine UI) thread.

### 3. Register an async handler — FunctionDispatcher.registerAsync
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: validate and store an async handler, install its
   page-side wrapper.
2. Methods:
   - `public void registerAsync(String name, AsyncJavascriptFunction fn)`
     - Logic:
       - `validateName(name);` and null-check `fn`.
       - `if (disposed) return;`
       - `handlers.put(name, fn);`
       - `installWrapper(name);`
3. Constraints: `fn.run` is invoked on the inbound binding-callback
   thread in `dispatch`; the contract (Javadoc) requires it to return
   promptly with a future and not block.

### 4. Dispatch an inbound call — FunctionDispatcher.dispatch
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: parse the inbound envelope, route to the handler,
   and arrange the resolve.
2. Methods:
   - `public void dispatch(String rawJson)`
     - Logic:
       - `if (rawJson == null) return;`
       - `String b64 = extractFirstArg(rawJson); if (b64 == null) return;`
       - `String rec = decodeBase64Utf8(b64); if (rec == null) return;`
       - `int p1 = rec.indexOf('|'); if (p1 <= 0) return;`
       - `long id; try { id = Long.parseLong(rec.substring(0, p1)); } catch (NumberFormatException e) { return; }`
       - `int p2 = rec.indexOf('|', p1 + 1); if (p2 < 0) return;`
       - `String name = rec.substring(p1 + 1, p2);`
       - `String arg = rec.substring(p2 + 1);` (verbatim; may contain `|`).
       - `AsyncJavascriptFunction h = handlers.get(name);`
       - `if (h == null) { resolve(id, false, "no such function: " + name); return; }`
       - `CompletableFuture<String> fut; try { fut = h.run(arg); } catch (Throwable t) { resolve(id, false, msgOf(t)); return; }`
       - `if (fut == null) { resolve(id, true, ""); return; }`
       - `fut.whenComplete((value, err) -> { if (err != null) resolve(id, false, msgOf(unwrap(err))); else resolve(id, true, value == null ? "" : value); });`
3. Constraints: never throws out of `dispatch` (it runs on the native
   binding callback thread); every path either resolves or silently
   drops malformed input. `unwrap` peels `CompletionException`;
   `msgOf` returns `t.getMessage()` or `t.getClass().getSimpleName()`.

### 5. Canonical JS shim — FunctionDispatcher.SHIM_JS
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: the idempotent base shim installed once per page.
2. Constant `public static final String SHIM_JS`:
   ```js
   (function(){
     if(window.__webview_fn_installed__)return;
     window.__webview_fn_installed__=true;
     var pending={}, seq=0;
     function enc(s){try{return btoa(unescape(encodeURIComponent(s)));}catch(e){return '';}}
     function dec(s){try{return decodeURIComponent(escape(atob(s)));}catch(e){return '';}}
     var post=window.__webview_fn_call__;   // cached before any user JS
     window.__webview_fn_invoke=function(name,arg){
       var id=++seq;
       return new Promise(function(resolve,reject){
         pending[id]={resolve:resolve,reject:reject};
         var sink=post||window.__webview_fn_call__;
         if(typeof sink!=='function'){reject(new Error('webview function channel unavailable'));return;}
         sink(enc(id+'|'+name+'|'+(arg==null?'':String(arg))));
       });
     };
     window.__webview_fn_resolve__=function(b64){
       var rec=dec(b64);
       var p1=rec.indexOf('|'); if(p1<0)return;
       var p2=rec.indexOf('|',p1+1); if(p2<0)return;
       var id=rec.substring(0,p1), ok=rec.substring(p1+1,p2), val=rec.substring(p2+1);
       var e=pending[id]; if(!e)return; delete pending[id];
       if(ok==='1') e.resolve(val); else e.reject(new Error(val));
     };
   })();
   ```
3. Constraints: idempotent (`__webview_fn_installed__`); installed at
   document-start via `addOnBeforeLoad`; caches the inbound binding
   reference; `pending` keys are JS-allocated ids so the page matches
   its own resolvers.

### 6. Install a per-name wrapper — FunctionDispatcher.installWrapper / wrapperFor
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: expose `window.<name>` and make it survive reloads.
2. Methods:
   - `private String wrapperFor(String name)` returns:
     ```js
     (function(){window["<NAME>"]=function(a){return window.__webview_fn_invoke("<NAME>",a);};})();
     ```
     `<NAME>` is substituted with the validated `name` (safe: it
     matches `NAME_PATTERN`, so it contains no quotes or backslashes).
   - `private void installWrapper(String name)`:
     - `String js = wrapperFor(name);`
     - `sink.addOnBeforeLoad(js);` — future navigations.
     - `sink.eval(js);` — current document.
3. Constraints: ordering (`addOnBeforeLoad` then `eval`) does not
   matter for correctness; both are issued so the function works
   immediately and after reloads.

### 7. Validate a name — FunctionDispatcher.validateName
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: reject names that could shadow internal channels or
   break the wrapper.
2. Methods:
   - `private static void validateName(String name)`:
     - `if (name == null) throw new NullPointerException("name");`
     - `if (name.startsWith("__webview_")) throw new IllegalArgumentException("name must not start with __webview_: " + name);`
     - `if (!NAME_PATTERN.matcher(name).matches()) throw new IllegalArgumentException("name must be a valid JS identifier: " + name);`
3. Constraints: `NAME_PATTERN = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");`.

### 8. Resolve back to the page — FunctionDispatcher.resolve
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: deliver the result to the page Promise.
2. Methods:
   - `private void resolve(long id, boolean ok, String payload)`:
     - `String rec = id + "|" + (ok ? "1" : "0") + "|" + (payload == null ? "" : payload);`
     - `String b64 = Base64.getEncoder().encodeToString(utf8(rec));`
     - `sink.eval("window.__webview_fn_resolve__('" + b64 + "')");`
3. Constraints: uses `eval` (non-blocking); base64 is single-quote
   safe so no escaping needed; never blocks the calling thread.

### 9. Dispose — FunctionDispatcher.disposeAll
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: stop accepting work and release the pool.
2. Methods:
   - `public void disposeAll()`:
     - `if (disposed) return;`
     - `disposed = true;`
     - `handlers.clear();`
     - `worker.shutdownNow();`
3. Constraints: idempotent; safe from any thread.

### 10. Base64 helpers — extractFirstArg / decodeBase64Utf8 / utf8
File: `src/ca/weblite/webview/FunctionDispatcher.java`
1. Responsibility: envelope/payload codec, copied verbatim from
   `EvalDispatcher` (`extractFirstArg`, `decodeBase64Utf8`) plus a
   small `private static byte[] utf8(String s)` returning
   `s.getBytes("UTF-8")` (wrapped to swallow the checked
   `UnsupportedEncodingException`, which never fires for UTF-8).

### 11. The functional interfaces — JavascriptFunction / AsyncJavascriptFunction
Files: `src/ca/weblite/webview/JavascriptFunction.java`,
`src/ca/weblite/webview/AsyncJavascriptFunction.java`
1. `JavascriptFunction`: `@FunctionalInterface public interface
   JavascriptFunction { String run(String arg) throws Exception; }`
   with Javadoc stating it runs off the engine UI thread, return value
   resolves the page Promise, thrown exception rejects it.
2. `AsyncJavascriptFunction`: `@FunctionalInterface public interface
   AsyncJavascriptFunction { CompletableFuture<String> run(String
   arg); }` with Javadoc stating `run` is invoked on the binding
   thread and must return promptly with a future; the future's normal
   completion resolves and exceptional completion rejects the page
   Promise.

## N · Norms
- **Visibility / internal marker:** `FunctionDispatcher` Javadoc leads
  with the verbatim `<strong>Internal:</strong> not part of the public
  API surface.` paragraph (as `EvalDispatcher`/`ConsoleDispatcher`).
  The two functional interfaces ARE public API and carry user-facing
  Javadoc.
- **Annotation standards:** `@FunctionalInterface` on
  `JavascriptFunction` and `AsyncJavascriptFunction`. No annotations on
  `FunctionDispatcher` or `FunctionSink` (the latter has two methods,
  so it is deliberately NOT `@FunctionalInterface`).
- **Dependency injection / construction:** `FunctionSink` is
  constructor-injected; consuming wrappers build it as an anonymous
  inner class once and store the dispatcher in a `final` field. No
  setters.
- **Exception handling:**
  - `dispatch(rawJson)` MUST NOT throw — it runs on the native binding
    callback thread. Malformed input is silently dropped (matching
    `EvalDispatcher.dispatch`); a handler that throws synchronously, or
    a future that completes exceptionally, becomes a Promise rejection.
  - Registration methods MAY throw `IllegalArgumentException` /
    `NullPointerException` synchronously for programming errors
    (bad name, null handler) — these are caller bugs, surfaced loudly.
  - The Promise rejection message contains only the Java-side
    exception message; no stack traces or peer pointers.
- **Threading:** synchronous handlers run on the dispatcher's daemon
  worker pool. The outbound resolve uses `eval` (never `evalAsync`,
  never `.get()`), so no dispatcher code blocks the engine UI thread
  or the EDT. This is the deadlock-free contract.
- **Data validation:** ids parsed with `Long.parseLong` (silent drop
  on `NumberFormatException`); ok-flag checked with exact
  `equals("1")`; registered names validated against `NAME_PATTERN` and
  the reserved prefix.
- **Logging:** none, matching the other dispatchers.
- **Java 8 idioms:** no `var`, no `List.of`, no `String.isBlank`, no
  `CompletableFuture.failedFuture`; use anonymous inner classes for
  the `FunctionSink` implementations in consuming wrappers (it has two
  methods anyway). Lambdas are acceptable inside the dispatcher's own
  adapter code.
- **Documentation standards:** `SHIM_JS` carries Javadoc documenting
  the page contract (`await window.<name>(arg)`, string passthrough,
  reject-on-throw) and idempotency. The functional interfaces document
  the threading and error contract.

## S · Safeguards
- **Functional constraints:**
  - A registered function MUST appear as `window.<name>` and return a
    Promise resolving to the handler's returned string (or rejecting
    with its exception message). Verified by `FunctionDispatcherTest`
    against a mock sink that captures the per-name wrapper and the
    resolve `eval`.
  - A synchronous handler MUST run on a thread other than the one that
    called `dispatch`. Verified by a test asserting the handler's
    observed thread differs from the dispatch thread.
  - An unknown function name MUST reject (not hang) the page Promise.
- **Performance constraints:** one `ConcurrentHashMap` lookup per call;
  one worker-pool task per synchronous call; base64 + a few `indexOf`
  on each boundary crossing. No per-call allocation beyond the
  `CompletableFuture` and the wrapped strings.
- **Security constraints:**
  - The reserved channels (`__webview_fn_call__`,
    `window.__webview_fn_resolve__`, and the `__webview_fn_*` window
    members) live under `__webview_` and are protected by the existing
    reserved-prefix guard on `addJavascriptCallback`. The public
    `addJavascriptFunction` additionally rejects reserved-prefix and
    non-identifier names, so a caller can neither shadow the internal
    channel nor inject JS through the per-name wrapper (the name is
    substituted into a JS string/identifier position but is constrained
    to `[A-Za-z_$][A-Za-z0-9_$]*`).
  - The invoke helper caches the inbound binding reference before any
    user JS runs (mirroring `EvalDispatcher`'s defense), so user JS
    reassigning `window.__webview_fn_call__` cannot reroute calls.
  - Rejection messages carry only Java-side text; callers rendering
    them into the DOM are responsible for escaping.
  - The wrapper/resolve scripts run through `eval`; CSPs that forbid
    `eval` reject them wholesale — same constraint as the existing
    fire-and-forget `eval` path, documented not mitigated.
- **Integration constraints:**
  - `FunctionDispatcher` MUST NOT depend on any engine wrapper or Swing
    class; the `FunctionSink` indirection is the decoupling mechanism.
  - The inbound binding callback registered by the consuming surface
    MUST be anchored in that surface's `heap` field (JNI lifecycle
    norm) — this canvas relies on that anchoring; each consuming
    canvas restates the reminder.
- **Business-rule constraints:**
  - Single string argument and string result this iteration (JSON for
    structure). Multi-arg / typed results are future canvases.
  - A page that drops the returned Promise still lets the Java handler
    run to completion; the result is discarded.
- **Exception-handling constraints:**
  - Handler failures surface as page-side Promise rejections; library
    misuse (bad name, null handler) surfaces as synchronous Java
    exceptions; neither is conflated with the other.
- **Technical constraints:** Java 8 source/target (`pom.xml`); no new
  external dependencies; no JNI additions; no native changes. Uses only
  `java.util.*`, `java.util.concurrent.*`, `java.util.regex.*`.
- **Data constraints:** ids are JS-allocated positive integers echoed
  back verbatim; the pipe-separated wire format splits at most twice so
  the final field may contain `|`.
- **API constraints:** `FunctionDispatcher` is `public final`;
  `FunctionSink` is a two-method nested interface (new abstract methods
  are a breaking change — extend via `default` methods only);
  `JavascriptFunction` and `AsyncJavascriptFunction` are
  `@FunctionalInterface` and binary-compatibility-frozen at one
  abstract method each.
