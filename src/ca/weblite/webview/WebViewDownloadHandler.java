/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.File;

import java.awt.Window;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Handler interface for file downloads started by the embedded page:
 * a click on {@code <a href="…" download>}, a navigation whose response
 * carries {@code Content-Disposition: attachment}, and a navigation to
 * a body the engine will not render inline.
 *
 * <p><strong>Ownership model.</strong>  The native engine writes the
 * bytes; Java chooses where they go.  The handler answers one
 * question — <em>which file</em> — and then observes.  Java is never
 * handed a stream: the engine already holds the connection, the cookie
 * jar, and the authentication state needed to finish the transfer.
 *
 * <p><strong>Default behaviour.</strong>  All three methods are
 * {@code default}.  {@link #downloadRequested} shows a modal
 * {@link JFileChooser} save dialog anchored on the host window,
 * pre-filled with {@link WebViewDownloadEvent#suggestedFileName()},
 * and confirms before replacing an existing file.  The two
 * notifications do nothing.  The framework's {@link #DEFAULT} instance
 * is pre-installed on every
 * {@link ca.weblite.webview.swing.WebViewComponent}.
 *
 * <p><strong>Custom behaviour.</strong>  Implement
 * {@link #downloadRequested} to choose the destination programmatically
 * — that is all an application needs to route downloads into its own
 * folder.  Return {@code null} to <strong>refuse</strong> the download:
 * nothing is written anywhere, including into the platform's own
 * default downloads folder.  Install with
 * {@link ca.weblite.webview.swing.WebViewComponent#setDownloadHandler}.
 *
 * <p><strong>Suppression / headless tests.</strong>  Pass {@code null}
 * to {@code setDownloadHandler} to install a drop handler that refuses
 * every download with no UI and no file.  Use {@link #DEFAULT}
 * explicitly to reset to the stock save dialog — note that
 * {@code setDownloadHandler(null)} and
 * {@code setDownloadHandler(DEFAULT)} are NOT equivalent.  Reading
 * {@code null} as "reset to the default" would produce an application
 * that silently refuses every download.
 *
 * <p><strong>Threading.</strong>  Every method runs on the Swing Event
 * Dispatch Thread, but they marshal differently:
 * <ul>
 *   <li>{@link #downloadRequested} is <em>synchronous</em>.  The
 *       engine's thread is blocked awaiting the destination, and the
 *       dispatcher marshals with
 *       {@link SwingUtilities#invokeAndWait} — the same shape the
 *       dialog channel uses for {@code alert} / {@code confirm} /
 *       {@code prompt}.</li>
 *   <li>{@link #downloadProgress} and {@link #downloadCompleted} are
 *       <em>fire-and-forget</em>, marshalled with
 *       {@link SwingUtilities#invokeLater}.  They cannot cancel a
 *       download and must not block.</li>
 * </ul>
 *
 * <p><strong>EDT-deadlock hazard.</strong>  Because the EDT is busy
 * running {@link #downloadRequested} while a native thread waits for
 * it, calling {@code wv.evalAsync(js).get()} (or any other synchronous
 * wait on an EDT-scheduled task) from inside that method DEADLOCKS —
 * the continuation can never run while the EDT is parked in the
 * handler.  This is the identical hazard documented on
 * {@link WebViewDialogHandler}.
 *
 * <p><strong>Progress is lossy; completion is not.</strong>  Progress
 * events are coalesced so at most one per download is queued on the
 * EDT at a time; a handler sees the latest counts rather than every
 * chunk, and must not do expensive work in
 * {@link #downloadProgress}.  {@link #downloadCompleted} is delivered
 * <em>exactly once</em> per download, whatever the backend emits.
 *
 * <p><strong>Untrusted filenames.</strong>
 * {@link WebViewDownloadEvent#suggestedFileName()} is already reduced
 * to a safe leaf name — no separators, no {@code ..}, no reserved
 * Windows device name, never empty.  A handler may join it onto a
 * directory of its own choosing, but must not join it onto a parent
 * path the page can influence.
 *
 * <p><strong>Exception isolation.</strong>  Exceptions thrown from a
 * handler method are caught by {@link DownloadDispatcher} and forwarded
 * to {@link Thread#getDefaultUncaughtExceptionHandler()}; they do not
 * propagate to the native engine.  A throwing
 * {@link #downloadRequested} is treated as a refusal, so the engine's
 * thread is always released cleanly.
 *
 * <p><strong>Platform coverage.</strong>  macOS heavyweight WKWebView
 * (Canvas 23), Linux WebKitGTK in both modes and in popups (Canvas 24),
 * and Windows WebView2 (Canvas 25) are all wired.  Windows requires a
 * WebView2 runtime exposing {@code ICoreWebView2_4}; on an older
 * runtime the engine keeps its built-in download handling and this
 * handler is not invoked.  macOS requires 11.3 or newer for the same
 * reason.
 */
public interface WebViewDownloadHandler {

    /**
     * Stock handler instance whose methods invoke the {@code default}
     * implementations as-is (a Swing save dialog anchored on the host
     * window; no-op notifications).  Stateless; safe to share across
     * components and threads.  Returned by
     * {@link ca.weblite.webview.swing.WebViewComponent#getDownloadHandler}
     * when no caller has installed a custom handler.  Pass to
     * {@code setDownloadHandler} to reset to defaults after a previous
     * custom or null installation.
     */
    WebViewDownloadHandler DEFAULT = new WebViewDownloadHandler() {};

    /**
     * Invoked when the page starts a download, to decide where the
     * bytes are written.  Default: shows a modal {@link JFileChooser}
     * save dialog pre-filled with
     * {@link WebViewDownloadEvent#suggestedFileName()}, confirming
     * before replacing an existing file.
     *
     * <p>Runs on the EDT with the engine's thread blocked; keep it
     * short and never wait on another EDT task from inside it.
     *
     * @return the file to write to, or {@code null} to refuse the
     *         download entirely — nothing is written anywhere, and
     *         {@link #downloadCompleted} reports the refusal once.
     */
    default File downloadRequested(WebViewDownloadEvent event) {
        Window host = SwingUtilities.getWindowAncestor(event.source());
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Download");
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(),
            event.suggestedFileName()));
        int r = chooser.showSaveDialog(host);
        if (r != JFileChooser.APPROVE_OPTION) return null;
        File chosen = chooser.getSelectedFile();
        if (chosen == null) return null;
        if (chosen.exists()) {
            int ok = JOptionPane.showConfirmDialog(host,
                chosen.getName() + " already exists.\nReplace it?",
                "Replace File?", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return null;
        }
        return chosen;
    }

    /**
     * Invoked as bytes arrive.  Default: does nothing.
     *
     * <p>Runs on the EDT, fire-and-forget.  Events are coalesced — at
     * most one is queued per download at a time — so this reports the
     * latest counts rather than every chunk.  Do not do expensive work
     * here; a large download can call it many times a second.
     */
    default void downloadProgress(WebViewDownloadProgressEvent event) { }

    /**
     * Invoked once, when the download reaches its terminal outcome —
     * finished, failed, or refused.  Default: does nothing.
     *
     * <p>Runs on the EDT, fire-and-forget.  Delivered <em>exactly
     * once</em> per download, so a handler may show a completion
     * notification without guarding against duplicates.
     */
    default void downloadCompleted(WebViewDownloadCompleteEvent event) { }
}
