/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when the
 * password manager's injected script reports a login submission or
 * requests autofill for the current page.
 *
 * <p><strong>Not part of the public application API.</strong>
 * Application code drives the password manager via
 * {@link ca.weblite.webview.swing.WebViewComponent} (enable/disable,
 * store, save-handler, get/save/delete).  This interface is
 * {@code public} only because the JNI bridge in
 * {@code src_c/webview_embed.cpp} and {@code windows/webview_embed.cc}
 * calls methods on instances of it, and Java has no
 * cross-package-but-non-public access modifier — the same constraint as
 * {@link WebViewDialogCallback}.
 *
 * <p><strong>Trusted origin.</strong>  {@code frameUrl} is the committed
 * URL of the frame that raised the message, read natively from the
 * engine — it is the only trusted origin source and is never a value
 * supplied by page JavaScript.  The username / password are base64url
 * strings; the library decodes them in Java (native stays free of
 * base64 code).
 *
 * <p><strong>Threading.</strong>  Invoked from the native message thread
 * (AppKit main on macOS, the GTK main thread on Linux, the WebView2
 * worker thread on Windows).  The library-provided implementation
 * delegates to {@link PasswordDispatcher}, which marshals the save
 * prompt to the EDT and runs store I/O on a worker — it never blocks the
 * native thread.
 */
public interface WebViewPasswordCallback {

    /**
     * A login form was submitted.
     *
     * @param frameUrl    the committed frame URL (trusted origin source)
     * @param b64Username base64url-encoded username (may decode to empty)
     * @param b64Password base64url-encoded password
     */
    void onLoginSubmitted(String frameUrl, String b64Username,
                          String b64Password);

    /**
     * The page is ready with a login form and is requesting autofill.
     *
     * @param frameUrl the committed frame URL (trusted origin source)
     */
    void onFillRequested(String frameUrl);
}
