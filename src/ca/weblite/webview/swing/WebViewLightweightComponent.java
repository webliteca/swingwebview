/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.WebView;

/**
 * Stub for the lightweight WebView component mode.
 *
 * <p>In lightweight mode the native engine renders into an off-screen pixel
 * buffer which is then blitted into a Swing {@code JComponent}.  Swing input
 * events are translated and forwarded back into the native engine.  This
 * implementation requires significant per-platform native plumbing
 * (Windows: WebView2 composition controller; macOS: WKWebView snapshotting
 * or IOSurface; Linux: WebKitGTK offscreen surface) and is not yet
 * available.  See {@link WebViewHeavyweightComponent} for the heavyweight
 * embedding which is supported today.
 */
public class WebViewLightweightComponent extends WebViewComponent {

    public WebViewLightweightComponent() {
        throw new UnsupportedOperationException(
            "Lightweight WebView component is not yet implemented. " +
            "Use WebViewHeavyweightComponent for now.");
    }

    @Override public WebViewComponent setUrl(String url) { return this; }
    @Override public String getUrl() { return null; }
    @Override public WebViewComponent setDebug(boolean debug) { return this; }
    @Override public WebViewComponent addOnBeforeLoad(String js) { return this; }
    @Override public WebViewComponent eval(String js) { return this; }
    @Override public WebViewComponent addJavascriptCallback(String name, WebView.JavascriptCallback cb) {
        return this;
    }
    @Override public void dispose() { }
}
