# SPDD Analysis: Print A Page Through The Native Print Dialog

## Original Business Requirement

See `requirements/[User-story-14]print-a-page-through-the-native-print-dialog.md`, reproduced here verbatim
in substance by reference (STORY-014-001 "Print a page through the platform's print dialog", AC1–AC8;
STORY-014-002 "The macOS PDF holds every page", AC1–AC3). Command argument as given:

> Print a page through the platform's native print dialog (macOS, Windows, Linux lightweight):
> WebViewComponent.print() opens the engine's own print dialog with preview, page range, copies, paper,
> margins, headers/footers; window.print() from the page reaches the same dialog (host may veto); and
> printToPdf on macOS paginates the whole page instead of a single screenful (the operation view's frame
> is one paper size today).

## Domain Concept Identification

### Existing Concepts (from codebase)
- **Print to PDF (Canvas 29)**: `WebViewComponent.printToPdf(File, PdfOptions)` → `printToPdfOnPeer` →
  `EmbeddedWebView` / `OffscreenWebView.printToPdf` → `PdfPrinting.Queue` (one print per engine at a time)
  → JNI `webview_embed_print_to_pdf` / `webview_offscreen_print_to_pdf` → per platform:
  `cocoa_print_to_pdf` (WKWebView `printOperationWithPrintInfo:`, save-job, panels off, the operation
  view's frame set to **one paper size**), `gtk_print_to_pdf` (`WebKitPrintOperation` to the "Print to
  File" printer; lightweight only — the heavyweight GTK print crashes), WebView2 `ICoreWebView2_7::
  PrintToPdf`. Answers through `WebViewPdfCallback.onPdfFinished(ok, error)` exactly once;
  `PdfPrinting.Request` completes the Java future on the EDT; pending requests fail with `CLOSED` on
  dispose. `isPdfPrintingSupported()` probes a native entry point and absorbs `UnsatisfiedLinkError`.
- **Page-initiated requests decided by the host**: the dialog handler (alert/confirm/prompt), popup
  handler, navigation handler (Canvas 34), permission handler (Canvas 35) — each a `set…Handler` on the
  component, a dispatcher class, a native callback installed at peer-attach, a decision delivered on the
  EDT, and an `is…Supported()` probe for older natives.
- **Cocoa UI delegate** `WebviewEmbedUIDelegate`: one runtime-built class carrying every WKUIDelegate /
  navigation selector; the library already uses WebKit private SPI where no public API exists
  (`_acceptedMIMETypes`).
- **GTK signals** on the WebKitWebView (`script-dialog`, `create`, `decide-policy`,
  `permission-request`, `context-menu`) connected per engine; WebKit symbols resolved at run time.
- **JavaScript callbacks** (`addJavascriptCallback`) and document-start scripts (`addOnBeforeLoad`).

### New Concepts Required
- **Print dialog request**: "show the person the engine's print dialog for this page"; outcome
  printed / cancelled / failed (with a reason), and on WebView2 "opened" (its dialog reports no outcome).
- **Print request from the page** (`window.print()`): carries the page's address; decided by an
  application **print handler** (allow → the same dialog, deny → nothing). Default: allow.
- **Print dialog capability**: whether the loaded native can show the dialog.

### Key Business Rules
- One print dialog per component at a time; a second request fails at once (AC8). Shares nothing with
  the PDF queue, which is dialog-free and may run concurrently with no dialog open.
- The dialog prints the whole document from the top, whatever the scroll position (AC1).
- Never block the EDT or the AppKit main thread on a person's decision (AC6).
- The dialog is the platform's own; the library never chooses paper, range or copies (Scope Out).
- A page's print request never opens a dialog the application denied (AC5); with no handler, it opens
  (AC4).

## Strategic Approach

### Solution Direction
A sibling of Canvas 29: `print()` on the component → `printOnPeer` → engine wrapper → new JNI entry
points → per-engine native dialog → a new callback answered exactly once → a future completed on the
EDT. Page-initiated prints are intercepted natively where the engine lets us (macOS, Linux) and routed
through a per-engine callback to a Java dispatcher that asks the application's handler, then calls the
same `print()`. On Windows, WebView2 already opens its own dialog for `window.print()` and offers no
event; the veto is provided by a document-start script that replaces `window.print` only while a handler
is installed.

| Platform | Dialog | Page's `window.print()` |
|---|---|---|
| macOS (heavyweight) | `printOperationWithPrintInfo:` with print & progress panels **on**, panel options for copies, page range, paper, orientation, scale, preview; run as a sheet on the view's window | private WKUIDelegate `_webView:printFrame:` and `_webView:printFrame:pdfFirstPageSize:completionHandler:` (WKWebView ignores `window.print()` without them) → host callback |
| Windows (heavyweight) | `ICoreWebView2_16::ShowPrintUI(COREWEBVIEW2_PRINT_DIALOG_KIND_BROWSER)` — Edge's dialog with preview, range, layout, margins, scale, headers/footers, backgrounds | WebView2's own dialog by default; with a handler, a document-start script routes it to Java |
| Linux (lightweight) | `webkit_print_operation_run_dialog` — GTK dialog with preview, range, copies, page setup | the WebKitWebView `print` signal, stopped → host callback |

STORY-014-002 is a correction to Canvas 29 D9 (amend Canvas 29, regenerate): the macOS print
operation's view gets the WKWebView's bounds, not one paper page, and the operation may spawn its
separate thread — WKPrintingView asks the web process for the page rectangles and cannot wait for them
when it is confined to the main thread.

### Key Design Decisions
- **Native dialog vs. our own Swing preview**: our own would be uniform but a re-implementation, without
  the system's printers and PDF tools → native, per the story.
- **Outcome on Windows**: `ShowPrintUI` reports only that the dialog opened → add an `OPENED` outcome
  rather than pretend "printed"; AC2/AC3's printed/cancelled distinction holds on macOS and Linux.
- **macOS `window.print()`**: no public API → private SPI, guarded by `respondsToSelector`-free
  `class_addMethod` (never called on a WebKit without it). The web process waits on the delegate, so the
  delegate answers at once and starts the dialog asynchronously (avoids a deadlock while the print view
  asks the web process for pages).
- **Windows veto via script vs. none**: a script makes `window.print()` return before the dialog closes
  (Chrome's blocks) → only while a handler is installed, documented.
- **Separate Canvas for the macOS PDF fix**: it changes Canvas 29's behaviour, so Canvas 29 is amended
  rather than overridden from the new Canvas.

### Alternatives Considered
- Replace `window.print` with a script on every platform: uniform, but changes the page on macOS/Linux
  where native hooks exist, and makes every print non-blocking → rejected except Windows-with-handler.
- `createPDFWithConfiguration:` for the macOS PDF: one tall, unpaginated page → rejected.
- Silent printing to a chosen printer: out of scope.

## Risk & Gap Analysis

### Requirement Ambiguities
- "Headers/footers": WebView2's dialog offers them; the macOS panel and the GTK dialog do not offer the
  engine's header/footer option → each platform offers what its dialog offers.
- "Printed" on macOS includes "Save as PDF" and "Open in Preview" from the panel's PDF menu.

### Edge Cases
- An iframe's `window.print()`: macOS and Linux print the whole page, not the frame; WebView2 prints the
  frame (its own behaviour) unless the Windows script is active (whole page).
- A page that calls `print()` then `close()` in a popup: with the Windows script (and on macOS/Linux,
  where the delegate/signal answers at once), the close may run before the person prints.
- Component disposed while the dialog is open: the outcome must still be answered (`CLOSED`).
- Hidden / not-attached component: fail with `NOT_ATTACHED`.

### Technical Risks
- Cannot run macOS or Windows here: correctness of the Cocoa and WebView2 paths is verified by CI
  compiling all six natives and by a demo (`run-*-print-demo`) the owner runs on each platform.
- Private SPI signatures may change across macOS releases → both known selectors are added; an unknown
  future selector leaves `window.print()` inert as today (no crash).
- GTK: `webkit_print_operation_run_dialog` runs a nested main loop on the GTK pump thread; other engines'
  GTK work waits until the dialog closes (the EDT does not).
- The macOS PDF fix cannot be verified here; the demo prints a 4-page sample and reports the page count.

### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 001-1 | Dialog shows every page | Yes | macOS depends on the same frame fix as 002 |
| 001-2 | Range, copies, orientation | Yes | Windows outcome is `OPENED`, not printed |
| 001-3 | Cancel is an answer | Partial | Windows cannot report a cancel |
| 001-4 | Page's Print button opens dialog | Yes | macOS via private SPI |
| 001-5 | Application may refuse | Yes | Windows: from the next document after the handler is set |
| 001-6 | UI stays responsive | Yes | GTK pump busy during the dialog |
| 001-7 | Unsupported is clear | Yes | new probe; old natives → false |
| 001-8 | One dialog at a time | Yes | Windows: until the dialog opens only |
| 002-1 | Long page → several pages | Yes | verify on a Mac |
| 002-2 | Scroll position irrelevant | Yes | verify on a Mac |
| 002-3 | Paged reports unchanged | Yes | verify on a Mac |
