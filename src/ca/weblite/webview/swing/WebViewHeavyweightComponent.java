/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.ConsoleDispatcher;
import ca.weblite.webview.EmbeddedWebView;
import ca.weblite.webview.WebView;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Swing component that embeds a native WebView as a heavyweight child of a
 * Swing container.
 *
 * <p>Internally this component is a {@code JComponent} whose layout contains
 * a single {@link Canvas} (heavyweight) to which the native web view is
 * parented via JAWT.  Because the embedded view is a heavyweight native
 * window/view, it will paint above any lightweight Swing components that
 * happen to overlap the same screen region.  This is the appropriate trade
 * to make when fidelity and input handling are more important than mixing
 * cleanly with arbitrary Swing layering -- if the latter is required, use
 * {@link WebViewLightweightComponent} instead.
 *
 * <p>The native peer is created lazily on {@code addNotify()} (when the
 * underlying Canvas first becomes displayable) and torn down on
 * {@code removeNotify()}.  Configuration applied before that point (URL,
 * init scripts, bindings, debug flag) is replayed at attach time.
 */
public class WebViewHeavyweightComponent extends WebViewComponent {

    private final EmbeddedCanvas canvas;
    private EmbeddedWebView embedded;

    private String pendingUrl = "about:blank";
    private boolean debug;
    private final List<String> pendingInit = new ArrayList<String>();
    private final Map<String, WebView.JavascriptCallback> pendingBindings =
            new LinkedHashMap<String, WebView.JavascriptCallback>();

    public WebViewHeavyweightComponent() {
        setLayout(new BorderLayout());
        canvas = new EmbeddedCanvas();
        // Avoid Swing trying to paint over the canvas region:
        canvas.setBackground(java.awt.Color.WHITE);
        add(canvas, BorderLayout.CENTER);
    }

    @Override
    public boolean isHeavyweight() {
        return true;
    }

    @Override
    public WebViewComponent setUrl(String url) {
        pendingUrl = url;
        if (embedded != null) {
            embedded.navigate(url);
        }
        return this;
    }

    @Override
    public String getUrl() {
        return pendingUrl;
    }

    @Override
    public WebViewComponent setDebug(boolean debug) {
        if (embedded != null) {
            throw new IllegalStateException(
                "setDebug must be called before the component is displayed.");
        }
        this.debug = debug;
        return this;
    }

    @Override
    public WebViewComponent addOnBeforeLoad(String js) {
        pendingInit.add(js);
        if (embedded != null) {
            embedded.addOnBeforeLoad(js);
        }
        return this;
    }

    @Override
    public WebViewComponent eval(String js) {
        if (embedded != null) {
            embedded.eval(js);
        }
        return this;
    }

    @Override
    public WebViewComponent addJavascriptCallback(String name,
                                                  WebView.JavascriptCallback cb) {
        if (name != null && name.startsWith(RESERVED_BINDING_PREFIX)) {
            throw new IllegalArgumentException(
                "name is reserved for internal use: names starting with \""
                + RESERVED_BINDING_PREFIX + "\" are not allowed (got \""
                + name + "\")");
        }
        pendingBindings.put(name, cb);
        if (embedded != null) {
            embedded.addJavascriptCallback(name, cb);
        }
        return this;
    }

    @Override
    public WebViewComponent dispatch(Runnable r) {
        if (embedded != null) {
            embedded.dispatch(r);
        }
        return this;
    }

    @Override
    public boolean openDevTools() {
        if (embedded == null) return false;
        return embedded.openDevTools();
    }

    @Override
    public void dispose() {
        if (embedded != null) {
            EmbeddedWebView e = embedded;
            embedded = null;
            e.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (d == null || (d.width <= 0 && d.height <= 0)) {
            return new Dimension(800, 600);
        }
        return d;
    }

    private void createPeer() {
        if (embedded != null || !canvas.isDisplayable()) {
            return;
        }
        try {
            embedded = EmbeddedWebView.attach(canvas, debug);
        } catch (RuntimeException ex) {
            embedded = null;
            throw ex;
        }
        // Install the console bridge BEFORE replaying user config so the
        // shim observes every subsequent init script's console output,
        // including any user init script that itself calls console.* at
        // document-start.  Goes directly through `embedded` rather than
        // through this component's addJavascriptCallback, which would
        // reject the reserved-prefix name.
        embedded.addOnBeforeLoad(ConsoleDispatcher.SHIM_JS);
        embedded.addJavascriptCallback("__webview_console__",
            new WebView.JavascriptCallback() {
                @Override
                public void run(String arg) {
                    consoleDispatcher.dispatch(arg);
                }
            });
        for (String js : pendingInit) {
            embedded.addOnBeforeLoad(js);
        }
        for (Map.Entry<String, WebView.JavascriptCallback> e : pendingBindings.entrySet()) {
            embedded.addJavascriptCallback(e.getKey(), e.getValue());
        }
        embedded.navigate(pendingUrl);
        sizeNative();
    }

    private void sizeNative() {
        if (embedded == null) {
            return;
        }
        Dimension d = canvas.getSize();
        if (d.width <= 0 || d.height <= 0) {
            return;
        }
        // Send the canvas's position in the AWT Window's content-pane
        // coordinates (top-left origin).  The native side translates into
        // its host NSView's coordinate space; when the host is
        // NSWindow.contentView this is what positions the WKWebView so it
        // overlays only the canvas region, not the entire window.
        int x = 0, y = 0;
        Window window = SwingUtilities.getWindowAncestor(canvas);
        if (window != null) {
            Point inWindow = SwingUtilities.convertPoint(canvas, 0, 0, window);
            Insets insets = window.getInsets();
            x = inWindow.x - insets.left;
            y = inWindow.y - insets.top;
        }
        embedded.setBounds(x, y, d.width, d.height);
    }

    private final class EmbeddedCanvas extends Canvas {

        private volatile boolean peerAttached = false;

        EmbeddedCanvas() {
            ComponentAdapter ca = new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    sizeNative();
                }
                @Override
                public void componentMoved(ComponentEvent e) {
                    sizeNative();
                }
            };
            addComponentListener(ca);

            // Track visibility transitions so the native WebView hides when
            // its tab in a JTabbedPane is deselected, when an ancestor is
            // hidden, etc.  The native view is parented to NSWindow's
            // contentView (not into Swing's layout) so without this it
            // would happily paint over whatever Swing tab is active.
            addHierarchyListener(new HierarchyListener() {
                @Override
                public void hierarchyChanged(HierarchyEvent e) {
                    if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                        return;
                    }
                    if (embedded == null) return;
                    boolean showing = isShowing();
                    embedded.setVisible(showing);
                    if (showing) {
                        sizeNative();
                    }
                }
            });

            // Note: there used to be AWT mouse/focus listeners here that
            // forwarded clicks/focus-gained to embedded.requestFocus().
            // They are removed because on Linux they appeared to cause AWT
            // to alter the canvas's X11 event-mask setup in a way that
            // broke rendering of the embedded popup.  Click-to-focus is
            // now handled entirely native-side (a button-press-event hook
            // on the WebKitWebView calls XSetInputFocus on the popup).
            // For programmatic focus from Java code, call
            // EmbeddedWebView.requestFocus() directly.
        }

        @Override
        public void removeNotify() {
            peerAttached = false;
            dispose();
            super.removeNotify();
        }

        @Override
        public void paint(java.awt.Graphics g) {
            // The native peer creation has to happen *after* the underlying
            // platform window/view has been instantiated, which on macOS is
            // an async hop from EDT to the AppKit main thread inside Canvas.
            // addNotify().  Hooking into the first paint gives us a moment
            // when both the EDT view tree and the AppKit-side NSView are
            // guaranteed to exist, so the JAWT lock can succeed.
            if (!peerAttached) {
                peerAttached = true;
                createPeer();
            }
            // Otherwise no-op -- the native webview paints over this Canvas.
        }

        @Override
        public void update(java.awt.Graphics g) {
            paint(g);
        }
    }
}
