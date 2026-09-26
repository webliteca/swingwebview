package ca.weblite.webview;

/**
 * Answers requests for a custom URL scheme registered with {@link WebViewSchemes#register}
 * (Canvas 30).
 *
 * <p>It runs on a library thread — never the UI thread — so it may do I/O. It may answer now or
 * later, from any thread, through {@code responder}; only the first answer counts. A request not
 * answered within 30 seconds is answered 504 for it, and a handler that throws is answered 500.
 */
public interface WebViewSchemeHandler {

    void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) throws Exception;
}
