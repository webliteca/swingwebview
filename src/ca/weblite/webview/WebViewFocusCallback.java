/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Callback invoked when the native WebView's first-responder / focus state
 * changes.  Registered on {@link EmbeddedWebView#setFocusCallback}.
 *
 * <p>The callback is invoked from a native thread (AppKit main thread on
 * macOS); implementations MUST marshal to the EDT themselves before
 * touching any Swing state.
 */
@FunctionalInterface
public interface WebViewFocusCallback {

    /**
     * @param became {@code true} when the native WebView gained first-responder
     *               status; {@code false} when it resigned.
     */
    void invoke(boolean became);
}
