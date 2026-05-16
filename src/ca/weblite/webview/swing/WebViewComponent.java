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
 * <p>Most callers don't need to think about heavyweight vs lightweight --
 * use the {@link #create()} factory and the right mode for the current
 * platform is picked for you (heavyweight on macOS / Windows, lightweight
 * on Linux).  Override with the {@code ca.weblite.webview.mode} system
 * property if you need a specific mode.
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

    /** Implementation mode for {@link #create(Mode)}. */
    public enum Mode {
        /** Native WebView embedded as a heavyweight AWT peer.  Highest
         *  fidelity on macOS and Windows.  On Linux, mouse and rendering
         *  work, but visible text-input feedback is unreliable. */
        HEAVYWEIGHT,
        /** Native WebView rendered offscreen and blitted into a regular
         *  Swing component.  Composites cleanly with other Swing widgets.
         *  Currently fully implemented on Linux only; on macOS and
         *  Windows this falls back silently to an empty component. */
        LIGHTWEIGHT
    }

    /** System property to force a specific mode regardless of platform. */
    public static final String MODE_PROPERTY = "ca.weblite.webview.mode";

    /**
     * Create a WebView component using the best mode for the current
     * platform.  The default is:
     * <ul>
     *   <li>macOS, Windows: {@link Mode#HEAVYWEIGHT}</li>
     *   <li>Linux:          {@link Mode#LIGHTWEIGHT}</li>
     * </ul>
     * Override by setting the {@code ca.weblite.webview.mode} system
     * property to {@code "heavyweight"} or {@code "lightweight"} (case
     * insensitive).
     */
    public static WebViewComponent create() {
        return create(resolveDefaultMode());
    }

    /** Create a WebView component using the requested implementation mode. */
    public static WebViewComponent create(Mode mode) {
        switch (mode) {
            case HEAVYWEIGHT: return new WebViewHeavyweightComponent();
            case LIGHTWEIGHT: return new WebViewLightweightComponent();
            default:
                throw new IllegalArgumentException(
                    "Unknown WebViewComponent.Mode: " + mode);
        }
    }

    /**
     * @return the mode that {@link #create()} will use right now.  Honors
     *         the {@code ca.weblite.webview.mode} system property if set.
     */
    public static Mode resolveDefaultMode() {
        String override = System.getProperty(MODE_PROPERTY, "")
            .trim().toLowerCase();
        if (!override.isEmpty()) {
            if (override.equals("heavyweight") || override.equals("heavy")) {
                return Mode.HEAVYWEIGHT;
            }
            if (override.equals("lightweight") || override.equals("light")) {
                return Mode.LIGHTWEIGHT;
            }
            System.err.println(
                "[webview] Unrecognized " + MODE_PROPERTY + " value: \"" +
                override + "\" (expected 'heavyweight' or 'lightweight'). " +
                "Falling back to platform default.");
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        // Linux defaults to lightweight: the heavyweight path's visible
        // text-input feedback is unreliable on WebKitGTK reparented under
        // a foreign-toolkit X11 parent (see README for details).
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return Mode.LIGHTWEIGHT;
        }
        // macOS, Windows, everything else: heavyweight has full fidelity
        // and works end-to-end.
        return Mode.HEAVYWEIGHT;
    }

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
     * Dispatch a {@code Runnable} onto the native WebView's UI thread.  No-op
     * until the component is displayable -- transient work is not buffered.
     */
    public abstract WebViewComponent dispatch(Runnable r);

    /**
     * Release the native resources held by this component.  After calling
     * this method the component should not be used.  This is also called
     * automatically when the component is removed from its parent and its
     * peer is destroyed.
     */
    public abstract void dispose();
}
