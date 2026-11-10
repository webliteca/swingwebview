# WebViewPrintDemo — the native print dialog (Canvas 38)

Run `./run-linux-print-demo.sh` (lightweight), `./run-mac-print-demo.sh` or
`run-windows-print-demo.bat` (heavyweight). The window shows a document about
four printed pages long, a **Print…** button, a **Deny page prints** checkbox
and a status line.

| Check | Story 14 AC | Expected |
|---|---|---|
| Scroll to the middle, press **Print…** | 001-AC1 | The platform's dialog opens over the window; its preview has about 4 pages and starts at "Native print dialog demo" |
| Choose pages 2–3, 2 copies, landscape, Save as PDF / print | 001-AC2 | The output holds pages 2–3 twice, landscape; status `Outcome: PRINTED` (Windows: `OPENED` as soon as the dialog opens) |
| Press Cancel in the dialog | 001-AC3 | Nothing printed; status `Outcome: CANCELLED` (not reported on Windows) |
| Press the page's **window.print()** button | 001-AC4 | The same dialog opens |
| Tick **Deny page prints**, reload if on Windows, press the page's button | 001-AC5 | No dialog; status `Denied a page print from file:…` |
| Leave the dialog open, move and resize other windows | 001-AC6 | Everything keeps repainting |
| Press **Print…** twice quickly (macOS, Linux) | 001-AC8 | The second reports `Failed: A print is already in progress.` |
