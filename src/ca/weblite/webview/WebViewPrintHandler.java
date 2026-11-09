/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Application hook deciding a page's own print requests — its
 * {@code window.print()} (Canvas 38 D6).  Install one with
 * {@link WebViewComponent#setPrintHandler}.
 *
 * <h2>No handler</h2>
 * Without a handler every page request opens the print dialog, exactly as
 * {@link WebViewComponent#print()} does.
 *
 * <h2>Threading</h2>
 * {@link #allowPrint} runs on the <strong>Swing EDT</strong>.  A handler that
 * throws refuses the request.
 *
 * <h2>Windows</h2>
 * WebView2 reports no event for {@code window.print()}.  While a handler is
 * installed, the component replaces {@code window.print} in each new document
 * with a call into the application, so the decision applies from the next
 * page loaded after the handler is installed, and the page's
 * {@code window.print()} returns before the dialog closes.
 */
public interface WebViewPrintHandler {

    /**
     * @param component the component whose page asked to print
     * @param url       the page's address, or empty
     * @return {@code true} to open the print dialog, {@code false} to do nothing
     */
    boolean allowPrint(WebViewComponent component, String url);
}
