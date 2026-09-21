/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Native completion callback for an asynchronous cookie query.
 *
 * <p>Applications normally use {@code WebViewComponent.getCookies(String)};
 * this interface is the JNI boundary shared by the platform backends.
 */
public interface WebViewCookieCallback {
    /**
     * @param cookieHeader cookies applicable to the requested URL, formatted
     *                     for an HTTP {@code Cookie} request header; never null
     * @param error null on success, otherwise a human-readable failure reason
     */
    void completed(String cookieHeader, String error);
}
