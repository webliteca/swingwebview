/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * How the library should handle a browser-initiated popup — the enriched
 * outcome of {@link WebViewPopupHandler#popupDisposition}.
 *
 * <p>Extends the Canvas 15 allow/deny model (a plain {@code boolean}) with a
 * third option, {@link #ADOPT}, that lets the application host the popup's
 * engine-created child web view inside a {@link
 * ca.weblite.webview.swing.WebViewComponent} it supplies (a tab) instead of
 * the native top-level window the engine owns by default.
 *
 * <p><strong>Ordinal wire contract.</strong> The ordinals of these constants
 * ({@code BLOCK = 0}, {@code NATIVE_WINDOW = 1}, {@code ADOPT = 2}) are shared
 * with the native engine through {@link WebViewPopupCallback#onPopupDisposition}
 * and MUST NOT be reordered — the native disposition switch keys off these
 * integer values.
 */
public enum PopupDisposition {

    /** Block the popup: {@code window.open} returns {@code null} and no child
     *  web view is created.  Equivalent to the Canvas 15 {@code false}
     *  allow/deny decision. */
    BLOCK,

    /** Allow the popup and host it in an engine-owned native top-level window
     *  (the Canvas 15 behaviour).  Equivalent to the Canvas 15 {@code true}
     *  allow/deny decision. */
    NATIVE_WINDOW,

    /** Allow the popup but do not open a native window: the engine creates the
     *  opener-linked child web view (preserving the in-flight request — POST
     *  verb and body — and {@code window.opener}), retains it under a hidden
     *  holder keyed by an engine-assigned {@code popupId}, and notifies the
     *  application via {@link WebViewPopupHandler#popupAdoptable} so it can
     *  host the child in a {@link
     *  ca.weblite.webview.swing.WebViewComponent#adoptPopup(long)}.  A child
     *  that is never adopted is reclaimed (see {@link PopupDispatcher}). */
    ADOPT
}
