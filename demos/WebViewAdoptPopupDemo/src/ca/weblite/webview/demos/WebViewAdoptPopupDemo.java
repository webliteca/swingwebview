/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.PopupDisposition;
import ca.weblite.webview.WebViewPopupEvent;
import ca.weblite.webview.WebViewPopupHandler;
import ca.weblite.webview.swing.WebViewComponent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

/**
 * On-device demo for Canvas 18 (popup adoption) and Canvas 21 (custom
 * User-Agent).  Exercises everything the two features add, so the native
 * code can be validated on a real desktop without swingwebbrowser:
 *
 * <ul>
 *   <li><b>Popup mode</b> combo — Adopt into tab / Native window / Block —
 *       drives {@code popupDisposition}.  On {@code ADOPT}, {@code
 *       popupAdoptable} opens the engine's opener-linked child as a new tab
 *       via {@link WebViewComponent#adoptPopup(long)}.</li>
 *   <li>The opener page has a <b>POST form</b> ({@code target="win"}), a
 *       {@code window.open} button, and a {@code target="_blank"} link, plus
 *       a live {@code navigator.userAgent} readout.  Submitting the POST form
 *       in Adopt mode proves the POST body survives into the adopted tab
 *       (httpbin echoes {@code q=hello} and the {@code User-Agent} header).</li>
 *   <li><b>User-Agent</b> field + Set / Reset — calls {@link
 *       WebViewComponent#setUserAgent(String)} on the opener and reloads, so
 *       both the JS-visible UA and the HTTP header (visible in the httpbin
 *       echo) change.</li>
 *   <li><b>Popup UA inheritance</b> (Canvas 21) — after Set UA, click
 *       {@code window.open &rarr; popup} (or submit the POST form): the popup
 *       opens {@code https://httpbin.org/user-agent}, which echoes the UA the
 *       popup's <em>first</em> request carried.  With the fix the popup echoes
 *       the opener's custom UA (not the engine default) in both Adopt and
 *       Native-window modes; with Reset UA it echoes the engine default.  This
 *       verifies the opener's override is applied to the popup child before its
 *       in-flight initial navigation.</li>
 * </ul>
 *
 * <p>The POST/echo checks hit {@code https://httpbin.org/post}, so they need
 * network; the adoption / opener mechanics work offline too.
 */
public final class WebViewAdoptPopupDemo {

    /** A current desktop Safari UA (prefilled into the UA field). */
    private static final String SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
      + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
      + "Version/18.3 Safari/605.1.15";

    /** Read off the EDT by {@code popupDisposition} (native UI thread); set
     *  on the EDT when the combo changes. */
    private static volatile PopupDisposition mode = PopupDisposition.ADOPT;

    public static void main(String[] args) {
        // Heavyweight popups need Swing popups to be heavyweight too.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        SwingUtilities.invokeLater(WebViewAdoptPopupDemo::buildUi);
    }

    private static void buildUi() {
        JFrame frame = new JFrame("WebView Adopt-Popup + User-Agent Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 760);

        JTabbedPane tabs = new JTabbedPane();

        WebViewComponent opener = WebViewComponent.create();
        opener.setPopupHandler(new WebViewPopupHandler() {
            // Native UI thread, synchronous, off the EDT.
            @Override public PopupDisposition popupDisposition(
                    WebViewPopupEvent e) {
                return mode;
            }
            // EDT: host the retained child in a new tab.
            @Override public void popupAdoptable(WebViewPopupEvent e,
                                                 long popupId) {
                WebViewComponent tab = WebViewComponent.adoptPopup(popupId);
                addTab(tabs, "popup", tab);
            }
            @Override public void popupOpened(WebViewPopupEvent e) {
                System.out.println("[demo] popupOpened  " + e.targetUrl());
            }
            @Override public void popupClosed(WebViewPopupEvent e) {
                System.out.println("[demo] popupClosed  " + e.targetUrl());
            }
        });
        opener.setUrl(openerPage());

        frame.add(buildControls(opener), java.awt.BorderLayout.NORTH);
        addTab(tabs, "opener", opener);
        frame.add(tabs, java.awt.BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private static JPanel buildControls(WebViewComponent opener) {
        JPanel bar = new JPanel(new java.awt.FlowLayout(
            java.awt.FlowLayout.LEFT, 8, 6));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        bar.add(new JLabel("Popup mode:"));
        JComboBox<String> modeBox = new JComboBox<>(new String[] {
            "Adopt into tab", "Native window", "Block" });
        modeBox.addActionListener(e -> {
            switch (modeBox.getSelectedIndex()) {
                case 1:  mode = PopupDisposition.NATIVE_WINDOW; break;
                case 2:  mode = PopupDisposition.BLOCK;         break;
                default: mode = PopupDisposition.ADOPT;         break;
            }
        });
        bar.add(modeBox);

        bar.add(new JLabel("   User-Agent:"));
        JTextField uaField = new JTextField(SAFARI_UA, 34);
        bar.add(uaField);
        JButton setUa = new JButton("Set UA");
        setUa.addActionListener(e -> {
            opener.setUserAgent(uaField.getText());
            opener.setUrl(openerPage());  // reload so the UA applies
        });
        bar.add(setUa);
        JButton resetUa = new JButton("Reset UA");
        resetUa.addActionListener(e -> {
            opener.setUserAgent(null);
            opener.setUrl(openerPage());
        });
        bar.add(resetUa);
        JButton clearCache = new JButton("Clear cache");
        clearCache.addActionListener(e -> {
            // Canvas 22: purge the HTTP resource cache, then reload so the
            // page re-fetches from the network (cookies/login are retained).
            opener.clearCache();
            opener.eval("location.reload()");
        });
        bar.add(clearCache);
        return bar;
    }

    private static void addTab(JTabbedPane tabs, String title,
                               WebViewComponent c) {
        tabs.addTab(title, c);
        tabs.setSelectedComponent(c);
    }

    /** Opener page as a base64 {@code data:} URL (the cross-engine-reliable
     *  path the dialog/popup demos use). */
    private static String openerPage() {
        String html =
            "<!doctype html><html><head><meta charset=utf-8>"
          + "<style>body{font:14px system-ui;margin:24px;line-height:1.5}"
          + "button,a{font-size:14px}code{background:#eee;padding:2px 4px}"
          + "</style></head><body>"
          + "<h2>Adopt-popup + User-Agent demo</h2>"
          + "<p><b>navigator.userAgent:</b><br><code id=ua></code></p>"
          + "<h3>1. POST form (proves POST survives into the popup)</h3>"
          + "<form method='post' action='https://httpbin.org/post' "
          + "target='win'>"
          + "<input name='q' value='hello'> "
          + "<button type='submit'>POST &rarr; popup</button></form>"
          + "<h3>2. window.open</h3>"
          + "<button onclick=\"window.open("
          + "'https://httpbin.org/user-agent','win','width=520,height=640')\">"
          + "window.open &rarr; popup</button>"
          + "<h3>3. target=_blank link</h3>"
          + "<a href='https://example.com' target='_blank'>open example.com</a>"
          + "<script>document.getElementById('ua').textContent="
          + "navigator.userAgent;</script>"
          + "</body></html>";
        String b64 = Base64.getEncoder().encodeToString(
            html.getBytes(StandardCharsets.UTF_8));
        return "data:text/html;charset=utf-8;base64," + b64;
    }

    private WebViewAdoptPopupDemo() { }
}
