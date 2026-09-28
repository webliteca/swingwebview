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
import javax.swing.JCheckBox;
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
 *   <li><b>Per-host resolver</b> (Canvas 21, 1.5.0) — tick
 *       <b>Per-host resolver (httpbin)</b> to install a
 *       {@link WebViewComponent#setUserAgentResolver(java.util.function.Function)}
 *       that maps {@code httpbin.org} to a distinctive
 *       {@code SwingWebView-Resolver} UA and declines (returns {@code null})
 *       for every other host.  Now open {@code window.open &rarr; popup}: the
 *       popup echoes the <em>resolver's</em> UA rather than the opener's,
 *       proving a popup child's first request is keyed on the child's own
 *       target URL instead of copied from the opener.  Untick it and the
 *       opener-copy behaviour returns unchanged.</li>
 *   <li><b>Address bar</b> (Canvas 21, 1.5.0) &mdash; a URL field + <b>Go</b>,
 *       plus one-click <b>httpbin (overridden)</b> /
 *       <b>httpbingo (not overridden)</b> / <b>Home</b> buttons.  This is what
 *       makes consultation point <b>(b)</b> &mdash; a Java-initiated
 *       {@code setUrl} on an already-live view &mdash; testable on-device;
 *       previously the opener only ever sat on a {@code data:} URL, which has
 *       no host, so the resolver never fired for it.  With the resolver toggle
 *       <b>on</b>, httpbin echoes {@code SwingWebView-Resolver} while httpbingo
 *       echoes the User-Agent field's value: per-host selection and
 *       fall-through in two clicks, no pop-up involved.  With the toggle
 *       <b>off</b>, both echo the field's value.</li>
 * </ul>
 *
 * <p>The POST/echo checks hit {@code https://httpbin.org/post}, so they need
 * network; the adoption / opener mechanics work offline too.
 */
public final class WebViewAdoptPopupDemo {

    /** A current desktop Safari UA (prefilled into the UA field). */
    /** The host the demo's resolver overrides; its /user-agent echoes back
     *  whatever UA the request carried (Canvas 21, 1.5.0, Op 14.3). */
    private static final String HTTPBIN_UA_URL = "https://httpbin.org/user-agent";
    /** A DIFFERENT host the resolver does NOT override, serving the same
     *  /user-agent JSON shape so the two echoes read side by side. Fallback if
     *  it is unreachable: https://postman-echo.com/get (Canvas 21, Op 14.3). */
    private static final String HTTPBINGO_UA_URL = "https://httpbingo.org/user-agent";

    /** A UA distinct from every preset, so the httpbin echo is unambiguous
     *  about which layer chose it (Canvas 21, 1.5.0). */
    private static final String RESOLVER_UA =
            "Mozilla/5.0 (SwingWebView-Resolver) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

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
        // Canvas 21 Op 14.4: three rows, not one long FlowLayout line. A row
        // wider than the frame silently wraps its trailing components out of
        // view, and in a fixed-height row they become unreachable -- the
        // control then looks absent rather than clipped.
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

        JTextField uaField = new JTextField(SAFARI_UA, 20);
        JButton setUa = new JButton("Set UA");
        setUa.addActionListener(e -> {
            opener.setUserAgent(uaField.getText());
            opener.setUrl(openerPage());  // reload so the UA applies
        });
        JButton resetUa = new JButton("Reset UA");
        resetUa.addActionListener(e -> {
            opener.setUserAgent(null);
            opener.setUrl(openerPage());
        });
        // Canvas 21 (1.5.0): per-destination resolver.  Maps httpbin.org to a
        // distinctive UA while leaving every other host on the static field
        // value above, so the window.open -> https://httpbin.org/user-agent
        // popup echoes the RESOLVER's UA rather than the opener's -- the
        // on-device proof that a popup child's first request is keyed on the
        // child's own target URL, not copied from its opener.
        JCheckBox resolverBox = new JCheckBox("Per-host resolver (httpbin)");
        resolverBox.setToolTipText(
                "Resolve httpbin.org to " + RESOLVER_UA + "; other hosts fall "
                + "through to the User-Agent field.");
        resolverBox.addActionListener(e -> {
            opener.setUserAgentResolver(
                resolverBox.isSelected() ? WebViewAdoptPopupDemo::resolveUa : null);
            System.out.println("[demo] resolver "
                + (resolverBox.isSelected() ? "INSTALLED" : "cleared"));
            opener.setUrl(openerPage());
        });
        bar.add(resolverBox);

        JButton clearCache = new JButton("Clear cache");
        clearCache.addActionListener(e -> {
            // Canvas 22: purge the HTTP resource cache, then reload so the
            // page re-fetches from the network (cookies/login are retained).
            opener.clearCache();
            opener.eval("location.reload()");
        });
        bar.add(clearCache);

        // Row 2: the User-Agent field takes the slack, its buttons pin right,
        // so the row's width never depends on the field's preferred size.
        JPanel uaRow = fieldRow("User-Agent:", uaField, setUa, resetUa);

        // Canvas 21 Op 14.3: the address row is what makes consultation point
        // (b) -- a Java-initiated setUrl on an already-live view -- testable
        // on-device. Without it the opener only ever sat on a data: URL, which
        // has no host, so the resolver never fired for it.
        JPanel stack = new JPanel(new java.awt.GridLayout(0, 1));
        stack.add(bar);
        stack.add(uaRow);
        stack.add(buildAddressRow(opener));
        return stack;
    }

    /**
     * One control row: a label, a text field that absorbs all slack, and
     * buttons pinned to the trailing edge (Canvas 21 Op 14.4). Because the
     * field is the only stretchy part, the row fits any window width and no
     * button can wrap out of view.
     */
    private static JPanel fieldRow(String label, JTextField field, JButton... buttons) {
        JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
        row.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        row.add(new JLabel(label), java.awt.BorderLayout.WEST);
        row.add(field, java.awt.BorderLayout.CENTER);
        JPanel right = new JPanel(new java.awt.FlowLayout(
            java.awt.FlowLayout.LEFT, 6, 0));
        for (JButton b : buttons) right.add(b);
        row.add(right, java.awt.BorderLayout.EAST);
        return row;
    }

    /**
     * The address row: a URL field + Go, plus one-click destinations that make
     * the per-host contrast a two-click test (Canvas 21 Op 14.3).
     *
     * <p>With the <b>Per-host resolver</b> toggle on, <b>httpbin (overridden)</b>
     * must echo {@link #RESOLVER_UA} and <b>httpbingo (not overridden)</b> must
     * echo the User-Agent field's value — per-host selection and fall-through,
     * on one engine, with no pop-up involved. With the toggle off, both echo the
     * field's value.
     */
    /**
     * The demo's per-destination resolver: {@code httpbin.org} gets
     * {@link #RESOLVER_UA}, every other host is declined so the static
     * {@code setUserAgent} value applies.
     *
     * <p>Prints every decision (Canvas 21 Op 14.3). Paired with the Windows
     * setter's {@code WV_LOG} line, one run says unambiguously whether a wrong
     * User-Agent came from this resolution chain or from an engine that ignored
     * what it resolved.
     */
    private static String resolveUa(String url) {
        boolean match = url != null && url.contains("httpbin.org");
        System.out.println("[demo] resolver(" + url + ") -> "
            + (match ? "RESOLVER_UA" : "(decline -> static UA)"));
        return match ? RESOLVER_UA : null;
    }

    private static JPanel buildAddressRow(WebViewComponent opener) {
        JTextField urlField = new JTextField(HTTPBIN_UA_URL, 20);

        JButton go = new JButton("Go");
        go.addActionListener(e -> {
            String url = urlField.getText().trim();
            if (!url.isEmpty()) opener.setUrl(url);
        });
        // Enter in the field navigates too.
        urlField.addActionListener(e -> {
            String url = urlField.getText().trim();
            if (!url.isEmpty()) opener.setUrl(url);
        });

        JButton httpbin = new JButton("httpbin (overridden)");
        httpbin.setToolTipText(
            "Resolver maps this host to " + RESOLVER_UA + " when the toggle is on.");
        httpbin.addActionListener(e -> {
            urlField.setText(HTTPBIN_UA_URL);
            opener.setUrl(HTTPBIN_UA_URL);
        });
        JButton httpbingo = new JButton("httpbingo (not overridden)");
        httpbingo.setToolTipText(
            "A different host the resolver declines, so it falls through to the "
            + "User-Agent field. Same /user-agent JSON shape as httpbin.");
        httpbingo.addActionListener(e -> {
            urlField.setText(HTTPBINGO_UA_URL);
            opener.setUrl(HTTPBINGO_UA_URL);
        });
        JButton home = new JButton("Home");
        home.setToolTipText("Back to the opener page, for the pop-up tests.");
        home.addActionListener(e -> {
            urlField.setText("");
            opener.setUrl(openerPage());
        });
        return fieldRow("URL:", urlField, go, httpbin, httpbingo, home);
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
