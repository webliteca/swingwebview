/*
 * MIT License
 *
 * Reproducer for the Windows-only blank-WebView bug.  Originally
 * blamed on deep JSplitPane nesting; the actual trigger was found
 * by the litecode agent to be a frame-spanning lightweight component
 * (typically a JFrame glass pane or a PALETTE-layer JLayeredPane
 * overlay) installed above the contentPane.  Such an overlay -- even
 * setOpaque(false) + setVisible(false) -- causes AWT's
 * lightweight/heavyweight mixing pass to apply SetWindowRgn on every
 * heavyweight Canvas in the frame, clipping the WebView2 HWND's pixels
 * to (effectively) nothing.  The WebView2 controller renders correctly;
 * the OS just hides the result.  Mac doesn't hit this because Cocoa
 * composites in one CALayer tree.
 *
 * The minimum trigger in a standalone repro is one extra line before
 * frame.setVisible(true):
 *
 *     JPanel glass = new JPanel();
 *     glass.setOpaque(false);
 *     glass.setVisible(false);
 *     frame.setGlassPane(glass);
 *
 * Pass --glass-pane to enable that here, and use the "Cutout glass pane"
 * toolbar checkbox to apply / clear an empty setMixingCutoutShape on
 * the glass pane at runtime -- toggling it should flip the bug-target
 * WebView between blank and rendered without reattaching anything.
 * That's the diagnosis-confirmation cycle.
 *
 * Mirrors the litecode-app structure that reliably triggers the bug.
 * The recipe combines several factors, each contributed by the agent
 * working on the affected app:
 *
 *   1. Two CardLayouts in the WebView's ancestor chain, each .show()n
 *      AFTER the JFrame is already on screen.
 *   2. The WebView's container is built and attached deferred -- via
 *      SwingUtilities.invokeLater + Timer(500) -- not at frame setup
 *      time.  So the WebView's hierarchy comes into existence after
 *      frame.setVisible(true) has already settled.
 *   3. Three nested JSplitPanes (HORIZONTAL outer / HORIZONTAL inner /
 *      VERTICAL inside the inner's left card).
 *   4. MULTIPLE sibling WebView2 instances created in one shot inside
 *      the same JTabbedPane.  litecode's BranchDetail builds Design /
 *      Readme / Notebook viewers as siblings; the strongest current
 *      hypothesis is that the FIRST WebView claims an HWND / GDI slot
 *      and subsequent WebViews silently render to invisible regions.
 *      This demo creates WV1 (initially-selected first tab), WV2/WV3
 *      (middle tabs), and a bug-target WebView in the LAST tab.
 *   5. The bug-target WebView's tab is NOT initially selected -- its
 *      first paint() is deferred until the user clicks that tab,
 *      AFTER WV1/2/3 have already become displayable.
 *   6. An ACTIVE heavyweight peer (not just a bare Canvas) doing real
 *      native rendering in another tab of the same frame.  This demo
 *      uses ActiveHeavyweightCanvas, a BufferStrategy-driven random-
 *      colour fill loop at ~30fps, as a stand-in for the JediTerm
 *      terminal in the real app.
 *
 * Component tree once the deferred build has run:
 *
 *   JFrame (FlatLaf via --flatlaf)
 *     └─ ContentPane (BorderLayout)
 *         ├─ toolbar (NORTH)
 *         └─ mainSplit   (JSplitPane HORIZONTAL #1)            CENTER
 *             ├─ leftNavPanel  (JList of "branches")
 *             └─ contentSplit  (JSplitPane HORIZONTAL #2)
 *                 ├─ centerPanel (JPanel + CardLayout #1)
 *                 │   ├─ "empty"     (initial card)
 *                 │   └─ "repoCard"  (added later, then shown)
 *                 │       └─ branchSplit (JSplitPane VERTICAL #3)
 *                 │           ├─ TOP: branchListPanel (JTable)
 *                 │           └─ BOT: detailPanel (BorderLayout)
 *                 │               └─ cardPanel2 (CardLayout #2)
 *                 │                   ├─ "empty"
 *                 │                   └─ "branchCard" (added later)
 *                 │                       └─ wrapper (BorderLayout)
 *                 │                           └─ innerTabs (JTabbedPane)
 *                 │                               ├─ WebView 1   (initially selected)
 *                 │                               ├─ WebView 2
 *                 │                               ├─ WebView 3
 *                 │                               ├─ Placeholder 0..7
 *                 │                               └─ WebView (bug target)  <-- target
 *                 │                                   └─ webviewHost (BorderLayout)
 *                 │                                       └─ WebViewComponent
 *                 └─ eastTabs (JTabbedPane)
 *                     ├─ Notes (placeholder)
 *                     └─ Heavy (ActiveHeavyweightCanvas -- BufferStrategy
 *                               render loop, stands in for JediTerm)
 *
 * What to look for on Windows:
 *   - On startup, WV1 paints normally in tab[0].  Click through to
 *     "WebView (bug target)" (last tab) -- its first paint() happens
 *     at that moment, AFTER WV1/2/3 are already alive.  DevTools
 *     (right-click -> Inspect Element) should confirm the page is
 *     fully rendered at the correct viewport size while the on-screen
 *     Canvas region is blank.
 *   - Also worth checking: do WV2 / WV3 render correctly when their
 *     tabs are first selected?  Any of the four going blank is
 *     evidence for "later WebViews silently render to invisible
 *     regions while WV1 hogs a slot."
 *
 * Diagnostic toggles in the toolbar (active once the WebView has been
 * built; click "Select WebView tab" first):
 *
 *   "Wrap in java.awt.Panel"
 *       Tears down the current WebView and reinstalls a fresh one with
 *       a java.awt.Panel (heavyweight) between it and the Swing host.
 *
 *   "Mixing cutout (this Canvas)"
 *       Applies an empty mixing-cutout shape to the WebView's Canvas
 *       (setComponentMixingCutoutShape on JDK 8, setMixingCutoutShape
 *       on JDK 9+).  If the bug is AWT's lightweight-overlap clip
 *       region, this should restore the WebView immediately.
 *
 *   "Force revalidate"
 *       Calls revalidate() + repaint() up the ancestor chain.
 *
 *   "Select WebView tab" / "Cycle tabs"
 *       Programmatic equivalents of the user-click bug-trigger and the
 *       hide-then-show cycle.
 *
 * Command-line:
 *   --flatlaf       use FlatLaf (if on classpath) instead of system L&F
 *   --no-defer      build the WebView eagerly during run() instead of
 *                   deferring -- baseline for confirming that the
 *                   deferred-attach pattern is what triggers the bug
 *   --webview-first make the bug-target tab initially-selected (last
 *                   tab is selected from the start, so its first paint
 *                   happens at frame.setVisible(true) instead of on
 *                   user click) -- baseline for confirming that
 *                   deferred first-paint is part of the trigger
 *
 * Re-launch with -Dsun.awt.disableMixing=true to test the AWT-mixing
 * hypothesis at JVM startup (or pass --no-mixing to the .bat launcher).
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.image.BufferStrategy;
import java.awt.Panel;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.lang.reflect.Method;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;

public class WebViewSplitPaneBlankRepro {

    public static void main(String[] args) {
        // Heavyweight popups so JComboBox/menu/tooltip don't render behind
        // the heavyweight WebView peer.  Matches the established demos.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);

        boolean useFlatLaf = false;
        for (String a : args) {
            if ("--flatlaf".equals(a)) useFlatLaf = true;
            else if ("--no-defer".equals(a)) DEFER_BUILD = false;
            else if ("--webview-first".equals(a)) WEBVIEW_FIRST = true;
            else if ("--glass-pane".equals(a)) GLASS_PANE = true;
            else if ("--glass-pane-visible".equals(a)) GLASS_PANE_VISIBLE = true;
            else if ("--palette-overlay".equals(a)) PALETTE_OVERLAY = true;
        }
        try {
            if (useFlatLaf) {
                Class<?> laf = Class.forName("com.formdev.flatlaf.FlatLightLaf");
                laf.getMethod("setup").invoke(null);
                System.err.println("[repro] FlatLaf enabled");
            } else {
                javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getSystemLookAndFeelClassName());
                System.err.println("[repro] System L&F: "
                    + javax.swing.UIManager.getLookAndFeel().getName());
            }
        } catch (Throwable t) {
            System.err.println("[repro] L&F setup failed; running with "
                + "cross-platform default. cause=" + t);
        }

        System.err.println("[repro] flags: --flatlaf=" + useFlatLaf
            + " --no-defer=" + (!DEFER_BUILD)
            + " --webview-first=" + WEBVIEW_FIRST
            + " --glass-pane=" + GLASS_PANE
            + " --glass-pane-visible=" + GLASS_PANE_VISIBLE
            + " --palette-overlay=" + PALETTE_OVERLAY);
        EventQueue.invokeLater(WebViewSplitPaneBlankRepro::run);
    }

    // ---- Runtime flag state (set in main, read in run/buildDeferred) ----

    /**
     * True (default): build the WebView's parent chain via Timer(500)
     * AFTER frame.setVisible(true).  False: build everything before
     * setVisible -- baseline for confirming that the deferred pattern
     * is what triggers the bug.
     */
    private static boolean DEFER_BUILD = true;

    /**
     * False (default): the WebView is the LAST tab in innerTabs (10
     * placeholder tabs + WebView), and innerTabs starts on Placeholder 0
     * so the WebView's first paint waits for the user to click its tab.
     * True: WebView is tab[0] and initially selected.
     */
    private static boolean WEBVIEW_FIRST = false;

    /**
     * --glass-pane: install a setOpaque(false), setVisible(false)
     * JPanel as the JFrame's glass pane before setVisible.
     *
     * NOTE: JFrame's default glass pane is already a setVisible(false),
     * setOpaque(false) empty JPanel (see JRootPane.createGlassPane), so
     * this flag effectively replaces one default-style panel with
     * another.  Tested first on the assumption it would match the
     * litecode pattern.  Did not reproduce.
     */
    private static boolean GLASS_PANE = false;

    /**
     * --glass-pane-visible: install a setVisible(TRUE) glass pane
     * with a single child JLabel covering the frame.  Hypothesis:
     * the litecode ChatPanel was visible at startup (or had been
     * laid out while visible) so the mixing pass actually walked
     * it and computed bounds.
     */
    private static boolean GLASS_PANE_VISIBLE = false;

    /**
     * --palette-overlay: install a setOpaque(false) JComponent at
     * JLayeredPane.PALETTE_LAYER (above the contentPane in z-order
     * but below the glass pane), sized to the full layered pane on
     * every resize.  Mirrors the litecode "stageOverlay" pattern
     * the agent identified as the other trigger candidate.
     */
    private static boolean PALETTE_OVERLAY = false;

    /**
     * References to the overlay components installed by the flags
     * above, so the toolbar's "Cutout overlays" toggle can target
     * them at runtime.  Null when the corresponding flag is unset.
     */
    private static JPanel glassPane;
    private static JComponent paletteOverlay;

    // ---- Cross-handler state ----

    /**
     * Mutable single-element holder so the toolbar's action listeners can
     * address the *current* WebViewComponent across panel-wrap toggle
     * recreations.  installWebView() rewrites slot [0]; everything else
     * reads through it.
     */
    private static final WebViewComponent[] CURRENT_WV = new WebViewComponent[1];

    /**
     * Populated by buildDeferredContent().  Null until the deferred Timer
     * fires; toolbar handlers null-guard on it.
     */
    private static JTabbedPane innerTabs;
    private static JPanel currentWebviewHost;

    private static void run() {
        JFrame frame = new JFrame(
            "WebView blank-render repro (litecode-pattern, Windows)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        System.err.println("[repro] WebView mode: "
            + WebViewComponent.resolveDefaultMode());

        // ---- Left nav: "branches" list ----
        DefaultListModel<String> branchModel = new DefaultListModel<>();
        for (int i = 1; i <= 8; i++) branchModel.addElement("branch-" + i);
        JList<String> branchList = new JList<>(branchModel);
        branchList.setSelectedIndex(0);
        JPanel leftNavPanel = new JPanel(new BorderLayout());
        leftNavPanel.add(new JLabel(" Branches"), BorderLayout.NORTH);
        leftNavPanel.add(new JScrollPane(branchList), BorderLayout.CENTER);
        leftNavPanel.setPreferredSize(new Dimension(180, 0));

        // ---- Center: outer CardLayout (#1).  Starts empty -- the
        // "repoCard" is added later by buildDeferredContent and then
        // shown.  Replicates "user clicks a repo" in litecode.
        CardLayout cardLayout1 = new CardLayout();
        JPanel centerPanel = new JPanel(cardLayout1);
        JLabel emptyOuter = new JLabel(
            "Select a repo (deferred build will replace this card)",
            SwingConstants.CENTER);
        centerPanel.add(emptyOuter, "empty");
        cardLayout1.show(centerPanel, "empty");

        // ---- East: tabbed pane with a stand-in heavyweight Canvas in
        // one tab, to approximate the JediTerm heavyweight peer the
        // litecode app has elsewhere in its frame.
        JTabbedPane eastTabs = new JTabbedPane();
        JTextArea notes = new JTextArea(
            "(placeholder Notes tab)\n\n"
          + "The Heavy tab contains a bare java.awt.Canvas as a "
          + "stand-in for the JediTerm heavyweight peer the real "
          + "app has elsewhere in the frame.  Removing it should "
          + "show whether 'multiple heavyweights in the same frame' "
          + "is part of the trigger.");
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        eastTabs.addTab("Notes", new JScrollPane(notes));

        JPanel heavyHost = new JPanel(new BorderLayout());
        heavyHost.add(new JLabel(
            " Active heavyweight Canvas (continuously repaints "
          + "via BufferStrategy -- competes for GDI/DWM resources)"),
            BorderLayout.NORTH);
        // ActiveHeavyweightCanvas does real native rendering on a
        // background thread (~30fps random-colour fills via
        // BufferStrategy + Toolkit.sync), which mirrors the
        // JediTerm-style active heavyweight peer the real app
        // has in its frame.  A bare java.awt.Canvas is heavyweight
        // but allocates no rendering surface -- it doesn't reproduce
        // the GDI/DWM-state effects of an active peer.
        heavyHost.add(new ActiveHeavyweightCanvas(), BorderLayout.CENTER);
        eastTabs.addTab("Heavy", heavyHost);

        // ---- Inner content split (#2): centerPanel | eastTabs.
        JSplitPane contentSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, centerPanel, eastTabs);
        contentSplit.setResizeWeight(0.75);
        contentSplit.setContinuousLayout(true);

        // ---- Main split (#1): leftNav | contentSplit.
        JSplitPane mainSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, leftNavPanel, contentSplit);
        mainSplit.setResizeWeight(0.15);
        mainSplit.setContinuousLayout(true);

        // ---- Toolbar (mostly the same as before, but with null guards
        // since the WebView doesn't exist until the deferred build).
        JTextField urlField = new JTextField("https://example.com", 40);
        JButton go = new JButton("Go");
        go.addActionListener(e -> {
            if (CURRENT_WV[0] != null) {
                CURRENT_WV[0].setUrl(urlField.getText().trim());
            } else {
                System.err.println(
                    "[repro] no WebView yet -- click 'Select WebView tab' "
                  + "after the deferred build runs.");
            }
        });
        urlField.addActionListener(e -> go.doClick());

        JCheckBox panelWrapToggle = new JCheckBox("Wrap in java.awt.Panel");
        panelWrapToggle.setToolTipText(
            "Recreate the WebView with a java.awt.Panel (heavyweight) "
          + "between it and the Swing host.  Inserts an extra "
          + "heavyweight ancestor above the WebView's own Canvas.");
        panelWrapToggle.addActionListener(e -> {
            if (currentWebviewHost == null) {
                System.err.println("[repro] no WebView host yet");
                panelWrapToggle.setSelected(!panelWrapToggle.isSelected());
                return;
            }
            installWebView(currentWebviewHost,
                panelWrapToggle.isSelected(), urlField.getText().trim());
            System.err.println("[repro] reinstalled WebView "
                + (panelWrapToggle.isSelected()
                    ? "WRAPPED in java.awt.Panel"
                    : "UNWRAPPED"));
        });

        JCheckBox cutoutToggle = new JCheckBox("Mixing cutout (this Canvas)");
        cutoutToggle.setToolTipText(
            "Apply an empty mixing-cutout shape to the WebView's Canvas "
          + "(JDK 8 sun.awt API, JDK 9+ public API).  If the bug is "
          + "AWT's lightweight-overlap clip region, this should restore "
          + "the WebView immediately.");
        cutoutToggle.addActionListener(e -> {
            WebViewComponent wv = CURRENT_WV[0];
            if (wv == null) {
                System.err.println("[repro] no WebView yet");
                return;
            }
            Component canvas = findHostCanvas(wv);
            if (canvas == null) {
                System.err.println(
                    "[repro] No Canvas descendant found under "
                  + "WebViewComponent; is it lightweight mode?");
                return;
            }
            boolean applied = applyMixingCutout(canvas,
                cutoutToggle.isSelected() ? new Rectangle() : null);
            System.err.println("[repro] mixing-cutout "
                + (cutoutToggle.isSelected() ? "applied" : "cleared")
                + " on " + canvas.getClass().getName() + " -> "
                + (applied ? "ok" : "FAILED"));
            canvas.invalidate();
            canvas.revalidate();
            canvas.repaint();
        });

        JCheckBox glassPaneCutoutToggle = new JCheckBox("Cutout glass pane");
        glassPaneCutoutToggle.setToolTipText(
            "Apply (checked) or clear an empty setMixingCutoutShape on "
          + "the JFrame's glass pane.  No-op if --glass-pane / "
          + "--glass-pane-visible wasn't passed at startup.");
        glassPaneCutoutToggle.addActionListener(e -> {
            if (glassPane == null) {
                System.err.println("[repro] no glass pane installed");
                glassPaneCutoutToggle.setSelected(false);
                return;
            }
            java.awt.Shape shape = glassPaneCutoutToggle.isSelected()
                ? new Rectangle() : null;
            applyMixingCutout(glassPane, shape);
            glassPane.invalidate();
            glassPane.revalidate();
            glassPane.repaint();
            if (CURRENT_WV[0] != null) {
                CURRENT_WV[0].invalidate();
                CURRENT_WV[0].revalidate();
                CURRENT_WV[0].repaint();
            }
            System.err.println("[repro] glass-pane cutout "
                + (glassPaneCutoutToggle.isSelected() ? "applied" : "cleared"));
        });

        JCheckBox paletteCutoutToggle = new JCheckBox("Cutout palette overlay");
        paletteCutoutToggle.setToolTipText(
            "Apply (checked) or clear an empty setMixingCutoutShape on "
          + "the PALETTE-layer overlay.  No-op if --palette-overlay "
          + "wasn't passed at startup.");
        paletteCutoutToggle.addActionListener(e -> {
            if (paletteOverlay == null) {
                System.err.println("[repro] no palette overlay installed");
                paletteCutoutToggle.setSelected(false);
                return;
            }
            java.awt.Shape shape = paletteCutoutToggle.isSelected()
                ? new Rectangle() : null;
            applyMixingCutout(paletteOverlay, shape);
            paletteOverlay.invalidate();
            paletteOverlay.revalidate();
            paletteOverlay.repaint();
            if (CURRENT_WV[0] != null) {
                CURRENT_WV[0].invalidate();
                CURRENT_WV[0].revalidate();
                CURRENT_WV[0].repaint();
            }
            System.err.println("[repro] palette-overlay cutout "
                + (paletteCutoutToggle.isSelected() ? "applied" : "cleared"));
        });

        JButton revalidateBtn = new JButton("Force revalidate");
        revalidateBtn.setToolTipText(
            "Walk the WebView's ancestor chain calling revalidate() + "
          + "repaint().");
        revalidateBtn.addActionListener(e -> {
            Component c = CURRENT_WV[0];
            if (c == null) {
                System.err.println("[repro] no WebView yet");
                return;
            }
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

        JButton selectWebViewTabBtn = new JButton("Select bug-target tab");
        selectWebViewTabBtn.setToolTipText(
            "Programmatically select the bug-target WebView tab "
          + "(the LAST tab in innerTabs).  This is the bug-trigger "
          + "step from the recipe -- the bug-target WebView's first "
          + "paint() happens at this moment, after WV1/2/3 already "
          + "claimed their HWND slots.");
        selectWebViewTabBtn.addActionListener(e -> {
            if (innerTabs == null) {
                System.err.println("[repro] innerTabs not built yet");
                return;
            }
            int idx = findBugTargetTabIndex(innerTabs);
            if (idx < 0) {
                System.err.println("[repro] no bug-target tab found");
                return;
            }
            innerTabs.setSelectedIndex(idx);
            System.err.println("[repro] selected bug-target tab at index "
                + idx);
        });

        JButton cycleTabBtn = new JButton("Cycle tabs");
        cycleTabBtn.setToolTipText(
            "Switch to WebView 1 and back to the bug-target tab after "
          + "300ms.  Tests the addNotify/removeNotify cycle on the "
          + "bug-target Canvas while the other WebViews continue "
          + "running.");
        cycleTabBtn.addActionListener(e -> {
            if (innerTabs == null) return;
            int wvIdx = findBugTargetTabIndex(innerTabs);
            if (wvIdx < 0) return;
            innerTabs.setSelectedIndex(0);
            final int finalWvIdx = wvIdx;
            Timer t = new Timer(300, ev ->
                innerTabs.setSelectedIndex(finalWvIdx));
            t.setRepeats(false);
            t.start();
        });

        JLabel hint = new JLabel(
            "Click 'Select WebView tab' to trigger first paint.  "
          + "Re-launch with --flatlaf / --no-defer / --webview-first "
          + "to vary the recipe.");
        hint.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbar.add(new JLabel("URL:"));
        toolbar.add(urlField);
        toolbar.add(go);
        toolbar.add(selectWebViewTabBtn);
        toolbar.add(panelWrapToggle);
        toolbar.add(cutoutToggle);
        toolbar.add(glassPaneCutoutToggle);
        toolbar.add(paletteCutoutToggle);
        toolbar.add(revalidateBtn);
        toolbar.add(cycleTabBtn);
        toolbar.add(hint);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(toolbar, BorderLayout.NORTH);
        frame.getContentPane().add(mainSplit, BorderLayout.CENTER);
        frame.setSize(1400, 900);
        frame.setLocationRelativeTo(null);

        // Install overlay variants BEFORE setVisible, matching different
        // shapes of the litecode trigger.  The Cutout overlays toolbar
        // toggle flips setMixingCutoutShape on whichever overlays were
        // installed.
        //
        // NOTE: JFrame's default glass pane is *already* a
        // setVisible(false) + setOpaque(false) empty JPanel, so
        // --glass-pane alone is essentially a no-op replacement and
        // (consistent with my Windows test) doesn't reproduce the bug.
        // The visible-glass-pane and palette-overlay variants below
        // are more invasive and closer to what litecode actually does.
        if (GLASS_PANE) {
            glassPane = new JPanel();
            glassPane.setOpaque(false);
            glassPane.setVisible(false);
            frame.setGlassPane(glassPane);
            System.err.println("[repro] installed --glass-pane "
                + "(opaque=false, visible=false, no children)");
        } else if (GLASS_PANE_VISIBLE) {
            // Visible-but-transparent glass pane with one child so it
            // has real bounds + a child contributing to the mixing pass.
            // Litecode's ChatPanel was probably laid out while visible
            // before being toggled off.
            JPanel gp = new JPanel(new BorderLayout());
            gp.setOpaque(false);
            JLabel filler = new JLabel(" (visible transparent glass pane)");
            filler.setOpaque(false);
            gp.add(filler, BorderLayout.NORTH);
            gp.setVisible(true);
            frame.setGlassPane(gp);
            glassPane = gp;
            System.err.println("[repro] installed --glass-pane-visible "
                + "(opaque=false, visible=TRUE, one child)");
        }

        frame.setVisible(true);
        System.err.println("[repro] frame shown.  defer="
            + DEFER_BUILD + " webviewFirst=" + WEBVIEW_FIRST);

        if (PALETTE_OVERLAY) {
            // Litecode's stageOverlay extends JLayeredPane (NOT JPanel)
            // and is installed AFTER setVisible.  The agent's
            // hypothesis: JLayeredPane interacts with the mixing pass
            // differently than a bare JPanel, and installing after
            // the frame is realized triggers a fresh mixing-pass
            // computation that picks up the overlay's bounds.
            //
            // Override contains() to return false so the overlay is
            // click-transparent -- otherwise it intercepts mouse
            // events across the entire frame and breaks JSplitPane
            // divider drag, JTabbedPane tab selection, etc.  The
            // mixing pass uses getBounds(), not contains(), so this
            // does NOT change the mixing-region calculation.
            JLayeredPane lp = frame.getLayeredPane();
            JLayeredPane overlay = new JLayeredPane() {
                @Override
                public boolean contains(int x, int y) {
                    return false;
                }
            };
            overlay.setOpaque(false);
            overlay.setVisible(true);
            overlay.setBounds(0, 0, lp.getWidth(), lp.getHeight());
            lp.add(overlay, JLayeredPane.PALETTE_LAYER);
            paletteOverlay = overlay;
            lp.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (paletteOverlay != null) {
                        paletteOverlay.setBounds(0, 0,
                            lp.getWidth(), lp.getHeight());
                    }
                }
            });
            System.err.println("[repro] installed --palette-overlay "
                + "(JLayeredPane at PALETTE_LAYER, post-setVisible, "
                + "bounds=" + paletteOverlay.getBounds() + ")");
        }

        // ---- THE KEY RECIPE STEP ----
        // Build the WebView's container chain AFTER frame is visible.
        // The 500ms Timer simulates the user-click delay in the real
        // app where this triggers.
        Runnable build = () -> buildDeferredContent(
            centerPanel, cardLayout1, urlField.getText().trim());
        if (DEFER_BUILD) {
            SwingUtilities.invokeLater(() -> {
                Timer t = new Timer(500, e -> build.run());
                t.setRepeats(false);
                t.start();
            });
        } else {
            // Baseline: build everything synchronously before any user
            // interaction, to confirm the deferred pattern is what
            // matters.  Still runs through buildDeferredContent so the
            // layout is identical.
            build.run();
        }
    }

    /**
     * Builds branchSplit + cardPanel2 + branchCard + innerTabs + WebView
     * and attaches them via two CardLayout.show() calls (one per
     * card-layout level).  Populates innerTabs and currentWebviewHost
     * so the toolbar handlers can address them.
     *
     * Must run on the EDT.
     */
    private static void buildDeferredContent(JPanel centerPanel,
                                             CardLayout cardLayout1,
                                             String url) {
        System.err.println("[repro] deferred build starting");
        // Diagnostic per the agent's note: if either overlay reports
        // 0x0 or null bounds at this point, the mixing pass is
        // correctly skipping it and the trigger isn't firing.
        System.err.println("[repro] at deferred build: glass="
            + (glassPane == null ? "null" : glassPane.getBounds())
            + " palette="
            + (paletteOverlay == null ? "null" : paletteOverlay.getBounds()));

        // ---- Top of branchSplit: a placeholder JTable of "branches" ----
        Object[][] branchRows = new Object[][] {
            {"main",    "abc12345"},
            {"develop", "bcd23456"},
            {"feature/x", "cde34567"},
            {"feature/y", "def45678"},
            {"hotfix/z", "efa56789"},
        };
        JTable branchTable = new JTable(branchRows,
            new Object[] {"Branch", "Sha"});

        // ---- Inner CardLayout (#2) inside detailPanel ----
        CardLayout cardLayout2 = new CardLayout();
        JPanel cardPanel2 = new JPanel(cardLayout2);
        cardPanel2.add(
            new JLabel("Select a branch", SwingConstants.CENTER), "empty");
        cardLayout2.show(cardPanel2, "empty");

        JPanel detailPanel = new JPanel(new BorderLayout());
        detailPanel.add(cardPanel2, BorderLayout.CENTER);

        // ---- branchSplit (JSplitPane VERTICAL #3): branches / detail ----
        JSplitPane branchSplit = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(branchTable), detailPanel);
        branchSplit.setResizeWeight(0.4);
        branchSplit.setContinuousLayout(true);

        // ---- "repoCard": container for branchSplit, attached to outer
        // CardLayout #1 and then shown.  This is the first .show() in
        // the recipe.
        JPanel repoCard = new JPanel(new BorderLayout());
        repoCard.add(branchSplit, BorderLayout.CENTER);
        centerPanel.add(repoCard, "repoCard");
        cardLayout1.show(centerPanel, "repoCard");
        System.err.println("[repro] cardLayout1.show(repoCard)");

        // ---- innerTabs: matches the litecode pattern.  Three WebViews
        // up front (the "Design / Readme / Notebook" cluster in the
        // real app) so the JFrame gets multiple heavyweight WebView2
        // peers parented to it simultaneously, then placeholders, then
        // the BUG-TARGET WebView in the last tab.  WV1 is the
        // initially-selected tab so it takes the "first paint" slot
        // before the user clicks the bug-target tab.
        JTabbedPane tabs = new JTabbedPane();

        // WV1: initially selected, first heavyweight WebView in the JFrame.
        // Pinned to example.com so the visual is unambiguous if it
        // renders -- we want to see WHICH of the four goes blank, if any.
        JPanel firstHost = new JPanel(new BorderLayout());
        WebViewComponent wv1 = WebViewComponent.create();
        wv1.setUrl("https://example.com");
        firstHost.add(wv1, BorderLayout.CENTER);
        tabs.addTab("WebView 1", firstHost);

        // WV2 / WV3: sibling WebViews to mirror litecode's
        // Design/Readme/Notebook trio.  Different URLs so they can be
        // told apart visually.
        JPanel midHost1 = new JPanel(new BorderLayout());
        WebViewComponent wv2 = WebViewComponent.create();
        wv2.setUrl("https://www.iana.org/about");
        midHost1.add(wv2, BorderLayout.CENTER);
        tabs.addTab("WebView 2", midHost1);

        JPanel midHost2 = new JPanel(new BorderLayout());
        WebViewComponent wv3 = WebViewComponent.create();
        wv3.setUrl("https://www.iana.org/numbers");
        midHost2.add(wv3, BorderLayout.CENTER);
        tabs.addTab("WebView 3", midHost2);

        // 8 placeholder tabs between the sibling cluster and the bug
        // target -- so a tab-click on the bug target traverses a real
        // tab-strip distance, like in the real app.
        for (int i = 0; i < 8; i++) {
            tabs.addTab("Placeholder " + i,
                new JLabel("Placeholder tab " + i, SwingConstants.CENTER));
        }

        // BUG-TARGET WebView: tracked via installWebView() in
        // CURRENT_WV[0] / currentWebviewHost so the toolbar's
        // diagnostic toggles operate on it.  By default NOT initially
        // selected; user must click "WebView (bug target)" to trigger
        // first paint.  --webview-first reverses this so the bug
        // target is the initially-selected tab (baseline for
        // "is deferred first-paint the trigger?").
        JPanel webviewHost = new JPanel(new BorderLayout());
        installWebView(webviewHost, false, url);
        tabs.addTab("WebView (bug target)", webviewHost);

        if (WEBVIEW_FIRST) {
            tabs.setSelectedIndex(tabs.getTabCount() - 1);
        } else {
            tabs.setSelectedIndex(0);
        }

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(tabs, BorderLayout.CENTER);
        JPanel branchCard = new JPanel(new BorderLayout());
        branchCard.add(wrapper, BorderLayout.CENTER);

        // ---- "branchCard": attached to inner CardLayout #2 and shown.
        // This is the second .show() in the recipe.
        cardPanel2.add(branchCard, "branchCard");
        cardLayout2.show(cardPanel2, "branchCard");
        System.err.println("[repro] cardLayout2.show(branchCard)");

        innerTabs = tabs;
        currentWebviewHost = webviewHost;

        System.err.println("[repro] deferred build complete.  WebView is "
            + "tab " + (WEBVIEW_FIRST ? 0 : tabs.getTabCount() - 1)
            + (WEBVIEW_FIRST ? " (initially selected)"
                             : " (NOT initially selected; click it to trigger first paint)"));
    }

    /**
     * (Re)install a WebViewComponent into webviewHost, optionally with a
     * java.awt.Panel between it and the Swing host.  Replaces whatever
     * is currently there -- removing the previous WebView triggers
     * removeNotify on its Canvas, which disposes the native peer.
     *
     * Updates CURRENT_WV[0] to the new WebView so the toolbar listeners
     * see it, and re-hooks the canvas-resize log on the fresh Canvas.
     */
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
            // on Windows.  Inserts an extra heavyweight ancestor between
            // the WebView's Canvas HWND and the lightweight Swing
            // parents.
            Panel panel = new Panel(new BorderLayout());
            panel.add(wv, BorderLayout.CENTER);
            webviewHost.add(panel, BorderLayout.CENTER);
        } else {
            webviewHost.add(wv, BorderLayout.CENTER);
        }
        webviewHost.revalidate();
        webviewHost.repaint();

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
                  + "build.");
            }
        });
    }

    /**
     * Find the index of the bug-target tab in innerTabs by exact
     * title match.  Returns -1 if not found.
     */
    private static int findBugTargetTabIndex(JTabbedPane tabs) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if ("WebView (bug target)".equals(tabs.getTitleAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Canvas subclass that, once displayable, allocates a
     * BufferStrategy and runs a background render loop painting
     * random-colour fills via {@code bs.getDrawGraphics()} +
     * {@code Toolkit.sync()} at ~30fps.  Approximates the active
     * heavyweight peer (JediTerm in the real app) that competes for
     * GDI / DWM resources alongside the WebView2 child HWNDs.  A bare
     * java.awt.Canvas is heavyweight but allocates no rendering
     * surface; it doesn't reproduce the GDI/DWM-state effects of an
     * active peer.
     */
    private static final class ActiveHeavyweightCanvas extends Canvas {
        private volatile boolean alive = true;
        private Thread renderThread;

        @Override
        public void addNotify() {
            super.addNotify();
            EventQueue.invokeLater(() -> {
                if (!isDisplayable()) return;
                try {
                    createBufferStrategy(2);
                } catch (Throwable t) {
                    System.err.println(
                        "[repro] ActiveHeavyweightCanvas: "
                      + "createBufferStrategy failed: " + t);
                    return;
                }
                renderThread = new Thread(this::renderLoop,
                    "active-canvas-render");
                renderThread.setDaemon(true);
                renderThread.start();
            });
        }

        @Override
        public void removeNotify() {
            alive = false;
            if (renderThread != null) renderThread.interrupt();
            super.removeNotify();
        }

        private void renderLoop() {
            java.util.Random rng = new java.util.Random();
            while (alive && isDisplayable()) {
                try {
                    Thread.sleep(33);
                } catch (InterruptedException ie) {
                    return;
                }
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) continue;
                try {
                    BufferStrategy bs = getBufferStrategy();
                    if (bs == null) continue;
                    Graphics g = bs.getDrawGraphics();
                    try {
                        g.setColor(new Color(rng.nextInt(0xFFFFFF)));
                        g.fillRect(0, 0, w, h);
                    } finally {
                        g.dispose();
                    }
                    bs.show();
                    Toolkit.getDefaultToolkit().sync();
                } catch (Throwable t) {
                    // Surface invalidated mid-render or shutting down.
                    return;
                }
            }
        }
    }

    /**
     * Walks the WebViewComponent looking for the first heavyweight AWT
     * Canvas descendant; on the heavyweight build that's the surface the
     * native WebView2 child HWND is parented under.
     */
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

    /**
     * Apply (or clear, with null) a mixing-cutout shape on a Component
     * across both JDK 8 (com.sun.awt.AWTUtilities) and JDK 9+
     * (Component.setMixingCutoutShape).  Returns true on success.
     */
    private static boolean applyMixingCutout(Component c, java.awt.Shape shape) {
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
