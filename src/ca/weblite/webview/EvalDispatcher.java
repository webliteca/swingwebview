/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * the {@code evalAsync(String)} method on {@code WebView} or
 * {@code WebViewComponent} instead.  This class is {@code public} only
 * because the consuming Swing subclasses live in a different package and
 * Java has no cross-package-but-non-public access modifier; matches the
 * existing pattern used by {@code ConsoleDispatcher},
 * {@code WebViewMouseDispatcher}, {@code EmbeddedWebView}, and
 * {@code WebViewNativeCallback}.
 *
 * <p>Per-engine fan-out hub for future-returning JavaScript evaluation.
 * Each consuming engine wrapper (the standalone {@code WebView}, each
 * {@code EmbeddedWebView}, each {@code OffscreenWebView}) constructs one
 * instance and keeps it in a final field for the engine's lifetime.  The
 * dispatcher holds the in-flight {@code requestId -> CompletableFuture<String>}
 * map, the canonical JS wrapper template (see {@link #SHIM_JS}), the
 * resolver-binding callback parsing, and the dispose-time drain that
 * completes pending futures exceptionally.
 *
 * <p>Threading: the dispatcher branches on the constructor-time
 * {@code marshalToEdt} flag.  When {@code true} (embedded surfaces),
 * future completions hop to the Swing EDT via
 * {@link SwingUtilities#invokeLater} so caller continuations
 * ({@code .thenAccept}, {@code .thenApply}, etc.) land on the EDT
 * matching the existing {@code ConsoleListener} /
 * {@code WebViewMouseListener} contracts.  When {@code false}
 * (standalone {@code WebView}), completions run inline on the
 * binding-callback thread.
 */
public final class EvalDispatcher {

    /**
     * Reserved JS binding name through which the wrapper template posts
     * eval results back to Java.  Protected from caller collision by
     * the existing {@code RESERVED_BINDING_PREFIX} check on
     * {@code WebViewComponent.addJavascriptCallback}.
     */
    public static final String CHANNEL_NAME = "__webview_eval_result__";

    /**
     * Canonical JS shim installed at document-start (via
     * {@code addOnBeforeLoad}) into every page loaded by an engine
     * that supports {@code evalAsync}.  Idempotency-guarded with
     * {@code window.__webview_eval_installed__} so re-injection on
     * repeated navigations or sub-frame loads is a no-op.
     *
     * <p>The shim defines two helpers on {@code window}:
     * <ul>
     *   <li>{@code __webview_eval_b64(s)} — UTF-8-safe base64 encoder
     *       (mirrors {@code ConsoleDispatcher.SHIM_JS}'s {@code b64}).</li>
     *   <li>{@code __webview_eval_post(id, ok, msg)} — posts the
     *       pipe-separated record {@code <id>|<okFlag>|<msg>} through
     *       {@code window.__webview_eval_result__(<base64>)}.</li>
     * </ul>
     *
     * <p>The per-call wrapper applied by {@code wrap(js, id)} caches a
     * stable reference to {@code window.__webview_eval_post} BEFORE
     * invoking the user JS, so user code that reassigns or deletes
     * {@code window.__webview_eval_result__} or
     * {@code window.__webview_eval_post} cannot reroute the result.
     */
    public static final String SHIM_JS =
        "(function(){"
      + "if(window.__webview_eval_installed__)return;"
      + "window.__webview_eval_installed__=true;"
      + "window.__webview_eval_b64=function(s){"
      + "  try{return btoa(unescape(encodeURIComponent(s)));}"
      + "  catch(e){return '';}"
      + "};"
      + "window.__webview_eval_post=function(id,ok,msg){"
      + "  try{"
      + "    var sink=window.__webview_eval_result__;"
      + "    if(typeof sink!=='function')return;"
      + "    var rec=id+'|'+(ok?'1':'0')+'|'+msg;"
      + "    sink(window.__webview_eval_b64(rec));"
      + "  }catch(e){}"
      + "};"
      + "})();";

    /**
     * Per-call wrapper template applied by {@code wrap(js, id)}.  Two
     * substitutions: {@code __ID__} (the request id) and
     * {@code __USER_JS__} (the user snippet).  Both substitutions land
     * in positions where any string is syntactically valid (numeric
     * literal and function-body source), so no escaping is needed.
     */
    private static final String WRAPPER_TEMPLATE =
        "(function(){"
      + "var __id=__ID__;"
      + "var __sink_post=window.__webview_eval_post;"
      + "try{"
      + "  var __r=(function(){__USER_JS__})();"
      + "  Promise.resolve(__r).then(function(v){"
      + "    try{"
      + "      var s=JSON.stringify(v===undefined?null:v);"
      + "      if(s===undefined)s='null';"
      + "      __sink_post(__id,true,s);"
      + "    }catch(e){"
      + "      __sink_post(__id,false,(e&&e.message)||String(e));"
      + "    }"
      + "  },function(e){"
      + "    __sink_post(__id,false,(e&&e.message)||String(e));"
      + "  });"
      + "}catch(e){"
      + "  __sink_post(__id,false,(e&&e.message)||String(e));"
      + "}"
      + "})();";

    /**
     * Engine-specific delivery channel injected at construction time.
     * The dispatcher calls {@link #eval(String)} with a fully-wrapped
     * JS snippet; implementations are typically two-line lambdas in
     * the consuming engine wrapper that issue the native eval call.
     */
    @FunctionalInterface
    public static interface EvalSink {
        void eval(String js);
    }

    private final EvalSink sink;
    private final boolean marshalToEdt;
    private final String disposeLabel;
    private final ConcurrentHashMap<Long, CompletableFuture<String>> pending;
    private final AtomicLong nextId;
    private volatile boolean disposed;

    public EvalDispatcher(EvalSink sink, boolean marshalToEdt, String disposeLabel) {
        if (sink == null) throw new NullPointerException("sink");
        if (disposeLabel == null) throw new NullPointerException("disposeLabel");
        this.sink = sink;
        this.marshalToEdt = marshalToEdt;
        this.disposeLabel = disposeLabel;
        this.pending = new ConcurrentHashMap<Long, CompletableFuture<String>>();
        this.nextId = new AtomicLong(0L);
    }

    /**
     * Submit a JS snippet for evaluation.  Returns a future that
     * completes with the JSON-stringified return value of the snippet
     * ({@code undefined} maps to {@code "null"}; returned
     * {@code Promise}s are awaited).
     *
     * <p>The user snippet must use {@code return} to yield a value —
     * the wrapper wraps it in an IIFE; a bare expression on its own
     * line is NOT the IIFE's return value.
     *
     * <p>JS-side failures (synchronous throw, Promise rejection,
     * {@code JSON.stringify} {@code TypeError}) complete the future
     * exceptionally with a {@link JavaScriptEvalException} wrapping
     * the JS-side message.  Lifecycle violations (dispatcher already
     * disposed) complete it exceptionally with
     * {@link IllegalStateException}.
     *
     * <p>Cancellation: {@code future.cancel(true)} marks the future
     * cancelled but does NOT abort the in-page JS.
     *
     * <p>Threading: continuations run on the Swing EDT when this
     * dispatcher was constructed with {@code marshalToEdt = true};
     * otherwise inline on the native binding-callback thread.
     *
     * @param js the JS snippet; must not be null.
     * @return a future that resolves to the JSON-stringified result.
     * @throws NullPointerException if {@code js} is null.
     */
    public CompletableFuture<String> evalAsync(String js) {
        Objects.requireNonNull(js, "js");
        if (disposed) {
            CompletableFuture<String> f = new CompletableFuture<String>();
            f.completeExceptionally(
                new IllegalStateException(disposeLabel + " disposed"));
            return f;
        }
        long id = nextId.incrementAndGet();
        CompletableFuture<String> f = new CompletableFuture<String>();
        pending.put(id, f);
        String wrapped = wrap(js, id);
        try {
            sink.eval(wrapped);
        } catch (RuntimeException ex) {
            // Sink threw synchronously (e.g. an underlying checkAlive
            // triggered because the peer was just disposed on another
            // thread).  Surface it through the future rather than
            // letting the throw escape evalAsync.
            pending.remove(id);
            f.completeExceptionally(ex);
        }
        return f;
    }

    /**
     * Consume the raw bind-envelope JSON the native engine passes to
     * the resolver callback, parse the base64-encoded
     * pipe-separated payload, and complete the matching future.
     * Called from whatever native thread the engine's bind machinery
     * runs on; thread-safe.  Silently drops malformed payloads
     * (matches {@code ConsoleDispatcher.dispatch} precedent).
     */
    public void dispatch(String rawJson) {
        if (rawJson == null) return;
        String b64 = extractFirstArg(rawJson);
        if (b64 == null) return;
        String payload = decodeBase64Utf8(b64);
        if (payload == null) return;
        int p1 = payload.indexOf('|');
        if (p1 <= 0) return;
        long id;
        try {
            id = Long.parseLong(payload.substring(0, p1));
        } catch (NumberFormatException e) {
            return;
        }
        int p2 = payload.indexOf('|', p1 + 1);
        if (p2 < 0) return;
        String okFlag = payload.substring(p1 + 1, p2);
        String rest = payload.substring(p2 + 1);
        CompletableFuture<String> f = pending.remove(id);
        if (f == null) return;
        if ("1".equals(okFlag)) {
            complete(f, rest, null);
        } else {
            complete(f, null, new JavaScriptEvalException(rest));
        }
    }

    /**
     * Complete every still-pending future exceptionally with
     * {@code IllegalStateException(<disposeLabel> + " disposed")} and
     * flip the {@code disposed} flag so subsequent {@code evalAsync}
     * calls return an already-failed future without touching the
     * engine.
     *
     * <p>Idempotent — multiple calls are no-ops after the first.
     *
     * <p>Dispose completions are NOT marshalled to the EDT, even when
     * {@code marshalToEdt} is {@code true}.  Dispose is called from
     * the engine's tear-down path which is itself often on the EDT;
     * a re-entrant {@code SwingUtilities.invokeLater} from inside an
     * EDT callback just defers to the next EDT cycle, which can race
     * with the consuming code's own teardown.  Completing inline
     * keeps the dispose path deterministic.
     */
    public void disposeAllPending() {
        if (disposed) return;
        disposed = true;
        IllegalStateException cause =
            new IllegalStateException(disposeLabel + " disposed");
        for (Map.Entry<Long, CompletableFuture<String>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(cause);
        }
        pending.clear();
    }

    private void complete(final CompletableFuture<String> f,
                          final String value,
                          final Throwable error) {
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                if (error != null) {
                    f.completeExceptionally(error);
                } else {
                    f.complete(value);
                }
            }
        };
        if (marshalToEdt) {
            SwingUtilities.invokeLater(r);
        } else {
            r.run();
        }
    }

    private static String wrap(String js, long id) {
        return WRAPPER_TEMPLATE
            .replace("__ID__", Long.toString(id))
            .replace("__USER_JS__", js);
    }

    /**
     * Extract the first arg from a bind-shim JSON wrapper of the form
     * {@code {"name":"...","seq":...,"args":["<value>"]}}.  Mirrors
     * {@code ConsoleDispatcher.extractFirstArg}.  Returns {@code null}
     * if the format doesn't match.  Assumes the value is a single
     * base64 string (no JSON-special characters), so no full JSON
     * escape parsing is needed.
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
}
