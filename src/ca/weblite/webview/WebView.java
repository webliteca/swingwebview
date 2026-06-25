/**
 *
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ca.weblite.webview;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Encapsulates a WebBrowser window.  This class interfaces directly with the
 * ZSerge WebView via JNI and loads the webview in the current process.
 * Running in-process owns the main thread and its own event loop, so this
 * class is not suitable for use inside an existing Swing/JavaFX UI; use
 * {@link ca.weblite.webview.swing.WebViewComponent} for that.
 * @author shannah
 */
public class WebView {
    /**
     * The native pointer reference.
     */
    private long peer;
    
    
    private int w=800, h=600;
    private boolean resizable=true;
    private boolean fullscreen;
    private String title="Browser";
    private String url="https://weblite.ca";
    private List<String> onBeforeLoad = new ArrayList<String>();
    private Map<String,JavascriptCallback> bindings = new HashMap<String,JavascriptCallback>();

    /**
     * Make the webview window resizable.
     * @param b True to make the webview window resizable.
     * @return 
     */
    public WebView resizable(boolean b) {
        resizable = b;
        return this;
    }
    
    /**
     * Interface for callbacks from javascript.
     */
    public static interface JavascriptCallback {
        
        /**
         * Run a callback from Javascript.  
         * @param arg 
         */
        public void run(String arg);
    }
    
    /**
     * Add Javascript code to be run in the webview when each page loads.
     * @param js
     * @return 
     */
    public WebView addOnBeforeLoad(String js) {
        if (peer != 0) {
            WebViewNative.webview_init(peer, js);
        } else {
            onBeforeLoad.add(js);
        }
        return this;
    }
    
    
    /**
     * Per-instance fan-out hub for {@link #evalAsync(String)}.  Holds
     * the in-flight {@code requestId -> CompletableFuture<String>} map,
     * the JS wrapper template, and the dispose drain.  Constructed with
     * {@code marshalToEdt = false} because the standalone {@code WebView}
     * owns its own main-thread event loop and has no Swing in the
     * picture — future continuations complete on the WebView's native
     * UI thread (the same thread that {@link #dispatch(Runnable)}
     * callbacks land on).  Callers needing EDT delivery wrap with
     * {@code .thenAcceptAsync(continuation, SwingUtilities::invokeLater)}.
     */
    private final EvalDispatcher evalDispatcher;

    /**
     * Per-engine hub for {@link #addJavascriptFunction} — value-returning
     * JS&rarr;Java functions.  Its {@code FunctionSink} routes
     * {@code addOnBeforeLoad} through this instance's own buffering
     * {@link #addOnBeforeLoad(String)} (so functions registered before
     * {@link #show()} are replayed at document-start once the window is
     * shown) and issues a live {@code webview_eval} only once a peer
     * exists.
     */
    private final FunctionDispatcher functionDispatcher;

    /**
     * Creates a new webview.
     */
    public WebView() {
        this.evalDispatcher = new EvalDispatcher(new EvalDispatcher.EvalSink() {
            @Override
            public void eval(String js) {
                long p = peer;
                if (p != 0L) {
                    WebViewNative.webview_eval(p, js);
                }
            }
        }, false, "WebView");
        this.functionDispatcher = new FunctionDispatcher(
            new FunctionDispatcher.FunctionSink() {
                @Override
                public void eval(String js) {
                    long p = peer;
                    if (p != 0L) {
                        WebViewNative.webview_eval(p, js);
                    }
                }
                @Override
                public void addOnBeforeLoad(String js) {
                    // Delegates to the buffering addOnBeforeLoad so per-name
                    // wrappers survive both the pre-show and post-show cases.
                    WebView.this.addOnBeforeLoad(js);
                }
            }, "WebView");
    }
    
    /**
     * Sets the URL of the webview.
     * @param url The url.
     * @return 
     */
    public WebView url(String url) {
        this.url = url;
        if (peer != 0) {
            WebViewNative.webview_navigate(peer, url);
        }
        return this;
    }
    
    /**
     * Gets the webview url.
     * @return 
     */
    public String url() {
        return url;
    }
    
    /**
     * Set the window title.
     * @param title
     * @return 
     */
    public WebView title(String title) {
        this.title = title;
        if (peer != 0) {
            WebViewNative.webview_set_title(peer, title);
        }
        return this;
    }
   
  
    /**
     * Set the window size.
     * @param w
     * @param h
     * @return 
     */
    public WebView size(int w, int h) {
        this.w = w;
        this.h = h;
        return this;
    }
    
    /**
     * Get the window width.
     * @return 
     */
    public int w() {
        return w;
    }
    
    /**
     * Get the window height.
     * @return 
     */
    public int h() {
        return h;
    }
    
    /**
     * Heap used so that callbacks don't get garbage collected by JVM while waiting
     * to be called by native code.
     */
    private ArrayList heap = new ArrayList();
    
    /**
     * Adds a javascript callback function.  This function will be accessible in Javascript
     * via window.name.
     * @param name THe name of the callback.
     * @param callback The callback to be run.
     * @return 
     */
    public WebView addJavascriptCallback(String name, JavascriptCallback callback) {
        if (peer == 0) {
            bindings.put(name, callback);
        } else {
            bindings.put(name, callback);
            WebViewNativeCallback fn = new WebViewNativeCallback() {
                @Override
                public void invoke(String arg2, long wv) {
                    JavascriptCallback cb = bindings.get(name);
                    if (cb != null) {
                        cb.run(arg2);
                    }
                }
            };
            heap.add(fn);
            WebViewNative.webview_bind(peer, name, fn, peer);
        }
        return this;
    }
    
    /**
     * Register a synchronous Java-backed JavaScript function under
     * {@code window.<name>}.  In the page, call it as
     * {@code const r = await window.<name>(arg)}.  See
     * {@link JavascriptFunction}.  May be called before {@link #show()};
     * the page-side wrapper is installed at document-start once shown.
     * @return this
     */
    public WebView addJavascriptFunction(String name, JavascriptFunction fn) {
        functionDispatcher.registerSync(name, fn);
        return this;
    }

    /**
     * Register an asynchronous Java-backed JavaScript function under
     * {@code window.<name>}.  See {@link AsyncJavascriptFunction}.
     * @return this
     */
    public WebView addJavascriptFunction(String name, AsyncJavascriptFunction fn) {
        functionDispatcher.registerAsync(name, fn);
        return this;
    }

    /**
     * Execute javascript.
     * @param js Javscript to run
     * @return
     */
    public WebView eval(String js) {
        WebViewNative.webview_eval(peer, js);
        return this;
    }

    /**
     * Evaluate JavaScript and return a future that completes with the
     * JSON-stringified result.  See
     * {@link EvalDispatcher#evalAsync(String)} for the full contract
     * (snippet must use {@code return} to yield a value, {@code undefined}
     * maps to {@code "null"}, returned {@code Promise}s are awaited,
     * JS-side errors surface as a {@link JavaScriptEvalException}).
     *
     * <p>Returns an already-failed future carrying
     * {@code IllegalStateException("WebView not shown")} when called
     * before {@link #show()} or after the window has closed (the
     * post-{@code webview_run} cleanup in {@link #show()} zeroes
     * {@code peer}).
     *
     * <p>Threading: future continuations
     * ({@code .thenAccept}/{@code .thenApply}/{@code .exceptionally}/{@code .handle})
     * run inline on the WebView's native UI thread — the same thread
     * that {@link #dispatch(Runnable)} callbacks land on.  Callers
     * needing EDT delivery wrap with
     * {@code .thenAcceptAsync(continuation, SwingUtilities::invokeLater)}.
     *
     * @param js the JS snippet; must not be null.
     * @return a future that resolves to the JSON-stringified result.
     * @throws NullPointerException if {@code js} is null.
     */
    public CompletableFuture<String> evalAsync(String js) {
        if (js == null) throw new NullPointerException("js");
        if (peer == 0L) {
            CompletableFuture<String> f = new CompletableFuture<String>();
            f.completeExceptionally(new IllegalStateException("WebView not shown"));
            return f;
        }
        return evalDispatcher.evalAsync(js);
    }


    /**
     * Dispatch on the WebView event thread.
     * @param r 
     * @return 
     */
    public WebView dispatch(Runnable r) {
        heap.add(r);
        WebViewNative.webview_dispatch(peer, () -> {
            r.run();
            heap.remove(r);
        }, 0);
        return this;
    }
    
    
    /**
     * Shows the webview.  This will start the webview event loop, and it will
     * block execution.
     */
    public void show() {
        peer = WebViewNative.webview_create(0, 0);
        WebViewNative.webview_set_bounds(peer, 0, 0, w, h, 0);
        // Install the evalAsync shim FIRST so it runs at document-start
        // before any user init script.  Idempotency-guarded inside the
        // shim itself by window.__webview_eval_installed__.
        WebViewNative.webview_init(peer, EvalDispatcher.SHIM_JS);
        // Install the addJavascriptFunction base shim BEFORE the buffered
        // onBeforeLoad scripts so window.__webview_fn_invoke exists before
        // any per-name wrapper (registered pre-show, replayed below) runs.
        WebViewNative.webview_init(peer, FunctionDispatcher.SHIM_JS);
        for (String js : onBeforeLoad) {
            WebViewNative.webview_init(peer, js);
        }
        WebViewNative.webview_set_title(peer, title);
        // Register the eval resolver binding via direct JNI (the
        // __webview_ reserved prefix is unenforced on the standalone
        // WebView surface, but going directly through webview_bind
        // matches the pattern used by the embedded engines and keeps
        // the resolver invisible to addJavascriptCallback callers).
        WebViewNativeCallback evalCb = (String arg, long wv) -> {
            evalDispatcher.dispatch(arg);
        };
        heap.add(evalCb);
        WebViewNative.webview_bind(peer, EvalDispatcher.CHANNEL_NAME, evalCb, peer);
        WebViewNativeCallback fnCb = (String arg, long wv) -> {
            functionDispatcher.dispatch(arg);
        };
        heap.add(fnCb);
        WebViewNative.webview_bind(peer, FunctionDispatcher.INBOUND_CHANNEL, fnCb, peer);
        for (final String key : bindings.keySet()) {
            WebViewNativeCallback fn = (String arg2, long wv) -> {
                JavascriptCallback cb = bindings.get(key);
                if (cb != null) {
                    cb.run(arg2);
                }
            };
            heap.add(fn);
            WebViewNative.webview_bind(peer, key, fn, peer);
        }
        WebViewNative.webview_navigate(peer, url);
        WebViewNative.webview_run(peer);
        // Window closed and native peer destroyed.  Zero peer so
        // subsequent entry points observe the dead state, and drain
        // pending evalAsync futures so callers waiting on .get() do
        // not hang.
        peer = 0L;
        evalDispatcher.disposeAllPending();
        functionDispatcher.disposeAll();
    }

}
