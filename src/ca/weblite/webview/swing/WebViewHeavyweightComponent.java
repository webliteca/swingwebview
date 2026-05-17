/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.ConsoleDispatcher;
import ca.weblite.webview.EditingCommand;
import ca.weblite.webview.EmbeddedWebView;
import ca.weblite.webview.WebView;
import ca.weblite.webview.WebViewFocusCallback;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.text.JTextComponent;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.KeyEventDispatcher;
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

    /**
     * Cached value of {@link Toolkit#getMenuShortcutKeyMask()}.  The mask
     * never changes once the JVM is up, but reading it is a JNI call that
     * we would otherwise repeat for every key event globally.  Static-final
     * keeps the dispatcher hot-path branch-free.
     *
     * <p>The Ex variant ({@code getMenuShortcutKeyMaskEx}) is Java 10+ only
     * and this project targets Java 1.8; the legacy API returns the
     * old-style {@code InputEvent.CTRL_MASK} / {@code META_MASK} that pairs
     * with {@code KeyEvent.getModifiers()}.
     */
    @SuppressWarnings("deprecation")
    private static final int SHORTCUT_MASK =
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

    private final EmbeddedCanvas canvas;
    private EmbeddedWebView embedded;

    private String pendingUrl = "about:blank";
    private boolean debug;
    private final List<String> pendingInit = new ArrayList<String>();
    private final Map<String, WebView.JavascriptCallback> pendingBindings =
            new LinkedHashMap<String, WebView.JavascriptCallback>();
    private KeyEventDispatcher editingShortcutDispatcher;
    private PropertyChangeListener focusOwnerListener;
    private JTextComponent suppressedCaretOwner;
    private boolean originalCaretVisible;

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

    @Override
    public void addNotify() {
        super.addNotify();
        if (editingShortcutDispatcher == null) {
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
        if (focusOwnerListener == null) {
            focusOwnerListener = new PropertyChangeListener() {
                @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    handleFocusOwnerChange(evt.getNewValue());
                }
            };
            KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("focusOwner", focusOwnerListener);
        }
    }

    @Override
    public void removeNotify() {
        if (focusOwnerListener != null) {
            KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("focusOwner", focusOwnerListener);
            focusOwnerListener = null;
        }
        if (editingShortcutDispatcher != null) {
            KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(editingShortcutDispatcher);
            editingShortcutDispatcher = null;
        }
        super.removeNotify();
    }

    private void handleFocusOwnerChange(Object newOwner) {
        if (embedded == null) return;
        if (!(newOwner instanceof Component)) return;
        Component owner = (Component) newOwner;
        // Focus moved INTO the WebView -- no native release needed.
        if (owner == this || SwingUtilities.isDescendingFrom(owner, this)) {
            return;
        }
        // Focus moved to an unrelated top-level window -- not our problem.
        Window myWindow = SwingUtilities.getWindowAncestor(this);
        Window ownerWindow = SwingUtilities.getWindowAncestor(owner);
        if (myWindow == null || ownerWindow != myWindow) {
            return;
        }
        // AWT moved focus to a Swing component in our window that isn't
        // part of the WebView.  On Windows, Win32 keyboard focus may still
        // be on the WebView2 child HWND; force it back to the AWT parent
        // HWND so the new Swing focus owner actually receives keystrokes.
        // No-op on macOS / Linux.
        embedded.releaseNativeFocus();
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
        if (embedded == null) {
            return false;
        }
        if (!isShowing()) {
            return false;
        }
        Window myWindow = SwingUtilities.getWindowAncestor(this);
        if (myWindow == null || !myWindow.isFocused()) {
            return false;
        }
        // Default to deferring to Swing.  Only dispatch to the WebView
        // when we have positive evidence the user is interacting with
        // it: either AWT focus is inside the component (Linux
        // lightweight / sometimes Windows), or the native WebView is
        // first responder (macOS, where AWT focus stays on the previously
        // focused Swing component while WKWebView holds AppKit focus).
        Component focusOwner = KeyboardFocusManager
            .getCurrentKeyboardFocusManager()
            .getFocusOwner();
        boolean focusInWebView = focusOwner != null
            && (focusOwner == this
                || SwingUtilities.isDescendingFrom(focusOwner, this));
        boolean nativeFocusOnWebView = embedded.isNativeFirstResponder();
        if (!focusInWebView && !nativeFocusOnWebView) {
            return false;
        }
        if (Boolean.getBoolean("ca.weblite.webview.debugShortcut")) {
            System.err.println(
                "[webview-editing-shortcut] heavyweight dispatch cmd="
                + cmd + " focusOwner="
                + (focusOwner == null ? "null" : focusOwner.getClass().getName())
                + " focusInWebView=" + focusInWebView
                + " nativeFR=" + nativeFocusOnWebView);
        }
        embedded.executeEditingCommand(cmd);
        return true;
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
        // Install the native focus callback so we can mirror WKWebView's
        // first-responder state into Swing's visual focus indicators.
        // The lambda is anchored in EmbeddedWebView.heap via
        // setFocusCallback so the JVM doesn't collect it while the
        // native side holds a global ref.
        embedded.setFocusCallback(new WebViewFocusCallback() {
            @Override
            public void invoke(boolean became) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        handleNativeFocusChange(became);
                    }
                });
            }
        });
    }

    private void handleNativeFocusChange(boolean became) {
        if (became) {
            if (suppressedCaretOwner != null) {
                // Already suppressed a caret in a prior transition; the
                // native first-responder state can flip during page
                // navigation or transient widget focus inside the page,
                // and we don't want to overwrite the saved restore state.
                return;
            }
            Component owner = KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .getFocusOwner();
            if (owner instanceof JTextComponent) {
                JTextComponent jtc = (JTextComponent) owner;
                suppressedCaretOwner = jtc;
                if (jtc.getCaret() != null) {
                    originalCaretVisible = jtc.getCaret().isVisible();
                    jtc.getCaret().setVisible(false);
                }
            }
        } else {
            if (suppressedCaretOwner != null && suppressedCaretOwner.getCaret() != null) {
                suppressedCaretOwner.getCaret().setVisible(originalCaretVisible);
            }
            suppressedCaretOwner = null;
        }
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
