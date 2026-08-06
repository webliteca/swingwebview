/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when the
 * embedded page requests a popup ({@code window.open}, {@code target="_blank"}).
 *
 * <p><strong>Not part of the public application API.</strong>  Application
 * code customises popup behaviour by installing a
 * {@link WebViewPopupHandler} via
 * {@link ca.weblite.webview.swing.WebViewComponent#setPopupHandler}; this
 * interface is the underlying bridge the library wires from the Swing
 * component to the native engine via JNI.  It is {@code public} only because
 * the JNI bridge in {@code src_c/webview_embed.cpp} and
 * {@code windows/webview_embed.cc} needs to call methods on instances of it,
 * matching the constraint on {@link WebViewDialogCallback},
 * {@link WebViewClickCallback}, and {@link WebViewFocusCallback}.
 *
 * <p><strong>Threading.</strong> The native engine invokes these methods from
 * whatever native UI thread the engine runs on (AppKit main on macOS, the GTK
 * main thread on Linux, the WebView2 worker thread on Windows).
 * {@link #onPopupRequested} returns the allow/deny decision
 * <strong>synchronously</strong> — the native side is blocked awaiting the
 * return value and it MUST NOT be marshalled to the Swing EDT.
 * {@link #onPopupOpened} and {@link #onPopupClosed} are fire-and-forget
 * notifications; the library-provided implementation routes them through
 * {@link PopupDispatcher}, which marshals to the EDT via
 * {@link javax.swing.SwingUtilities#invokeLater}.
 */
public interface WebViewPopupCallback {

    /**
     * Invoked synchronously when the page requests a popup.  Return
     * {@code true} to allow it (the engine opens a native window loading
     * {@code targetUrl}) or {@code false} to block it ({@code window.open}
     * returns {@code null}).
     *
     * @param width  requested width, or {@code -1} when unspecified
     * @param height requested height, or {@code -1} when unspecified
     */
    boolean onPopupRequested(String targetUrl, String targetName,
                             boolean userGesture, int width, int height,
                             String pageUrl);

    /** Invoked after the popup's native window has been created and shown.
     *  {@code popupId} is an engine-assigned opaque handle correlating this
     *  open with its later {@link #onPopupClosed}. */
    void onPopupOpened(long popupId, String targetUrl, String targetName,
                       boolean userGesture, int width, int height,
                       String pageUrl);

    /** Invoked after the popup's native window has been destroyed. */
    void onPopupClosed(long popupId, String targetUrl, String pageUrl);
}
