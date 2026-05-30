/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.File;

/**
 * Handler interface for browser-initiated downloads raised by the
 * embedded page — HTTP responses carrying
 * {@code Content-Disposition: attachment}, or non-renderable MIME
 * types the engine classifies as downloads.
 *
 * <p><strong>Default behaviour.</strong> The one {@code default}
 * method, {@link #downloadRequested}, returns
 * {@code ~/Downloads/<deduped-filename>} — creating
 * {@code ~/Downloads} if absent, and appending {@code (1)},
 * {@code (2)}, … before the last {@code .}-segment of the filename
 * if a file of that name already exists (matches Chrome / Edge /
 * Safari). Callers that want stock behaviour do nothing — the
 * framework's {@link #DEFAULT} instance is pre-installed on every
 * {@link ca.weblite.webview.swing.WebViewComponent}.
 *
 * <p><strong>Custom behaviour.</strong> Implement
 * {@code downloadRequested} to override the destination decision
 * — return a chosen {@link File}, or {@code null} to cancel the
 * download before any bytes are written. Install with
 * {@link ca.weblite.webview.swing.WebViewComponent#setDownloadHandler}.
 *
 * <p><strong>Suppression / headless tests.</strong> Pass {@code null}
 * to {@code setDownloadHandler} to install a drop handler that
 * cancels every download synchronously without UI. Use {@link #DEFAULT}
 * explicitly to reset to the stock {@code ~/Downloads} policy —
 * {@code setDownloadHandler(null)} and
 * {@code setDownloadHandler(DEFAULT)} are NOT equivalent.
 *
 * <p><strong>Threading.</strong> The handler method runs on the
 * Swing Event Dispatch Thread, marshaled from the native engine's
 * worker thread (on macOS / Windows) or the GTK pump thread (on
 * Linux). The engine is suspended waiting for the destination
 * decision; no bytes are written until the handler returns. The
 * page's JavaScript thread continues running normally during the
 * decision (download events are not synchronous in the JS contract
 * — the page's {@code click} handler has already returned).
 *
 * <p><strong>EDT-deadlock hazard.</strong> Because the EDT is busy
 * running the handler, calling
 * {@code wv.evalAsync(js).get()} (or any other synchronous wait on
 * an EDT-scheduled task) from inside the handler DEADLOCKS — the
 * continuation can never run while the EDT is parked in the
 * handler. Handlers MAY freely call {@code wv.setUrl},
 * {@code wv.eval}, {@code wv.dispatch(r)} (those enqueue work on
 * the native UI thread; the enqueued work runs after the handler
 * returns and the engine has resumed).
 *
 * <p><strong>Exception isolation.</strong> Exceptions thrown from
 * the handler are caught by {@link DownloadDispatcher} and
 * forwarded to {@link Thread#getDefaultUncaughtExceptionHandler()};
 * they do not propagate to the native engine. When the handler
 * throws, the dispatcher returns {@code null} to the native side so
 * the download is cancelled cleanly.
 *
 * <p><strong>Platform coverage.</strong>
 * <ul>
 *   <li><strong>macOS</strong>: requires macOS 11.3 or later for
 *       the {@code WKDownload} API. On older macOS, the navigation
 *       delegate's {@code didBecomeDownload:} selectors do not
 *       exist and downloads are silently dropped. The native side
 *       probes {@code WKDownload} availability at engine creation
 *       and logs a one-line stderr warning if absent.</li>
 *   <li><strong>Windows</strong>: requires a modern Evergreen
 *       WebView2 Runtime that exposes the {@code ICoreWebView2_4}
 *       interface. Older runtimes silently drop downloads.</li>
 *   <li><strong>Linux</strong>: the default handler resolves
 *       {@code ~/Downloads} via {@code user.home}; it does not
 *       consult {@code XDG_DOWNLOAD_DIR}. Users wanting XDG can
 *       override {@code downloadRequested}.</li>
 * </ul>
 *
 * <p><strong>Same-origin caveat for the
 * {@code <a download="...">} attribute.</strong> The HTML spec
 * requires the {@code download} attribute to be honoured only for
 * same-origin URLs; cross-origin downloads use whatever filename
 * the {@code Content-Disposition} response header specifies. The
 * handler sees whichever the engine resolves —
 * {@link WebViewDownloadEvent#suggestedFilename()} reflects the
 * engine's resolution.
 */
public interface WebViewDownloadHandler {

    /**
     * Stock handler instance whose {@code downloadRequested} returns
     * {@code ~/Downloads/<deduped-filename>}. Stateless; safe to
     * share across components and threads. Returned by
     * {@link ca.weblite.webview.swing.WebViewComponent#getDownloadHandler}
     * when no caller has installed a custom handler. Pass to
     * {@code setDownloadHandler} to reset to defaults after a
     * previous custom or null installation.
     */
    WebViewDownloadHandler DEFAULT = new WebViewDownloadHandler() {};

    /**
     * Invoked when the embedded page initiates a download. Return
     * the destination {@link File} for the bytes to be written to,
     * or {@code null} to cancel the download before any bytes are
     * written.
     *
     * <p>The default implementation saves to
     * {@code ~/Downloads/<event.suggestedFilename()>}, creating
     * {@code ~/Downloads} if absent. When a file of that name
     * already exists, the implementation iterates
     * {@code <stem> (1).<ext>}, {@code <stem> (2).<ext>}, … up to
     * {@code (999)}; beyond the 999-collision sanity ceiling it
     * returns {@code null} (cancel). For names with no extension
     * (e.g. {@code Makefile}), the stamp is appended to the whole
     * name. For names with multiple dots (e.g. {@code archive.tar.gz}),
     * the split point is the LAST dot — produces
     * {@code archive.tar (1).gz} per browser convention.
     *
     * <p>Returning {@code null} cancels: the engine is told to
     * abort, no bytes are written, no temporary or partial file is
     * created. The page's link {@code click} handler has already
     * fired; the page learns nothing further about the cancelled
     * download in this iteration (lifecycle observation lands in
     * STORY-005-002).
     *
     * @param event the download request — never {@code null}.
     * @return the destination file, or {@code null} to cancel.
     */
    default File downloadRequested(WebViewDownloadEvent event) {
        File dir = new File(System.getProperty("user.home"), "Downloads");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String name = event.suggestedFilename();
        if (name == null || name.isEmpty()) {
            name = "download";
        }
        File candidate = new File(dir, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String stem = (dot <= 0) ? name : name.substring(0, dot);
        String ext = (dot <= 0) ? "" : name.substring(dot);
        for (int i = 1; i <= 999; i++) {
            String stamped = stem + " (" + i + ")" + ext;
            candidate = new File(dir, stamped);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }
}
