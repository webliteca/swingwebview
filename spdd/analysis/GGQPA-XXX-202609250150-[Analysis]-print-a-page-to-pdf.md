# SPDD Analysis: Print A Page To PDF

## Original Business Requirement

From `requirements/[User-story-7]print-a-page-to-pdf.md`, verbatim:

> # Story Decomposition: Print A Page To PDF
>
> ## INVEST Analysis
>
> ### Abstract Task: "Save What The WebView Shows As A PDF, With No Dialog"
>
> **Analysis Dimensions**:
> - **Core Responsibility**: Let an application ask the embedded WebView to write the page it is showing
>   to a **PDF file**, at a page size and margins the application chooses, with backgrounds, without a
>   print dialog — and be told when the file is written or why it was not. Applications built on this
>   library produce reports as paginated HTML (CSS page boxes laid out by a polyfill) and need the PDF
>   to be those pages, one for one.
> - **Primary Operations**: print the current page to a PDF file; find out whether this build and this
>   machine can do it.
> - **Key Constraints**: all three engines (WebView2, WebKitGTK, WKWebView), in the component mode
>   each platform supports (lightweight on Linux, heavyweight on macOS and Windows); asynchronous (a print can take seconds and must not block the UI); a clear, catchable
>   failure on an engine or runtime that cannot print to PDF, rather than a crash; no new link-time
>   dependency on Linux (WebKit symbols are loaded at runtime).
> - **Technical Complexity**: High — three native implementations and a result delivered back to Java.
> - **Business Complexity**: Low — one operation with a size, margins and a backgrounds switch.
>
> ### INVEST Evaluation
> - ✅ **Independent**: builds on the existing engine wrappers only.
> - ✅ **Negotiable**: which options are exposed beyond size, margins and backgrounds.
> - ✅ **Valuable**: unblocks PDF reports for every application on the library.
> - ✅ **Estimable**: one operation per platform.
> - ⚠️ **Small**: about 5 days — three engines. One story, because the value is a portable API; a
>   Linux-only or Windows-only print would be a platform-specific feature applications cannot rely on.
> - ✅ **Testable**: the PDF can be opened and its pages counted and measured.
>
> **Conclusion**: **Ready as-is — one story**, at the upper bound.
>
> ### Split Strategy
> Not applicable — no split needed.
>
> ---
>
> ## [STORY-007-001] Print a page to PDF
>
> ### Background
>
> Applications embed the WebView to show reports laid out as pages — a cover, running headers,
> "Page 3 of 5" — using CSS page boxes. The last step, "save this as a PDF", has no API: the only way
> out is the platform print dialog, which a user must drive by hand and which applies its own margins
> and scaling. Each engine can print to a file without a dialog, but through three different APIs.
>
> This story adds one call: print the current page to a PDF file at a given page size and margins,
> with backgrounds, and complete a result when the file is written.
>
> ### Business Value
> - Provide **application developers** with a one-call, dialog-free PDF of what the WebView shows.
> - Support **generated reports** whose pages must come out exactly as laid out.
> - Enable **portable code**: the same call on Windows, macOS and Linux.
>
> ### Dependencies and Assumptions
> - **Prerequisites**: none.
> - **Data assumptions**: the page is loaded; the application waits for its own layout to finish
>   before asking.
> - **Integration points**: WebView2 (Windows), WebKitGTK (Linux) with GTK's print-to-file backend,
>   WKWebView (macOS).
> - **Business constraints**: no new mandatory runtime dependency.
>
> ### Scope In
> - `printToPdf(path, options)` on the Swing component and on the engine wrappers, returning a result
>   that completes when the file is written and fails with a reason otherwise.
> - Options: page width and height (inches), four margins (inches), print backgrounds (default on).
>   Defaults: US Letter, zero margins, backgrounds on.
> - `isPdfPrintingSupported()`: whether the loaded native library provides the operation.
> - The component each platform supports: lightweight on Linux, heavyweight on macOS and Windows.
> - A demo that prints a two-page sample.
>
> ### Scope Out
> - Printing to a printer, and the print dialog.
> - Headers and footers drawn by the engine (they are always off).
> - Scaling, page ranges, orientation switches (orientation is implied by width and height).
> - Printing the standalone (non-Swing) `WebView` window.
>
> ### Acceptance Criteria
>
> #### AC1: A page prints to a PDF file
> **Given** a component showing a page laid out as two 8.5 × 11 in pages with zero margins
> **When** the application prints it to `/tmp/report.pdf` with the default options
> **Then** the result completes, and `/tmp/report.pdf` is a 2-page PDF with 612 × 792 pt pages.
>
> #### AC2: Size and margins are honoured
> **Given** the same component
> **When** it prints with an A4 page (8.27 × 11.69 in) and 0.5 in margins on every side
> **Then** every page of the PDF is 595 × 842 pt (±1 pt).
>
> #### AC3: Backgrounds are printed
> **Given** a page whose first page has a full-bleed dark background
> **When** it prints with the default options
> **Then** the first page of the PDF shows that background.
>
> #### AC4: The UI stays responsive
> **Given** a print in progress
> **When** the application's event thread runs other work
> **Then** it is not blocked by the print; the result completes later.
>
> #### AC5: A path that cannot be written fails with a reason
> **Given** a destination in a folder that does not exist
> **When** the application prints to it
> **Then** the result fails with a message, and nothing else breaks.
>
> #### AC6: An older native library says so
> **Given** an application running against a native library built before this feature
> **When** it asks whether PDF printing is supported, and then prints
> **Then** it is told no, and the print fails with "PDF printing is not available in this version of
> the native library" rather than crashing.
>
> #### AC7: A runtime too old to print fails with a reason
> **Given** a Windows WebView2 runtime without print-to-PDF, or macOS before 11
> **When** the application prints
> **Then** the result fails with a message naming the missing capability.
>
> #### AC8: Printing with no page attached fails cleanly
> **Given** a component that has not been displayed yet
> **When** the application prints
> **Then** the result fails with "The WebView is not attached yet."
>
> #### Non-Functional Expectations
> - A 10-page report prints in a few seconds.
> - The result completes on the Swing event thread for Swing callers.

## Domain Concept Identification

### Existing Concepts (from codebase)
- **Engine wrappers**: `EmbeddedWebView` (heavyweight, all platforms) and `OffscreenWebView`
  (lightweight, Linux offscreen). Both expose one-shot engine operations such as `clearCache()`
  (Canvas 22), each a JNI call dispatched onto the engine's UI thread.
- **Swing component**: `WebViewComponent` with protected peer hooks (`clearCacheOnPeer`) overridden
  by `WebViewHeavyweightComponent` and `WebViewLightweightComponent`, a no-op when no peer is attached.
- **Engine UI threads**: `GtkPump` (Linux, `run_async`), `cocoa_run_on_main_async` (macOS),
  `dispatch_to_thread` (Windows WebView2 worker).
- **Java upcalls**: global-ref callback objects invoked with attach/detach and exception clearing
  (downloads, Canvases 23–25).
- **Runtime-loaded WebKit** (Linux): every `webkit_*` symbol is listed in `WK_WEBKIT_SYMS`
  (`webkit_loader.h`) and redirected by `webkit_shim.h`; a missing symbol fails library load. GTK
  (`gtk_print_settings_*`, `gtk_page_setup_*`, `gtk_paper_size_*`) links normally.
- **WebView2 COM handler pattern**: `CallbackBase<Iface>` (`ClearCacheHandler`), interface-version
  gating by `QueryInterface`, the engine's stored `environment`.
- **Capability probing**: only `webview_cred_store_available()` wrapped in
  `catch (Throwable) → false`, which also absorbs `UnsatisfiedLinkError` from an older native.

### New Concepts Required
- **PDF print request**: destination path, page size, margins, backgrounds; one outstanding native
  operation with a one-shot completion.
- **PDF completion callback**: a Java object passed with each request and called exactly once
  (`onPdfFinished(boolean ok, String error)`), held by a native global ref until then.
- **PDF options**: a small value class with the defaults (Letter, zero margins, backgrounds on).
- **Capability probe**: `webview_pdf_available()` — present only in natives that implement printing.

### Key Business Rules
- **Exactly one completion per request**, success or failure; never zero, never two.
- **Never block the caller's thread**; complete Swing callers on the EDT.
- **Fail with a sentence**: no peer, old native, old runtime, unwritable path, engine error.
- **The PDF is the page**: engine headers/footers off, scale 1, backgrounds on by default.

## Strategic Approach

### Solution Direction
One JNI entry per engine kind — `webview_embed_print_to_pdf` and `webview_offscreen_print_to_pdf` —
taking the path, the six numbers, the backgrounds flag and a callback; each posts the work to the
engine UI thread and calls the callback when the engine reports completion. A new
`webview_pdf_available()` answers the capability question. Java wraps the callback in a
`CompletableFuture<File>`; the Swing component completes it on the EDT and fails it immediately
when no peer is attached.

- **Linux**: `WebKitPrintOperation` with a `GtkPrintSettings` naming the "Print to File" printer,
  `pdf` format and a `file://` output URI, and a `GtkPageSetup` with a custom paper size and margins;
  `finished` and `failed` signals complete the request; backgrounds through the view's
  `print-backgrounds` setting (`g_object_set`, no new WebKit symbol). Four WebKit symbols are added to
  the loader; all exist in every WebKitGTK 2.x.
- **macOS**: `-[WKWebView printOperationWithPrintInfo:]` (macOS 11+) with an `NSPrintInfo` whose job
  disposition is "save" to the destination URL, custom paper size and margins, no panels; run with
  `runOperationModalForWindow:delegate:didRunSelector:contextInfo:` on the view's window, with a small
  runtime-created delegate class receiving the completion. The print view's frame must be set, or
  WKWebView prints blank pages.
- **Windows**: `ICoreWebView2_7::PrintToPdf` with settings from
  `ICoreWebView2Environment6::CreatePrintSettings` (page size, margins, backgrounds on, header/footer
  off, scale 1); a `CallbackBase<ICoreWebView2PrintToPdfCompletedHandler>` completes the request.

### Key Design Decisions
- **Per-request callback vs. per-engine callback + request ids**: per-request needs no map and makes
  "exactly once" local to one native object → **per-request global ref, released after the call**.
- **Future type**: `CompletableFuture<File>` (Java 8 target; no `Path` in the public API of a
  Java-8 library is required, but `File` matches the existing download API).
- **Units**: inches, as WebView2 uses; converted to points on macOS and GTK units on Linux.
- **Capability**: the native probe answers "this build can"; runtime gaps (old WebView2, macOS < 11,
  missing GTK file backend) fail the request with a reason.

### Alternatives Considered
- **DevTools `Page.printToPDF` on Windows**: returns base64 through a JSON channel; more code, no
  benefit over `PrintToPdf` on SDK 1.0.2592.
- **`createPDFWithConfiguration:` on macOS**: produces one long page, not paginated.
- **Printing on the Java side** (e.g. rendering to an image): loses text and vector output.

## Risk & Gap Analysis

### Requirement Ambiguities
- Should backgrounds be forced on even when the page's CSS says `print-color-adjust: economy`?
  Recommend: the option sets the engine flag; CSS still applies.

### Edge Cases
- A second print before the first finishes: allowed; each has its own callback.
- The component is disposed mid-print: the engine teardown must still release the callback's
  global ref; the future fails with "The WebView was closed."
- Destination file exists: overwritten.

### Technical Risks
- **Headless Linux**: the GTK "Print to File" backend must be installed (`libgtk-3-common` ships it);
  when no printer named "Print to File" is found, WebKit fails the operation — surfaced as a reason.
- **macOS printing needs a window**: an embedded view has the AWT window; if it has none, fail.
- **Cannot compile macOS or Windows locally**: CI compiles both on every pull request; runtime checks
  on those platforms are manual (demo).

### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | 2-page Letter PDF | Yes | Verified on Linux locally; macOS/Windows by demo. |
| 2 | A4 with margins | Yes | Page size per engine settings. |
| 3 | Backgrounds | Yes | Engine flag per platform. |
| 4 | Non-blocking | Yes | Engine UI thread + future. |
| 5 | Unwritable path fails | Yes | Engine error or pre-check. |
| 6 | Old native says no | Yes | Probe + `UnsatisfiedLinkError` catch. |
| 7 | Old runtime fails with reason | Yes | QI / respondsToSelector gates. |
| 8 | No peer fails cleanly | Yes | Component hook. |
