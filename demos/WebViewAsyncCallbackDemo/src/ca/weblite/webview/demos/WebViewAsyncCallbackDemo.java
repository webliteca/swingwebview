/*
 * MIT License
 *
 * Demonstrates addJavascriptFunction: the Java-friendly way to expose a
 * value-returning function to JavaScript, with NO JavaScript glue.
 *
 * The page calls `const r = await window.<name>(arg)` and gets a result
 * back. On the Java side you just register a lambda:
 *
 *     wv.addJavascriptFunction("reverse", arg -> reverse(arg));
 *
 * The library runs the (synchronous) handler on a background thread, so a
 * slow handler never blocks the UI or deadlocks the engine UI thread
 * against the Swing EDT -- the hazard a synchronous, value-returning
 * addJavascriptCallback would reintroduce (see
 * requirements/[User-story-4]eliminate-edt-appkit-sync-deadlock-on-macos.md).
 * For inherently-async work, register an AsyncJavascriptFunction returning
 * a CompletableFuture.
 *
 * The page is served over a localhost HTTP server because the embedded
 * macOS engine navigates with WKWebView's loadRequest:, which refuses
 * data: URLs.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.JavascriptFunction;
import ca.weblite.webview.swing.WebViewComponent;

import com.sun.net.httpserver.HttpServer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewAsyncCallbackDemo {

    public static void main(String[] args) {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewAsyncCallbackDemo::run);
    }

    private static void run() {
        WebViewComponent.Mode mode = WebViewComponent.resolveDefaultMode();
        System.err.println("[demo] os.name=" + System.getProperty("os.name")
            + "  resolved mode=" + mode);

        final WebViewComponent wv = WebViewComponent.create();

        // Java-side log: shows that the synchronous handlers run on a
        // background thread (never the EDT / engine UI thread).
        final JTextArea javaLog = new JTextArea(8, 80);
        javaLog.setEditable(false);
        javaLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        final Consumer<String> log = msg -> SwingUtilities.invokeLater(() -> {
            javaLog.append(msg + "\n");
            javaLog.setCaretPosition(javaLog.getDocument().getLength());
        });

        // ---- The whole API surface: register Java functions, no JS glue. ----

        // Synchronous handlers. The library runs each on a worker thread and
        // resolves the page Promise with the returned String.  (Explicit
        // (String arg) typing disambiguates the sync vs async overload.)
        wv.addJavascriptFunction("reverse", (String arg) -> {
            log.accept("reverse(\"" + arg + "\")  [" + Thread.currentThread().getName() + "]");
            return new StringBuilder(arg).reverse().toString();
        });
        wv.addJavascriptFunction("upper", (String arg) -> {
            log.accept("upper(\"" + arg + "\")  [" + Thread.currentThread().getName() + "]");
            return arg.toUpperCase();
        });
        // Slow handler: sleeps 1.5s. The UI stays fully responsive because the
        // library ran it off the UI thread -- a synchronous callback would
        // freeze the window here.
        wv.addJavascriptFunction("slowEcho", (String arg) -> {
            log.accept("slowEcho(\"" + arg + "\") sleeping 1.5s  ["
                + Thread.currentThread().getName() + "]");
            Thread.sleep(1500);
            return "echo after 1.5s: " + arg;
        });
        // Throwing a checked or unchecked exception rejects the page Promise.
        // (A throw-only lambda fits both overloads, so cast to disambiguate.)
        wv.addJavascriptFunction("fail", (JavascriptFunction) (String arg) -> {
            throw new IllegalStateException("intentional failure for: " + arg);
        });
        // Asynchronous handler: returns a CompletableFuture; the page Promise
        // settles when the future completes.
        wv.addJavascriptFunction("asyncLookup", (String arg) ->
            CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(800); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "async result for \"" + arg + "\"";
            }));

        int port = startPageServer();
        wv.setUrl("http://localhost:" + port + "/");

        wv.setPreferredSize(new Dimension(900, 540));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wv, new JScrollPane(javaLog));
        split.setResizeWeight(0.72);

        JFrame frame = new JFrame("WebViewAsyncCallbackDemo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(split, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Start a loopback HTTP server serving {@link #PAGE_HTML} at {@code /}.
     */
    private static int startPageServer() {
        try {
            HttpServer server = HttpServer.create(
                new InetSocketAddress("localhost", 0), 0);
            final byte[] body = PAGE_HTML.getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().set(
                    "Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "page-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            return server.getAddress().getPort();
        } catch (IOException e) {
            throw new RuntimeException("could not start page server", e);
        }
    }

    private static final String PAGE_HTML =
        "<!doctype html><html><head><meta charset='utf-8'>"
      + "<title>addJavascriptFunction demo</title><style>"
      + "  body{font:14px/1.5 system-ui,sans-serif;padding:1.2em;"
      + "    max-width:46em;margin:0 auto;}"
      + "  h1{font-size:1.2em;} input{font:inherit;padding:.3em .4em;width:18em;}"
      + "  button{font:inherit;padding:.3em .7em;margin:.1em;cursor:pointer;}"
      + "  #log{margin-top:1em;border:1px solid #ccc;border-radius:.3em;"
      + "    padding:.6em;height:13em;overflow:auto;background:#fafafa;"
      + "    font:12px/1.4 ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap;}"
      + "</style></head><body>"
      + "<h1>Java functions called from JavaScript</h1>"
      + "<p>Each button does <code>const r = await window.&lt;name&gt;(arg)</code>."
      + " The Java side is just <code>wv.addJavascriptFunction(\"reverse\", arg "
      + "-&gt; ...)</code> &mdash; no JavaScript glue.</p>"
      + "<p><input id='in' value='Hello, WebView'/></p>"
      + "<p>"
      + "  <button onclick=\"call('reverse')\">reverse</button>"
      + "  <button onclick=\"call('upper')\">upper</button>"
      + "  <button onclick=\"call('slowEcho')\">slowEcho (1.5s)</button>"
      + "  <button onclick=\"call('fail')\">fail (rejects)</button>"
      + "  <button onclick=\"call('asyncLookup')\">asyncLookup</button>"
      + "  <button onclick=\"stress()\">5&times; concurrent</button>"
      + "</p>"
      + "<div id='log'></div>"
      + "<script>"
      + "function log(s){var d=document.getElementById('log');"
      + "  d.textContent+=s+'\\n'; d.scrollTop=d.scrollHeight;}"
      + "function call(name){"
      + "  var arg=document.getElementById('in').value;"
      + "  var t0=performance.now();"
      + "  log('-> '+name+'('+JSON.stringify(arg)+')');"
      + "  window[name](arg).then(function(r){"
      + "    log('   <- '+JSON.stringify(r)+'  ('+Math.round(performance.now()-t0)+' ms)');"
      + "  }).catch(function(e){"
      + "    log('   x rejected: '+e.message+'  ('+Math.round(performance.now()-t0)+' ms)');"
      + "  });"
      + "}"
      + "function stress(){"
      + "  for(var i=1;i<=5;i++){(function(n){"
      + "    window[n%2?'slowEcho':'upper']('req#'+n).then(function(r){"
      + "      log('   [concurrent] req#'+n+' <- '+JSON.stringify(r));"
      + "    });"
      + "  })(i);}"
      + "  log('-> fired 5 concurrent calls; UI stays responsive');"
      + "}"
      + "</script></body></html>";
}
