/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.ConsoleDispatcher;
import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.WebView;

import java.io.PrintStream;
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

    /** Prefix on JS binding names reserved for this library's internal
     *  channels.  Callers passing a name starting with this prefix to
     *  {@link #addJavascriptCallback(String, WebView.JavascriptCallback)}
     *  must be rejected with {@code IllegalArgumentException}. */
    public static final String RESERVED_BINDING_PREFIX = "__webview_";

    /** Per-component console fan-out hub.  Owned by every instance for the
     *  lifetime of the component; survives the native peer's create/destroy
     *  cycle so listeners registered before display still receive messages
     *  once the engine attaches and the JS shim starts feeding them. */
    protected final ConsoleDispatcher consoleDispatcher = new ConsoleDispatcher();

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

    // ---------------------------------------------------------------------
    // DevTools + console-capture API.
    //
    // openDevTools() defaults to returning false; subclasses override to
    // delegate to their native peer's open-devtools call.  The four
    // console-listener methods delegate to the per-component
    // ConsoleDispatcher and therefore work uniformly across all subclasses
    // without each one having to re-implement them.
    // ---------------------------------------------------------------------

    /**
     * Open the platform's native DevTools / Web Inspector in a separate OS
     * window.
     *
     * <p>Default implementation returns {@code false}.  First-party
     * subclasses override to delegate to their native peer:
     * <ul>
     *   <li>On Linux (heavyweight or lightweight), opens the WebKitGTK Web
     *       Inspector and returns {@code true}.</li>
     *   <li>On Windows (heavyweight), opens the Chromium DevTools window
     *       via {@code ICoreWebView2::OpenDevToolsWindow} and returns
     *       {@code true}.</li>
     *   <li>On macOS (heavyweight), returns {@code false} — no public
     *       WKWebView API exists to programmatically pop the Web Inspector.
     *       When {@code setDebug(true)} was called before display, the
     *       inspector is still reachable via right-click &rarr; Inspect
     *       Element or via the Safari Develop menu (on macOS 13.3+).</li>
     * </ul>
     *
     * <p>Returns {@code false} when {@code setDebug(true)} was not called
     * before display, when the component has not yet been displayed, or
     * when the native call otherwise reports unsupported.
     *
     * <p>Safe to call from the EDT; the underlying native call is marshaled
     * to the appropriate native UI thread by the subclass implementation
     * and does not block the EDT beyond a normal native UI dispatch.
     *
     * <p>Idempotent — repeated calls while the inspector is already open
     * are safe; the platform either focuses the existing window (Linux,
     * Windows) or no-ops (macOS).
     */
    public boolean openDevTools() {
        return false;
    }

    /**
     * Register a listener to receive each {@code console.*} call captured
     * from the embedded page.  The listener is invoked on the Swing Event
     * Dispatch Thread so it may touch Swing state directly.
     *
     * <p>Listeners registered before the component is displayed are
     * remembered and start receiving messages as soon as the native peer
     * is created and the JS shim takes effect.
     *
     * <p>The same listener instance may be registered multiple times; it
     * will then receive each message multiple times.  Each
     * {@link #removeConsoleListener(ConsoleListener)} call removes one
     * occurrence.
     *
     * @throws NullPointerException if {@code listener} is {@code null}.
     */
    public void addConsoleListener(ConsoleListener listener) {
        consoleDispatcher.addListener(listener);
    }

    /**
     * Unregister a previously-registered listener.  Silently does nothing
     * if the listener was not registered.
     */
    public void removeConsoleListener(ConsoleListener listener) {
        consoleDispatcher.removeListener(listener);
    }

    /**
     * Redirect each captured console message, formatted via
     * {@link ca.weblite.webview.ConsoleMessage#toString()}, to the given
     * {@code PrintStream}.  Passing {@code null} clears the redirect.
     *
     * <p>The stream's existing character encoding is respected — the
     * dispatcher writes through {@code PrintStream.println(String)} and
     * does not impose a charset.  IO failures on the stream do not
     * propagate out of the dispatch path; {@code PrintStream} itself
     * swallows {@code IOException}.
     *
     * <p>Calling this method while a previous stream is set replaces it;
     * the previous stream is not flushed or closed.
     */
    public void setConsoleOutput(PrintStream stream) {
        consoleDispatcher.setOutputStream(stream);
    }

    /**
     * @return the {@code PrintStream} currently set via
     *         {@link #setConsoleOutput(PrintStream)}, or {@code null} if
     *         no stream is set.
     */
    public PrintStream getConsoleOutput() {
        return consoleDispatcher.getOutputStream();
    }
}
