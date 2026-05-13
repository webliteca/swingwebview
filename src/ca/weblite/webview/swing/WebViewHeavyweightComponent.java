/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.EmbeddedWebView;
import ca.weblite.webview.WebView;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
        pendingBindings.put(name, cb);
        if (embedded != null) {
            embedded.addJavascriptCallback(name, cb);
        }
        return this;
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
        embedded.setBounds(0, 0, d.width, d.height);
    }

    private final class EmbeddedCanvas extends Canvas {

        private volatile boolean peerAttached = false;

        EmbeddedCanvas() {
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    sizeNative();
                }
            });
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
