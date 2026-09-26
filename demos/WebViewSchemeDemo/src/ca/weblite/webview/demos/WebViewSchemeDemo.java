package ca.weblite.webview.demos;

import ca.weblite.webview.WebViewSchemeHandler;
import ca.weblite.webview.WebViewSchemeRequest;
import ca.weblite.webview.WebViewSchemeResponder;
import ca.weblite.webview.WebViewSchemeResponse;
import ca.weblite.webview.WebViewSchemes;
import ca.weblite.webview.swing.WebViewComponent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.nio.charset.StandardCharsets;

/**
 * Demo for custom URL schemes (Canvas 30, STORY-008-001).
 *
 * <p>Registers {@code demo} before any WebView exists and serves a two-file page from Java:
 * {@code index.html} and {@code app.js}, plus {@code api/echo} (echoes a POST body as JSON),
 * {@code slow} (answers after 2 s, from its own thread) and {@code broken} (throws, so the page
 * gets a 500). The page reports {@code isSecureContext}, {@code location.origin} and a
 * {@code localStorage} round trip.
 */
public class WebViewSchemeDemo {

    private static final String INDEX = "<!doctype html>\n"
        + "<html><head><meta charset=\"utf-8\"><title>demo://app</title>\n"
        + "<style>body{font:15px system-ui,sans-serif;margin:24px}pre{background:#f3f3f3;padding:12px}"
        + "button{margin-right:8px}</style></head>\n"
        + "<body><h1>Hello</h1>\n"
        + "<p>This page was served from Java through the <code>demo</code> scheme.</p>\n"
        + "<button id=\"post\">POST {\"n\":3}</button>"
        + "<button id=\"slow\">Slow (2 s)</button>"
        + "<button id=\"broken\">Broken</button>"
        + "<button id=\"popup\">Open popup</button>\n"
        + "<pre id=\"out\"></pre>\n"
        + "<script src=\"app.js\"></script></body></html>\n";

    private static final String DETAIL = "<!doctype html><html><head><meta charset=\"utf-8\"></head>"
        + "<body><h1>Detail</h1><p>Served for demo://app/detail.html.</p></body></html>\n";

    private static final String APP_JS = ""
        + "var out = document.getElementById('out');\n"
        + "function log(s) { out.textContent += s + '\\n'; }\n"
        + "log('script ran: yes');\n"
        + "log('location.origin: ' + location.origin);\n"
        + "log('isSecureContext: ' + window.isSecureContext);\n"
        + "try { localStorage.setItem('demo', 'kept'); log('localStorage: ' + localStorage.getItem('demo')); }\n"
        + "catch (e) { log('localStorage failed: ' + e); }\n"
        + "document.getElementById('post').onclick = function () {\n"
        + "  fetch('demo://app/api/echo', {method: 'POST', body: '{\"n\":3}'})\n"
        + "    .then(function (r) { return r.text().then(function (t) { log('POST ' + r.status + ' ' + t); }); })\n"
        + "    .catch(function (e) { log('POST failed: ' + e); });\n"
        + "};\n"
        + "document.getElementById('slow').onclick = function () {\n"
        + "  var t0 = Date.now();\n"
        + "  fetch('demo://app/slow').then(function (r) { return r.text(); })\n"
        + "    .then(function (t) { log('slow: ' + t + ' after ' + (Date.now() - t0) + ' ms'); });\n"
        + "};\n"
        + "document.getElementById('broken').onclick = function () {\n"
        + "  fetch('demo://app/broken').then(function (r) { log('broken: status ' + r.status); });\n"
        + "};\n"
        + "document.getElementById('popup').onclick = function () { window.open('demo://app/detail.html'); };\n";

    public static void main(String[] args) {
        if (!WebViewSchemes.isSupported()) {
            System.out.println("Custom URL schemes are not available in this version of the native library");
            System.exit(0);
        }
        WebViewSchemes.register("demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, final WebViewSchemeResponder responder) {
                System.out.println("[demo] " + request);
                String path = request.url().replaceFirst("^demo://app/?", "").replaceFirst("[?#].*$", "");
                if (path.isEmpty() || path.equals("index.html")) {
                    responder.respond(WebViewSchemeResponse.ok("text/html; charset=utf-8", utf8(INDEX)));
                } else if (path.equals("detail.html")) {
                    responder.respond(WebViewSchemeResponse.ok("text/html; charset=utf-8", utf8(DETAIL)));
                } else if (path.equals("app.js")) {
                    responder.respond(WebViewSchemeResponse.ok("text/javascript; charset=utf-8", utf8(APP_JS)));
                } else if (path.equals("api/echo")) {
                    String body = new String(request.body(), StandardCharsets.UTF_8);
                    String json = "{\"method\":\"" + request.method() + "\",\"received\":" + (body.isEmpty() ? "null" : body) + "}";
                    responder.respond(WebViewSchemeResponse.ok("application/json", utf8(json)));
                } else if (path.equals("slow")) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ignored) {
                            }
                            responder.respond(WebViewSchemeResponse.text(200, "done"));
                        }
                    }, "demo-slow").start();
                } else if (path.equals("broken")) {
                    throw new IllegalStateException("This handler always fails.");
                } else {
                    responder.respond(WebViewSchemeResponse.text(404, "No such page: " + path));
                }
            }
        });
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JFrame frame = new JFrame("WebView Scheme Demo");
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.setSize(900, 640);
                frame.setLocationRelativeTo(null);
                WebViewComponent wv = WebViewComponent.create();
                frame.add(wv, BorderLayout.CENTER);
                frame.setVisible(true);
                wv.setUrl("demo://app/index.html");
            }
        });
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
