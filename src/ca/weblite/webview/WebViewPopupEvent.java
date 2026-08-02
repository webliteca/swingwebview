/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable carrier of one browser-initiated popup request raised by the
 * embedded page — {@code window.open(url, name, features)} or a click on a
 * link / form with {@code target="_blank"} — surfaced to
 * {@link WebViewPopupHandler}.
 *
 * <p>String accessors return non-null values; {@code targetUrl},
 * {@code targetName}, and {@code pageUrl} are the empty string when the
 * engine reports no value.  {@link #width()} and {@link #height()} are
 * {@code -1} when the page did not request an explicit size.
 */
public final class WebViewPopupEvent {

    private final WebViewComponent source;
    private final String targetUrl;
    private final String targetName;
    private final boolean userGesture;
    private final int width;
    private final int height;
    private final String pageUrl;

    WebViewPopupEvent(WebViewComponent source, String targetUrl,
                      String targetName, boolean userGesture,
                      int width, int height, String pageUrl) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.targetUrl = targetUrl == null ? "" : targetUrl;
        this.targetName = targetName == null ? "" : targetName;
        this.userGesture = userGesture;
        this.width = width;
        this.height = height;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
    }

    /** @return the opener WebView component; never null. */
    public WebViewComponent source() { return source; }

    /** @return the URL the popup will load; never null, may be empty
     *  (empty when {@code window.open()} was called with no URL). */
    public String targetUrl() { return targetUrl; }

    /** @return the requested window name / target; never null, may be
     *  empty. */
    public String targetName() { return targetName; }

    /** @return whether a user gesture initiated the popup. */
    public boolean userGesture() { return userGesture; }

    /** @return the requested popup width, or {@code -1} when the page did
     *  not request an explicit size. */
    public int width() { return width; }

    /** @return the requested popup height, or {@code -1} when the page did
     *  not request an explicit size. */
    public int height() { return height; }

    /** @return the opener page URL; never null, may be empty. */
    public String pageUrl() { return pageUrl; }

    @Override
    public String toString() {
        String u = targetUrl;
        if (u.length() > 80) u = u.substring(0, 80) + "...";
        return "WebViewPopupEvent[url=" + u + ", name=" + targetName
            + ", size=" + width + "x" + height + "]";
    }
}
