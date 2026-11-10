# Story Decomposition: Print A Page Through The Native Print Dialog

## INVEST Analysis

### Abstract Task: "Print What The WebView Shows The Way A Browser Does"

**Analysis Dimensions**:
- **Core Responsibility**: Let a person print the page an embedded WebView shows with the same
  print experience their browser gives them: the platform's own print dialog, with a preview of every
  page, a choice of printer, page range, copies, paper, orientation, margins, scale, headers and
  footers and background graphics, and "Save as PDF". Let a page's own **Print** button
  (`window.print()`) open that same dialog, unless the application says no. And make the existing
  dialog-free PDF (Story 7) contain **every page** of the document on macOS, as it already does on
  Windows and Linux, not one screenful.
- **Primary Operations**: open the print dialog for the current page; decide on a page's own print
  request; print the current page to PDF (corrected on macOS).
- **Key Constraints**: all three engines (WebView2, WKWebView, WebKitGTK), each in the component
  mode its platform supports (heavyweight on macOS and Windows, lightweight on Linux); the dialog is
  the engine's or the operating system's own, never a re-implementation; asynchronous — the
  application learns whether the person printed, cancelled, or it failed, and the UI never freezes;
  a clear, catchable answer where an engine cannot show a dialog; older native libraries answer
  "not supported" rather than crash.
- **Technical Complexity**: High — three native implementations of a dialog, one page-initiated
  request per engine, and a pagination fix on macOS.
- **Business Complexity**: Low — one operation, one decision, one correction.

### INVEST Evaluation
- ✅ **Independent**: builds only on the existing engine wrappers and Story 7's print path.
- ✅ **Negotiable**: which platform dialog (WebView2 offers its browser dialog or the system's).
- ✅ **Valuable**: printing in an embedded browser stops being "a PDF of the screen".
- ✅ **Estimable**: one dialog call and one request hook per engine.
- ✅ **Small**: split in two — the dialog (about 4 days) and the macOS PDF pagination fix (1 day).
- ✅ **Testable**: the dialog can be seen, the page count in the preview and in a saved PDF counted.

**Conclusion**: **Split into two stories.**

### Split Strategy
By functional point: STORY-014-001 is the new capability (the dialog, and the page's own Print
button reaching it); STORY-014-002 corrects Story 7's PDF on macOS so it holds every page. Either
can ship without the other.

---

## [STORY-014-001] Print a page through the platform's print dialog

### Background

Story 7 gave applications a dialog-free PDF. An application that offers "Print…" in a browser built
on this library can only make that PDF and hand it to a PDF viewer: the person never sees the
browser-style print dialog they expect, cannot pick the pages, scale, margins, or turn headers and
footers on, and the page's own "Print" buttons (`window.print()`) do nothing on macOS. Every engine
has a native print dialog; this library never asks for it.

### Business Value
- Provide **people using an application's browser** with the print dialog their platform's browser
  gives them: preview of every page, printer, page range, copies, paper, orientation, margins, scale,
  headers and footers, background graphics, Save as PDF.
- Support **pages with their own Print buttons** — receipts, tickets, invoices.
- Enable **application developers** to offer first-class printing with one call, the same on Windows,
  macOS and Linux.

### Dependencies and Assumptions
- **Prerequisites**: none (Story 7 exists but is not required).
- **Data assumptions**: the page is loaded and showing in a component that is on screen.
- **Integration points**: WebView2's print dialog (Windows), the macOS print panel run for a
  WKWebView, the GTK print dialog run for WebKitGTK.
- **Business constraints**: no new mandatory runtime dependency; no telemetry or network use.

### Scope In
- A call on the Swing component that opens the native print dialog for the page it shows and
  completes with one of: **printed** (the person confirmed — sent to a printer or saved as a PDF),
  **cancelled**, or a failure with a reason.
- A way to ask whether this native library and machine can show the dialog.
- A page's own `window.print()` opens the same dialog, by default.
- An application-supplied decision for page-initiated prints: allow (the dialog opens) or deny
  (nothing happens; the page continues).
- The component mode each platform supports: heavyweight on macOS and Windows, lightweight on Linux.
- A demo that loads a long, multi-page sample and opens the dialog.

### Scope Out
- Printing without a dialog to a printer (silent printing).
- Choosing dialog options from the application (paper, range, copies) — the person chooses.
- The standalone (non-Swing) `WebView` window.
- Changes to the dialog-free PDF (STORY-014-002 and Story 7).

### Acceptance Criteria

#### AC1: The dialog shows every page of a long document
**Given** a page whose content is about 4 printed pages long, scrolled to the middle of page 2
**When** the application asks the component to print
**Then** the platform's print dialog opens over the application's window, its preview shows 4 pages,
starting at the top of the document — not only the part scrolled into view

#### AC2: The person can choose what a browser lets them choose
**Given** the dialog from AC1 is open
**When** the person sets the pages to "2–3", 2 copies and landscape, and prints
**Then** the printer (or Save as PDF) receives pages 2 and 3, twice, in landscape, and the call
completes as printed

#### AC3: Cancelling is an answer, not a failure
**Given** the dialog is open
**When** the person presses Cancel
**Then** nothing is printed and the call completes as cancelled

#### AC4: A page's Print button opens the dialog
**Given** a page with a button that calls `window.print()` and no application decision installed
**When** the person clicks the button
**Then** the same print dialog opens for that page, on every platform

#### AC5: The application may refuse a page-initiated print
**Given** the application has installed a decision that denies page-initiated prints
**When** the page calls `window.print()`
**Then** no dialog opens, the page keeps running, and the application's decision received the page's
address

#### AC6: A UI stays responsive
**Given** the dialog is open for 2 minutes
**When** other windows of the application repaint or receive events
**Then** they do so normally; the call's result arrives only when the dialog closes

#### AC7: Unsupported is a clear answer
**Given** an older native library, or a component that is not on screen
**When** the application asks whether printing is supported, or asks to print
**Then** it is told "not supported" (or the call fails with a reason such as "The WebView is not
attached yet.") and nothing crashes

#### AC8: One dialog at a time
**Given** the dialog is open for a component
**When** the application asks that component to print again
**Then** the second call fails at once with a reason saying a print is already in progress, and the
open dialog is unaffected

---

## [STORY-014-002] The macOS PDF holds every page

### Background

On macOS the dialog-free PDF of Story 7 holds one screenful of the page, not the whole document,
while the same call on Windows and Linux paginates the whole document. Applications that print a
long web page through it get a truncated PDF.

### Business Value
- Provide **application developers** with the same PDF on macOS as on the other platforms.
- Support **people printing long pages** on a Mac.

### Dependencies and Assumptions
- **Prerequisites**: Story 7.
- **Data assumptions**: the page is loaded and laid out.
- **Integration points**: WKWebView's print operation (macOS 11+).
- **Business constraints**: generated paginated reports (CSS page boxes) must still come out one
  for one.

### Scope In
- The macOS PDF contains every page of the document at the requested paper size and margins.

### Scope Out
- Windows and Linux (already correct).
- New PDF options.

### Acceptance Criteria

#### AC1: A long page becomes several pages
**Given** on macOS a page about 4 printed Letter pages long, scrolled to the top
**When** the application prints it to PDF on Letter with 0.5 in margins
**Then** the PDF has 4 Letter pages and the last page ends with the document's last line

#### AC2: Scroll position does not matter
**Given** the same page scrolled to the bottom
**When** it is printed to PDF
**Then** the PDF is the same 4 pages, starting at the top of the document

#### AC3: Paged reports are unchanged
**Given** a report laid out as exactly 2 CSS page boxes the size of the requested paper
**When** it is printed to PDF
**Then** the PDF has exactly 2 pages, one per page box, as before
