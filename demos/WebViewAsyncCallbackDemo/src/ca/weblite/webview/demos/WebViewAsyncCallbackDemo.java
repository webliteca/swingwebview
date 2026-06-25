/*
 * MIT License
 *
 * Demonstrates the recommended, deadlock-free way to expose a Java
 * function to JavaScript that *returns a result* — without a synchronous
 * addJavascriptCallback.
 *
 * The page calls `await window.callJava(method, arg)` and gets a value
 * back.  Under the hood this is the mirror image of WebViewComponent's
 * evalAsync:
 *
 *   JS  --(void binding __rpc_call)-->  Java        (request, base64)
 *   Java does its work OFF the UI thread (never blocks)
 *   Java --(eval window.__rpc_resolve)-->  JS        (result, base64)
 *   JS settles the Promise it handed the caller.
 *
 * Because nothing ever blocks one thread waiting on the other, there is
 * no EDT <-> native-UI-thread deadlock — the hazard a synchronous,
 * value-returning binding would reintroduce (see
 * requirements/[User-story-4]eliminate-edt-appkit-sync-deadlock-on-macos.md).
 *
 * The base64 envelope and the single-string channel mirror the
 * convention used by ca.weblite.webview.EvalDispatcher so this demo
 * needs no JSON-parsing dependency.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewComponent;

import com.sun.net.httpserver.HttpServer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    /**
     * Reserved-prefix note: the only real binding installed here is
     * {@code __rpc_call}.  It does NOT start with
     * {@link WebViewComponent#RESERVED_BINDING_PREFIX} ({@code __webview_}),
     * so it passes the reserved-name guard.  {@code __rpc_resolve} is a
     * page-side function the shim defines — it is invoked via {@code eval},
     * not registered as a binding.
     */
    private static final String INBOUND_BINDING = "__rpc_call";

    /** Background pool so Java handlers never run on the engine UI thread. */
    private static final Executor WORKER =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "rpc-worker");
            t.setDaemon(true);
            return t;
        });

    public static void main(String[] args) {
        // Heavyweight popups so any menu renders above the WebView region.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewAsyncCallbackDemo::run);
    }

    private static void run() {
        WebViewComponent.Mode mode = WebViewComponent.resolveDefaultMode();
        System.err.println("[demo] os.name=" + System.getProperty("os.name")
            + "  resolved mode=" + mode);

        final WebViewComponent wv = WebViewComponent.create();

        // Java-side log.  The page has its own log, but this one proves the
        // threading on the Java side: the inbound call lands on the native
        // UI thread, the work runs on a worker thread, and nothing blocks.
        final JTextArea javaLog = new JTextArea(8, 80);
        javaLog.setEditable(false);
        javaLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        final Consumer<String> log = msg -> SwingUtilities.invokeLater(() -> {
            javaLog.append(msg + "\n");
            javaLog.setCaretPosition(javaLog.getDocument().getLength());
        });

        // 1. Install the JS glue at document-start of every page.  It
        //    defines window.callJava(method, arg) -> Promise, and the
        //    window.__rpc_resolve(b64) sink that Java calls back into.
        wv.addOnBeforeLoad(RPC_SHIM_JS);

        // 2. Register the (void) inbound binding.  This is a STANDARD
        //    addJavascriptCallback — the result travels back out-of-band
        //    via eval, so the binding itself stays fire-and-forget.
        wv.addJavascriptCallback(INBOUND_BINDING, raw -> handleCall(wv, raw, log));

        // Serve the page over a localhost HTTP server instead of a data:
        // URL.  The embedded macOS engine navigates via WKWebView's
        // loadRequest:, which silently refuses data: URLs (a long-standing
        // WKWebView restriction) -- the page would load fine in a normal
        // browser but show blank in the embedded view.  An http://localhost
        // URL loads identically to any remote site (loopback is exempt from
        // App Transport Security, so plain HTTP is fine).
        int port = startPageServer();
        wv.setUrl("http://localhost:" + port + "/");

        // Embed the WebView in a split pane with the Java log below.
        wv.setPreferredSize(new Dimension(900, 540));
        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
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
     * Start a tiny localhost HTTP server that serves {@link #PAGE_HTML} at
     * {@code /}.  Returns the chosen ephemeral port.  Bound to the loopback
     * interface only; daemon-threaded so it never blocks JVM exit.
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
            Executor pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "page-http");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.start();
            return server.getAddress().getPort();
        } catch (IOException e) {
            throw new RuntimeException("could not start page server", e);
        }
    }

    /**
     * Handle one inbound JS->Java request.  Runs on the engine's native
     * UI thread (AppKit main thread on macOS).  We extract the request,
     * then immediately hop OFF this thread onto {@link #WORKER} so the
     * UI thread is never held while the "business logic" runs — this is
     * what keeps the round trip deadlock-free.
     */
    private static void handleCall(WebViewComponent wv, String rawEnvelope,
                                   Consumer<String> log) {
        // The native engine hands us {"name":..,"seq":..,"args":["<b64>"]}.
        String b64 = extractFirstArg(rawEnvelope);
        if (b64 == null) return;
        String rec = decodeBase64Utf8(b64);
        if (rec == null) return;

        // rec = "<id>|<method>|<rawArg>".  rawArg may itself contain '|',
        // so split on the first two delimiters only (EvalDispatcher does
        // the same).
        int p1 = rec.indexOf('|');
        if (p1 < 0) return;
        int p2 = rec.indexOf('|', p1 + 1);
        if (p2 < 0) return;
        final String id = rec.substring(0, p1);
        final String method = rec.substring(p1 + 1, p2);
        final String arg = rec.substring(p2 + 1);

        log.accept("<- " + method + "(\"" + arg + "\")  [bind on "
            + Thread.currentThread().getName() + "]");

        // Do the work asynchronously; resolve (or reject) when it's done.
        CompletableFuture
            .supplyAsync(() -> {
                log.accept("   computing #" + id + " [worker "
                    + Thread.currentThread().getName() + "]");
                return invokeMethod(method, arg);
            }, WORKER)
            .whenComplete((result, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null
                        ? error.getCause() : error;
                    log.accept("-> reject #" + id + ": "
                        + cause.getClass().getSimpleName());
                    resolve(wv, id, false,
                        cause.getClass().getSimpleName() + ": "
                            + cause.getMessage());
                } else {
                    log.accept("-> resolve #" + id + " = \"" + result + "\"");
                    resolve(wv, id, true, result);
                }
            });
    }

    /**
     * The actual "exposed" Java methods.  Returns a plain String; the
     * channel is string-typed for clarity.  For structured results you
     * would return JSON here and {@code JSON.parse} it in
     * {@code __rpc_resolve} (the shim already isolates that one spot).
     */
    private static String invokeMethod(String method, String arg) {
        switch (method) {
            case "reverse":
                return new StringBuilder(arg).reverse().toString();
            case "upper":
                return arg.toUpperCase();
            case "slowEcho":
                // Simulate slow work on the worker thread.  The UI stays
                // fully responsive; a synchronous binding would freeze it
                // (or deadlock).
                sleep(1500);
                return "echo after 1.5s: " + arg;
            case "fail":
                throw new IllegalStateException("intentional failure for: " + arg);
            default:
                throw new IllegalArgumentException("unknown method: " + method);
        }
    }

    /**
     * Settle the page-side Promise by calling window.__rpc_resolve with a
     * base64 payload {@code "<id>|<ok>|<value>"}.  eval() is asynchronous
     * and non-blocking, so this never waits on the page.  The base64
     * alphabet (A-Za-z0-9+/=) is safe inside a single-quoted JS string,
     * so no escaping is needed.
     */
    private static void resolve(WebViewComponent wv, String id,
                                boolean ok, String value) {
        String rec = id + "|" + (ok ? "1" : "0") + "|" + value;
        String b64 = encodeBase64Utf8(rec);
        wv.eval("window.__rpc_resolve('" + b64 + "')");
    }

    // ---- base64 helpers (mirror EvalDispatcher) ---------------------------

    private static String extractFirstArg(String json) {
        int argsIdx = json.indexOf("\"args\":[");
        if (argsIdx < 0) return null;
        int quoteStart = json.indexOf('"', argsIdx + 8);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String decodeBase64Utf8(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64), "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return null;
        }
    }

    private static String encodeBase64Utf8(String s) {
        try {
            return Base64.getEncoder().encodeToString(s.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- page assets ------------------------------------------------------

    /**
     * Document-start shim.  Defines window.callJava(method, arg) returning
     * a Promise, plus the window.__rpc_resolve(b64) sink Java calls back
     * into.  Idempotency-guarded so repeated navigations are a no-op.
     */
    private static final String RPC_SHIM_JS =
        "(function(){"
      + "if(window.__rpcInstalled)return; window.__rpcInstalled=true;"
      + "var pending={}, seq=0;"
      + "function enc(s){return btoa(unescape(encodeURIComponent(s)));}"
      + "function dec(s){return decodeURIComponent(escape(atob(s)));}"
      // The async, value-returning function page authors call:
      + "window.callJava=function(method,arg){"
      + "  var id=++seq;"
      + "  return new Promise(function(resolve,reject){"
      + "    pending[id]={resolve:resolve,reject:reject};"
      + "    var rec=id+'|'+method+'|'+(arg==null?'':String(arg));"
      + "    window." + INBOUND_BINDING + "(enc(rec));"
      + "  });"
      + "};"
      // Java settles the Promise through here:
      + "window.__rpc_resolve=function(b64){"
      + "  var rec=dec(b64);"
      + "  var p1=rec.indexOf('|'); if(p1<0)return;"
      + "  var p2=rec.indexOf('|',p1+1); if(p2<0)return;"
      + "  var id=rec.substring(0,p1);"
      + "  var ok=rec.substring(p1+1,p2);"
      + "  var val=rec.substring(p2+1);"
      + "  var e=pending[id]; if(!e)return; delete pending[id];"
      + "  if(ok==='1') e.resolve(val); else e.reject(new Error(val));"
      + "};"
      + "})();";

    private static final String PAGE_HTML =
        "<!doctype html><html><head><meta charset='utf-8'>"
      + "<title>async callback demo</title><style>"
      + "  body{font:14px/1.5 system-ui,sans-serif;padding:1.2em;"
      + "    max-width:46em;margin:0 auto;}"
      + "  h1{font-size:1.2em;} input{font:inherit;padding:.3em .4em;width:18em;}"
      + "  button{font:inherit;padding:.3em .7em;margin:.1em;cursor:pointer;}"
      + "  #log{margin-top:1em;border:1px solid #ccc;border-radius:.3em;"
      + "    padding:.6em;height:14em;overflow:auto;background:#fafafa;"
      + "    font:12px/1.4 ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap;}"
      + "  .spin{color:#c60;}"
      + "</style></head><body>"
      + "<h1>window.callJava(method, arg) &rarr; Promise</h1>"
      + "<p>Each button does <code>const r = await window.callJava(...)</code>."
      + " Java computes the result on a background thread and resolves the"
      + " Promise — the UI never blocks.</p>"
      + "<p><input id='in' value='Hello, WebView'/></p>"
      + "<p>"
      + "  <button onclick=\"call('reverse')\">reverse</button>"
      + "  <button onclick=\"call('upper')\">upper</button>"
      + "  <button onclick=\"call('slowEcho')\">slowEcho (1.5s)</button>"
      + "  <button onclick=\"call('fail')\">fail (rejects)</button>"
      + "  <button onclick=\"stress()\">5&times; concurrent</button>"
      + "</p>"
      + "<div id='log'></div>"
      + "<script>"
      + "function log(s){var d=document.getElementById('log');"
      + "  d.textContent+=s+'\\n'; d.scrollTop=d.scrollHeight;}"
      + "function call(method){"
      + "  var arg=document.getElementById('in').value;"
      + "  var t0=performance.now();"
      + "  log('-> '+method+'('+JSON.stringify(arg)+')');"
      + "  window.callJava(method,arg).then(function(r){"
      + "    log('   <- '+JSON.stringify(r)+'  ('+Math.round(performance.now()-t0)+' ms)');"
      + "  }).catch(function(e){"
      + "    log('   x rejected: '+e.message+'  ('+Math.round(performance.now()-t0)+' ms)');"
      + "  });"
      + "}"
      // Fire several at once to show concurrency + that results match
      // their own request (slow ones resolve later, none cross-talk).
      + "function stress(){"
      + "  for(var i=1;i<=5;i++){(function(n){"
      + "    window.callJava(n%2?'slowEcho':'upper','req#'+n).then(function(r){"
      + "      log('   [concurrent] req#'+n+' <- '+JSON.stringify(r));"
      + "    });"
      + "  })(i);}"
      + "  log('-> fired 5 concurrent calls; UI stays responsive');"
      + "}"
      + "</script></body></html>";
}
