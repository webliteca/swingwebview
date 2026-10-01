/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * {@link WebViewComponent#setNavigationHandler} instead.  This class is
 * {@code public} only because the consuming Swing subclasses live in a
 * different package, like {@link PopupDispatcher}.
 *
 * <p>Per-component hub for navigation decisions (Canvas 34 D3, D4).  Holds
 * the active {@link WebViewNavigationHandler} and the application's own
 * pending navigations.
 *
 * <p><strong>Threading:</strong> {@link #dispatch} runs the handler
 * <strong>inline on the calling (native UI) thread</strong> and returns its
 * answer synchronously; it never marshals to the EDT.  Every failure —
 * a disposed dispatcher, a thrown handler — refuses.
 */
public final class NavigationDispatcher {

    /** Canvas 34 D4: how long an expected application navigation stays
     *  recognisable. */
    static final long EXPECT_MILLIS = 30000L;
    /** Canvas 34 D4: how many expected application navigations are kept. */
    static final int EXPECT_MAX = 16;

    /** A time source, replaceable in tests. */
    interface Clock {
        long millis();
    }

    private final WebViewComponent source;
    private final Clock clock;
    private volatile WebViewNavigationHandler handler =
        WebViewNavigationHandler.DEFAULT;
    private volatile boolean disposed = false;

    /** Normalized URLs the application is about to load, oldest first,
     *  each with its deadline.  Guarded by {@code this}. */
    private final ArrayDeque<Object[]> expected = new ArrayDeque<Object[]>();
    /** D4: whether the last decision allowed an application-initiated
     *  navigation, which a following redirect inherits.  Guarded by
     *  {@code this}. */
    private boolean lastAllowedApplication = false;

    public NavigationDispatcher(WebViewComponent source) {
        this(source, new Clock() {
            @Override public long millis() {
                return System.currentTimeMillis();
            }
        });
    }

    NavigationDispatcher(WebViewComponent source, Clock clock) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.clock = clock;
    }

    /** Canvas 34 D6: whether the loaded native library can decide
     *  navigations; {@code false} against a native built before the
     *  feature, or without natives at all. */
    public static boolean isAvailable() {
        try {
            return WebViewNative.webview_navigation_available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Replace the active handler.  {@code null} installs
     *  {@link WebViewNavigationHandler#DEFAULT}, which allows everything
     *  (Canvas 34 D1). */
    public void setHandler(WebViewNavigationHandler h) {
        handler = h == null ? WebViewNavigationHandler.DEFAULT : h;
    }

    /** @return the active handler; never {@code null}. */
    public WebViewNavigationHandler getHandler() {
        return handler;
    }

    /**
     * Record that the application is about to load {@code url} (Canvas 34
     * D4).  Called immediately before each native navigate the component
     * makes; the first matching navigation within 30 seconds is marked
     * application-initiated.
     */
    public void expectApplicationNavigation(String url) {
        if (url == null) return;
        String key = normalize(url);
        synchronized (this) {
            expected.addLast(new Object[] {key, clock.millis() + EXPECT_MILLIS});
            while (expected.size() > EXPECT_MAX) expected.removeFirst();
        }
    }

    /**
     * Decide one navigation (Canvas 34 D3).  Runs the handler inline on the
     * calling thread.
     *
     * @return {@code true} to let it proceed
     */
    public boolean dispatch(String url, String currentUrl, int causeOrdinal) {
        if (disposed) return false;
        NavigationCause cause = NavigationCause.fromOrdinal(causeOrdinal);
        boolean application;
        synchronized (this) {
            application = cause == NavigationCause.REDIRECT
                ? lastAllowedApplication
                : consumeExpected(url);
            lastAllowedApplication = false;
        }
        WebViewNavigationHandler h = handler;
        boolean allowed;
        if (h == WebViewNavigationHandler.DEFAULT) {
            allowed = true;
        } else {
            try {
                allowed = h.navigationRequested(new WebViewNavigationEvent(
                    source, url, currentUrl, cause, application));
            } catch (Throwable t) {
                forwardUncaught(t);
                allowed = false;
            }
        }
        if (allowed && application) {
            synchronized (this) {
                lastAllowedApplication = true;
            }
        }
        return allowed && !disposed;
    }

    /** Flip into disposed state: every later {@link #dispatch} refuses.
     *  Idempotent. */
    public void disposeAll() {
        disposed = true;
        synchronized (this) {
            expected.clear();
            lastAllowedApplication = false;
        }
    }

    /** @return whether the dispatcher has been disposed. */
    public boolean isDisposed() {
        return disposed;
    }

    /** Remove and report the first unexpired expectation matching
     *  {@code url}; drop expired ones on the way.  Caller holds the lock. */
    private boolean consumeExpected(String url) {
        if (url == null || expected.isEmpty()) return false;
        String key = normalize(url);
        long now = clock.millis();
        for (Iterator<Object[]> it = expected.iterator(); it.hasNext(); ) {
            Object[] e = it.next();
            if ((Long) e[1] < now) {
                it.remove();
                continue;
            }
            if (key.equals(e[0])) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Canvas 34 D4a: lower-case scheme and host, drop the default port, an
     * empty hierarchical path becomes {@code /}, drop the fragment.  A URL
     * {@link URI} can't parse compares as its exact string.
     */
    static String normalize(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (scheme == null) return url;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (u.isOpaque()) {
                return scheme + ":" + u.getRawSchemeSpecificPart();
            }
            String host = u.getHost();
            int port = u.getPort();
            if (("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            StringBuilder b = new StringBuilder(scheme).append("://");
            if (u.getRawUserInfo() != null) b.append(u.getRawUserInfo()).append('@');
            if (host != null) {
                b.append(host.toLowerCase(Locale.ROOT));
            } else if (u.getRawAuthority() != null) {
                b.append(u.getRawAuthority().toLowerCase(Locale.ROOT));
            }
            if (port != -1 && host != null) b.append(':').append(port);
            b.append(path);
            if (u.getRawQuery() != null) b.append('?').append(u.getRawQuery());
            return b.toString();
        } catch (Exception unparseable) {
            return url;
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
