/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when the
 * embedded page raises a UI dialog ({@code window.alert},
 * {@code window.confirm}, {@code window.prompt}, or
 * {@code <input type="file">}).  Each method returns the answer
 * synchronously; the native side waits for the return value before
 * releasing the page's JavaScript thread.
 *
 * <p><strong>Not part of the public application API.</strong>
 * Application code customises dialog behaviour by installing a
 * {@link WebViewDialogHandler} via
 * {@link ca.weblite.webview.swing.WebViewComponent#setDialogHandler};
 * this interface is the underlying bridge that the library wires from
 * the Swing component to the native engine via JNI.  It is
 * {@code public} only because the JNI bridge in
 * {@code src_c/webview_embed.cpp} and {@code windows/webview_embed.cc}
 * needs to call methods on instances of it, and Java has no
 * cross-package-but-non-public access modifier.  The same constraint
 * applies to {@link WebViewClickCallback} and
 * {@link WebViewFocusCallback}.
 *
 * <p><strong>Threading.</strong>  The native engine invokes these
 * methods from whatever native UI thread the engine runs on — AppKit
 * main on macOS, the GTK main thread on Linux, the WebView2 worker
 * thread on Windows.  Implementations MUST marshal to the Swing EDT
 * before touching any Swing state.  The library-provided
 * implementation in {@code WebViewHeavyweightComponent} and
 * {@code WebViewLightweightComponent} delegates to
 * {@link DialogDispatcher}, which performs the EDT hop via
 * {@link javax.swing.SwingUtilities#invokeAndWait}.
 */
public interface WebViewDialogCallback {

    /** Invoked for {@code window.alert(message)}. */
    void onAlert(String message, String pageUrl, String frameUrl);

    /** Invoked for {@code window.confirm(message)}; return {@code true}
     *  for OK, {@code false} for Cancel. */
    boolean onConfirm(String message, String pageUrl, String frameUrl);

    /** Invoked for {@code window.prompt(message, defaultValue)}; return
     *  the entered text on OK, or {@code null} on Cancel (the page
     *  sees {@code null} as the {@code prompt} return value). */
    String onPrompt(String message, String defaultValue,
                    String pageUrl, String frameUrl);

    /**
     * Invoked when the user clicks an {@code <input type="file">}
     * element.  The {@code mimeTypes} and {@code extensions} arrays
     * may be {@code null} when the page imposed no constraint.
     * Return a non-null array of absolute file paths; an empty array
     * means the user cancelled (the page receives an empty
     * {@code FileList}).
     */
    String[] onFilePicker(boolean multiple, String[] mimeTypes,
                          String[] extensions, String pageUrl,
                          String frameUrl);
}
