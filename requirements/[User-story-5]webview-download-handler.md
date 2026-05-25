# Story Decomposition: WebView Download Handler (destination + lifecycle)

## INVEST Analysis

### Abstract Task: "Let HTTP responses that the embedded browser engine classifies as downloads (rather than navigations) surface to the Swing host so the host application can pick where the file lands, observe transfer progress, and cancel a transfer mid-flight, with consistent behaviour across every supported platform/mode"

**Analysis Dimensions**:
- **Core Responsibility**: Today, when a page running inside `WebViewComponent` triggers a download — by following a link whose response carries `Content-Disposition: attachment`, or whose MIME type the engine cannot render — nothing happens. The three native engines each have their own download channel, none of which is wired through to Java; the click is silently dropped. This task gives `WebViewComponent` a single, uniform download channel so:
  - **By default**, every platform saves the file to the user's `Downloads` folder under the engine-supplied filename (de-duplicated when a file of that name already exists), mirroring the cross-platform default-Swing-dialog stance taken by `WebViewDialogHandler` (`src/ca/weblite/webview/WebViewDialogHandler.java:80`). Behaviour looks identical to the user regardless of which native backend is underneath.
  - **By override**, a Java caller can register a `WebViewDownloadHandler` that takes over and decides where the file lands — including returning a programmatic path without showing UI (for tests and for headed apps that integrate their own file picker), or returning `null` to cancel before any bytes are written.
  - **By observation (story 2)**, a registered listener receives `received-bytes` / `completed` / `failed` callbacks marshaled to the Swing EDT, and the caller can cancel a download already in flight.
- **Primary Operations**:
  1. Intercept the engine's "download starting" signal at the native layer on each backend, defer the engine's first-byte write until the Java host has supplied a destination path (or a cancel decision), and feed the answer back without freezing the page beyond the brief decision window.
  2. (Story 2 only) Surface the engine's ongoing per-download progress signals (`received-data` / `didWriteData` / `BytesReceivedChanged`) and terminal signals (`finished`/`failed` / `didFinishDownload`/`didFailWithError` / `StateChanged`) to a Java listener on the EDT; expose a `cancel()` operation that calls back through the engine's native cancellation primitive.
  3. Marshal the download request onto the Swing EDT, invoke the active `WebViewDownloadHandler` (default or caller-supplied), and pass the destination decision back to the native layer.
  4. Expose a public Java API (`WebViewDownloadHandler`, `WebViewDownloadEvent` POJO, `WebViewDownload` handle in story 2, listener interface in story 2) shared by all backends.
  5. Provide a default `WebViewDownloadHandler` implementation that saves to the user's `Downloads` folder with filename de-duplication.
- **Key Constraints**:
  - The destination decision is **synchronous from the engine's perspective**: each engine offers a "you must give me a path before I write the first byte" callback. WebKitGTK exposes this via the `WebKitDownload::decide-destination` signal; WKWebView exposes it via `WKDownloadDelegate.download(_:decideDestinationUsing:suggestedFilename:completionHandler:)`; WebView2 exposes it via the `DownloadStarting` event's deferral pattern with `put_ResultFilePath`. All three are async-safe (the engine can be told "I'll get back to you") so marshaling to the EDT and back is supported by every backend.
  - The progress signals (story 2) are **asynchronous and non-blocking** in every engine — they fire on the engine's UI thread and do not require synchronous acknowledgement. The bridge must marshal them to the EDT without blocking the engine thread.
  - The default destination — the user's `Downloads` folder — must be resolved portably. `System.getProperty("user.home") + "/Downloads"` is the cross-platform anchor; on Windows the localised "Downloads" name still maps to that path for the default user profile via the `%USERPROFILE%\Downloads` junction.
  - Filename de-duplication: when the proposed file already exists, append ` (1)`, ` (2)`, … before the extension (e.g. `report.pdf` → `report (1).pdf`). This matches Chrome / Edge / Safari behaviour and is the least-surprising default.
  - The reserved-binding convention (`__webview_` prefix) introduced by STORY-001-002 / STORY-002-001 is **not** the right channel for this — download requests originate from native browser-engine APIs, not from page-injected JS. No new reserved JS binding is introduced.
  - `WKDownload` (the modern `WKDownloadDelegate` API) is **macOS 11.3+** only. The codebase already supports macOS 11+ in heavyweight mode (per WKWebView availability); macOS earlier than 11.3 would fall back to "no download support" — `WebViewDownloadHandler.downloadRequested` simply never fires. This is documented as a known limitation in the same style README.md documents the Windows file-picker limitation in STORY-004-003.
  - On Windows, WebView2's `DownloadStarting` event is on the `ICoreWebView2_4` interface, which is available in modern Evergreen WebView2 Runtimes (the README already requires the Evergreen Runtime on older Windows, so this is consistent).
  - Behaviour must be observable from a small Swing harness; the handler-override path must be usable from a unit test (programmatic destination without showing UI) so headless test environments stay viable.
- **Technical Complexity**: Medium overall, split across three platform-shaped pieces per story:
  - macOS: implement a `WKDownloadDelegate` (currently no download path exists), wire it through `WKWebView`'s navigation delegate to convert a navigation-that-became-a-download into a `WKDownload`, and bridge the `decideDestinationUsingResponse:suggestedFilename:completionHandler:` selector to the Java handler. Story 2 adds the three progress selectors.
  - Linux: connect a handler to `WebKitWebContext::download-started`, claim the `WebKitDownload`'s `decide-destination` signal, call `webkit_download_set_destination` with the Java-supplied URI, and (story 2) connect `received-data`, `finished`, `failed` and route them to a listener.
  - Windows: register `add_DownloadStarting` on `ICoreWebView2_4`, deference its event args, call `put_ResultFilePath` with the Java-supplied path or `put_Cancel(TRUE)` on null, and (story 2) capture the returned `CoreWebView2DownloadOperation`, register `add_BytesReceivedChanged` and `add_StateChanged`, and route them to a listener. The `Cancel()` method on the `CoreWebView2DownloadOperation` powers mid-flight cancel.
- **Business Complexity**: Low — "save a file when the user clicks a download link" is a behaviour every desktop browser has had for decades, with universally-understood semantics. The work is bridging plumbing across three engines, not new business behaviour.

### INVEST Evaluation (whole feature)

- ✅ **Independent**: No story-level dependency on previously-shipped Canvases beyond the existing native-engine plumbing (canvases 6 / 7 / 2). The destination-only story (005-001) is independently shippable as a complete, useful feature: callers get downloads landing in `Downloads` by default and can redirect them. The lifecycle story (005-002) is a strict layer on top — it adds new types but does not modify the 005-001 contract.
- ✅ **Negotiable**: Specific defaults agreed with user — saves to `~/Downloads` with name de-duplication; single `WebViewDownloadHandler` per component replacing any default; null handler cancels every download (drop mode for headless tests).
- ✅ **Valuable**: Without this work, every download link in every embedded page silently fails — auth-token PDFs, exported CSVs, third-party SDK installers all do nothing when clicked, with no diagnostic visible to the page or to the host. This is the single largest known gap in the embedded-page experience after the dialog-handler family.
- ✅ **Estimable**: Each backend has a well-documented native-API surface. The Java side is one interface + one POJO + a default implementation (story 1); story 2 adds a handle class and a listener interface with three callbacks.
- ⚠️ **Small (as a single story)**: Combined, the work spans three native backends across both the destination decision and the lifecycle observation — realistically 7-9 days. **This exceeds the 1-5 day INVEST sizing target.** Splitting is required (see below).
- ✅ **Testable**: Default behaviour is observable from a Swing harness (file appears in `~/Downloads`); override behaviour is observable from a unit test by registering a programmatic handler that writes to a temp directory. Story 2's listener events are observable by counting `received-bytes` callbacks and asserting on terminal state.

**Conclusion**: Needs splitting along **lifecycle phases**. Story 005-001 ships the full destination-decision contract across all three platforms — the smallest unit that delivers a working download. Story 005-002 layers progress / completion / cancel observation on top.

### Split Strategy

Split by **lifecycle phase / API surface**, because:

- The destination-decision phase is **synchronous-shaped** (engine waits for a path) and the observation phase is **asynchronous-shaped** (engine emits events). Each phase is one coherent slice on every engine — wiring "download starting → ask Java → set destination" is a single small piece of work per backend, well isolated from "every N bytes → fire EDT listener".
- Story 005-001 alone is **already useful in production**: downloads land in `~/Downloads` by default, callers can redirect them, callers can cancel them before they start. Many embedding apps will not need anything more. Shipping this slice first delivers value sooner.
- Story 005-002's API surface (`WebViewDownload` handle, `WebViewDownloadListener` interface, `cancel()` method) is additive — it does not modify the 005-001 `WebViewDownloadHandler.downloadRequested` signature, it only adds new types and a new listener registration on `WebViewComponent`. This means 005-002 can be developed and merged without breaking 005-001's contract or its tests.
- Each story spans all three platforms intentionally: splitting by platform within each lifecycle phase would create six tiny stories where most of the cost is the shared Java-side scaffolding done once. The dialog feature (STORY-004-*) split by platform because **the Java contract had to be designed alongside the first platform** (macOS) since macOS was the only platform where dialogs were fully broken; here the Java contract is small enough and well-enough understood that designing and shipping it across all three engines in one story is sound.
- Each story is 4-5 days, at the upper edge of INVEST sizing but within bounds.

Story dependency graph:

```
STORY-005-001 (WebViewDownloadHandler API + destination routing on all 3 platforms)
        │
        └──► STORY-005-002 (WebViewDownload handle + progress / completion / cancel) — depends on the Java contract and native callback sites from 005-001
```

STORY-005-002 cannot be started until STORY-005-001 has landed — its work is layered on the same native callback sites.

---

## [STORY-005-001] WebViewDownloadHandler API and Destination Routing (macOS + Linux + Windows)

### Background

A page running inside `WebViewComponent` can produce a download by following a link whose HTTP response carries `Content-Disposition: attachment`, or whose `Content-Type` the engine cannot render inline (`application/pdf` on Linux WebKitGTK by default, `application/octet-stream`, `.zip` archives, installer binaries, exported CSVs, etc.). When that happens **today**, every supported platform behaves the same way: the click is silently dropped. The page's `click` handler fires, the navigation begins, the engine recognises the response as a download, and then nothing — no file appears on disk, no JS-side error, no Java-side callback, no log line. The host application has no way to participate.

The three engines each expose a "download starting" callback that the application can attach to:

- **WebKitGTK**: `WebKitWebContext::download-started` fires with a `WebKitDownload` object as soon as the engine has classified a response as a download. The application can then connect to `WebKitDownload::decide-destination` and call `webkit_download_set_destination(download, uri)`. Returning `TRUE` from `decide-destination` claims the decision.
- **WKWebView** (macOS 11.3+): the `WKNavigationDelegate` selectors `webView:navigationAction:didBecomeDownload:` and `webView:navigationResponse:didBecomeDownload:` deliver a `WKDownload`. The application then implements `WKDownloadDelegate.download(_:decideDestinationUsing:suggestedFilename:completionHandler:)` and invokes the supplied completion handler with a `URL` for the destination (or `nil` to cancel).
- **WebView2** (`ICoreWebView2_4`): `add_DownloadStarting(handler, &token)` fires with `ICoreWebView2DownloadStartingEventArgs *`. The application calls `GetDeferral`, then either `put_ResultFilePath(path)` + `Complete()` or `put_Cancel(TRUE)` + `Complete()`.

This story does three things:

1. **Designs and exposes the cross-platform Java contract** for browser-initiated downloads — one `WebViewDownloadHandler` interface with a single `default` method that saves to `~/Downloads`, one immutable `WebViewDownloadEvent` POJO, and a setter on `WebViewComponent`. The same contract is the one STORY-005-002 will conform to and extend.
2. **Implements that contract on all three platforms in one slice**, because the destination-decision surface is small enough to wire on each engine in roughly a day. The Java contract was designed in this story; STORY-005-002 layers progress observation on top of the same native callback sites.
3. **Provides a default destination policy** that matches user expectations on every desktop OS: save to `~/Downloads` (the cross-platform anchor on Windows / macOS / Linux for the standard user profile) under the engine-supplied filename, with `(1)` / `(2)` / … de-duplication when a file of that name already exists.

The chosen design — handler override defaulting to `~/Downloads` — was selected for these reasons:

- A `~/Downloads` default is **portable**: every desktop OS this library targets has such a folder by default, accessible via `System.getProperty("user.home") + "/Downloads"`. If the folder does not exist (atypical), it is created.
- A single-handler model (`setDownloadHandler(handler)` replacing the default) keeps the call site simple. Callers either accept the default or supply their own implementation. This mirrors `WebViewDialogHandler` exactly.
- The handler is **per-component-instance**. Two `WebViewComponent`s in the same JVM can have different handlers (or share one).

Key points:
- Business value: every desktop app embedding a WebView needs basic download support to host real-world content. All three platforms are currently broken (silent failure), so this is high-leverage.
- Relationship with other features: orthogonal to dialog handling (STORY-004-*) and to console capture (STORY-001-*). The reserved `__webview_` binding prefix is not used — these download requests come from native browser-engine callbacks, not page-injected JS. Internally, this story introduces a `DownloadDispatcher` Java class that parallels the existing `DialogDispatcher` (`src/ca/weblite/webview/DialogDispatcher.java`).
- Why now: closing the dialog-handler family in STORY-004 left downloads as the only remaining major page-initiated channel not bridged to the host. A user just reported the link-click failure mode, confirming the gap is hit in practice.

### Business Value

- Provide **working downloads** in `WebViewComponent` across every supported platform, replacing the current silent-failure behaviour for `Content-Disposition: attachment` and non-renderable MIME types.
- Provide a **single Java contract** (`WebViewDownloadHandler`) that mirrors `WebViewDialogHandler` in shape and behaviour, so callers who have already learned the dialog pattern get downloads for free.
- Provide a **default `~/Downloads` policy** out of the box, so callers who do nothing get the user-expected desktop-browser behaviour without writing any code.
- Provide a **deterministic override hook** so apps with their own file pickers (or with custom destination routing, e.g. "save attachments into the current workspace folder") can take over.
- Provide a **deterministic cancel hook** (handler returns `null`) so headless tests, kiosk modes, and apps that want to refuse downloads can do so without any UI.

### Dependencies and Assumptions

- **Prerequisites**: Canvases 5, 6, 7 (mode selection, heavyweight, lightweight) in place. macOS heavyweight already creates a `WKWebView` and attaches a `WKScriptMessageHandler` (STORY-001-*) and a `WKUIDelegate` (STORY-004-001) — this story adds a `WKNavigationDelegate` hook (or augments the existing one) plus a `WKDownloadDelegate` on the `WKDownload` produced by navigation. Linux heavyweight + lightweight already share a `WebKitWebContext` per engine — this story connects one signal to it. Windows heavyweight already obtains `ICoreWebView2Settings` at engine creation (STORY-004-003) — this story queries the `ICoreWebView2_4` interface from the same `webview` object and adds one event handler.
- **Data assumptions**: No persisted state. The active handler is a per-component-instance reference. Default handler is a stateless `~/Downloads` writer. The `~/Downloads` folder is auto-created if missing.
- **Integration points**:
  - macOS: `WKNavigationDelegate` selectors `webView:navigationAction:didBecomeDownload:` and `webView:navigationResponse:didBecomeDownload:` (introduced in macOS 11.3); `WKDownloadDelegate.download:decideDestinationUsingResponse:suggestedFilename:completionHandler:`. The codebase already attaches a navigation delegate channel — this story extends it.
  - Linux: `WebKitWebContext::download-started` signal on the engine-shared `WebKitWebContext`; `WebKitDownload::decide-destination` signal on the per-download object. Functions used: `webkit_download_get_request`, `webkit_uri_request_get_uri`, `webkit_download_get_response`, `webkit_uri_response_get_suggested_filename`, `webkit_uri_response_get_mime_type`, `webkit_uri_response_get_content_length`, `webkit_download_set_destination`, `webkit_download_cancel`. Connection site: the existing engine context-creation path in `src_c/webview_embed.cpp`.
  - Windows: `ICoreWebView2_4::add_DownloadStarting`, `ICoreWebView2DownloadStartingEventArgs::GetDeferral` / `get_DownloadOperation` (for the `Uri`, `MimeType`, `TotalBytesToReceive`, `ContentDisposition`) / `put_ResultFilePath` / `put_Cancel`. Connection site: alongside the existing `ScriptDialogOpening` registration from STORY-004-003 in `windows/webview_embed.cc`.
- **Business constraints**:
  - The destination decision is **engine-synchronous** in the sense that no bytes are written until the engine has been given a path. The bridging must not deadlock the engine UI thread by waiting on the EDT while the EDT is waiting on the engine — same constraint pattern as `DialogDispatcher`.
  - The default destination is `${user.home}/Downloads/${suggestedFilename}` with de-duplication: if `report.pdf` exists, try `report (1).pdf`; if that exists, `report (2).pdf`; up to a sanity ceiling (e.g. `report (999).pdf`) beyond which the handler treats it as a failure and returns `null` (cancel). The probe is performed on the EDT inside the default handler's body — no async work.
  - Suggested filename comes from the engine: WebKitGTK reads it from `Content-Disposition` filename, falling back to the URL path's last segment; WKDownload provides it directly; WebView2 reads it from `Content-Disposition`. All three sanitise platform-illegal characters before handing it to us. The Java side performs one additional sanitisation pass — strip path separators (`/` and `\`) and any leading dots — so that a malicious server cannot direct a write to `../etc/passwd` even if the engine missed it.
  - If `~/Downloads` does not exist, the default handler creates it (mode 0700 on POSIX; default ACLs on Windows). If creation fails, the default handler returns `null` (cancel) and the failure is surfaced to the default uncaught-exception handler.
  - The drop-handler mode (`setDownloadHandler(null)`) returns `null` from `downloadRequested` for every download. This is the documented signal for "cancel before any bytes are written" — no temp file, no log.

### Scope In

- New public interface `ca.weblite.webview.WebViewDownloadHandler` with one `default` method, invoked on the Swing EDT:
  - `default java.io.File downloadRequested(WebViewDownloadEvent event)` — default impl: returns `${user.home}/Downloads/${dedupedFilename}`, creating `~/Downloads` if absent. Filename de-duplication appends ` (N)` before the last `.`-segment.
  - Returning `null` cancels the download (the engine is told to abort; no bytes are written).
- A static `WebViewDownloadHandler.DEFAULT` instance: stateless, safe to share, returned by `getDownloadHandler()` when no caller has installed a custom handler. Mirrors `WebViewDialogHandler.DEFAULT`.
- New public POJO `ca.weblite.webview.WebViewDownloadEvent`, immutable with public accessors:
  - `WebViewComponent source()` — the component the download originated in.
  - `String suggestedFilename()` — engine-supplied filename, sanitised of path separators and leading dots.
  - `String sourceUrl()` — the URL the response was fetched from.
  - `String mimeType()` — engine-supplied MIME type, or empty string if the engine does not provide one.
  - `long totalBytes()` — engine-supplied content length, or `-1` when unknown (chunked transfer encoding, missing header).
- New public methods on `WebViewComponent`:
  - `WebViewComponent setDownloadHandler(WebViewDownloadHandler handler)` — replaces the active handler. Passing `null` installs a "drop" handler whose `downloadRequested` returns `null` synchronously without UI. Passing a non-null handler installs that handler; un-overridden methods (there is only one here, but the shape is forward-compatible with STORY-005-002 additions) fall through to the interface default.
  - `WebViewDownloadHandler getDownloadHandler()` — returns the currently installed handler. Never returns `null`; the framework `DEFAULT` instance is returned when no caller has set one.
- Internal class `ca.weblite.webview.DownloadDispatcher` (package-private), parallel to `DialogDispatcher`. It is invoked from JNI / native callbacks on the engine UI thread; it marshals to the EDT via `SwingUtilities.invokeAndWait`, calls the active `WebViewDownloadHandler.downloadRequested`, captures the return value (a `File` or `null`), converts to the appropriate native representation (file URI on macOS / Linux, file path string on Windows; empty / null marker for cancel), and returns it to native. Exceptions thrown from the handler are caught, forwarded to `Thread.getDefaultUncaughtExceptionHandler()`, and a cancel decision is returned to the native side.
- macOS heavyweight implementation:
  - Augment the existing navigation-delegate hook (or attach one if the engine does not yet have one) to implement `webView:navigationAction:didBecomeDownload:` and `webView:navigationResponse:didBecomeDownload:`. Both set the new `WKDownload`'s delegate to an Objective-C class implementing `WKDownloadDelegate`.
  - The delegate's `download:decideDestinationUsingResponse:suggestedFilename:completionHandler:` selector reads the response (URL, MIME, expected content length), populates a `WebViewDownloadEvent`, marshals to `DownloadDispatcher`, and invokes the completion handler with `[NSURL fileURLWithPath:javaDestination]` on success or `nil` on cancel.
  - Cleanup: the `WKDownloadDelegate` is released when the engine is destroyed.
  - **Availability gate**: the `didBecomeDownload:` selectors and `WKDownload` are macOS 11.3+. On macOS earlier than 11.3, the navigation delegate falls back to the pre-`WKDownload` behaviour (`decidePolicyForNavigationResponse:` cancels the navigation, no download handler is fired). This is documented as a known platform limitation in README.md and in the Javadoc for `WebViewDownloadHandler`.
- Linux heavyweight + lightweight implementation:
  - In the engine creation path in `src_c/webview_embed.cpp`, connect a `download-started` signal handler to the `WebKitWebContext` shared by the engine. The handler:
    1. Refs the `WebKitDownload`.
    2. Connects a `decide-destination` handler that reads the response (`webkit_download_get_response` → URI, MIME, content-length, suggested-filename), populates a `WebViewDownloadEvent`, calls `DownloadDispatcher` via the existing JNI bridge, and on return either calls `webkit_download_set_destination(download, fileUri)` (returning `TRUE`) or `webkit_download_cancel(download)` (returning `FALSE`).
  - Shared between heavyweight and lightweight modes — the signal fires on the same `WebKitWebContext` regardless of which window the WebView lives in.
- Windows implementation:
  - In `windows/webview_embed.cc`, after acquiring the `ICoreWebView2`, query for the `ICoreWebView2_4` interface. Register an `ICoreWebView2DownloadStartingEventHandler` via `add_DownloadStarting`. The handler:
    1. Reads `Uri`, `MimeType`, `TotalBytesToReceive`, `ContentDisposition`, and the operation's filename (via `get_DownloadOperation` then `get_ResultFilePath` to seed the default).
    2. Calls `GetDeferral` and stashes the deferral pointer.
    3. Routes to `DownloadDispatcher` on the EDT, captures the return path.
    4. On non-null return: `args->put_ResultFilePath(path)`. On null return: `args->put_Cancel(TRUE)`. Then `deferral->Complete()`.
  - **Interface availability**: if `ICoreWebView2_4` is not present (very old WebView2 Runtime), the registration is skipped and downloads continue to drop silently — documented as a known limitation. The README's existing Evergreen-Runtime guidance covers the supported configuration.
- Demo: `demos/WebViewDownloadDemo/` mirroring `demos/WebViewDialogDemo/` — three modes selectable by radio button:
  - **Default**: no handler set; downloads land in `~/Downloads`.
  - **Custom**: handler routes to a temp directory selected by `JFileChooser` at app start and logs each download's source URL + chosen destination to a `JTextArea`.
  - **Drop**: `setDownloadHandler(null)` — all downloads are cancelled; the log records the cancel decision.
- README.md updated with a new "Downloads" section between "Browser-initiated dialogs" and "Demo", documenting the contract, the default policy, and the platform caveats (macOS 11.3+, Windows requires modern WebView2 Runtime).

### Scope Out

- Progress callbacks (`received-bytes`), completion / failure callbacks, and mid-flight cancel — STORY-005-002.
- The `WebViewDownload` handle class — STORY-005-002.
- A `WebViewDownloadListener` interface — STORY-005-002.
- The standalone in-process `WebView` class (`src/ca/weblite/webview/WebView.java`) — this story is for embedded `WebViewComponent` only. Adding the same API to standalone `WebView` is a follow-up if a user asks for it.
- HTTP basic / digest authentication challenges during download — different delegate channel, out of scope.
- Resumable downloads (resume data via `WKDownload.cancel(producingResumeData:)`, persistent across app restarts) — out of scope; cancel is fire-and-forget in story 1.
- `<a download="custom.pdf">` HTML `download` attribute handling — this is delivered by every engine as the suggested filename of the resulting download, so it falls out automatically; no special-case code is needed beyond honouring the engine-supplied suggested filename.
- Per-MIME or per-origin download policy gating in the engine (e.g. "only allow `application/pdf`"). The Java handler can implement this itself by inspecting `event.mimeType()` and returning `null` for refused types — no engine-level policy is required.
- Cross-origin / CORS validation of downloads — the engines already enforce their own; this story does not duplicate or override that.
- Bridging `<input type="file">` saves (e.g. `showSaveFilePicker` from the File System Access API) — different code path, out of scope.
- Telemetry / diagnostic logging of download events to stderr — handlers that want to log do so themselves.
- macOS earlier than 11.3 — no `WKDownload` API; documented limitation.

### Acceptance Criteria

#### AC1: Default handler saves a download to ~/Downloads on macOS
**Given** a `WebViewHeavyweightComponent` running on macOS 11.3 or later, attached to a visible `JFrame`, with no custom `WebViewDownloadHandler` set,
**When** the loaded page initiates a download of a 4 KB file `sample.txt` whose response carries `Content-Disposition: attachment; filename="sample.txt"`,
**Then** after the download completes the file `${user.home}/Downloads/sample.txt` exists, has the expected 4 KB length, and no Swing or native dialog appeared during the transfer.

#### AC2: Default handler saves a download to ~/Downloads on Linux (heavyweight)
**Given** a `WebViewHeavyweightComponent` on Linux with no custom handler,
**When** the loaded page initiates the same `sample.txt` download as AC1,
**Then** `${user.home}/Downloads/sample.txt` exists with the expected 4 KB length and no GTK file dialog appeared.

#### AC3: Default handler saves a download to ~/Downloads on Linux (lightweight)
**Given** a `WebViewLightweightComponent` on Linux with no custom handler,
**When** the loaded page initiates the same `sample.txt` download as AC1,
**Then** `${user.home}/Downloads/sample.txt` exists with the expected 4 KB length and no GTK file dialog appeared.

#### AC4: Default handler saves a download to ~/Downloads on Windows
**Given** a `WebViewHeavyweightComponent` on Windows 11 with the modern Evergreen WebView2 Runtime and no custom handler,
**When** the loaded page initiates the same `sample.txt` download as AC1,
**Then** `${USERPROFILE}\Downloads\sample.txt` exists with the expected 4 KB length and no WebView2 download UI (the inline browser download bar) appeared.

#### AC5: Default handler de-duplicates filenames
**Given** a `WebViewComponent` on any supported platform with the default handler and a pre-existing file `${user.home}/Downloads/report.pdf`,
**When** the page initiates a download whose suggested filename is `report.pdf`,
**Then** the new file is saved as `${user.home}/Downloads/report (1).pdf` and the pre-existing `report.pdf` is unchanged.

#### AC6: Default handler de-duplicates again on the second collision
**Given** the same setup as AC5 but with both `report.pdf` and `report (1).pdf` already present,
**When** the page initiates another `report.pdf` download,
**Then** the new file is saved as `${user.home}/Downloads/report (2).pdf`.

#### AC7: Default handler creates ~/Downloads when missing
**Given** a `WebViewComponent` with the default handler, run in a fresh user profile where `${user.home}/Downloads` does not exist,
**When** the page initiates a `sample.txt` download,
**Then** the directory `${user.home}/Downloads` is created and the file is saved inside it.

#### AC8: Custom handler routes the download to a chosen path
**Given** a `WebViewComponent` on any supported platform with `setDownloadHandler(event -> new File("/tmp/dl/" + event.suggestedFilename()))` set, and `/tmp/dl/` already existing as an empty directory,
**When** the page initiates a `sample.txt` download,
**Then** `/tmp/dl/sample.txt` exists with the expected content and `${user.home}/Downloads/sample.txt` is unchanged (was not written).

#### AC9: Custom handler returning null cancels the download before any bytes are written
**Given** a `WebViewComponent` on any supported platform with a custom handler whose `downloadRequested` returns `null` and records the event in a list,
**When** the page initiates a `sample.txt` download,
**Then** the handler is invoked exactly once with `event.suggestedFilename()` equal to `"sample.txt"`, no file appears in `${user.home}/Downloads`, no temporary or partial file is left behind anywhere, and the page's JS thread continues without hanging.

#### AC10: setDownloadHandler(null) cancels every download
**Given** a `WebViewComponent` on any supported platform with `setDownloadHandler(null)` installed,
**When** the page initiates three downloads in succession,
**Then** none of the three files appears on disk and the page's JS thread continues without hanging on any of the three.

#### AC11: WebViewDownloadEvent surfaces the suggested filename, source URL, MIME type, and size
**Given** a `WebViewComponent` on any supported platform with a custom handler that records all four `WebViewDownloadEvent` fields,
**When** the page initiates a download of `https://example.com/files/sample.pdf` whose response carries `Content-Type: application/pdf`, `Content-Length: 12345`, and `Content-Disposition: attachment; filename="sample.pdf"`,
**Then** the handler is invoked with `suggestedFilename()` `"sample.pdf"`, `sourceUrl()` `"https://example.com/files/sample.pdf"`, `mimeType()` `"application/pdf"`, and `totalBytes()` `12345`.

#### AC12: totalBytes is -1 when the server omits Content-Length
**Given** the same setup as AC11 but the server uses chunked transfer encoding (no `Content-Length` header),
**When** the download begins,
**Then** `event.totalBytes()` is `-1`.

#### AC13: Handler runs on the Swing EDT
**Given** a `WebViewComponent` on any supported platform with a custom handler that records `SwingUtilities.isEventDispatchThread()` and returns a fixed path,
**When** the page initiates a download,
**Then** the recorded value is `true`.

#### AC14: Handler exception cancels the download cleanly
**Given** a `WebViewComponent` on any supported platform with a custom handler whose `downloadRequested` throws a `RuntimeException`,
**When** the page initiates a download,
**Then** the exception is surfaced via `Thread.getDefaultUncaughtExceptionHandler()`, the download is cancelled (no file written), and the WebView remains responsive (a subsequent page navigation completes normally within 5 seconds).

#### AC15: Path-separator characters in suggested filename are sanitised
**Given** a `WebViewComponent` on any supported platform with the default handler and a server that sends `Content-Disposition: attachment; filename="../../etc/passwd"`,
**When** the page initiates the download,
**Then** no file is written outside `${user.home}/Downloads/`. The actual filename used inside `~/Downloads/` does not contain `/` or `\` (the exact sanitised form is engine-dependent; what matters is that no path-escape occurred).

#### AC16: getDownloadHandler never returns null
**Given** a freshly constructed `WebViewComponent` with no handler explicitly set,
**When** the caller invokes `getDownloadHandler()`,
**Then** the return value is non-null and refers to the framework's `DEFAULT` handler instance.

#### AC17: Default handler returns null when 999 collisions are exhausted
**Given** a `WebViewComponent` with the default handler and a `~/Downloads` directory pre-populated with `report.pdf`, `report (1).pdf`, …, `report (999).pdf`,
**When** the page initiates another `report.pdf` download,
**Then** the default handler treats this as a failure to find a non-colliding name, returns `null`, and the download is cancelled with no file written.

#### AC18: HTML download attribute is honoured as the suggested filename
**Given** a `WebViewComponent` on any supported platform with a handler that records `event.suggestedFilename()`,
**When** the page contains `<a href="/bytes" download="custom-name.bin">d</a>` and the user clicks it,
**Then** the handler is invoked with `event.suggestedFilename()` equal to `"custom-name.bin"`.

#### AC19: The demo's three modes produce the documented outcomes
**Given** `demos/WebViewDownloadDemo/` running on any supported platform with a test page that has three download links,
**When** the user selects "Default" and clicks all three links,
**Then** all three files appear under `~/Downloads/`.
**And When** the user selects "Custom" and clicks all three links,
**Then** all three files appear under the temp directory chosen at app start, and the log shows three lines each with the source URL and chosen destination.
**And When** the user selects "Drop" and clicks all three links,
**Then** no files appear anywhere on disk and the log shows three cancellation entries.

#### AC20: macOS earlier than 11.3 documents the limitation without crashing
**Given** a `WebViewHeavyweightComponent` running on macOS earlier than 11.3 (where `WKDownload` is unavailable) with the default handler,
**When** the page initiates a download,
**Then** the JVM does not crash, no file is written, and the documented behaviour is observed (download is dropped). The README's Downloads section documents this limitation explicitly.

### Non-Functional Expectations

- The bridging from each engine's UI thread to the Swing EDT must not deadlock. The engine UI thread is parked between "download-started" and "destination decision" — for the duration of that brief window the EDT must remain free to run the handler. The synchronisation primitive used to wait for the EDT's reply must not block the EDT itself (same constraint shape as `DialogDispatcher`).
- The default handler's filename de-duplication probe must complete fast enough on the EDT not to be perceptible (a few milliseconds for typical `~/Downloads` sizes). The implementation may stat-test up to the documented 999-collision ceiling without hitting that ceiling in any realistic case.
- The handler-override path must be usable from a unit test in a headless environment — registering a programmatic handler that returns a fixed path must not cause Swing to instantiate any `Window`.
- Path sanitisation must defend against engine bugs that leak path separators into `suggestedFilename`, even though every engine claims to sanitise on its own. The Java side does an additional defensive pass.
- The native code must not leak the `WKDownload` / `WebKitDownload` / `CoreWebView2DownloadOperation` reference after the handler returns — the engine owns the object for the lifetime of the transfer; story 1 only borrows it during the destination decision and releases it when the engine's terminal signal fires (or, in cancel cases, when the engine acknowledges the cancel).
- No native or page-side hang on cancel — when the handler returns `null`, the cancellation must complete on the engine's UI thread within at most a few hundred milliseconds; the page's `error` event for the originating link fires and JS continues.

---

## [STORY-005-002] WebView Download Lifecycle: Progress, Completion, and Mid-Flight Cancel

### Background

STORY-005-001 ships the destination-decision contract: a download fires `WebViewDownloadHandler.downloadRequested`, the host returns a destination (or `null` to cancel), and the engine writes the bytes. The host learns where the file landed before the transfer starts but learns nothing during or after.

This story layers **observation** and **mid-flight control** on top:

- **Observation**: after the destination is chosen, the host can register a `WebViewDownloadListener` that receives `progress` (bytes received so far), `completed` (download finished successfully, on-disk path is final), and `failed` (download failed; engine-supplied error message and cancelled / network / IO category). All three callbacks fire on the Swing EDT.
- **Control**: the host can `cancel()` a download in flight. Cancel takes effect at the engine's next opportunity (typically within a few packets); subsequent progress events stop firing and a single `failed` event with `Category.CANCELLED` fires to close the listener lifecycle.

The shape change to `WebViewDownloadHandler` is **strictly additive** — the existing `downloadRequested` signature still returns a `File` (or `null`); no caller written against STORY-005-001 needs to change. Story 2 adds a second pathway: callers who want progress install a `WebViewDownloadListener` on a `WebViewDownload` handle. The handle is obtained by overriding a new second method on `WebViewDownloadHandler` (or by calling a new `WebViewComponent.addDownloadListener` channel for "I want every download tracked" use cases — see Scope In for the chosen shape).

All three engines emit progress signals natively:

- **WebKitGTK**: `WebKitDownload::received-data` (fires per buffer; `gsize` of bytes just received), `WebKitDownload::finished`, `WebKitDownload::failed` (`GError *` + `WebKitDownloadError` enum: `CANCELLED_BY_USER` / `DESTINATION` / `NETWORK`). Cancel: `webkit_download_cancel(download)`.
- **WKDownload** (`WKDownloadDelegate`): `download:didWriteData:totalBytesWritten:totalBytesExpectedToWrite:`, `downloadDidFinish:`, `download:didFailWithError:resumeData:`. Cancel: `[download cancel:^(NSData *resumeData) { ... }]` (we discard the resume data — story 1's no-resume scope remains).
- **WebView2** (`CoreWebView2DownloadOperation`): `add_BytesReceivedChanged` (read `get_BytesReceived` for the current total), `add_StateChanged` (read `get_State` for `INPROGRESS` / `COMPLETED` / `INTERRUPTED`; on `INTERRUPTED` read `get_InterruptReason` for the error category). Cancel: `downloadOperation->Cancel()`.

Key points:
- Business value: a typical use case is showing a small progress bar in the host app's status area for each active download, with a "Cancel" button. Without this story, the host has no way to render either piece of UI.
- Relationship with other features: the Java side reuses the `DownloadDispatcher` introduced in STORY-005-001; native code adds three more callback hooks per engine on the same per-download object the destination-decision callback already ran on.
- Why now: the destination contract from STORY-005-001 is already in callers' hands; lifecycle is the next-most-asked-for piece and the natural next slice.

### Business Value

- Provide **per-download progress reporting** to the host app, enabling progress bars, byte-rate displays, and ETAs.
- Provide **terminal-state reporting** (completed / failed-with-reason) so the host can show a "Download complete — open?" notification or a "Download failed — retry?" alert.
- Provide **mid-flight cancel** so the host can offer a Cancel button alongside an in-progress download, and so the host can abort a long download when the user navigates away or closes the window.
- Provide a **listener model** that mirrors the rest of the Swing-centric API: callbacks on the EDT, exception isolation, same threading guarantees as `WebViewDialogHandler` and `WebViewDownloadHandler` from STORY-005-001.

### Dependencies and Assumptions

- **Prerequisites**: STORY-005-001 must be complete. The `WebViewDownloadHandler` interface, the `WebViewDownloadEvent` POJO, the `setDownloadHandler` / `getDownloadHandler` methods, the `DownloadDispatcher` internal class, and the three platforms' native "download-starting" callback wiring must all be in place.
- **Data assumptions**: No persisted state. Each `WebViewDownload` handle's lifetime is bound to the underlying engine download — the engine owns it; the Java handle is a thin wrapper that holds a native pointer and unsubscribes its listener when the terminal callback fires. Cancelled, completed, and failed all release the underlying native resources.
- **Integration points**:
  - macOS: `WKDownloadDelegate.download:didWriteData:totalBytesWritten:totalBytesExpectedToWrite:`, `downloadDidFinish:`, `download:didFailWithError:resumeData:`, `[WKDownload cancel:]`.
  - Linux: `WebKitDownload::received-data`, `WebKitDownload::finished`, `WebKitDownload::failed`, `webkit_download_cancel`. The `g_signal_connect` calls are added in the same code site as STORY-005-001's `decide-destination` connection.
  - Windows: `ICoreWebView2DownloadOperation::add_BytesReceivedChanged`, `add_StateChanged`, `Cancel()`. The `CoreWebView2DownloadOperation` is obtained from STORY-005-001's `ICoreWebView2DownloadStartingEventArgs::get_DownloadOperation` before the deferral is completed.
- **Business constraints**:
  - Listener callbacks **must not block** the engine UI thread. They are marshaled to the EDT and dispatched via `SwingUtilities.invokeLater` (not `invokeAndWait`) — there is no engine-side waiting for a listener to return. Listener exceptions go to `Thread.getDefaultUncaughtExceptionHandler()` and do not propagate back to the engine.
  - Progress callback **firing rate**: every engine emits a notification roughly per buffer (8-64 KB depending on platform / TCP segment size). For very large or very fast downloads this can be hundreds of events per second. The bridge does not coalesce — each native event becomes one EDT runnable — so listeners that update Swing components must do bounded work per callback (a `progress.setValue(...)` is fine; a layout-triggering operation is the listener's responsibility to throttle).
  - Terminal callbacks (`completed` / `failed`) fire **exactly once** per download. After a terminal callback, the `WebViewDownload` handle is in its terminal state, no further callbacks fire, and calling `cancel()` is a no-op.
  - `cancel()` is **asynchronous** — the call returns immediately; the underlying download is asked to stop. The engine emits a `failed` event with the `CANCELLED` category once it has actually stopped (typically a few milliseconds, but the API does not guarantee a hard bound). If `cancel()` is called after the download has already completed, it is a no-op and no spurious `failed` event fires.
  - The `WebViewDownload.state()` accessor reflects the most recent state callback observed on the EDT — it is safe to read from the EDT but stale from other threads. Calling code is documented as "read state from inside a listener callback, or from the EDT after a known transition".
  - The `failed` callback's error category: `CANCELLED` (user cancel), `NETWORK` (connection lost, DNS failure, TLS error), `IO` (disk full, permission denied), `UNKNOWN` (engine-specific category that doesn't map). The native code maps each engine's error code namespace to this small enum.

### Scope In

- New public class `ca.weblite.webview.WebViewDownload` — a Java handle representing one active or recently-terminated download. Methods:
  - `WebViewComponent source()` — the originating component.
  - `WebViewDownloadEvent event()` — the original event the handler saw.
  - `File destination()` — the final on-disk destination (the same `File` the handler returned).
  - `long bytesReceived()` — most-recent observed byte count (EDT-safe accessor).
  - `long totalBytes()` — same as `event().totalBytes()`; `-1` when unknown.
  - `State state()` — enum `IN_PROGRESS` / `COMPLETED` / `FAILED` / `CANCELLED`.
  - `void cancel()` — asynchronous cancel; no-op if the download is already in a terminal state.
  - `void addListener(WebViewDownloadListener listener)` — register a listener. Multiple listeners are supported. Adding a listener after a terminal callback has fired immediately invokes the terminal callback on the EDT before returning (so callers can register late without missing the terminal event).
  - `void removeListener(WebViewDownloadListener listener)` — deregister.
- New public interface `ca.weblite.webview.WebViewDownloadListener` with three `default` methods (no-ops), all invoked on the Swing EDT:
  - `default void progress(WebViewDownload download)` — fired on each native `received-data` / `didWriteData` / `BytesReceivedChanged` event.
  - `default void completed(WebViewDownload download)` — fired exactly once on successful completion. `download.destination()` is now final, with all bytes written.
  - `default void failed(WebViewDownload download, FailureReason reason)` — fired exactly once on failure or cancel. `reason.category()` is one of `CANCELLED` / `NETWORK` / `IO` / `UNKNOWN`; `reason.message()` is an engine-supplied human-readable string (or empty when unavailable).
- New public class `ca.weblite.webview.WebViewDownloadListener.FailureReason` — small immutable POJO with `Category category()` and `String message()`. `Category` is a nested enum.
- New public default method on `WebViewDownloadHandler` for receiving the handle: `default void downloadStarted(WebViewDownload download)`. The default impl is empty. Callers that want to track progress override this method, call `download.addListener(...)`, and stash the handle. The existing `downloadRequested` is called first to choose the destination; if it returns non-null, `downloadStarted` is then called with the handle. If `downloadRequested` returned `null`, `downloadStarted` is **not** called (cancelled before the handle existed).
- macOS implementation:
  - Extend the `WKDownloadDelegate` from STORY-005-001 to implement the three lifecycle selectors. Each marshals to the EDT, updates the `WebViewDownload` handle's bytesReceived field (for progress) or state field (for terminal), and invokes the corresponding listener method on each registered listener.
  - `[WKDownload cancel:]` is invoked from the Java `cancel()` via the existing JNI bridge; the resume data block is provided as a no-op so we discard the resume data.
  - Error-code mapping: `WKErrorDomain` codes mapped to the `Category` enum.
- Linux heavyweight + lightweight implementation:
  - In the same `download-started` handler from STORY-005-001, also connect `received-data`, `finished`, `failed` after the destination decision succeeds. Each routes to the EDT via the existing JNI bridge and invokes the listener.
  - `webkit_download_cancel` invoked from Java `cancel()`.
  - Error-code mapping: the `WebKitDownloadError` enum (`CANCELLED_BY_USER` → `CANCELLED`; `DESTINATION` → `IO`; `NETWORK` → `NETWORK`).
- Windows implementation:
  - In the `DownloadStarting` handler from STORY-005-001, after `put_ResultFilePath` succeeds and before completing the deferral, obtain the `ICoreWebView2DownloadOperation` and register `add_BytesReceivedChanged` + `add_StateChanged`.
  - Each event reads its respective getter, marshals to the EDT, and invokes the listener.
  - `Cancel()` invoked from Java `cancel()`.
  - Error-code mapping: `COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_*` mapped (`USER_CANCELED` → `CANCELLED`; `NETWORK_*` → `NETWORK`; `FILE_*` → `IO`; everything else → `UNKNOWN`).
- Demo: extend `demos/WebViewDownloadDemo/` (from STORY-005-001) with a progress-bar panel — for each in-progress download, show a `JProgressBar` (indeterminate when `totalBytes == -1`), a label with bytes / total / percent, and a `Cancel` button. On completion, the row shows "Done" and the file path; on failure, the row shows the failure category and message. **Alternatively**, this can be a new `demos/WebViewDownloadProgressDemo/` if the existing demo grows too large.
- README.md "Downloads" section (from STORY-005-001) extended with a "Tracking progress" subsection covering `WebViewDownloadListener`, the `WebViewDownload` handle, and the cancel pattern.

### Scope Out

- Resumable downloads (`resumeData:` on cancel, restarting from where a download stopped). The macOS `[WKDownload cancel:]` block accepts a resume-data parameter — we discard it. A future story can wire this through if needed.
- Per-byte transfer-rate calculation. The listener receives raw byte counts; rate / ETA is the caller's responsibility (one line of arithmetic).
- Pause-without-cancel. None of the three engines expose a clean pause primitive that round-trips reliably; the closest is "cancel and resume from resume data", which is the resumable-downloads story above.
- Download retry. The caller can re-initiate the download by calling `setUrl` or by injecting JS — out of scope to automate.
- Cross-download orchestration (queue management, parallelism caps, prioritisation). The host app implements its own policy on top of the per-download handles.
- Bandwidth limiting. None of the three engines expose a public API to limit per-download throughput.
- Telemetry / diagnostic logging of progress events. Listeners that want to log do so themselves.
- Standalone in-process `WebView` class. Same scope-out as STORY-005-001.

### Acceptance Criteria

#### AC1: progress callback fires at least once for a non-trivial download on macOS
**Given** a `WebViewHeavyweightComponent` on macOS 11.3 or later with a custom handler that returns `new File("/tmp/dl/sample.bin")` from `downloadRequested` and, in `downloadStarted`, registers a listener that records every `progress`, `completed`, and `failed` callback,
**When** the page initiates a 4 MB download of `sample.bin`,
**Then** at least one `progress` callback fires before `completed`, the final recorded `bytesReceived()` equals the file's full size, and `completed` fires exactly once after the file is fully written.

#### AC2: progress callback fires at least once on Linux (heavyweight + lightweight)
**Given** the same setup as AC1 but on Linux in `WebViewHeavyweightComponent`, then again in `WebViewLightweightComponent`,
**When** the same 4 MB `sample.bin` is downloaded,
**Then** in each mode, at least one `progress` fires before `completed`, and `completed` fires exactly once.

#### AC3: progress callback fires at least once on Windows
**Given** the same setup as AC1 but on a Windows 11 `WebViewHeavyweightComponent`,
**When** the same 4 MB `sample.bin` is downloaded,
**Then** at least one `progress` fires before `completed`, and `completed` fires exactly once.

#### AC4: completed fires exactly once with the final on-disk path
**Given** a `WebViewComponent` on any supported platform, with a custom handler that returns `new File("/tmp/dl/result.bin")` and a listener recording each `completed(download)` call,
**When** the page downloads a 1 MB file,
**Then** `completed` is recorded exactly once, the recorded `download.destination()` is `new File("/tmp/dl/result.bin")`, the file at that path has the expected 1 MB length, and no `failed` callback fires.

#### AC5: failed fires exactly once with NETWORK category when the connection drops
**Given** a `WebViewComponent` on any supported platform, a custom handler that returns a temp path, and a listener recording each `failed` call, and a test server that closes the connection mid-transfer,
**When** the page begins a download and the connection drops at 50% of the expected total,
**Then** `failed` is recorded exactly once with `reason.category() == NETWORK`, `completed` is not recorded, the partial file is **not** left in the destination directory (engine cleans up its work), and `download.state() == FAILED`.

#### AC6: failed fires exactly once with IO category on disk-full
**Given** a `WebViewComponent` on any supported platform, with a custom handler returning a path on a filesystem that fills up mid-write (e.g. a small tmpfs simulated in the test),
**When** the download exceeds the available disk space,
**Then** `failed` is recorded exactly once with `reason.category() == IO`, `completed` is not recorded, and `download.state() == FAILED`.

#### AC7: cancel mid-flight fires failed with CANCELLED category
**Given** a `WebViewComponent` on any supported platform, with a 50 MB download in progress and a listener that calls `download.cancel()` after the first `progress` callback,
**When** `cancel()` returns,
**Then** within 2 seconds a `failed` event fires exactly once with `reason.category() == CANCELLED`, `completed` does not fire, `download.state() == CANCELLED`, and the partial file is removed from the destination directory.

#### AC8: cancel after completion is a no-op
**Given** a `WebViewComponent` on any supported platform with a 1 KB download that has already completed (the listener has observed `completed`),
**When** the caller invokes `download.cancel()`,
**Then** no `failed` callback fires, `download.state()` remains `COMPLETED`, and the on-disk file is unchanged.

#### AC9: cancel during cancel is idempotent
**Given** a `WebViewComponent` on any supported platform with an in-progress download,
**When** the caller invokes `download.cancel()` twice in quick succession,
**Then** exactly one `failed` event fires (not two), with `reason.category() == CANCELLED`.

#### AC10: downloadStarted is not invoked when downloadRequested returns null
**Given** a `WebViewComponent` on any supported platform with a handler whose `downloadRequested` returns `null` and whose `downloadStarted` records every call,
**When** the page initiates a download,
**Then** `downloadStarted` is not invoked, no `WebViewDownload` handle is constructed, and the download is cancelled before any bytes are written (AC9 of STORY-005-001).

#### AC11: Listener callbacks run on the EDT
**Given** a `WebViewComponent` on any supported platform with a custom handler that, in `downloadStarted`, registers a listener recording `SwingUtilities.isEventDispatchThread()` inside `progress`, `completed`, and `failed`,
**When** the page completes a successful download,
**Then** every recorded value is `true`. Repeated with a cancelled download (cancel triggers `failed`), every recorded value is also `true`.

#### AC12: Listener exception does not crash the WebView or stop other listeners
**Given** a `WebViewComponent` on any supported platform with two listeners registered on the same `WebViewDownload`: the first listener throws `RuntimeException` from `completed`; the second listener records that `completed` was called,
**When** the download completes,
**Then** the first listener's exception is surfaced via `Thread.getDefaultUncaughtExceptionHandler()`, the second listener's `completed` is still invoked, the WebView remains responsive, and subsequent downloads work normally.

#### AC13: Adding a listener after terminal state fires the terminal callback immediately
**Given** a `WebViewComponent` on any supported platform with a download that has already reached `COMPLETED`,
**When** a new listener is added to the handle via `download.addListener(listener)`,
**Then** within a single EDT cycle the listener's `completed(download)` is invoked exactly once. No `progress` callbacks are replayed.

#### AC14: removeListener stops subsequent callbacks
**Given** a `WebViewComponent` on any supported platform with a 4 MB download in progress, a listener `A` registered receiving `progress` events, and a listener `B` registered receiving the same,
**When** the caller invokes `download.removeListener(A)` after the third `progress` event observed by both,
**Then** subsequent `progress` events fire only on listener `B`; listener `A` records no further events; and on completion `completed` fires only on listener `B`.

#### AC15: bytesReceived monotonically increases
**Given** a `WebViewComponent` on any supported platform with a download in progress,
**When** the listener records `download.bytesReceived()` inside each `progress` callback,
**Then** the recorded sequence is monotonically non-decreasing, the final recorded value equals the file's total size on completion, and no recorded value exceeds `download.totalBytes()` (when `totalBytes > 0`).

#### AC16: state() reflects the most recent terminal callback
**Given** a `WebViewComponent` on any supported platform with a download that completes,
**When** the caller inspects `download.state()` from inside the `completed` callback,
**Then** the value is `COMPLETED`. Repeated with a cancelled download inside `failed`, the value is `CANCELLED`. Repeated with a network-failed download, the value is `FAILED`.

#### AC17: The handle's destination matches the handler's returned path
**Given** a `WebViewComponent` on any supported platform with a handler returning `new File("/tmp/dl/x.bin")`,
**When** `downloadStarted` is invoked with the handle,
**Then** `handle.destination()` equals `new File("/tmp/dl/x.bin")` (the same `File` instance the handler returned, by `.equals` not necessarily identity).

#### AC18: Demo shows progress and supports cancel
**Given** `demos/WebViewDownloadDemo/` (extended) or `demos/WebViewDownloadProgressDemo/` (new) running on any supported platform, with a test page that initiates a slow 50 MB download,
**When** the user observes the demo and clicks Cancel halfway through,
**Then** the progress bar visibly advances from 0% before Cancel is clicked, the row's state transitions to "Cancelled" within 2 seconds of the click, no further byte updates appear after the cancel, and the partial file is removed from disk.

#### AC19: backward-compatible with STORY-005-001 handlers
**Given** a `WebViewComponent` with a handler that implements only `downloadRequested` (returning a valid `File`) and not `downloadStarted` (relying on the default empty impl),
**When** the page initiates a download,
**Then** the download completes successfully to the chosen destination, `downloadRequested` is invoked exactly once, no exception is thrown about the missing `downloadStarted` override, and no listener events are required.

### Non-Functional Expectations

- The progress event marshal from native to EDT must not block the engine UI thread. `invokeLater` (not `invokeAndWait`) is the dispatch primitive. A slow EDT does not back-pressure the engine; runnables queue on the EDT and may coalesce visually (Swing's repaint coalescing handles the progress-bar update naturally).
- The `cancel()` round-trip from Java to native to engine must not deadlock with any in-flight EDT runnable.
- Terminal callbacks (`completed` / `failed`) must fire **exactly once** per download lifetime even under stressful interleavings (rapid cancel-while-completing, network drop racing with disk error). The native code uses an atomic terminal-state flag to enforce this.
- Adding a listener after terminal state must be **safe and synchronous-feeling** — the terminal callback is enqueued via `invokeLater` before `addListener` returns, ensuring the caller sees the terminal callback on the next EDT cycle without manual polling.
- No native resource leak after either terminal callback — the `WKDownload` / `WebKitDownload` / `CoreWebView2DownloadOperation` reference is released, signal connections are disconnected, and the Java handle becomes eligible for GC once the caller drops it.
- Identical Java-visible event field values across heavyweight and lightweight modes on Linux for the same download (byte counts, terminal category, error message text — modulo engine-version differences in the error string).
