/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when the page
 * calls {@code window.print()} (Canvas 38 D6).  Fire-and-forget: the engine
 * has already answered the page.
 *
 * <p><strong>Not part of the public application API.</strong> Applications use
 * {@link WebViewPrintHandler}.
 */
public interface WebViewPrintRequestCallback {

    /**
     * @param url the main frame's address, or empty when the engine reports none
     */
    void onPrintRequested(String url);
}
