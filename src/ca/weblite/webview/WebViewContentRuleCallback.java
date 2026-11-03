/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Native-facing per-request decision for content rule lists on Windows
 * (Canvas 37 D9), where WebView2 has no rule compiler of its own and asks
 * the host about every request instead.  The Swing component installs an
 * adapter that answers from its applied lists.
 *
 * <p>Invoked synchronously on the WebView2 UI thread, which is blocked
 * awaiting the answer; it MUST be fast, MUST NOT be marshalled to the EDT
 * and MUST NOT throw.
 */
public interface WebViewContentRuleCallback {

    /** Answer: let the request proceed unchanged. */
    int ALLOW = 0;
    /** Answer: refuse the request before it is sent. */
    int BLOCK = 1;
    /** Answer: send the request without its {@code Cookie} header. */
    int BLOCK_COOKIES = 2;

    /**
     * @param url          the address requested
     * @param pageUrl      the address of the page the request belongs to
     * @param resourceType the request kind: 0 document, 1 image, 2 style sheet,
     *                     3 script, 4 font, 5 media, 6 fetch, 7 web socket,
     *                     8 ping, 9 other (a wire contract)
     * @return {@link #ALLOW}, {@link #BLOCK} or {@link #BLOCK_COOKIES}
     */
    int onResourceRequested(String url, String pageUrl, int resourceType);
}
