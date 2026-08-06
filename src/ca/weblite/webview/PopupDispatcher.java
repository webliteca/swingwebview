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

    /** Default grace period (ms) after which a popup decided
     *  {@link PopupDisposition#ADOPT} but never adopted is reclaimed. */
    static final long DEFAULT_ADOPT_GRACE_MS = 30000L;

    /** Popups decided {@link PopupDisposition#ADOPT} that have been retained
     *  natively but not yet claimed by {@link #claimAdopt}.  Keyed by the
     *  engine-assigned popup id. */
    private final ConcurrentHashMap<Long, WebViewPopupEvent> pendingAdopts =
        new ConcurrentHashMap<Long, WebViewPopupEvent>();

    /** Popup ids that have been successfully adopted, so a second
     *  {@link #claimAdopt} of the same id can be rejected with a distinct
     *  {@link IllegalStateException} rather than the "unknown id"
     *  {@link IllegalArgumentException}. */
    private final java.util.Set<Long> claimedAdopts =
        java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<Long, Boolean>());

    /** Per-pending-id grace tasks, so a claim can cancel the reclaim. */
    private final ConcurrentHashMap<Long, java.util.TimerTask> graceTasks =
        new ConcurrentHashMap<Long, java.util.TimerTask>();

    /** Lazily-created daemon timer that fires the unclaimed-adopt reclaim.
     *  Created on first {@link #dispatchPopupAdoptable} so components that
     *  never adopt spawn no timer thread.  Guarded by {@code this}. */
    private java.util.Timer graceTimer;

    private final long adoptGraceMs = DEFAULT_ADOPT_GRACE_MS;

    /** Installed by the owning Swing component so the dispatcher can ask the
     *  native engine to discard a retained-but-unadopted popup child. */
    public interface ReclaimSink {
        /** Discard the retained popup child with the given id. */
        void discard(long popupId);
    }

    private volatile ReclaimSink reclaimSink;

    public PopupDispatcher(WebViewComponent source) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
    }

    /** Install (or clear, when {@code null}) the sink used to discard
     *  retained-but-unadopted popup children during reclaim.  Set by the
     *  component at peer-attach time. */
    public void setReclaimSink(ReclaimSink sink) {
        this.reclaimSink = sink;
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
        // Reclaim any retained-but-unadopted popup children before the native
        // peer is destroyed, then cancel the grace timer.
        reclaimAdopts();
        synchronized (this) {
            if (graceTimer != null) {
                graceTimer.cancel();
                graceTimer = null;
            }
        }
        graceTasks.clear();
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

    /** Synchronous disposition gate — the enriched successor to
     *  {@link #dispatchPopupRequested}.  Runs the handler inline on the
     *  calling (native UI) thread and returns the chosen
     *  {@link PopupDisposition}'s ordinal; never marshals to the EDT.  A
     *  disposed dispatcher, a {@code null} return, or a thrown decision all
     *  yield {@link PopupDisposition#BLOCK} (safe default: block). */
    public int dispatchPopupDisposition(String targetUrl, String targetName,
                                        boolean userGesture, int width,
                                        int height, String pageUrl) {
        if (disposed) return PopupDisposition.BLOCK.ordinal();
        final WebViewPopupEvent event = new WebViewPopupEvent(
            source, targetUrl, targetName, userGesture, width, height, pageUrl);
        try {
            PopupDisposition d = handler.popupDisposition(event);
            return (d == null ? PopupDisposition.BLOCK : d).ordinal();
        } catch (Throwable t) {
            forwardUncaught(t);
            return PopupDisposition.BLOCK.ordinal();
        }
    }

    /** Async notification that a popup was decided
     *  {@link PopupDisposition#ADOPT} and is retained under a hidden holder.
     *  Records the pending adoption (for {@link #claimAdopt} and reclaim),
     *  arms the grace-period backstop, and delivers
     *  {@link WebViewPopupHandler#popupAdoptable} on the EDT. */
    public void dispatchPopupAdoptable(long popupId, String targetUrl,
                                       String targetName, boolean userGesture,
                                       int width, int height, String pageUrl) {
        if (disposed) return;
        final Long key = Long.valueOf(popupId);
        final WebViewPopupEvent event = new WebViewPopupEvent(
            source, targetUrl, targetName, userGesture, width, height, pageUrl);
        pendingAdopts.put(key, event);
        scheduleGrace(key);
        runOnEdtLater(new Runnable() {
            @Override public void run() {
                handler.popupAdoptable(event, popupId);
            }
        });
    }

    /** Claim a pending adoption by its {@code popupId}, enforcing
     *  adopt-once.  Called by the adopting component at peer-attach.
     *
     *  @return the event recorded at {@link #dispatchPopupAdoptable}
     *  @throws IllegalArgumentException if {@code popupId} was never issued
     *          (or already expired / reclaimed)
     *  @throws IllegalStateException    if {@code popupId} was already adopted
     */
    public WebViewPopupEvent claimAdopt(long popupId) {
        final Long key = Long.valueOf(popupId);
        WebViewPopupEvent event = pendingAdopts.remove(key);
        if (event == null) {
            if (claimedAdopts.contains(key)) {
                throw new IllegalStateException(
                    "popup " + popupId + " has already been adopted");
            }
            throw new IllegalArgumentException(
                "unknown or expired popup id: " + popupId);
        }
        claimedAdopts.add(key);
        cancelGrace(key);
        return event;
    }

    /** Discard every retained-but-unadopted popup child via the reclaim
     *  sink, clearing the pending map.  Invoked from {@link #disposeAll} and
     *  never throws out to the caller. */
    public void reclaimAdopts() {
        final ReclaimSink sink = reclaimSink;
        for (Long id : pendingAdopts.keySet()) {
            WebViewPopupEvent removed = pendingAdopts.remove(id);
            cancelGrace(id);
            if (removed == null) continue;
            if (sink != null) {
                try {
                    sink.discard(id.longValue());
                } catch (Throwable t) {
                    forwardUncaught(t);
                }
            }
        }
    }

    /** @return the number of retained-but-unadopted popup children (test /
     *  diagnostic hook). */
    public int pendingAdoptCount() {
        return pendingAdopts.size();
    }

    // ---------------------------------------------------------------------
    // Grace-period reclaim backstop.
    // ---------------------------------------------------------------------

    private void scheduleGrace(final Long key) {
        java.util.Timer timer;
        synchronized (this) {
            if (disposed) return;
            if (graceTimer == null) {
                graceTimer =
                    new java.util.Timer("webview-popup-adopt-grace", true);
            }
            timer = graceTimer;
        }
        java.util.TimerTask task = new java.util.TimerTask() {
            @Override public void run() {
                WebViewPopupEvent removed = pendingAdopts.remove(key);
                graceTasks.remove(key);
                if (removed == null) return; // already claimed or reclaimed
                ReclaimSink sink = reclaimSink;
                if (sink != null) {
                    try {
                        sink.discard(key.longValue());
                    } catch (Throwable t) {
                        forwardUncaught(t);
                    }
                }
            }
        };
        graceTasks.put(key, task);
        try {
            timer.schedule(task, adoptGraceMs);
        } catch (Throwable ignored) {
            // Timer was cancelled concurrently (disposeAll); the reclaim on
            // that path already handled this id.
            graceTasks.remove(key);
        }
    }

    private void cancelGrace(Long key) {
        java.util.TimerTask task = graceTasks.remove(key);
        if (task != null) task.cancel();
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
