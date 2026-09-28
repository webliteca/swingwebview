/*
 * MIT License
 *
 * Chrome-style, user-opt-in autofill demo for WebViewComponent's password
 * manager.
 *
 * Unlike WebViewPasswordDemo (which shows the built-in silent autofill and
 * an optional load-time "Use saved password?" CONFIRM prompt), this demo
 * reproduces the flow a browser like Chrome uses:
 *
 *   1. Nothing is filled automatically on load - the login fields stay
 *      empty (silent autofill is suppressed with a DONT_FILL fill-handler).
 *   2. When the user focuses the username or password field, a dropdown
 *      chooser appears anchored under the field, listing the saved
 *      account(s) for the page's origin (username + a masked password).
 *   3. Picking an account runs a simulated re-authentication ("Unlock
 *      passwords - Touch ID / your login password"), mirroring the OS
 *      re-auth Chrome does before revealing a saved password.
 *   4. Only on approval are the username + password filled in.
 *
 * The whole flow is host-driven and uses the library's PUBLIC API only:
 *   - setFillPasswordHandler(... DONT_FILL) to disable silent autofill,
 *   - addOnBeforeLoad(...) to inject a focus detector,
 *   - addJavascriptCallback(...) to hear about field focus,
 *   - getCredentials(origin) to populate the chooser,
 *   - eval("window.__webview_pw_fill__(b64user, b64pass)") to fill,
 *     reusing the library's own field detection + input/change dispatch.
 *
 * The saved credentials live in an in-memory store seeded at startup, so
 * the demo touches no real OS Keychain and is repeatable.  Passwords are
 * never printed.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.InMemoryCredentialStore;
import ca.weblite.webview.FillPasswordDisposition;
import ca.weblite.webview.WebViewCredential;
import ca.weblite.webview.WebViewFillPasswordEvent;
import ca.weblite.webview.WebViewFillPasswordHandler;
import ca.weblite.webview.WebView;
import ca.weblite.webview.swing.WebViewComponent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JPopupMenu.Separator;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewPasswordOptInDemo {

    /** Bound JS callback name the injected focus detector calls. */
    private static final String FOCUS_CB = "__optin_focus__";

    private static WebViewComponent wv;
    private static JTextArea log;
    private static String origin;                 // http://127.0.0.1:<port>
    private static InMemoryCredentialStore store; // seeded, no real Keychain

    public static void main(String[] args) throws Exception {
        // Heavyweight popups so the chooser + unlock render above the
        // WebView region on macOS / Windows heavyweight.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new LoginHandler());
        server.setExecutor(null);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();

        EventQueue.invokeLater(WebViewPasswordOptInDemo::run);
    }

    private static void run() {
        JFrame frame = new JFrame("WebView Password Manager - Chrome-style opt-in fill");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        wv = WebViewComponent.create();
        wv.setPreferredSize(new Dimension(760, 440));

        // Seed a couple of saved accounts for this origin in an in-memory
        // store (no real Keychain), so the chooser has a multi-account list.
        store = new InMemoryCredentialStore();
        store.save(new WebViewCredential(origin, "alice", "s3cret"));
        store.save(new WebViewCredential(origin, "demo",  "demo-pass"));
        wv.setCredentialStore(store);

        // Opt-in flow: suppress the built-in silent autofill entirely.  The
        // demo drives the fill itself, only after the user picks an account.
        wv.setFillPasswordHandler(new WebViewFillPasswordHandler() {
            @Override public FillPasswordDisposition onAutofillRequested(
                    WebViewFillPasswordEvent e) {
                return FillPasswordDisposition.DONT_FILL;
            }
        });

        // Hear about login-field focus from the page.
        wv.addJavascriptCallback(FOCUS_CB, new WebView.JavascriptCallback() {
            @Override public void run(String wrapped) {
                final String payload = decodeArg(wrapped);
                if (payload.isEmpty()) return;
                SwingUtilities.invokeLater(() -> onFieldFocused(payload));
            }
        });
        // Inject the focus detector at document-start on every load.
        wv.addOnBeforeLoad(FOCUS_JS);

        log = new JTextArea();
        log.setEditable(false);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setPreferredSize(new Dimension(760, 150));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, wv, logScroll);
        split.setResizeWeight(0.75);
        frame.add(split, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        append("Saved accounts for " + origin + ": alice, demo (in-memory).");
        append("Click the username or password field - a chooser appears; "
            + "pick an account and unlock to fill. Nothing fills on its own.");
        wv.setUrl(origin + "/");
    }

    /** A login field was focused; show the account chooser under it. */
    private static void onFieldFocused(String payload) {
        // payload = "left|top|width|height|type"
        String[] p = payload.split("\\|");
        if (p.length < 4) return;
        int left, top, height;
        try {
            left = Integer.parseInt(p[0]);
            top = Integer.parseInt(p[1]);
            height = Integer.parseInt(p[3]);
        } catch (NumberFormatException nfe) {
            return;
        }
        List<WebViewCredential> accounts = wv.getCredentials(origin);
        if (accounts.isEmpty()) {
            append("field focused - no saved accounts for this origin.");
            return;
        }
        append("field focused - showing chooser (" + accounts.size()
            + " account(s)).");

        JPopupMenu chooser = new JPopupMenu();
        JLabel header = new JLabel("  Saved passwords for " + origin + "  ");
        header.setEnabled(false);
        chooser.add(header);
        chooser.add(new Separator());
        for (final WebViewCredential c : accounts) {
            // Username + a masked password - never the real secret.
            JMenuItem item = new JMenuItem(
                c.username() + "    " + mask(c.password().length()));
            item.addActionListener(e -> chooseAccount(c));
            chooser.add(item);
        }
        // Anchor the chooser just under the focused field.  The rect is in
        // CSS px relative to the web viewport, which is the WebView
        // component's content area, so the WebView is the popup invoker.
        if (wv.isShowing()) {
            chooser.show(wv, left, top + height);
        }
    }

    /** The user picked an account; re-authenticate, then fill. */
    private static void chooseAccount(WebViewCredential c) {
        append("selected account: " + c.username());
        // Simulated OS re-auth, mirroring Chrome's Touch ID / password gate.
        int r = JOptionPane.showConfirmDialog(
            SwingUtilities.getWindowAncestor(wv),
            "Unlock passwords to fill the saved password for\n" + origin
                + "\n\n(Simulated Touch ID / login-password check.)",
            "Unlock passwords",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            append("unlock declined - nothing filled.");
            return;
        }
        // Fill via the library's write-only entrypoint (reuses its field
        // detection + input/change dispatch).  Passwords go over as
        // base64url and are never logged.
        String js = "window.__webview_pw_fill__('"
            + b64url(c.username()) + "','" + b64url(c.password()) + "')";
        wv.eval(js);
        append("unlocked - filled " + c.username() + " (password redacted).");
    }

    // ---- helpers ------------------------------------------------------

    private static String mask(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(6, Math.min(n, 12)); i++) sb.append('\u2022');
        return sb.toString();
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extract and base64url-decode the first argument from a bind-shim
     * JSON wrapper of the form {@code {"name":..,"seq":..,"args":["<b64>"]}}.
     * The payload is base64url so it contains no JSON-special characters,
     * making the naive extraction safe.
     */
    private static String decodeArg(String json) {
        if (json == null) return "";
        int i = json.indexOf("\"args\":[");
        String b64;
        if (i < 0) {
            b64 = json.trim(); // some backends deliver the raw arg
        } else {
            int q = json.indexOf('"', i + 8);
            if (q < 0) return "";
            int e = json.indexOf('"', q + 1);
            if (e < 0) return "";
            b64 = json.substring(q + 1, e);
        }
        try {
            return new String(Base64.getUrlDecoder().decode(b64),
                StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static void append(String s) {
        SwingUtilities.invokeLater(() -> {
            log.append(s + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    /** Document-start focus detector: reports login-field focus rects. */
    private static final String FOCUS_JS =
        "(function(){\n"
      + "try{\n"
      + "if(window.__optin_installed__)return; window.__optin_installed__=true;\n"
      + "function b64e(s){try{var u=unescape(encodeURIComponent(s));"
      + "return btoa(u).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');}"
      + "catch(e){return '';}}\n"
      + "function isLogin(el){if(!el||el.tagName!=='INPUT')return false;"
      + "var t=(el.type||'text').toLowerCase();"
      + "return t==='password'||t==='text'||t==='email';}\n"
      + "document.addEventListener('focusin',function(e){try{var el=e.target;"
      + "if(!isLogin(el))return;var r=el.getBoundingClientRect();"
      + "var payload=[Math.round(r.left),Math.round(r.top),Math.round(r.width),"
      + "Math.round(r.height),(el.type||'text')].join('|');"
      + "if(window." + FOCUS_CB + ")window." + FOCUS_CB + "(b64e(payload));}"
      + "catch(e){}},true);\n"
      + "}catch(e){}\n"
      + "})();";

    /** Serves a login form on GET, a landing page on POST. */
    private static final class LoginHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String html;
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getRequestBody().read(new byte[4096]);
                html = "<!doctype html><meta charset=utf-8><title>Signed in</title>"
                    + "<body style='font-family:sans-serif;padding:2rem'>"
                    + "<h2>Signed in.</h2><p><a href='/'>Back to login</a></p>";
            } else {
                html = "<!doctype html><meta charset=utf-8><title>Sign in</title>"
                    + "<body style='font-family:sans-serif;padding:2rem;max-width:22rem'>"
                    + "<h2>Sign in</h2>"
                    + "<form method='post' action='/login'>"
                    + "<p><label>Username<br>"
                    + "<input name='username' autocomplete='username' "
                    + "style='width:100%;padding:.4rem'></label></p>"
                    + "<p><label>Password<br>"
                    + "<input type='password' name='password' "
                    + "autocomplete='current-password' "
                    + "style='width:100%;padding:.4rem'></label></p>"
                    + "<p><button type='submit'>Sign in</button></p>"
                    + "</form>"
                    + "<p style='color:#666;font-size:.85rem'>Click a field to "
                    + "choose a saved password.</p>";
            }
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
