/*
 * MIT License
 *
 * Smoke test for the macOS data:-URL navigation fix (cocoa_navigate ->
 * loadHTMLString:baseURL:).  Loads data:text/html URLs through all three
 * decode paths and lets you eyeball that each renders instead of showing
 * a blank WKWebView.
 *
 * If the fix works you see the colored heading text for each variant.
 * If data: navigation is still broken you see a blank/white WebView.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ToolTipManager;

public class WebViewDataUrlSmokeTest {

    private static String html(String variant, String color) {
        return "<!doctype html><html><head><meta charset='utf-8'></head>"
             + "<body style='font-family:sans-serif;padding:2em'>"
             + "<h1 style='color:" + color + "'>data: URL OK &mdash; " + variant + "</h1>"
             + "<p>If you can read this, WKWebView loaded a <code>data:</code> "
             + "URL via <code>loadHTMLString:</code>.</p>"
             + "<p>Unicode check: caf&#233; &#9989; &#128512;</p>"
             + "</body></html>";
    }

    /** data:text/html,<raw html> — exercises the no-encoding path. */
    private static String plainUrl() {
        return "data:text/html," + html("plain", "#0a7");
    }

    /** data:text/html,<percent-encoded> — exercises stringByRemovingPercentEncoding. */
    private static String percentUrl() {
        try {
            String enc = URLEncoder.encode(html("percent-encoded", "#06c"), "UTF-8")
                .replace("+", "%20");
            return "data:text/html," + enc;
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /** data:text/html;base64,<base64> — exercises the NSData base64 path. */
    private static String base64Url() {
        String b64 = Base64.getEncoder().encodeToString(
            html("base64", "#a05").getBytes(StandardCharsets.UTF_8));
        return "data:text/html;base64," + b64;
    }

    public static void main(String[] args) {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewDataUrlSmokeTest::run);
    }

    private static void run() {
        System.err.println("[smoke] os.name=" + System.getProperty("os.name")
            + "  mode=" + WebViewComponent.resolveDefaultMode());

        final WebViewComponent wv = WebViewComponent.create();
        wv.setPreferredSize(new Dimension(760, 420));
        // Initial page is a plain data: URL — the case that was blank before.
        wv.setUrl(plainUrl());

        final JLabel status = new JLabel("Loaded: plain data: URL");
        JPanel buttons = new JPanel();
        buttons.add(mkButton("plain", wv, status, plainUrl()));
        buttons.add(mkButton("percent-encoded", wv, status, percentUrl()));
        buttons.add(mkButton("base64", wv, status, base64Url()));
        buttons.add(mkButton("https (control)", wv, status, "https://example.com"));

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);

        JFrame frame = new JFrame("WebViewDataUrlSmokeTest");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(wv, BorderLayout.CENTER);
        frame.getContentPane().add(south, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JButton mkButton(final String label, final WebViewComponent wv,
                                    final JLabel status, final String url) {
        JButton b = new JButton(label);
        b.addActionListener(e -> {
            wv.setUrl(url);
            status.setText("Loaded: " + label + "  (" +
                (url.length() > 60 ? url.substring(0, 60) + "..." : url) + ")");
        });
        return b;
    }
}
