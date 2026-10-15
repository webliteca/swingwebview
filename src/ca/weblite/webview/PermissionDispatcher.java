/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * {@link WebViewComponent#setPermissionHandler} instead.  This class is
 * {@code public} only because the consuming Swing subclasses live in a
 * different package, like {@link NavigationDispatcher}.
 *
 * <p>Per-component hub for media-capture permission requests (Canvas 35 D3,
 * D4).  {@link #dispatch} runs inline on the native UI thread and never
 * blocks: with no handler it answers {@link PermissionDecision#DEFAULT} at
 * once (the engine's own path); otherwise it records the request as pending,
 * schedules the handler on the EDT, and answers {@link #PENDING}.  The
 * handler's first answer goes to the {@link Sink} — the native
 * {@code webview_permission_respond} by default.
 */
public final class PermissionDispatcher {

    /** The JNI answer meaning "the decision will come later". */
    public static final int PENDING = -1;

    /** Where an answer goes: native, or a test's recorder. */
    interface Sink {
        void respond(long id, int decision);
    }

    private static final Sink NATIVE_SINK = new Sink() {
        @Override public void respond(long id, int decision) {
            WebViewNative.webview_permission_respond(id, decision);
        }
    };

    private static final Executor EDT = new Executor() {
        @Override public void execute(Runnable r) {
            SwingUtilities.invokeLater(r);
        }
    };

    private final WebViewComponent source;
    private final Sink sink;
    private final Executor edt;
    private volatile WebViewPermissionHandler handler;
    private volatile boolean disposed = false;
    private final ConcurrentHashMap<Long, WebViewPermissionRequest> pending =
        new ConcurrentHashMap<Long, WebViewPermissionRequest>();

    private final WebViewPermissionRequest.Answerer answerer =
        new WebViewPermissionRequest.Answerer() {
            @Override
            public void answered(WebViewPermissionRequest r, PermissionDecision d) {
                pending.remove(r.id());
                try {
                    sink.respond(r.id(), d.ordinal());
                } catch (Throwable t) {
                    forwardUncaught(t);
                }
            }
        };

    public PermissionDispatcher(WebViewComponent source) {
        this(source, NATIVE_SINK, EDT);
    }

    PermissionDispatcher(WebViewComponent source, Sink sink, Executor edt) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.sink = sink;
        this.edt = edt;
    }

    /** Canvas 35 D6: whether the loaded native library routes media-capture
     *  requests; {@code false} against an older native, or without natives. */
    public static boolean isAvailable() {
        try {
            return WebViewNative.webview_permission_available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Replace the handler; {@code null} removes it, so the engine decides
     *  (Canvas 35 D1). */
    public void setHandler(WebViewPermissionHandler h) {
        handler = h;
    }

    /** @return the handler, or {@code null} when none is installed. */
    public WebViewPermissionHandler getHandler() {
        return handler;
    }

    /**
     * One request from native (Canvas 35 D3).  Inline, never blocks, never
     * throws.
     *
     * @return {@link #PENDING}, or a {@link PermissionDecision} ordinal to
     *         apply now
     */
    public int dispatch(long id, String origin, String url, int kindBits) {
        try {
            if (disposed) return PermissionDecision.DENY.ordinal();
            final WebViewPermissionHandler h = handler;
            if (h == null) return PermissionDecision.DEFAULT.ordinal();
            Set<MediaCaptureKind> kinds = MediaCaptureKind.fromBits(kindBits);
            if (kinds.isEmpty()) return PermissionDecision.DEFAULT.ordinal();
            final WebViewPermissionRequest request = new WebViewPermissionRequest(
                source, id, origin, url, kinds, answerer);
            pending.put(id, request);
            edt.execute(new Runnable() {
                @Override public void run() {
                    try {
                        h.permissionRequested(request);
                    } catch (Throwable t) {
                        forwardUncaught(t);
                        request.deny();
                    }
                }
            });
            return PENDING;
        } catch (Throwable t) {
            forwardUncaught(t);
            return PermissionDecision.DENY.ordinal();
        }
    }

    /** Canvas 35 D4: deny every pending request; every later
     *  {@link #dispatch} denies.  Idempotent. */
    public void disposeAll() {
        disposed = true;
        for (WebViewPermissionRequest r : new ArrayList<WebViewPermissionRequest>(pending.values())) {
            r.deny();
        }
        pending.clear();
    }

    /** @return whether the dispatcher has been disposed. */
    public boolean isDisposed() {
        return disposed;
    }

    /** How many requests await an answer; for tests. */
    int pendingCount() {
        return pending.size();
    }

    private static void forwardUncaught(Throwable t) {
        try {
            Thread.UncaughtExceptionHandler h =
                Thread.getDefaultUncaughtExceptionHandler();
            if (h != null) {
                h.uncaughtException(Thread.currentThread(), t);
            } else {
                t.printStackTrace();
            }
        } catch (Throwable ignored) {
            try { t.printStackTrace(); } catch (Throwable ignored2) { }
        }
    }
}
