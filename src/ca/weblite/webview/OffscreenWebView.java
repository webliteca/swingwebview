/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Low-level wrapper around an offscreen (lightweight) WebView native peer.
 *
 * <p>Unlike {@link EmbeddedWebView}, the native engine here renders into
 * a private offscreen surface (a {@code GtkOffscreenWindow} on Linux) and
 * never touches the user's screen.  Java owns the paint cycle: pull
 * pixels via {@link #snapshot} into a {@code BufferedImage}, render them
 * into a {@code JComponent}.
 *
 * <p>Phase 1 status: rendering only.  Mouse/keyboard event forwarding
 * isn't wired yet.  Currently Linux only -- macOS / Windows entry points
 * are stubs that return 0.
 *
 * <p>Most callers should not use this class directly; use
 * {@link ca.weblite.webview.swing.WebViewLightweightComponent}.
 */
public class OffscreenWebView {

    private long peer;

    private OffscreenWebView(long peer) {
        this.peer = peer;
    }

    /**
     * Create the offscreen engine with the given initial pixel dimensions.
     * Returns null on unsupported platform or native failure.
     */
    public static OffscreenWebView create(int width, int height, boolean debug) {
        long p = WebViewNative.webview_offscreen_create(
            Math.max(1, width), Math.max(1, height), debug ? 1 : 0);
        if (p == 0L) return null;
        return new OffscreenWebView(p);
    }

    /** @return the native peer pointer, or 0 if disposed. */
    public long peer() {
        return peer;
    }

    /** Resize the offscreen viewport. */
    public OffscreenWebView setSize(int width, int height) {
        checkAlive();
        WebViewNative.webview_offscreen_resize(peer,
            Math.max(1, width), Math.max(1, height));
        return this;
    }

    /** Navigate to a URL. */
    public OffscreenWebView navigate(String url) {
        checkAlive();
        WebViewNative.webview_offscreen_navigate(peer, url);
        return this;
    }

    /**
     * Copy the current pixel contents into the given Java int[].  The
     * array is expected to hold at least {@code w*h} pixels in
     * 0xAARRGGBB layout (matches {@code BufferedImage.TYPE_INT_ARGB}).
     */
    public void snapshot(int[] pixels, int width, int height) {
        checkAlive();
        WebViewNative.webview_offscreen_snapshot(peer, pixels, width, height);
    }

    /** Release native resources. */
    public void dispose() {
        if (peer != 0L) {
            long p = peer;
            peer = 0L;
            WebViewNative.webview_offscreen_destroy(p);
        }
    }

    private void checkAlive() {
        if (peer == 0L) {
            throw new IllegalStateException(
                "OffscreenWebView has been disposed.");
        }
    }
}
