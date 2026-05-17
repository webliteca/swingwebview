/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Callback invoked when the user presses any mouse button inside the
 * embedded heavyweight WebView's native surface.  Registered on
 * {@link EmbeddedWebView#setClickCallback}.
 *
 * <p>The heavyweight native peer receives mouse events directly from the
 * OS and does not forward them through AWT's event queue, so listeners
 * registered via {@link java.awt.Toolkit#addAWTEventListener} -- including
 * Swing's internal {@code BasicPopupMenuUI.MouseGrabber} that closes open
 * {@code JPopupMenu}s on outside clicks -- never see clicks that land
 * inside the WebView.  This callback exists to surface those clicks back
 * to Java so Swing-side reactions can run as if the click had been a
 * normal AWT mouse press.
 *
 * <p>The callback is invoked from a native thread (GTK main thread on
 * Linux, AppKit main thread on macOS, the WebView2 worker on Windows);
 * implementations MUST marshal to the EDT themselves before touching any
 * Swing state.
 */
@FunctionalInterface
public interface WebViewClickCallback {

    /**
     * Invoked once per native mouse-button press inside the embedded
     * WebView.  Fires for every button (left, right, middle) so the
     * Swing-side reaction matches the dismiss-on-any-outside-click
     * behaviour of standard Swing popups.
     */
    void invoke();
}
