package ca.weblite.webview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives custom-scheme requests from native code and answers each exactly once (Canvas 30
 * D5–D11).
 *
 * <p>Native calls {@link #onSchemeRequest} from a non-UI thread and gets control back at once: the
 * handler runs on this dispatcher's own daemon threads. The first of the handler's answer, a 500
 * after it throws, a 504 after the timeout, or a cancellation wins; every later one is ignored, and
 * a cancelled request sends nothing back. Answers go back through a {@link Sink} — the native
 * {@code webview_scheme_respond} by default, a recorder in tests.
 */
final class SchemeDispatcher {

    /** Where an answer goes: native, or a test's recorder. */
    interface Sink {
        void respond(long id, int status, String[] headerPairs, byte[] body);
    }

    /** A request body larger than this is answered 413 without calling the handler (D10). */
    static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    /** A response body larger than this is replaced by a 500 (D10). */
    static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    /** How long a handler has to answer (D8); overridable for tests. */
    long timeoutMs = 30000;

    private final Map<String, WebViewSchemeHandler> handlers;
    private final Sink sink;
    private final ExecutorService executor;
    private final ScheduledExecutorService timer;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<Long, Pending>();

    private static final class Pending {
        final AtomicBoolean done = new AtomicBoolean();
        final String origin;
        volatile ScheduledFuture<?> timeout;

        Pending(String origin) {
            this.origin = origin;
        }
    }

    SchemeDispatcher(Map<String, WebViewSchemeHandler> handlers, Sink sink) {
        this.handlers = new LinkedHashMap<String, WebViewSchemeHandler>(handlers);
        this.sink = sink;
        this.executor = Executors.newCachedThreadPool(daemon("webview-scheme-", true));
        this.timer = Executors.newSingleThreadScheduledExecutor(daemon("webview-scheme-timer", false));
    }

    private static ThreadFactory daemon(final String name, final boolean numbered) {
        final AtomicInteger n = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, numbered ? name + n.incrementAndGet() : name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** Called by native for every request to a registered scheme (D15). Never throws. */
    void onSchemeRequest(final long id, String method, String url, String[] headerPairs, byte[] body,
                         boolean bodyAvailable) {
        try {
            final WebViewSchemeRequest req = new WebViewSchemeRequest(method, url, headerPairs, body, bodyAvailable);
            Pending p = new Pending(originOf(req.url()));
            pending.put(id, p);
            final WebViewSchemeHandler handler = handlers.get(req.scheme());
            if (handler == null) {
                finish(id, WebViewSchemeResponse.text(404, "Not found"));
                return;
            }
            if (req.body().length > MAX_REQUEST_BYTES) {
                finish(id, WebViewSchemeResponse.text(413, "Request too large"));
                return;
            }
            p.timeout = timer.schedule(new Runnable() {
                @Override
                public void run() {
                    finish(id, WebViewSchemeResponse.text(504, "Timed out"));
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        handler.handle(req, new WebViewSchemeResponder() {
                            @Override
                            public void respond(WebViewSchemeResponse response) {
                                finish(id, response == null
                                        ? WebViewSchemeResponse.text(500, "Internal error") : response);
                            }
                        });
                    } catch (Throwable t) {
                        report(t);
                        finish(id, WebViewSchemeResponse.text(500, "Internal error"));
                    }
                }
            });
        } catch (Throwable t) {
            report(t);
            finish(id, WebViewSchemeResponse.text(500, "Internal error"));
        }
    }

    /** Called by native when the engine abandons a request (D7). Nothing is sent back. */
    void onSchemeCancelled(long id) {
        Pending p = pending.remove(id);
        if (p == null) return;
        p.done.set(true);
        ScheduledFuture<?> t = p.timeout;
        if (t != null) t.cancel(false);
    }

    /** The first outcome for {@code id} wins; it goes to the sink with D11's headers. */
    void finish(long id, WebViewSchemeResponse response) {
        Pending p = pending.get(id);
        if (p == null || !p.done.compareAndSet(false, true)) return;
        pending.remove(id);
        ScheduledFuture<?> t = p.timeout;
        if (t != null) t.cancel(false);
        WebViewSchemeResponse r = response;
        if (r.body().length > MAX_RESPONSE_BYTES) r = WebViewSchemeResponse.text(500, "Response too large");
        try {
            sink.respond(id, r.status(), pairs(r, p.origin), r.body());
        } catch (Throwable e) {
            report(e);
        }
    }

    /** D11: no Content-Length, a default Content-Type, and CORS for the request's own origin. */
    static String[] pairs(WebViewSchemeResponse r, String origin) {
        List<String> out = new ArrayList<String>();
        boolean type = false;
        boolean cors = false;
        for (String[] h : r.headers()) {
            String name = h[0];
            if ("Content-Length".equalsIgnoreCase(name)) continue;
            if ("Content-Type".equalsIgnoreCase(name)) type = true;
            if ("Access-Control-Allow-Origin".equalsIgnoreCase(name)) cors = true;
            out.add(name);
            out.add(h[1]);
        }
        if (!type) {
            out.add("Content-Type");
            out.add("application/octet-stream");
        }
        if (!cors && origin != null) {
            out.add("Access-Control-Allow-Origin");
            out.add(origin);
        }
        return out.toArray(new String[0]);
    }

    /** {@code scheme://host} of {@code url}, or null when it has no host. */
    static String originOf(String url) {
        String scheme = WebViewSchemeRequest.schemeOf(url);
        if (scheme.isEmpty()) return null;
        String rest = url.substring(scheme.length() + 1);
        if (!rest.startsWith("//")) return null;
        rest = rest.substring(2);
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return scheme + "://" + rest.substring(0, end);
    }

    /** How many requests are waiting for an answer; for tests. */
    int pendingCount() {
        return pending.size();
    }

    private static void report(Throwable t) {
        Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
        if (h != null) {
            h.uncaughtException(Thread.currentThread(), t);
        } else {
            t.printStackTrace();
        }
    }
}
