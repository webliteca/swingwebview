/*
 * MIT License
 *
 * Demonstrates embedding a native WebView as a heavyweight Swing component,
 * and exercises the trickier mixing scenarios with surrounding Swing
 * widgets: a JComboBox whose drop-down extends over the WebView area, and
 * a JTabbedPane where the WebView is one of several tabs.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.WebView;
import ca.weblite.webview.swing.WebViewComponent;
import ca.weblite.webview.swing.WebViewHeavyweightComponent;
import ca.weblite.webview.swing.WebViewLightweightComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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

public class WebViewHeavyweightDemo {

    // Self-contained page that exercises every JS-bridge feature: it calls
    // window.swingLog / window.swingPing on button click so the user can
    // observe round-trips from both webviews after registering the bindings.
    private static final String TEST_PAGE_LABEL = "(JS bridge test page)";
    private static final String TEST_PAGE_URL =
        "data:text/html;charset=utf-8," +
        "<html><body style='font-family:sans-serif;padding:16px'>" +
        "<h2>JS Bridge Test Page</h2>" +
        "<p>Register <code>window.swingLog</code> and " +
        "<code>window.swingPing</code> from the JS Bridge panel below, " +
        "then click these:</p>" +
        "<p>" +
        "<button onclick=\"if(window.swingLog)window.swingLog('hello from button',new Date().toLocaleTimeString());else alert('swingLog not bound yet')\">Call swingLog</button> " +
        "<button onclick=\"if(window.swingPing)window.swingPing(JSON.stringify({rand:Math.random()}));else alert('swingPing not bound yet')\">Call swingPing</button>" +
        "</p>" +
        "<p>Or type in <code>document.title</code> below and use the JS " +
        "Bridge eval box to read it back via <code>window.swingLog</code>:</p>" +
        "<p>Title: <input id='t' value='hello'/> " +
        "<button onclick=\"document.title=document.getElementById('t').value\">Set title</button></p>" +
        "<p>Try eval: <code>window.swingLog(document.title)</code></p>" +
        "</body></html>";

    public static void main(String[] args) {
        // Heavyweight popups so JComboBox dropdowns, tooltips, and menus
        // render as real NSWindows that sit above the WKWebView heavyweight
        // peer.  Without this their lightweight Swing form would be painted
        // behind the WebView and effectively invisible.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewHeavyweightDemo::run);
    }

    private static void run() {
        JFrame frame = new JFrame("WebView Heavyweight Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // ----- WebView and URL bar -----
        WebViewHeavyweightComponent wv = new WebViewHeavyweightComponent();
        wv.setUrl("https://example.com");
        wv.setPreferredSize(new Dimension(900, 600));

        JTextField urlField = new JTextField("https://example.com", 50);
        urlField.setToolTipText("Press Enter or click Go to navigate.");
        JButton go = new JButton("Go");
        go.addActionListener(e -> wv.setUrl(urlField.getText().trim()));
        urlField.addActionListener(e -> wv.setUrl(urlField.getText().trim()));

        // JComboBox in the toolbar -- drop-down should open over the
        // WebView area when "Browser" tab is selected.  This is the
        // canonical "does heavyweight popup work?" test.
        JComboBox<String> bookmark = new JComboBox<>(new String[] {
            "Bookmarks ...",
            TEST_PAGE_LABEL,
            "https://example.com",
            "https://en.wikipedia.org",
            "https://news.ycombinator.com",
            "https://www.google.com",
            "https://www.openjdk.org",
            "https://www.apple.com",
        });
        bookmark.setToolTipText("Open the dropdown to verify it appears above the WebView.");
        bookmark.addActionListener(e -> {
            int i = bookmark.getSelectedIndex();
            if (i <= 0) return;
            String selected = (String) bookmark.getSelectedItem();
            String u = TEST_PAGE_LABEL.equals(selected) ? TEST_PAGE_URL : selected;
            urlField.setText(u);
            wv.setUrl(u);
        });

        JCheckBox lightweightToggle = new JCheckBox("Lightweight popups");
        lightweightToggle.setToolTipText(
            "Toggle to flip popup style.  When checked, dropdowns try to " +
            "use lightweight Swing rendering -- which on this WebView " +
            "renders BEHIND the heavyweight peer.  Restart required for " +
            "the change to apply to already-created components.");
        lightweightToggle.addActionListener(e -> {
            JPopupMenu.setDefaultLightWeightPopupEnabled(
                lightweightToggle.isSelected());
            ToolTipManager.sharedInstance().setLightWeightPopupEnabled(
                lightweightToggle.isSelected());
        });

        JPanel toolbar = new JPanel(new BorderLayout(8, 4));
        JPanel toolbarWest = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbarWest.add(new JLabel("URL:"));
        toolbarWest.add(urlField);
        toolbarWest.add(go);
        JPanel toolbarEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        toolbarEast.add(bookmark);
        toolbarEast.add(lightweightToggle);
        toolbar.add(toolbarWest, BorderLayout.CENTER);
        toolbar.add(toolbarEast, BorderLayout.EAST);

        // ----- Tabs -----
        JTabbedPane tabs = new JTabbedPane();

        JPanel browserTab = new JPanel(new BorderLayout());
        browserTab.add(wv, BorderLayout.CENTER);
        tabs.addTab("Heavyweight", browserTab);

        // Lightweight (offscreen) WebView.  Currently Linux-only;
        // on macOS / Windows the native entry points are stubs and
        // the component will show its empty Swing background.  On
        // Linux this is the recommended mode: rendering, mouse, and
        // keyboard all work; the heavyweight tab has reliable
        // rendering but unreliable visible text-input feedback.
        WebViewLightweightComponent lwv = new WebViewLightweightComponent();
        lwv.setUrl("https://example.com");
        lwv.setPreferredSize(new Dimension(900, 600));
        JPanel lightweightTab = new JPanel(new BorderLayout());
        JTextArea lwExplainer = new JTextArea(
            "Lightweight (offscreen) WebView.  Renders the page into a " +
            "BufferedImage and forwards mouse/keyboard from AWT into the " +
            "engine -- so Swing components composite cleanly above it " +
            "(JComboBox dropdowns, JLayer overlays, etc.) and Z-order " +
            "works without tricks.  On Linux this is the recommended " +
            "mode.  Known limitations: no IME / CJK composition; " +
            "right-click context menus and in-page <select> dropdowns " +
            "don't render (they're suppressed because they'd otherwise " +
            "paint to our invisible offscreen surface).");
        lwExplainer.setEditable(false);
        lwExplainer.setLineWrap(true);
        lwExplainer.setWrapStyleWord(true);
        lwExplainer.setMargin(new java.awt.Insets(8, 12, 8, 12));
        lightweightTab.add(lwExplainer, BorderLayout.NORTH);
        lightweightTab.add(lwv, BorderLayout.CENTER);
        tabs.addTab("Lightweight", lightweightTab);

        // On Linux the lightweight tab is the recommended path, so
        // open the demo on it.  Other platforms get the heavyweight
        // tab by default since heavyweight works fully there.
        if (System.getProperty("os.name", "").toLowerCase()
                .contains("linux")) {
            tabs.setSelectedIndex(1); // Lightweight
        }

        // Have the Go button and bookmark dropdown drive both webviews
        // so the user can compare them side-by-side as they navigate.
        go.addActionListener(e -> lwv.setUrl(urlField.getText().trim()));
        urlField.addActionListener(e -> lwv.setUrl(urlField.getText().trim()));
        bookmark.addActionListener(e -> {
            int i = bookmark.getSelectedIndex();
            if (i <= 0) return;
            String selected = (String) bookmark.getSelectedItem();
            String u = TEST_PAGE_LABEL.equals(selected) ? TEST_PAGE_URL : selected;
            lwv.setUrl(u);
        });

        JPanel swingTab = new JPanel(new BorderLayout(8, 8));
        JTextArea explainer = new JTextArea(
            "This tab is pure Swing.\n\n" +
            "Switch back to the 'Browser' tab to verify the embedded\n" +
            "WebView re-appears (a HierarchyListener on the heavyweight\n" +
            "Canvas calls setHidden:YES on the WKWebView when this tab\n" +
            "becomes active, and reverses it on the way back).");
        explainer.setEditable(false);
        explainer.setMargin(new java.awt.Insets(12, 12, 12, 12));
        swingTab.add(explainer, BorderLayout.NORTH);
        DefaultListModel<String> items = new DefaultListModel<>();
        for (int i = 1; i <= 30; i++) {
            items.addElement("Swing list item " + i);
        }
        swingTab.add(new JScrollPane(new JList<>(items)), BorderLayout.CENTER);
        tabs.addTab("Swing only", swingTab);

        // ----- JS Bridge panel (drives both WebViews) -----
        Map<String, WebViewComponent> bridgeTargets = new LinkedHashMap<>();
        bridgeTargets.put("[hw]", wv);
        bridgeTargets.put("[lw]", lwv);
        JPanel jsBridge = buildJsBridgePanel(bridgeTargets);

        // ----- Frame -----
        JSplitPane split = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, tabs, jsBridge);
        split.setResizeWeight(0.7);
        split.setContinuousLayout(true);
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(toolbar, BorderLayout.NORTH);
        frame.getContentPane().add(split, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Builds a panel that exercises every JS-interaction method on the
     * abstract {@link WebViewComponent}: {@code eval},
     * {@code addOnBeforeLoad}, {@code addJavascriptCallback}, and
     * {@code dispatch}.  The same controls drive every webview in
     * {@code targets} so the heavyweight and lightweight implementations
     * can be compared side-by-side by switching tabs.
     */
    private static JPanel buildJsBridgePanel(
            Map<String, WebViewComponent> targets) {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("JS Bridge"));

        JTextArea log = new JTextArea(8, 60);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        DateTimeFormatter ts = DateTimeFormatter.ofPattern("HH:mm:ss");
        java.util.function.Consumer<String> append = msg ->
            SwingUtilities.invokeLater(() -> {
                log.append("[" + LocalTime.now().format(ts) + "] " + msg + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            });

        // --- Eval row ---
        JPanel evalRow = new JPanel(new BorderLayout(4, 0));
        JTextField evalField = new JTextField("document.title");
        evalField.setToolTipText(
            "Run JS in the current document of both webviews.  Result is " +
            "fire-and-forget -- use a binding (swingLog) to round-trip a value.");
        JButton evalBtn = new JButton("Eval");
        Runnable runEval = () -> {
            String js = evalField.getText();
            for (Map.Entry<String, WebViewComponent> en : targets.entrySet()) {
                append.accept(en.getKey() + " eval: " + js);
                en.getValue().eval(js);
            }
        };
        evalBtn.addActionListener(e -> runEval.run());
        evalField.addActionListener(e -> runEval.run());
        evalRow.add(new JLabel("Eval:"), BorderLayout.WEST);
        evalRow.add(evalField, BorderLayout.CENTER);
        evalRow.add(evalBtn, BorderLayout.EAST);

        // --- Init script row ---
        JPanel initRow = new JPanel(new BorderLayout(4, 0));
        JTextField initField = new JTextField(
            "window.addEventListener('load',function(){if(window.swingLog)window.swingLog('onload: '+location.href);});");
        initField.setToolTipText(
            "Register an init script that runs at the start of every new " +
            "document.  Applies on the NEXT navigation -- click a bookmark " +
            "or press Go after adding.");
        JButton initBtn = new JButton("Add onBeforeLoad");
        initBtn.addActionListener(e -> {
            String js = initField.getText();
            for (Map.Entry<String, WebViewComponent> en : targets.entrySet()) {
                en.getValue().addOnBeforeLoad(js);
            }
            append.accept("addOnBeforeLoad registered on " +
                targets.size() + " webview(s); takes effect on next navigation");
        });
        initRow.add(new JLabel("Init:"), BorderLayout.WEST);
        initRow.add(initField, BorderLayout.CENTER);
        initRow.add(initBtn, BorderLayout.EAST);

        // --- Bindings + dispatch row ---
        JPanel bindRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton bindLog = new JButton("Register window.swingLog");
        bindLog.setToolTipText(
            "Expose a Java callback as window.swingLog(...) inside both " +
            "pages.  Args arrive as a JSON array string.");
        bindLog.addActionListener(e -> {
            for (Map.Entry<String, WebViewComponent> en : targets.entrySet()) {
                final String lbl = en.getKey();
                en.getValue().addJavascriptCallback("swingLog",
                    new WebView.JavascriptCallback() {
                        @Override public void run(String arg) {
                            append.accept(lbl + " swingLog <- " + arg);
                        }
                    });
            }
            append.accept("(registered window.swingLog on " +
                targets.size() + " webview(s))");
        });
        JButton bindPing = new JButton("Register window.swingPing");
        bindPing.addActionListener(e -> {
            for (Map.Entry<String, WebViewComponent> en : targets.entrySet()) {
                final String lbl = en.getKey();
                en.getValue().addJavascriptCallback("swingPing",
                    new WebView.JavascriptCallback() {
                        @Override public void run(String arg) {
                            append.accept(lbl + " swingPing <- " + arg);
                        }
                    });
            }
            append.accept("(registered window.swingPing on " +
                targets.size() + " webview(s))");
        });
        JButton dispatchBtn = new JButton("Dispatch hello");
        dispatchBtn.setToolTipText(
            "Call dispatch(Runnable) on both webviews; the Runnable runs " +
            "on each native UI thread and reports back via the log.");
        dispatchBtn.addActionListener(e -> {
            for (Map.Entry<String, WebViewComponent> en : targets.entrySet()) {
                final String lbl = en.getKey();
                en.getValue().dispatch(new Runnable() {
                    @Override public void run() {
                        append.accept(lbl + " dispatch ran on thread: " +
                            Thread.currentThread().getName());
                    }
                });
            }
        });
        bindRow.add(bindLog);
        bindRow.add(bindPing);
        bindRow.add(dispatchBtn);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(evalRow);
        controls.add(initRow);
        controls.add(bindRow);

        JButton clearLog = new JButton("Clear log");
        clearLog.addActionListener(e -> log.setText(""));
        JPanel logBtnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        logBtnRow.add(clearLog);

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(new JScrollPane(log), BorderLayout.CENTER);
        logPanel.add(logBtnRow, BorderLayout.SOUTH);

        panel.add(controls, BorderLayout.NORTH);
        panel.add(logPanel, BorderLayout.CENTER);
        return panel;
    }
}
