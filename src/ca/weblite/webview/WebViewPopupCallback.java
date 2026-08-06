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

    /**
     * Invoked synchronously when the page requests a popup, to obtain the
     * enriched disposition.  Returns a {@link PopupDisposition} ordinal —
     * {@code 0 = BLOCK}, {@code 1 = NATIVE_WINDOW}, {@code 2 = ADOPT} — which
     * the native side switches on to block, open a native window, or retain
     * the child for adoption.  Like {@link #onPopupRequested}, the native
     * side is blocked awaiting the return value and it MUST NOT be marshalled
     * to the EDT.
     *
     * <p>The default derives the disposition from {@link #onPopupRequested}
     * ({@code true → NATIVE_WINDOW.ordinal()}, {@code false → BLOCK.ordinal()})
     * so an engine that only calls {@code onPopupRequested} keeps working.
     */
    default int onPopupDisposition(String targetUrl, String targetName,
                                   boolean userGesture, int width, int height,
                                   String pageUrl) {
        return onPopupRequested(targetUrl, targetName, userGesture,
                                width, height, pageUrl)
            ? PopupDisposition.NATIVE_WINDOW.ordinal()
            : PopupDisposition.BLOCK.ordinal();
    }

    /** Invoked (as a notification) after an {@code ADOPT} disposition, once
     *  the opener-linked child has been created and retained under a hidden
     *  holder.  {@code popupId} is the engine-assigned handle the application
     *  passes to {@code WebViewComponent.adoptPopup}.  Default is a no-op so
     *  engines that have not implemented adoption need not call it. */
    default void onPopupAdoptable(long popupId, String targetUrl,
                                  String targetName, boolean userGesture,
                                  int width, int height, String pageUrl) {
    }

    /** Invoked after the popup's native window has been created and shown.
     *  {@code popupId} is an engine-assigned opaque handle correlating this
     *  open with its later {@link #onPopupClosed}. */
    void onPopupOpened(long popupId, String targetUrl, String targetName,
                       boolean userGesture, int width, int height,
                       String pageUrl);

    /** Invoked after the popup's native window has been destroyed. */
    void onPopupClosed(long popupId, String targetUrl, String pageUrl);
}
