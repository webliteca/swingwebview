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

    /**
     * Decide how to handle a popup — the enriched successor to {@link
     * #popupRequested}.  Return {@link PopupDisposition#BLOCK} to block it,
     * {@link PopupDisposition#NATIVE_WINDOW} to host it in an engine-owned
     * native window (the Canvas 15 allow behaviour), or {@link
     * PopupDisposition#ADOPT} to have the engine retain the opener-linked
     * child for the application to host in a {@link
     * ca.weblite.webview.swing.WebViewComponent} (a tab).
     *
     * <p>Runs on the <strong>native UI thread</strong>,
     * <strong>synchronously</strong> and <strong>off the EDT</strong> — the
     * same threading contract as {@link #popupRequested}: it must be fast,
     * thread-safe, and MUST NOT touch Swing state.
     *
     * <p><strong>Backward compatibility.</strong> The default implementation
     * derives the disposition from {@link #popupRequested}: {@code true} maps
     * to {@link PopupDisposition#NATIVE_WINDOW} and {@code false} to {@link
     * PopupDisposition#BLOCK}.  Existing handlers that override only {@code
     * popupRequested} therefore behave exactly as before.  Override this
     * method (returning {@link PopupDisposition#ADOPT}) to opt into tab
     * adoption; a handler that returns {@code ADOPT} should also override
     * {@link #popupAdoptable}.
     *
     * @param event the popup request
     * @return the disposition; never {@code null} (the dispatcher treats a
     *         {@code null} return as {@link PopupDisposition#BLOCK})
     */
    default PopupDisposition popupDisposition(WebViewPopupEvent event) {
        return popupRequested(event)
            ? PopupDisposition.NATIVE_WINDOW
            : PopupDisposition.BLOCK;
    }

    /**
     * Notification that a popup was decided {@link PopupDisposition#ADOPT}:
     * the engine has created and retained the opener-linked child, and it is
     * ready to be adopted into a {@link
     * ca.weblite.webview.swing.WebViewComponent}.  Runs on the <strong>EDT
     * </strong>.  Default is a no-op.
     *
     * <p>To host the popup, create a component with {@link
     * ca.weblite.webview.swing.WebViewComponent#adoptPopup(long)} passing the
     * given {@code popupId} and add it to a container (e.g. a new tab).  If no
     * component adopts the {@code popupId}, the retained child is reclaimed
     * (on opener disposal or after a bounded grace period).
     *
     * @param event   the popup request
     * @param popupId the engine-assigned handle to pass to {@code adoptPopup}
     */
    default void popupAdoptable(WebViewPopupEvent event, long popupId) {
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
