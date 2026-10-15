/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * An application's answer to a media-capture request (Canvas 35 D2).  The
 * ordinals are the native wire contract; never reorder.
 */
public enum PermissionDecision {
    /**
     * Do what the engine does when nobody answers: WebKitGTK refuses,
     * WebView2 shows its own prompt, WKWebView shows its own prompt.
     */
    DEFAULT,
    /** Grant the devices asked for. */
    ALLOW,
    /** Refuse; the page's promise rejects with {@code NotAllowedError}. */
    DENY;

    /** @return the decision with this wire ordinal, or {@link #DENY} for an
     *  unknown one. */
    static PermissionDecision fromOrdinal(int ordinal) {
        PermissionDecision[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : DENY;
    }
}
