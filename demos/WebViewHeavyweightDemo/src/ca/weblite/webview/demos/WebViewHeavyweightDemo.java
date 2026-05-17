/*
 * MIT License
 *
 * Demonstrates embedding a native WebView in a Swing application via the
 * {@link WebViewComponent#create()} factory, alongside surrounding Swing
 * widgets that exercise the trickier mixing scenarios: a JComboBox whose
 * drop-down extends over the WebView area, a JTabbedPane where the
 * WebView is one of several tabs, and a JS Bridge panel that round-trips
 * values between the page and Java via the structured callback / eval /
 * dispatch / console-capture APIs.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.WebView;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
    // observe round-trips after registering the bindings.
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
        "<p>Console output (visible in the Log tab):</p>" +
        "<p>" +
        "<button onclick=\"console.log('log()', new Date().toLocaleTimeString())\">console.log</button> " +
        "<button onclick=\"console.info('info()')\">console.info</button> " +
        "<button onclick=\"console.warn('warn() — careful')\">console.warn</button> " +
        "<button onclick=\"console.error('error() — oops')\">console.error</button> " +
        "<button onclick=\"console.debug('debug() trace')\">console.debug</button>" +
        "</p>" +
        "<p>Or type in <code>document.title</code> below and use the JS " +
        "Bridge eval box to read it back via <code>window.swingLog</code>:</p>" +
        "<p>Title: <input id='t' value='hello'/> " +
        "<button onclick=\"document.title=document.getElementById('t').value\">Set title</button></p>" +
        "<p>Try eval: <code>window.swingLog(document.title)</code></p>" +
        "</body></html>";

    public static void main(String[] args) {
        // Heavyweight popups so JComboBox dropdowns, tooltips, and menus
        // render as real OS-level popups that sit above any heavyweight
        // WebView peer.  Has no observable downside in lightweight mode
        // (Linux default), where Swing popups composite over the offscreen
        // pixels just fine either way.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewHeavyweightDemo::run);
    }

    private static void run() {
        JFrame frame = new JFrame("WebView Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // ----- WebView via factory -----
        // create() picks the right implementation for the host platform
        // (heavyweight on macOS/Windows, lightweight on Linux).  Override
        // with -Dca.weblite.webview.mode=heavyweight|lightweight.
        WebViewComponent wv = WebViewComponent.create();
        // debug=true enables the platform's native DevTools and (on Linux
        // debug builds) routes console.* to stdout.  Console capture
        // itself works either way.
        wv.setDebug(true);
        wv.setUrl("https://example.com");
        wv.setPreferredSize(new Dimension(900, 600));

        System.err.println("[demo] WebView mode: "
            + WebViewComponent.resolveDefaultMode()
            + " (heavyweight=" + wv.isHeavyweight() + ")");

        // ----- Console capture -> Log tab -----
        JTextArea logArea = new JTextArea(20, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ConsoleListener consoleListener = new ConsoleListener() {
            @Override
            public void onMessage(ConsoleMessage msg) {
                logArea.append(msg.toString() + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        };
        wv.addConsoleListener(consoleListener);

        // ----- URL bar / toolbar -----
        JTextField urlField = new JTextField("https://example.com", 50);
        urlField.setToolTipText("Press Enter or click Go to navigate.");
        JButton go = new JButton("Go");
        go.addActionListener(e -> wv.setUrl(resolveUrl(urlField.getText())));
        urlField.addActionListener(e -> wv.setUrl(resolveUrl(urlField.getText())));

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
            // Display the label in the URL field instead of stuffing a
            // multi-kilobyte data: URL into it; resolveUrl() reverses the
            // label back to TEST_PAGE_URL if the user later presses Enter
            // or Go without editing.
            urlField.setText(selected);
            wv.setUrl(resolveUrl(selected));
        });

        JCheckBox lightweightToggle = new JCheckBox("Lightweight popups");
        lightweightToggle.setToolTipText(
            "Toggle to flip popup style.  When checked, dropdowns try to " +
            "use lightweight Swing rendering -- which on a heavyweight " +
            "WebView renders BEHIND the native peer.  Useful for verifying " +
            "the heavyweight popup interop story; has no observable effect " +
            "in lightweight mode.");
        lightweightToggle.addActionListener(e -> {
            JPopupMenu.setDefaultLightWeightPopupEnabled(
                lightweightToggle.isSelected());
            ToolTipManager.sharedInstance().setLightWeightPopupEnabled(
                lightweightToggle.isSelected());
        });

        // ----- Tabs -----
        JTabbedPane tabs = new JTabbedPane();

        // "+ New WebView Tab" instantiates a second (third, ...) WebView in
        // the same JVM.  Before the fix for issue #21 this reliably crashed
        // the JVM with SIGSEGV in objc_registerClassPair on macOS the first
        // time it was clicked (second WebView in the process).  After the
        // fix the new tab opens normally.
        JButton newTabBtn = new JButton("+ New WebView Tab");
        newTabBtn.setToolTipText(
            "Create an additional WebView in a new tab.  Reproduces " +
            "issue #21 on macOS: instantiating a second WKWebView in the " +
            "same process used to crash in objc_registerClassPair.");
        newTabBtn.addActionListener(e -> addWebViewTab(tabs));

        JPanel toolbar = new JPanel(new BorderLayout(8, 4));
        JPanel toolbarWest = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbarWest.add(new JLabel("URL:"));
        toolbarWest.add(urlField);
        toolbarWest.add(go);
        JPanel toolbarEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        toolbarEast.add(bookmark);
        toolbarEast.add(newTabBtn);
        toolbarEast.add(lightweightToggle);
        toolbar.add(toolbarWest, BorderLayout.CENTER);
        toolbar.add(toolbarEast, BorderLayout.EAST);

        JPanel webviewTab = new JPanel(new BorderLayout());
        webviewTab.add(wv, BorderLayout.CENTER);
        tabs.addTab("WebView", webviewTab);

        // Log tab -- captures every console.* call from the page on the EDT.
        JPanel logTab = new JPanel(new BorderLayout(4, 4));
        JTextArea logExplainer = new JTextArea(
            "Captured console.log / info / warn / error / debug calls from " +
            "the embedded page.  Lines are formatted as " +
            "[LEVEL] source:line text.  Listeners fire on the EDT and the " +
            "shim is installed at document-start, so this includes calls " +
            "that run before the first navigation completes.");
        logExplainer.setEditable(false);
        logExplainer.setLineWrap(true);
        logExplainer.setWrapStyleWord(true);
        logExplainer.setMargin(new java.awt.Insets(8, 12, 8, 12));
        JButton clearLogBtn = new JButton("Clear");
        clearLogBtn.addActionListener(e -> logArea.setText(""));
        JCheckBox mirrorStdout = new JCheckBox("Mirror to System.out", false);
        mirrorStdout.setToolTipText(
            "When checked, captured messages are also written to the " +
            "process's stdout (in addition to this Log tab).");
        mirrorStdout.addActionListener(e -> {
            wv.setConsoleOutput(mirrorStdout.isSelected() ? System.out : null);
        });
        JPanel logCtrlRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        logCtrlRow.add(mirrorStdout);
        logCtrlRow.add(clearLogBtn);
        logTab.add(logExplainer, BorderLayout.NORTH);
        logTab.add(new JScrollPane(logArea), BorderLayout.CENTER);
        logTab.add(logCtrlRow, BorderLayout.SOUTH);
        tabs.addTab("Log", logTab);

        // Pure-Swing tab -- exercises the tab-visibility tracking on the
        // heavyweight engine (HierarchyListener hides the native peer when
        // this tab is selected and restores it on the way back).  No-op on
        // lightweight where the engine is offscreen anyway.
        JPanel swingTab = new JPanel(new BorderLayout(8, 8));
        JTextArea explainer = new JTextArea(
            "This tab is pure Swing.\n\n" +
            "Switch back to the 'WebView' tab to verify the embedded\n" +
            "WebView re-appears.  On heavyweight (macOS / Windows) a\n" +
            "HierarchyListener on the Canvas hides the native peer when\n" +
            "this tab becomes active and restores it on the way back.\n" +
            "Lightweight (Linux) has nothing to hide -- the offscreen\n" +
            "engine just stops being painted.");
        explainer.setEditable(false);
        explainer.setMargin(new java.awt.Insets(12, 12, 12, 12));
        swingTab.add(explainer, BorderLayout.NORTH);
        DefaultListModel<String> items = new DefaultListModel<>();
        for (int i = 1; i <= 30; i++) {
            items.addElement("Swing list item " + i);
        }
        swingTab.add(new JScrollPane(new JList<>(items)), BorderLayout.CENTER);
        tabs.addTab("Swing only", swingTab);

        // ----- JS Bridge panel -----
        JPanel jsBridge = buildJsBridgePanel(wv);

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

    // Translates the URL-field text into the URL to navigate to.  The only
    // non-identity mapping today is the (JS bridge test page) label, which
    // expands back to its data: URL -- otherwise the multi-kilobyte URL
    // would be stuffed into the URL field on bookmark selection.
    private static String resolveUrl(String input) {
        String trimmed = input == null ? "" : input.trim();
        return TEST_PAGE_LABEL.equals(trimmed) ? TEST_PAGE_URL : trimmed;
    }

    // Running count of additional WebView tabs spawned by the "+ New
    // WebView Tab" button, used for the tab title.
    private static int extraWebViewCount = 0;

    private static void addWebViewTab(JTabbedPane tabs) {
        extraWebViewCount++;
        int n = extraWebViewCount + 1;   // first extra tab is "WebView 2"

        WebViewComponent extra = WebViewComponent.create();
        extra.setDebug(true);
        extra.setUrl("https://example.com");
        extra.setPreferredSize(new Dimension(900, 600));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(extra, BorderLayout.CENTER);

        String title = "WebView " + n;
        tabs.addTab(title, panel);
        int idx = tabs.indexOfComponent(panel);

        // Tab header with a small "x" close button so the user can dispose
        // the extra WebView without leaking native peers.
        JPanel tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tabHeader.setOpaque(false);
        tabHeader.add(new JLabel(title));
        JButton close = new JButton("x");
        close.setMargin(new java.awt.Insets(0, 4, 0, 4));
        close.setFocusable(false);
        close.setToolTipText("Close this WebView tab.");
        close.addActionListener(ev -> {
            int curIdx = tabs.indexOfComponent(panel);
            if (curIdx >= 0) tabs.removeTabAt(curIdx);
            // removeNotify() on the WebViewComponent will dispose the
            // native peer.
        });
        tabHeader.add(close);
        tabs.setTabComponentAt(idx, tabHeader);

        tabs.setSelectedIndex(idx);

        System.err.println("[demo] Spawned " + title
            + " (heavyweight=" + extra.isHeavyweight() + ")");
    }

    /**
     * Builds a panel that exercises every JS-interaction method on the
     * abstract {@link WebViewComponent}: {@code eval},
     * {@code addOnBeforeLoad}, {@code addJavascriptCallback}, and
     * {@code dispatch}.  The controls drive the single webview supplied
     * by the factory.
     */
    private static JPanel buildJsBridgePanel(WebViewComponent wv) {
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
            "Run JS in the current document.  Result is fire-and-forget " +
            "-- use a binding (swingLog) to round-trip a value.");
        JButton evalBtn = new JButton("Eval");
        Runnable runEval = () -> {
            String js = evalField.getText();
            append.accept("eval: " + js);
            wv.eval(js);
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
            wv.addOnBeforeLoad(js);
            append.accept("addOnBeforeLoad registered; takes effect on next navigation");
        });
        initRow.add(new JLabel("Init:"), BorderLayout.WEST);
        initRow.add(initField, BorderLayout.CENTER);
        initRow.add(initBtn, BorderLayout.EAST);

        // --- Bindings + dispatch row ---
        JPanel bindRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton bindLog = new JButton("Register window.swingLog");
        bindLog.setToolTipText(
            "Expose a Java callback as window.swingLog(...) inside the " +
            "page.  Args arrive as a JSON-array string.");
        bindLog.addActionListener(e -> {
            wv.addJavascriptCallback("swingLog",
                new WebView.JavascriptCallback() {
                    @Override public void run(String arg) {
                        append.accept("swingLog <- " + arg);
                    }
                });
            append.accept("(registered window.swingLog)");
        });
        JButton bindPing = new JButton("Register window.swingPing");
        bindPing.addActionListener(e -> {
            wv.addJavascriptCallback("swingPing",
                new WebView.JavascriptCallback() {
                    @Override public void run(String arg) {
                        append.accept("swingPing <- " + arg);
                    }
                });
            append.accept("(registered window.swingPing)");
        });
        JButton dispatchBtn = new JButton("Dispatch hello");
        dispatchBtn.setToolTipText(
            "Call dispatch(Runnable) on the webview; the Runnable runs " +
            "on the native UI thread and reports back via the log.");
        dispatchBtn.addActionListener(e -> {
            wv.dispatch(new Runnable() {
                @Override public void run() {
                    append.accept("dispatch ran on thread: " +
                        Thread.currentThread().getName());
                }
            });
        });
        JButton devtoolsBtn = new JButton("Open DevTools");
        devtoolsBtn.setToolTipText(
            "Open the platform's native Web Inspector / DevTools in a " +
            "separate OS window.  Returns false on macOS (use right-click " +
            "-> Inspect Element instead).");
        devtoolsBtn.addActionListener(e -> {
            boolean opened = wv.openDevTools();
            append.accept("openDevTools() -> " + opened);
        });
        bindRow.add(bindLog);
        bindRow.add(bindPing);
        bindRow.add(dispatchBtn);
        bindRow.add(devtoolsBtn);

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
