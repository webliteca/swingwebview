/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.
 * Use {@link WebViewComponent#setDialogHandler} /
 * {@link WebViewComponent#getDialogHandler} instead.  This class is
 * {@code public} only because the consuming Swing subclasses live in
 * a different package and Java has no cross-package-but-non-public
 * access modifier; matches the existing pattern used by
 * {@code ConsoleDispatcher}, {@code WebViewMouseDispatcher},
 * {@code EvalDispatcher}, {@code EmbeddedWebView}, and
 * {@code OffscreenWebView}.
 *
 * <p>Per-component fan-out hub for browser-initiated UI dialogs.
 * Holds the single active {@link WebViewDialogHandler} reference and
 * marshals each native-side dispatch onto the Swing EDT via
 * {@link SwingUtilities#invokeAndWait}.  Captures the handler's return
 * value and propagates it back to the native side, which then
 * completes the platform's deferral / completion handler and releases
 * the page's JavaScript thread.
 *
 * <p><strong>Threading:</strong> the four {@code dispatch*} entry
 * points are invoked from whatever native UI thread the engine runs
 * on (AppKit main, GTK main, WebView2 worker).  Each method hops to
 * the EDT, runs the handler, and returns the captured value
 * synchronously.  Handler exceptions are caught and forwarded to
 * {@link Thread#getDefaultUncaughtExceptionHandler()} (matches the
 * established {@code ConsoleDispatcher} pattern); a safe fallback
 * value is returned so the native completion handler always fires.
 *
 * <p><strong>Divergence from {@code ConsoleDispatcher} /
 * {@code WebViewMouseDispatcher}:</strong> dialogs need a single
 * resolver, not a listener list, so this class holds a single
 * volatile handler reference and uses {@code invokeAndWait} rather
 * than {@code invokeLater}.  Disposal flips a {@code disposed} flag;
 * subsequent dispatches return safe fallbacks without invoking the
 * handler.
 */
public final class DialogDispatcher {

    /** Internal drop singleton — installed when caller passes
     *  {@code null} to {@link #setHandler}.  Returns the JS-spec
     *  cancel values synchronously without UI.  Stateless; package-
     *  private (callers reset to the framework default by passing
     *  {@link WebViewDialogHandler#DEFAULT}). */
    static final WebViewDialogHandler DROP = new WebViewDialogHandler() {
        @Override public void alertOpened(WebViewAlertEvent e) { }
        @Override public boolean confirmOpened(WebViewConfirmEvent e) {
            return false;
        }
        @Override public String promptOpened(WebViewPromptEvent e) {
            return null;
        }
        @Override public List<File> filePickerOpened(WebViewFilePickerEvent e) {
            return Collections.emptyList();
        }
    };

    private final WebViewComponent source;
    private volatile WebViewDialogHandler handler = WebViewDialogHandler.DEFAULT;
    private volatile boolean disposed = false;

    public DialogDispatcher(WebViewComponent source) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
    }

    /** Replace the active handler.  Passing {@code null} installs an
     *  internal drop handler that returns the JS-spec cancel values
     *  without UI — useful for headless tests.  To reset to the
     *  framework default, pass {@link WebViewDialogHandler#DEFAULT}
     *  explicitly. */
    public void setHandler(WebViewDialogHandler h) {
        handler = (h == null) ? DROP : h;
    }

    /** @return the active handler; never {@code null}.  Returns
     *  {@link WebViewDialogHandler#DEFAULT} when no caller has set
     *  one; returns the internal drop singleton when caller passed
     *  {@code null} to {@link #setHandler}. */
    public WebViewDialogHandler getHandler() {
        return handler;
    }

    /**
     * Flip the dispatcher into disposed state.  After this call, every
     * {@code dispatch*} method returns the safe fallback (void / false
     * / null / empty array) without invoking the handler.  Idempotent.
     * Called from the Swing subclass's disposal path so the native
     * completion handler still fires cleanly even when the native
     * teardown races with an in-flight dialog event.
     */
    public void disposeAll() {
        disposed = true;
    }

    /** @return whether the dispatcher has been disposed. */
    public boolean isDisposed() {
        return disposed;
    }

    // ---------------------------------------------------------------------
    // Native-facing dispatch entry points (invoked from JNI through the
    // WebViewDialogCallback adapter installed at peer-attach time).
    // ---------------------------------------------------------------------

    /** Native dispatch for {@code window.alert}. */
    public void dispatchAlert(String message, String pageUrl, String frameUrl) {
        if (disposed) return;
        final WebViewAlertEvent event = new WebViewAlertEvent(
            source, message, pageUrl, frameUrl);
        runOnEdtVoid(new Runnable() {
            @Override public void run() {
                handler.alertOpened(event);
            }
        });
    }

    /** Native dispatch for {@code window.confirm}. */
    public boolean dispatchConfirm(String message, String pageUrl,
                                   String frameUrl) {
        if (disposed) return false;
        final WebViewConfirmEvent event = new WebViewConfirmEvent(
            source, message, pageUrl, frameUrl);
        final boolean[] cell = new boolean[1];
        runOnEdtVoid(new Runnable() {
            @Override public void run() {
                cell[0] = handler.confirmOpened(event);
            }
        });
        return cell[0];
    }

    /** Native dispatch for {@code window.prompt}; {@code null} return
     *  signals cancel. */
    public String dispatchPrompt(String message, String defaultValue,
                                 String pageUrl, String frameUrl) {
        if (disposed) return null;
        final WebViewPromptEvent event = new WebViewPromptEvent(
            source, message, defaultValue, pageUrl, frameUrl);
        final String[] cell = new String[1];
        runOnEdtVoid(new Runnable() {
            @Override public void run() {
                cell[0] = handler.promptOpened(event);
            }
        });
        return cell[0];
    }

    /** Native dispatch for {@code <input type="file">} click; returns
     *  an array of absolute file paths.  Empty array signals cancel. */
    public String[] dispatchFilePicker(boolean multiple, String[] mimeTypes,
                                       String[] extensions, String pageUrl,
                                       String frameUrl) {
        if (disposed) return new String[0];
        final List<String> normExts = normaliseExtensions(extensions);
        final List<String> normMimes = normaliseMimeTypes(mimeTypes);
        final WebViewFilePickerEvent event = new WebViewFilePickerEvent(
            source, multiple, normExts, normMimes, pageUrl, frameUrl);
        final Object[] cell = new Object[1];
        runOnEdtVoid(new Runnable() {
            @Override public void run() {
                cell[0] = handler.filePickerOpened(event);
            }
        });
        @SuppressWarnings("unchecked")
        List<File> files = (List<File>) cell[0];
        if (files == null || files.isEmpty()) return new String[0];
        List<String> paths = new ArrayList<String>(files.size());
        for (File f : files) {
            if (f != null) paths.add(f.getAbsolutePath());
        }
        return paths.toArray(new String[0]);
    }

    // ---------------------------------------------------------------------
    // EDT marshal + exception isolation.
    // ---------------------------------------------------------------------

    /**
     * Run {@code r} on the EDT, blocking the caller until it
     * completes.  Handler exceptions are caught and forwarded to
     * {@link Thread#getDefaultUncaughtExceptionHandler()} so they do
     * not propagate to the native side.
     */
    private void runOnEdtVoid(final Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                r.run();
            } catch (Throwable t) {
                forwardUncaught(t);
            }
            return;
        }
        final Runnable wrapped = new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    forwardUncaught(t);
                }
            }
        };
        try {
            SwingUtilities.invokeAndWait(wrapped);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Fall through; caller gets the cell's current (zero-init)
            // value, which is the safe fallback.
        } catch (InvocationTargetException ite) {
            // wrapped.run already caught and forwarded any handler
            // exception; an ITE here means invokeAndWait itself
            // failed (the wrapped Runnable threw before our catch
            // could swallow — should be impossible since we wrap
            // everything, but be defensive).
            Throwable cause = ite.getCause();
            forwardUncaught(cause == null ? ite : cause);
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
            // Last-ditch: an uncaught-handler that itself throws.  Print
            // the original stack trace and move on.
            try { t.printStackTrace(); } catch (Throwable ignored2) {}
        }
    }

    // ---------------------------------------------------------------------
    // Accept-attribute normalisation: lower-case, strip leading dot
    // (extensions only), deduplicate preserving insertion order.
    // ---------------------------------------------------------------------

    static List<String> normaliseExtensions(String[] input) {
        if (input == null || input.length == 0) return Collections.emptyList();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String raw : input) {
            if (raw == null) continue;
            String s = raw.toLowerCase(Locale.ROOT).trim();
            if (s.startsWith(".")) s = s.substring(1);
            if (s.isEmpty()) continue;
            seen.add(s);
        }
        if (seen.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<String>(seen));
    }

    static List<String> normaliseMimeTypes(String[] input) {
        if (input == null || input.length == 0) return Collections.emptyList();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String raw : input) {
            if (raw == null) continue;
            String s = raw.toLowerCase(Locale.ROOT).trim();
            if (s.isEmpty()) continue;
            seen.add(s);
        }
        if (seen.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<String>(seen));
    }
}
