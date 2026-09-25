/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Internal JNI bridge interface invoked by the native engine when a
 * print-to-PDF request finishes.
 *
 * <p><strong>Not part of the public application API.</strong>
 * Application code prints through
 * {@link ca.weblite.webview.swing.WebViewComponent#printToPdf(java.io.File, PdfOptions)},
 * which returns a future; this interface is the underlying bridge.  It is
 * {@code public} only because the JNI bridge in
 * {@code src_c/webview_embed.cpp} and {@code windows/webview_embed.cc} needs
 * to call it.
 *
 * <p>The native side calls {@link #onPdfFinished} exactly once per request,
 * on the engine UI thread (Canvas 29 D1).
 */
public interface WebViewPdfCallback {

    /**
     * @param ok    whether the PDF was written
     * @param error the reason it was not, when {@code ok} is false
     */
    void onPdfFinished(boolean ok, String error);
}
