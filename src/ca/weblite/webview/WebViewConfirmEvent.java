/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable carrier of one {@code window.confirm} request raised by the
 * embedded page, surfaced to {@link WebViewDialogHandler#confirmOpened}.
 *
 * <p>Shape matches {@link WebViewAlertEvent} verbatim — both events
 * carry only a single message string plus the page / frame URLs.  The
 * two classes are intentionally distinct types (rather than a shared
 * base or a single discriminated event) so handler method signatures
 * stay honest about which dialog kind they handle.
 */
public final class WebViewConfirmEvent {

    private final WebViewComponent source;
    private final String message;
    private final String pageUrl;
    private final String frameUrl;

    WebViewConfirmEvent(WebViewComponent source, String message,
                        String pageUrl, String frameUrl) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.message = message == null ? "" : message;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.frameUrl = frameUrl == null ? "" : frameUrl;
    }

    /** @return the WebView component the confirm originated from; never null. */
    public WebViewComponent source() { return source; }

    /** @return the confirm message; never null, may be empty. */
    public String message() { return message; }

    /** @return the top-level document URL; never null, may be empty. */
    public String pageUrl() { return pageUrl; }

    /** @return the initiating frame's URL; never null, may be empty. */
    public String frameUrl() { return frameUrl; }

    @Override
    public String toString() {
        String m = message;
        if (m.length() > 80) m = m.substring(0, 80) + "...";
        return "WebViewConfirmEvent[message=" + m + ", pageUrl=" + pageUrl + "]";
    }
}
