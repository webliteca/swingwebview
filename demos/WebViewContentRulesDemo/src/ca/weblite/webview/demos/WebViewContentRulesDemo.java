/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.WebViewContentRuleList;
import ca.weblite.webview.WebViewContentRules;
import ca.weblite.webview.swing.WebViewComponent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Canvas 37 demo: content rule lists.
 *
 * <p>Serves a page from {@code http://localhost:8124/} that loads ads, a
 * first-party script, a third-party script (from {@code 127.0.0.1}), a
 * fetch beacon and three {@code .ad-slot} elements.  A rule list blocks
 * {@code /ads/}, blocks third-party scripts and hides {@code .ad-slot}.
 * The log shows every request the server received.
 *
 * <p>With {@code -Dcrdemo.auto=true} it runs the checks and exits 0 on pass:
 * AC1, AC2, AC3, AC5 (blocked requests never arrive, the rest do, ad slots
 * hidden), AC6 (look-up of the stored list), AC7 (an invalid list fails),
 * AC8 (removing the list and reloading lets {@code /ads/} through).
 */
public class WebViewContentRulesDemo {

    static final int PORT = 8124;
    static final String PAGE = "http://localhost:" + PORT + "/";

    static final String RULES = "["
        + "{\"trigger\":{\"url-filter\":\"/ads/\"},\"action\":{\"type\":\"block\"}},"
        + "{\"trigger\":{\"url-filter\":\".*\",\"resource-type\":[\"script\"],"
        + "\"load-type\":[\"third-party\"]},\"action\":{\"type\":\"block\"}},"
        + "{\"trigger\":{\"url-filter\":\".*\"},"
        + "\"action\":{\"type\":\"css-display-none\",\"selector\":\".ad-slot\"}}"
        + "]";

    static final String HTML = "<!doctype html><html><head><title>Content rules</title></head><body>"
        + "<div class='content' id='content'>Article text.</div>"
        + "<div class='ad-slot'>AD 1</div><div class='ad-slot'>AD 2</div><div class='ad-slot'>AD 3</div>"
        + "<img src='/ads/banner.png'><img id='ok' src='/ok/logo.png'>"
        + "<script src='/ads/tag.js'></script>"
        + "<script src='/first/party.js'></script>"
        + "<script src='http://127.0.0.1:" + PORT + "/third/party.js'></script>"
        // Canvas 37 D9: WebKit rejects a blocked fetch; WebView2 answers it with a
        // 403, so the fetch resolves with ok=false.  Both mean "not sent".
        + "<script>fetch('/ads/beacon').then(function(r){window.beacon=r.ok?'sent':'failed';},"
        + "function(){window.beacon='failed';});</script>"
        + "</body></html>";

    static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
        0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89, 0, 0, 0,
        0x0A, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A,
        0x2D, (byte) 0xB4, 0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 'B', 0x60, (byte) 0x82
    };

    static final List<String> HITS = Collections.synchronizedList(new ArrayList<String>());

    public static void main(String[] args) throws Exception {
        final boolean auto = Boolean.getBoolean("crdemo.auto");
        startServer();
        final File store = Files.createTempDirectory("content-rules").toFile();

        log("supported: " + WebViewContentRules.isSupported());
        if (!WebViewContentRules.isSupported()) {
            log("FAIL: content rule lists are not supported by this native/engine");
            System.exit(auto ? 1 : 0);
        }
        final WebViewContentRuleList list =
            WebViewContentRules.compile(store, "demo-rules", RULES).get(60, TimeUnit.SECONDS);
        log("compiled: " + list);

        final WebViewComponent[] holder = new WebViewComponent[1];
        final JTextArea area = new JTextArea(12, 80);
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("WebView Content Rules Demo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            WebViewComponent wv = WebViewComponent.create();
            wv.addContentRuleList(list);       // before display: governs the first request
            wv.setUrl(PAGE);
            holder[0] = wv;
            JPanel south = new JPanel(new BorderLayout());
            JButton toggle = new JButton("Toggle rules and reload");
            toggle.addActionListener(e -> {
                if (wv.getContentRuleLists().isEmpty()) wv.addContentRuleList(list);
                else wv.removeAllContentRuleLists();
                HITS.clear();
                wv.setUrl(PAGE + "?r=" + System.nanoTime());
            });
            JButton show = new JButton("Show requests");
            show.addActionListener(e -> area.setText(String.join("\n", snapshot())));
            south.add(toggle, BorderLayout.WEST);
            south.add(show, BorderLayout.EAST);
            south.add(new JScrollPane(area), BorderLayout.SOUTH);
            frame.getContentPane().add(wv, BorderLayout.CENTER);
            frame.getContentPane().add(south, BorderLayout.SOUTH);
            frame.setSize(900, 700);
            frame.setVisible(true);
        });
        if (!auto) return;

        boolean pass = true;
        WebViewComponent wv = holder[0];
        Thread.sleep(5000);
        List<String> hits = snapshot();
        log("HITS (rules on): " + hits);
        pass &= check("AC1 /ads/ image and script never requested",
            !contains(hits, "/ads/banner.png") && !contains(hits, "/ads/tag.js"));
        pass &= check("AC1 /ok/ image requested", contains(hits, "/ok/logo.png"));
        pass &= check("AC2 fetch beacon never requested", !contains(hits, "/ads/beacon"));
        pass &= check("AC5 first-party script requested", contains(hits, "/first/party.js"));
        pass &= check("AC5 third-party script never requested", !contains(hits, "/third/party.js"));
        String vis = eval(wv, "var v=0;document.querySelectorAll('.ad-slot').forEach(function(e){"
            + "if(getComputedStyle(e).display!=='none')v++;});"
            + "return v+'|'+getComputedStyle(document.getElementById('content')).display"
            + "+'|'+window.beacon");
        log("visible ad slots|content display|beacon: " + vis);
        pass &= check("AC3 ad slots hidden, content shown", vis != null && vis.startsWith("\"0|block"));
        pass &= check("AC2 fetch failed in the page", vis != null && vis.contains("failed"));

        WebViewContentRuleList again = WebViewContentRules.lookup(store, "demo-rules")
            .get(30, TimeUnit.SECONDS);
        pass &= check("AC6 stored list found by look-up", list.equals(again));
        WebViewContentRuleList missing = WebViewContentRules.lookup(store, "never-compiled")
            .get(30, TimeUnit.SECONDS);
        pass &= check("AC6 look-up of an unknown name answers null", missing == null);

        boolean invalidFailed;
        try {
            WebViewContentRules.compile(store, "bad", "[{\"trigger\":{}}]").get(30, TimeUnit.SECONDS);
            invalidFailed = false;
        } catch (ExecutionException ex) {
            log("invalid list: " + ex.getCause().getMessage());
            invalidFailed = true;
        }
        pass &= check("AC7 invalid list reported", invalidFailed);

        SwingUtilities.invokeAndWait(() -> {
            wv.removeAllContentRuleLists();
            HITS.clear();
            wv.setUrl(PAGE + "?off=1");
        });
        Thread.sleep(4000);
        hits = snapshot();
        log("HITS (rules off): " + hits);
        pass &= check("AC8 after removal /ads/ requests arrive",
            contains(hits, "/ads/banner.png") && contains(hits, "/third/party.js"));

        WebViewContentRules.remove(store, "demo-rules").get(30, TimeUnit.SECONDS);
        pass &= check("remove: look-up then answers null",
            WebViewContentRules.lookup(store, "demo-rules").get(30, TimeUnit.SECONDS) == null);

        log(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static String eval(WebViewComponent wv, String js) {
        try {
            return wv.evalAsync(js).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log("eval failed: " + ex);
            return null;
        }
    }

    static boolean check(String what, boolean ok) {
        log((ok ? "ok   " : "FAIL ") + what);
        return ok;
    }

    static boolean contains(List<String> hits, String path) {
        for (String h : hits) if (h.contains(path)) return true;
        return false;
    }

    static List<String> snapshot() {
        synchronized (HITS) {
            return new ArrayList<>(HITS);
        }
    }

    static void log(String s) {
        System.out.println("[crdemo] " + s);
    }

    static void startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/", (HttpExchange ex) -> {
            String path = ex.getRequestURI().getPath();
            String host = ex.getRequestHeaders().getFirst("Host");
            HITS.add(host + path);
            byte[] body;
            String type;
            if (path.endsWith(".png")) {
                body = PNG;
                type = "image/png";
            } else if (path.endsWith(".js")) {
                body = "/* demo */".getBytes(StandardCharsets.UTF_8);
                type = "application/javascript";
            } else if (path.startsWith("/ads/")) {
                body = "{}".getBytes(StandardCharsets.UTF_8);
                type = "application/json";
            } else {
                body = HTML.getBytes(StandardCharsets.UTF_8);
                type = "text/html; charset=utf-8";
            }
            ex.getResponseHeaders().add("Content-Type", type);
            ex.getResponseHeaders().add("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
    }
}
