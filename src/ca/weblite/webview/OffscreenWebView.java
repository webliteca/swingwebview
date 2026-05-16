/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level wrapper around an offscreen (lightweight) WebView native peer.
 *
 * <p>Unlike {@link EmbeddedWebView}, the native engine here renders into
 * a private offscreen surface (a {@code GtkOffscreenWindow} on Linux) and
 * never touches the user's screen.  Java owns the paint cycle: pull
 * pixels via {@link #snapshot} into a {@code BufferedImage}, render them
 * into a {@code JComponent}.
 *
 * <p>Currently Linux only -- macOS / Windows entry points are stubs that
 * return 0 from create.
 *
 * <p>Most callers should not use this class directly; use
 * {@link ca.weblite.webview.swing.WebViewLightweightComponent}.
 */
public class OffscreenWebView {

    private long peer;
    private final List<Object> heap = new ArrayList<Object>();
    private final Map<String, WebView.JavascriptCallback> bindings =
        new HashMap<String, WebView.JavascriptCallback>();

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

    /**
     * Inject a mouse button press / release into the offscreen WebView.
     * GDK modifier bits live in {@link GdkInput#GDK_SHIFT_MASK} etc.
     */
    public void mouseButton(boolean press, int x, int y, int button,
                            int modifiers, int clickCount) {
        checkAlive();
        WebViewNative.webview_offscreen_mouse_button(
            peer, press ? 1 : 0, x, y, button, modifiers, clickCount);
    }

    /** Inject a mouse-move event. */
    public void mouseMotion(int x, int y, int modifiers) {
        checkAlive();
        WebViewNative.webview_offscreen_mouse_motion(peer, x, y, modifiers);
    }

    /** Inject a smooth-scroll event. */
    public void mouseScroll(int x, int y, double dx, double dy, int modifiers) {
        checkAlive();
        WebViewNative.webview_offscreen_mouse_scroll(peer, x, y, dx, dy,
                                                    modifiers);
    }

    /** Inject a key event (press={@code true} for press, {@code false}
     *  for release).  See {@link GdkInput} for keyval translation. */
    public void keyEvent(boolean press, int keyval, int modifiers,
                         boolean isModifierKey) {
        checkAlive();
        WebViewNative.webview_offscreen_key_event(
            peer, press ? 1 : 0, keyval, modifiers,
            isModifierKey ? 1 : 0);
    }

    /**
     * Add a script to be evaluated each time a new document is created.
     * Mirrors {@link EmbeddedWebView#addOnBeforeLoad}.
     */
    public OffscreenWebView addOnBeforeLoad(String js) {
        checkAlive();
        WebViewNative.webview_offscreen_init(peer, js);
        return this;
    }

    /**
     * Evaluate JavaScript in the current document.  Mirrors
     * {@link EmbeddedWebView#eval}.
     */
    public OffscreenWebView eval(String js) {
        checkAlive();
        WebViewNative.webview_offscreen_eval(peer, js);
        return this;
    }

    /**
     * Bind a Java callback under {@code window.<name>} in the offscreen
     * WebView's pages.  Mirrors
     * {@link EmbeddedWebView#addJavascriptCallback}.
     */
    public OffscreenWebView addJavascriptCallback(final String name,
                                                  final WebView.JavascriptCallback cb) {
        checkAlive();
        bindings.put(name, cb);
        WebViewNativeCallback fn = new WebViewNativeCallback() {
            @Override
            public void invoke(String arg, long wv) {
                WebView.JavascriptCallback c = bindings.get(name);
                if (c != null) {
                    c.run(arg);
                }
            }
        };
        heap.add(fn);
        WebViewNative.webview_offscreen_bind(peer, name, fn, peer);
        return this;
    }

    /**
     * Open the WebKitGTK Web Inspector for the offscreen WebView in a
     * separate OS window.  Returns {@code true} if opened, {@code false}
     * if developer-extras was not enabled at create time or the engine
     * has no inspector.
     */
    public boolean openDevTools() {
        checkAlive();
        return WebViewNative.webview_offscreen_open_devtools(peer) == 1;
    }

    /** Release native resources. */
    public void dispose() {
        if (peer != 0L) {
            long p = peer;
            peer = 0L;
            WebViewNative.webview_offscreen_destroy(p);
            heap.clear();
            bindings.clear();
        }
    }

    private void checkAlive() {
        if (peer == 0L) {
            throw new IllegalStateException(
                "OffscreenWebView has been disposed.");
        }
    }
}
