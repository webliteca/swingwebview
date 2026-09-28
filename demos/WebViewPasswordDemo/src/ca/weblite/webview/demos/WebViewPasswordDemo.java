/*
 * MIT License
 *
 * Exercises the built-in password manager on WebViewComponent.  Designed
 * for manual verification against the acceptance criteria in
 *   requirements/[User-story-6]webview-native-password-manager.md
 *
 * A tiny embedded HTTP server serves a login form at a real origin
 * (http://127.0.0.1:<port>) so the manager's origin-keyed capture and
 * autofill actually fire — a data:/about:blank page has no origin and
 * would be skipped.
 *
 *   1. Type a username + password and submit  -> "Save password?" prompt.
 *   2. Reload (the "Reload" button)            -> fields auto-fill.
 *   3. Use the Save / Get / Delete buttons     -> programmatic API.
 *   4. Toggle "Enabled" and the store combo    -> gating + store swap.
 *   5. "Get All"                               -> enumerate every stored
 *                                                 credential (006-005).
 *   6. "Confirm before autofill"               -> browser-style consent
 *                                                 prompt before fill (006-004).
 *
 * Coverage: macOS (Keychain), Windows (Credential Manager), and Linux
 * (libsecret / freedesktop Secret Service) are all wired for automatic
 * capture/fill.  On a Linux session with no Secret Service provider the
 * store degrades gracefully (nothing persists).
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.InMemoryCredentialStore;
import ca.weblite.webview.WebViewCredential;
import ca.weblite.webview.WebViewFillPasswordHandler;
import ca.weblite.webview.swing.WebViewComponent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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

public class WebViewPasswordDemo {

    private static final String STORE_NATIVE = "Native (OS Keychain)";
    private static final String STORE_MEMORY = "In-memory (test store)";

    private static WebViewComponent wv;
    private static JTextArea log;
    private static String origin; // http://127.0.0.1:<port>

    public static void main(String[] args) throws Exception {
        // Heavyweight popups so the "Save password?" JOptionPane renders
        // above the WebView region on macOS / Windows heavyweight.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new LoginHandler());
        // Canvas 26 4a: a two-step login, the shape the username-first flow exists for.
        server.createContext("/two-step", new TwoStepHandler());
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();
        origin = "http://127.0.0.1:" + port;

        EventQueue.invokeLater(() -> run(port));
    }

    private static void run(int port) {
        JFrame frame = new JFrame("WebView Password Manager Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        wv = WebViewComponent.create();
        wv.setPreferredSize(new Dimension(760, 420));
        wv.addConsoleListener(new ConsoleListener() {
            @Override public void onMessage(ConsoleMessage m) {
                append("[console] " + m.getText());
            }
        });

        log = new JTextArea();
        log.setEditable(false);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setPreferredSize(new Dimension(760, 160));

        JComboBox<String> storeCombo =
            new JComboBox<>(new String[] { STORE_NATIVE, STORE_MEMORY });
        storeCombo.addActionListener(e -> {
            Object sel = storeCombo.getSelectedItem();
            if (STORE_MEMORY.equals(sel)) {
                wv.setCredentialStore(new InMemoryCredentialStore());
                append("store -> in-memory");
            } else {
                wv.setCredentialStore(null); // restore default NativeCredentialStore
                append("store -> native (OS Keychain)");
            }
        });

        JCheckBox enabled = new JCheckBox("Enabled", true);
        enabled.addActionListener(e -> {
            wv.setPasswordManagerEnabled(enabled.isSelected());
            append("manager enabled = " + enabled.isSelected());
        });

        JButton reload = new JButton("Reload");
        reload.addActionListener(e -> wv.setUrl(origin + "/"));

        JButton save = new JButton("Save (demo/demo)");
        save.addActionListener(e -> {
            wv.saveCredential(new WebViewCredential(origin, "demo", "demo-pass"));
            append("saveCredential(demo) -> stored");
        });

        JButton get = new JButton("Get");
        get.addActionListener(e -> {
            Optional<WebViewCredential> c = wv.getCredential(origin);
            append("getCredential(" + origin + ") -> "
                + (c.isPresent() ? "username=" + c.get().username()
                                   + " (password redacted)" : "<none>"));
            List<WebViewCredential> all = wv.getCredentials(origin);
            append("  getCredentials count = " + all.size());
        });

        JButton delete = new JButton("Delete demo");
        delete.addActionListener(e ->
            append("deleteCredential(demo) -> " + wv.deleteCredential(origin, "demo")));

        // STORY-006-005: enumerate every stored credential across all origins.
        JButton getAll = new JButton("Get All");
        getAll.addActionListener(e -> {
            List<WebViewCredential> all = wv.getAllCredentials();
            append("getAllCredentials() -> " + all.size() + " credential(s):");
            for (WebViewCredential c : all) {
                // origin + username only — never the password.
                append("  " + c.origin() + "  (username=" + c.username() + ")");
            }
        });

        // STORY-006-004: opt into a browser-style confirm before autofill.
        JCheckBox confirmFill = new JCheckBox("Confirm before autofill", false);
        confirmFill.addActionListener(e -> {
            wv.setFillPasswordHandler(confirmFill.isSelected()
                ? WebViewFillPasswordHandler.CONFIRM : null);
            append("autofill consent = "
                + (confirmFill.isSelected() ? "CONFIRM" : "DEFAULT (silent)"));
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Store:"));
        controls.add(storeCombo);
        controls.add(enabled);
        controls.add(confirmFill);
        controls.add(reload);
        controls.add(save);
        controls.add(get);
        controls.add(getAll);
        controls.add(delete);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, wv, logScroll);
        split.setResizeWeight(0.72);

        frame.add(controls, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        append("Serving login form at " + origin + "/");
        append("Submit the form to trigger the Save prompt; Reload to autofill.");
        append("Open " + origin + "/two-step for the two-step login: the user name is");
        append("typed on the first page, the password on the second, and the Save prompt");
        append("should still name the user (Canvas 26 4a).");
        wv.setUrl(origin + "/");
    }

    private static void append(String s) {
        SwingUtilities.invokeLater(() -> {
            log.append(s + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    /** Serves a login form on GET and a confirmation page on POST. */
    private static final class LoginHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String html;
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                // Drain the body; the demo does not validate credentials.
                ex.getRequestBody().read(new byte[4096]);
                html = "<!doctype html><meta charset=utf-8><title>Logged in</title>"
                    + "<body style='font-family:sans-serif;padding:2rem'>"
                    + "<h2>Logged in.</h2><p><a href='/'>Back to login</a></p>"
                    + "<script>console.log('submitted (password captured by the manager)');</script>";
            } else {
                html = "<!doctype html><meta charset=utf-8><title>Login</title>"
                    + "<body style='font-family:sans-serif;padding:2rem'>"
                    + "<h2>Sign in</h2>"
                    + "<form method='post' action='/login'>"
                    + "<p><label>Username<br><input name='username' autocomplete='username'></label></p>"
                    + "<p><label>Password<br><input type='password' name='password' autocomplete='current-password'></label></p>"
                    + "<p><button type='submit'>Sign in</button></p>"
                    + "</form>"
                    + "<script>document.querySelector('input[type=password]')"
                    + ".addEventListener('input',function(){console.log('password field input event');});</script>";
            }
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    /**
     * A two-step login (Canvas 26 4a): the user name on one page, the password on
     * the next, with no user name field anywhere near the password. This is what
     * Google, Microsoft and Okta do, and without the username-first flow the Save
     * prompt has no name to offer. The two pages are separate documents on one
     * origin, so the candidate has to survive a real navigation.
     */
    private static final class TwoStepHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String html;
            if (path.endsWith("/done")) {
                ex.getRequestBody().read(new byte[4096]);
                html = "<!doctype html><meta charset=utf-8><title>Logged in</title>"
                    + "<body style='font-family:sans-serif;padding:2rem'>"
                    + "<h2>Logged in.</h2>"
                    + "<p>The Save prompt should have named the user you typed on step 1.</p>"
                    + "<p><a href='/two-step'>Start over</a></p>";
            } else if (path.endsWith("/password")) {
                // Step 2: a password and nothing else -- exactly the Okta shape. The
                // user name is shown as text, which no password manager can read.
                html = "<!doctype html><meta charset=utf-8><title>Verify with your password</title>"
                    + "<body style='font-family:sans-serif;padding:2rem'>"
                    + "<h2>Verify with your password</h2>"
                    + "<p><span id='who' style='color:#555'></span></p>"
                    + "<form method='post' action='/two-step/done'>"
                    + "<p><label>Password<br><input type='password' name='password' "
                    + "autocomplete='current-password'></label></p>"
                    + "<p><button type='submit'>Verify</button></p>"
                    + "</form>"
                    + "<script>try{document.getElementById('who').textContent="
                    + "new URLSearchParams(location.search).get('u')||'';}catch(e){}</script>";
            } else {
                // Step 1: a user name and nothing else.
                html = "<!doctype html><meta charset=utf-8><title>Sign in</title>"
                    + "<body style='font-family:sans-serif;padding:2rem'>"
                    + "<h2>Sign in</h2>"
                    + "<form method='get' action='/two-step/password'>"
                    + "<p><label>Username<br><input name='u' autocomplete='username'></label></p>"
                    + "<p><button type='submit'>Next</button></p>"
                    + "</form>";
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
