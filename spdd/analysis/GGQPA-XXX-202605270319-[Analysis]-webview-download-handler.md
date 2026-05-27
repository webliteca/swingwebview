# SPDD Analysis: WebView Download Handler (destination + lifecycle)

## Original Business Requirement

This analysis covers both stories in `requirements/[User-story-5]webview-download-handler.md`:

- **STORY-005-001** — `WebViewDownloadHandler` API and destination routing across macOS, Linux (heavyweight + lightweight), and Windows. Mirrors `WebViewDialogHandler`. Default saves to `~/Downloads` with `(N)` de-duplication; `setDownloadHandler(null)` cancels every download.
- **STORY-005-002** — Lifecycle observation (`progress` / `completed` / `failed`) and mid-flight `cancel()` via a `WebViewDownload` handle and `WebViewDownloadListener` interface. Strictly additive on top of 005-001 — `downloadRequested` keeps its `File`-or-null signature, and a new optional `downloadStarted(WebViewDownload)` callback delivers the handle.

The user-provided story file (485 lines) is the canonical input; see `requirements/[User-story-5]webview-download-handler.md` in this branch for the complete text including all 39 ACs and the non-functional expectations sections. Both stories share the same native callback sites and the same Java dispatcher class, so a single analysis is appropriate.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **`WebViewComponent`** (`src/ca/weblite/webview/swing/WebViewComponent.java:51`) — the abstract Swing base hosting the embedded engine. Already owns three per-component "fan-out hubs" (`consoleDispatcher`, `mouseDispatcher`, `dialogDispatcher`, plus an `evalDispatcher` in `EmbeddedWebView`) that all follow the same shape: held for the component's lifetime, survive the native peer's create/destroy cycle, and use one of two EDT-marshal styles (`invokeAndWait` for synchronous resolvers; `invokeLater` for fire-and-forget listeners). The download stories add a fourth dispatcher in the same mould.
- **`WebViewDialogHandler`** (`src/ca/weblite/webview/WebViewDialogHandler.java`) — the closest precedent. Defines the API style the download handler must mirror: an interface with `default` methods, a static `DEFAULT` instance, per-component installation via `setDialogHandler(handler)` with `null` mapped to an internal `DROP` singleton inside `DialogDispatcher`, EDT-only handler invocation, exception isolation that forwards to `Thread.getDefaultUncaughtExceptionHandler()`.
- **`DialogDispatcher`** (`src/ca/weblite/webview/DialogDispatcher.java`) — the synchronous-resolver dispatcher template. Holds a single volatile handler reference, exposes `dispatch*` entry points that JNI calls from the engine UI thread, uses `SwingUtilities.invokeAndWait` to hop to the EDT, captures return values via single-cell arrays, isolates exceptions, and exposes `disposeAll()` for the teardown path. STORY-005-001's `DownloadDispatcher` is a near-clone with one method (`dispatchDownload`) returning a path string or null.
- **`ConsoleDispatcher` / `WebViewMouseDispatcher`** — the asynchronous-listener dispatcher template. Holds a listener list (not a single resolver), uses `invokeLater` (not `invokeAndWait`), no return values. STORY-005-002's progress/completed/failed delivery follows this shape.
- **`EvalDispatcher`** — the request/response correlation template using `CompletableFuture<String>`. Not directly reused (downloads aren't request/response keyed by id) but the dispose-drain pattern (`disposeAllPending`) maps directly onto STORY-005-002's "complete in-flight handles with a terminal `failed(CANCELLED)` when the engine is destroyed" requirement.
- **`WebViewDialogCallback`** (`src/ca/weblite/webview/WebViewDialogCallback.java`) — the JNI-facing adapter interface the native engine invokes. STORY-005-001 needs a parallel `WebViewDownloadCallback` with one synchronous method (the destination decision); STORY-005-002 extends it with three asynchronous methods (progress / completed / failed).
- **`EmbeddedWebView.setDialogCallback`** (`src/ca/weblite/webview/EmbeddedWebView.java:442`) — the existing per-engine native-callback registration channel. The download work adds a parallel `setDownloadCallback`.
- **Native dialog wiring sites** — three of them:
  - **Linux**: `src_c/webview_embed.cpp:1002-1005` (heavyweight) and `:1548-1550` (lightweight) — both connect `script-dialog` / `run-file-chooser` directly on the per-engine `WebKitWebView`. The corresponding download signal is `download-started`, but it lives on the **`WebKitWebContext`**, not the `WebKitWebView` — see "Technical Risks" below.
  - **macOS**: `src_c/webview_embed.cpp:2517-2614` (the deadlock-safe alert IMP and its three siblings) — they use the "copy completion handler block + std::thread for JNI hop + dispatch_async back to AppKit main" template fixed in commit `480798c`. This is the **mandatory template** for the macOS download work.
  - **Windows**: `windows/webview_embed.cc:477-548` (the `ScriptDialogHandler` class) + `:745` (`put_AreDefaultScriptDialogsEnabled(FALSE)`) + `:771` (`add_ScriptDialogOpening`). Same deferral pattern needed for `DownloadStarting`.
- **README "Browser-initiated dialogs" section** — the existing precedent section that the new "Downloads" section sits next to.
- **`demos/WebViewDialogDemo`** — the demo precedent; STORY-005-001's `demos/WebViewDownloadDemo` mirrors its shape (radio-button-selected modes).

### New Concepts Required

- **`WebViewDownloadHandler`** (interface) — single-resolver contract per component; default returns `~/Downloads/<deduped-filename>`; `null` return cancels. Relates to `WebViewDialogHandler` as a sibling pattern; relates to `WebViewComponent` as a per-instance reference held by a new `DownloadDispatcher`.
- **`WebViewDownloadEvent`** (POJO) — immutable carrier of the four engine-supplied fields (suggestedFilename, sourceUrl, mimeType, totalBytes) plus the source component. Relates to `WebViewAlertEvent` / `WebViewConfirmEvent` / `WebViewPromptEvent` / `WebViewFilePickerEvent` as a sibling shape.
- **`DownloadDispatcher`** (internal) — sibling of `DialogDispatcher`; the EDT-hop bridge for the destination-decision callback. In STORY-005-002 it also owns the lifecycle dispatch hub (a registry of in-flight `WebViewDownload` handles, plus the listener-fanout machinery).
- **`WebViewDownloadCallback`** (JNI-facing adapter interface) — sibling of `WebViewDialogCallback`. Story 1 has one method (`onDownloadStarting`); story 2 adds three (`onProgress`, `onCompleted`, `onFailed`).
- **`WebViewDownload`** (story 2) — public class. The Java-side handle representing one active download. Combines the role of "session-scoped tracking object" (think `WKDownload` / `WebKitDownload` / `CoreWebView2DownloadOperation`) with the role of "listener registry for this one download". Relates to `WebViewDownloadEvent` as a strict superset — the handle exposes the event plus the chosen destination, the current state, and the listener-management methods.
- **`WebViewDownloadListener`** (story 2, interface) — sibling of `ConsoleListener` / `WebViewMouseListener`. Receives the three asynchronous lifecycle callbacks. The shape diverges slightly from those siblings because all three callbacks receive the same `WebViewDownload` handle (no separate event POJOs per callback) — a deliberate simplification since the handle already carries all the per-download state.
- **`WebViewDownloadListener.FailureReason`** (story 2, POJO with nested `Category` enum) — new concept with no direct precedent. The enum (CANCELLED / NETWORK / IO / UNKNOWN) is the cross-engine normalisation of three quite different error code domains (`WKErrorDomain`, `WebKitDownloadError`, `COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_*`).
- **Native per-download tracking record** (story 2) — on each platform, the native code needs to hold a per-download struct linking the engine's download object (`WKDownload *`, `WebKitDownload *`, `ICoreWebView2DownloadOperation *`), the Java global ref of the corresponding `WebViewDownload` handle, and the JNI-bridge function pointers. Lifetime is bounded by "destination decision made" → "terminal callback fired". Closest precedent in the codebase is the `Binding` struct used per JS binding inside the macOS Engine — same pattern of "per-resource glue object held in a `std::map` keyed by the native pointer".
- **`Downloads/` folder resolution** — small concept. `System.getProperty("user.home") + "/Downloads"` works on all three OSes for the default user profile. New utility code lives in `WebViewDownloadHandler` (the default impl) and is not factored out — same minimalism as `WebViewDialogHandler.DEFAULT`'s anonymous-class style.

### Key Business Rules

- **Single per-component handler.** Like `WebViewDialogHandler`, there is exactly one `WebViewDownloadHandler` per `WebViewComponent`; setter replaces, never composes. Governs `WebViewComponent` ↔ `DownloadDispatcher` ↔ `WebViewDownloadHandler`.
- **`null` is "drop", not "reset".** Passing `null` to `setDownloadHandler` installs an internal cancel-everything singleton (parallels `DialogDispatcher.DROP`). To reset to `~/Downloads` default, callers pass `WebViewDownloadHandler.DEFAULT` explicitly. This is the explicit divergence from the obvious "null means default" interpretation, and it has a real reason — headless tests need a way to suppress all downloads without UI.
- **EDT-only handler and listener invocation.** Governs `WebViewDownloadHandler.downloadRequested` (synchronous on EDT, `invokeAndWait`), `WebViewDownloadHandler.downloadStarted` (synchronous on EDT, `invokeAndWait` — runs after destination is chosen and the engine has committed), and `WebViewDownloadListener.progress/completed/failed` (asynchronous on EDT, `invokeLater`).
- **Terminal callback fires exactly once.** Governs the `WebViewDownload` lifecycle: after `completed` OR `failed` fires, no further callbacks fire; subsequent `cancel()` calls are no-ops; subsequent listener registrations replay the terminal event on the next EDT tick. The native code uses an atomic flag to guarantee single-firing under engine-level races (a cancel racing a completion).
- **`downloadStarted` is skipped when `downloadRequested` returned `null`.** Governs the relationship between the two `WebViewDownloadHandler` methods — if the destination decision is "cancel", no handle is constructed and the listener pathway is dead. The page's link `click` handler still fired; the engine has been told to abort; nothing more happens Java-side.
- **Filename sanitisation is Java-side defence-in-depth.** Every engine claims to sanitise path separators out of the suggested filename, but the Java layer does it again. Governs `WebViewDownloadEvent.suggestedFilename()` (the value the Java side surfaces) and the default `~/Downloads` write path. A malicious server's `Content-Disposition: filename="../../etc/passwd"` cannot escape `~/Downloads` even if a future engine version regresses on its own sanitisation.
- **Engine owns the bytes; Java owns the decisions.** The Java side never sees the raw file contents — every engine writes directly to the chosen path. This governs the "destination via path" rather than "destination via stream" shape of the API, and the absence of any "you've received N bytes, here they are" callback. Story 2's progress callback is **just a count**, never a buffer.

## Strategic Approach

### Solution Direction

Build the download channel as a **sibling of the dialog channel** at every level — Java public API, internal dispatcher, JNI-facing callback adapter, native engine bridge, demo, README section. The dialog channel went through three stories (macOS, Linux, Windows) to reach feature-completeness, and the resulting code in `DialogDispatcher`, `WebViewDialogHandler`, the three native call sites, and `EmbeddedWebView.setDialogCallback` is the template the download work clones. STORY-005-001 ships the synchronous-resolver half (destination decision); STORY-005-002 ships the asynchronous-listener half (progress / completed / failed / cancel) layered on the same native callback sites, mirroring how `ConsoleDispatcher` (listener-style) coexists with `DialogDispatcher` (resolver-style).

Data flow direction:

- **Page click** → engine classifies response as download → engine fires per-platform "download starting" callback on its native UI thread → C++ side captures URL/MIME/size/filename, copies them across the thread boundary (no NSString / GValue / LPWSTR captured), spawns a worker thread, calls `WebViewDownloadCallback.onDownloadStarting(...)` via JNI → Java-side `DownloadDispatcher` hops to EDT via `invokeAndWait`, invokes `WebViewDownloadHandler.downloadRequested(event)` → captured return value (path or null) is handed back to C++ worker thread → C++ marshals back to the engine's UI thread and either tells the engine the destination or cancels.
- **(Story 2) Engine fires progress / terminal events** on its native UI thread → C++ extracts byte counts or error category, looks up the per-download Java handle global ref, calls `WebViewDownloadCallback.onProgress/onCompleted/onFailed(...)` via JNI → Java-side `DownloadDispatcher` hops to EDT via `invokeLater`, updates the `WebViewDownload` handle's fields, fans out to each registered listener.
- **(Story 2) Java cancel()** → Java-side calls a native cancel method on `WebViewDownload`'s native handle → C++ marshals to the engine's UI thread and invokes the engine's cancel primitive → the engine subsequently emits a `failed` terminal event with the cancel category, which travels back through the normal progress path.

Where this leverages existing patterns:

- The Java dispatcher class is a direct clone of `DialogDispatcher` for story 1 and a hybrid clone of `DialogDispatcher + ConsoleDispatcher` for story 2.
- The macOS bridge uses the 480798c deadlock-safe template verbatim — copy the completion handler block, snapshot strings on AppKit main, std::thread for the JNI hop, `dispatch_async` back to AppKit main for the completion handler.
- The Windows bridge uses `windows/webview_embed.cc:477-548`'s `ScriptDialogHandler` shape — `GetDeferral`, AddRef the args, std::thread for the JNI hop, `dispatch_to_thread` back onto the WebView2 worker for the result-path / cancel / Complete sequence.
- The Linux bridge uses `src_c/webview_embed.cpp:692-756`'s `handle_script_dialog` shape — a shared inner function called by per-engine wrappers, returning TRUE to suppress the default GTK UI.
- The README integration follows the existing "Browser-initiated dialogs" section format.

### Key Design Decisions

#### Decision 1 — Two-method `WebViewDownloadHandler` interface vs separate listener-registration API on `WebViewComponent`

**Story 2 design** (per the story): a new `default void downloadStarted(WebViewDownload download)` method added to `WebViewDownloadHandler` alongside the existing `downloadRequested(WebViewDownloadEvent) → File`. Callers that want progress override `downloadStarted` and call `download.addListener(...)`.

**Alternative considered**: keep `WebViewDownloadHandler` single-method (destination decision only) and add `WebViewComponent.addDownloadObserver(WebViewDownloadObserver)` where the observer receives a `downloadStarted(WebViewDownload)` event for every download regardless of the handler.

**Trade-offs**:

- The two-method-on-handler shape (story design) keeps "per-download decisions" centralised on one object. A test that wants to verify "this download was cancelled" registers one handler and stashes the handle in a field, full stop. The `addListener` shape would require two registration sites (the handler decides destination; the observer learns about the handle).
- However, the two-method shape couples observation to handler ownership: a caller who wants to **observe** every download without **deciding** their destinations (e.g. a logging facility that doesn't want to override the default `~/Downloads` policy) has to write a handler that forwards to `super.downloadRequested(event)` — which doesn't exist on an interface. They'd have to call `WebViewDownloadHandler.DEFAULT.downloadRequested(event)` manually. That's awkward.
- The `addListener` shape would let observation compose with the default handler trivially: leave the handler unset (so `DEFAULT` runs), register one or more observers, observe the resulting handles. It's a closer match to how `ConsoleDispatcher` exposes `addListener` independent of the dialog handler.
- The story explicitly notes this trade-off ("see Scope In for the chosen shape") and goes with the two-method-on-handler approach. Reason: it parallels `WebViewDialogHandler` (single object, multiple methods) instead of introducing a new pattern.

**Recommendation**: **Keep the story's two-method-on-handler design**, but reconsider during Canvas / generation if the "observation without override" use case shows up in any AC or in the demo. Concretely: AC18 (the demo) shows progress + cancel using a custom handler — fine. There is no AC that exercises "observe with the default handler still installed", so this design is not contradicted by any AC. **However**, if the demo ends up with three modes that all override `downloadRequested` (Default mode, Custom mode, Drop mode), the demo doesn't exercise observing-the-default either, which is a small ergonomic gap to consider noting in the README. **Flag this in the REASONS Canvas's Norms / open-questions section.**

#### Decision 2 — Linux `download-started` signal connection: per-engine context vs shared default context

**Story-implied design**: connect `download-started` "on the engine-shared `WebKitWebContext`" (Scope In). 

**Codebase reality**: this codebase does **not** explicitly create a `WebKitWebContext` — `webkit_web_view_new()` and friends use the default context (`webkit_web_context_get_default()`) by default. A grep for `webkit_web_context` in `src_c/webview_embed.cpp` returns nothing.

**Trade-offs**:

- If we keep using the default context, the `download-started` signal fires for **every** WebKit web view in the entire JVM that uses the default context — including all of this library's engines, heavyweight and lightweight, in any number of `WebViewComponent`s. The handler must look up the originating `WebKitWebView` via `webkit_download_get_web_view(download)` and route to the right `Engine` / `OffEngine` (which owns the right `download_callback` global ref). The macOS side already does this routing for the swizzled responder hooks via `g_webview_map` (`src_c/webview_embed.cpp:2181-2182`). A parallel Linux `g_webview_to_engine_map` (or similar) is needed.
- Alternative: create a **per-engine** `WebKitWebContext` (one `WebKitWebContext` per `Engine` and `OffEngine`) and connect `download-started` only on each engine's own context. This eliminates the routing question (the signal handler closure captures the owning Engine* directly) but it changes more behaviour than just downloads — separate contexts means separate cookie jars, separate session storage, separate caches between WebViews in the same JVM. That is a **significant** behaviour change and almost certainly **not what current users expect**. It would break any existing app that relies on, say, "log in to https://example.com in one WebView, then open the link in another WebView and stay logged in".
- The script-dialog signal is per-WebView, so the dialog work didn't have to face this question.

**Recommendation**: **Use the existing default context, add a Linux `webview-to-engine` map, route in the signal handler**. The cost is one extra `std::map<id, Engine*>`-equivalent for Linux (separate maps for heavyweight `Engine` and lightweight `OffEngine`, or one unified one keyed by `WebKitWebView*`). The benefit is preserving the current shared-context behaviour that any existing user depends on. The macOS `g_webview_map` is the template.

#### Decision 3 — macOS: extend the existing UIDelegate vs new NavigationDelegate

**Story-implied design**: "Augment the existing navigation-delegate hook (or attach one if the engine does not yet have one)" — STORY-005-001 Scope In.

**Codebase reality**: a grep for `navigationDelegate` / `WKNavigationDelegate` in `src_c/webview_embed.cpp` returns **no matches**. The current macOS engine attaches a `WKUIDelegate` (for dialogs) and a `WKScriptMessageHandler` (for the eval channel) but **no `WKNavigationDelegate`**. Today the engine relies on `WKWebView`'s default navigation behaviour. Downloads are silently dropped because of this — without a navigation delegate, `didBecomeDownload:` cannot fire.

**Trade-offs**:

- Create a brand-new `WebviewEmbedNavigationDelegate` ObjC class (parallel to the existing `WebviewEmbedUIDelegate`) and assign it via `[webview setNavigationDelegate:]` at engine-creation time. Implements `webView:navigationAction:didBecomeDownload:` and `webView:navigationResponse:didBecomeDownload:`. Each sets the new `WKDownload`'s `delegate` to a parallel `WebviewEmbedDownloadDelegate` ObjC class implementing `WKDownloadDelegate`. This is the **clean** route — three separate delegate classes, each with a single responsibility, matching the existing `WebviewEmbedUIDelegate` shape.
- Alternative: collapse navigation + download into one ObjC class. Slightly less code but conflates concerns; rejected.
- Alternative: skip the navigation delegate and intercept downloads via some other path. **There is no other path** — `WKWebView` only surfaces downloads via the navigation delegate's `didBecomeDownload:` selectors. Rejected.

**Recommendation**: **Create three new ObjC classes**: `WebviewEmbedNavigationDelegate`, `WebviewEmbedDownloadDelegate`, and a per-download tracking struct. Wire the navigation delegate at engine-creation time; have `didBecomeDownload:` attach the download delegate and create the tracking record. Update the story / Canvas to reflect that the navigation delegate is **new**, not "augmented".

#### Decision 4 — Filename de-duplication strategy

**Story design**: on collision, append ` (1)`, ` (2)`, … before the last `.`-segment of the filename, up to 999. Probe is performed on the EDT inside the default handler.

**Trade-offs**:

- Matches Chrome / Edge / Safari out of the box. Familiar to end users.
- Probe is `File.exists()` (or `Files.exists(Path)` if we touched up to NIO; but Java 8 baseline means staying on `java.io.File`). Cheap; even at 999 collisions it's sub-millisecond on any modern filesystem.
- Edge case: `tar.gz` — the dedup splits before the **last** dot, producing `archive.tar (1).gz` not `archive (1).tar.gz`. Browsers do the same. Document but don't fix.
- Edge case: no extension — `Makefile` becomes `Makefile (1)`. Fine.

**Recommendation**: **As designed.** The 999 ceiling is a sanity limit; in practice no user accumulates 999 collisions, but the cap prevents an infinite loop if the filesystem is read-only or has another bug.

#### Decision 5 — Story 2's `cancel()` semantics: synchronous-shaped (caller knows it's done) vs asynchronous (caller relies on the listener)

**Story design**: `cancel()` is asynchronous. The call returns immediately; the engine emits a `failed` event with `CANCELLED` category once it has actually stopped (typically a few ms, no hard upper bound).

**Trade-offs**:

- Engine reality: all three engines' cancel primitives are asynchronous. `[WKDownload cancel:]` runs a completion block once cancellation is acknowledged; `webkit_download_cancel` returns void and the `failed` signal fires later; `CoreWebView2DownloadOperation::Cancel()` schedules and the `StateChanged` event delivers `COREWEBVIEW2_DOWNLOAD_STATE_INTERRUPTED` later. There is no synchronous-cancel primitive on any engine.
- A "synchronous cancel" would have to be Java-side simulation — block the EDT until the terminal callback fires. That would either deadlock (the terminal callback comes via `invokeLater` on the EDT we just blocked) or require the `cancel()` caller to be off the EDT.
- The asynchronous design is therefore the only correct shape. AC9 (cancel idempotency) explicitly relies on this.

**Recommendation**: **As designed.** Document in the JavaDoc that `cancel()` is fire-and-forget and the listener's `failed(CANCELLED)` is the authoritative signal.

#### Decision 6 — Error category mapping (story 2)

Three engines, three error namespaces, one `Category` enum. The mappings the story proposes:

- macOS `WKErrorDomain` → mapped per-code (e.g., `WKErrorWebContentProcessTerminated` → UNKNOWN; `NSURLErrorCancelled` → CANCELLED; `NSURLErrorNotConnectedToInternet` → NETWORK; `NSURLErrorCannotCreateFile` → IO).
- Linux `WebKitDownloadError` (an enum, only 4 values): `CANCELLED_BY_USER` → CANCELLED; `DESTINATION` → IO; `NETWORK` → NETWORK.
- Windows `COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_*`: `USER_CANCELED` → CANCELLED; the `NETWORK_*` family → NETWORK; the `FILE_*` family → IO; everything else → UNKNOWN.

**Recommendation**: Define the mapping table in code comments inside each native bridge file (alongside the existing error-mapping comments in the dialog work). The Canvas should specify the exact mappings as Norms; the analysis just confirms the three namespaces are mappable into the four-value enum without information-destroying gaps.

### Alternatives Considered

- **One story instead of two**: rejected during story decomposition (see `requirements/[User-story-5]webview-download-handler.md` INVEST Analysis section) — combined size is 7-9 days, splitting at the lifecycle boundary keeps each slice within INVEST sizing and ships destination-only earlier.
- **Per-platform stories (six total: macOS/Linux/Windows × destination/lifecycle)**: rejected for the reasons noted in the story file — the Java contract is small and well-understood, and the per-platform native work is small enough per platform that bundling all three within each lifecycle phase is the right grain. (The dialog work split per-platform because macOS was 100% broken and the contract had to be designed alongside it.)
- **Routing downloads through a JS shim that intercepts link clicks**: rejected because the engines deliver downloads via response classification (Content-Disposition, non-renderable MIME) not just link clicks; a JS shim cannot see these responses.
- **Streaming the bytes through Java (so the handler returns an `OutputStream` instead of a `File`)**: rejected because every engine writes to a path natively and there is no public hook to substitute a stream. Forcing this would mean writing to a temp file and copying — overhead and complexity for no real gain. The chosen "engine owns the bytes; Java owns the decisions" stance is correct.
- **Reusing `WebViewDialogHandler` (overload it for downloads)**: not seriously considered — different lifecycle, different return shape. The parallel-but-distinct interface is right.

## Risk & Gap Analysis

### Requirement Ambiguities

- **"User's `Downloads` folder" on Linux**: the story says `System.getProperty("user.home") + "/Downloads"`. This works for default user profiles on every distro this library targets, but a Linux user with `XDG_DOWNLOAD_DIR` set to a non-default path (or with their `Downloads` folder localised to a non-English name in their language) will be surprised. The story doesn't mention XDG. **Recommendation**: stick with `~/Downloads` for the default handler — it matches Chrome's behaviour when XDG is unset and is dead-simple. Users who care can override via `setDownloadHandler`. Note this in the README's caveats. **Flag for Canvas.**
- **`<a download>` filename precedence vs `Content-Disposition` filename**: the story says (AC18) `<a download="custom-name.bin">` is honoured as the suggested filename. In practice every engine resolves which-wins-among-the-two internally (the `download` attribute is overridden by `Content-Disposition: attachment; filename=` for cross-origin downloads per the spec). The story implies the engine's resolution is the suggested filename we surface, which is fine, but AC18's wording is slightly off: it says "the handler is invoked with `event.suggestedFilename()` equal to `\"custom-name.bin\"`" — this only holds for same-origin URLs where the engine actually uses the `download` attribute. **Recommendation**: tighten AC18 to specify same-origin. **Flag for Canvas.**
- **What does "partial file is **not** left in the destination directory" (AC5) mean exactly?** Each engine has its own temp-file behaviour during a transfer. WKDownload writes to the destination path directly as the bytes arrive. WebKitGTK writes to the destination. WebView2 writes to `<path>.crdownload` until finalised. **On AC5 (failed/NETWORK)**: does WKDownload clean up its partial write? does WebView2 delete the `.crdownload`? — those are engine behaviours we don't control. **Recommendation**: weaken AC5 to "the partial file, if any, is not delivered as the completed download" (i.e. `completed` is not fired and the listener treats it as failed). Engine cleanup of stray bytes on disk is engine-dependent and not something we can guarantee uniformly. **Flag for Canvas.**
- **Suggested filename when none is provided**: story doesn't explicitly say what happens when the response has no `Content-Disposition` filename AND the URL has no path segment (e.g. `https://example.com/`). All three engines fall back to something (often `download` or `untitled.bin`). The Java side should pass through whatever the engine produces and not invent its own fallback. **Implicit but worth stating in the Canvas's Norms.**

### Edge Cases

- **Multiple simultaneous downloads from the same page** — not addressed by any AC. The native code must handle the fact that multiple `download-started` / `didBecomeDownload:` / `DownloadStarting` events can fire concurrently. Per-download tracking records must be uniquely keyed (by `WKDownload*` / `WebKitDownload*` / `CoreWebView2DownloadOperation*`). The Java handle list (story 2) likewise. **Add an AC?** Probably yes — "two concurrent downloads each fire `downloadRequested` once and produce independent `completed` events". **Flag for Canvas.**
- **Component dispose during in-flight download** — story 1's `DownloadDispatcher.disposeAll()` is mentioned implicitly. Story 2's listener fanout must handle "engine destroyed mid-transfer" — terminal `failed(CANCELLED)` (or a new `failed(DESTROYED)`?) should fire on all in-flight handles before the native side is freed. The story doesn't have an AC for this. **Flag for Canvas.**
- **Same `WebViewComponent` displayed across reload/dispose cycles** — the `WebViewComponent`'s `DownloadDispatcher` survives `dispose()` and reattach (per the pattern documented in `WebViewComponent.java:71-77`). In-flight handles from a previous engine instance — does the dispatcher carry them forward, or are they all terminal-failed at engine destroy? **Recommendation**: terminal-failed at engine destroy (engine is gone, handle can't track anything). **Flag for Canvas.**
- **`setDownloadHandler(handler)` called mid-transfer** — does the new handler get any callback about in-flight downloads? The story implies no (the handler only fires at `downloadRequested` time, which already happened for in-flight transfers). **Flag for Canvas Norms.**
- **`addListener` called from a non-EDT thread** — the story specifies callbacks fire on EDT but is silent about registration thread. By precedent (`ConsoleDispatcher.addListener` is safe from any thread because the listener list is `CopyOnWriteArrayList`), make registration any-thread-safe. **Flag for Canvas.**
- **Very fast small download** — `progress` may fire zero times if the engine writes everything in one buffer. AC1/AC2/AC3 say "at least one `progress` callback fires before `completed`" for a 4 MB download — 4 MB is large enough that this is true on every engine, but a 4 KB download (AC1 of story 1) might complete with zero progress callbacks. **Not a bug**; just worth noting that `progress` is not guaranteed for arbitrarily small downloads.
- **WebView2 on machines without the Evergreen runtime** — story acknowledges `ICoreWebView2_4` may be absent. Behaviour is "download silently drops". The Java side has no way to detect this; the only signal is "no Java callback fires for downloads on this machine". **Acceptable as a known limitation** (documented in README), but a stronger story would add an opt-in log line on first download attempt. **Flag for Canvas — minor.**
- **macOS 11.3 boundary** — same shape: silently drops; documented in README. Same flag.
- **Downloads triggered by `window.open` to a downloadable URL** — these come through a different navigation delegate selector on macOS (`createWebViewWithConfiguration:` then the new window's nav-delegate fires `didBecomeDownload:`). Our nav delegate is per-engine; downloads in a `window.open`-created window will not fire our delegate unless we also serve as the new view's delegate. **Probably out of scope** since this library doesn't currently support `window.open` popups, but worth surfacing. **Flag for Canvas Scope-Out.**

### Technical Risks

- **macOS deadlock if 480798c pattern is not followed exactly.** The `WKDownloadDelegate.download:decideDestinationUsingResponse:suggestedFilename:completionHandler:` selector runs on AppKit main and gets a completion-handler block. If we do `SwingUtilities.invokeAndWait` directly from the selector to call the default handler (which uses `~/Downloads` — no Swing UI, but the JNI hop still parks AppKit main waiting for EDT), we hit the same pattern that causes the dialog deadlock on macOS. **Mitigation**: copy the completion handler block; capture URL / MIME / filename / size on AppKit main; spawn `std::thread` for the JNI hop into `DownloadDispatcher`; `dispatch_async` back to AppKit main to invoke the completion handler. **This is non-negotiable.** Story 2's lifecycle selectors do NOT have this hazard (they have no completion handler — they're notification-only) but they still must use `invokeLater` not `invokeAndWait` to avoid blocking AppKit main.
- **Windows COM apartment violation.** `args->put_ResultFilePath` and `args->put_Cancel` must run on the WebView2 worker thread, not on a Java worker. The dialog work's `dispatch_to_thread` pattern is the only correct path. **Mitigation**: follow `ScriptDialogHandler` shape verbatim.
- **Linux `download-started` signal fires on shared `WebKitWebContext`** — as detailed in Decision 2, the handler must route to the right engine. **Mitigation**: add a Linux `WebKitWebView*` → `Engine* | OffEngine*` map (similar to the macOS `g_webview_map`). Use `webkit_download_get_web_view(download)` to identify the originating view. Skip the signal entirely (return without claiming) for downloads from WebViews this library doesn't own. **Risk if missed**: downloads from a foreign WebView in the same JVM (unlikely but possible) would invoke a random Java handler on the wrong component, possibly NPE-ing.
- **Native global ref leak on per-download handle.** Story 2 holds a `jobject` global ref for each in-flight `WebViewDownload` handle. If the native code does not release the global ref in **every** terminal path (completed, failed, cancelled, engine destroyed mid-transfer), references accumulate. **Mitigation**: a single "terminal" function in each native bridge that fires the terminal callback AND releases the global ref AND removes the tracking record from any map AND disconnects signal handlers, called from every terminal path. The macOS `Engine::clear_dialog_callback` style is the template.
- **Race between `cancel()` and natural completion.** Both story 2's `cancel()` and the engine's own `completed` event can race. The terminal-state atomic flag must be set CAS-style to ensure only one terminal callback fires. **Mitigation**: per-platform atomic terminal flag inside the per-download tracking record; the first writer wins and fires the terminal callback; the second writer no-ops.
- **macOS WKDownload availability check at runtime.** The codebase already does Objective-C runtime feature probing (see `91f673d` "macOS build: probe private WKOpenPanelParameters accessors via runtime"). The same shape for `WKDownload` and the `didBecomeDownload:` selectors — check `[WKWebView instancesRespondToSelector:@selector(navigationAction:didBecomeDownload:)]` or equivalent at engine-creation time, and only attach the navigation delegate if available. **Risk if missed**: pre-11.3 macOS link errors at engine load. **Mitigation**: respondsToSelector probes; skip the navigation-delegate attachment when unavailable. Document the result in README.
- **Demo cancel UX flakiness.** AC18 ("cancel halfway through") requires a download slow enough for the user to click Cancel. The demo's test page must serve bytes slowly (artificial delay) or use a large enough file. **Risk if missed**: a 50 MB download over a fast loopback completes in <100ms, before the user can click. **Mitigation**: demo page uses a chunked-encoded slow byte source or a 200 MB file. **Flag for Canvas-Demo.**
- **Re-implementing rather than reusing native string helpers on macOS.** `ns_string_to_utf8`, `page_url_utf8`, `frame_url_utf8` were added in 480798c. The download work should reuse them — same scenario (AppKit main captures strings before crossing thread boundary). Re-implementing risks subtle Unicode bugs.
- **WebView2 `put_ResultFilePath` requires path or URI?** The WebView2 SDK expects a wide-char Windows-style path (e.g. `C:\Users\Alice\Downloads\file.bin`). Conversion from Java `File.getAbsolutePath()` must be UTF-8 → UTF-16 (matches existing `wide_to_utf8` / `utf8_to_wide` helpers in `windows/webview_embed.cc`). **Low risk** but worth verifying during implementation.
- **WebKit `webkit_download_set_destination` requires a URI, not a path.** The path must be wrapped: `g_filename_to_uri(path, NULL, &err)`. Off by one and the engine writes to the literal string `/home/user/Downloads/file.bin` as a relative path (or fails silently). **Mitigation**: use `g_filename_to_uri`; verify error path.

### Acceptance Criteria Coverage

#### STORY-005-001 (destination-only)

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| AC1 | Default handler saves to ~/Downloads on macOS | Yes | Requires `WebviewEmbedNavigationDelegate` + `WebviewEmbedDownloadDelegate` (new ObjC classes), `DownloadDispatcher`, `WebViewDownloadHandler.DEFAULT`. Hits 480798c-style deferral pattern. |
| AC2 | Default handler on Linux heavyweight | Yes | Needs Linux `WebKitWebView` → `Engine` map. Connect `download-started` to default `WebKitWebContext` once per process or once per JVM (idempotent connect; or a process-wide connect flag). |
| AC3 | Default handler on Linux lightweight | Yes | Same code path as AC2; `WebKitWebView` → `OffEngine` half of the map. |
| AC4 | Default handler on Windows | Yes | Mirror `ScriptDialogHandler` shape for `DownloadStarting`. |
| AC5 | Filename de-duplication on first collision | Yes | Pure Java; `WebViewDownloadHandler.DEFAULT`. No platform-specific work. |
| AC6 | Filename de-duplication on second collision | Yes | Same site as AC5; iterative loop. |
| AC7 | `~/Downloads` created when missing | Yes | Same site; `mkdirs()`. |
| AC8 | Custom handler routes to chosen path | Yes | Pure Java; `DownloadDispatcher.dispatchDownload` returns the handler's chosen path; native bridge applies it. |
| AC9 | Custom handler returning null cancels | Yes | Native bridge cancels via engine primitive; story 2 layer also propagates this via the terminal-failed-not-fired observation. |
| AC10 | `setDownloadHandler(null)` cancels all | Yes | Pure Java; `DownloadDispatcher.DROP` singleton; parallel to `DialogDispatcher.DROP`. |
| AC11 | Event surfaces filename/url/mime/size | Yes | Native bridge populates `WebViewDownloadEvent` from engine getters: WKDownload `originalRequest.URL` / `originalResponse.MIMEType` / `expectedContentLength`; WebKitDownload `request` / `response`'s URI, MIME, content-length; WebView2 args `Uri` / `MimeType` / `TotalBytesToReceive`. |
| AC12 | `totalBytes == -1` when Content-Length absent | Yes | Each engine signals "unknown" with its own sentinel (`NSURLResponseUnknownLength` = -1 on macOS; `webkit_uri_response_get_content_length` returns 0 on absent — needs special handling to map 0 → -1; WebView2 `TotalBytesToReceive` returns -1 on absent). **Mild gap**: Linux maps 0-length to -1 but a legitimate 0-byte response would then look "unknown" too. Tolerable. |
| AC13 | Handler runs on EDT | Yes | `DownloadDispatcher.runOnEdtSync` (clone of `DialogDispatcher.runOnEdtVoid`). |
| AC14 | Handler exception cancels cleanly | Yes | `DownloadDispatcher` catches and forwards to default uncaught-exception handler, returns null to native side. |
| AC15 | Path-separator sanitisation | Yes | Java-side defensive sanitisation in `WebViewDownloadEvent` construction (strip `/`, `\`, leading dots). |
| AC16 | `getDownloadHandler` never null | Yes | Pure Java; `DownloadDispatcher.getHandler()` returns DEFAULT or DROP, never null. |
| AC17 | Default handler returns null at 999 collisions | Yes | Pure Java; bounded loop in `WebViewDownloadHandler.DEFAULT`. |
| AC18 | `<a download>` honoured as suggested filename | Yes (with caveat) | Engine resolves which-wins between `download` attribute and `Content-Disposition`; we surface whatever the engine produces. **Tighten AC to same-origin** as noted under Ambiguities. |
| AC19 | Demo's three modes work | Yes | Mirror `demos/WebViewDialogDemo`'s structure. Test page needs three download links. |
| AC20 | macOS <11.3 does not crash | Yes | Runtime selector probe; skip navigation-delegate install when unavailable. README documents. |

**Coverage: 20/20 addressable, 1 wording tightening needed (AC18), 1 implicit edge case to add (concurrent downloads — see Edge Cases).**

#### STORY-005-002 (lifecycle + cancel)

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| AC1 | progress fires on macOS | Yes | `WKDownloadDelegate.download:didWriteData:...:` selector. `invokeLater` to EDT. |
| AC2 | progress fires on Linux (both modes) | Yes | `WebKitDownload::received-data` signal, connected per-download after destination is set. |
| AC3 | progress fires on Windows | Yes | `CoreWebView2DownloadOperation::add_BytesReceivedChanged`. |
| AC4 | completed exactly once with final path | Yes | Per-engine terminal-state atomic flag; `WKDownloadDelegate.downloadDidFinish:`, `WebKitDownload::finished`, WebView2 `StateChanged` with state==COMPLETED. |
| AC5 | failed NETWORK on connection drop | Yes (with caveat) | Error category mapping per Decision 6. **Partial-file cleanup wording**: weaken per Ambiguities. |
| AC6 | failed IO on disk-full | Partial | Hard to test in CI (need a tmpfs to fill). May be a manual-only AC. **Flag for Canvas-Test-Plan.** |
| AC7 | cancel mid-flight fires CANCELLED | Yes | Atomic terminal flag + engine cancel primitive. |
| AC8 | cancel after completion is no-op | Yes | Atomic terminal flag's CAS write means second writer (cancel) is dropped. |
| AC9 | cancel during cancel is idempotent | Yes | Same atomic flag — first `cancel()` call wins; subsequent calls no-op at the native bridge. |
| AC10 | `downloadStarted` not called when `downloadRequested` returns null | Yes | Native bridge does not construct the handle / does not invoke `downloadStarted` when the destination is null. |
| AC11 | Listener callbacks on EDT | Yes | All listener fan-out via `invokeLater`. |
| AC12 | Listener exception isolation | Yes | Fan-out loop catches per-listener; forwards to default uncaught-exception handler; other listeners still run. Same shape as `ConsoleDispatcher.dispatch`. |
| AC13 | Add listener after terminal replays terminal | Yes | `WebViewDownload.addListener` checks terminal state; if set, schedules a single `invokeLater` to invoke the matching terminal method on the new listener. |
| AC14 | removeListener stops subsequent callbacks | Yes | CopyOnWriteArrayList iteration; modifications take effect on next event. |
| AC15 | bytesReceived monotonically increases | Yes | Engine guarantees this (every engine accumulates); the `WebViewDownload.bytesReceived` field is updated only from the EDT, and only from progress / completed callbacks. |
| AC16 | state() reflects most recent terminal | Yes | Updated on EDT inside the terminal callback before listeners are fanned out. |
| AC17 | handle.destination() matches handler return | Yes | The `File` returned by `downloadRequested` is captured into the handle's `destination` field at construction; never mutated. |
| AC18 | Demo shows progress + cancel | Yes (with caveat) | Demo needs a slow-byte test server or large file; see Risks > Demo cancel UX flakiness. |
| AC19 | Backward compatible with story 1 handlers | Yes | `downloadStarted` is a `default` method with empty body; an existing story-1-only handler compiles and runs unchanged. |

**Coverage: 19/19 addressable, 1 wording tightening (AC5 partial-file cleanup), 1 marked manual-only candidate (AC6 disk-full), 1 demo-infrastructure flag (AC18).**

#### Cross-story gaps to address before Canvas

- Concurrent downloads from the same page — no AC. **Add one to STORY-005-001's Canvas-time amendment**: "AC21: Two concurrent downloads each fire `downloadRequested` exactly once and produce independent destinations."
- Engine dispose during in-flight transfer — no AC in either story. **Add one to STORY-005-002**: "AC20: Disposing the component during an in-flight download fires `failed(CANCELLED)` on every listener and releases native references."
- `setDownloadHandler` called mid-transfer — clarify in Canvas Norms (no effect on in-flight downloads).
- Observation-without-override use case (Decision 1) — note in Canvas open questions; no AC change unless the design changes.

## Codebase Touch List Summary (for Canvas planning)

### STORY-005-001

- **New Java files**: `src/ca/weblite/webview/WebViewDownloadHandler.java`, `src/ca/weblite/webview/WebViewDownloadEvent.java`, `src/ca/weblite/webview/DownloadDispatcher.java`, `src/ca/weblite/webview/WebViewDownloadCallback.java`.
- **Modified Java files**: `src/ca/weblite/webview/swing/WebViewComponent.java` (add `downloadDispatcher` field, `setDownloadHandler` / `getDownloadHandler`), `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java` (install download callback adapter at peer-attach time; symmetric to dialog-callback install), `src/ca/weblite/webview/swing/WebViewLightweightComponent.java` (same), `src/ca/weblite/webview/EmbeddedWebView.java` (add `setDownloadCallback` and the matching native binding), `src/ca/weblite/webview/WebViewNative.java` (one new native method).
- **Modified native files**: 
  - `src_c/webview_embed.cpp` — large additions inside the macOS Cocoa block (new ObjC classes `WebviewEmbedNavigationDelegate`, `WebviewEmbedDownloadDelegate`; per-download tracking struct; new functions `fire_download_starting` / etc; bridge install in `cocoa_create_engine`), large additions inside the Linux block (per-engine `download_callback` field on `Engine` and `OffEngine`; per-context signal connect; `WebKitWebView` → engine map; bridge functions; teardown cleanup), one new exported JNI function `webview_embed_set_download_callback`.
  - `windows/webview_embed.cc` — new `DownloadStartingHandler` class mirroring `ScriptDialogHandler`; `add_DownloadStarting` registration alongside `add_ScriptDialogOpening`; per-Engine `download_callback` field; teardown cleanup.
  - `src_c/ca_weblite_webview_WebViewNative.h` and `windows/ca_weblite_webview_WebViewNative.h` — one new JNI prototype each.
- **New demo**: `demos/WebViewDownloadDemo/` (Java sources + per-platform launch scripts + a test page served from an embedded `HttpServer` similar to the existing demos).
- **Modified**: `README.md` (new "Downloads" section between "Browser-initiated dialogs" and "Demo").
- **New scripts**: `run-mac-download-demo.sh`, `run-linux-download-demo.sh`, `run-windows-download-demo.bat`.

### STORY-005-002 (deltas on top of 005-001)

- **New Java files**: `src/ca/weblite/webview/WebViewDownload.java`, `src/ca/weblite/webview/WebViewDownloadListener.java` (with nested `FailureReason`, `Category` enum).
- **Modified Java files**: `src/ca/weblite/webview/WebViewDownloadHandler.java` (add `downloadStarted` default method), `src/ca/weblite/webview/WebViewDownloadCallback.java` (add `onProgress`, `onCompleted`, `onFailed`), `src/ca/weblite/webview/DownloadDispatcher.java` (add the handle-registry, listener-fanout, cancel-from-Java machinery), `src/ca/weblite/webview/WebViewNative.java` (one new native cancel method).
- **Modified native files** — extend the same files: add the progress / terminal selectors / signals / event handlers in the same per-platform bridge sites. Add a per-download terminal-state atomic flag inside the per-download tracking record. Add a `webview_embed_cancel_download` native function plumbed through the per-download tracking record.
- **Modified demo**: extend `demos/WebViewDownloadDemo/` with a progress-bar panel + cancel button (or split into a new `demos/WebViewDownloadProgressDemo/` — the story leaves this open; recommend keeping it as one demo with mode-switching UI).
- **Modified**: `README.md` (extend the Downloads section with a "Tracking progress" subsection).

## Strategic Recommendation Summary

1. **Build both stories as siblings of the dialog channel.** Every architectural decision has a precedent in the dialog work — clone it.
2. **Adopt the 480798c deferral pattern on macOS without compromise.** It is the only deadlock-safe shape for synchronous Java callbacks driven from `WKWebView` delegate selectors.
3. **Connect Linux `download-started` to the shared default `WebKitWebContext` once per process, route to engines via a new `WebKitWebView` → engine map.** Do NOT create per-engine contexts.
4. **Create three new ObjC delegate classes on macOS** (navigation, download, per-download tracker) rather than reusing or extending `WebviewEmbedUIDelegate`.
5. **Keep `WebViewDownloadHandler` two-method as the story specifies**, but surface the "observation-without-override" gap in the Canvas open-questions list — revisit if a real use case shows up.
6. **Add three small ACs at Canvas-time amendment**: concurrent downloads (STORY-005-001), engine-dispose-during-transfer terminal (STORY-005-002), and tighten AC18 wording to same-origin and AC5 wording on partial-file cleanup.
7. **Story 1 first, story 2 second.** They share the same native callback sites, so 1 must land before 2. Don't try to do them in one Canvas — the synchronous-resolver and asynchronous-listener halves are distinct enough that a single Canvas would become hard to review.
