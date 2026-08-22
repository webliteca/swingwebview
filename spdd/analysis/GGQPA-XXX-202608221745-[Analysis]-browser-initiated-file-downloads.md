# SPDD Analysis: Browser-Initiated File Downloads

## Original Business Requirement

# Story Decomposition: Browser-Initiated File Downloads

## INVEST Analysis

### Abstract Task: "Let a download the embedded page starts surface to the Swing host, so the host picks the destination and sees the transfer through, on every supported platform/mode"

**Analysis Dimensions**:
- **Core Responsibility**: A page running inside `WebViewComponent` can start a file transfer three ways — an `<a download>` click, a navigation whose response carries `Content-Disposition: attachment`, and a navigation to a response the engine cannot render inline (a `.zip`, a `.dmg`, an `application/octet-stream` body). Today `WebViewComponent` has no download channel at all: on macOS the transfer is silently discarded (no `WKDownloadDelegate` and, before macOS 11.3, no `WKDownload` at all), on Linux WebKitGTK falls back to its own built-in download handling with a destination the host never sees, and on Windows WebView2 shows its own download flyout in the corner of the embedded view. An embedding application therefore cannot answer the two questions that matter: *where do these bytes go* and *did the transfer finish*. This task gives `WebViewComponent` a single, uniform download channel so:
  - **By default**, every platform shows a Swing `JFileChooser` save dialog pre-filled with the server-suggested filename, anchored on the host `JFrame` — the same "Save as…" affordance a real browser offers, portable across backends.
  - **By override**, a Java caller registers a `WebViewDownloadHandler` that decides the destination programmatically (including returning `null` to refuse the download outright) and observes progress and completion — which is what an embedding app needs to route downloads into its own folder and its own notification surface, and what a headless test needs to assert on a transfer without showing UI.
- **Primary Operations**:
  1. Intercept the start of a download at the native layer on each backend, defer the transfer until the Java host has named a destination file (or refused), and hand the decision back without stalling the engine beyond that decision.
  2. Report progress as bytes arrive, and report terminal outcome (finished / failed / cancelled) once, per download.
  3. Expose a public Java API (`WebViewDownloadHandler`, `setDownloadHandler` / `getDownloadHandler`, event POJOs) shared by all backends.
  4. Provide a default `WebViewDownloadHandler` implementation that shows a `JFileChooser` save dialog modal to the host window and ignores progress and completion.
- **Key Constraints**:
  - The destination decision is **synchronous from the engine's point of view** — every backend blocks or defers the transfer until a path is supplied. This matches the `onPopupRequested` / dialog precedent already in the codebase: marshal to the EDT with `invokeAndWait`, capture the return value, hand it back to native.
  - Progress and completion are **fire-and-forget notifications**. They must not block the engine; they marshal with `invokeLater`, matching `onPopupOpened` / `onPopupClosed`.
  - A download outlives the navigation that started it, and may outlive the tab: a component disposed mid-transfer must not fire into freed JNI refs. Disposal has to make in-flight callbacks safe, the way `DialogDispatcher.disposeAll` already does.
  - Downloads carry **untrusted, server-controlled strings**. The suggested filename comes off the wire (`Content-Disposition`, or the URL's last path segment) and can contain path separators, `..`, NUL bytes, or be empty. The library must not let a page choose where on disk the bytes land by crafting that string.
  - The default `JFileChooser` must be modal to the **host `JFrame`** (`SwingUtilities.getWindowAncestor(component)`), for the same reason the dialog defaults are.
  - `WKDownload` requires macOS 11.3+. Older systems have no public download API on `WKWebView`; on those the download must degrade to the existing behaviour (discarded) rather than crash.
  - Behaviour must be observable from a small Swing harness, and the handler-override path must be usable from a unit test (programmatic destination, no UI) so headless test environments stay viable.
- **Technical Complexity**: Medium-High overall, split across three platform-shaped pieces:
  - macOS: adopt `WKDownloadDelegate`, implement `webView:navigationAction:didBecomeDownload:` and `webView:navigationResponse:didBecomeDownload:` on the navigation delegate, and drive `download:decideDestinationUsingResponse:suggestedFilename:completionHandler:` plus the finish/fail delegate methods. Requires returning `WKNavigationResponsePolicyDownload` for responses the engine will not render.
  - Linux: connect `download-started` on the `WebKitWebContext`, then `decide-destination`, `received-data`, `finished` and `failed` on the resulting `WebKitDownload`, and suppress the built-in destination choice. Identical wiring for heavyweight (X11-reparented) and lightweight (offscreen) modes — the GTK signal model is the same — plus the popup engines, which share the opener's context.
  - Windows: register `add_DownloadStarting` on `ICoreWebView2_4`, take a deferral, set `ResultFilePath`, suppress the default download dialog with `put_Handled(TRUE)`, and subscribe `add_BytesReceivedChanged` / `add_StateChanged` on the `ICoreWebView2DownloadOperation`.
- **Business Complexity**: Low — "the user clicked a download link, the file should land somewhere the user chose" is well-understood behaviour. The work is bridging plumbing plus one genuinely security-sensitive step (filename sanitisation), not new business behaviour.

### INVEST Evaluation (whole feature)

- ✅ **Independent**: No dependency on unshipped work. It builds on the existing native-engine plumbing (canvases 6 / 7 / 2) and reuses the dispatcher/handler/callback precedent from canvases 11-13 and 15-17. Each per-platform sub-story is independently shippable; the Java API designed in STORY-006-001 is the contract the other two conform to.
- ✅ **Negotiable**: The default (a `JFileChooser` save dialog rather than a silent save to a fixed folder) and the refusal signal (`null` destination) are the open decisions; both are settled below and both are overridable.
- ✅ **Valuable**: Without this, any embedding application that hosts real-world content — a webmail attachment, an invoice PDF, a report export, an installer — either loses the file silently or lands it somewhere the app cannot see. With it, the host owns the destination and knows when the bytes arrived.
- ✅ **Estimable**: Each backend has a documented native download API. The Java side is one interface, three event POJOs, one dispatcher, and one JNI bridge interface — the same shape as two features already in the repo.
- ⚠️ **Small (as a single story)**: Combined, the work spans three native backends plus the public Java API — realistically 7-10 days. **This exceeds the 1-5 day INVEST sizing target.** Splitting is required (see below).
- ✅ **Testable**: The dispatcher's decision, sanitisation, and disposal rules are unit-testable with no native engine present; per-platform delivery is observable from a Swing harness that downloads a known file and asserts on its bytes.

**Conclusion**: Needs splitting along platform-backend boundaries, exactly as browser-initiated dialogs (STORY-004-*) and browser-initiated popups (STORY-005-*) were. The Java contract is established in STORY-006-001, co-delivered with macOS because macOS is the platform with zero current coverage and the one where the failure is silent.

### Split Strategy

Split by **technical dependency / native backend**, because:

- Each backend's download-interception API is distinct (an ObjC delegate protocol on macOS, GTK signals on a shared `WebKitWebContext` on Linux, a COM event source with deferrals on Windows). The work to wire each is independent of the others.
- The Java contract is shared and must be designed once — that is STORY-006-001's primary responsibility, co-delivered with macOS.
- Each story delivers independent value: STORY-006-001 alone makes downloads work at all on macOS and gives every platform the Java API; STORY-006-002 alone takes Linux downloads out of WebKitGTK's invisible built-in handling; STORY-006-003 alone replaces the WebView2 download flyout with the host's own destination choice.
- Each story is 2-4 days, within INVEST sizing.

Story dependency graph:

```
STORY-006-001 (Java API contract + macOS)
        │
        ├──► STORY-006-002 (Linux heavyweight + lightweight + popups) — depends on the Java contract from 006-001
        │
        └──► STORY-006-003 (Windows) — depends on the Java contract from 006-001
```

STORY-006-002 and STORY-006-003 can be developed in parallel once STORY-006-001 has landed.

---

## [STORY-006-001] WebViewDownloadHandler API and macOS WKWebView Coverage

### Background

A page inside `WebViewComponent` starts a download when the user clicks `<a href="report.pdf" download>`, when a navigation's response carries `Content-Disposition: attachment`, or when a navigation lands on a body the engine will not render inline. On macOS today **all three are silently discarded**: the `WKWebView` has no `WKDownloadDelegate`, and the navigation delegate never answers `WKNavigationResponsePolicyDownload`, so `WKWebView` simply abandons the transfer. Nothing appears on disk, nothing is reported to the host, and the page gives no indication anything went wrong. An embedding application hosting a webmail client or an internal reporting tool loses every attachment its user tries to save.

This story does two things at once:

1. **Designs and exposes the cross-platform Java contract** for browser-initiated downloads — one `WebViewDownloadHandler` interface whose `default` methods show a Swing save dialog, three event POJOs (`WebViewDownloadEvent`, `WebViewDownloadProgressEvent`, `WebViewDownloadCompleteEvent`), and `setDownloadHandler` / `getDownloadHandler` on `WebViewComponent`. This is the contract STORY-006-002 (Linux) and STORY-006-003 (Windows) conform to.
2. **Implements that contract on macOS** by adopting `WKDownloadDelegate`, answering `WKNavigationResponsePolicyDownload` for non-renderable and attachment responses, and bridging destination / progress / completion to the Java handler.

The chosen design mirrors the two browser-initiated features already in this repo, deliberately:

- **A `JFileChooser` save dialog as the default.** It is portable — one Swing call behaves identically on every backend — and it is what a user expects a download to do. It is also the honest default for a library: silently writing a server-named file into a fixed folder is a decision only the embedding application can make.
- **A single handler per component**, replacing the default rather than composing with it. Callers either accept the default or supply their own. Tests supply one that returns a fixed `File` with no UI.
- **`null` means refuse.** A handler that returns `null` from `downloadRequested` cancels the transfer. This is the same "no answer" convention `promptOpened` already uses for Cancel, and it gives the host a veto without a second method.
- **Progress and completion are observations, not decisions.** They cannot cancel, cannot block, and default to no-ops, so a caller who only wants to choose a folder writes exactly one method.

Key points:
- Business value: any desktop app embedding a WebView to host real-world content needs its user's downloads to survive. macOS is silently broken today, so it is the highest-leverage place to start.
- Relationship with other features: orthogonal to the dialog channel (canvases 11-13) and the popup channel (canvases 15-17), and reuses their dispatcher/handler/callback shape. The reserved `__webview_` binding prefix is not used — downloads originate from native engine callbacks, not page-injected JS.
- Why now: the dialog and popup stacks established the pattern for "browser-initiated event surfaced to Swing through a per-component handler." Downloads are the last of the three common Swing-Web interop needs still missing.

### Business Value

- Provide **working downloads on macOS**, where every transfer is currently discarded without a trace.
- Give an embedding application **ownership of the destination** — the folder, the filename, and the right to refuse — on every platform, through one API.
- Give an embedding application **visibility into the transfer** — bytes so far, total expected, and a single terminal outcome — so it can show progress and tell the user when the file is ready.
- Keep the **out-of-the-box experience sane**: an app that installs no handler still gets a real "Save as…" dialog rather than a silent drop.

### Dependencies and Assumptions

- **Prerequisites**: the existing embedded-engine plumbing (heavyweight and lightweight `WebViewComponent` modes) and the established dispatcher/handler/callback precedent from the dialog and popup canvases.
- **Data assumptions**: the engine supplies a suggested filename, a source URL, a MIME type, and (when the server sent `Content-Length`) an expected byte count. Any of these may be absent or server-controlled garbage.
- **Integration points**: `WKWebView`'s navigation delegate and `WKDownloadDelegate` on macOS; `WebViewComponent`'s public API; the JNI bridge in `src_c/webview_embed.cpp`.
- **Business constraints**: the library targets Java 8 source level; `WKDownload` requires macOS 11.3 or newer and the feature must degrade quietly on older systems.

### Scope In

- The public Java API: `WebViewDownloadHandler` (with `DEFAULT`), the three event POJOs, and `setDownloadHandler` / `getDownloadHandler` on `WebViewComponent`.
- The internal `WebViewDownloadCallback` JNI bridge interface and the `DownloadDispatcher` fan-out hub, including EDT marshalling, exception isolation, and disposal safety.
- Sanitisation of the server-suggested filename before it reaches the handler.
- macOS coverage for all three download triggers, in both heavyweight and lightweight modes.
- README documentation of the new channel.

### Scope Out

- Linux coverage (STORY-006-002) and Windows coverage (STORY-006-003).
- Pausing, resuming, or cancelling a download after it has started.
- A built-in downloads list, download history, or any UI beyond the default save dialog.
- Resuming interrupted transfers across process restarts.
- Downloads started by the host application itself (an app that wants to fetch a URL uses its own HTTP client).

### Acceptance Criteria

#### AC1: A download with no handler installed offers a save dialog
**Given** a `WebViewComponent` on which no caller has installed a download handler
**When** the page navigates to `https://example.com/report.pdf`, whose response carries `Content-Disposition: attachment; filename="Q3 report.pdf"`
**Then** a save dialog appears, modal to the host window, pre-filled with the name `Q3 report.pdf`, and on confirmation the complete file is written to the chosen location.

#### AC2: A custom handler chooses the destination without UI
**Given** a caller has installed a handler whose `downloadRequested` returns `/tmp/downloads/fixed.bin` and shows no UI
**When** the page starts a download of a 2 MB file
**Then** no dialog appears, and once the transfer completes `/tmp/downloads/fixed.bin` exists and is 2 MB.

#### AC3: Returning no destination refuses the download
**Given** a caller has installed a handler whose `downloadRequested` returns nothing
**When** the page starts a download
**Then** the transfer is abandoned, no file is written anywhere on disk, and the handler is told the download ended without success.

#### AC4: A cancelled save dialog refuses the download
**Given** the default handler is active
**When** the page starts a download and the user dismisses the save dialog without choosing a file
**Then** the transfer is abandoned and no file is written.

#### AC5: Progress is reported as the bytes arrive
**Given** a caller has installed a handler that records every progress report
**When** the page downloads a 10 MB file whose response declares `Content-Length: 10485760`
**Then** the handler receives progress reports whose received-byte counts increase and never exceed the total, each reporting a total of 10485760, and the final report matches the file's size on disk.

#### AC6: A download whose size is unknown still reports progress
**Given** a caller has installed a handler that records every progress report
**When** the page downloads a chunked response that declares no `Content-Length`
**Then** progress reports still arrive with increasing received-byte counts, and each reports the total as unknown rather than as zero.

#### AC7: Completion is reported exactly once, with the outcome
**Given** a caller has installed a handler that counts completion reports
**When** a download finishes normally
**Then** the handler is told exactly once that the download succeeded, and is given the file it was written to.

#### AC8: A failed transfer is reported as a failure, not a success
**Given** a caller has installed a handler that records completion reports
**When** the connection drops midway through a download
**Then** the handler is told exactly once that the download did not succeed, and is given a description of what went wrong.

#### AC9: A server-supplied filename cannot escape the chosen folder
**Given** the default handler is active
**When** a page starts a download whose server-suggested filename is `../../../../etc/passwd`
**Then** the name offered in the save dialog contains no directory separators and no parent-directory segments, and nothing is written outside the folder the user picks.

#### AC10: A missing or empty suggested filename still yields a usable name
**Given** the default handler is active
**When** a page starts a download for which the server supplies no filename at all
**Then** a non-empty fallback name is offered rather than an empty one, and the download can be saved.

#### AC11: A handler that throws does not break the page or the engine
**Given** a caller has installed a handler whose `downloadRequested` throws an exception
**When** the page starts a download
**Then** the exception is reported through the JVM's default uncaught-exception path, the transfer is abandoned cleanly, and the page and the component remain usable for further navigation and downloads.

#### AC12: Disposing the component mid-transfer is safe
**Given** a download is in flight
**When** the hosting `WebViewComponent` is disposed
**Then** the process does not crash, and no further progress or completion reports reach the handler.

#### AC13: Suppressing downloads entirely for tests
**Given** a caller has passed no handler at all — explicitly installing "no handler"
**When** the page starts a download
**Then** the download is refused with no dialog and no file, so a headless test environment is unaffected.

#### Non-Functional Expectations
- Choosing the destination must not stall the engine longer than the host's decision genuinely takes — an automated handler that returns immediately must not introduce a perceptible pause in the page.
- Progress reporting must not degrade transfer throughput for large files; a handler that does nothing must not make a 100 MB download measurably slower than the engine's own built-in handling.
- Two `WebViewComponent`s in the same JVM may download concurrently, each with its own handler, without their reports being confused with one another.

---

## [STORY-006-002] Linux WebKitGTK Coverage for WebViewDownloadHandler (Heavyweight + Lightweight + Popups)

### Background

STORY-006-001 establishes the Java contract and delivers macOS. On Linux, WebKitGTK does not silently drop downloads the way `WKWebView` does — it handles them itself, choosing a destination under the user's download directory with no involvement from, and no report to, the embedding application. The host therefore cannot honour its own destination policy, cannot show progress, and cannot tell the user the file arrived. Worse, in `LIGHTWEIGHT` mode the offscreen WebKit window has no `transient_for`, so any dialog WebKitGTK does raise is unpositionable — the same constraint already documented for context menus and native dialogs.

This story routes Linux downloads through the same `WebViewDownloadHandler` contract, for heavyweight (X11-reparented) and lightweight (offscreen) engines alike, and for the popup engines that share an opener's web context.

Key points:
- Business value: an application shipping on Linux gets the same destination control and the same progress reporting it gets on macOS, from the same one API.
- Relationship: conforms to the contract from STORY-006-001; no Java API changes.
- Why now: a per-platform gap in a cross-platform handler is worse than no handler — a caller who tests on macOS would ship a Linux build whose downloads quietly ignore their policy.

### Business Value

- Give Linux downloads the **same destination control** the Java contract already promises.
- Replace WebKitGTK's **invisible built-in destination choice** with the host's, so downloads land where the application says they do.
- Cover **popup-opened downloads** — a download started from a `window.open`ed child — so a transfer is not lost because of which view began it.

### Dependencies and Assumptions

- **Prerequisites**: STORY-006-001 (the Java contract, the dispatcher, and the JNI bridge interface).
- **Data assumptions**: the same suggested filename / URL / MIME type / expected-size inputs, from WebKitGTK's `WebKitURIResponse`.
- **Integration points**: `WebKitWebContext`'s `download-started` signal; `WebKitDownload`'s `decide-destination`, `received-data`, `finished` and `failed` signals; both engine structs and the popup engine struct in `src_c/webview_embed.cpp`.
- **Business constraints**: the library dlopens WebKitGTK at runtime (4.1 preferred, 4.0 fallback), so every new symbol must go through the existing loader shim rather than being linked directly.

### Scope In

- Heavyweight (X11-reparented) engine coverage.
- Lightweight (offscreen) engine coverage.
- Popup-engine coverage, including a download started in an adopted popup.
- Suppression of WebKitGTK's built-in destination choice.

### Scope Out

- Any change to the Java API from STORY-006-001.
- macOS (STORY-006-001) and Windows (STORY-006-003).
- WebKitGTK's own download-progress UI.

### Acceptance Criteria

#### AC1: Heavyweight downloads route to the host's handler
**Given** a heavyweight `WebViewComponent` on Linux with a handler that returns `/tmp/dl/a.bin`
**When** the page downloads a 2 MB file
**Then** WebKitGTK shows no destination UI of its own, and the complete 2 MB lands at `/tmp/dl/a.bin`.

#### AC2: Lightweight downloads route to the same handler
**Given** a lightweight (offscreen) `WebViewComponent` on Linux with the same handler
**When** the page downloads the same file
**Then** the behaviour is identical to AC1 — no built-in UI, and the bytes land at the handler's path.

#### AC3: A download started in a popup reaches the opener's handler
**Given** a page opens a popup via `window.open` and the popup starts a download
**When** the transfer begins
**Then** the handler installed on the opening component decides the destination, and progress and completion are reported to it.

#### AC4: Refusal is honoured on Linux
**Given** a handler that returns no destination
**When** the page starts a download
**Then** WebKitGTK abandons the transfer, writes nothing — including nothing in the user's default download directory — and reports the download as unsuccessful.

#### AC5: Progress and completion match the contract
**Given** a handler that records progress and completion
**When** a 10 MB download with a declared size completes on Linux
**Then** progress reports increase toward 10485760, and exactly one successful completion is reported, with the same guarantees STORY-006-001 specifies.

#### AC6: A failed Linux transfer is reported as a failure
**Given** a handler that records completion
**When** a download fails partway through
**Then** exactly one unsuccessful completion is reported with a description of the failure.

#### AC7: Disposal during a Linux transfer is safe
**Given** a download is in flight in either mode
**When** the component is disposed
**Then** the process does not crash and no further reports arrive.

---

## [STORY-006-003] Windows WebView2 Coverage for WebViewDownloadHandler

### Background

STORY-006-001 establishes the Java contract. On Windows, WebView2 handles downloads with its own flyout UI rendered inside the embedded view, choosing a destination under the user's Downloads folder. As on Linux, the embedding application has no say in the destination and no report of the outcome — and the flyout is visually foreign to a Swing application, appearing inside the web content rather than as part of the host window.

This story routes Windows downloads through the same handler, suppressing the built-in flyout.

Key points:
- Business value: Windows applications get the same destination control and progress reporting, and stop showing a browser-chrome flyout inside a native desktop app.
- Relationship: conforms to the contract from STORY-006-001; no Java API changes.
- Why now: completes the cross-platform promise of the handler.

### Business Value

- Give Windows downloads the **same destination control** as the other two platforms.
- **Suppress the WebView2 download flyout**, so an embedded view stops rendering browser chrome inside a Swing application.
- Report progress and completion to the host, so a Windows application can show its own download indicator.

### Dependencies and Assumptions

- **Prerequisites**: STORY-006-001 (the Java contract, the dispatcher, and the JNI bridge interface).
- **Data assumptions**: the same inputs, from WebView2's `ICoreWebView2DownloadOperation`.
- **Integration points**: `ICoreWebView2_4::add_DownloadStarting`, `ICoreWebView2DownloadStartingEventArgs` (deferral, `ResultFilePath`, `Handled`), and `ICoreWebView2DownloadOperation`'s `add_BytesReceivedChanged` / `add_StateChanged`, in `windows/webview_embed.cc`.
- **Business constraints**: `add_DownloadStarting` requires the `ICoreWebView2_4` interface; on a runtime too old to expose it the feature must degrade to WebView2's built-in handling rather than fail to start.

### Scope In

- Windows heavyweight coverage of destination, progress, and completion.
- Suppression of the built-in download flyout when a handler decides the destination.
- Graceful degradation on WebView2 runtimes that do not expose `ICoreWebView2_4`.

### Scope Out

- Any change to the Java API from STORY-006-001.
- macOS (STORY-006-001) and Linux (STORY-006-002).
- WebView2's download-history UI.

### Acceptance Criteria

#### AC1: Windows downloads route to the host's handler
**Given** a `WebViewComponent` on Windows with a handler returning `C:\temp\dl\a.bin`
**When** the page downloads a 2 MB file
**Then** no WebView2 download flyout appears and the complete 2 MB lands at `C:\temp\dl\a.bin`.

#### AC2: Refusal is honoured on Windows
**Given** a handler that returns no destination
**When** the page starts a download
**Then** the transfer is abandoned, nothing is written — including nothing in the user's Downloads folder — and the download is reported as unsuccessful.

#### AC3: Progress and completion match the contract
**Given** a handler that records progress and completion
**When** a 10 MB download with a declared size completes on Windows
**Then** progress reports increase toward 10485760 and exactly one successful completion is reported.

#### AC4: A cancelled or failed Windows transfer is reported as a failure
**Given** a handler that records completion
**When** a download is interrupted before it finishes
**Then** exactly one unsuccessful completion is reported with a description of the failure.

#### AC5: An old WebView2 runtime does not break the component
**Given** a WebView2 runtime too old to expose the download event source
**When** a page starts a download
**Then** the component still loads pages and runs scripts normally, and the download falls back to WebView2's built-in handling rather than crashing or hanging.

#### AC6: Disposal during a Windows transfer is safe
**Given** a download is in flight
**When** the component is disposed
**Then** the process does not crash and no further reports arrive.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **`WebViewComponent`** (`src/ca/weblite/webview/swing/WebViewComponent.java`): the abstract Swing host, `create()`d in `HEAVYWEIGHT` or `LIGHTWEIGHT` mode. It already owns two per-instance dispatcher fields (`dialogDispatcher`, `popupDispatcher`, lines 79 and 86) and exposes exactly one `set*Handler` / `get*Handler` pair per browser-initiated channel. A download channel is a third instance of this same shape — relationship: the component *owns* the channel; the channel outlives no component.
- **Handler interface with `default` methods** (`WebViewDialogHandler`, `WebViewPopupHandler`): the public contract a caller overrides. Both carry a `DEFAULT` constant that is "the stock behaviour as an instance", and both distinguish `setXHandler(null)` (install an internal drop handler) from `setXHandler(DEFAULT)` (reset to stock). This asymmetry is deliberate, documented, and load-bearing for headless tests — a download handler must reproduce it exactly rather than invent a third convention.
- **Dispatcher** (`DialogDispatcher`, `PopupDispatcher`): the per-component fan-out hub. Holds one `volatile` handler reference, marshals native-thread dispatches onto the EDT, isolates handler exceptions to `Thread.getDefaultUncaughtExceptionHandler()`, and carries a `disposed` flag flipped by `disposeAll()` so late native events return safe fallbacks instead of touching a torn-down component. Relationship: the dispatcher is what makes the handler safe to call from a native thread; nothing else in the design does.
- **JNI bridge callback interface** (`WebViewDialogCallback`, `WebViewPopupCallback`, `WebViewClickCallback`, `WebViewFocusCallback`): `public` only because native code in `src_c/webview_embed.cpp` and `windows/webview_embed.cc` must resolve methods on it; explicitly *not* application API. The Swing subclasses install an anonymous implementation at peer-attach time that forwards straight into the dispatcher (`WebViewHeavyweightComponent` lines 610-680, `WebViewLightweightComponent` lines 293-345).
- **`Engine` / `PopupEngine` structs** (`src_c/webview_embed.cpp`): the native per-view state, each holding JNI global refs to the registered callbacks, created in `gtk_create_engine` / `gtk_off_create_engine` and freed in `gtk_destroy_engine`. A popup engine *inherits* the opener's global refs (line 1216) — that inheritance is why a popup's dialogs already reach the opener's handler, and is the mechanism a popup's downloads must reuse.
- **WebKitGTK symbol loader** (`src_c/webkit_loader.h` / `webkit_shim.h`): the X-macro symbol inventory `WK_WEBKIT_SYMS`. `libwebview.so` links no WebKit symbol directly — everything is `dlopen`'d at runtime so one binary serves WebKitGTK 4.0 and 4.1. Every new WebKit function a download needs is a new entry in this list, not a new link-time dependency.
- **`WebViewFilePickerEvent`**: the closest existing analogue to a download event — an immutable POJO with a `source()` component, defensive `freeze()`ing of collections, never-null string accessors that normalise `null` to `""`, and a `toString()` for logging. It also establishes that *the dispatcher, not the event, normalises untrusted engine-supplied strings* (`DialogDispatcher.normaliseExtensions` / `normaliseMimeTypes`).
- **`spdd/norms.md`**: Java 8 source level (no `var`, no `List.of`, no `String.isBlank`); anonymous inner classes for JNI callbacks; "anchor every JNI callback in a Java field" or the GC collects it under the native side's feet; opaque `long` peers where `0` means disposed; idempotent `dispose()`.

### New Concepts Required

- **Download**: one file transfer the page started, with a lifetime that begins before the destination is known and ends in exactly one terminal outcome. It is the first concept in this repo that is *long-lived and asynchronous* — a dialog is a question answered in one call, a popup is a window, but a download spans seconds or minutes and emits many events. This is the analysis's central finding: the existing dispatcher shape covers the *decision*, but nothing in the repo yet covers *a stream of notifications tied to an in-flight native object*.
- **Download identity**: because several downloads can be in flight on one component at once, progress and completion reports must be attributable to the download that produced them. Nothing in the dialog or popup channel needs this (a dialog answer returns to its own call frame; a popup already carries a native popup id). Downloads need an identity that is stable from the destination decision through to the terminal report — relationship: identity is what lets a host correlate "40% of *what*".
- **Destination decision**: the host's answer of "write it here", "I refuse", expressed as one nullable value. Relates to the existing `promptOpened` → `null` = Cancel convention.
- **Terminal outcome**: success or failure with a reason, delivered exactly once per download. The "exactly once" property is new — no existing channel makes a delivery-count guarantee, and all three native backends can plausibly emit both a failure and a cancellation for the same abandoned transfer.
- **Sanitised filename**: the server-suggested name reduced to something that names a file and cannot name a *path*. New because it is the first place in this library where a remote party's string is used to construct a filesystem location.

### Key Business Rules

- **The host decides where bytes land, or that they do not land at all.** Governs: destination decision, download. No backend may fall back to its own destination when the host refuses — including WebKitGTK's default download directory and WebView2's Downloads folder, both of which are the *current* behaviour and both of which are precisely what must stop.
- **A refusal writes nothing.** Governs: destination decision. A partially-written file left behind by a refused download is a failure of this rule, not an acceptable approximation of it.
- **Exactly one terminal report per download.** Governs: terminal outcome, download identity. A host that shows a completion notification must not show two, and must not show none.
- **Progress never exceeds the declared total, and "unknown" is distinct from "zero".** Governs: progress reporting. A host rendering a progress bar needs to distinguish "no bytes yet, 10 MB expected" from "some bytes, size unknown" — conflating the two produces a bar that jumps to 100% or sits at 0%.
- **A server-controlled name cannot select a filesystem location.** Governs: sanitised filename. The name is a *suggestion for a leaf name*, never a path.
- **A disposed component reports nothing.** Governs: download, dispatcher. Established by `DialogDispatcher.disposeAll`; downloads extend it to events that can arrive long after the page that started them is gone.
- **Notifications never block the engine; the decision may.** Governs: all three callbacks. Inverting this — blocking on progress, or fire-and-forgetting the destination — breaks either throughput or correctness.

## Strategic Approach

### Solution Direction

Add a **third browser-initiated channel** to `WebViewComponent`, structurally identical to the two that exist: a public `WebViewDownloadHandler` with `default` methods and a `DEFAULT` constant, immutable event POJOs, an internal `WebViewDownloadCallback` JNI bridge interface, and a `DownloadDispatcher` holding the handler and doing the EDT marshalling, exception isolation, and disposal gating. The Swing subclasses install an anonymous `WebViewDownloadCallback` on their native peer at peer-attach time, exactly where they already install the dialog and popup callbacks. Native side: a new `download_callback` JNI global ref on each `Engine` / `PopupEngine` / Windows engine struct, registered by a new `webview_embed_set_download_callback` JNI entry point, cleared in the existing destroy paths.

Data flow: native engine detects a download → fires the JNI bridge → dispatcher marshals to EDT → handler returns a destination (or `null`) → dispatcher hands the path back → native engine writes the bytes and streams progress/terminal events back through the same bridge, marshalled with `invokeLater` instead.

The one genuinely new mechanism is **download identity**: a monotonic `long` minted natively when the transfer starts and carried on every event for that download. This is the smallest thing that makes concurrent downloads distinguishable, and it mirrors the `popupId` the popup channel already threads through `dispatchPopupOpened` / `dispatchPopupClosed`.

### Key Design Decisions

- **Default = a `JFileChooser` save dialog, not a silent save to a fixed folder**: trade-off is convenience versus honesty. A silent default would make the common embedding case a one-liner, but it commits *the library* to a folder policy it has no business choosing, and it makes a page able to write to disk with no user involvement the moment a caller forgets to install a handler. → **Recommend the save dialog.** It matches the `WebViewDialogHandler` precedent (stock behaviour is always a Swing dialog on the host frame), it fails safe, and an application that wants the silent behaviour writes four lines to get it.
- **Refusal signalled by a `null` destination, not a separate `boolean` or a cancel method**: trade-off is explicitness versus surface area. → **Recommend `null`.** `promptOpened` already means Cancel by `null`, so the convention is established; a second method would have to define what happens when the two disagree.
- **Progress and completion as `default` no-ops rather than a listener list**: trade-off is composability versus call-site simplicity. Dialogs use a single handler, mouse events use a listener list; downloads could plausibly go either way, since "several observers of a transfer" is a real shape. → **Recommend the single handler.** The destination decision *must* be single-resolver (two handlers cannot both choose the path), and splitting decision and observation across two registration mechanisms doubles the API for a case no embedder has asked for. A host that wants fan-out fans out inside its own handler.
- **Identity as a native-minted `long` rather than the destination `File`**: trade-off is simplicity versus correctness. Keying on the `File` is tempting and needs no new field. → **Recommend the `long`.** The destination is unknown at request time and can legitimately repeat across sequential downloads; two concurrent downloads that the host maps to the same path would become indistinguishable exactly when distinguishing them matters most.
- **Sanitisation in the dispatcher, not in the event POJO or in each native backend**: trade-off is one implementation versus three. → **Recommend the dispatcher.** `DialogDispatcher` already normalises engine-supplied extension/MIME strings on the way in; doing it natively would mean writing the same rule three times in three languages and getting it subtly different on each platform. The dispatcher is also the only place a unit test can reach without an engine.
- **Degrade quietly where the platform cannot comply**: macOS below 11.3 has no `WKDownload`; WebView2 runtimes below `ICoreWebView2_4` have no `add_DownloadStarting`. Trade-off is loud failure versus silent inconsistency. → **Recommend quiet degradation to the pre-feature behaviour**, matching how the dialog canvases handled platforms not yet wired: the component must keep loading pages and running scripts. Refusing to construct the engine because downloads are unavailable would break every app that never downloads anything.

### Alternatives Considered

- **Expose the transfer as a Java-side `InputStream` and let the host write the bytes**: rejected. It sounds cleaner but moves the transfer onto a Java thread, loses the engine's connection reuse, cookies, and authentication state, and turns a native-driven stream into a backpressure problem across the JNI boundary. The engine is already the right thing to be writing the file.
- **A `WebViewDownload` object with `cancel()` / `pause()` / `resume()` methods**: rejected for this iteration and marked Scope Out in the story. It requires holding a live native handle per download on the Java side with its own disposal rules, and neither the Linux nor the Windows backend exposes the same control set. Better added once the one-way channel is proven.
- **Reusing `WebViewDialogHandler` with a fifth `downloadRequested` method**: rejected. It would change an interface two shipped canvases already specify, silently give every existing caller a new method, and conflate a synchronous question-and-answer channel with a long-lived streaming one.
- **Deriving the destination folder from a component-level property (`setDownloadDirectory`) instead of a handler**: rejected. It cannot express refusal, cannot express per-download naming, and cannot report progress — it would have to be joined by a handler anyway, leaving two overlapping mechanisms.

## Risk & Gap Analysis

### Requirement Ambiguities

- **Progress cadence is unspecified.** The story says progress reports arrive with increasing counts, but not how often. Each backend has its own natural frequency (WebKitGTK's `received-data` fires per chunk; WebView2's `BytesReceivedChanged` fires on its own schedule). Needs clarification in the Canvas: the contract should promise *ordering and monotonicity*, not a rate, and should say plainly that a handler may receive many reports per second and must not do expensive work in them.
- **"The file it was written to" on failure.** AC8 requires a description of what went wrong but does not say whether the destination is still reported, or whether a partial file is left on disk. Each backend differs (WebKitGTK leaves the partial file; WKDownload removes it). Needs an explicit rule.
- **Whether a download should be attributed to the frame or the page.** The dialog events carry both `pageUrl` and `frameUrl`. The story mentions only the source URL of the download itself. Needs a decision — recommend carrying `pageUrl` for parity, since a host applying a per-site policy needs the initiating page, not just the asset URL.
- **`setDownloadHandler(null)` semantics are implied but not stated.** AC13 describes "explicitly installing no handler" refusing every download, which matches the established drop-handler convention, but the story never states that `null` and `DEFAULT` differ. This must be spelled out in the Canvas or a caller will reasonably read `null` as "reset to default" and ship an app that silently refuses every download.

### Edge Cases

- **Two downloads of the same URL started simultaneously**: both suggest the same filename; a host that maps name → path would write both to one file. Matters because it is the ordinary "user double-clicked the link" case, and it is why identity cannot be the path.
- **The handler returns a path whose parent directory does not exist, or which is not writable, or which is an existing directory.** Matters because the failure surfaces natively, after the decision, and must become a normal failure report rather than a crash or a silent no-op.
- **The handler returns a path that already exists.** Overwrite or fail? Matters because silently overwriting a user's file is a data-loss bug; the Canvas must state the rule and the default handler must not surprise (the `JFileChooser` path should confirm).
- **Disposal races the destination decision** — the component is disposed while the EDT hop for `downloadRequested` is in flight. Matters because the native side is blocked waiting for an answer; the dispatcher must return "refuse" rather than hang.
- **A download that starts and immediately fails** (dead connection, 404 body already consumed): may produce a terminal report with no preceding progress report, or a terminal report before the destination decision has been observed by the host.
- **A zero-byte download.** Progress may never fire at all; completion must still arrive.
- **A very long or non-ASCII suggested filename** — a name longer than the filesystem's per-component limit, or one containing characters legal on one platform and not another. Sanitisation must bound length as well as strip separators.
- **A suggested filename that sanitises to nothing** (`"../.."`, `"/"`, all-NUL): the fallback rule in AC10 must cover "sanitised away", not only "absent".
- **A download started by a popup that is subsequently adopted into a tab** (Canvas 18-20): which component's handler owns it? Matters on Linux especially, where popup engines inherit the opener's refs.

### Technical Risks

- **JNI global-ref lifetime across a long transfer** → the dominant crash risk. A download can outlive the page, the navigation, and plausibly the component. The existing engines free callback global refs in their destroy paths; a download event arriving after that frees is a SIGSEGV, not an exception. Mitigation direction: clear and null every ref before widget destruction (the `gtk_destroy_engine` ordering already documented for `click_callback`), gate every fire on a null check, and make the dispatcher's `disposed` flag authoritative on the Java side.
- **The destination decision blocks a native UI thread while the EDT runs the handler** → deadlock risk identical to the one `WebViewDialogHandler` already documents: a handler that calls `evalAsync(js).get()` parks the EDT waiting for a continuation that cannot run. Mitigation: carry the same EDT-deadlock warning into the download handler's contract, and use `invokeAndWait` only for the decision.
- **`invokeAndWait` from a thread that is itself the EDT** throws `IllegalArgumentException`; on some backends the callback may already arrive on a thread the EDT is pumping. Mitigation: reuse whatever guard `DialogDispatcher.runOnEdtVoid` already applies rather than writing a second one.
- **Progress events flooding the EDT** → a 100 MB download firing `invokeLater` per 8 KB chunk queues ~12,800 EDT tasks and can starve the UI, directly threatening the story's non-functional throughput expectation. Mitigation direction: coalesce natively or in the dispatcher (drop superseded pending progress events, since only the latest matters), and document that progress is lossy-but-monotonic while completion is not.
- **WebKitGTK's `download-started` is on the `WebKitWebContext`, not the web view** → the signal is shared by every view on that context, so the handler must map the download back to the originating view before it can pick a dispatcher. Getting this wrong sends one component's downloads to another component's handler. This is the sharpest correctness risk on Linux and has no analogue on the other two platforms.
- **New WebKit symbols must go through the `dlopen` shim** → adding a direct call to `webkit_download_*` would compile locally and fail at runtime on the other WebKitGTK ABI. Mitigation: every new symbol enters `WK_WEBKIT_SYMS` in `webkit_loader.h` and gets a redirect in `webkit_shim.h`; no exceptions.
- **Windows deferral lifetime**: `ICoreWebView2Deferral` must be completed exactly once, from the right apartment. Dropping it hangs the download; completing it twice is undefined. Mitigation: hold it in a COM smart pointer whose destructor cannot run before the decision returns.
- **macOS availability gating**: `WKDownload` and `WKDownloadDelegate` are 11.3+. Building against a newer SDK and running on 11.0 must not crash. Mitigation: runtime `respondsToSelector:` / `@available` gating, matching how the codebase already gates `ICoreWebView2_13` behind a `QueryInterface`.
- **Cannot be verified end-to-end in CI on all three platforms** — the repo's native libraries are built by a per-platform CI matrix and the download paths need a live engine and a live server. Mitigation: put every rule that can be tested without an engine (sanitisation, refusal, disposal gating, exception isolation, terminal-once) into JUnit tests against the dispatcher, and treat the per-platform native paths as harness-verified.

### Acceptance Criteria Coverage

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 006-001 AC1 | No handler ⇒ save dialog pre-filled with suggested name | Yes | Requires the `DEFAULT` instance to be the stock save dialog, per the `WebViewDialogHandler.DEFAULT` precedent. |
| 006-001 AC2 | Custom handler picks destination, no UI | Yes | The core path; unit-testable at the dispatcher with no engine. |
| 006-001 AC3 | `null` destination refuses, nothing written | Partial | Native backends must be verified not to leave a partial file. WebKitGTK in particular begins writing before `decide-destination` resolves on some versions — the Canvas must require an explicit cancel, not just a refused path. |
| 006-001 AC4 | Cancelled save dialog refuses | Yes | Falls out of AC3 once `JFileChooser` cancel maps to `null`. |
| 006-001 AC5 | Progress increases, bounded by declared total | Yes | Cadence unspecified — see Ambiguities; contract should promise monotonicity, not rate. |
| 006-001 AC6 | Unknown size reports unknown, not zero | Yes | Needs an explicit sentinel (`-1`) fixed in the Canvas; `WebViewPopupEvent` already uses `-1` for unspecified width/height, so reuse it. |
| 006-001 AC7 | Exactly one success report | Partial | "Exactly once" needs a dispatcher-side latch — no backend guarantees it natively, and Linux can emit `finished` after `failed`. |
| 006-001 AC8 | Exactly one failure report, with reason | Partial | Same latch. Also needs the partial-file rule from Ambiguities. |
| 006-001 AC9 | `../../../../etc/passwd` cannot escape | Yes | Dispatcher-side sanitisation; directly unit-testable. Must also cover Windows separators and drive letters, since the rule is written once for all platforms. |
| 006-001 AC10 | Missing name ⇒ usable fallback | Yes | Extend to "sanitises to empty", not just "absent" — see Edge Cases. |
| 006-001 AC11 | Throwing handler does not break engine | Yes | Established pattern (`ConsoleDispatcher` / `DialogDispatcher` exception isolation); fallback must be refuse. |
| 006-001 AC12 | Disposal mid-transfer is safe | Partial | Java-side gating is straightforward; the native-side global-ref ordering is the real risk and is only observable under a live engine. |
| 006-001 AC13 | `setDownloadHandler(null)` refuses everything | Yes | Requires the drop-singleton convention to be stated explicitly — see Ambiguities. |
| 006-001 NFR | Decision latency, progress throughput, per-component isolation | Partial | Throughput depends on the progress-coalescing decision; per-component isolation depends on the Linux context-to-view mapping risk. |
| 006-002 AC1 | Heavyweight Linux routes to handler | Yes | — |
| 006-002 AC2 | Lightweight Linux routes identically | Yes | Same GTK signal model; the offscreen engine differs only in windowing. |
| 006-002 AC3 | Popup download reaches opener's handler | Partial | Depends on the `download-started`-is-on-the-context risk *and* on popup ref inheritance; also intersects the adopted-popup edge case. |
| 006-002 AC4 | Linux refusal writes nothing, incl. default dir | Partial | The sharpest Linux behaviour to verify — this is the current behaviour being removed. |
| 006-002 AC5 | Linux progress/completion match contract | Yes | — |
| 006-002 AC6 | Linux failure reported once | Partial | Linux is the platform most likely to emit both `failed` and `finished`; the latch covers it. |
| 006-002 AC7 | Linux disposal safety | Partial | Native ref-ordering, as AC12. |
| 006-003 AC1 | Windows routes to handler, no flyout | Yes | Requires `put_Handled(TRUE)`. |
| 006-003 AC2 | Windows refusal writes nothing | Yes | Cancel on the `DownloadOperation`, not merely an unset path. |
| 006-003 AC3 | Windows progress/completion | Yes | — |
| 006-003 AC4 | Windows interruption reported once | Yes | `StateChanged` distinguishes interrupted from completed. |
| 006-003 AC5 | Old runtime degrades, does not hang | Yes | `QueryInterface` gate, matching the existing `ICoreWebView2_13` pattern. |
| 006-003 AC6 | Windows disposal safety | Partial | Deferral and event-token teardown ordering; observable only under a live runtime. |

**Coverage summary**: 17 of 29 ACs are fully addressable as specified; 12 are addressable but depend on a rule the Canvas must fix (the exactly-once latch, the unknown-size sentinel, the partial-file rule, the refusal-must-cancel rule, and the Linux context-to-view mapping). None are unaddressable. The four ambiguities above must be resolved in `/spdd-reasons-canvas`, not deferred to implementation.
