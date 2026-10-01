/*
 * MIT License
 *
 * Regression smoke test for issue #64: on Linux the JVM aborted (SIGABRT) at
 * normal exit after a lightweight WebViewComponent had loaded a page, because
 * WebKitGTK's default WebKitWebContext was finalized from libc exit handlers
 * on the JVM's exit thread rather than the GTK thread.
 *
 * The test loads a page, confirms it is live, then calls System.exit(0). The
 * driving script (run-linux-exit-smoketest.sh) passes only when the process
 * exit status is 0 -- an abort shows up as 134.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;

public class WebViewExitSmokeTest {

    public static void main(String[] args) throws Exception {
        final WebViewComponent[] holder = new WebViewComponent[1];
        EventQueue.invokeAndWait(() -> {
            WebViewComponent wv = WebViewComponent.create(WebViewComponent.Mode.LIGHTWEIGHT);
            wv.setPreferredSize(new Dimension(320, 200));
            JFrame frame = new JFrame("WebViewExitSmokeTest");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(wv, BorderLayout.CENTER);
            frame.pack();
            frame.setVisible(true);
            wv.setUrl("data:text/html,<p id=p>hello</p>");
            holder[0] = wv;
        });
        WebViewComponent wv = holder[0];

        String text = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                text = wv.evalAsync("var p=document.getElementById('p'); return p ? p.textContent : null;")
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the page may not be ready yet
            }
            if ("\"hello\"".equals(text)) break;
            Thread.sleep(250);
        }
        if (!"\"hello\"".equals(text)) {
            System.err.println("[exit-smoke] FAIL page never loaded (got " + text + ")");
            System.exit(1);
        }
        // Dispose the component first -- what closing the window does. That drops
        // the last WebKitWebView reference to the default WebKitWebContext, so
        // only WebKit's own static reference remains for exit to release.
        EventQueue.invokeAndWait(wv::dispose);
        Thread.sleep(1000);
        System.err.println("[exit-smoke] page loaded and disposed; exiting the JVM with status 0");
        System.exit(0);
    }
}
