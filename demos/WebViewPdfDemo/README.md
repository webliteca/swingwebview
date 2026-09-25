# WebViewPdfDemo

Demo for **print to PDF** (Canvas 29, STORY-007-001).

Run it with `./run-linux-pdf-demo.sh` (lightweight, the supported Linux mode),
`./run-mac-pdf-demo.sh` or `run-windows-pdf-demo.bat` (heavyweight, the
supported macOS and Windows mode).

The demo loads a two-page report laid out with CSS page boxes
(`@page { size: 8.5in 11in; margin: 0 }`): a dark, full-bleed cover and a
plain second page. Three buttons print it:

| Button | Writes | Options |
|---|---|---|
| **Print Letter PDF** | `~/webview-pdf-demo.pdf` | defaults: US Letter, zero margins, backgrounds |
| **Print A4 PDF (0.5in margins)** | `~/webview-pdf-demo-a4.pdf` | `PdfOptions.a4().withMargins(0.5)` |
| **Print into a missing folder** | nothing | fails with the folder sentence |

Each result is logged, including whether the future completed on the EDT.

**Automatic mode.** `PDFDEMO_AUTO=1` (or `-Dpdfdemo.auto=true`) waits four
seconds for the page, starts all three prints at once, logs the outcomes and
exits with status 0 only when both PDFs were written and the bad path failed.

## Manual acceptance checklist

Check the PDFs with any viewer, or with `pdfinfo` (poppler) for page counts
and sizes.

- [ ] **AC1**: Letter prints: `~/webview-pdf-demo.pdf` has 2 pages of
      612 × 792 pt.
- [ ] **AC2**: A4 with 0.5 in margins: every page of
      `~/webview-pdf-demo-a4.pdf` is 595 × 842 pt (±1 pt).
- [ ] **AC3**: page 1 of the Letter PDF shows the dark cover to the paper's
      edge.
- [ ] **AC4**: while a print runs, the window stays responsive (resize it,
      click the other buttons).
- [ ] **AC5**: **Print into a missing folder** logs
      `failed - The folder …/no-such-folder does not exist.`
- [ ] **AC6**: run against a native library built before this feature: the log
      shows `PDF printing supported: false` and a print fails with "PDF printing
      is not available in this version of the native library." rather than
      crashing.
- [ ] **AC7**: Windows with a WebView2 runtime without print-to-PDF fails with
      "PDF printing needs a newer WebView2 runtime."; macOS 10.15 fails with
      "PDF printing needs macOS 11 or later."
- [ ] **AC8**: covered by `WebViewComponentPrintToPdfTest` (a component with
      no peer fails with "The WebView is not attached yet.").
- [ ] **AC9**: click **Print Letter** and **Print A4** in quick succession (or
      use automatic mode): both files are written.
- [ ] No print dialog or progress panel appears on any platform, and the PDFs
      carry no engine header or footer (no URL, date or page numbers added).

Verified on Linux (WebKitGTK 2.52, Xvfb, lightweight): AC1, AC2, AC3, AC5 and
AC9 in automatic mode, three runs in a row.
