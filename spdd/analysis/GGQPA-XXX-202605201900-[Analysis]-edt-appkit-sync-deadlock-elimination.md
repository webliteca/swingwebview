# SPDD Analysis: Eliminate EDT↔AppKit Sync Deadlock Surface on macOS

## Original Business Requirement

The full story decomposition `[User-story-4]eliminate-edt-appkit-sync-deadlock-on-macos.md` is preserved verbatim in `requirements/[User-story-4]eliminate-edt-appkit-sync-deadlock-on-macos.md` and is incorporated by reference. The headline capability:

> Eliminate every synchronous EDT→AppKit-main bridge in the macOS heavyweight WebView implementation (`src_c/webview_embed.cpp`), closing the class of deadlocks where the EDT and the AppKit main thread mutually wait on each other. Three sync sites must be eliminated:
>
> 1. **Cached first-responder flag** (STORY-004-001): replace `cocoa_is_first_responder`'s per-call EDT→main sync probe with an `std::atomic<bool>` on the `Engine`, kept current by a KVO observer registered on the host `NSWindow.firstResponder` property. Existing swizzled `WKWebView` `becomeFirstResponder` / `resignFirstResponder` hooks are insufficient because focus on inner WebKit content views (e.g. an `<input>` inside the page) does not fire `WKWebView`'s own responder methods.
>
> 2. **Async engine attach with completion listener** (STORY-004-002): `cocoa_create_engine` returns immediately after allocating the C++ `Engine` struct; WKWebView creation, `addSubview:`, and configuration run via `cocoa_run_on_main_async`. New public Java API `WebViewAttachListener` + `EmbeddedWebView.addOnAttachComplete(listener)` reports the deferred outcome on the EDT. Pre-attach operations buffer and replay. `EmbeddedWebView.attach(parent, debug)` stays a synchronous factory at the Java API level.
>
> 3. **Async engine destroy with safe queued-op handling** (STORY-004-003): `cocoa_destroy_engine`'s `removeFromSuperview` and `delete e` move inside `cocoa_run_on_main_async`. A generation counter (or destroyed atomic) on the `Engine` lets queued async ops short-circuit safely. The now-unused `cocoa_run_on_main` sync helper, `WebViewAwtMainBridge` class, and `performWork:` selector are removed. Canvas 6's Norm mandating `performSelectorOnMainThread:modes:` is retired in favour of "no sync EDT→main bridge in `webview_embed.cpp`".
>
> Cross-platform: Windows and Linux native paths do not need bridge changes (they have no analogous EDT↔main-thread dispatch primitive). The Java-level attach-listener API is added on all platforms; on Windows / Linux it fires immediately because attachment there is already synchronous. The reproducing demo `demos/WebViewDeadlockRepro` must run indefinitely without the watchdog firing.

All 24 Acceptance Criteria across STORY-004-001 (7 ACs), STORY-004-002 (10 ACs), and STORY-004-003 (7 ACs), plus the per-story Non-Functional Expectations, are in scope of this analysis.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **`EmbeddedWebView`** (`src/ca/weblite/webview/EmbeddedWebView.java`): low-level Java wrapper around the native engine peer. Public surface includes `attach(Component, boolean) [static factory]`, `setBounds`, `setVisible`, `requestFocus`, `navigate`, `addOnBeforeLoad`, `eval`, `evalAsync`, `addJavascriptCallback`, `dispatch`, `pumpEvents`, `openDevTools`, `executeEditingCommand`, `isNativeFirstResponder`, `setFocusCallback`, `setClickCallback`, `releaseNativeFocus`, `dispose`. Holds a `long peer` (zero after dispose), a `heap: List<Object>` GC-anchor list, a `bindings: Map<String, JavascriptCallback>` map, and a per-instance `EvalDispatcher`. `checkAlive()` throws `IllegalStateException` when `peer == 0L`. `dispose()` already orchestrates a careful drain sequence: `evalDispatcher.disposeAllPending()`, then `setFocusCallback(null)` (try/catch), then `setClickCallback(null)` (try/catch), then `peer = 0L`, then `webview_embed_destroy`.

- **`WebViewHeavyweightComponent`** (`src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`): Swing `JComponent` wrapping `EmbeddedWebView`. Holds a private `embedded: EmbeddedWebView` (null until first paint, null after `dispose`), plus the pre-attach buffer fields `pendingUrl`, `pendingInit`, `pendingBindings`, `debug`. `createPeer()` calls `EmbeddedWebView.attach(canvas, debug)` and replays all pending state. `EmbeddedCanvas.peerAttached` (volatile boolean) flips on first `paint()` and back on `removeNotify`. The `editingShortcutDispatcher` (a `KeyEventDispatcher` registered on `KeyboardFocusManager` in `addNotify`) calls `EmbeddedWebView.isNativeFirstResponder()` for every platform-shortcut + C/V/X/A key press — this is the sync-bridge call that the repro demo wedges on.

- **C++ `Engine` struct** (`src_c/webview_embed.cpp:1548-1586`): per-instance native state. Fields: `webview` (WKWebView id), `manager` (WKUserContentController id), `config`, `debug`, `bindings: std::map<std::string, Binding*>`, `jvm`, `surface_layers`, `host_view`, `host_is_awt`, `focus_callback` (JNI global ref), `click_callback` (JNI global ref). Lives until `cocoa_destroy_engine` frees it.

- **`cocoa_run_on_main`** (`src_c/webview_embed.cpp:1681-1720`): the sync EDT→main bridge. Inlines if already on main; otherwise routes through `[g_awt_main_bridge_target performSelectorOnMainThread:withObject:waitUntilDone:YES modes:g_awt_main_bridge_modes]` with `g_awt_main_bridge_modes` including `AWTRunLoopMode`, `kCFRunLoopDefaultMode`, `NSEventTrackingRunLoopMode`, `NSModalPanelRunLoopMode`. Three callers: `cocoa_is_first_responder` (line 1998), `cocoa_create_engine` (line 2077), `cocoa_destroy_engine` (line 2264). **The sync site this story eliminates.**

- **`cocoa_run_on_main_async`** (`src_c/webview_embed.cpp:1722-1735`): the async counterpart. Inlines if already on main; otherwise uses `dispatch_async_f(dispatch_get_main_queue(), …)`. **The replacement substrate.** The macOS `dispatch_get_main_queue()` is FIFO and serial — blocks fire in enqueue order, one at a time, on the AppKit main thread, never concurrently.

- **`WebViewAwtMainBridge` / `performWork:`** (`src_c/webview_embed.cpp:1626-1668`): the Objective-C class and selector that `cocoa_run_on_main` dispatches through. Allocated and registered exactly once per JVM via `std::call_once`. Becomes dead code once all three sync-bridge sites are eliminated; STORY-004-003 removes it.

- **Swizzled responder hooks** (`src_c/webview_embed.cpp:1816-1860`): `swizzled_become_first_responder` and `swizzled_resign_first_responder` are installed via `method_setImplementation` on `WKWebView` once per JVM. They fire whenever the `WKWebView` itself becomes/resigns first responder, look up the engine in `g_webview_map`, and call `fire_focus_callback`. **Key limitation**: they do NOT fire when focus lands on or leaves an inner WebKit content view (`<input>`, etc.) — those transitions don't pass through `WKWebView`'s own responder methods. This is why a KVO observer on `NSWindow.firstResponder` is needed for the cache, not the existing swizzle.

- **`g_webview_map`** (`src_c/webview_embed.cpp:1593-1594`): process-global `std::map<id, Engine*>` keyed by `WKWebView` id. Guarded by `g_webview_map_mutex`. Populated in `cocoa_create_engine` after `WKWebView` alloc; cleared in `cocoa_destroy_engine` before the `cocoa_run_on_main` teardown block. Used by the swizzled responder/mouse hooks (process-wide class swizzle; the map filters to our `WKWebView`s).

- **`cocoa_is_first_responder`** (`src_c/webview_embed.cpp:1995-2022`): the sync responder probe. Walks the responder chain from `[window firstResponder]` upward through `superview` looking for `e->webview`. Returns 1 if found, 0 otherwise. **The sync site eliminated by STORY-004-001.** The walk logic itself (lines 2004-2019) is the right shape — it correctly handles inner content views by walking up — and is reusable inside the KVO callback.

- **`cocoa_create_engine`** (`src_c/webview_embed.cpp:2052-2224`): synchronously allocates the `Engine`, takes a JAWT lock to resolve `surface_layers`, then runs a `cocoa_run_on_main` block that installs swizzles, allocates `WKWebView`, registers in `g_webview_map`, finds a host NSView, calls `[host setWantsLayer:YES]` + `[host addSubview:e->webview]`, installs the script-message-handler delegate, and (if `debug`) sets `developerExtrasEnabled` / `setInspectable:`. The `ok` flag inside the block flips to true on success; outside the block, `!ok → delete e; return nullptr`. **The sync site eliminated by STORY-004-002.**

- **`cocoa_destroy_engine`** (`src_c/webview_embed.cpp:2226-2305`): drops the `WKWebView` from `g_webview_map`, releases `focus_callback` and `click_callback` JNI global refs on the calling thread, runs a `cocoa_run_on_main` block that does `removeFromSuperview`, releases `host_view` / `surface_layers` / `webview` / `config`, then walks `e->bindings` on the calling thread releasing global refs, and finally `delete e`. **The sync site eliminated by STORY-004-003.**

- **`cocoa_navigate` / `cocoa_init_script` / `cocoa_eval` / `cocoa_set_visible` / `cocoa_request_focus` / `cocoa_set_bounds` / `cocoa_execute_editing_command`** (`src_c/webview_embed.cpp:2313-2443`): all already implemented as `cocoa_run_on_main_async` lambdas. Each null-checks `e->webview` or `e->manager` (or `e->host_view`) at the top of the lambda and returns silently if cleared. **The existing async pattern that pre-attach buffering will naturally piggyback on.**

- **`cocoa_bind`** (`src_c/webview_embed.cpp:2383`): currently runs synchronously on the calling thread (`e->bindings[b->name] = b;`). This is the one non-async per-engine op. Inspected by the script-message-handler delegate on the main thread when JS posts a message. Today it works because attach is sync (main thread is the only reader; the EDT writes after attach completes); under async attach it becomes a cross-thread write that must be made safe.

- **`EvalDispatcher`** (`src/ca/weblite/webview/EvalDispatcher.java`, canvas-10): per-engine in-flight `id → CompletableFuture<String>` map with `disposeAllPending()` drain. Owned by `EmbeddedWebView`. `EmbeddedWebView.dispose()` already drains it before `webview_embed_destroy`. **Already async-safe**: new `evalAsync` calls after `peer == 0L` return already-failed futures without touching the dispatcher. Provides the AC4 (story 003) behaviour for free.

- **Pre-paint buffer pattern in `WebViewHeavyweightComponent`** (`src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java:86-103, 111-178, 460-466`): the established convention for "configuration set before the peer exists, replayed when it does". `setUrl`, `addOnBeforeLoad`, `addJavascriptCallback`, `setDebug` all write to `pendingUrl` / `pendingInit` / `pendingBindings` and only delegate to `embedded` when it's non-null. `createPeer()` replays in registration order. **The blueprint for any pre-attach buffer; STORY-004-002 generalises it down to `EmbeddedWebView` level (or, see Strategic Approach below, leverages the existing `dispatch_get_main_queue` FIFO ordering to avoid an explicit Java-level buffer entirely).**

- **Canvas 6 Norm: `cocoa_run_on_main` MUST use `performSelectorOnMainThread:withObject:waitUntilDone:YES modes:` with `AWTRunLoopMode`** (`spdd/prompt/6-*.md` Norms section, lines 1700–1735). The v1.0.5 fix from PR #30 that this work supersedes. **Explicitly retired by STORY-004-003** and replaced by a Norm forbidding any sync EDT→main bridge in `webview_embed.cpp`.

### New Concepts Required

- **`WebViewAttachListener`** (new Java functional interface, `src/ca/weblite/webview/WebViewAttachListener.java`): single-callback observer with `onAttached(EmbeddedWebView)` and `onAttachFailed(EmbeddedWebView, Throwable cause)`. Documented to fire on the Swing EDT. Listeners can be registered before or after attach resolves; resolved-state registration fires the callback on the next EDT tick (never on the calling thread, to prevent re-entrancy bugs in the registering code).

- **Attach state machine on `EmbeddedWebView`**: three-state enum (`PENDING`, `ATTACHED`, `FAILED`) exposed via a getter, plus an internal `Throwable attachFailure` field set on FAILED transition. State transitions are EDT-only — the macOS native side calls back into Java on the EDT to drive transitions, mirroring the existing `WebViewFocusCallback` invokeLater pattern.

- **`is_first_responder: std::atomic<bool>`** on the C++ `Engine` struct (STORY-004-001). Written by the KVO observer callback running on the AppKit main thread; read by `cocoa_is_first_responder` from any thread (typically the EDT). Atomic with default memory_order is sufficient — a stale read by at most one event-loop tick is acceptable, and a single `bool` field admits no torn reads.

- **KVO observer on `NSWindow.firstResponder`** (STORY-004-001). Registered when the WKWebView gains a non-nil window (deferred from `addSubview:` because the window may not be set immediately) and unregistered before the engine is destroyed. Fires on the AppKit main thread. Each firing walks the responder chain (same logic as the current `cocoa_is_first_responder` body) and updates the atomic. **Lifecycle is shared with the engine**, so STORY-004-003 (async destroy) must include observer unregistration in the destroy lambda before any view release.

- **Generation counter (or destroyed atomic) on the C++ `Engine`** (STORY-004-003). `std::atomic<uint64_t>` incremented on destroy, captured by every async lambda at enqueue time, compared at fire time. Used as a belt-and-suspenders short-circuit for late async ops on a destroyed engine. Strictly speaking, FIFO dispatch-queue ordering + EDT-only enqueueing already guarantees no late op fires after destroy — but the counter defends against (a) destroy-from-non-EDT (hypothetical), (b) future code changes that might violate the invariant, and (c) provides a clean signalling path for STORY-004-003 AC4 (evalAsync future fails with "WebView disposed" message from inside the late lambda).

- **Pre-attach buffer for `addJavascriptCallback`** (STORY-004-002, conditional). If we choose to expose `addJavascriptCallback` as a pre-attach operation (and we should, for symmetry with `WebViewHeavyweightComponent`'s `pendingBindings`), `cocoa_bind` must either (a) become an async-on-main lambda (using the FIFO queue to order itself after the attach lambda that creates `e->bindings`), or (b) the Java-side `EmbeddedWebView` must buffer the binding name+callback pair and replay it on the `onAttached` transition. See Strategic Approach for the recommendation.

### Key Business Rules

- **Editing-shortcut routing must remain user-perceptibly identical**: the Cmd-C / Cmd-V / Cmd-X / Cmd-A behaviour described in Canvas 6's Requirements section (the "interacting with the WebView" gating logic in `handleEditingShortcut`) MUST be preserved. The change is purely the mechanism by which `isNativeFirstResponder` returns its answer — sync probe → cached atomic. Edge cases: focus on inner content views, focus on a Swing sibling, focus transferred mid-keystroke.

- **`EmbeddedWebView.attach(parent, debug)` is a synchronous factory at the Java API level on all platforms**: no caller should be required to await a future or pass a listener inline. The PENDING state is the user-observable consequence of macOS-side async setup; Windows / Linux callers see ATTACHED immediately.

- **Pre-attach operations are buffered, not rejected**: a caller who does `EmbeddedWebView ewv = EmbeddedWebView.attach(...); ewv.setUrl("..."); ewv.addBinding(...);` on a single EDT tick MUST get the same end-state as if attach had been fully synchronous. Order is preserved.

- **Pre-attach failure surfaces only through `WebViewAttachListener.onAttachFailed`** (or, if no listener is registered, is captured in the `attachFailure` field and reported to any subsequently-registered listener). It does NOT throw from any post-`attach()` method call — those methods buffer or no-op until the FAILED transition is observed. This avoids the trap where every post-`attach` call site needs try/catch.

- **`dispose()` is non-blocking and idempotent**: returns within 50 ms on macOS (AC1 of story 003) and is safe to call repeatedly. Late ops on a disposed engine are no-ops (navigate) or already-failed futures (evalAsync). Existing dispose() drain order in Java (`evalDispatcher.disposeAllPending` → clear callbacks → `peer = 0L` → JNI destroy) is preserved and remains correct under async destroy.

- **Canvas 6's existing Norms unrelated to the sync bridge must be preserved**: the console-bridge ordering rule, the editing-shortcut consumption rule, the swizzle-once-per-JVM rule, the `webview_embed_open_devtools` non-blocking rule, etc., all stay in force. Only the specific `cocoa_run_on_main` / `performSelectorOnMainThread:modes:` Norm is retired.

- **Listener callbacks never fire on the calling thread**: `addOnAttachComplete` registered after resolution fires on the next EDT tick (`SwingUtilities.invokeLater`), never inline. This prevents re-entrancy when the listener body itself calls `addOnAttachComplete` or other `EmbeddedWebView` methods.

## Strategic Approach

### Solution Direction

**Three coordinated changes to `src_c/webview_embed.cpp` and the macOS-facing Java surface, sequenced (in any order, but recommended 001 → 002 → 003 for user impact) to fully eliminate the sync EDT→main bridge from the heavyweight WebView path.** The three sub-stories share enough infrastructure (KVO observer lifecycle spans 001 + 003; generation counter spans 002 + 003) that a single analysis pass is appropriate even though each ships independently.

**STORY-004-001 — Cached responder via KVO**:
- Add `is_first_responder: std::atomic<bool>` to the `Engine` struct.
- On the existing attach path, when the `WKWebView` is added to a host view and the host view has a non-nil window, register a KVO observer on `[window firstResponder]`. If the window is nil at attach time (transient AppKit state), defer registration: observe the WKWebView's `viewDidMoveToWindow` (or a one-shot KVO on the WKWebView's `window` keypath) and register the firstResponder observer on the first non-nil window.
- KVO callback re-walks the responder chain using the existing `cocoa_is_first_responder` logic and writes the atomic.
- Refactor `cocoa_is_first_responder` to read the atomic only — no main-thread hop. Keep the body's existing fallback (peer-not-yet-set returns 0) intact.
- On destroy, unregister the observer before any view release. This is STORY-004-001 scope; the destroy path itself is still sync in 001 (STORY-004-003 makes it async, but observer unregistration is symmetric in both modes).

**STORY-004-002 — Async attach with FIFO-queue replay**:
- Split `cocoa_create_engine` into a synchronous prologue (allocate `Engine`, take the JAWT lock long enough to retain `surface_layers`, attach the `EvalDispatcher`-related state) and an async-on-main epilogue (`cocoa_run_on_main_async`) that does the WKWebView allocation, host-view discovery, `addSubview:`, configuration, KVO observer registration (from 001), and final state write.
- The async epilogue captures a JNI global ref to a callback object (provided by the Java side via the new attach-listener wiring). On success or failure, the epilogue posts the result back to the EDT via `SwingUtilities.invokeLater` (or, equivalently, uses `webview_embed_dispatch` style marshalling).
- **Pre-attach op ordering**: rely on `dispatch_get_main_queue()` FIFO serial ordering. The attach epilogue is the first block enqueued. Subsequent `cocoa_navigate` / `cocoa_eval` / `cocoa_init_script` / `cocoa_set_bounds` / `cocoa_request_focus` / `cocoa_set_visible` / `cocoa_execute_editing_command` calls each enqueue their own async block; they fire in registration order, after the attach block. Each block already null-checks `e->webview` / `e->manager` and silently no-ops if the field is null — so an op enqueued before the attach block creates the WKWebView simply finds it ready by the time it fires. **No Java-level buffer is needed for these ops.**
- **`cocoa_bind` is the exception**: it currently mutates `e->bindings` synchronously on the calling thread (the EDT). Under async attach, the script-message-handler delegate (running on main) might read `e->bindings` before the calling thread's write is visible. Convert `cocoa_bind` to enqueue an async-on-main lambda that does the map write, OR keep it sync but add a `std::mutex` around `e->bindings`. Recommendation: convert to async — it preserves the "every per-engine native op is async" invariant and trivially serialises against the script-message-handler reads (which also run on main).
- **Java-side attach-listener wiring**: add `addOnAttachComplete(WebViewAttachListener)` to `EmbeddedWebView`. Internally, `attach()` returns an instance in PENDING state with a JNI global ref to a new callback object. The native attach epilogue calls back into Java with success/failure; the Java side flips the state and fires any registered listeners on the EDT via `SwingUtilities.invokeLater`.
- **Cross-platform parity**: on Windows / Linux, `webview_embed_create` is synchronous and returns a non-zero peer. The Java side detects this (peer != 0L at constructor entry) and immediately transitions to ATTACHED; any subsequently-registered listener fires on the next EDT tick.
- **Synchronous failure path**: if the C++ Engine struct allocation itself fails (out of memory, JAWT lock failure), `attach()` throws synchronously as today. Only the AppKit-side phase is moved async. This matches AC9 of story 002.

**STORY-004-003 — Async destroy with generation guard**:
- Add `generation: std::atomic<uint64_t>` (or `destroyed: std::atomic<bool>`) to the `Engine` struct.
- Move the existing `cocoa_run_on_main` block in `cocoa_destroy_engine` to `cocoa_run_on_main_async`. The destroy lambda runs `removeFromSuperview`, releases retained AppKit objects, unregisters the KVO observer (from 001), walks `e->bindings` releasing global refs, increments the generation counter (or sets `destroyed = true`), and finally `delete e`.
- Every other async-on-main lambda captures the generation at enqueue time and compares at fire time. On mismatch (engine destroyed), short-circuit cleanly: navigate / eval / setBounds / setVisible / executeEditingCommand / etc. return without touching the engine. For `cocoa_eval`, on detection inside the late lambda, surface the failure back to the Java-side `EvalDispatcher` so the corresponding `CompletableFuture` completes exceptionally with "WebView disposed". (Note: in practice, the `EvalDispatcher` is already drained by `EmbeddedWebView.dispose()` BEFORE `webview_embed_destroy` is called, so this code path is exercised only in pathological orderings; it remains the right defensive design.)
- The pre-destroy Java-side cleanup in `cocoa_destroy_engine` (focus_callback / click_callback `DeleteGlobalRef`) happens BEFORE the async destroy lambda is enqueued, on the calling thread (which holds the JNIEnv). This is preserved — the calling thread is the EDT, and these refs must be released before the swizzle hooks could fire on the now-detached `WKWebView`.
- Once all three sync sites are eliminated, `cocoa_run_on_main`, `WebViewAwtMainBridge`, and `performWork:` have no callers. Delete them. Verify by grep (story 003 AC5).
- Update Canvas 6's Norms section to retire the `performSelectorOnMainThread:modes:` mandate and add the new prohibition.

### Key Design Decisions

- **Decision: KVO on `NSWindow.firstResponder` vs. swizzle `NSWindow.firstResponder` setter.** KVO is the documented, supported AppKit pattern; swizzle is intrusive and class-wide. The cost of KVO is a single `addObserver:forKeyPath:options:context:` at attach and a `removeObserver:forKeyPath:` at destroy, plus a per-focus-change callback that walks one responder chain. → **KVO chosen.** Rationale: smaller footprint, no risk of conflicting with another library that observes the same key path, and documented forward-compatibility across macOS versions.

- **Decision: Java-level buffer vs. native FIFO-queue replay for pre-attach ops.** Java-level buffer (mirroring `WebViewHeavyweightComponent`'s `pendingUrl` etc.) is explicit but duplicative — `WebViewHeavyweightComponent` already does it at one level up. Native FIFO-queue replay is implicit but leverages an invariant we already depend on (every macOS per-engine op is already an async-on-main block; `dispatch_get_main_queue()` is documented as serial FIFO). → **Native FIFO-queue replay chosen for most ops.** The one exception is `cocoa_bind`, which is currently sync; converting it to async makes the whole picture uniform. **Trade-off**: if a caller observes attach succeed (via the listener) and then immediately reads back state (e.g. eval that depends on a binding being installed), they will see the binding ready because the bind block was enqueued first. The listener fires on the EDT *after* the attach block completes on main, so by the time user code runs in onAttached, all pre-attach blocks have already fired.

- **Decision: Generation counter vs. destroyed flag.** A `std::atomic<bool> destroyed` is simpler; a `std::atomic<uint64_t> generation` is more powerful (lets us tell "is this op still relevant" vs. "did we destroy and recreate"). For this library, engines are never reused; once destroyed, they're gone. → **Destroyed flag is sufficient.** Rationale: simpler. **Trade-off**: if we ever add an engine-reset operation in the future, we'll need the counter. Defer until that requirement exists.

- **Decision: attach() failure error reporting — exception vs. listener.** The story specifies AC9 of 002: synchronous throw only on C++ allocation failure (the rare path). Async AppKit-side failure goes through the listener. → **Two-tier failure reporting accepted.** Rationale: synchronous throw remains for the cases existing callers handle today (out-of-memory, bad parent); the new listener path covers the new failure surface (WKWebView allocation, host NSView discovery) that did not previously exist as a distinct failure mode. **Trade-off**: callers who don't register a listener will silently get a FAILED-state engine; document explicitly and make `getAttachState()` queryable.

- **Decision: dispose() return semantics — true async (fire-and-forget) vs. await-and-return.** Today, `dispose()` returns after the sync destroy block completes on main. Under async destroy, `dispose()` enqueues the destroy lambda and returns immediately. There's no observable difference for callers because Java-side state (`peer = 0L`, evalDispatcher drained, callbacks cleared) is set synchronously before the native call returns. → **True async (fire-and-forget).** Rationale: it's the whole point of the story. **Trade-off**: callers who relied on "after dispose() returns, the WKWebView is fully detached from the view hierarchy" will see the detachment happen up to one main-loop tick later. No current caller relies on this; the demos and `WebViewHeavyweightComponent` don't observe AppKit state from Java after dispose.

- **Decision: pre-attach `setUrl` / `eval` / etc. semantics — buffer-then-replay vs. queue-onto-main-immediately.** As above, native queue-onto-main-immediately works because `dispatch_get_main_queue()` is FIFO serial. **No special-case handling needed in Java**; the existing methods on `EmbeddedWebView` already call straight through to JNI without buffering. **However**, `EmbeddedWebView.checkAlive()` throws when `peer == 0L` — and during PENDING the peer is non-zero (the Engine struct exists). So `checkAlive()` continues to work correctly. **Trade-off**: caller cannot distinguish PENDING from ATTACHED through normal API calls (they all succeed). This is the desired behaviour — the listener is the explicit signal.

- **Decision: editing-shortcut dispatcher continues to call `isNativeFirstResponder` directly vs. push the responder state into Swing.** We could mirror the responder state into a Swing-side `boolean` and have the editing-shortcut dispatcher read that. But that requires an EDT hop on every responder change, which we're trying to avoid. → **Keep the direct call.** The call now reads an atomic, not a sync bridge. The dispatcher's gating logic is unchanged.

### Alternatives Considered

- **Alternative: keep sync bridges but install AWTRunLoopMode-aware locks (the v1.0.5 fix extended).** The current v1.0.5 fix already does this for the inverse-ordering deadlock. But it does not close the cycle where the EDT enters our sync bridge while AppKit-main is parked in `invokeAndWait` waiting for the EDT — adding more modes cannot help because the EDT itself isn't running an event loop. **Rejected** — fundamentally unsolvable with sync bridges.

- **Alternative: timeout-based sync bridge with degraded fallback.** Replace `waitUntilDone:YES` with a manual condition-variable wait + timeout. On timeout, log + degrade (return last-cached value, no-op the op, etc.). **Rejected** — turns a hang into a diagnosable error, but the diagnosable error is still observable to users (Cmd-C "doesn't work for ~5 seconds occasionally") and the fundamental class of bug is not closed. Acceptable as a defense-in-depth layered on top of the async fix, but not as the primary fix.

- **Alternative: move `addSubview:` off the AX-triggering path by attaching only the layer, not the view.** The macOS implementation already has a layer-only fallback path (`src_c/webview_embed.cpp:2166-2176`) when no host NSView is found. We could prefer that path universally, side-stepping AX. **Rejected** — the layer-only fallback is documented as "won't render WKWebView content"; it's a dead-end, not a viable rendering mode. The async fix is the right shape.

- **Alternative: split the C++ Engine struct into "shell" and "peer" — Java holds the shell pointer (eternal until dispose), peer is a separately-managed handle.** Would let the peer be safely destroyed async without touching the shell. **Rejected** — adds complexity without benefit over the generation-counter approach, which achieves the same UAF safety with one atomic.

- **Alternative: separate-thread main pump that drives the WKWebView on a non-AppKit thread.** WKWebView is documented to require the AppKit main thread; cannot run elsewhere. **Rejected** — not technically possible.

- **Alternative: introduce a Java-level pre-attach buffer in `EmbeddedWebView`** (mirroring `WebViewHeavyweightComponent`'s `pendingUrl` etc.). Explicit, easier to reason about, but duplicates the FIFO-queue ordering work already provided by `dispatch_get_main_queue()`. **Rejected as the primary mechanism**, but kept as a fallback if testing reveals queue-ordering edge cases. The `cocoa_bind` async conversion is required regardless.

## Risk & Gap Analysis

### Requirement Ambiguities

- **KVO registration timing**: the story says "register a KVO observer on the host NSWindow's firstResponder". At the moment of `addSubview:`, the WKWebView is in the view hierarchy but the host view's `window` property may transiently be nil (AppKit policy: a view's `window` resolves when the host window is fully constructed and the view is part of its content view's hierarchy). The story implicitly assumes the observer can always be registered immediately. **Clarification**: register lazily on the first `viewDidMoveToWindow:` (or via a one-shot KVO on the WKWebView's own `window` keypath) so we tolerate the transient-nil case. The actual responder chain check still gates on `[e->webview window]` being non-nil — same as the current sync probe.

- **`addOnAttachComplete` re-fire semantics**: the story says listeners registered after resolution fire "immediately" — interpreted as "on the next EDT tick via `invokeLater`", not "inline on the calling thread". This is implied by AC6 of story 002 ("fires on the next EDT event-loop tick"). **Confirmed via AC; documented in Key Business Rules above.**

- **Multiple listeners per attach**: the story uses `addOnAttachComplete` (singular subject, additive verb) implying multiple listeners are supported. **Clarification**: the implementation maintains a `List<WebViewAttachListener>` and fires all of them in registration order. No `removeOnAttachComplete` is required for v1 of this work.

- **Cross-story interaction — story 001 alone leaves attach sync**: if STORY-004-001 ships first (recommended for user impact), `cocoa_create_engine` is still sync. The KVO observer registration happens inside the sync attach block, fine. But the demo (AC6 of 001) still has a create-time AX deadlock surface — it's not a regression, it's that 001 only closes one deadlock. **Clarification**: AC6 of 001 says "runs cleanly for at least 60 seconds" of the Cmd-C loop; this targets the Cmd-C path, not the create path. The demo does not currently exercise creating-during-AX, so AC6 is achievable with just 001. **The demo extension in story 003 AC6 (5-minute attach→focus→Cmd-C→dispose loop) is the test that requires all three.**

- **`WebViewAttachListener.onAttachFailed` `cause` field**: the story says "actionable message identifying the failure step" (e.g. "WKWebView allocation failed" / "host NSView not found"). Implementation question: is the cause a Java exception type with structured fields, or a generic `Throwable` with message? **Recommendation**: a new `WebViewAttachException` extends `IllegalStateException` (matching the existing `attach()` throw type) with a message and (optionally) a fail-stage enum. Defer to canvas-generation phase.

- **`getAttachState()` exposure**: not explicitly listed in the story but implied by AC10 ("...attach state transitions to FAILED"). **Recommendation**: add `AttachState getAttachState()` getter on `EmbeddedWebView` returning the enum. Trivial and useful for tests.

### Edge Cases

- **`addBinding` racing with first JS message**: under async attach, a caller does `attach(); addBinding("foo", cb); setUrl("page-that-calls-foo")`. The `cocoa_bind` async lambda inserts `b` into `e->bindings`; the navigate lambda starts loading the page. If the page calls `foo()` before the bind lambda fires, the script-message-handler looks up `foo` in `e->bindings`, finds nothing, drops the message silently. **Mitigation**: FIFO ordering of the main queue ensures the bind lambda fires before the navigate lambda. But what about the time between navigate enqueue and the page actually loading and calling JS? That's wall-clock time, much longer than the dispatch-queue delta — by the time the page is loading and running its scripts, all enqueued async blocks have fired. **Risk is bounded by the same invariant as today's sync attach; no new edge case.**

- **`dispose()` during pending attach**: a caller does `attach(); dispose();` on the same EDT tick. `dispose()` enqueues a destroy lambda; the attach lambda is already enqueued before it. FIFO ordering means attach completes, then destroy fires. The listener (if registered) fires `onAttached` first, then the engine is destroyed. From the listener's perspective, the engine is briefly valid then gone. **Mitigation**: listener implementations must check the engine's attach state or use `evalAsync` (which is dispose-safe) rather than sync `eval` (which would `checkAlive` and throw). Document the contract.

- **`dispose()` before attach lambda fires + no listener registered**: same as above but no listener. attach lambda fires, internal state goes to ATTACHED, destroy lambda fires, engine is freed. No observer of the brief ATTACHED state. No UAF (destroy lambda is last). **Edge case OK.**

- **KVO observer + window change**: the WKWebView's host window can change at runtime (e.g. user drags the JFrame to a different display, or a re-parenting operation). The KVO observer is registered on a specific NSWindow's firstResponder property; if the WKWebView's `window` changes, the observer must move to the new window. **Mitigation**: observe the WKWebView's own `window` keypath; on change, unregister from the old window's firstResponder and register on the new one. Recompute the atomic immediately after the move.

- **WKWebView never gets a window (degenerate case)**: layer-only fallback path. `cocoa_is_first_responder` already returns 0 in that case (because `e->webview` has no window → no firstResponder → walk never matches). Cache is initialised to false and stays false. **Edge case OK.**

- **JNI thread for the attach-completion callback**: the async epilogue runs on the AppKit main thread. To invoke a Java callback there, it must attach the current thread to the JVM (`AttachCurrentThread`) and detach after. The existing `fire_focus_callback` pattern in `webview_embed.cpp:1793-1814` does exactly this. **Reuse that pattern.**

- **Listener exception propagation**: if a user-supplied `WebViewAttachListener.onAttached(...)` throws, what happens? Currently, the `editingShortcutDispatcher` and similar EDT callbacks let exceptions propagate to AWT's uncaught exception handler. **Recommendation**: same. Document that listener implementations should catch their own exceptions or accept the AWT default behaviour.

- **Engine destroyed during `cocoa_is_first_responder` read**: the read is `return e->is_first_responder.load();`. If `e` itself is freed concurrently, this is a UAF. **Mitigation**: by contract, `EmbeddedWebView.isNativeFirstResponder` returns false when `peer == 0L` without making the JNI call (see `EmbeddedWebView.java:301`). Java-side dispose sets `peer = 0L` before the native destroy. So `isNativeFirstResponder` reads can never race with destroy from a single-EDT-thread perspective. **Edge case OK.**

- **Test hook for AC9 (synchronous C++ allocation failure)**: simulating `new Engine()` failure is hard. **Recommendation**: add a `WEBVIEW_TEST_FAIL_ALLOC=1` env-var hook in cocoa_create_engine prologue (returns nullptr from the prologue). Defer the exact mechanism to canvas-generation phase.

- **macOS version compatibility for KVO on `firstResponder`**: `NSWindow.firstResponder` is observable via KVO on macOS 10.7+ (it's a documented KVO-compliant property). The library targets macOS 10.13+ (per the README), so this is well within bounds. **Edge case OK.**

### Technical Risks

- **Risk: KVO observer + AppKit reentrancy.** KVO callbacks can fire from inside AppKit's responder-change machinery. Walking the responder chain inside the callback is safe (read-only), but the atomic write may need a memory barrier if other CPUs read it without one. **Mitigation**: `std::atomic<bool>` with default memory_order is sequentially consistent — sufficient for our needs.

- **Risk: dispatch queue ordering assumed serial.** `dispatch_get_main_queue()` is documented as serial FIFO; this is the foundation of the no-Java-buffer recommendation. **Mitigation**: this is documented Apple behaviour, not an inference. If it ever changes, the dispatch queue is no longer the macOS main queue and many other things break first.

- **Risk: `addOnBeforeLoad` (init script) ordering vs. attach.** Today's `cocoa_init_script` enqueues an async block; under async attach, the script is added to `e->manager` after the manager is created in the attach lambda. The init script is then injected by `WKWebView` at document-start for every subsequent navigation. If the navigate enqueues a load before the init script is added: race. **Mitigation**: FIFO queue order — addOnBeforeLoad and navigate from the same caller go in registration order, so the init script is added before the navigate fires. From WKWebView's perspective: init scripts added to the controller before the navigation request reaches WebKit are honoured. ✓

- **Risk: `cocoa_bind` async conversion introduces a window where JS messages can arrive before the binding is registered.** As above, FIFO ordering means the bind lambda fires before any navigate-triggered page load that could call the binding. But the page may also be loaded BEFORE attach completes (rare: if the caller does `attach(); setUrl(url); addBinding(name, cb);`). In that case, the navigate lambda fires after the attach lambda but before the bind lambda — the page could call the binding before it's registered. **Mitigation**: this is a caller-ordering issue identical to today's sync attach (where `embedded.addJavascriptCallback` after `embedded.navigate` has the same race). The `WebViewHeavyweightComponent` code conventionally registers bindings before calling navigate (`createPeer()` does exactly this: `pendingBindings` is iterated before `pendingUrl` is navigated). Documented convention; preserved.

- **Risk: removing `cocoa_run_on_main` sync helper might break a non-obvious caller.** The grep at story 003 AC5 should catch all production callers. Demos and test code might still reference it. **Mitigation**: grep in story 003 covers `src/`, `src_c/`, `windows/`, and `demos/`. Update or remove demo references.

- **Risk: Canvas norm retirement may invalidate other documents.** Canvas 6's Norm currently mandates the `performSelectorOnMainThread:modes:` mechanism. Other canvases may reference it (e.g. discussions of how to safely talk to AppKit from JNI). **Mitigation**: grep `spdd/prompt/` for `performSelectorOnMainThread`, `AWTRunLoopMode`, `WebViewAwtMainBridge`. Update or cross-reference as needed during `/spdd-prompt-update`.

- **Risk: cross-platform Java API change for `WebViewAttachListener` affects Windows / Linux consumers.** Adding a public interface and a new method (`addOnAttachComplete`) is API addition, not API breakage. Existing callers ignore it. **Mitigation**: ensure the Windows / Linux implementations of `EmbeddedWebView` (currently a single class with platform-aware JNI calls) correctly transition to ATTACHED at construction and fire the listener on next EDT tick. Single Java class, single code path — low risk.

- **Risk: testing the deadlock fix on CI.** The repro demo requires GUI interaction. **Mitigation**: the existing demo at `demos/WebViewDeadlockRepro` is deterministic (uses synthetic Cmd-C and a setInterval-driven JS callback). Extend it to terminate cleanly after N successful loop iterations and use it as a smoke test. ASan can run locally for story 003 AC6.

- **Risk: pre-existing `WebViewHeavyweightComponent.handleEditingShortcut` calls `isNativeFirstResponder` from inside the KeyEventDispatcher.** Today the call is sync and fast under non-deadlock conditions. After the cache, it's a load of an atomic — even faster. No behavioural risk.

- **Risk: `EmbeddedWebView.dispose()` is called from a non-EDT thread.** Defensive programming: today's dispose flips `peer = 0L` and calls `webview_embed_destroy`. Both work from any thread. Under async destroy, the same continues to work — JNI thread for the native call doesn't matter because the destroy lambda runs on main. **Edge case OK.**

### Acceptance Criteria Coverage

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 001-AC1 | Cmd-C inside WebView copies to clipboard | Yes | Existing `executeEditingCommand` path unchanged; only the responder probe changes |
| 001-AC2 | Cmd-V inside WebView pastes | Yes | Same as AC1 |
| 001-AC3 | Cmd-C with focus outside WebView falls through to Swing | Yes | `handleEditingShortcut` already gates on `isNativeFirstResponder`; cached value preserves semantics |
| 001-AC4 | Focus on inner DOM element reported as WebView-is-responder | Yes | KVO callback walks the responder chain (same logic as today's sync probe); inner content views are visited |
| 001-AC5 | Cache reflects focus transfer out within one event-loop tick | Yes | KVO fires synchronously inside AppKit's `setFirstResponder:` call; cache updated before any subsequent EDT read |
| 001-AC6 | WebViewDeadlockRepro runs cleanly for 60 s | Yes | Repro demo wedges specifically on the `isNativeFirstResponder` sync probe; cache eliminates that path |
| 001-AC7 | KVO observer released cleanly on engine destroy | Yes | Symmetric registration / unregistration in attach / destroy. Need to handle window-change case (see Edge Cases) |
| 002-AC1 | attach() returns within 50 ms under AX | Yes | Async epilogue returns immediately after `new Engine()` |
| 002-AC2 | Listener fires on EDT on success | Yes | Native epilogue marshals to EDT via JNI callback + invokeLater |
| 002-AC3 | Listener fires on EDT on failure | Yes | Failure path symmetric with success path |
| 002-AC4 | Pre-attach setUrl replayed | Yes | FIFO queue ordering; no Java buffer needed |
| 002-AC5 | Pre-attach addBinding replayed and callable from JS | Yes | Requires `cocoa_bind` async conversion (see Strategic Approach) |
| 002-AC6 | Late listener registration fires immediately | Yes | `SwingUtilities.invokeLater` from resolved state |
| 002-AC7 | WebViewHeavyweightComponent's pre-paint buffer still works | Yes | The component's buffer operates above `EmbeddedWebView`'s; new state machine is transparent to it |
| 002-AC8 | Windows / Linux attach is synchronous | Yes | No native change on those platforms; constructor immediately transitions to ATTACHED |
| 002-AC9 | attach() throws synchronously on C++ allocation failure | Yes | Sync prologue retains existing throw paths; only async epilogue is new |
| 002-AC10 | Attach failure does not leak C++ Engine struct | Yes | Async epilogue must `delete e` on failure; symmetric with existing sync `!ok → delete e` path |
| 003-AC1 | dispose() returns within 50 ms | Yes | Async destroy enqueues and returns immediately |
| 003-AC2 | dispose() does not deadlock under AX | Yes | No sync EDT→main bridge in the dispose path |
| 003-AC3 | Late navigate is safe no-op | Yes | Generation counter + existing `e->webview` null check |
| 003-AC4 | Late evalAsync fails future with "WebView disposed" | Yes | EvalDispatcher already drained at Java dispose; native generation guard is belt-and-suspenders |
| 003-AC5 | cocoa_run_on_main helper removed | Yes | After 001 + 002 + 003 land, no callers; mechanical removal |
| 003-AC6 | Long-run demo (5 min, ASan) passes | Yes | Requires demo extension (in scope of 003 per the story) |
| 003-AC7 | Canvas 6 Norms updated | Yes | Mechanical Norms-section rewrite during `/spdd-prompt-update` |

**Coverage**: 24 of 24 ACs addressable. No gaps. Two require care during canvas generation: 002-AC5 (cocoa_bind async conversion) and 001-AC7 (KVO observer lifecycle including window-change handling).

**Non-functional**: all five non-functional expectations (50 ms attach, 50 ms dispose, O(1) late-op short-circuit, KVO callback O(depth), ASan-clean) are addressable with the proposed mechanisms.
