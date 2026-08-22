/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.
 * Use {@link WebViewComponent#setDownloadHandler} /
 * {@link WebViewComponent#getDownloadHandler} instead.  This class is
 * {@code public} only because the consuming Swing subclasses live in a
 * different package and Java has no cross-package-but-non-public
 * access modifier; matches the existing pattern used by
 * {@code DialogDispatcher}, {@code PopupDispatcher},
 * {@code ConsoleDispatcher}, and {@code EvalDispatcher}.
 *
 * <p>Per-component fan-out hub for browser-initiated file downloads.
 * Holds the single active {@link WebViewDownloadHandler} reference and
 * mediates three native-side dispatches, each with different
 * marshalling:
 *
 * <ul>
 *   <li><b>The destination decision</b>
 *       ({@link #dispatchDownloadRequested}) hops to the EDT with
 *       {@link SwingUtilities#invokeAndWait} and returns the handler's
 *       answer synchronously — the native engine's thread is blocked
 *       awaiting it, exactly as for a {@code confirm} dialog.</li>
 *   <li><b>Progress</b> ({@link #dispatchDownloadProgress}) hops with
 *       {@code invokeLater} and is <em>coalesced</em>: at most one
 *       event per download is queued at a time, always carrying the
 *       latest counters.  A 100 MB transfer reporting every 8 KB would
 *       otherwise queue ~12,800 EDT tasks and starve the UI.</li>
 *   <li><b>Completion</b> ({@link #dispatchDownloadCompleted}) hops
 *       with {@code invokeLater} and is delivered <em>exactly once</em>
 *       per download.  No backend guarantees this natively — WebKitGTK
 *       can emit both {@code failed} and {@code finished} for one
 *       abandoned transfer — so the guarantee is owned here.</li>
 * </ul>
 *
 * <p><strong>Sanitisation.</strong>  The server-suggested filename is
 * attacker-controlled and is reduced to a safe leaf name by
 * {@link #sanitiseFileName} before any handler sees it.  This happens
 * once, here, rather than three times in three native languages;
 * {@code DialogDispatcher}'s accept-attribute normalisation sets the
 * precedent, and this is the only place a unit test can reach without
 * a live engine.
 *
 * <p><strong>Threading:</strong> the three {@code dispatch*} entry
 * points are invoked from whatever native UI thread the engine runs on
 * (AppKit main, GTK main, WebView2 worker).  Handler exceptions are
 * caught and forwarded to
 * {@link Thread#getDefaultUncaughtExceptionHandler()}; a throwing
 * {@link WebViewDownloadHandler#downloadRequested} is treated as a
 * refusal so the native side is always released cleanly.
 *
 * <p><strong>Disposal.</strong>  {@link #disposeAll()} is
 * authoritative: after it, every dispatch returns its safe fallback
 * without touching the handler.  A download can outlive the page, the
 * navigation, and the component, so this is the Java-side half of the
 * three gates protecting against a late event (the other two are the
 * native null-check on the JNI global ref and the ref being cleared
 * before view teardown).
 */
public final class DownloadDispatcher {

    /** Internal drop singleton — installed when caller passes
     *  {@code null} to {@link #setHandler}.  Refuses every download
     *  with no UI and no file.  Stateless; package-private (callers
     *  reset to the framework default by passing
     *  {@link WebViewDownloadHandler#DEFAULT}). */
    static final WebViewDownloadHandler DROP = new WebViewDownloadHandler() {
        @Override public File downloadRequested(WebViewDownloadEvent e) {
            return null;
        }
        @Override public void downloadProgress(
                WebViewDownloadProgressEvent e) { }
        @Override public void downloadCompleted(
                WebViewDownloadCompleteEvent e) { }
    };

    /** How many recently-terminated download ids are remembered so a
     *  late duplicate terminal event can be recognised and dropped.
     *  Bounded so a component that downloads for hours does not
     *  accumulate state. */
    private static final int TERMINATED_MEMORY = 256;

    /** Longest file name the sanitiser will emit.  255 bytes is the
     *  per-component limit on ext4, APFS, and NTFS alike. */
    private static final int MAX_NAME_LENGTH = 255;

    /** Longest suffix preserved when truncating an over-long name. */
    private static final int MAX_EXTENSION_LENGTH = 32;

    /** Names that open a device rather than a file on Windows. */
    private static final Set<String> RESERVED_DEVICE_NAMES;
    static {
        Set<String> s = new HashSet<String>(Arrays.asList(
            "CON", "PRN", "AUX", "NUL"));
        for (int i = 0; i <= 9; i++) {
            s.add("COM" + i);
            s.add("LPT" + i);
        }
        RESERVED_DEVICE_NAMES = Collections.unmodifiableSet(s);
    }

    /** One in-flight download: the destination the handler chose, the
     *  latest counters, the terminal latch, and the progress-coalescing
     *  gate. */
    private static final class Active {
        final File destination;
        final AtomicBoolean terminal = new AtomicBoolean(false);
        final AtomicLong received = new AtomicLong(0L);
        final AtomicLong total;
        final AtomicBoolean progressQueued = new AtomicBoolean(false);

        Active(File destination, long total) {
            this.destination = destination;
            this.total = new AtomicLong(total);
        }
    }

    private final WebViewComponent source;
    private volatile WebViewDownloadHandler handler =
        WebViewDownloadHandler.DEFAULT;
    private volatile boolean disposed = false;

    /** Live downloads, keyed by native id.  Entries are added when a
     *  destination is chosen and removed when the terminal event
     *  fires. */
    private final ConcurrentHashMap<Long, Active> active =
        new ConcurrentHashMap<Long, Active>();

    /** Bounded LRU of ids whose terminal event has already been
     *  reported, so a late duplicate — or a native failure for a
     *  download refused before it ever entered {@link #active} — is
     *  recognised and dropped.  Guarded by its own monitor. */
    private final Map<Long, Boolean> terminated =
        new LinkedHashMap<Long, Boolean>(64, 0.75f, false) {
            @Override protected boolean removeEldestEntry(
                    Map.Entry<Long, Boolean> eldest) {
                return size() > TERMINATED_MEMORY;
            }
        };

    public DownloadDispatcher(WebViewComponent source) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
    }

    /** Replace the active handler.  Passing {@code null} installs an
     *  internal drop handler that refuses every download without UI —
     *  useful for headless tests.  To reset to the framework default,
     *  pass {@link WebViewDownloadHandler#DEFAULT} explicitly. */
    public void setHandler(WebViewDownloadHandler h) {
        handler = (h == null) ? DROP : h;
    }

    /** @return the active handler; never {@code null}.  Returns
     *  {@link WebViewDownloadHandler#DEFAULT} when no caller has set
     *  one; returns the internal drop singleton when caller passed
     *  {@code null} to {@link #setHandler}. */
    public WebViewDownloadHandler getHandler() {
        return handler;
    }

    /**
     * Flip the dispatcher into disposed state.  After this call every
     * {@code dispatch*} method returns its safe fallback (refuse the
     * decision; drop the notifications) without invoking the handler,
     * and the in-flight bookkeeping is released.  Idempotent.  Called
     * from the Swing subclass's disposal path so a download that
     * outlives its component cannot fire into a torn-down Swing tree.
     */
    public void disposeAll() {
        disposed = true;
        active.clear();
        synchronized (terminated) {
            terminated.clear();
        }
    }

    /** @return whether the dispatcher has been disposed. */
    public boolean isDisposed() {
        return disposed;
    }

    // ---------------------------------------------------------------------
    // Native-facing dispatch entry points (invoked from JNI through the
    // WebViewDownloadCallback adapter installed at peer-attach time).
    // ---------------------------------------------------------------------

    /**
     * Native dispatch for the start of a download.  Blocks the calling
     * (native) thread while the handler runs on the EDT.
     *
     * @return the absolute path to write to, or {@code null} to refuse
     *         — in which case exactly one unsuccessful completion has
     *         already been reported and the id is latched, so a
     *         subsequent native failure for it is dropped.
     */
    public String dispatchDownloadRequested(long id, String url,
                                            String suggestedFileName,
                                            String mimeType, long totalBytes,
                                            String pageUrl) {
        if (disposed) return null;
        String safeName = sanitiseFileName(suggestedFileName, url);
        String mime = mimeType == null
            ? "" : mimeType.toLowerCase(Locale.ROOT).trim();
        final WebViewDownloadEvent event = new WebViewDownloadEvent(
            source, id, url, safeName, mime, totalBytes, pageUrl);
        final File[] cell = new File[1];
        runOnEdtAndWait(new Runnable() {
            @Override public void run() {
                cell[0] = handler.downloadRequested(event);
            }
        });
        File destination = cell[0];
        if (destination == null) {
            // Refusal, a throwing handler, or an interrupted EDT hop.
            // All three mean "do not write anything", and all three owe
            // the handler its single terminal event.
            reportTerminal(id, null, false, "Download refused by handler", 0L);
            return null;
        }
        active.put(Long.valueOf(id), new Active(destination, totalBytes));
        return destination.getAbsolutePath();
    }

    /**
     * Native dispatch for download progress.  Fire-and-forget;
     * coalesced so at most one event per download is queued on the EDT
     * at a time.  A dispatch for an unknown id is dropped silently —
     * that is the normal race between a terminal event and an
     * in-flight chunk notification, not an error.
     */
    public void dispatchDownloadProgress(final long id, long receivedBytes,
                                         long totalBytes) {
        if (disposed) return;
        final Long key = Long.valueOf(id);
        final Active a = active.get(key);
        if (a == null) return;

        // Keep the reported count monotonic even if the native side
        // delivers two chunk notifications out of order.
        long prev;
        while (true) {
            prev = a.received.get();
            if (receivedBytes <= prev) break;
            if (a.received.compareAndSet(prev, receivedBytes)) break;
        }
        if (totalBytes >= 0) a.total.set(totalBytes);

        if (!a.progressQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                // Clear the gate first so a dispatch arriving while
                // this task runs can queue the next one; then read the
                // counters, which are current as of right now.
                a.progressQueued.set(false);
                if (disposed) return;
                if (active.get(key) != a) return;
                if (a.terminal.get()) return;
                WebViewDownloadProgressEvent event =
                    new WebViewDownloadProgressEvent(source, id, a.destination,
                        a.received.get(), a.total.get());
                try {
                    handler.downloadProgress(event);
                } catch (Throwable t) {
                    forwardUncaught(t);
                }
            }
        });
    }

    /**
     * Native dispatch for a download's terminal state.  Fire-and-forget.
     * Delivered to the handler exactly once per download, whatever the
     * backend emits.
     */
    public void dispatchDownloadCompleted(long id, boolean success,
                                          String failureReason,
                                          long receivedBytes) {
        if (disposed) return;
        Active a = active.get(Long.valueOf(id));
        File destination = (a == null) ? null : a.destination;
        reportTerminal(id, destination, success, failureReason, receivedBytes);
    }

    // ---------------------------------------------------------------------
    // Exactly-once terminal reporting.
    // ---------------------------------------------------------------------

    /**
     * Deliver the single terminal event for {@code id}, or do nothing
     * if some other path already claimed it.  Every route to a terminal
     * outcome — native finish, native failure, refusal, a throwing
     * handler — goes through here.
     */
    private void reportTerminal(long id, File destination, boolean success,
                                String reason, long received) {
        Long key = Long.valueOf(id);
        Active a = active.get(key);
        if (a != null) {
            if (!a.terminal.compareAndSet(false, true)) return;
            active.remove(key);
            if (destination == null) destination = a.destination;
            long seen = a.received.get();
            if (seen > received) received = seen;
            rememberTerminated(key);
        } else if (!rememberTerminated(key)) {
            // No live download and the id is already latched: this is a
            // duplicate (a refused download's native cancellation, or a
            // backend emitting both `failed` and `finished`).
            return;
        }

        final WebViewDownloadCompleteEvent event =
            new WebViewDownloadCompleteEvent(source, id, destination, success,
                reason, received);
        if (disposed) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                if (disposed) return;
                try {
                    handler.downloadCompleted(event);
                } catch (Throwable t) {
                    forwardUncaught(t);
                }
            }
        });
    }

    /** @return {@code true} when {@code key} was not already latched
     *  (i.e. the caller has just claimed the terminal report). */
    private boolean rememberTerminated(Long key) {
        synchronized (terminated) {
            if (terminated.containsKey(key)) return false;
            terminated.put(key, Boolean.TRUE);
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // EDT marshal + exception isolation.  Same shape and semantics as
    // DialogDispatcher.runOnEdtVoid / forwardUncaught.
    // ---------------------------------------------------------------------

    /**
     * Run {@code r} on the EDT, blocking the caller until it completes.
     * Handler exceptions are caught and forwarded to
     * {@link Thread#getDefaultUncaughtExceptionHandler()} so they do
     * not propagate to the native side; the caller then sees the
     * unset cell, which is the safe fallback.
     */
    private void runOnEdtAndWait(final Runnable r) {
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
            // Fall through; caller gets the cell's current (null) value,
            // which is the safe fallback (refuse).
        } catch (InvocationTargetException ite) {
            // wrapped.run already caught and forwarded any handler
            // exception; an ITE here means invokeAndWait itself failed.
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
            try { t.printStackTrace(); } catch (Throwable ignored2) { }
        }
    }

    // ---------------------------------------------------------------------
    // Filename sanitisation.  The suggestion arrives from the wire
    // (Content-Disposition, or the URL's last path segment) and is
    // attacker-controlled: it may name a path, a parent directory, a
    // Windows device, or nothing at all.  The rules below are the union
    // of every supported platform's, so a name that is safe on the
    // developer's machine is safe on the user's.
    // ---------------------------------------------------------------------

    /**
     * Reduce a server-suggested download name to a bare, safe leaf file
     * name.  Never returns {@code null} or an empty string.
     *
     * @param suggested the name the server suggested; may be
     *                  {@code null} or empty
     * @param url       the download URL, used as a fallback source of a
     *                  name; may be {@code null}
     */
    static String sanitiseFileName(String suggested, String url) {
        String name = (suggested == null) ? "" : suggested;
        if (name.trim().length() == 0) {
            name = lastPathSegment(url);
        }

        // A suggestion names a leaf, never a path.  Cut everything up
        // to and including the last separator of either flavour, so
        // "../../../../etc/passwd" and "..\\..\\evil.exe" cannot steer
        // the write out of the folder the handler chose.
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) name = name.substring(cut + 1);

        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;
            if (c == '<' || c == '>' || c == ':' || c == '"'
                || c == '|' || c == '?' || c == '*') continue;
            sb.append(c);
        }
        name = sb.toString().trim();

        // Windows strips trailing dots and spaces silently, which would
        // otherwise let "evil.exe." and "evil.exe" name the same file
        // while a handler's de-duplication saw two distinct strings.
        int end = name.length();
        while (end > 0) {
            char c = name.charAt(end - 1);
            if (c == '.' || c == ' ') end--; else break;
        }
        name = name.substring(0, end).trim();

        if (name.length() == 0) name = "download";

        // "CON", "NUL", "LPT1" and friends open a device rather than a
        // file on Windows, extension or not.
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        if (RESERVED_DEVICE_NAMES.contains(base.toUpperCase(Locale.ROOT))) {
            name = "_" + name;
        }

        if (name.length() > MAX_NAME_LENGTH) {
            int d = name.lastIndexOf('.');
            String ext = "";
            if (d > 0 && (name.length() - d) <= MAX_EXTENSION_LENGTH) {
                ext = name.substring(d);
            }
            name = name.substring(0, MAX_NAME_LENGTH - ext.length()) + ext;
        }
        return name;
    }

    /** Last path segment of {@code url}, query and fragment stripped
     *  and percent-decoding applied best-effort.  Empty when the URL
     *  carries no usable segment. */
    private static String lastPathSegment(String url) {
        if (url == null) return "";
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        try {
            // URLDecoder maps '+' to a space, which is a query-string
            // rule, not a path rule; protect a literal '+' first.
            s = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException uee) {
            // UTF-8 is required of every JVM; unreachable in practice.
        } catch (RuntimeException re) {
            // A malformed escape sequence; keep the raw segment.
        }
        return s;
    }
}
