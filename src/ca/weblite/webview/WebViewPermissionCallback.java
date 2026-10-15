/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Native-facing callback for media-capture permission requests (Canvas 35
 * D5).  The Swing components install an adapter onto their native peer at
 * attach time that forwards into {@link PermissionDispatcher}.
 *
 * <p>Invoked synchronously on the engine's UI thread; it MUST return at once
 * and MUST NOT be marshalled to the EDT.  A later answer goes back through
 * {@code WebViewNative.webview_permission_respond(id, decision)}.
 */
public interface WebViewPermissionCallback {

    /**
     * @param id     the native request id, used to answer later
     * @param origin the requesting origin as the engine reports it, or empty
     * @param url    the address the engine reports, or empty
     * @param kinds  {@link MediaCaptureKind} bits
     * @return {@link PermissionDispatcher#PENDING} when the answer will come
     *         later, else a {@link PermissionDecision} ordinal to apply now
     */
    int onPermissionRequested(long id, String origin, String url, int kinds);
}
