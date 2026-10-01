package ca.weblite.webview.demos;

import ca.weblite.webview.NavigationCause;
import ca.weblite.webview.PopupDisposition;
import ca.weblite.webview.WebViewNavigationEvent;
import ca.weblite.webview.WebViewNavigationHandler;
import ca.weblite.webview.WebViewPopupEvent;
import ca.weblite.webview.WebViewPopupHandler;
import ca.weblite.webview.WebViewSchemeHandler;
import ca.weblite.webview.WebViewSchemeRequest;
import ca.weblite.webview.WebViewSchemeResponder;
import ca.weblite.webview.WebViewSchemeResponse;
import ca.weblite.webview.WebViewSchemes;
import ca.weblite.webview.swing.WebViewComponent;
import com.sun.net.httpserver.HttpServer;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demo for navigation decisions (Canvas 34, STORY-010-001).
 *
 * <p>Serves {@code demo://app/} from a custom scheme and runs a local listener that stands for "the
 * internet". The navigation handler allows {@code demo://app/…}, {@code about:blank},
 * {@code about:srcdoc} and the application's own navigations, and refuses everything else; the page's
 * buttons try each way a page can leave. In auto mode ({@code -Dnavdemo.auto=true}) the demo drives
 * every acceptance criterion itself, prints each check, then PASS or FAIL, and exits 0 or 1.
 */
public class WebViewNavigationDemo {

    static final List<WebViewNavigationEvent> EVENTS = Collections.synchronizedList(new ArrayList<WebViewNavigationEvent>());
    static final AtomicInteger HITS = new AtomicInteger();
    static final List<String> HIT_PATHS = Collections.synchronizedList(new ArrayList<String>());
    static final AtomicReference<WebViewComponent> ADOPTED = new AtomicReference<WebViewComponent>();
    static String listener;

    /** Allows the demo's own pages and the application's own navigations; refuses the rest. */
    static final WebViewNavigationHandler GUARD = new WebViewNavigationHandler() {
        @Override
        public boolean navigationRequested(WebViewNavigationEvent e) {
            EVENTS.add(e);
            System.out.println("[nav] " + e);
            if (e.url().contains("throw")) throw new IllegalStateException("This handler always fails here.");
            return e.applicationInitiated() || e.url().startsWith("demo://app/")
                || e.url().equals("about:blank") || e.url().equals("about:srcdoc");
        }
    };

    private static String index() {
        return "<!doctype html>\n"
            + "<html><head><meta charset=\"utf-8\"><title>demo://app</title>\n"
            + "<style>body{font:15px system-ui,sans-serif;margin:24px}button,a{margin:4px}</style></head>\n"
            + "<body><h1 id=\"h\">A page that can't leave</h1>\n"
            + "<p>Every button tries to send <code>secret</code> to " + listener + ".</p>\n"
            + "<a id=\"link\" href=\"" + listener + "/link?d=secret\">A link</a>\n"
            + "<a id=\"dl\" download href=\"" + listener + "/download?d=secret\">A download link</a>\n"
            + "<a id=\"ok\" href=\"demo://app/next.html\">An allowed link</a>\n"
            + "<form id=\"form\" action=\"" + listener + "/form\" method=\"post\" target=\"_top\">"
            + "<input name=\"d\" value=\"secret\"><button>Post a form</button></form>\n"
            + "<button onclick=\"location.href='" + listener + "/href?d=secret'\">location.href</button>\n"
            + "<button onclick=\"location.assign('" + listener + "/assign?d=secret')\">location.assign</button>\n"
            + "<button onclick=\"location.replace('" + listener + "/replace?d=secret')\">location.replace</button>\n"
            + "<button onclick=\"meta()\">Meta refresh</button>\n"
            + "<button onclick=\"frame()\">A frame that navigates</button>\n"
            + "<button onclick=\"window.open('demo://app/popup.html')\">A popup that navigates</button>\n"
            + "<script>\n"
            + "function meta(){var m=document.createElement('meta');m.httpEquiv='refresh';"
            + "m.content='0;url=" + listener + "/meta?d=secret';document.head.appendChild(m);}\n"
            + "function frame(){var f=document.createElement('iframe');f.id='fr';"
            + "f.srcdoc=\"<p>frame</p><script>setTimeout(function(){location.href='" + listener
            + "/frame?d=secret'},200)<\\/script>\";document.body.appendChild(f);}\n"
            + "</script></body></html>\n";
    }

    private static String page(String title, String script) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + title + "</title></head>"
            + "<body><h1 id=\"h\">" + title + "</h1>" + (script == null ? "" : "<script>" + script + "</script>")
            + "</body></html>\n";
    }

    public static void main(String[] args) throws Exception {
        System.out.println("isNavigationHandlerSupported: " + WebViewComponent.isNavigationHandlerSupported());   // AC11
        final boolean auto = Boolean.getBoolean("navdemo.auto");
        if (!WebViewComponent.isNavigationHandlerSupported()) {
            System.out.println("Navigation decisions are not available in this version of the native library");
            if (auto) System.out.println("FAIL");
            System.exit(auto ? 1 : 0);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            HITS.incrementAndGet();
            HIT_PATHS.add(exchange.getRequestURI().toString());
            System.out.println("[listener] " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] b = "<h1 id=h>The listener</h1>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        listener = "http://127.0.0.1:" + server.getAddress().getPort();

        WebViewSchemes.register("demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                String path = request.url().replaceFirst("^demo://app/?", "").replaceFirst("[?#].*$", "");
                String html;
                if (path.isEmpty() || path.equals("index.html")) html = index();
                else if (path.equals("next.html")) html = page("Next", null);
                else if (path.equals("popup.html")) {
                    html = page("Popup", "setTimeout(function(){location.href='" + listener + "/popup?d=secret'},500)");
                } else {
                    responder.respond(WebViewSchemeResponse.text(404, "No such page: " + path));
                    return;
                }
                responder.respond(WebViewSchemeResponse.ok("text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8)));
            }
        });

        final WebViewComponent[] wv = new WebViewComponent[1];
        final JPanel[] popups = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("WebView Navigation Demo");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setSize(1000, 700);
            frame.setLocationRelativeTo(null);
            JPanel grid = new JPanel(new GridLayout(1, 2));
            wv[0] = WebViewComponent.create();
            wv[0].setNavigationHandler(GUARD);
            // AC9: popups are adopted into a component with the same guard.
            wv[0].setPopupHandler(new WebViewPopupHandler() {
                @Override public PopupDisposition popupDisposition(WebViewPopupEvent e) {
                    return PopupDisposition.ADOPT;
                }
                @Override public void popupAdoptable(WebViewPopupEvent e, long popupId) {
                    WebViewComponent child = WebViewComponent.adoptPopup(popupId);
                    child.setNavigationHandler(GUARD);
                    ADOPTED.set(child);
                    popups[0].removeAll();
                    popups[0].add(child, BorderLayout.CENTER);
                    popups[0].revalidate();
                }
            });
            grid.add(wv[0]);
            popups[0] = new JPanel(new BorderLayout());
            popups[0].add(new JLabel("An adopted popup appears here."), BorderLayout.NORTH);
            grid.add(popups[0]);
            frame.add(grid, BorderLayout.CENTER);
            frame.setVisible(true);
            wv[0].setUrl("demo://app/index.html");
        });
        if (auto) {
            boolean pass = false;
            try {
                pass = new Checks(wv[0]).run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            System.out.println(pass ? "PASS" : "FAIL");
            server.stop(0);
            System.exit(pass ? 0 : 1);
        }
    }

    /** The auto-mode checks, one per acceptance criterion. */
    static final class Checks {
        final WebViewComponent wv;
        final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        boolean pass = true;

        Checks(WebViewComponent wv) {
            this.wv = wv;
        }

        boolean run() throws Exception {
            waitFor("demo://app/index.html", "A page that can't leave");
            refused("AC1 link", "document.getElementById('link').click()", "/link", NavigationCause.LINK);
            refused("AC1 download link", "document.getElementById('dl').click()", "/download", NavigationCause.LINK);
            refused("AC2 location.href", "location.href='" + listener + "/href?d=secret'", "/href", NavigationCause.OTHER);
            refused("AC2 location.assign", "location.assign('" + listener + "/assign?d=secret')", "/assign", NavigationCause.OTHER);
            refused("AC2 location.replace", "location.replace('" + listener + "/replace?d=secret')", "/replace", NavigationCause.OTHER);
            refused("AC2 meta refresh", "meta()", "/meta", NavigationCause.OTHER);
            refused("AC3 form", "document.getElementById('form').submit()", "/form", NavigationCause.FORM);
            refused("AC10 frame", "frame()", "/frame", NavigationCause.OTHER);
            refused("AC7 throwing handler", "location.href='" + listener + "/throw?d=secret'", "/throw", NavigationCause.OTHER);

            int before = EVENTS.size();
            eval("document.getElementById('ok').click()");
            check("AC4 allowed link", waitFor("demo://app/next.html", "Next"));
            eval("history.back()");
            check("AC4/AC6 back", waitFor("demo://app/index.html", "A page that can't leave"));
            check("AC6 back is BACK_FORWARD", seen(before, "demo://app/index.html", NavigationCause.BACK_FORWARD));
            eval("history.forward()");
            check("AC6 forward", waitFor("demo://app/next.html", "Next"));
            before = EVENTS.size();
            eval("location.reload()");
            Thread.sleep(800);
            check("AC6 reload is RELOAD", seen(before, "demo://app/next.html", NavigationCause.RELOAD));

            // AC5: the page's navigation to an address is not marked; the application's is.
            before = EVENTS.size();
            String appUrl = listener + "/app";
            eval("location.href='" + appUrl + "'");
            Thread.sleep(800);
            WebViewNavigationEvent pageNav = find(before, appUrl);
            check("AC5 a page navigation is not marked", pageNav != null && !pageNav.applicationInitiated() && HITS.get() == 0);
            before = EVENTS.size();
            SwingUtilities.invokeAndWait(() -> wv.setUrl(appUrl));
            boolean loaded = waitFor(appUrl, "The listener");
            WebViewNavigationEvent appNav = find(before, appUrl);
            check("AC5 setUrl is marked and loads", loaded && appNav != null && appNav.applicationInitiated());

            // AC9: an adopted popup is guarded by its own component's handler.
            SwingUtilities.invokeAndWait(() -> wv.setUrl("demo://app/index.html"));
            waitFor("demo://app/index.html", "A page that can't leave");
            int hits = HITS.get();
            eval("window.open('demo://app/popup.html')");
            long deadline = System.currentTimeMillis() + 8000;
            WebViewNavigationEvent popupNav = null;
            while (System.currentTimeMillis() < deadline && popupNav == null) {
                Thread.sleep(200);
                synchronized (EVENTS) {
                    for (WebViewNavigationEvent e : EVENTS) {
                        if (e.url().endsWith("/popup?d=secret")) popupNav = e;
                    }
                }
            }
            Thread.sleep(500);
            check("AC9 adopted popup refused", popupNav != null && popupNav.source() == ADOPTED.get()
                && HITS.get() == hits);

            // AC8: without a handler, nothing changes.
            wv.setNavigationHandler(null);
            eval("location.href='" + listener + "/free'");
            check("AC8 no handler navigates", waitFor(listener + "/free", "The listener"));
            return pass;
        }

        void refused(String name, String js, String path, NavigationCause cause) throws Exception {
            int before = EVENTS.size();
            int hits = HITS.get();
            eval(js);
            Thread.sleep(900);
            String where = location();
            WebViewNavigationEvent e = null;
            synchronized (EVENTS) {
                for (int i = before; i < EVENTS.size(); i++) {
                    if (EVENTS.get(i).url().contains(path)) e = EVENTS.get(i);
                }
            }
            NavigationCause expected = cause;
            if (windows && (cause == NavigationCause.LINK || cause == NavigationCause.FORM)) expected = NavigationCause.OTHER;
            boolean ok = e != null && HITS.get() == hits && where.startsWith("demo://app/index.html")
                && e.cause() == expected && !e.applicationInitiated();
            System.out.println("  " + name + ": " + (ok ? "ok" : "FAIL (event=" + e + ", hits " + hits + "->"
                + HITS.get() + ", at " + where + ")"));
            if (!ok) pass = false;
        }

        void check(String name, boolean ok) {
            System.out.println("  " + name + ": " + (ok ? "ok" : "FAIL"));
            if (!ok) pass = false;
        }

        boolean seen(int from, String url, NavigationCause cause) {
            synchronized (EVENTS) {
                for (int i = from; i < EVENTS.size(); i++) {
                    WebViewNavigationEvent e = EVENTS.get(i);
                    if (e.url().equals(url) && e.cause() == cause) return true;
                }
            }
            return false;
        }

        WebViewNavigationEvent find(int from, String url) {
            synchronized (EVENTS) {
                for (int i = from; i < EVENTS.size(); i++) {
                    if (EVENTS.get(i).url().startsWith(url)) return EVENTS.get(i);
                }
            }
            return null;
        }

        void eval(final String js) throws Exception {
            SwingUtilities.invokeAndWait(() -> wv.eval(js));
        }

        String location() {
            try {
                String s = wv.evalAsync("return location.href").get(5, TimeUnit.SECONDS);
                return s == null ? "" : s.replaceAll("^\"|\"$", "");
            } catch (Exception e) {
                return "";
            }
        }

        /** Wait up to 8 s for the view to show {@code url} with heading {@code heading}. */
        boolean waitFor(String url, String heading) throws Exception {
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    String s = wv.evalAsync("return location.href+'|'+(document.getElementById('h')||{}).textContent")
                        .get(3, TimeUnit.SECONDS);
                    if (s != null) {
                        s = s.replaceAll("^\"|\"$", "");
                        if (s.startsWith(url) && s.endsWith("|" + heading)) return true;
                    }
                } catch (Exception ignored) {
                    // the page is between documents
                }
                Thread.sleep(200);
            }
            return false;
        }
    }
}
