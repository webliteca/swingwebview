/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.OffscreenWebView;
import ca.weblite.webview.WebView;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import javax.swing.Timer;

/**
 * Swing component that renders an embedded WebView entirely on the Java
 * side: the native engine renders into an offscreen surface, this
 * component periodically copies the latest pixels and paints them via
 * {@code paintComponent}.  Unlike {@link WebViewHeavyweightComponent} it
 * doesn't put a native window into the Swing hierarchy, so it composites
 * cleanly with arbitrary Swing widgets (popups, Z-order, JLayer, etc.).
 *
 * <p><strong>Phase 1 status:</strong> this implementation currently does
 * <em>rendering only</em>.  Input forwarding (mouse / keyboard / IME)
 * isn't wired yet -- the WebView will display pages but you can't
 * interact with them.  For interaction use
 * {@link WebViewHeavyweightComponent} until Phase 2 lands.
 *
 * <p><strong>Platform support:</strong> currently Linux only.  On macOS
 * and Windows the underlying native entry points are stubs that return 0,
 * so this component will fail to attach and report it in the logs.
 */
public class WebViewLightweightComponent extends WebViewComponent {

    private static final int REPAINT_INTERVAL_MS = 33; // ~30fps

    private OffscreenWebView engine;
    private BufferedImage buffer;
    private int[] pixelArray;
    private Timer repaintTimer;

    private String pendingUrl = "about:blank";
    private boolean debug;

    public WebViewLightweightComponent() {
        setOpaque(true);
        setBackground(Color.WHITE);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeNative();
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (d == null || (d.width <= 0 && d.height <= 0)) {
            return new Dimension(800, 600);
        }
        return d;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (engine != null) return;
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        engine = OffscreenWebView.create(w, h, debug);
        if (engine == null) {
            // Unsupported platform or native failure -- leave the
            // engine null so subsequent ops are no-ops.  paintComponent
            // will fall back to the default Swing background.
            return;
        }
        allocateBuffer(w, h);
        engine.navigate(pendingUrl);
        repaintTimer = new Timer(REPAINT_INTERVAL_MS, e -> repaint());
        repaintTimer.setRepeats(true);
        repaintTimer.start();
    }

    @Override
    public void removeNotify() {
        if (repaintTimer != null) {
            repaintTimer.stop();
            repaintTimer = null;
        }
        if (engine != null) {
            OffscreenWebView ow = engine;
            engine = null;
            ow.dispose();
        }
        buffer = null;
        pixelArray = null;
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (engine == null || buffer == null || pixelArray == null) {
            super.paintComponent(g);
            return;
        }
        engine.snapshot(pixelArray, buffer.getWidth(), buffer.getHeight());
        g.drawImage(buffer, 0, 0, null);
    }

    private void allocateBuffer(int w, int h) {
        buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        pixelArray =
            ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
    }

    private void resizeNative() {
        if (engine == null) return;
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            allocateBuffer(w, h);
        }
        engine.setSize(w, h);
    }

    // ----- WebViewComponent API ------------------------------------------

    @Override
    public WebViewComponent setUrl(String url) {
        pendingUrl = url;
        if (engine != null) {
            engine.navigate(url);
        }
        return this;
    }

    @Override
    public String getUrl() {
        return pendingUrl;
    }

    @Override
    public WebViewComponent setDebug(boolean debug) {
        if (engine != null) {
            throw new IllegalStateException(
                "setDebug must be called before the component is displayed.");
        }
        this.debug = debug;
        return this;
    }

    @Override
    public WebViewComponent addOnBeforeLoad(String js) {
        // Phase 1: not yet wired through the offscreen path.
        return this;
    }

    @Override
    public WebViewComponent eval(String js) {
        // Phase 1: not yet wired through the offscreen path.
        return this;
    }

    @Override
    public WebViewComponent addJavascriptCallback(String name,
                                                  WebView.JavascriptCallback cb) {
        // Phase 1: not yet wired through the offscreen path.
        return this;
    }

    @Override
    public void dispose() {
        if (repaintTimer != null) {
            repaintTimer.stop();
            repaintTimer = null;
        }
        if (engine != null) {
            OffscreenWebView ow = engine;
            engine = null;
            ow.dispose();
        }
    }
}
