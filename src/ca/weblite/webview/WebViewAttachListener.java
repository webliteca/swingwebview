/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction.
 */
package ca.weblite.webview;

/**
 * Observer for the deferred completion of
 * {@link EmbeddedWebView#attach(java.awt.Component, boolean)}.
 *
 * <p>On macOS the AppKit-side setup of the WKWebView runs asynchronously on
 * the AppKit main thread after the synchronous Java factory returns. This
 * listener fires on the Swing EDT once that setup resolves — with
 * {@link #onAttached} on success or {@link #onAttachFailed} on failure.
 *
 * <p>On Windows and Linux the engine is fully attached by the time the
 * Java factory returns, and any listener registered via
 * {@link EmbeddedWebView#addOnAttachComplete} fires on the next EDT tick
 * with {@link #onAttached}.
 *
 * <p>Listeners MAY be registered before or after attach resolves; a
 * registration on an already-resolved engine schedules the corresponding
 * callback via {@link javax.swing.SwingUtilities#invokeLater}. Callbacks
 * never fire inline on the calling thread; the implementation always uses
 * an EDT trip so listener bodies that themselves call back into
 * {@code EmbeddedWebView} (or register further listeners) cannot re-enter
 * the dispatch path with half-initialised state.
 *
 * <p>Implementations should catch their own exceptions if they do not want
 * AWT's default uncaught-exception handling to apply.
 */
public interface WebViewAttachListener {

    /**
     * Invoked on the Swing EDT once the native peer's attach completes
     * successfully.
     *
     * @param webView the {@link EmbeddedWebView} whose attach just resolved;
     *                never null.
     */
    void onAttached(EmbeddedWebView webView);

    /**
     * Invoked on the Swing EDT once the native peer's attach fails.
     *
     * <p>Failure surfaces only for AppKit-side errors that occur during
     * the asynchronous portion of macOS attach (WKWebView allocation
     * returned nil, no hostable NSView discovered). Synchronous failures
     * during the C++ engine allocation (out-of-memory, JAWT lock
     * failure) propagate as an {@link IllegalStateException} from
     * {@link EmbeddedWebView#attach}, never as a listener callback.
     *
     * @param webView the {@link EmbeddedWebView} whose attach just failed;
     *                never null. The engine is in {@link AttachState#FAILED}
     *                and is not usable; callers should discard it.
     * @param cause   the failure cause; carries an actionable message
     *                identifying the failure step. Never null.
     */
    void onAttachFailed(EmbeddedWebView webView, Throwable cause);
}
