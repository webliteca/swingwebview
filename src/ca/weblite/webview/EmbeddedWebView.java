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
import java.util.concurrent.CompletableFuture;

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

    /**
     * Per-engine fan-out hub for {@link #evalAsync(String)}.  Holds the
     * in-flight {@code requestId -> CompletableFuture<String>} map and
     * the dispose drain.  Constructed with {@code marshalToEdt = true}
     * so future continuations land on the Swing EDT, matching the
     * existing {@code ConsoleListener} / {@code WebViewMouseListener}
     * contracts on {@code WebViewComponent}.  Its {@code EvalSink}
     * issues {@code webview_embed_eval} when {@code peer != 0}; the
     * sink-level guard is belt-and-suspenders against a destroy that
     * races a dispatch.
     */
    private final EvalDispatcher evalDispatcher;

    private EmbeddedWebView(long peer) {
        this.peer = peer;
        this.evalDispatcher = new EvalDispatcher(new EvalDispatcher.EvalSink() {
            @Override
            public void eval(String js) {
                long p = EmbeddedWebView.this.peer;
                if (p != 0L) {
                    WebViewNative.webview_embed_eval(p, js);
                }
            }
        }, true, "EmbeddedWebView");
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
        final EmbeddedWebView ewv = new EmbeddedWebView(p);
        // Install the evalAsync bridge BEFORE returning so the SHIM_JS is
        // in place at document-start for every subsequent navigation, and
        // the __webview_eval_result__ resolver binding is ready before
        // the heavyweight component's createPeer starts replaying user
        // config.  Goes directly through WebViewNative (not through
        // EmbeddedWebView.addJavascriptCallback) so callers can't reroute
        // the binding by registering a colliding name through the
        // public API.
        WebViewNative.webview_embed_init(p, EvalDispatcher.SHIM_JS);
        WebViewNativeCallback evalCb = new WebViewNativeCallback() {
            @Override
            public void invoke(String arg, long wv) {
                ewv.evalDispatcher.dispatch(arg);
            }
        };
        ewv.heap.add(evalCb);
        WebViewNative.webview_embed_bind(p, EvalDispatcher.CHANNEL_NAME, evalCb, p);
        return ewv;
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
     * Hand text-input / keyboard focus to the embedded WebView.  When
     * the WebView is hosted inside a Swing heavyweight component the
     * AWT side keeps system keyboard focus until told otherwise, so
     * typing in fields (form inputs, address bars, contenteditable
     * regions) won't reach the embedded engine unless this is called.
     * The Swing wrapper auto-calls this on mouse press and on AWT
     * focus-gained events.
     */
    public EmbeddedWebView requestFocus() {
        checkAlive();
        WebViewNative.webview_embed_request_focus(peer);
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
     * Evaluate JavaScript in the current document and return a future
     * that completes with the JSON-stringified result.  See
     * {@link EvalDispatcher#evalAsync(String)} for the full contract.
     *
     * <p>Unlike {@link #eval(String)} this method does NOT call
     * {@code checkAlive} and does NOT throw on dispose — lifecycle
     * failures surface as exceptional future completions instead, so
     * callers never have to wrap {@code evalAsync} in try/catch.  When
     * {@code peer == 0L} (disposed) returns an already-failed future
     * carrying
     * {@code IllegalStateException("EmbeddedWebView has been disposed.")}
     * without touching the dispatcher; otherwise delegates to
     * {@link EvalDispatcher#evalAsync(String)}.
     *
     * <p>Future continuations complete on the Swing EDT.
     *
     * @param js the JS snippet; must not be null.
     * @return a future that resolves to the JSON-stringified result.
     * @throws NullPointerException if {@code js} is null.
     */
    public CompletableFuture<String> evalAsync(String js) {
        if (js == null) throw new NullPointerException("js");
        if (peer == 0L) {
            CompletableFuture<String> f = new CompletableFuture<String>();
            f.completeExceptionally(
                new IllegalStateException("EmbeddedWebView has been disposed."));
            return f;
        }
        return evalDispatcher.evalAsync(js);
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
     * Open the platform's native DevTools / Web Inspector in a separate OS
     * window.  Returns {@code true} if an inspector window was actually
     * opened (or focused-existing), {@code false} otherwise.  See
     * {@link WebViewNative#webview_embed_open_devtools} for the per-platform
     * semantics.
     */
    public boolean openDevTools() {
        checkAlive();
        return WebViewNative.webview_embed_open_devtools(peer) == 1;
    }

    /**
     * Execute a platform editing command (Cut / Copy / Paste / Select-All)
     * against the embedded WebView's current focused element.  The native
     * call marshals to the correct UI thread (AppKit main / GTK main /
     * WebView2 worker) on its own, so this is safe to invoke from the EDT
     * without blocking.
     *
     * @param cmd the editing command to perform; must not be null.
     */
    public EmbeddedWebView executeEditingCommand(EditingCommand cmd) {
        if (cmd == null) {
            throw new NullPointerException("cmd");
        }
        checkAlive();
        WebViewNative.webview_embed_execute_editing_command(peer, cmd.getNativeId());
        return this;
    }

    /**
     * @return {@code true} if the native WebView (or one of its inner views) is
     * the current first responder of its window.  macOS-specific; Linux /
     * Windows always return {@code false}.  Returns {@code false} after dispose
     * without throwing.
     */
    public boolean isNativeFirstResponder() {
        if (peer == 0L) return false;
        return WebViewNative.webview_embed_is_native_first_responder(peer) == 1;
    }

    /**
     * Register (or clear, by passing {@code null}) a callback invoked when the
     * native WebView becomes / resigns first responder.  The callback runs on
     * a native thread; implementations must marshal to the EDT before touching
     * Swing state.
     */
    public EmbeddedWebView setFocusCallback(WebViewFocusCallback cb) {
        checkAlive();
        if (cb != null) {
            heap.add(cb);
        }
        WebViewNative.webview_embed_set_focus_callback(peer, cb);
        return this;
    }

    /**
     * Register (or clear, by passing {@code null}) a callback invoked once per
     * native mouse-button press inside the embedded WebView's surface.  Used
     * by the heavyweight component to dismiss any open Swing popup when the
     * user clicks into the WebView -- the native peer takes clicks directly
     * from the OS so AWT's {@code BasicPopupMenuUI.MouseGrabber} listener
     * never fires for them.  Fires for left / right / middle buttons.  The
     * callback runs on a native thread; implementations must marshal to the
     * EDT before touching Swing state.
     */
    public EmbeddedWebView setClickCallback(WebViewClickCallback cb) {
        checkAlive();
        if (cb != null) {
            heap.add(cb);
        }
        WebViewNative.webview_embed_set_click_callback(peer, cb);
        return this;
    }

    /**
     * Windows-only: force Win32 keyboard focus back to the AWT-owned parent
     * HWND, so subsequent keystrokes route to AWT instead of the WebView2
     * child HWND.  Used by the Java-side global focus-owner listener when
     * AWT moves its focus owner to a Swing component outside the WebView.
     * macOS / Linux: no-op.
     */
    public EmbeddedWebView releaseNativeFocus() {
        checkAlive();
        WebViewNative.webview_embed_release_native_focus(peer);
        return this;
    }

    /**
     * Release the native resources and detach from the parent.
     */
    public void dispose() {
        if (peer != 0L) {
            long p = peer;
            // Drain any pending evalAsync futures FIRST so callers
            // waiting on .get() wake up with IllegalStateException
            // rather than hanging forever, and the dispatcher's
            // disposed flag flips so subsequent evalAsync calls
            // return an already-failed future without touching the
            // engine.  Runs before the heap clear / native destroy so
            // the resolver-binding callback (anchored in heap) is
            // still reachable in case a late JS-side callback fires
            // during the destroy sequence — but the drain ensures
            // such a late callback finds an empty pending map and
            // silently drops.
            evalDispatcher.disposeAllPending();
            // Clear any native focus callback BEFORE we hand off to destroy,
            // so the swizzled responder hooks never fire into a freed Java
            // global ref.  checkAlive would still pass here since peer is
            // not yet zero.
            try {
                WebViewNative.webview_embed_set_focus_callback(p, null);
            } catch (Throwable ignored) {
                // Don't let a clear-callback failure prevent destroy.
            }
            // Same reasoning for the click callback: the native click hook
            // (gtk gesture pressed handler on Linux, swizzled mouseDown:
            // on macOS, WM_PARENTNOTIFY on Windows) can fire any time the
            // user has the pointer over the WebView, including races
            // against teardown.  Clearing the global ref here guarantees
            // a late press lands on a null callback field and silently
            // no-ops instead of invoking JNI on a freed ref.
            try {
                WebViewNative.webview_embed_set_click_callback(p, null);
            } catch (Throwable ignored) {
                // Don't let a clear-callback failure prevent destroy.
            }
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
