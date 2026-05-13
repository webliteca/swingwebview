/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction.
 */
package ca.weblite.webview;

import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level wrapper around an embedded WebView native peer.
 *
 * <p>Unlike {@link WebView}, this class does not create or own a top-level
 * window and does not run its own event loop.  Instead, it attaches a native
 * web view as a child of an existing native window owned by another toolkit
 * (typically Swing/AWT via JAWT), and relies on the host's event loop for
 * input dispatch and rendering.
 *
 * <p>Most callers should not use this class directly; use the Swing components
 * in the {@code ca.weblite.webview.swing} package instead.
 */
public class EmbeddedWebView {

    private long peer;
    private final List<Object> heap = new ArrayList<Object>();
    private final Map<String, WebView.JavascriptCallback> bindings =
            new HashMap<String, WebView.JavascriptCallback>();

    private EmbeddedWebView(long peer) {
        this.peer = peer;
    }

    /**
     * Attach a WebView to the displayable parent Component.  The Component
     * must be heavyweight and already displayable (i.e. {@code addNotify()}
     * has been called).
     *
     * @param parent a heavyweight, displayable AWT Component to attach to.
     * @param debug enables developer tools on supported platforms.
     */
    public static EmbeddedWebView attach(Component parent, boolean debug) {
        if (parent == null) {
            throw new IllegalArgumentException("parent must not be null");
        }
        if (!parent.isDisplayable()) {
            throw new IllegalStateException(
                "parent is not displayable; addNotify() has not been called.");
        }
        long p = WebViewNative.webview_embed_create(parent, debug ? 1 : 0);
        if (p == 0L) {
            throw new IllegalStateException(
                "Native webview_embed_create returned 0; could not obtain a " +
                "native window handle or initialize the embedded WebView.");
        }
        return new EmbeddedWebView(p);
    }

    /**
     * @return the native pointer used by the underlying webview implementation.
     *         Returns 0 if this wrapper has already been disposed.
     */
    public long peer() {
        return peer;
    }

    /**
     * Position and size the embedded WebView within its parent.
     */
    public EmbeddedWebView setBounds(int x, int y, int width, int height) {
        checkAlive();
        WebViewNative.webview_embed_set_bounds(peer, x, y, width, height);
        return this;
    }

    /**
     * Show or hide the embedded WebView without destroying the engine.
     * Used to track Swing visibility changes (e.g. when the WebView is
     * inside a JTabbedPane and a different tab is selected).
     */
    public EmbeddedWebView setVisible(boolean visible) {
        checkAlive();
        WebViewNative.webview_embed_set_visible(peer, visible ? 1 : 0);
        return this;
    }

    /**
     * Navigate the WebView to the given URL.
     */
    public EmbeddedWebView navigate(String url) {
        checkAlive();
        WebViewNative.webview_embed_navigate(peer, url);
        return this;
    }

    /**
     * Add a script to be evaluated each time a new document is created.
     */
    public EmbeddedWebView addOnBeforeLoad(String js) {
        checkAlive();
        WebViewNative.webview_embed_init(peer, js);
        return this;
    }

    /**
     * Evaluate javascript in the current document.
     */
    public EmbeddedWebView eval(String js) {
        checkAlive();
        WebViewNative.webview_embed_eval(peer, js);
        return this;
    }

    /**
     * Bind a Java callback under {@code window.<name>} in the embedded page.
     */
    public EmbeddedWebView addJavascriptCallback(final String name,
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
        WebViewNative.webview_embed_bind(peer, name, fn, peer);
        return this;
    }

    /**
     * Dispatch a Runnable onto the WebView's native UI thread.
     */
    public EmbeddedWebView dispatch(final Runnable r) {
        checkAlive();
        final Runnable wrapper = new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } finally {
                    heap.remove(this);
                }
            }
        };
        heap.add(wrapper);
        WebViewNative.webview_embed_dispatch(peer, wrapper);
        return this;
    }

    /**
     * Pump one iteration of the platform UI loop.  On platforms where the
     * host (Swing/AWT) loop already drives the WebView's events, this is a
     * no-op.  See {@link WebViewNative#webview_embed_pump} for details.
     *
     * @param waitForEvent if true, blocks until at least one event has been
     *                     processed.
     * @return non-zero if events were processed.
     */
    public int pumpEvents(boolean waitForEvent) {
        checkAlive();
        return WebViewNative.webview_embed_pump(peer, waitForEvent ? 1 : 0);
    }

    /**
     * Release the native resources and detach from the parent.
     */
    public void dispose() {
        if (peer != 0L) {
            long p = peer;
            peer = 0L;
            WebViewNative.webview_embed_destroy(p);
            heap.clear();
            bindings.clear();
        }
    }

    private void checkAlive() {
        if (peer == 0L) {
            throw new IllegalStateException("EmbeddedWebView has been disposed.");
        }
    }
}
