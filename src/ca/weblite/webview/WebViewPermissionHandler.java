/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Application hook deciding camera, microphone and screen-capture requests
 * from the page (Canvas 35 D1).  Install one via
 * {@link ca.weblite.webview.swing.WebViewComponent#setPermissionHandler}.
 *
 * <h2>Answering</h2>
 * The handler answers through the {@link WebViewPermissionRequest}:
 * {@link WebViewPermissionRequest#allow() allow()},
 * {@link WebViewPermissionRequest#deny() deny()} or
 * {@link WebViewPermissionRequest#useDefault() useDefault()} — at once, or
 * later from any thread (for example after asking the user).  Only the first
 * answer counts.  The page's {@code getUserMedia} / {@code getDisplayMedia}
 * promise stays pending until the answer arrives; a request still unanswered
 * when the component is disposed is denied.
 *
 * <h2>Threading</h2>
 * {@link #permissionRequested} runs on the <strong>Swing EDT</strong>,
 * delivered with {@code SwingUtilities.invokeLater}.  No engine thread waits
 * for it to return, so it may show a modal dialog.  It must not block the EDT
 * for long.
 *
 * <h2>Failure</h2>
 * A handler that throws denies the request (unless it had already answered);
 * the throwable goes to the default uncaught-exception handler.
 *
 * <h2>No handler</h2>
 * Without a handler every request goes to the engine as it would without
 * this feature: WebKitGTK refuses it, WebView2 shows its own prompt, and
 * WKWebView shows its own prompt.
 *
 * <h2>Scope</h2>
 * Only camera, microphone and screen-capture requests are asked about.
 * Screen capture reaches the handler on Linux (WebKitGTK 2.42+) only; on
 * Windows and macOS the engine's own picker decides it.  Geolocation,
 * notifications and other permissions stay with the engine.
 */
public interface WebViewPermissionHandler {

    /**
     * A page asked for media-capture devices.  Runs on the EDT; answer now
     * or later through {@code request}.
     *
     * @param request the request, carrying the origin, address and kinds
     */
    void permissionRequested(WebViewPermissionRequest request);
}
