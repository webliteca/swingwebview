/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * What started a navigation reported to {@link WebViewNavigationHandler}
 * (Canvas 34 D2).
 *
 * <p>Each engine reports what it knows.  WebView2 (Windows) cannot tell a
 * link from a form or a script and reports those as {@link #OTHER};
 * WKWebView (macOS) does not flag server redirects and reports them as
 * {@link #OTHER}.
 *
 * <p><strong>Ordinal wire contract.</strong> The ordinals ({@code LINK = 0}
 * through {@code OTHER = 5}) are shared with the native engines through
 * {@link WebViewNavigationCallback#onNavigationRequested} and MUST NOT be
 * reordered.
 */
public enum NavigationCause {

    /** A link was followed, including one with a {@code download}
     *  attribute. */
    LINK,

    /** A form was submitted (or resubmitted) to this view or frame. */
    FORM,

    /** The session history moved back or forward. */
    BACK_FORWARD,

    /** The page was reloaded. */
    RELOAD,

    /** A server redirected an earlier navigation. */
    REDIRECT,

    /** Anything else: script ({@code location.href}, {@code assign},
     *  {@code replace}), a meta refresh, a frame's first page, or the
     *  application's own {@code setUrl}. */
    OTHER;

    /** The cause with {@code ordinal}, or {@link #OTHER} for an unknown
     *  value. */
    static NavigationCause fromOrdinal(int ordinal) {
        NavigationCause[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : OTHER;
    }
}
