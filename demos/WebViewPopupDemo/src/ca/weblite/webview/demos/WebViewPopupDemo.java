/*
 * MIT License
 *
 * Exercises the browser-initiated popup API (window.open) on
 * WebViewComponent — Canvas 15.
 *
 * Switch among three handler modes via the combo box at the top:
 *   - Default   : allow all popups (the native engine opens each in a
 *                 separate window linked to the opener).
 *   - Custom    : allow + log popupOpened / popupClosed to the pane.
 *   - Block     : setPopupHandler(null) — window.open returns null.
 *
 * macOS coverage is wired in Canvas 15 (this demo's reference).  On Linux
 * and Windows window.open stays blocked until the follow-up coverage
 * canvases land.
 *
 * Manual checks:
 *   - Default / Custom: clicking "window.open(...)" opens a native popup
 *     window loading example.com; the log line reports opener !== null
 *     (proving the child is linked, so OAuth postMessage-to-opener works).
 *   - Block: clicking the button logs "window.open returned null".
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.WebViewPopupEvent;
import ca.weblite.webview.WebViewPopupHandler;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewPopupDemo {

    private static final String MODE_DEFAULT = "Default handler (allow popups)";
    private static final String MODE_CUSTOM  = "Custom handler (allow + log)";
    private static final String MODE_BLOCK   = "Block handler (window.open == null)";

    public static void main(String[] args) {
        // Heavyweight popups so any nested Swing menus render above the
        // WebView region on macOS / Windows heavyweight.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewPopupDemo::run);
    }

    private static void run() {
        final WebViewComponent wv = WebViewComponent.create();

        // Inline test page: a window.open button, a target=_blank link, and
        // a button that reports whether window.open returned null (blocked)
        // or a Window (allowed) plus whether the child is linked to us.
        final String html =
            "<!doctype html><html><head><meta charset='utf-8'>"
          + "<title>WebView Popup Demo</title>"
          + "<style>body{font:14px sans-serif;padding:16px}"
          + "button,a{display:block;margin:8px 0;font-size:14px}</style>"
          + "</head><body>"
          + "<h3>window.open popup demo</h3>"
          + "<button onclick=\"openPopup()\">window.open(example.com, 520x640)</button>"
          + "<a href='https://example.org' target='_blank'>&lt;a target=_blank&gt; link</a>"
          + "<script>"
          + "function openPopup(){"
          + "  var w = window.open('https://example.com','demo','width=520,height=640');"
          + "  if(!w){console.log('window.open returned null (blocked)');return;}"
          + "  console.log('window.open ok; opener-linked child opened');"
          + "}"
          + "</script></body></html>";

        wv.addOnBeforeLoad(
            "document.open();document.write("
          + toJsString(html) + ");document.close();");

        final JTextArea log = new JTextArea();
        log.setEditable(false);
        wv.addConsoleListener(new ConsoleListener() {
            @Override public void onMessage(ConsoleMessage m) {
                append(log, "[page] " + m.getText());
            }
        });

        // Handler modes.
        final WebViewPopupHandler custom = new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                return true; // allow
            }
            @Override public void popupOpened(WebViewPopupEvent e) {
                append(log, "[opened] " + e.targetUrl()
                    + " (" + e.width() + "x" + e.height() + ")");
            }
            @Override public void popupClosed(WebViewPopupEvent e) {
                append(log, "[closed] " + e.targetUrl());
            }
        };

        final JComboBox<String> mode = new JComboBox<String>(
            new String[] { MODE_DEFAULT, MODE_CUSTOM, MODE_BLOCK });
        mode.addActionListener(ev -> {
            Object sel = mode.getSelectedItem();
            if (MODE_CUSTOM.equals(sel)) {
                wv.setPopupHandler(custom);
                append(log, "-- custom handler installed --");
            } else if (MODE_BLOCK.equals(sel)) {
                wv.setPopupHandler(null);
                append(log, "-- popups blocked (setPopupHandler(null)) --");
            } else {
                wv.setPopupHandler(WebViewPopupHandler.DEFAULT);
                append(log, "-- default handler (allow) --");
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Popup handler:"));
        top.add(mode);

        wv.setPreferredSize(new Dimension(700, 420));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wv, new JScrollPane(log));
        split.setResizeWeight(0.7);

        JFrame f = new JFrame("WebView Popup Demo");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setLayout(new BorderLayout());
        f.add(top, BorderLayout.NORTH);
        f.add(split, BorderLayout.CENTER);
        f.setSize(760, 640);
        f.setLocationRelativeTo(null);
        f.setVisible(true);

        wv.setUrl("about:blank");
    }

    private static void append(JTextArea log, String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    /** Encode an HTML string as a JS string literal for document.write. */
    private static String toJsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                default:   b.append(c);
            }
        }
        return b.append("\"").toString();
    }
}
