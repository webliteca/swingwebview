/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable carrier of one navigation of a component's view or of one of
 * its frames, surfaced to {@link WebViewNavigationHandler} before the
 * engine sends any request (Canvas 34 D2).
 *
 * <p>String accessors never return null; an address the engine does not
 * report is the empty string.  A frame's navigation is not told apart from
 * the view's own: WebKitGTK cannot, so no engine claims to.
 */
public final class WebViewNavigationEvent {

    private final WebViewComponent source;
    private final String url;
    private final String currentUrl;
    private final NavigationCause cause;
    private final boolean applicationInitiated;

    WebViewNavigationEvent(WebViewComponent source, String url,
                           String currentUrl, NavigationCause cause,
                           boolean applicationInitiated) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.url = url == null ? "" : url;
        this.currentUrl = currentUrl == null ? "" : currentUrl;
        this.cause = cause == null ? NavigationCause.OTHER : cause;
        this.applicationInitiated = applicationInitiated;
    }

    /** @return the component whose view (or frame) is navigating; never
     *  null. */
    public WebViewComponent source() { return source; }

    /** @return the address the navigation would load; never null. */
    public String url() { return url; }

    /** @return the address the view reports when asked; never null.  For a
     *  view's first navigation it may equal {@link #url()} or be empty. */
    public String currentUrl() { return currentUrl; }

    /** @return what started the navigation, as the engine reports it; never
     *  null. */
    public NavigationCause cause() { return cause; }

    /** @return whether the application asked for this navigation itself,
     *  through {@code setUrl} — or it is a server redirect of one it asked
     *  for and allowed.  A navigation the page starts is never marked. */
    public boolean applicationInitiated() { return applicationInitiated; }

    @Override
    public String toString() {
        return "WebViewNavigationEvent{url=" + url + ", currentUrl="
            + currentUrl + ", cause=" + cause + ", applicationInitiated="
            + applicationInitiated + "}";
    }
}
