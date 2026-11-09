/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when a print
 * dialog closes (Canvas 38 D1).
 *
 * <p><strong>Not part of the public application API.</strong> Applications use
 * {@link ca.weblite.webview.swing.WebViewComponent#print()}.
 */
public interface WebViewPrintCallback {

    /**
     * Called exactly once per request, on whatever thread the engine answers on.
     *
     * @param outcome a {@link PrintOutcome} ordinal, or {@code -1} on failure
     * @param error   the reason on failure, else {@code null}
     */
    void onPrintFinished(int outcome, String error);
}
