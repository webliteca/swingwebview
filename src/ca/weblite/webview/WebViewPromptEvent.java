/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable carrier of one {@code window.prompt} request raised by the
 * embedded page, surfaced to {@link WebViewDialogHandler#promptOpened}.
 *
 * <p>The {@link #defaultValue()} carries whatever the page passed as
 * the second argument to {@code prompt(message, defaultValue)}.  JS
 * coerces a missing second argument to the empty string; the native
 * engine reports this as an empty string.  A page that explicitly
 * passes {@code null} is JS-coerced to the string {@code "null"} by
 * the engine, so {@link #defaultValue()} never carries the literal
 * Java null.
 */
public final class WebViewPromptEvent {

    private final WebViewComponent source;
    private final String message;
    private final String defaultValue;
    private final String pageUrl;
    private final String frameUrl;

    WebViewPromptEvent(WebViewComponent source, String message,
                       String defaultValue, String pageUrl, String frameUrl) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.message = message == null ? "" : message;
        this.defaultValue = defaultValue == null ? "" : defaultValue;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.frameUrl = frameUrl == null ? "" : frameUrl;
    }

    /** @return the WebView component the prompt originated from; never null. */
    public WebViewComponent source() { return source; }

    /** @return the prompt message; never null, may be empty. */
    public String message() { return message; }

    /** @return the prompt's default value; never null, may be empty. */
    public String defaultValue() { return defaultValue; }

    /** @return the top-level document URL; never null, may be empty. */
    public String pageUrl() { return pageUrl; }

    /** @return the initiating frame's URL; never null, may be empty. */
    public String frameUrl() { return frameUrl; }

    @Override
    public String toString() {
        String m = message;
        if (m.length() > 80) m = m.substring(0, 80) + "...";
        return "WebViewPromptEvent[message=" + m
            + ", defaultValue=" + defaultValue
            + ", pageUrl=" + pageUrl + "]";
    }
}
