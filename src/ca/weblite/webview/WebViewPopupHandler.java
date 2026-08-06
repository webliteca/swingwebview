/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Application hook for browser-initiated popups — {@code window.open(url,
 * name, features)} and clicks on links / forms with {@code target="_blank"}.
 * Install one via
 * {@link ca.weblite.webview.swing.WebViewComponent#setPopupHandler}.
 *
 * <h2>Native-owned window model</h2>
 * When a popup is allowed, the <em>native engine</em> creates the child web
 * view — linked to the opener so {@code window.opener} is non-null and
 * {@code window.opener.postMessage(...)} works — and hosts it in a fresh
 * native top-level window that the engine sizes, shows, and destroys.  The
 * opener linkage is what makes OAuth "sign-in with popup" flows (which call
 * {@code window.opener.postMessage} then {@code window.close()}) succeed.
 * This handler only decides <em>policy</em> ({@link #popupRequested}) and
 * <em>observes</em> the popup lifecycle ({@link #popupOpened} /
 * {@link #popupClosed}); it does not open, host, or size the window.
 *
 * <h2>Threading</h2>
 * {@link #popupRequested} runs on the <strong>native UI thread</strong>,
 * <strong>synchronously</strong> and <strong>off the Swing EDT</strong>: the
 * platform popup callback must return the allow/deny decision before yielding
 * back to the browser engine and cannot round-trip to the EDT without risking
 * a deadlock.  Implementations MUST be fast, thread-safe, and MUST NOT touch
 * Swing state.  {@link #popupOpened} and {@link #popupClosed} are
 * asynchronous notifications delivered on the EDT.
 *
 * <h2>Blocking popups</h2>
 * The default handler {@linkplain #DEFAULT allows} every popup.  Passing
 * {@code null} to {@code setPopupHandler} installs an internal drop handler
 * whose {@link #popupRequested} returns {@code false}, blocking all popups
 * ({@code window.open} returns {@code null}) — the pre-feature behaviour,
 * available as an explicit opt-out.  Reset to the framework default by
 * passing {@link #DEFAULT} explicitly.
 */
public interface WebViewPopupHandler {

    /**
     * Decide whether to allow a popup.  Runs on the native UI thread,
     * synchronously, off the EDT — keep it fast and free of Swing access.
     *
     * @param event the popup request
     * @return {@code true} to open the popup in a native window,
     *         {@code false} to block it ({@code window.open} returns null)
     */
    default boolean popupRequested(WebViewPopupEvent event) {
        return true;
    }

    /** Notification that a popup window has been created and shown.  Runs
     *  on the Swing EDT.  Default is a no-op. */
    default void popupOpened(WebViewPopupEvent event) {
    }

    /** Notification that a popup window has closed (the popup page called
     *  {@code window.close()}, or the user closed the window).  Runs on the
     *  Swing EDT.  Default is a no-op. */
    default void popupClosed(WebViewPopupEvent event) {
    }

    /** The framework default: allows every popup, no-op notifications.
     *  Installed when no caller has set a handler.  Stateless; safe to
     *  share across components and threads. */
    WebViewPopupHandler DEFAULT = new WebViewPopupHandler() {
    };
}
