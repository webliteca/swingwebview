# WebViewDownloadDemo

Interactive demo for the browser-initiated **download** channel
(Canvas 23 — Java API + macOS; Canvas 24 — Linux; Canvas 25 — Windows).

Run it with `./run-linux-download-demo.sh`, `./run-mac-download-demo.sh`,
or `run-windows-download-demo.bat`. On Linux, run it **twice** — once
`heavyweight`, once `lightweight` — since STORY-006-002 requires both.

The demo serves five download shapes from a loopback `HttpServer`:

| Link | Trigger | What it proves |
|---|---|---|
| `notes.txt` | `<a download>` on a renderable body | the `download` attribute is honoured rather than navigating |
| `report` | `Content-Disposition: attachment`, filename with a space | the server-suggested name reaches the handler intact |
| `bundle.zip` | a body the engine will not render inline | the non-renderable path becomes a download, not a blank page |
| `big.bin` | 10 MB, declared `Content-Length` | known-size progress, `fraction()` climbing to 1.0 |
| `stream.bin` | chunked, no `Content-Length` | unknown size reports `-1`, not `0` |

Two checkboxes swap the handler at runtime:

- **Use silent handler** — a programmatic handler that writes into
  `$TMPDIR/webview-download-demo` with no UI and logs every event.
- **Refuse every download (null handler)** — `setDownloadHandler(null)`,
  the drop handler.

Unchecking either restores `WebViewDownloadHandler.DEFAULT`.

## Manual acceptance checklist

Mapped to the ACs in
`requirements/[User-story-6]browser-initiated-file-downloads.md`.

| AC | Steps | Expected |
|---|---|---|
| 006-001 AC1 | With no checkbox set, click `report` | A save dialog appears, modal to the demo window, pre-filled `Q3 report.txt`. Saving writes the full file. |
| 006-001 AC2 | Check **Use silent handler**, click `notes.txt` | No dialog. `$TMPDIR/webview-download-demo/notes.txt` exists with the full body. |
| 006-001 AC3 | Check **Refuse every download**, click `bundle.zip` | No dialog, no file anywhere — **check the platform's own Downloads folder too**. Log shows one `completed … success=false`. |
| 006-001 AC4 | Uncheck both, click `report`, dismiss the dialog | Nothing written. |
| 006-001 AC5 | Check **Use silent handler**, click `big.bin` | Progress lines climb toward `10485760`, each showing a percentage, never exceeding the total. |
| 006-001 AC6 | Same, click `stream.bin` | Progress lines climb with `?` as the total — never `0`. |
| 006-001 AC7 | Any successful download | Exactly one `completed … success=true` line. |
| 006-001 AC8 | Start `big.bin`, then kill the demo's server (or pull the network) | Exactly one `completed … success=false` with a reason. |
| 006-001 AC9 | — | Covered by `DownloadDispatcherTest`; no manual step. |
| 006-001 AC10 | — | Covered by `DownloadDispatcherTest`; no manual step. |
| 006-001 AC11 | — | Covered by `DownloadDispatcherTest`; no manual step. |
| 006-001 AC12 | Start `big.bin` with the silent handler, then close the window mid-transfer | No crash, no further log lines. |
| 006-001 AC13 | Check **Refuse every download**, click every link | No dialog, no file, one failure line each. |
| 006-002 AC1/AC2 | Run the whole table in `heavyweight`, then `lightweight` | Identical behaviour in both modes. |
| 006-002 AC3 | Open a popup with `window.open`, download from it | The opener's handler decides; log lines appear in this window. |
| 006-003 AC1 | Windows: click any link with the silent handler | No WebView2 download flyout appears anywhere in the view. |
| 006-003 AC5 | Windows on a runtime without `ICoreWebView2_4` | Pages still load and scripts still run; downloads fall back to WebView2's own handling. |

## Things worth watching

- **Progress is coalesced.** `big.bin` writes ~160 chunks but the log
  shows far fewer progress lines, and the last one matches the file size
  on disk. That is the dispatcher's coalescing gate doing its job, not a
  dropped event.
- **Completion is exactly once.** Even a transfer that the engine
  reports as both failed and finished produces one line.
- **The suggested name is pre-sanitised.** `report` yields
  `Q3 report.txt`; a hostile server suggesting `../../etc/passwd` would
  yield `passwd`.
