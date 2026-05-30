/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.
 * Use {@link WebViewComponent#setDownloadHandler} /
 * {@link WebViewComponent#getDownloadHandler} instead. This class
 * is {@code public} only because the consuming Swing subclasses
 * live in a different package and Java has no
 * cross-package-but-non-public access modifier; matches the
 * existing pattern used by {@code DialogDispatcher},
 * {@code ConsoleDispatcher}, {@code WebViewMouseDispatcher},
 * {@code EvalDispatcher}, {@code EmbeddedWebView}, and
 * {@code OffscreenWebView}.
 *
 * <p>Per-component fan-out hub for browser-initiated downloads.
 * Holds the single active {@link WebViewDownloadHandler} reference
 * and marshals each native-side dispatch onto the Swing EDT via
 * {@link SwingUtilities#invokeAndWait}. Captures the handler's
 * returned {@link File}, converts to an absolute path string (or
 * {@code null} for cancel), and propagates it back to the native
 * side, which then resolves the platform's deferral.
 *
 * <p><strong>Threading.</strong> The single {@code dispatchDownload}
 * entry point is invoked from a short-lived worker thread spawned
 * by the native UI thread (AppKit main on macOS, WebView2 worker
 * on Windows) or directly from the GTK pump thread on Linux. The
 * method hops to the EDT, runs the handler, and returns the
 * captured path synchronously. Handler exceptions are caught and
 * forwarded to {@link Thread#getDefaultUncaughtExceptionHandler()}
 * (matches the established {@code DialogDispatcher} pattern); a
 * safe fallback ({@code null}, meaning cancel) is returned so the
 * native deferral always resolves.
 *
 * <p><strong>Divergence from {@code DialogDispatcher}:</strong>
 * downloads have one dispatch entry point (not four), construct
 * the event POJO inside the dispatcher (not in the native bridge),
 * and return a {@code String} path (not void / boolean / String /
 * String[]).
 */
public final class DownloadDispatcher {

    /** Internal drop singleton — installed when caller passes
     *  {@code null} to {@link #setHandler}. Returns {@code null}
     *  (cancel) synchronously without UI. Stateless; package-
     *  private (callers reset to the framework default by passing
     *  {@link WebViewDownloadHandler#DEFAULT}). */
    static final WebViewDownloadHandler DROP = new WebViewDownloadHandler() {
        @Override public File downloadRequested(WebViewDownloadEvent e) {
            return null;
        }
    };

    private final WebViewComponent source;
    private volatile WebViewDownloadHandler handler = WebViewDownloadHandler.DEFAULT;
    private volatile boolean disposed = false;

    public DownloadDispatcher(WebViewComponent source) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
    }

    /** Replace the active handler. Passing {@code null} installs an
     *  internal drop handler that cancels every download without UI
     *  — useful for headless tests. To reset to the framework
     *  default, pass {@link WebViewDownloadHandler#DEFAULT}
     *  explicitly. */
    public void setHandler(WebViewDownloadHandler h) {
        handler = (h == null) ? DROP : h;
    }

    /** @return the active handler; never {@code null}. Returns
     *  {@link WebViewDownloadHandler#DEFAULT} when no caller has
     *  installed one; returns the internal drop singleton when
     *  caller passed {@code null} to {@link #setHandler}. */
    public WebViewDownloadHandler getHandler() {
        return handler;
    }

    /**
     * Flip the dispatcher into disposed state. After this call,
     * every {@code dispatchDownload} invocation returns {@code null}
     * (cancel) without invoking the handler. Idempotent. Called
     * from the Swing subclass's disposal path so the native
     * deferral still resolves cleanly even when native teardown
     * races with an in-flight download event.
     */
    public void disposeAll() {
        disposed = true;
    }

    /** @return whether the dispatcher has been disposed. */
    public boolean isDisposed() {
        return disposed;
    }

    // ---------------------------------------------------------------------
    // Native-facing dispatch entry point (invoked from JNI through the
    // WebViewDownloadCallback adapter installed at peer-attach time).
    // ---------------------------------------------------------------------

    /** Native dispatch for "download starting". Returns the absolute
     *  path string of the chosen destination, or {@code null} to
     *  cancel. */
    public String dispatchDownload(String suggestedFilename, String sourceUrl,
                                   String mimeType, long totalBytes) {
        if (disposed) return null;
        final WebViewDownloadEvent event = new WebViewDownloadEvent(
            source, suggestedFilename, sourceUrl, mimeType, totalBytes);
        final File result = runOnEdtAndCapture(event);
        if (result == null) return null;
        String path = result.getAbsolutePath();
        return (path == null || path.isEmpty()) ? null : path;
    }

    // ---------------------------------------------------------------------
    // EDT marshal + exception isolation.
    // ---------------------------------------------------------------------

    /**
     * Run the handler on the EDT, returning its {@link File} result.
     * Handler exceptions are caught and forwarded to
     * {@link Thread#getDefaultUncaughtExceptionHandler()} so they
     * do not propagate to the native side; the captured cell stays
     * {@code null} in that case.
     */
    private File runOnEdtAndCapture(final WebViewDownloadEvent event) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return handler.downloadRequested(event);
            } catch (Throwable t) {
                forwardUncaught(t);
                return null;
            }
        }
        final File[] cell = new File[1];
        final Runnable wrapped = new Runnable() {
            @Override public void run() {
                try {
                    cell[0] = handler.downloadRequested(event);
                } catch (Throwable t) {
                    forwardUncaught(t);
                }
            }
        };
        try {
            SwingUtilities.invokeAndWait(wrapped);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Fall through; cell[0] is the safe fallback (null = cancel).
        } catch (InvocationTargetException ite) {
            // wrapped.run already caught and forwarded any handler
            // exception; an ITE here means invokeAndWait itself
            // failed (the wrapped Runnable threw before our catch
            // could swallow — should be impossible since we wrap
            // everything, but be defensive).
            Throwable cause = ite.getCause();
            forwardUncaught(cause == null ? ite : cause);
        }
        return cell[0];
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
            // Last-ditch: an uncaught-handler that itself throws.
            // Print the original stack trace and move on.
            try { t.printStackTrace(); } catch (Throwable ignored2) {}
        }
    }
}
