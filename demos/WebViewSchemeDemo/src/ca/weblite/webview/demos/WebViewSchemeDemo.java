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

    /** Canvas 31 D10: the automatic checks, run when the page is loaded with {@code ?auto=1}. */
    private static final String AUTO_JS = ""
        + "if (location.search.indexOf('auto=1') >= 0) (function () {\n"
        + "  var r = {script: 'ok'};\n"
        + "  r.origin = location.origin === 'demo://app' ? 'ok' : 'got ' + location.origin;\n"
        + "  r.secure = window.isSecureContext ? 'ok' : 'isSecureContext is false';\n"
        + "  try { localStorage.setItem('auto', 'v');\n"
        + "        r.storage = localStorage.getItem('auto') === 'v' ? 'ok' : 'read back wrong'; }\n"
        + "  catch (e) { r.storage = 'error ' + e; }\n"
        + "  function step(name, p) {\n"
        + "    return p.then(function (v) { r[name] = v; }, function (e) { r[name] = 'error ' + e; });\n"
        + "  }\n"
        + "  step('post', fetch('demo://app/api/echo', {method: 'POST', body: '{\"n\":3}'}).then(function (x) {\n"
        + "    return x.json().then(function (j) {\n"
        + "      if (x.status !== 200) return 'status ' + x.status;\n"
        + "      if (j.received === null) return 'post-body-unavailable';\n"
        + "      return j.received && j.received.n === 3 ? 'ok' : 'got ' + JSON.stringify(j);\n"
        + "    });\n"
        + "  })).then(function () {\n"
        + "    var ticks = 0, t = setInterval(function () { ticks++; }, 100);\n"
        + "    return step('slow', fetch('demo://app/slow').then(function (x) {\n"
        + "      return x.text().then(function (s) {\n"
        + "        clearInterval(t);\n"
        + "        return s === 'done' && ticks >= 5 ? 'ok' : 'got ' + s + ' after ' + ticks + ' ticks';\n"
        + "      });\n"
        + "    }));\n"
        + "  }).then(function () {\n"
        + "    return step('broken', fetch('demo://app/broken').then(function (x) {\n"
        + "      return x.status === 500 ? 'ok' : 'status ' + x.status;\n"
        + "    }));\n"
        + "  }).then(function () {\n"
        + "    return step('missing', fetch('demo://app/no-such-page').then(function (x) {\n"
        + "      return x.status === 404 ? 'ok' : 'status ' + x.status;\n"
        + "    }));\n"
        + "  }).then(function () {\n"
        + "    log('auto: ' + JSON.stringify(r));\n"
        + "    return fetch('demo://app/api/report?r=' + encodeURIComponent(JSON.stringify(r)));\n"
        + "  }).then(function () {\n"
        + "    fetch('demo://app/slow');\n"
        + "    location.href = 'demo://app/index.html?done=1';\n"
        + "  });\n"
        + "})();\n";

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
        + "document.getElementById('popup').onclick = function () { window.open('demo://app/detail.html'); };\n"
        + AUTO_JS;

    public static void main(String[] args) {
        if (!WebViewSchemes.isSupported()) {
            System.out.println("Custom URL schemes are not available in this version of the native library");
            System.exit(0);
        }
        final boolean auto = Boolean.getBoolean("schemedemo.auto");
        if (auto) {
            exitAfter(60000, 2, "FAIL: no report");
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
                } else if (path.equals("api/report")) {
                    report(reportParameter(request.url()));
                    responder.respond(WebViewSchemeResponse.text(200, "ok"));
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
                wv.setUrl(auto ? "demo://app/index.html?auto=1" : "demo://app/index.html");
            }
        });
    }

    /**
     * Canvas 31 D10: print each check, then PASS or FAIL, and exit 0 or 1 three seconds later so
     * the request the page abandons on its way out has time to reach native.
     */
    private static void report(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"([a-z]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        boolean pass = true;
        int checks = 0;
        while (m.find()) {
            String name = m.group(1);
            String result = m.group(2);
            checks++;
            System.out.println("  " + name + ": " + result);
            boolean ok = result.equals("ok") || (name.equals("post") && result.equals("post-body-unavailable"));
            if (!ok) pass = false;
        }
        if (checks < 8) pass = false;
        String verdict = pass ? "PASS" : "FAIL";
        System.out.println(verdict);
        if (Boolean.getBoolean("schemedemo.auto")) exitAfter(3000, pass ? 0 : 1, null);
    }

    /** The URL-decoded {@code r} query parameter: the page's report (a GET works on every engine). */
    private static String reportParameter(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]r=([^&#]*)").matcher(url);
        if (!m.find()) return "";
        try {
            return java.net.URLDecoder.decode(m.group(1), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    private static void exitAfter(final long ms, final int status, final String message) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) {
                }
                if (message != null) System.out.println(message);
                System.exit(status);
            }
        }, "demo-exit");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
