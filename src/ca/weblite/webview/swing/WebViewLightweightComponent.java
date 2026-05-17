/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.ConsoleDispatcher;
import ca.weblite.webview.EditingCommand;
import ca.weblite.webview.GdkInput;
import ca.weblite.webview.OffscreenWebView;
import ca.weblite.webview.WebView;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Swing component that renders an embedded WebView entirely on the Java
 * side: the native engine renders into an offscreen surface, this
 * component periodically copies the latest pixels and paints them via
 * {@code paintComponent}.  Unlike {@link WebViewHeavyweightComponent} it
 * doesn't put a native window into the Swing hierarchy, so it composites
 * cleanly with arbitrary Swing widgets (popups, Z-order, JLayer, etc.).
 *
 * <p><strong>Input.</strong> AWT mouse and keyboard events are
 * translated to GDK events and injected into WebKit, so clicks, drags,
 * scroll, typing, and the common edit keys (Backspace, Delete, arrows,
 * Home/End, modifiers) all work.  IME / CJK composition is currently
 * not available -- the WebKit input-method context is disabled because
 * all input arrives already-decoded from AWT.
 *
 * <p><strong>JS interaction.</strong> {@link #eval},
 * {@link #addOnBeforeLoad}, {@link #addJavascriptCallback}, and
 * {@link #dispatch} delegate to the offscreen engine.  Init scripts and
 * bindings registered before the component is displayable are buffered
 * and replayed when {@code addNotify} creates the engine.  The
 * {@code window.<name>(...)} contract for bound callbacks is identical
 * to {@link WebViewHeavyweightComponent}.
 *
 * <p><strong>Platform support.</strong> Linux only at the moment.  On
 * macOS and Windows the underlying native entry points are stubs that
 * return 0 from create, so this component will silently fail to attach
 * and show its empty Swing background; the JS-interaction methods
 * remain no-ops there alongside the rendering path.  Use
 * {@link WebViewHeavyweightComponent} on those platforms.
 */
public class WebViewLightweightComponent extends WebViewComponent {

    private static final int REPAINT_INTERVAL_MS = 33; // ~30fps

    /**
     * Cached value of {@link Toolkit#getMenuShortcutKeyMask()}.  The Ex
     * variant is Java 10+ and this project targets Java 1.8 in
     * {@code pom.xml}; the legacy API pairs with
     * {@code KeyEvent.getModifiers()}.
     */
    @SuppressWarnings("deprecation")
    private static final int SHORTCUT_MASK =
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

    private OffscreenWebView engine;
    private BufferedImage buffer;
    private int[] pixelArray;
    private Timer repaintTimer;

    private String pendingUrl = "about:blank";
    private boolean debug;
    private final List<String> pendingInit = new ArrayList<String>();
    private final Map<String, WebView.JavascriptCallback> pendingBindings =
            new LinkedHashMap<String, WebView.JavascriptCallback>();
    private KeyEventDispatcher editingShortcutDispatcher;

    public WebViewLightweightComponent() {
        setOpaque(true);
        setBackground(Color.WHITE);
        setFocusable(true);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeNative();
            }
        });
        installMouseListeners();
        installKeyListener();
    }

    private void installMouseListeners() {
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (engine == null) return;
                requestFocusInWindow();
                engine.mouseButton(true, e.getX(), e.getY(),
                    GdkInput.translateButton(e.getButton()),
                    GdkInput.translateModifiers(e.getModifiersEx()),
                    e.getClickCount());
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (engine == null) return;
                engine.mouseButton(false, e.getX(), e.getY(),
                    GdkInput.translateButton(e.getButton()),
                    GdkInput.translateModifiers(e.getModifiersEx()),
                    e.getClickCount());
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                if (engine == null) return;
                engine.mouseMotion(e.getX(), e.getY(),
                    GdkInput.translateModifiers(e.getModifiersEx()));
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (engine == null) return;
                engine.mouseMotion(e.getX(), e.getY(),
                    GdkInput.translateModifiers(e.getModifiersEx()));
            }
        });
        addMouseWheelListener(new MouseWheelListener() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (engine == null) return;
                // Java's wheel rotation is integer units (+ down / - up).
                // GTK's smooth scroll wants pixel-ish deltas; multiply by
                // a small step so a single notch scrolls a sensible
                // amount.  Hold Shift to scroll horizontally to match
                // GTK/web convention.
                double step = 40.0;
                double rot = e.getPreciseWheelRotation();
                double dx = 0, dy = 0;
                if (e.isShiftDown()) dx = rot * step;
                else                  dy = rot * step;
                engine.mouseScroll(e.getX(), e.getY(), dx, dy,
                    GdkInput.translateModifiers(e.getModifiersEx()));
            }
        });
    }

    private void installKeyListener() {
        // Don't traverse focus on Tab/Shift-Tab -- let WebKit see them
        // so the user can tab through form fields inside the page.
        setFocusTraversalKeysEnabled(false);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e)  { forward(true,  e); }
            @Override public void keyReleased(KeyEvent e) { forward(false, e); }

            private void forward(boolean press, KeyEvent e) {
                if (engine == null) return;
                int keyval = GdkInput.translateKeyCode(
                    e.getKeyCode(), e.getKeyChar());
                if (keyval == 0) return;
                engine.keyEvent(press, keyval,
                    GdkInput.translateModifiers(e.getModifiersEx()),
                    GdkInput.isModifierKey(e.getKeyCode()));
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
            // will fall back to the default Swing background.  Still
            // install the editing-shortcut dispatcher so its install
            // path is uniform across platforms; its engine-null
            // short-circuit makes it a no-op here.
            installEditingShortcutDispatcher();
            return;
        }
        // Install the console bridge BEFORE replaying user config so the
        // shim observes every user init script.  Goes directly through
        // `engine` rather than this component's addJavascriptCallback,
        // which would reject the reserved-prefix name.
        engine.addOnBeforeLoad(ConsoleDispatcher.SHIM_JS);
        engine.addJavascriptCallback("__webview_console__",
            new WebView.JavascriptCallback() {
                @Override
                public void run(String arg) {
                    consoleDispatcher.dispatch(arg);
                }
            });
        for (String js : pendingInit) {
            engine.addOnBeforeLoad(js);
        }
        for (Map.Entry<String, WebView.JavascriptCallback> ent : pendingBindings.entrySet()) {
            engine.addJavascriptCallback(ent.getKey(), ent.getValue());
        }
        allocateBuffer(w, h);
        engine.navigate(pendingUrl);
        repaintTimer = new Timer(REPAINT_INTERVAL_MS, e -> repaint());
        repaintTimer.setRepeats(true);
        repaintTimer.start();
        installEditingShortcutDispatcher();
    }

    @Override
    public void removeNotify() {
        if (editingShortcutDispatcher != null) {
            KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(editingShortcutDispatcher);
            editingShortcutDispatcher = null;
        }
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

    private void installEditingShortcutDispatcher() {
        if (editingShortcutDispatcher != null) return;
        editingShortcutDispatcher = new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                return handleEditingShortcut(e);
            }
        };
        KeyboardFocusManager
            .getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(editingShortcutDispatcher);
    }

    private boolean handleEditingShortcut(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        if ((e.getModifiers() & SHORTCUT_MASK) != SHORTCUT_MASK) {
            return false;
        }
        EditingCommand cmd;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_C: cmd = EditingCommand.COPY;       break;
            case KeyEvent.VK_V: cmd = EditingCommand.PASTE;      break;
            case KeyEvent.VK_X: cmd = EditingCommand.CUT;        break;
            case KeyEvent.VK_A: cmd = EditingCommand.SELECT_ALL; break;
            default: return false;
        }
        if (engine == null) {
            return false;
        }
        if (!isShowing()) {
            return false;
        }
        java.awt.Window myWindow = SwingUtilities.getWindowAncestor(this);
        if (myWindow == null || !myWindow.isFocused()) {
            return false;
        }
        Component focusOwner = KeyboardFocusManager
            .getCurrentKeyboardFocusManager()
            .getFocusOwner();
        if (focusOwner instanceof javax.swing.text.JTextComponent) {
            return false;
        }
        if (Boolean.getBoolean("ca.weblite.webview.debugShortcut")) {
            System.err.println(
                "[webview-editing-shortcut] lightweight dispatch cmd="
                + cmd + " focusOwner="
                + (focusOwner == null ? "null" : focusOwner.getClass().getName()));
        }
        engine.executeEditingCommand(cmd);
        return true;
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
        pendingInit.add(js);
        if (engine != null) {
            engine.addOnBeforeLoad(js);
        }
        return this;
    }

    @Override
    public WebViewComponent eval(String js) {
        if (engine != null) {
            engine.eval(js);
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
        if (engine != null) {
            engine.addJavascriptCallback(name, cb);
        }
        return this;
    }

    @Override
    public WebViewComponent dispatch(Runnable r) {
        if (engine != null) {
            engine.dispatch(r);
        }
        return this;
    }

    @Override
    public boolean openDevTools() {
        if (engine == null) return false;
        return engine.openDevTools();
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
