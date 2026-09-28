# Story Decomposition: Print A Page To PDF

## INVEST Analysis

### Abstract Task: "Save What The WebView Shows As A PDF, With No Dialog"

**Analysis Dimensions**:
- **Core Responsibility**: Let an application ask the embedded WebView to write the page it is showing
  to a **PDF file**, at a page size and margins the application chooses, with backgrounds, without a
  print dialog — and be told when the file is written or why it was not. Applications built on this
  library produce reports as paginated HTML (CSS page boxes laid out by a polyfill) and need the PDF
  to be those pages, one for one.
- **Primary Operations**: print the current page to a PDF file; find out whether this build and this
  machine can do it.
- **Key Constraints**: all three engines (WebView2, WebKitGTK, WKWebView), in the component mode
  each platform supports (lightweight on Linux, heavyweight on macOS and Windows); asynchronous (a
  print can take seconds and must not block the UI); a clear, catchable
  failure on an engine or runtime that cannot print to PDF, rather than a crash; no new link-time
  dependency on Linux (WebKit symbols are loaded at runtime).
- **Technical Complexity**: High — three native implementations and a result delivered back to Java.
- **Business Complexity**: Low — one operation with a size, margins and a backgrounds switch.

### INVEST Evaluation
- ✅ **Independent**: builds on the existing engine wrappers only.
- ✅ **Negotiable**: which options are exposed beyond size, margins and backgrounds.
- ✅ **Valuable**: unblocks PDF reports for every application on the library.
- ✅ **Estimable**: one operation per platform.
- ⚠️ **Small**: about 5 days — three engines. One story, because the value is a portable API; a
  Linux-only or Windows-only print would be a platform-specific feature applications cannot rely on.
- ✅ **Testable**: the PDF can be opened and its pages counted and measured.

**Conclusion**: **Ready as-is — one story**, at the upper bound.

### Split Strategy
Not applicable — no split needed.

---

## [STORY-007-001] Print a page to PDF

### Background

Applications embed the WebView to show reports laid out as pages — a cover, running headers,
"Page 3 of 5" — using CSS page boxes. The last step, "save this as a PDF", has no API: the only way
out is the platform print dialog, which a user must drive by hand and which applies its own margins
and scaling. Each engine can print to a file without a dialog, but through three different APIs.

This story adds one call: print the current page to a PDF file at a given page size and margins,
with backgrounds, and complete a result when the file is written.

### Business Value
- Provide **application developers** with a one-call, dialog-free PDF of what the WebView shows.
- Support **generated reports** whose pages must come out exactly as laid out.
- Enable **portable code**: the same call on Windows, macOS and Linux.

### Dependencies and Assumptions
- **Prerequisites**: none.
- **Data assumptions**: the page is loaded; the application waits for its own layout to finish
  before asking.
- **Integration points**: WebView2 (Windows), WebKitGTK (Linux) with GTK's print-to-file backend,
  WKWebView (macOS).
- **Business constraints**: no new mandatory runtime dependency.

### Scope In
- `printToPdf(path, options)` on the Swing component and on the engine wrappers, returning a result
  that completes when the file is written and fails with a reason otherwise.
- Options: page width and height (inches), four margins (inches), print backgrounds (default on).
  Defaults: US Letter, zero margins, backgrounds on.
- `isPdfPrintingSupported()`: whether the loaded native library provides the operation.
- The component each platform supports: lightweight on Linux, heavyweight on macOS and Windows.
- A demo that prints a two-page sample.

### Scope Out
- Printing to a printer, and the print dialog.
- Headers and footers drawn by the engine (they are always off).
- Scaling, page ranges, orientation switches (orientation is implied by width and height).
- Printing the standalone (non-Swing) `WebView` window.

### Acceptance Criteria

#### AC1: A page prints to a PDF file
**Given** a component showing a page laid out as two 8.5 × 11 in pages with zero margins
**When** the application prints it to `/tmp/report.pdf` with the default options
**Then** the result completes, and `/tmp/report.pdf` is a 2-page PDF with 612 × 792 pt pages.

#### AC2: Size and margins are honoured
**Given** the same component
**When** it prints with an A4 page (8.27 × 11.69 in) and 0.5 in margins on every side
**Then** every page of the PDF is 595 × 842 pt (±1 pt).

#### AC3: Backgrounds are printed
**Given** a page whose first page has a full-bleed dark background
**When** it prints with the default options
**Then** the first page of the PDF shows that background.

#### AC4: The UI stays responsive
**Given** a print in progress
**When** the application's event thread runs other work
**Then** it is not blocked by the print; the result completes later.

#### AC5: A path that cannot be written fails with a reason
**Given** a destination in a folder that does not exist
**When** the application prints to it
**Then** the result fails with a message, and nothing else breaks.

#### AC6: An older native library says so
**Given** an application running against a native library built before this feature
**When** it asks whether PDF printing is supported, and then prints
**Then** it is told no, and the print fails with "PDF printing is not available in this version of
the native library" rather than crashing.

#### AC7: A runtime too old to print fails with a reason
**Given** a Windows WebView2 runtime without print-to-PDF, or macOS before 11
**When** the application prints
**Then** the result fails with a message naming the missing capability.

#### AC8: Printing with no page attached fails cleanly
**Given** a component that has not been displayed yet
**When** the application prints
**Then** the result fails with "The WebView is not attached yet."

#### AC9: Prints on one component run one after another
**Given** a component asked to print twice before the first print finishes
**When** both prints complete
**Then** both files are written, in the order asked.

#### Non-Functional Expectations
- A 10-page report prints in a few seconds.
- The result completes on the Swing event thread for Swing callers.
