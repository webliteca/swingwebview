/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.util.concurrent.ConcurrentHashMap;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * {@link WebViewComponent#setPopupHandler} /
 * {@link WebViewComponent#getPopupHandler} instead.  This class is
 * {@code public} only because the consuming Swing subclasses live in a
 * different package; matches the pattern used by {@link DialogDispatcher},
 * {@code ConsoleDispatcher}, {@code WebViewMouseDispatcher},
 * {@link EmbeddedWebView}, and {@link OffscreenWebView}.
 *
 * <p>Per-component fan-out hub for browser-initiated popups.  Holds the
 * single active {@link WebViewPopupHandler} reference.
 *
 * <p><strong>Threading — diverges from {@link DialogDispatcher} in two
 * documented places:</strong>
 * <ol>
 *   <li>{@link #dispatchPopupRequested} runs the handler
 *       <strong>inline on the calling (native UI) thread</strong>, never on
 *       the EDT, and returns its {@code boolean} synchronously — the platform
 *       popup callback needs the allow/deny decision before it can return the
 *       child web view (or block), and an EDT round-trip from the native UI
 *       thread risks a deadlock.</li>
 *   <li>{@link #dispatchPopupOpened} / {@link #dispatchPopupClosed} are
 *       asynchronous notifications marshalled to the EDT via
 *       {@link SwingUtilities#invokeLater} (like the focus/click adapters),
 *       not {@code invokeAndWait}.</li>
 * </ol>
 * Handler exceptions are caught and forwarded to
 * {@link Thread#getDefaultUncaughtExceptionHandler()}; a safe fallback is
 * returned ({@code false} for the allow/deny gate, so a thrown decision
 * blocks the popup rather than leaking into the native thread).
 */
public final class PopupDispatcher {

    /** Internal drop singleton — installed when caller passes {@code null}
     *  to {@link #setHandler}.  Blocks all popups without UI.  Stateless;
     *  package-private (callers reset to the framework default by passing
     *  {@link WebViewPopupHandler#DEFAULT}). */
    static final WebViewPopupHandler DROP = new WebViewPopupHandler() {
        @Override public boolean popupRequested(WebViewPopupEvent e) {
            return false;
        }
        @Override public void popupOpened(WebViewPopupEvent e) { }
        @Override public void popupClosed(WebViewPopupEvent e) { }
    };

    private final WebViewComponent source;
    private volatile WebViewPopupHandler handler = WebViewPopupHandler.DEFAULT;
    private volatile boolean disposed = false;

    /** Correlates {@link #dispatchPopupOpened} with the later
     *  {@link #dispatchPopupClosed} so {@code popupClosed} receives the same
     *  rich event.  Keyed by the engine-assigned popup id. */
    private final ConcurrentHashMap<Long, WebViewPopupEvent> openPopups =
        new ConcurrentHashMap<Long, WebViewPopupEvent>();

    public PopupDispatcher(WebViewComponent source) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
    }

    /** Replace the active handler.  Passing {@code null} installs an internal
     *  drop handler that blocks all popups.  To reset to the framework
     *  default, pass {@link WebViewPopupHandler#DEFAULT} explicitly. */
    public void setHandler(WebViewPopupHandler h) {
        handler = (h == null) ? DROP : h;
    }

    /** @return the active handler; never {@code null}. */
    public WebViewPopupHandler getHandler() {
        return handler;
    }

    /** Flip the dispatcher into disposed state.  After this call every
     *  {@code dispatch*} method returns the safe fallback without invoking
     *  the handler.  Idempotent. */
    public void disposeAll() {
        disposed = true;
        openPopups.clear();
    }

    /** @return whether the dispatcher has been disposed. */
    public boolean isDisposed() {
        return disposed;
    }

    // ---------------------------------------------------------------------
    // Native-facing dispatch entry points (invoked from JNI through the
    // WebViewPopupCallback adapter installed at peer-attach time).
    // ---------------------------------------------------------------------

    /** Synchronous allow/deny gate.  Runs the handler inline on the calling
     *  (native UI) thread; never marshals to the EDT. */
    public boolean dispatchPopupRequested(String targetUrl, String targetName,
                                          boolean userGesture, int width,
                                          int height, String pageUrl) {
        if (disposed) return false;
        final WebViewPopupEvent event = new WebViewPopupEvent(
            source, targetUrl, targetName, userGesture, width, height, pageUrl);
        try {
            return handler.popupRequested(event);
        } catch (Throwable t) {
            forwardUncaught(t);
            return false;
        }
    }

    /** Async notification that a popup window opened; delivered on the EDT. */
    public void dispatchPopupOpened(long popupId, String targetUrl,
                                    String targetName, boolean userGesture,
                                    int width, int height, String pageUrl) {
        if (disposed) return;
        final WebViewPopupEvent event = new WebViewPopupEvent(
            source, targetUrl, targetName, userGesture, width, height, pageUrl);
        openPopups.put(Long.valueOf(popupId), event);
        runOnEdtLater(new Runnable() {
            @Override public void run() {
                handler.popupOpened(event);
            }
        });
    }

    /** Async notification that a popup window closed; delivered on the EDT. */
    public void dispatchPopupClosed(long popupId, String targetUrl,
                                    String pageUrl) {
        if (disposed) return;
        WebViewPopupEvent existing = openPopups.remove(Long.valueOf(popupId));
        final WebViewPopupEvent event = (existing != null) ? existing
            : new WebViewPopupEvent(source, targetUrl, "", false, -1, -1,
                                    pageUrl);
        runOnEdtLater(new Runnable() {
            @Override public void run() {
                handler.popupClosed(event);
            }
        });
    }

    // ---------------------------------------------------------------------
    // EDT marshal + exception isolation (async notifications only).
    // ---------------------------------------------------------------------

    private void runOnEdtLater(final Runnable r) {
        final Runnable wrapped = new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    forwardUncaught(t);
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            wrapped.run();
        } else {
            SwingUtilities.invokeLater(wrapped);
        }
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
