/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Low-level wrapper around an offscreen (lightweight) WebView native peer.
 *
 * <p>Unlike {@link EmbeddedWebView}, the native engine here renders into
 * a private offscreen surface (a {@code GtkOffscreenWindow} on Linux) and
 * never touches the user's screen.  Java owns the paint cycle: pull
 * pixels via {@link #snapshot} into a {@code BufferedImage}, render them
 * into a {@code JComponent}.
 *
 * <p>Exposes the same JS-interaction surface as {@link EmbeddedWebView}:
 * {@link #eval}, {@link #addOnBeforeLoad}, {@link #addJavascriptCallback}
 * (which appears as {@code window.<name>} inside the page), and
 * {@link #dispatch} for marshaling work onto the native UI thread.  The
 * {@code window.<name>(...)} shim and round-trip envelope are shared
 * verbatim with the embed engine so page authors see an identical
 * contract in both modes.
 *
 * <p>Currently Linux only -- macOS / Windows entry points are stubs that
 * return 0 from create, so {@link #create} returns {@code null} on those
 * platforms.
 *
 * <p>Most callers should not use this class directly; use
 * {@link ca.weblite.webview.swing.WebViewLightweightComponent}.
 */
public class OffscreenWebView {

    private long peer;
    private final List<Object> heap = new ArrayList<Object>();
    private final Map<String, WebView.JavascriptCallback> bindings =
            new LinkedHashMap<String, WebView.JavascriptCallback>();

    /**
     * Per-engine fan-out hub for {@link #evalAsync(String)}.  Holds the
     * in-flight {@code requestId -> CompletableFuture<String>} map and
     * the dispose drain.  Constructed with {@code marshalToEdt = true}
     * so future continuations land on the Swing EDT, matching the
     * existing {@code ConsoleListener} / {@code WebViewMouseListener}
     * contracts on {@code WebViewComponent}.  Its {@code EvalSink}
     * issues {@code webview_offscreen_eval} when {@code peer != 0}; the
     * sink-level guard is belt-and-suspenders against a destroy that
     * races a dispatch.
     */
    private final EvalDispatcher evalDispatcher;

    /**
     * Per-engine hub for {@link #addJavascriptFunction} — value-returning
     * JS&rarr;Java functions.  Mirrors the offscreen {@code evalDispatcher}
     * wiring; the {@code FunctionSink} issues {@code webview_offscreen_eval}
     * / {@code webview_offscreen_init} when {@code peer != 0}.
     */
    private final FunctionDispatcher functionDispatcher;

    private OffscreenWebView(long peer) {
        this.peer = peer;
        this.evalDispatcher = new EvalDispatcher(new EvalDispatcher.EvalSink() {
            @Override
            public void eval(String js) {
                long p = OffscreenWebView.this.peer;
                if (p != 0L) {
                    WebViewNative.webview_offscreen_eval(p, js);
                }
            }
        }, true, "OffscreenWebView");
        this.functionDispatcher = new FunctionDispatcher(
            new FunctionDispatcher.FunctionSink() {
                @Override
                public void eval(String js) {
                    long p = OffscreenWebView.this.peer;
                    if (p != 0L) {
                        WebViewNative.webview_offscreen_eval(p, js);
                    }
                }
                @Override
                public void addOnBeforeLoad(String js) {
                    long p = OffscreenWebView.this.peer;
                    if (p != 0L) {
                        WebViewNative.webview_offscreen_init(p, js);
                    }
                }
            }, "OffscreenWebView");
    }

    /**
     * Create the offscreen engine with the given initial pixel dimensions.
     * Returns null on unsupported platform or native failure.
     */
    public static OffscreenWebView create(int width, int height, boolean debug) {
        long p = WebViewNative.webview_offscreen_create(
            Math.max(1, width), Math.max(1, height), debug ? 1 : 0);
        if (p == 0L) return null;
        final OffscreenWebView ow = new OffscreenWebView(p);
        // Install the evalAsync bridge BEFORE returning so the SHIM_JS is
        // in place at document-start for every subsequent navigation, and
        // the __webview_eval_result__ resolver binding is ready before
        // the lightweight component's addNotify starts replaying user
        // config.  Goes directly through WebViewNative (not through
        // OffscreenWebView.addJavascriptCallback) so callers can't reroute
        // the binding by registering a colliding name through the
        // public API.
        WebViewNative.webview_offscreen_init(p, EvalDispatcher.SHIM_JS);
        WebViewNativeCallback evalCb = new WebViewNativeCallback() {
            @Override
            public void invoke(String arg, long wv) {
                ow.evalDispatcher.dispatch(arg);
            }
        };
        ow.heap.add(evalCb);
        WebViewNative.webview_offscreen_bind(p, EvalDispatcher.CHANNEL_NAME, evalCb, p);
        // Install the addJavascriptFunction bridge the same way.
        WebViewNative.webview_offscreen_init(p, FunctionDispatcher.SHIM_JS);
        WebViewNativeCallback fnCb = new WebViewNativeCallback() {
            @Override
            public void invoke(String arg, long wv) {
                ow.functionDispatcher.dispatch(arg);
            }
        };
        ow.heap.add(fnCb);
        WebViewNative.webview_offscreen_bind(p, FunctionDispatcher.INBOUND_CHANNEL, fnCb, p);
        return ow;
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
     * Add a script to be evaluated at the start of every new document.
     */
    public OffscreenWebView addOnBeforeLoad(String js) {
        checkAlive();
        WebViewNative.webview_offscreen_init(peer, js);
        return this;
    }

    /**
     * Evaluate javascript in the current document.
     */
    public OffscreenWebView eval(String js) {
        checkAlive();
        WebViewNative.webview_offscreen_eval(peer, js);
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
     * {@code IllegalStateException("OffscreenWebView has been disposed.")}
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
                new IllegalStateException("OffscreenWebView has been disposed."));
            return f;
        }
        return evalDispatcher.evalAsync(js);
    }

    /**
     * Bind a Java callback under {@code window.<name>} in the offscreen
     * page.  Structurally identical to
     * {@link EmbeddedWebView#addJavascriptCallback}: the native side
     * round-trips through the same {@code {name, seq, args}} envelope.
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
     * Register a synchronous Java-backed JavaScript function under
     * {@code window.<name>}.  See {@link JavascriptFunction}.
     */
    public OffscreenWebView addJavascriptFunction(String name, JavascriptFunction fn) {
        checkAlive();
        functionDispatcher.registerSync(name, fn);
        return this;
    }

    /**
     * Register an asynchronous Java-backed JavaScript function under
     * {@code window.<name>}.  See {@link AsyncJavascriptFunction}.
     */
    public OffscreenWebView addJavascriptFunction(String name, AsyncJavascriptFunction fn) {
        checkAlive();
        functionDispatcher.registerAsync(name, fn);
        return this;
    }

    /**
     * Dispatch a Runnable onto the offscreen WebView's native UI thread.
     */
    public OffscreenWebView dispatch(final Runnable r) {
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
        WebViewNative.webview_offscreen_dispatch(peer, wrapper);
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

    /**
     * Execute a platform editing command (Cut / Copy / Paste / Select-All)
     * against the offscreen WebView's current focused element.  The native
     * call marshals to the GTK pump thread on its own, so this is safe to
     * invoke from the EDT without blocking.  No-op on macOS / Windows
     * (the offscreen engine is Linux-only).
     *
     * @param cmd the editing command to perform; must not be null.
     */
    public OffscreenWebView executeEditingCommand(EditingCommand cmd) {
        if (cmd == null) {
            throw new NullPointerException("cmd");
        }
        checkAlive();
        WebViewNative.webview_offscreen_execute_editing_command(
            peer, cmd.getNativeId());
        return this;
    }

    /**
     * Register (or clear, by passing {@code null}) a callback invoked for
     * each JS-initiated UI dialog ({@code alert} / {@code confirm} /
     * {@code prompt}) or {@code <input type="file">} click inside the
     * offscreen WebView.  Unlike {@code setFocusCallback} /
     * {@code setClickCallback} on {@link EmbeddedWebView}, dialog
     * callbacks RETURN VALUES to the engine synchronously: the native
     * UI thread is suspended while waiting for the answer.
     * Implementations MUST marshal to the EDT (the library-provided
     * implementation routes through {@link DialogDispatcher} which uses
     * {@code SwingUtilities.invokeAndWait}).
     *
     * <p>Anchoring the callback in {@code heap} is required so the JVM
     * does not garbage-collect the lambda while the native side holds
     * a global ref.
     *
     * <p>On macOS / Windows, where the offscreen engine itself is a
     * stub, this method has no effect — the native stub is a no-op.
     * Linux is the only platform where the offscreen path actually
     * dispatches dialog requests, and STORY-004-002 implements the GTK
     * signal-handler side.
     */
    public OffscreenWebView setDialogCallback(WebViewDialogCallback cb) {
        checkAlive();
        if (cb != null) {
            heap.add(cb);
        }
        WebViewNative.webview_offscreen_set_dialog_callback(peer, cb);
        return this;
    }

    /** Release native resources. */
    public void dispose() {
        if (peer != 0L) {
            long p = peer;
            // Drain pending evalAsync futures FIRST so callers waiting
            // on .get() wake up with IllegalStateException rather than
            // hanging forever, and the dispatcher's disposed flag flips
            // so subsequent evalAsync calls return an already-failed
            // future without touching the engine.  Runs before the
            // heap clear / native destroy so the resolver-binding
            // callback (anchored in heap) is still reachable if a late
            // JS-side callback fires during the destroy sequence — but
            // the drain ensures such a late callback finds an empty
            // pending map and silently drops.
            evalDispatcher.disposeAllPending();
            functionDispatcher.disposeAll();
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
