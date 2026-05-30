/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when
 * the embedded page initiates a download. The single method
 * returns the destination path synchronously; the native side
 * waits for the return value before resolving the engine's
 * deferral and beginning the byte transfer.
 *
 * <p><strong>Not part of the public application API.</strong>
 * Application code customises download behaviour by installing a
 * {@link WebViewDownloadHandler} via
 * {@link ca.weblite.webview.swing.WebViewComponent#setDownloadHandler};
 * this interface is the underlying bridge that the library wires
 * from the Swing component to the native engine via JNI. It is
 * {@code public} only because the JNI bridge in
 * {@code src_c/webview_embed.cpp} and {@code windows/webview_embed.cc}
 * needs to call methods on instances of it, and Java has no
 * cross-package-but-non-public access modifier. The same constraint
 * applies to {@link WebViewDialogCallback},
 * {@link WebViewClickCallback}, and
 * {@link WebViewFocusCallback}.
 *
 * <p><strong>Threading.</strong> The native engine invokes this
 * method from a short-lived worker thread spawned by the
 * download-starting callback on macOS / Windows (so the engine's
 * UI thread — AppKit main / WebView2 worker — is not parked
 * during the EDT hop), or directly from the GTK pump thread on
 * Linux (which is already decoupled from AWT's EDT).
 * Implementations MUST marshal to the Swing EDT before touching
 * any Swing state. The library-provided implementation in
 * {@code WebViewHeavyweightComponent} and
 * {@code WebViewLightweightComponent} delegates to
 * {@link DownloadDispatcher}, which performs the EDT hop via
 * {@link javax.swing.SwingUtilities#invokeAndWait}.
 *
 * <p><strong>No-throw contract.</strong> Implementations MUST NOT
 * propagate exceptions to the native side — they catch and
 * forward to {@link Thread#getDefaultUncaughtExceptionHandler()}
 * via the dispatcher and return {@code null} to indicate cancel.
 * A propagated Java exception crossing the JNI boundary would
 * crash the native engine.
 */
public interface WebViewDownloadCallback {

    /**
     * Invoked when the embedded page initiates a download.
     *
     * @param suggestedFilename engine-supplied filename (may be
     *        empty when the engine reports none).
     * @param sourceUrl URL the response was fetched from.
     * @param mimeType engine-supplied MIME type (may be empty).
     * @param totalBytes engine-supplied content length, or {@code -1}
     *        when unknown.
     * @return absolute path string of the chosen destination, or
     *         {@code null} / empty string to cancel.
     */
    String onDownloadStarting(String suggestedFilename, String sourceUrl,
                              String mimeType, long totalBytes);
}
