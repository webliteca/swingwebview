package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewComponent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Demo for {@link WebViewComponent#getCookies(String)} (Canvas 6, Operation 16).
 *
 * <p>Starts a loopback HTTP server whose page sets four cookies:
 * <ul>
 *   <li>{@code swv_http} -- HttpOnly, so {@code document.cookie} cannot see it;</li>
 *   <li>{@code swv_js} -- an ordinary script-visible cookie;</li>
 *   <li>{@code swv_path} -- restricted to {@code Path=/private};</li>
 *   <li>{@code swv_secure} -- {@code Secure}, so never sent over plain http.</li>
 * </ul>
 * Once the page reports it has loaded (with its {@code document.cookie}), the demo queries the
 * native cookie store for several URLs and prints PASS/FAIL per check.  Only cookie names are
 * printed, never values.
 *
 * <p>{@code -Dcookiedemo.auto=true} exits 0 when every check passes, 1 otherwise.
 */
public class WebViewCookieDemo {

    private static final String PAGE = "<!doctype html>\n"
        + "<html><head><meta charset=\"utf-8\"><title>Cookie demo</title>\n"
        + "<style>body{font:15px system-ui,sans-serif;margin:24px}pre{background:#f3f3f3;padding:12px}</style>"
        + "</head><body><h1>getCookies demo</h1>\n"
        + "<p>This page set four cookies. <code>document.cookie</code> sees:</p>\n"
        + "<pre id=\"dc\"></pre>\n"
        + "<p>Results of the native <code>getCookies</code> checks are printed on the console.</p>\n"
        + "<script>\n"
        + "document.getElementById('dc').textContent = document.cookie || '(empty)';\n"
        + "fetch('/loaded?dc=' + encodeURIComponent(document.cookie));\n"
        + "</script></body></html>\n";

    private static volatile boolean auto;
    private static volatile WebViewComponent webView;
    private static int port;

    public static void main(String[] args) throws IOException {
        auto = Boolean.getBoolean("cookiedemo.auto");
        if (auto) {
            exitAfter(60000, 2, "FAIL: page never reported it had loaded");
        }
        List<HttpServer> servers = startServers();
        System.out.println("[cookie-demo] serving http://localhost:" + port + "/ ("
            + servers.size() + " loopback listener(s))");

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JFrame frame = new JFrame("WebView Cookie Demo");
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.setSize(900, 640);
                frame.setLocationRelativeTo(null);
                final WebViewComponent wv = WebViewComponent.create();
                webView = wv;
                frame.add(wv, BorderLayout.CENTER);
                JButton rerun = new JButton("Run getCookies checks again");
                rerun.addActionListener(e -> runChecks(null));
                frame.add(rerun, BorderLayout.SOUTH);
                frame.setVisible(true);
                wv.setUrl(base() + "/");
            }
        });
    }

    private static String base() {
        return "http://localhost:" + port;
    }

    /**
     * Binds 127.0.0.1 on an ephemeral port, then ::1 on the same port when IPv6 loopback is
     * available, so "localhost" works whichever address the engine resolves first.  Nothing is
     * exposed beyond loopback.
     */
    private static List<HttpServer> startServers() throws IOException {
        List<HttpServer> servers = new ArrayList<HttpServer>();
        HttpServer v4 = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        port = v4.getAddress().getPort();
        servers.add(v4);
        try {
            servers.add(HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("::1"), port), 0));
        } catch (IOException ignored) {
            // No IPv6 loopback; 127.0.0.1 alone is fine.
        }
        for (HttpServer s : servers) {
            s.createContext("/", new Handler());
            s.start();
        }
        return servers;
    }

    private static final class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            System.out.println("[cookie-demo] " + ex.getRequestMethod() + " " + path);
            if (path.equals("/")) {
                ex.getResponseHeaders().add("Set-Cookie", "swv_http=h1; Path=/; HttpOnly");
                ex.getResponseHeaders().add("Set-Cookie", "swv_js=j1; Path=/");
                ex.getResponseHeaders().add("Set-Cookie", "swv_path=p1; Path=/private");
                ex.getResponseHeaders().add("Set-Cookie", "swv_secure=s1; Path=/; Secure");
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                send(ex, 200, "text/html; charset=utf-8", PAGE);
            } else if (path.equals("/loaded")) {
                String query = ex.getRequestURI().getRawQuery();
                String dc = "";
                if (query != null && query.startsWith("dc=")) {
                    dc = URLDecoder.decode(query.substring(3), "UTF-8");
                }
                send(ex, 204, null, null);
                final String documentCookie = dc;
                SwingUtilities.invokeLater(() -> runChecks(documentCookie));
            } else {
                send(ex, 404, "text/plain", "not found");
            }
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body)
            throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // -----------------------------------------------------------------------
    // Checks
    // -----------------------------------------------------------------------

    private static final class Result {
        final List<String> lines = new ArrayList<String>();
        boolean pass = true;

        void check(String name, boolean ok, String detail) {
            lines.add("  " + (ok ? "PASS" : "FAIL") + "  " + name + "  [" + detail + "]");
            if (!ok) pass = false;
        }

        void info(String name, String detail) {
            lines.add("  INFO  " + name + "  [" + detail + "]");
        }
    }

    /** Runs every getCookies check on the EDT; documentCookie is null on a manual re-run. */
    private static void runChecks(final String documentCookie) {
        final WebViewComponent wv = webView;
        if (wv == null) return;
        final String page = base() + "/";
        final String priv = base() + "/private/area";
        final String secure = "https://localhost:" + port + "/";
        final String sub = "http://sub.localhost:" + port + "/";
        final String other = "http://unrelated.invalid/";

        final Result r = new Result();
        System.out.println("[cookie-demo] running getCookies checks ...");

        if (documentCookie != null) {
            Set<String> dc = names(documentCookie);
            r.check("document.cookie hides HttpOnly", !dc.contains("swv_http"),
                "document.cookie names " + dc);
            r.check("document.cookie sees swv_js", dc.contains("swv_js"),
                "document.cookie names " + dc);
        }

        query(wv, page, r, (h, n) -> {
            r.check("HttpOnly cookie returned for " + page, n.contains("swv_http"), "names " + n);
            r.check("script cookie returned for " + page, n.contains("swv_js"), "names " + n);
            r.check("Path=/private cookie excluded for " + page, !n.contains("swv_path"), "names " + n);
            r.check("Secure cookie excluded for http", !n.contains("swv_secure"), "names " + n);
            r.check("header syntax is name=value; ...", h.isEmpty() || h.matches("[^;=]+=[^;]*(; [^;=]+=[^;]*)*"),
                n.size() + " pair(s)");
            return null;
        }).thenCompose(v -> query(wv, priv, r, (h, n) -> {
            r.check("Path=/private cookie returned for " + priv, n.contains("swv_path"), "names " + n);
            List<String> order = orderedNames(h);
            int ip = order.indexOf("swv_path");
            int ij = order.indexOf("swv_js");
            if (ip >= 0 && ij >= 0) {
                r.check("longer-path cookie listed first", ip < ij, "order " + order);
            }
            return null;
        })).thenCompose(v -> query(wv, secure, r, (h, n) -> {
            // Engines differ on whether a Secure cookie set over http://localhost is stored at
            // all, so this is informational only.
            r.info("Secure cookie for " + secure, n.contains("swv_secure")
                ? "returned" : "not stored by this engine (acceptable)");
            return null;
        })).thenCompose(v -> query(wv, sub, r, (h, n) -> {
            r.check("host-only cookies excluded for " + sub,
                !n.contains("swv_http") && !n.contains("swv_js"), "names " + n);
            return null;
        })).thenCompose(v -> query(wv, other, r, (h, n) -> {
            r.check("unrelated host returns empty", h.isEmpty(), n.size() + " cookie(s)");
            return null;
        })).whenComplete((v, err) -> {
            if (err != null) {
                r.check("getCookies completed", false, rootMessage(err));
            }
            for (String line : r.lines) System.out.println(line);
            System.out.println(r.pass ? "PASS" : "FAIL");
            if (auto) exitAfter(1000, r.pass ? 0 : 1, null);
        });
    }

    /** One getCookies call; asserts it completes on the EDT, then hands names to the checks. */
    private static CompletableFuture<Void> query(WebViewComponent wv, final String url,
                                                 final Result r,
                                                 final BiFunction<String, Set<String>, Void> checks) {
        return wv.getCookies(url).thenAccept(header -> {
            r.check("completes on EDT (" + url + ")", SwingUtilities.isEventDispatchThread(),
                Thread.currentThread().getName());
            checks.apply(header, names(header));
        });
    }

    private static Set<String> names(String header) {
        return new LinkedHashSet<String>(orderedNames(header));
    }

    private static List<String> orderedNames(String header) {
        List<String> out = new ArrayList<String>();
        if (header == null) return out;
        for (String pair : header.split(";")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq > 0) out.add(p.substring(0, eq));
        }
        return out;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
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
}
