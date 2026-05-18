/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable carrier of one {@code window.alert} request raised by the
 * embedded page, surfaced to {@link WebViewDialogHandler#alertOpened}.
 *
 * <p>All accessors return non-null values.  {@code message},
 * {@code pageUrl}, and {@code frameUrl} are the empty string when the
 * native engine reports no value (the engine may legitimately do so;
 * empty is the "no value" sentinel for these fields).
 */
public final class WebViewAlertEvent {

    private final WebViewComponent source;
    private final String message;
    private final String pageUrl;
    private final String frameUrl;

    WebViewAlertEvent(WebViewComponent source, String message,
                      String pageUrl, String frameUrl) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.message = message == null ? "" : message;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.frameUrl = frameUrl == null ? "" : frameUrl;
    }

    /** @return the WebView component the alert originated from; never null. */
    public WebViewComponent source() { return source; }

    /** @return the alert message; never null, may be empty. */
    public String message() { return message; }

    /** @return the top-level document URL; never null, may be empty. */
    public String pageUrl() { return pageUrl; }

    /** @return the initiating frame's URL; never null, may be empty.
     *  Equals {@link #pageUrl()} when the alert came from the top-level
     *  frame. */
    public String frameUrl() { return frameUrl; }

    @Override
    public String toString() {
        String m = message;
        if (m.length() > 80) m = m.substring(0, 80) + "...";
        return "WebViewAlertEvent[message=" + m + ", pageUrl=" + pageUrl + "]";
    }
}
