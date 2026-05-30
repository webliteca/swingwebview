# WebViewDownloadDemo

Manual-test harness for the browser-initiated download API
(`WebViewDownloadHandler`) added in **STORY-005-001**. Verifies the
acceptance criteria in
`requirements/[User-story-5]webview-download-handler.md`.

The demo starts an embedded `HttpServer` on a random localhost port
serving three downloadable payloads, loads a tiny test page with three
`<a href="..." download>` links, and exposes a `JComboBox` switching
between three handler modes:

- **Default** — `setDownloadHandler(WebViewDownloadHandler.DEFAULT)`
  (with a logging wrapper). Downloads land in `~/Downloads` with
  `(N)` de-duplication.
- **Custom** — handler routes every download to a temp directory
  created at app start. Each event is logged with its URL, MIME,
  total bytes, and chosen destination.
- **Drop** — `setDownloadHandler(null)`. Every download is cancelled
  before any bytes are written; the log records the cancel decision.

## Launching

Run from the repository root:

- **macOS**: `./run-mac-download-demo.sh`
- **Linux**: `./run-linux-download-demo.sh` (defaults to lightweight
  mode; override with `./run-linux-download-demo.sh heavyweight`)
- **Windows**: `run-windows-download-demo.bat`

Each script builds the native library, the `WebView.jar`, and the
demo sources, then launches the JVM with the right classpath.

## Acceptance criteria checklist (STORY-005-001)

| AC  | Steps |
|-----|-------|
| AC1–AC4 | In **Default** mode, click each link once.  Verify the file appears under `~/Downloads/` on the platform (`~/Downloads/` on macOS / Linux; `%USERPROFILE%\Downloads\` on Windows).  No native download UI appears. |
| AC5–AC6 | In **Default** mode, click `sample.txt` repeatedly.  Verify `~/Downloads/sample.txt`, `~/Downloads/sample (1).txt`, `~/Downloads/sample (2).txt`, … appear in turn. |
| AC7 | In a fresh user profile where `~/Downloads` does not exist, click any link in Default mode.  Verify `~/Downloads/` is created and the file is saved inside it. |
| AC8 | In **Custom** mode, click each link.  Verify the file appears under the temp directory logged at startup, NOT under `~/Downloads`. |
| AC9 | (Set up via DownloadDispatcherTest's exception isolation test.) |
| AC10 | In **Drop** mode, click each link.  Verify no files appear anywhere and each click produces a `[drop] cancelled ...` log line. |
| AC11 | In **Custom** mode, click `report.pdf`.  Verify the log line shows MIME `application/pdf` and a non-`-1` byte total. |
| AC12 | Configure a server endpoint that omits `Content-Length` (chunked).  Verify the log shows `-1`.  (Optional; the default ByteHandler always sets Content-Length.) |
| AC13 | (Covered by `DownloadDispatcherTest.dispatchDownload_runsHandlerOnEdt`.) |
| AC14 | (Covered by `DownloadDispatcherTest.dispatchDownload_isolatesHandlerException`.) |
| AC15 | (Covered by `DownloadDispatcherTest.event_sanitises*` tests.) |
| AC16 | Read `wv.getDownloadHandler()` after `wv.setDownloadHandler(null)`.  Verify non-null. |
| AC17 | Pre-populate `~/Downloads/<name>`, `~/Downloads/<name> (1)`, … `(999)`.  Trigger another download.  Verify the log shows `(cancelled — collision ceiling)`. |
| AC18 | In **Custom** mode, click `sample.txt` (which has `<a download>` matching the response filename).  Verify `event.suggestedFilename()` matches.  (Cross-origin behaviour intentionally out of scope.) |
| AC19 | Walk all three modes end-to-end as documented above. |
| AC20 | Run on macOS earlier than 11.3.  Verify no crash; `[default]` log entries don't appear because the navigation delegate is not installed.  WebView still loads pages normally. |
| AC21 | In **Custom** mode, modify the page (via DevTools console) to fire two `<a>` clicks in quick succession.  Verify both files appear in the temp directory and the log shows two `[custom]` entries. |

Native bridge coverage:

- **macOS 11.3+**: `WKNavigationDelegate.didBecomeDownload:` →
  `WKDownloadDelegate.decideDestinationUsingResponse:`. The
  selector uses the worker-thread + `dispatch_async` deferral
  pattern from commit `480798c`.
- **Linux**: `WebKitWebContext::download-started` on the shared
  default context, routed via the new `g_webkit_view_owners`
  map; foreign WebViews are skipped without claiming.
- **Windows**: `ICoreWebView2_4::add_DownloadStarting` with
  `GetDeferral` + `std::thread` + `dispatch_to_thread` back onto
  the WebView2 worker.
