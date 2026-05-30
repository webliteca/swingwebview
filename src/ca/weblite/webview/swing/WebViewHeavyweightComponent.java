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
import ca.weblite.webview.WebViewClickCallback;
import ca.weblite.webview.WebViewDialogCallback;
import ca.weblite.webview.WebViewDownloadCallback;
import ca.weblite.webview.WebViewFocusCallback;
import ca.weblite.webview.WebViewMouseDispatcher;
import java.awt.AWTEvent;
import java.awt.event.AWTEventListener;
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
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    private AWTEventListener globalMouseListener;
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
    public CompletableFuture<String> evalAsync(String js) {
        if (js == null) throw new NullPointerException("js");
        EmbeddedWebView e = embedded;
        if (e == null) {
            CompletableFuture<String> f = new CompletableFuture<String>();
            f.completeExceptionally(
                new IllegalStateException("WebViewComponent not displayed"));
            return f;
        }
        return e.evalAsync(js);
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
        // Flip the dispatcher's disposed flag BEFORE tearing down the
        // native peer so any in-flight dialog event arriving from the
        // native side mid-teardown returns the safe fallback (void /
        // false / null / empty) without invoking the handler against
        // a half-disposed component.
        dialogDispatcher.disposeAll();
        // Same disposal-precedes-native-teardown reasoning as
        // dialogDispatcher: any in-flight download event arriving
        // from the native side mid-teardown returns the safe
        // fallback (null = cancel) without invoking the handler
        // against a half-disposed component.
        downloadDispatcher.disposeAll();
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
        // Belt-and-suspenders mouse listener: PropertyChangeListener on
        // focusOwner may not fire on Windows when Win32 keyboard focus is
        // on a foreign HWND (WebView2's child HWND) -- AWT's internal
        // focus tracking can desync.  An AWTEventListener for mouse
        // events fires for every mouse press in the JVM regardless of
        // focus state, so it can reliably trigger the native focus
        // release when the user clicks outside the WebView.
        if (globalMouseListener == null) {
            globalMouseListener = new AWTEventListener() {
                @Override
                public void eventDispatched(AWTEvent event) {
                    handleGlobalMouseEvent(event);
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(
                globalMouseListener, AWTEvent.MOUSE_EVENT_MASK);
        }
    }

    @Override
    public void removeNotify() {
        if (globalMouseListener != null) {
            Toolkit.getDefaultToolkit()
                .removeAWTEventListener(globalMouseListener);
            globalMouseListener = null;
        }
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

    private void handleGlobalMouseEvent(AWTEvent event) {
        if (embedded == null) return;
        if (event.getID() != java.awt.event.MouseEvent.MOUSE_PRESSED) return;
        Object src = event.getSource();
        if (!(src instanceof Component)) return;
        Component target = (Component) src;
        boolean debug = Boolean.getBoolean("ca.weblite.webview.debugShortcut");
        // Click landed inside the WebView -- WebView2 is taking focus
        // legitimately, no native release needed.
        if (target == this || SwingUtilities.isDescendingFrom(target, this)) {
            if (debug) System.err.println(
                "[webview-focus] mouse-press ignored (inside WebView): "
                + target.getClass().getName());
            return;
        }
        Window myWindow = SwingUtilities.getWindowAncestor(this);
        Window targetWindow = SwingUtilities.getWindowAncestor(target);
        if (myWindow == null || targetWindow != myWindow) {
            if (debug) System.err.println(
                "[webview-focus] mouse-press ignored (different window): "
                + target.getClass().getName());
            return;
        }
        if (debug) System.err.println(
            "[webview-focus] mouse-press releaseNativeFocus on target="
            + target.getClass().getName());
        embedded.releaseNativeFocus();
        // Also restore the suppressed caret in case the native LostFocus
        // event won't fire reliably -- WebView2 only fires LostFocus when
        // its hosted content (an inner DOM element) loses focus.  If the
        // user clicked the page background without selecting anything,
        // no inner element ever held focus, so LostFocus never fires when
        // we forcibly release Win32 focus via SetFocus.  Restoring here
        // makes the path independent of the native event.
        restoreSuppressedCaret();
    }

    private void handleFocusOwnerChange(Object newOwner) {
        boolean debug = Boolean.getBoolean("ca.weblite.webview.debugShortcut");
        if (embedded == null) {
            if (debug) System.err.println(
                "[webview-focus] focusOwner change ignored: embedded == null");
            return;
        }
        if (!(newOwner instanceof Component)) {
            if (debug) System.err.println(
                "[webview-focus] focusOwner change ignored: newOwner not Component ("
                + (newOwner == null ? "null" : newOwner.getClass().getName()) + ")");
            return;
        }
        Component owner = (Component) newOwner;
        // Focus moved INTO the WebView -- no native release needed.
        if (owner == this || SwingUtilities.isDescendingFrom(owner, this)) {
            if (debug) System.err.println(
                "[webview-focus] focusOwner change ignored: focus moved into WebView ("
                + owner.getClass().getName() + ")");
            return;
        }
        // Focus moved to an unrelated top-level window -- not our problem.
        Window myWindow = SwingUtilities.getWindowAncestor(this);
        Window ownerWindow = SwingUtilities.getWindowAncestor(owner);
        if (myWindow == null || ownerWindow != myWindow) {
            if (debug) System.err.println(
                "[webview-focus] focusOwner change ignored: different window ("
                + owner.getClass().getName() + ")");
            return;
        }
        if (debug) System.err.println(
            "[webview-focus] releaseNativeFocus on focusOwner="
            + owner.getClass().getName());
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
        // Install the DOM mouse-event bridge alongside the console bridge:
        // same pattern, separate channel.  The flag sink wraps eval and
        // addOnBeforeLoad in try/catch so a late state-change call during
        // teardown can't propagate IllegalStateException out of the
        // dispatcher.
        embedded.addOnBeforeLoad(WebViewMouseDispatcher.SHIM_JS);
        embedded.addJavascriptCallback(WebViewMouseDispatcher.CHANNEL_NAME,
            new WebView.JavascriptCallback() {
                @Override
                public void run(String arg) {
                    mouseDispatcher.dispatch(arg);
                }
            });
        mouseDispatcher.attachFlagSink(new WebViewMouseDispatcher.FlagSink() {
            @Override
            public void eval(String js) {
                EmbeddedWebView e = embedded;
                if (e == null) return;
                try { e.eval(js); } catch (IllegalStateException ignored) {}
            }
            @Override
            public void addOnBeforeLoad(String js) {
                EmbeddedWebView e = embedded;
                if (e == null) return;
                try { e.addOnBeforeLoad(js); } catch (IllegalStateException ignored) {}
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
        // Install the native click callback so a press inside the
        // WebView's native surface can drive Swing's outside-click popup
        // dismissal -- AWT's MouseGrabber AWTEventListener never sees
        // these presses because the heavyweight peer receives them
        // directly from the OS.  The lambda is anchored in
        // EmbeddedWebView.heap via setClickCallback so the JVM does not
        // collect it while the native side holds a global ref.
        embedded.setClickCallback(new WebViewClickCallback() {
            @Override
            public void invoke() {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        handleNativeClick();
                    }
                });
            }
        });
        // Install the dialog bridge so JS alert/confirm/prompt and
        // <input type=file> route to the per-component
        // WebViewDialogHandler.  Anchored in EmbeddedWebView.heap by
        // setDialogCallback so the JVM does not collect the lambda
        // while the native side holds a global ref.  Each method
        // delegates to dialogDispatcher.dispatch*, which does the
        // EDT hop via SwingUtilities.invokeAndWait and returns the
        // handler's answer to the native completion handler.
        embedded.setDialogCallback(new WebViewDialogCallback() {
            @Override
            public void onAlert(String message, String pageUrl, String frameUrl) {
                dialogDispatcher.dispatchAlert(message, pageUrl, frameUrl);
            }
            @Override
            public boolean onConfirm(String message, String pageUrl, String frameUrl) {
                return dialogDispatcher.dispatchConfirm(message, pageUrl, frameUrl);
            }
            @Override
            public String onPrompt(String message, String defaultValue,
                                   String pageUrl, String frameUrl) {
                return dialogDispatcher.dispatchPrompt(
                    message, defaultValue, pageUrl, frameUrl);
            }
            @Override
            public String[] onFilePicker(boolean multiple, String[] mimeTypes,
                                         String[] extensions, String pageUrl,
                                         String frameUrl) {
                return dialogDispatcher.dispatchFilePicker(
                    multiple, mimeTypes, extensions, pageUrl, frameUrl);
            }
        });
        // Install the download bridge so HTTP responses the engine
        // classifies as downloads (Content-Disposition: attachment,
        // non-renderable MIME types) route to the per-component
        // WebViewDownloadHandler.  Anchored in EmbeddedWebView.heap
        // by setDownloadCallback so the JVM does not collect the
        // lambda while the native side holds a global ref.  The
        // callback delegates to downloadDispatcher.dispatchDownload,
        // which does the EDT hop via SwingUtilities.invokeAndWait
        // and returns the handler's chosen path to the native
        // deferral (or null to cancel before any bytes are written).
        embedded.setDownloadCallback(new WebViewDownloadCallback() {
            @Override
            public String onDownloadStarting(String suggestedFilename,
                                             String sourceUrl,
                                             String mimeType,
                                             long totalBytes) {
                return downloadDispatcher.dispatchDownload(
                    suggestedFilename, sourceUrl, mimeType, totalBytes);
            }
        });
    }

    private void handleNativeClick() {
        // Close any open Swing popup (JPopupMenu, JMenu, JComboBox
        // dropdown, tooltip).  Standard Swing dismisses popups when the
        // user clicks outside them via BasicPopupMenuUI's MouseGrabber
        // AWTEventListener -- but that listener only sees mouse events
        // that flow through AWT's event queue, and the heavyweight
        // native peer receives clicks directly from the OS.  Forwarding
        // the dismiss action here restores the expected behaviour.
        // clearSelectedPath() is a no-op when no popup is selected, so
        // it is safe to call on every click without checking first.
        MenuSelectionManager.defaultManager().clearSelectedPath();
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
            restoreSuppressedCaret();
        }
    }

    private void restoreSuppressedCaret() {
        if (suppressedCaretOwner == null) return;
        JTextComponent jtc = suppressedCaretOwner;
        suppressedCaretOwner = null;
        if (jtc.getCaret() != null) {
            jtc.getCaret().setVisible(true);
        }
        // setVisible(true) sets the field, but DefaultCaret only restarts
        // its blink timer on a focusGained event.  In the AWT-vs-Win32
        // focus-desync scenario (Win32 focus moved to WebView2 while
        // AWT's focus owner stayed on the JTextField), AWT never fired
        // a focusLost/focusGained pair on the JTextField, so DefaultCaret
        // believes it never lost focus -- but its blink state may be
        // dead anyway.  Dispatch a synthetic FOCUS_GAINED to retrigger
        // DefaultCaret.focusGained, which restarts the blink timer.
        jtc.dispatchEvent(new java.awt.event.FocusEvent(
            jtc, java.awt.event.FocusEvent.FOCUS_GAINED));
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
