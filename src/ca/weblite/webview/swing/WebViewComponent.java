/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.WebView;

import javax.swing.JComponent;

/**
 * Base class for Swing components that host a native WebView.
 *
 * <p>Two concrete implementations are provided:
 * <ul>
 *   <li>{@link WebViewHeavyweightComponent} -- the native WebView is embedded
 *       as a child of a heavyweight AWT peer and renders directly over the
 *       Swing hierarchy.  Highest fidelity and best performance, but
 *       interacts with the Z-order in the usual heavyweight-vs-lightweight
 *       manner (it will paint over any Swing components occupying the same
 *       region).</li>
 *   <li>{@link WebViewLightweightComponent} -- the native WebView is rendered
 *       into an off-screen buffer and the pixels are drawn into the Swing
 *       component using {@code Graphics2D}.  Composites cleanly with the
 *       rest of the Swing hierarchy at the cost of additional copy overhead
 *       and the need to forward Swing input events back into the native
 *       engine.</li>
 * </ul>
 *
 * <p>Configuration methods (URL, init scripts, bindings) may be called at any
 * time; pending configuration is applied as soon as the component becomes
 * displayable.  After that, the same methods take effect immediately on the
 * underlying native WebView.
 */
public abstract class WebViewComponent extends JComponent {

    /** @return true if this component embeds the WebView as a heavyweight peer. */
    public boolean isHeavyweight() {
        return false;
    }

    /** Navigate the embedded WebView to the given URL.  May be called before display. */
    public abstract WebViewComponent setUrl(String url);

    /** @return the current (or pending) URL. */
    public abstract String getUrl();

    /** Toggle developer tools (where supported).  Must be called before display. */
    public abstract WebViewComponent setDebug(boolean debug);

    /**
     * Add javascript to be evaluated at the start of every new document.
     * May be called before display; cached entries are replayed on attach.
     */
    public abstract WebViewComponent addOnBeforeLoad(String js);

    /**
     * Evaluate javascript on the currently loaded document.  No-op until
     * the component is displayable.
     */
    public abstract WebViewComponent eval(String js);

    /**
     * Bind a Java callback that will appear as a global javascript function
     * {@code window.<name>(arg)}.  May be called before display; cached
     * bindings are replayed on attach.
     */
    public abstract WebViewComponent addJavascriptCallback(String name,
                                                           WebView.JavascriptCallback cb);

    /**
     * Release the native resources held by this component.  After calling
     * this method the component should not be used.  This is also called
     * automatically when the component is removed from its parent and its
     * peer is destroyed.
     */
    public abstract void dispose();
}
