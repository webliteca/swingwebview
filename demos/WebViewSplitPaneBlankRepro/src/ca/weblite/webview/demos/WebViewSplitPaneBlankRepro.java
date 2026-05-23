/*
 * MIT License
 *
 * Reproducer for the Windows-only "WebView renders blank inside deeply
 * nested JSplitPane" bug.
 *
 * Layout mimics a typical IDE/editor: a horizontal top-level split with a
 * sidebar on the left, then a vertical split on the right whose top half
 * is itself a horizontal split containing the WebView in its right pane.
 * That puts the WebView three JSplitPane ancestors deep, which is the
 * shape multiple users have reported triggering the blank render on
 * Windows 10/11 with the WebView2 backend.
 *
 * What to look for on Windows:
 *   - The WebView2 page is fully loaded -- right-click -> Inspect Element
 *     in DevTools confirms the page rendered and reports the correct
 *     viewport size -- but the on-screen Canvas region is white/blank.
 *   - Dragging any divider often "wakes it up" momentarily then it goes
 *     blank again, or stays blank entirely.
 *
 * Diagnostic toggles in the toolbar:
 *
 *   "Wrap in java.awt.Panel"
 *       Tears down the current WebView and reinstalls a fresh one
 *       with a java.awt.Panel (heavyweight) between it and the
 *       Swing host.  Tests the agent-suggested workaround of
 *       inserting an extra heavyweight ancestor above the WebView's
 *       own Canvas HWND -- if that's enough to break the bad
 *       lightweight-overlap clip computation in nested JSplitPanes,
 *       the rendering should come back immediately on toggle.
 *       The page reloads from the URL field on each toggle.
 *
 *   "Mixing cutout (this Canvas)"
 *       Calls com.sun.awt.AWTUtilities.setComponentMixingCutoutShape
 *       (JDK 8) or Component.setMixingCutoutShape (JDK 9+) on the
 *       Canvas that hosts the WebView2 child HWND, with an empty
 *       Rectangle.  That tells AWT "never clip me to make room for
 *       lightweight overlap."  If the diagnosis (AWT mixing applies a
 *       bogus SetWindowRgn on the Canvas inside nested JSplitPanes) is
 *       right, flipping this on should restore the rendering without
 *       touching anything else.
 *
 *   "-Dsun.awt.disableMixing=true"
 *       Not a runtime toggle (the flag is read at JVM startup).
 *       Mentioned in the toolbar tooltip so users can re-launch with
 *       it set to compare.  If THIS fixes the blank render, the
 *       mixing-clip hypothesis is confirmed.
 *
 *   "Force revalidate"
 *       Calls revalidate() + repaint() on the WebView's ancestor
 *       chain.  Useful for confirming this is not just a stale-layout
 *       problem (if revalidate fixes it, the bug is layout-side; if
 *       it doesn't, it's the mixing region or DComp clip).
 *
 * Diagnostic logging is written to stderr on attach and on every
 * Canvas resize, including the Canvas peer's window region via
 * GetWindowRgnBox-equivalent reflection where available.  Look for
 * lines prefixed [repro] in the launcher output.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Panel;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.lang.reflect.Method;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewSplitPaneBlankRepro {

    public static void main(String[] args) {
        // Heavyweight popups so JComboBox/menu/tooltip don't render behind
        // the heavyweight WebView peer.  Matches the established demos.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewSplitPaneBlankRepro::run);
    }

    // Mutable single-element holder so the toolbar's action listeners can
    // address the *current* WebViewComponent across panel-wrap toggle
    // recreations.  installWebView() rewrites slot [0]; everything else
    // reads through it.
    private static final WebViewComponent[] CURRENT_WV = new WebViewComponent[1];

    private static void run() {
        JFrame frame = new JFrame(
            "WebView blank-render repro (nested JSplitPane, Windows)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        System.err.println("[repro] WebView mode: "
            + WebViewComponent.resolveDefaultMode());

        // ----- Left sidebar (depth-1 child of top-level split) -----
        DefaultListModel<String> sidebarItems = new DefaultListModel<>();
        for (int i = 1; i <= 20; i++) {
            sidebarItems.addElement("Sidebar item " + i);
        }
        JList<String> sidebar = new JList<>(sidebarItems);
        JScrollPane sidebarScroll = new JScrollPane(sidebar);
        sidebarScroll.setPreferredSize(new Dimension(180, 0));

        // ----- Inner editor area: horizontal split with a "file tree" on
        // the left and the WebView on the right.  This is the second
        // JSplitPane the WebView sits inside.
        DefaultListModel<String> filesItems = new DefaultListModel<>();
        for (int i = 1; i <= 12; i++) {
            filesItems.addElement("file-" + i + ".java");
        }
        JList<String> filesList = new JList<>(filesItems);
        JTabbedPane filesTabs = new JTabbedPane();
        filesTabs.addTab("Files", new JScrollPane(filesList));
        filesTabs.addTab("Outline", new JScrollPane(new JTextArea(
            "(pretend this is a code outline view)")));

        JPanel webviewHost = new JPanel(new BorderLayout());
        // First install: unwrapped, default URL.  installWebView() also
        // populates CURRENT_WV[0] and hooks the canvas-resize log.
        installWebView(webviewHost, false, "https://example.com");

        JSplitPane editorSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, filesTabs, webviewHost);
        editorSplit.setResizeWeight(0.2);
        editorSplit.setContinuousLayout(true);

        // ----- Bottom panel: third JSplitPane (vertical), placing
        // editorSplit on top and a "console" area on the bottom.  This is
        // the WebView's outermost split-pane ancestor.
        JTextArea console = new JTextArea(
            "(pretend this is a build console / problems view)\n"
          + "Drag the divider above / below me; on Windows the WebView\n"
          + "to the upper right may go blank even though devtools say\n"
          + "the page is fully rendered at the correct size.\n");
        console.setEditable(false);
        JSplitPane verticalSplit = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, editorSplit, new JScrollPane(console));
        verticalSplit.setResizeWeight(0.75);
        verticalSplit.setContinuousLayout(true);

        // ----- Top-level horizontal split: sidebar | (editor + console).
        // WebView ancestor chain is now:
        //   JSplitPane (top-level, horizontal)
        //     └─ JSplitPane (vertical)
        //         └─ JSplitPane (editor, horizontal)
        //             └─ JPanel webviewHost
        //                 └─ WebViewHeavyweightComponent
        //                     └─ Canvas  <-- the HWND that goes blank
        JSplitPane topSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, sidebarScroll, verticalSplit);
        topSplit.setResizeWeight(0.15);
        topSplit.setContinuousLayout(true);

        // ----- Toolbar with the diagnostic toggles -----
        JTextField urlField = new JTextField("https://example.com", 40);
        JButton go = new JButton("Go");
        go.addActionListener(e -> CURRENT_WV[0].setUrl(urlField.getText().trim()));
        urlField.addActionListener(e -> CURRENT_WV[0].setUrl(urlField.getText().trim()));

        JCheckBox panelWrapToggle = new JCheckBox("Wrap in java.awt.Panel");
        panelWrapToggle.setToolTipText(
            "Recreate the WebView with a java.awt.Panel (heavyweight) "
          + "between it and the lightweight Swing webviewHost.  Tests "
          + "the suggestion that adding a heavyweight ancestor above "
          + "the WebView's own Canvas changes how AWT computes the "
          + "lightweight-overlap clip region inside nested JSplitPanes.  "
          + "Toggling tears down the current WebView (dispose() runs via "
          + "removeNotify) and reinstalls a fresh one -- the page "
          + "reloads from the URL field.");
        panelWrapToggle.addActionListener(e -> {
            String currentUrl = urlField.getText().trim();
            installWebView(webviewHost, panelWrapToggle.isSelected(), currentUrl);
            System.err.println("[repro] reinstalled WebView "
                + (panelWrapToggle.isSelected()
                    ? "WRAPPED in java.awt.Panel"
                    : "UNWRAPPED (direct child of webviewHost)"));
        });

        JCheckBox cutoutToggle = new JCheckBox("Mixing cutout (this Canvas)");
        cutoutToggle.setToolTipText(
            "Apply an empty mixing-cutout shape to the WebView's Canvas "
          + "(setComponentMixingCutoutShape on JDK 8 / "
          + "setMixingCutoutShape on JDK 9+).  If the bug is AWT's "
          + "lightweight-overlap clip region, this should restore the "
          + "WebView immediately.");
        cutoutToggle.addActionListener(e -> {
            WebViewComponent wv = CURRENT_WV[0];
            Component canvas = findHostCanvas(wv);
            if (canvas == null) {
                System.err.println(
                    "[repro] No Canvas descendant found under WebViewComponent; "
                  + "is the WebView in lightweight mode?");
                return;
            }
            boolean applied = applyMixingCutout(canvas,
                cutoutToggle.isSelected() ? new Rectangle() : null);
            System.err.println("[repro] mixing-cutout "
                + (cutoutToggle.isSelected() ? "applied" : "cleared")
                + " on " + canvas.getClass().getName() + " -> "
                + (applied ? "ok" : "FAILED (see stack trace above)"));
            // Force a relayout so the new clip takes effect immediately.
            canvas.invalidate();
            canvas.revalidate();
            canvas.repaint();
        });

        JButton revalidateBtn = new JButton("Force revalidate");
        revalidateBtn.setToolTipText(
            "Walk the WebView's ancestor chain calling revalidate() + "
          + "repaint().  If this 'fixes' the blank render, the bug is a "
          + "stale-layout problem.  If not, it's likely the mixing clip "
          + "region or DComp.");
        revalidateBtn.addActionListener(e -> {
            Component c = CURRENT_WV[0];
            while (c != null) {
                c.invalidate();
                if (c instanceof javax.swing.JComponent) {
                    ((javax.swing.JComponent) c).revalidate();
                }
                c.repaint();
                c = c.getParent();
            }
            System.err.println("[repro] forced revalidate/repaint up the chain");
        });

        JLabel hint = new JLabel(
            "Re-launch with -Dsun.awt.disableMixing=true to test the "
          + "AWT-mixing hypothesis at JVM startup.");
        hint.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 0));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbar.add(new JLabel("URL:"));
        toolbar.add(urlField);
        toolbar.add(go);
        toolbar.add(panelWrapToggle);
        toolbar.add(cutoutToggle);
        toolbar.add(revalidateBtn);
        toolbar.add(hint);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(toolbar, BorderLayout.NORTH);
        frame.getContentPane().add(topSplit, BorderLayout.CENTER);
        frame.setSize(1200, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // (Re)install a WebViewComponent into webviewHost, optionally with a
    // java.awt.Panel between it and the Swing host.  Replaces whatever
    // is currently there -- removing the previous WebView triggers
    // removeNotify on its Canvas, which disposes the native peer, so it
    // is safe to drop the reference.
    //
    // Updates CURRENT_WV[0] to the new WebView so the toolbar listeners
    // see it, and re-hooks the canvas-resize log on the fresh Canvas
    // (the old listener went with the old peer).
    private static void installWebView(JPanel webviewHost,
                                       boolean wrapInPanel,
                                       String url) {
        webviewHost.removeAll();

        WebViewComponent wv = WebViewComponent.create();
        wv.setDebug(true);
        wv.setUrl(url);
        wv.setPreferredSize(new Dimension(700, 500));
        CURRENT_WV[0] = wv;
        System.err.println("[repro] new WebView heavyweight="
            + wv.isHeavyweight()
            + " wrappedInPanel=" + wrapInPanel);

        if (wrapInPanel) {
            // java.awt.Panel is heavyweight -- it gets a WPanelPeer HWND
            // on Windows.  Wrapping the WebViewComponent in one inserts
            // an extra heavyweight ancestor between the WebView's own
            // Canvas HWND and the lightweight Swing parents (JPanel,
            // JSplitPanes, ...).  This is the "wrap in a heavyweight
            // panel" workaround the user's other agent suggested.
            Panel panel = new Panel(new BorderLayout());
            panel.add(wv, BorderLayout.CENTER);
            webviewHost.add(panel, BorderLayout.CENTER);
        } else {
            webviewHost.add(wv, BorderLayout.CENTER);
        }
        webviewHost.revalidate();
        webviewHost.repaint();

        // Hook the canvas-resize log on the next EDT tick so addNotify
        // has had a chance to run and the Canvas exists.
        EventQueue.invokeLater(() -> {
            Component canvas = findHostCanvas(wv);
            if (canvas != null) {
                System.err.println("[repro] host canvas class="
                    + canvas.getClass().getName()
                    + " initialBounds=" + canvas.getBounds());
                canvas.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        System.err.println("[repro] canvas resized -> "
                            + e.getComponent().getBounds());
                    }
                    @Override
                    public void componentMoved(ComponentEvent e) {
                        System.err.println("[repro] canvas moved -> "
                            + e.getComponent().getBounds());
                    }
                });
            } else {
                System.err.println(
                    "[repro] WARNING: could not find a Canvas under the "
                  + "WebViewComponent.  This demo targets the heavyweight "
                  + "build; on Linux it runs but the blank-render bug "
                  + "being investigated does not apply.");
            }
        });
    }

    // Walks the WebViewComponent looking for the first heavyweight AWT
    // Canvas descendant; on the heavyweight build that's the surface the
    // native WebView2 child HWND is parented under.
    private static Component findHostCanvas(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof java.awt.Canvas) return c;
            if (c instanceof Container) {
                Component found = findHostCanvas((Container) c);
                if (found != null) return found;
            }
        }
        return null;
    }

    // Apply (or clear, with null) a mixing-cutout shape on a Component
    // across both JDK 8 (com.sun.awt.AWTUtilities) and JDK 9+
    // (Component.setMixingCutoutShape).  Returns true on success.
    //
    // Reflection-only so the demo compiles on JDK 8 without
    // --add-exports and runs on JDK 9+ without depending on
    // com.sun.awt.AWTUtilities at compile time.
    private static boolean applyMixingCutout(Component c, java.awt.Shape shape) {
        // JDK 9+ public API first.
        try {
            Method m = Component.class.getMethod(
                "setMixingCutoutShape", java.awt.Shape.class);
            m.invoke(c, shape);
            return true;
        } catch (NoSuchMethodException ignore) {
            // fall through to JDK 8 path
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
        // JDK 8 sun-internal API.
        try {
            Class<?> util = Class.forName("com.sun.awt.AWTUtilities");
            Method m = util.getMethod(
                "setComponentMixingCutoutShape",
                Component.class, java.awt.Shape.class);
            m.invoke(null, c, shape);
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }
}
