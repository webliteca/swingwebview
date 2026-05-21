# Story Decomposition: Eliminate EDT↔AppKit Sync Deadlock Surface on macOS

## INVEST Analysis

### Abstract Task

**Feature**: Eliminate every synchronous EDT→AppKit-main bridge in the macOS heavyweight WebView implementation, closing a class of deadlocks where the EDT and the AppKit main thread mutually wait on each other.

**Analysis Dimensions**:

- **Core Responsibility**: Make the macOS native bridge non-deadlockable by removing the three sync EDT→main call sites in `src_c/webview_embed.cpp` (engine create, first-responder probe, engine destroy) and replacing them with async or cached equivalents.
- **Primary Operations**:
  1. Async engine attach with deferred completion listener (replaces sync `cocoa_create_engine`).
  2. KVO-driven cached first-responder flag (replaces sync `cocoa_is_first_responder`).
  3. Async engine destroy with safe queued-op handling (replaces sync `cocoa_destroy_engine`).
- **Key Constraints**:
  - Java-level `EmbeddedWebView.attach(parent, debug)` must remain a synchronous factory — no API break for existing call sites.
  - `WebViewHeavyweightComponent`'s pre-paint buffer/replay pattern must keep working unchanged.
  - Queued async ops on a destroyed engine must never UAF.
  - The new attach-listener API must be present on Windows and Linux too, even though those platforms attach synchronously.
  - Repro demo (`demos/WebViewDeadlockRepro`) must run indefinitely without the watchdog firing.
- **Technical Complexity**: High — touches native concurrency (KVO observers, generation counters, async lifecycle), JNI lifecycle, and cross-platform Java API design.
- **Business Complexity**: Medium — three logically distinct subgoals, each with its own failure modes and acceptance scenarios, but all in service of one outcome (no hangs).

### INVEST Evaluation

- ❌ **Independent**: As a single story, it has three internal subgoals that can ship independently — splitting clarifies dependencies.
- ✅ **Negotiable**: Yes — listener shape, KVO mechanism, generation counter vs. destroyed flag are all open to refinement.
- ✅ **Valuable**: Yes — eliminates user-visible hangs in production.
- ✅ **Estimable**: Yes — each subgoal is bounded.
- ❌ **Small**: Combined, the three subgoals exceed 5 days. Each subgoal individually is 1–3 days.
- ✅ **Testable**: Yes — the repro demo is a deterministic end-to-end test, plus AC-level unit tests on the cache and lifecycle.

**Conclusion**: Needs splitting. Three sub-stories, each independently shippable, each closing one specific sync bridge.

### Split Strategy

**Dimension**: by sync site / technical dependency. Each sub-story removes one of the three sync EDT→main bridge sites. They can land in any order, but the demo will keep deadlocking until all three plus the helper-removal land.

**Recommended sequence** (highest user impact first):

1. **STORY-004-001** — Cached first-responder flag. Closes the sync site that the repro demo actually exercises (Cmd-C path).
2. **STORY-004-002** — Async engine create with attach-completion listener. Closes the AX-triggered deadlock window during initial attach.
3. **STORY-004-003** — Async engine destroy with safe queued-op handling, plus removal of the now-unused `cocoa_run_on_main` sync helper. Closes the destroy-side variant and retires the v1.0.5 `performSelectorOnMainThread:modes:` scaffolding.

---

## [STORY-004-001] Cached First-Responder Flag for Editing-Shortcut Routing

### Background

The editing-shortcut dispatcher on the EDT calls `EmbeddedWebView.isNativeFirstResponder` for every `Cmd-C` / `Cmd-V` / `Cmd-X` / `Cmd-A` key event, to decide whether to route the shortcut into the WKWebView or let Swing handle it. On macOS, that check currently hops EDT→main via a synchronous native bridge: it parks the EDT on a semaphore, signals the AppKit main thread to walk the responder chain, and waits for the answer.

When the AppKit main thread is simultaneously parked in `invokeAndWait` waiting for the EDT (a routine condition during accessibility, IME, focus, or other AppKit→Java upcalls), both threads wait forever. The repro demo at `demos/WebViewDeadlockRepro` triggers this within seconds of pressing `Cmd-C` while the WebView is focused.

Because this is the highest-frequency sync bridge call in the library — fired on every editing keystroke — closing it eliminates the most-likely-to-be-hit deadlock window in production.

Key points:

- The fix targets the per-call sync bridge in `cocoa_is_first_responder`, not the broader bridge infrastructure (that's covered by 004-002 and 004-003).
- Swizzled `becomeFirstResponder` / `resignFirstResponder` hooks on `WKWebView` are not sufficient: focus on inner WebKit content views (e.g. an `<input>` element inside the page) is delegated to a private `WKContentView` and does not fire `WKWebView`'s own responder methods.
- A KVO observer on the host `NSWindow.firstResponder` property fires on the AppKit main thread for every focus change anywhere in the window's responder hierarchy. Walking the responder chain from the KVO callback and updating an `std::atomic<bool>` on the Engine gives the EDT a sync-free read path.

### Business Value

- End users of any heavyweight WebView application never experience hangs from `Cmd-C` / `Cmd-V` / `Cmd-X` / `Cmd-A` shortcuts.
- Library users do not need to audit their callback code for the EDT-sync-bridge contract on this specific path.
- Removes the most frequently-exercised deadlock surface in `src_c/webview_embed.cpp`.

### Dependencies and Assumptions

- **Prerequisites**: None. This story is independently shippable.
- **Data assumptions**: KVO observation on `NSWindow.firstResponder` is supported in every macOS version the library targets (10.13+); responder-chain walking from KVO is a documented AppKit pattern.
- **Integration points**:
  - `EmbeddedWebView.isNativeFirstResponder` (Java)
  - `cocoa_is_first_responder` (C++)
  - Existing swizzled `WKWebView` becomeFirstResponder / resignFirstResponder hooks (kept as additional update points, not the sole source of truth)
- **Business constraints**: Existing public API of `EmbeddedWebView` must not change shape.

### Scope In

- `std::atomic<bool>` field on the macOS-side `Engine` struct: `is_first_responder`.
- KVO observer registered on `[host window] firstResponder` at engine attach; unregistered at engine destroy.
- On each KVO firing, walk the responder chain to determine whether the WKWebView (or any of its descendants, including the private content view) is the current responder, and update the atomic.
- `EmbeddedWebView.isNativeFirstResponder` reads the atomic via a JNI call that performs no main-thread hop.
- Demo verification: `demos/WebViewDeadlockRepro` runs at least 60 seconds with periodic `Cmd-C` without the EDT watchdog firing.

### Scope Out

- Async engine create / attach (STORY-004-002).
- Async engine destroy (STORY-004-003).
- Removal of the now-partially-unused `cocoa_run_on_main` sync helper — its other call sites (create, destroy) still exist after this story; cleanup happens in 004-003.
- Windows / Linux paths.

### Acceptance Criteria

#### AC1: Cmd-C inside the WebView copies selected text to the clipboard

**Given** a heavyweight WebView showing a page with selectable text and the user has clicked into the page so the WebView holds focus
**When** the user selects text and presses `Cmd-C`
**Then** the selected text appears on the macOS system clipboard and the editing-shortcut dispatcher determined responder status with no AppKit main-thread hop

#### AC2: Cmd-V inside the WebView pastes from the clipboard

**Given** the WebView holds focus, the system clipboard contains "hello", and an input field on the page has focus
**When** the user presses `Cmd-V`
**Then** "hello" appears in the input field

#### AC3: Editing shortcut with focus outside the WebView falls through to Swing

**Given** a `JTextField` sibling holds focus and contains selected text
**When** the user presses `Cmd-C`
**Then** Swing's default `Cmd-C` action runs and the editing-shortcut dispatcher does not invoke `EmbeddedWebView.executeEditingCommand`

#### AC4: Focus on an inner DOM element is reported as WebView-is-responder

**Given** the user has clicked into an `<input>` element inside the loaded page, so first-responder is the private WebKit content view
**When** the editing-shortcut dispatcher queries `isNativeFirstResponder`
**Then** the result is `true`

#### AC5: Cache reflects focus transfer out of the WebView within one event-loop tick

**Given** the WebView held focus, then the user clicked a `JTextField` sibling
**When** the editing-shortcut dispatcher queries `isNativeFirstResponder` after the focus transfer
**Then** the result is `false`

#### AC6: WebViewDeadlockRepro demo runs cleanly for at least 60 seconds

**Given** `demos/WebViewDeadlockRepro` is launched and the simulated `Cmd-C` loop is running
**When** the demo runs for 60 seconds
**Then** the EDT-deadlock watchdog never fires and the app remains responsive

#### AC7: KVO observer is released cleanly on engine destroy

**Given** an `EmbeddedWebView` is attached to a parent that is later removed via `removeNotify`
**When** the engine is destroyed
**Then** the KVO observer is unregistered from the host window before the engine struct is freed and no AppKit warnings about pending observers appear in the log

### Non-Functional Expectations

- `isNativeFirstResponder` must return in constant time with no perceptible latency increase versus the previous sync implementation under non-deadlock conditions.
- KVO callback work must be bounded (single responder-chain walk, O(depth) where depth is small).

---

## [STORY-004-002] Async Engine Attach with Completion Listener

### Background

`cocoa_create_engine` in `src_c/webview_embed.cpp` runs the entire AppKit-side setup of the WKWebView synchronously on the main thread, with the EDT parked on a semaphore waiting for it to finish. The setup calls `addSubview:` on the JDK's `AWTView`. `addSubview:` triggers AppKit's accessibility subsystem, which queries the `AWTView` for accessibility metadata; the JDK answers that query by routing back to the EDT via `LWCToolkit.invokeAndWait`. Because the EDT is parked above, both sides deadlock.

The accessibility subsystem is activated by VoiceOver, screen readers, `Cmd-F1` accessibility shortcuts, Spotlight, Stage Manager, and other system-level events the application cannot control. Users have reported the hang occurring within minutes of first launch on machines with no obvious AX consumer running.

This story makes engine attach asynchronous: the C++ Engine struct is allocated immediately, but all AppKit-side setup runs on the main thread via `cocoa_run_on_main_async`. The EDT never parks. A new `WebViewAttachListener` API lets callers observe completion (success or failure). Pre-attach operations buffer and replay, analogous to the existing `WebViewHeavyweightComponent` pre-paint buffer.

Key points:

- `EmbeddedWebView.attach(parent, debug)` remains a synchronous factory at the Java API level — it returns immediately with a non-null `EmbeddedWebView` in a new `PENDING` state.
- The new listener API ships on all platforms; on Windows and Linux attachment is already synchronous, so listeners fire immediately.

### Business Value

- Engine attach is no longer deadlock-able by external AppKit subsystems (AX, IME, focus tracking) that round-trip through the JDK.
- Library users gain a documented mechanism (`addOnAttachComplete`) to react to attach success or failure, rather than relying on synchronous exception throw.
- `WebViewHeavyweightComponent` and other library-internal consumers can layer their pre-paint buffer on top of a uniform pre-attach buffer.

### Dependencies and Assumptions

- **Prerequisites**: None — independent of 004-001 and 004-003.
- **Data assumptions**:
  - `WebViewHeavyweightComponent`'s buffer/replay pattern (pendingUrl, pendingInit, pendingBindings, debug) for pre-paint configuration is generalizable to pre-attach.
  - The Engine struct can be allocated synchronously on the calling thread (it has no AppKit dependencies) — only the WKWebView and subview attachment require main-thread execution.
- **Integration points**:
  - `EmbeddedWebView` (Java public API)
  - `WebViewAttachListener` (new Java interface)
  - `cocoa_create_engine` and the Windows / Linux equivalents
  - `WebViewHeavyweightComponent`'s pre-paint replay path
- **Business constraints**: `EmbeddedWebView.attach(parent, debug)` signature is fixed — callers must not need to be rewritten.

### Scope In

- New attach-state enum on `EmbeddedWebView`: `PENDING`, `ATTACHED`, `FAILED`.
- New public Java interface `WebViewAttachListener` with `onAttached(EmbeddedWebView)` and `onAttachFailed(EmbeddedWebView, Throwable cause)`.
- New public method `EmbeddedWebView.addOnAttachComplete(WebViewAttachListener)` — fires on the EDT once attach resolves. If already resolved at registration time, fires on the next EDT tick.
- macOS: `cocoa_create_engine` returns immediately after allocating the C++ Engine struct; WKWebView creation, `setWantsLayer:`, `addSubview:`, and configuration run inside `cocoa_run_on_main_async`. Success or failure is communicated back to Java via a JNI callback on the EDT.
- Pre-attach `setUrl`, `executeJavaScript`, `evalAsync`, `addBinding`, `setInitScript`, `setUserAgent`, etc. on a `PENDING` `EmbeddedWebView` are buffered and replayed on `ATTACHED` transition in registration order.
- `attach()` still throws synchronously if the C++ Engine struct allocation itself fails (the rare case — out of memory). Only the AppKit-side phase is async.
- Cross-platform parity: on Windows and Linux, attach completes synchronously and any registered listener fires immediately on the EDT.
- `WebViewHeavyweightComponent`'s existing pre-paint buffer keeps working unchanged on top of the new pre-attach mechanism.

### Scope Out

- Async destroy (STORY-004-003).
- Removal of `cocoa_run_on_main` sync helper (STORY-004-003).
- Changes to the JS-side `eval` callback dispatch path.

### Acceptance Criteria

#### AC1: attach() returns within 50 ms on macOS regardless of AppKit state

**Given** an AppKit consumer (e.g. VoiceOver enabled or AX query active) is exercising `accessibilityFocusedUIElement` on AWTView
**When** `EmbeddedWebView.attach(parent, debug)` is called from the EDT
**Then** the call returns within 50 ms with a non-null `EmbeddedWebView` whose attach state is `PENDING`

#### AC2: Listener fires on the EDT on successful attach

**Given** `attach()` has just returned a `PENDING` `EmbeddedWebView` and a `WebViewAttachListener` is registered
**When** the async AppKit-side setup completes successfully
**Then** `onAttached(webView)` is invoked on the EDT and the attach state is `ATTACHED`

#### AC3: Listener fires on the EDT on attach failure

**Given** the async AppKit-side setup fails (e.g. WKWebView allocation returns nil because of a configuration error)
**When** the async setup resolves with failure
**Then** `onAttachFailed(webView, cause)` is invoked on the EDT, the attach state is `FAILED`, and `cause` carries an actionable message identifying which setup step failed (e.g. "WKWebView allocation failed" / "host NSView not found")

#### AC4: Pre-attach setUrl is replayed after attach completes

**Given** `attach()` returned a `PENDING` `EmbeddedWebView` and the caller invoked `setUrl("https://example.com")` before any listener fired
**When** attach completes successfully
**Then** the WebView begins loading `https://example.com` automatically with no further caller action

#### AC5: Pre-attach addBinding is replayed and callable from JS

**Given** `attach()` returned `PENDING` and the caller invoked `addBinding("myFn", handler)` before attach completed
**When** attach completes and a page is then loaded
**Then** `window.myFn(...)` is callable from JavaScript and dispatches to `handler`

#### AC6: Late listener registration fires immediately on the EDT

**Given** attach already resolved (to `ATTACHED` or `FAILED`) before any listener was registered
**When** `addOnAttachComplete(listener)` is called from the EDT
**Then** the listener fires on the next EDT event-loop tick with the resolved outcome

#### AC7: WebViewHeavyweightComponent's pre-paint buffer continues to work

**Given** a `WebViewHeavyweightComponent` is constructed and `setUrl(...)` and `addBinding(...)` are called before the component has been painted
**When** the component is added to a frame and painted for the first time
**Then** the URL loads and the binding is callable, with no observable behaviour change versus the pre-story implementation

#### AC8: Windows and Linux attach is synchronous and listener fires immediately

**Given** `attach()` is called on Windows or Linux
**When** the call returns
**Then** the `EmbeddedWebView`'s attach state is already `ATTACHED` and any subsequently-registered `WebViewAttachListener` fires on the next EDT tick with `onAttached`

#### AC9: attach() throws synchronously only on C++ allocation failure

**Given** the C++ Engine struct allocation itself fails (simulated via test hook)
**When** `attach()` is called
**Then** it throws `EmbeddedWebViewException` synchronously with a message identifying the allocation failure, and no `EmbeddedWebView` instance is returned

#### AC10: Attach failure on macOS does not leak the C++ Engine struct

**Given** the async AppKit-side setup fails after the C++ Engine struct was allocated
**When** the failure is reported back to Java
**Then** the C++ Engine struct is released as part of the failure path and no native memory leak is observable in a leak-detection harness

### Non-Functional Expectations

- `attach()` return time on macOS must be under 50 ms in all conditions, including under heavy AX activity.
- Buffered pre-attach operations must replay in registration order.

---

## [STORY-004-003] Async Engine Destroy with Safe Queued-Op Handling

### Background

`cocoa_destroy_engine` in `src_c/webview_embed.cpp` runs `removeFromSuperview` on the AppKit main thread synchronously, with the EDT parked. `removeFromSuperview` can trigger accessibility tree updates and other AppKit→Java upcalls — the same deadlock shape as STORY-004-002, but on the destroy side.

Closing this sync site requires more than moving `delete e` inside `cocoa_run_on_main_async`: the engine has potentially-queued async ops (navigate, eval, addBinding, executeJavaScript) that might fire on the main thread after the destroy lambda has freed the Engine struct, causing a use-after-free. A generation counter or destroyed atomic on the Engine lets each queued lambda check whether it is still safe to touch the struct.

Once destroy is async, the `cocoa_run_on_main` synchronous helper has no remaining callers and can be removed along with the v1.0.5 `WebViewAwtMainBridge` / `performWork:` scaffolding. The Canvas Norm mandating `performSelectorOnMainThread:modes:` for `cocoa_run_on_main` is retired in favour of a new Norm forbidding any sync EDT→main bridge in `webview_embed.cpp`.

Key points:

- This story depends conceptually on 004-002 (the same async-lambda pattern), but ships independently.
- The helper removal is part of this story because it is the natural cleanup once all three sync sites are gone.

### Business Value

- `removeNotify` / `dispose()` paths on heavyweight WebViews can never hang the EDT, regardless of AppKit state.
- The library becomes robust to teardown happening while the page is actively running JS or loading.
- The codebase's deadlock surface is provably zero — there is no remaining sync EDT→main bridge to audit.

### Dependencies and Assumptions

- **Prerequisites**: None as a sequencing dependency, but the helper-removal AC presumes 004-001 and 004-002 have also landed (because they remove the other two callers of the sync helper). If 004-003 lands first, the helper-removal AC is deferred to whichever story lands last.
- **Data assumptions**:
  - A generation counter (e.g. `std::atomic<uint64_t>`) or destroyed flag (`std::atomic<bool>`) on the Engine is sufficient to short-circuit late async ops.
  - All async ops in the macOS implementation already capture an `Engine*`; capturing a generation snapshot at queue time and comparing at fire time is straightforward.
- **Integration points**:
  - `cocoa_destroy_engine`
  - Every `cocoa_run_on_main_async` lambda in `src_c/webview_embed.cpp` (navigate, eval, addBinding, setInitScript, etc.)
  - `WebViewAwtMainBridge` Objective-C class and `performWork:` selector (to be removed)
  - Canvas 6 Norms section
- **Business constraints**: No observable behaviour change for callers other than destroy being non-blocking.

### Scope In

- `cocoa_destroy_engine` becomes fully async: `removeFromSuperview`, WKWebView teardown, KVO observer unregistration (from 004-001), and `delete e` all run inside `cocoa_run_on_main_async`. The JNI entry returns immediately.
- Generation counter (or destroyed atomic) on Engine. Every async lambda for navigate / eval / addBinding / etc. checks the counter before dereferencing the engine and short-circuits cleanly (no crash, no exception) if the engine has been destroyed.
- `evalAsync` lambdas on a destroyed engine complete the returned `CompletableFuture` exceptionally with an actionable message ("WebView disposed").
- Removal of `cocoa_run_on_main` sync helper, `WebViewAwtMainBridge` class, and `performWork:` selector.
- Canvas 6 Norms section updated: the v1.0.5 `performSelectorOnMainThread:modes:` norm is retired and replaced by a norm forbidding any sync EDT→main bridge.

### Scope Out

- Any change to Windows or Linux destroy paths.
- Any change to the synchronous Java API contract of `dispose()` (it still returns when called; only the underlying native work becomes async).

### Acceptance Criteria

#### AC1: dispose() returns within 50 ms on macOS

**Given** an attached `EmbeddedWebView`
**When** `dispose()` (or the parent component's `removeNotify`) is invoked from the EDT
**Then** the JNI call returns within 50 ms

#### AC2: dispose() does not deadlock when AX is active

**Given** VoiceOver is enabled or another AX consumer is querying the AWTView
**When** `dispose()` is called from the EDT
**Then** the call returns cleanly within 50 ms and the EDT remains responsive (no watchdog event)

#### AC3: Late navigate on a destroyed engine is a safe no-op

**Given** an `EmbeddedWebView` was disposed while a `setUrl` async lambda was queued on the main thread
**When** the queued lambda fires
**Then** no UAF occurs, no exception escapes the native layer, and a debug-level log entry records the no-op

#### AC4: Late evalAsync on a destroyed engine fails the future with a clear message

**Given** an `EmbeddedWebView` was disposed while an `evalAsync(...)` lambda was queued on the main thread
**When** the queued lambda fires
**Then** the previously-returned `CompletableFuture` completes exceptionally with a message containing "WebView disposed" and no UAF occurs

#### AC5: cocoa_run_on_main sync helper is removed from the codebase

**Given** the codebase is searched for `cocoa_run_on_main` (without the `_async` suffix)
**When** the search completes
**Then** there are zero matches in production source files, and `WebViewAwtMainBridge` / `performWork:` have also been removed

#### AC6: Long-run repro demo passes without deadlock

**Given** `demos/WebViewDeadlockRepro` is extended to run a loop of attach → focus → simulated Cmd-C → dispose for at least 5 minutes
**When** the demo executes
**Then** no EDT-watchdog event fires, no native crash occurs, and no UAF is reported by Address Sanitizer (when run under ASan)

#### AC7: Canvas 6 Norms reflect the new contract

**Given** `spdd/prompt/6-*-Swing-Heavyweight-Webview-Embedding.md` is read
**When** the Norms section is inspected
**Then** it contains a norm forbidding any sync EDT→main bridge in `src_c/webview_embed.cpp`, and the previous norm mandating `performSelectorOnMainThread:modes:` for `cocoa_run_on_main` has been removed

### Non-Functional Expectations

- `dispose()` return time on macOS must be under 50 ms in all conditions.
- Late async ops on destroyed engines must complete in O(1) regardless of how many ops were queued.
- ASan / leak-detection runs of the repro demo must show no native leaks attributable to the destroy path.
