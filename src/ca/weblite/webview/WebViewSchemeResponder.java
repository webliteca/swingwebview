package ca.weblite.webview;

/**
 * Answers one custom-scheme request (Canvas 30 D6/D7). Callable from any thread; only the first
 * call counts, and a call after the request was cancelled or timed out is ignored.
 */
public interface WebViewSchemeResponder {

    void respond(WebViewSchemeResponse response);
}
