/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Native-facing callback for navigation decisions (Canvas 34 D5).  The
 * Swing components install an adapter onto their native peer at attach
 * time that forwards into {@link NavigationDispatcher}.
 *
 * <p>Invoked synchronously on the engine's UI thread, which is blocked
 * awaiting the answer; it MUST NOT be marshalled to the EDT.
 */
public interface WebViewNavigationCallback {

    /**
     * @param url        the address the navigation would load
     * @param currentUrl the address the view reports, or empty
     * @param cause      a {@link NavigationCause} ordinal
     * @return {@code true} to let the navigation proceed, {@code false} to
     *         refuse it
     */
    boolean onNavigationRequested(String url, String currentUrl, int cause);
}
