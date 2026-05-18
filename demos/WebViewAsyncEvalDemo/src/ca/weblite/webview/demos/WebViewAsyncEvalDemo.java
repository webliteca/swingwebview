/*
 * MIT License
 *
 * Exercises the future-returning evalAsync API on WebViewComponent.
 * Designed for manual verification against the acceptance criteria in
 *   requirements/[User-story-3]async-javascript-eval.md
 *
 * See the README in this demo's directory for which AC each button maps
 * to.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.JavaScriptEvalException;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewAsyncEvalDemo {

    public static void main(String[] args) {
        // Heavyweight popups so the button-bar menus (if any) render
        // above the WebView region.  Safe in lightweight mode (no-op).
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewAsyncEvalDemo::run);
    }

    private static void run() {
        logPlatformPath();

        // Platform default: lightweight on Linux, heavyweight elsewhere.
        final WebViewComponent wv = WebViewComponent.create();

        // A self-contained page exercising every evalAsync scenario.
        final String html =
            "<!doctype html><html><head><meta charset='utf-8'>"
          + "<title>evalAsync demo</title>"
          + "<style>"
          + "  body{font:14px/1.4 system-ui,sans-serif;padding:1em;"
          + "    max-width:48em;margin:0 auto;}"
          + "  h1{font-size:1.2em;margin:0 0 .4em 0;}"
          + "  #target{padding:.3em .5em;background:#eef;"
          + "    border:1px solid #99c;border-radius:.3em;display:inline-block;}"
          + "  #spacer{height:1500px;background:linear-gradient("
          + "    #fff,#dde);margin-top:1em;border-top:1px dashed #999;}"
          + "</style></head><body>"
          + "<h1>WebViewAsyncEvalDemo</h1>"
          + "<p>Select some text in this paragraph, scroll around, and try"
          + " the buttons in the bottom panel.  Each click runs an"
          + " <code>evalAsync</code> against the loaded page and prints"
          + " the future's resolution in the text area below.</p>"
          + "<p id='target' data-line='42'>This paragraph carries"
          + " <code>data-line=\"42\"</code> &mdash; the &lsquo;query"
          + " data-attribute&rsquo; button reads it.</p>"
          + "<div id='spacer'>Scroll me &mdash; the &lsquo;scroll"
          + " position&rsquo; button reads window.scrollX/Y.</div>"
          + "</body></html>";

        wv.setUrl("data:text/html;charset=utf-8," + html);

        // Capture pane.
        final JTextArea log = new JTextArea(14, 80);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Button panel — one button per scenario.
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        addEvalButton(buttons, log, wv, "1+2",
            "primitive result (AC1)",
            "return 1 + 2;");
        addEvalButton(buttons, log, wv, "object",
            "object result (AC2)",
            "return {a: 1, b: 'two'};");
        addEvalButton(buttons, log, wv, "undefined",
            "undefined → \"null\" (AC3)",
            "return;");
        addEvalButton(buttons, log, wv, "Promise",
            "awaits returned Promise (AC4)",
            "return new Promise(function(r){"
            + "  setTimeout(function(){ r('done'); }, 250);"
            + "});");
        addEvalButton(buttons, log, wv, "sync throw",
            "sync throw → JavaScriptEvalException (AC5)",
            "foo.bar();");
        addEvalButton(buttons, log, wv, "reject",
            "Promise rejection → JavaScriptEvalException (AC6)",
            "return Promise.reject(new Error('boom'));");
        addEvalButton(buttons, log, wv, "scroll position",
            "live page query — scrollX/Y",
            "return [window.scrollX, window.scrollY];");
        addEvalButton(buttons, log, wv, "selection text",
            "live page query — current selection",
            "return window.getSelection().toString();");
        addEvalButton(buttons, log, wv, "data-line",
            "ancestor attribute lookup (the kind of thing the user "
            + "raised this feature for)",
            "var el = document.getElementById('target');"
            + "return el && el.getAttribute('data-line');");
        addEvalButton(buttons, log, wv, "viewport",
            "innerWidth/Height as a JSON object",
            "return {w: window.innerWidth, h: window.innerHeight};");

        // Concurrent calls — AC8.
        JButton concurrent = new JButton("2× concurrent");
        concurrent.setToolTipText(
            "two evalAsync calls in flight at once (AC8): the second "
            + "should resolve first");
        concurrent.addActionListener(e -> {
            long t0 = System.nanoTime();
            CompletableFuture<String> slow = wv.evalAsync(
                "return new Promise(function(r){"
                + "  setTimeout(function(){ r('slow'); }, 200);"
                + "});");
            CompletableFuture<String> fast = wv.evalAsync(
                "return 'fast';");
            slow.thenAccept(s -> log.append(
                fmt("slow", s, t0)));
            fast.thenAccept(s -> log.append(
                fmt("fast", s, t0)));
        });
        buttons.add(concurrent);

        // EDT check — AC10.
        JButton edt = new JButton("EDT check");
        edt.setToolTipText(
            "verifies the continuation runs on the Swing EDT (AC10)");
        edt.addActionListener(e -> {
            wv.evalAsync("return 'check';").thenAccept(s -> {
                boolean onEdt = SwingUtilities.isEventDispatchThread();
                log.append("[edt] continuation on EDT? " + onEdt
                    + " (expected true)  thread=\""
                    + Thread.currentThread().getName() + "\"\n");
            });
        });
        buttons.add(edt);

        // Pre-display call — AC7.  Build a fresh component, do NOT
        // attach it, and observe the failed future.
        JButton predisplay = new JButton("pre-display fail");
        predisplay.setToolTipText(
            "evalAsync on a never-attached component returns a "
            + "failed future with IllegalStateException (AC7)");
        predisplay.addActionListener(e -> {
            WebViewComponent unattached = WebViewComponent.create();
            CompletableFuture<String> f = unattached.evalAsync("return 1;");
            log.append("[pre-display] isCompletedExceptionally="
                + f.isCompletedExceptionally() + "\n");
            f.exceptionally(t -> {
                Throwable cause = t.getCause();
                log.append("[pre-display] cause: "
                    + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage() + "\n");
                return null;
            });
            // Clean up — the unattached component still owns a
            // ConsoleDispatcher / EvalDispatcher.
            unattached.dispose();
        });
        buttons.add(predisplay);

        // Existing eval still works — AC12 regression check.
        JButton legacy = new JButton("legacy eval");
        legacy.setToolTipText(
            "fire-and-forget eval still works (AC12); flashes the page bg");
        legacy.addActionListener(e -> {
            wv.eval(
                "document.body.style.background = '#ffeaa7';"
              + "setTimeout(function(){"
              + "  document.body.style.background = '';"
              + "}, 400);");
            log.append("[legacy] eval() fire-and-forget issued\n");
        });
        buttons.add(legacy);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wv, new JScrollPane(log));
        split.setResizeWeight(0.65);
        wv.setPreferredSize(new Dimension(900, 540));

        JPanel root = new JPanel(new BorderLayout());
        root.add(split, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        JFrame frame = new JFrame("WebViewAsyncEvalDemo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** Wire one button: click → evalAsync(js); print result or error. */
    private static void addEvalButton(JPanel panel, JTextArea log,
                                      WebViewComponent wv,
                                      String label, String tooltip,
                                      String js) {
        JButton b = new JButton(label);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> {
            long t0 = System.nanoTime();
            wv.evalAsync(js).handle((value, error) -> {
                if (error != null) {
                    Throwable cause = (error instanceof CompletionException)
                        ? error.getCause() : error;
                    log.append(fmtErr(label, cause, t0));
                } else {
                    log.append(fmt(label, value, t0));
                }
                return null;
            });
        });
        panel.add(b);
    }

    private static String fmt(String label, String value, long t0) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return "[" + label + "] (" + ms + " ms) → " + value + "\n";
    }

    private static String fmtErr(String label, Throwable cause, long t0) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return "[" + label + "] (" + ms + " ms) ✗ "
             + cause.getClass().getSimpleName()
             + ": " + cause.getMessage()
             + (cause instanceof JavaScriptEvalException
                ? "   (JavaScriptEvalException as expected)" : "")
             + "\n";
    }

    private static void logPlatformPath() {
        WebViewComponent.Mode mode = WebViewComponent.resolveDefaultMode();
        System.err.println(
            "[demo] os.name=" + System.getProperty("os.name")
          + "  resolved mode=" + mode
          + "  override=" + System.getProperty(
              WebViewComponent.MODE_PROPERTY, "(none)"));
    }
}
