/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * the {@code addJavascriptFunction(String, ...)} methods on {@code WebView}
 * or {@code WebViewComponent} instead.  This class is {@code public} only
 * because the consuming Swing subclasses live in a different package and
 * Java has no cross-package-but-non-public access modifier; matches the
 * existing pattern used by {@code EvalDispatcher},
 * {@code ConsoleDispatcher}, and {@code WebViewMouseDispatcher}.
 *
 * <p>Per-engine fan-out hub for value-returning JS&rarr;Java functions &mdash;
 * the mirror image of {@link EvalDispatcher} (which lets Java call JS and
 * await a result).  Here the page calls a registered global,
 * {@code const r = await window.<name>(arg)}, and a Java handler produces
 * the result.  The dispatcher owns the {@code name -> handler} registry, a
 * daemon worker pool that runs synchronous handlers off the engine UI
 * thread, the canonical JS shim ({@link #SHIM_JS}), the per-name wrapper
 * installer, the inbound-call parsing, and the outbound resolve.
 *
 * <p>Threading: synchronous handlers ({@link JavascriptFunction}) run on the
 * worker pool; asynchronous handlers ({@link AsyncJavascriptFunction}) are
 * invoked on the binding-callback thread and return a future.  The result is
 * delivered back to the page through a fire-and-forget {@code eval} (never
 * {@code evalAsync}, never {@code get()}), so no dispatcher code blocks the
 * engine UI thread or the Swing EDT.  This is the deadlock-free property that
 * motivates the feature over a synchronous value-returning callback.
 *
 * <p>Like the other dispatchers the wire format is a base64-encoded
 * pipe-separated record and there is no JSON-parsing dependency.
 */
public final class FunctionDispatcher {

    /**
     * Reserved JS binding name through which the page posts inbound calls.
     * Carries base64 of {@code <id>|<name>|<arg>}.  Protected from caller
     * collision by the existing {@code RESERVED_BINDING_PREFIX} check on
     * {@code WebViewComponent.addJavascriptCallback}.
     */
    public static final String INBOUND_CHANNEL = "__webview_fn_call__";

    /** Reserved prefix; registered function names may not start with it. */
    private static final String RESERVED_PREFIX = "__webview_";

    /** Registered names must be plain JS identifiers (no quotes/backslashes),
     *  so they can be substituted into the per-name wrapper without escaping. */
    private static final Pattern NAME_PATTERN =
        Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    /**
     * Canonical base shim installed once per page (via {@code addOnBeforeLoad}
     * by the consuming surface).  Defines, on {@code window}:
     * <ul>
     *   <li>{@code __webview_fn_invoke(name, arg)} &mdash; returns a Promise,
     *       allocates a request id, stashes its {@code {resolve,reject}}, and
     *       posts {@code <id>|<name>|<arg>} (base64) through
     *       {@code window.__webview_fn_call__}.</li>
     *   <li>{@code __webview_fn_resolve__(b64)} &mdash; the sink Java calls
     *       back into; decodes {@code <id>|<ok>|<result>} and settles the
     *       stashed Promise.</li>
     * </ul>
     *
     * <p>Idempotency-guarded with {@code window.__webview_fn_installed__} so
     * re-injection on repeated navigations is a no-op.  The inbound binding
     * reference is cached BEFORE any user JS runs, so user code that
     * reassigns {@code window.__webview_fn_call__} cannot reroute calls
     * (mirrors {@link EvalDispatcher#SHIM_JS}).
     */
    public static final String SHIM_JS =
        "(function(){"
      + "if(window.__webview_fn_installed__)return;"
      + "window.__webview_fn_installed__=true;"
      + "var pending={}, seq=0;"
      + "function enc(s){try{return btoa(unescape(encodeURIComponent(s)));}catch(e){return '';}}"
      + "function dec(s){try{return decodeURIComponent(escape(atob(s)));}catch(e){return '';}}"
      + "var post=window.__webview_fn_call__;"
      + "window.__webview_fn_invoke=function(name,arg){"
      + "  var id=++seq;"
      + "  return new Promise(function(resolve,reject){"
      + "    pending[id]={resolve:resolve,reject:reject};"
      + "    var sink=post||window.__webview_fn_call__;"
      + "    if(typeof sink!=='function'){reject(new Error('webview function channel unavailable'));return;}"
      + "    sink(enc(id+'|'+name+'|'+(arg==null?'':String(arg))));"
      + "  });"
      + "};"
      + "window.__webview_fn_resolve__=function(b64){"
      + "  var rec=dec(b64);"
      + "  var p1=rec.indexOf('|'); if(p1<0)return;"
      + "  var p2=rec.indexOf('|',p1+1); if(p2<0)return;"
      + "  var id=rec.substring(0,p1), ok=rec.substring(p1+1,p2), val=rec.substring(p2+1);"
      + "  var e=pending[id]; if(!e)return; delete pending[id];"
      + "  if(ok==='1') e.resolve(val); else e.reject(new Error(val));"
      + "};"
      + "})();";

    /**
     * Engine-specific delivery channel injected at construction time.  Unlike
     * {@link EvalDispatcher.EvalSink} this carries two methods (so it is NOT a
     * {@code @FunctionalInterface}); modeled on
     * {@code WebViewMouseDispatcher.FlagSink}, which likewise carries both
     * {@code eval} and {@code addOnBeforeLoad}.  Implementations own
     * engine-alive checks and {@code IllegalStateException} swallowing.
     */
    public static interface FunctionSink {
        /** Fire-and-forget eval of {@code js} on the live engine. */
        void eval(String js);
        /** Register {@code js} as a document-start script. */
        void addOnBeforeLoad(String js);
    }

    private final FunctionSink sink;
    private final String disposeLabel;
    private final ConcurrentHashMap<String, AsyncJavascriptFunction> handlers;
    private final ExecutorService worker;
    private volatile boolean disposed;

    public FunctionDispatcher(FunctionSink sink, String disposeLabel) {
        if (sink == null) throw new NullPointerException("sink");
        if (disposeLabel == null) throw new NullPointerException("disposeLabel");
        this.sink = sink;
        this.disposeLabel = disposeLabel;
        this.handlers = new ConcurrentHashMap<String, AsyncJavascriptFunction>();
        final String label = disposeLabel;
        final AtomicInteger seq = new AtomicInteger(0);
        this.worker = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r,
                    "webview-fn-" + label + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Register a synchronous handler under {@code name}.  The handler runs on
     * the worker pool (never the engine UI thread); its return value resolves
     * the page Promise and a thrown exception rejects it.  The function is
     * installed for the current document and all future navigations.
     *
     * @throws NullPointerException if {@code name} or {@code fn} is null.
     * @throws IllegalArgumentException if {@code name} starts with the
     *         reserved prefix or is not a valid JS identifier.
     */
    public void registerSync(final String name, final JavascriptFunction fn) {
        validateName(name);
        if (fn == null) throw new NullPointerException("fn");
        if (disposed) return;
        AsyncJavascriptFunction adapted = new AsyncJavascriptFunction() {
            @Override
            public CompletableFuture<String> run(final String arg) {
                return CompletableFuture.supplyAsync(new java.util.function.Supplier<String>() {
                    @Override
                    public String get() {
                        try {
                            return fn.run(arg);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Error e) {
                            throw e;
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }
                }, worker);
            }
        };
        handlers.put(name, adapted);
        installWrapper(name);
    }

    /**
     * Register an asynchronous handler under {@code name}.  The handler is
     * invoked on the binding-callback thread and must return promptly with a
     * future; the future's completion resolves/rejects the page Promise.  The
     * function is installed for the current document and all future
     * navigations.
     *
     * @throws NullPointerException if {@code name} or {@code fn} is null.
     * @throws IllegalArgumentException if {@code name} starts with the
     *         reserved prefix or is not a valid JS identifier.
     */
    public void registerAsync(String name, AsyncJavascriptFunction fn) {
        validateName(name);
        if (fn == null) throw new NullPointerException("fn");
        if (disposed) return;
        handlers.put(name, fn);
        installWrapper(name);
    }

    /**
     * Consume the raw bind-envelope JSON the native engine passes to the
     * inbound callback, parse the base64 {@code <id>|<name>|<arg>} record,
     * route to the handler, and arrange the resolve.  Called from the native
     * binding-callback thread; never throws.  Silently drops malformed
     * payloads (matches {@code EvalDispatcher.dispatch}).
     */
    public void dispatch(String rawJson) {
        if (rawJson == null) return;
        String b64 = extractFirstArg(rawJson);
        if (b64 == null) return;
        String rec = decodeBase64Utf8(b64);
        if (rec == null) return;
        int p1 = rec.indexOf('|');
        if (p1 <= 0) return;
        final long id;
        try {
            id = Long.parseLong(rec.substring(0, p1));
        } catch (NumberFormatException e) {
            return;
        }
        int p2 = rec.indexOf('|', p1 + 1);
        if (p2 < 0) return;
        String name = rec.substring(p1 + 1, p2);
        String arg = rec.substring(p2 + 1);
        AsyncJavascriptFunction h = handlers.get(name);
        if (h == null) {
            resolve(id, false, "no such function: " + name);
            return;
        }
        CompletableFuture<String> fut;
        try {
            fut = h.run(arg);
        } catch (Throwable t) {
            resolve(id, false, msgOf(t));
            return;
        }
        if (fut == null) {
            resolve(id, true, "");
            return;
        }
        fut.whenComplete(new java.util.function.BiConsumer<String, Throwable>() {
            @Override
            public void accept(String value, Throwable err) {
                if (err != null) {
                    resolve(id, false, msgOf(unwrap(err)));
                } else {
                    resolve(id, true, value == null ? "" : value);
                }
            }
        });
    }

    /**
     * Stop accepting work and release the worker pool.  Idempotent and safe
     * from any thread.  In-flight synchronous handlers are interrupted; their
     * results are dropped (no resolver remains after native teardown).  The
     * dispatcher does not unbind the inbound channel &mdash; the native
     * destroy invalidates it wholesale.
     */
    public void disposeAll() {
        if (disposed) return;
        disposed = true;
        handlers.clear();
        worker.shutdownNow();
    }

    // ----------------------------------------------------------------------

    private void installWrapper(String name) {
        String js = wrapperFor(name);
        // Future navigations, then the current document.
        sink.addOnBeforeLoad(js);
        sink.eval(js);
    }

    private static String wrapperFor(String name) {
        // name has already passed NAME_PATTERN, so it is safe to interpolate
        // directly into both the property key and the string-literal argument.
        return "(function(){window[\"" + name + "\"]=function(a){"
             + "return window.__webview_fn_invoke(\"" + name + "\",a);};})();";
    }

    private void resolve(long id, boolean ok, String payload) {
        String rec = id + "|" + (ok ? "1" : "0") + "|"
            + (payload == null ? "" : payload);
        String b64 = encodeBase64Utf8(rec);
        // Base64 alphabet (A-Za-z0-9+/=) is safe inside a single-quoted JS
        // string, so no escaping is required.  eval is non-blocking.
        sink.eval("window.__webview_fn_resolve__('" + b64 + "')");
    }

    private static void validateName(String name) {
        if (name == null) throw new NullPointerException("name");
        if (name.startsWith(RESERVED_PREFIX)) {
            throw new IllegalArgumentException(
                "name must not start with " + RESERVED_PREFIX + ": " + name);
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "name must be a valid JS identifier: " + name);
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static String msgOf(Throwable t) {
        if (t == null) return "error";
        String m = t.getMessage();
        return (m != null) ? m : t.getClass().getSimpleName();
    }

    /**
     * Extract the first arg from a bind-shim JSON wrapper of the form
     * {@code {"name":"...","seq":...,"args":["<value>"]}}.  Copied verbatim
     * from {@code EvalDispatcher.extractFirstArg}.  Assumes the value is a
     * single base64 string (no JSON-special characters).
     */
    private static String extractFirstArg(String json) {
        int argsIdx = json.indexOf("\"args\":[");
        if (argsIdx < 0) return null;
        int quoteStart = json.indexOf('"', argsIdx + 8);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String decodeBase64Utf8(String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            return new String(bytes, "UTF-8");
        } catch (IllegalArgumentException e) {
            return null;
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    private static String encodeBase64Utf8(String s) {
        try {
            return Base64.getEncoder().encodeToString(s.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
