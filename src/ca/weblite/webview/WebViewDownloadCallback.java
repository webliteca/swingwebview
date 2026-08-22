/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when the
 * embedded page starts, advances, or finishes a file download.
 *
 * <p><strong>Not part of the public application API.</strong>
 * Application code customises download behaviour by installing a
 * {@link WebViewDownloadHandler} via
 * {@link ca.weblite.webview.swing.WebViewComponent#setDownloadHandler};
 * this interface is the underlying bridge the library wires from the
 * Swing component to the native engine via JNI.  It is {@code public}
 * only because the JNI bridge in {@code src_c/webview_embed.cpp} and
 * {@code windows/webview_embed.cc} needs to call methods on instances
 * of it, matching the constraint on {@link WebViewDialogCallback},
 * {@link WebViewPopupCallback}, {@link WebViewClickCallback}, and
 * {@link WebViewFocusCallback}.
 *
 * <p><strong>Threading.</strong>  The native engine invokes these
 * methods from whatever native UI thread it runs on (AppKit main on
 * macOS, the GTK main thread on Linux, the WebView2 worker thread on
 * Windows).  {@link #onDownloadRequested} returns the destination
 * <strong>synchronously</strong> — the native side is blocked awaiting
 * the return value and it MUST NOT be marshalled to the Swing EDT by
 * the native side; the library-provided implementation routes it
 * through {@link DownloadDispatcher}, which performs the EDT hop with
 * {@link javax.swing.SwingUtilities#invokeAndWait}.
 * {@link #onDownloadProgress} and {@link #onDownloadCompleted} are
 * fire-and-forget notifications the dispatcher marshals with
 * {@code invokeLater}.
 *
 * <p>Note that the progress and completion signatures do not carry the
 * destination file.  The dispatcher already knows it from the request
 * and correlates by {@code id}, which keeps the native side from
 * having to retain the path across the life of a transfer.
 */
public interface WebViewDownloadCallback {

    /**
     * Invoked synchronously when a download starts, to obtain its
     * destination.
     *
     * @param id                a per-engine identity for this download,
     *                          repeated on every later event for it
     * @param url               the URL the bytes come from
     * @param suggestedFileName the server-suggested name, unsanitised;
     *                          the dispatcher sanitises it
     * @param mimeType          the server-declared content type, or
     *                          {@code null}
     * @param totalBytes        expected size, or {@code -1} when the
     *                          server declared none
     * @param pageUrl           the page that initiated the download
     * @return the absolute path to write to, or {@code null} to refuse
     *         the download (the native side must then cancel the
     *         transfer, not fall back to a default destination)
     */
    String onDownloadRequested(long id, String url, String suggestedFileName,
                               String mimeType, long totalBytes,
                               String pageUrl);

    /**
     * Invoked as bytes arrive.  Fire-and-forget.
     *
     * @param receivedBytes running total written so far
     * @param totalBytes    expected size, or {@code -1} when unknown
     */
    void onDownloadProgress(long id, long receivedBytes, long totalBytes);

    /**
     * Invoked when the download reaches a terminal state.
     * Fire-and-forget.  The native side guards against emitting this
     * twice, and the dispatcher latches it regardless.
     *
     * @param failureReason a description of the failure, or {@code null}
     *                      / empty on success
     */
    void onDownloadCompleted(long id, boolean success, String failureReason,
                             long receivedBytes);
}
