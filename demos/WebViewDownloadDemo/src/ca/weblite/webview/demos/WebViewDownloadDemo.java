/*
 * MIT License
 *
 * Exercises the browser-initiated download API on WebViewComponent.
 * Designed for manual verification against the acceptance criteria in
 *   requirements/[User-story-5]webview-download-handler.md
 *
 * Switch among three handler modes via the combo box at the top:
 *   - Default      : framework's stock ~/Downloads policy with (N) de-duplication.
 *   - Custom       : route to a temp directory chosen at app start; log each event.
 *   - Drop (null)  : cancel every download synchronously without UI.
 *
 * Coverage in this iteration spans macOS 11.3+ (WKDownload),
 * Linux WebKitGTK (heavyweight + lightweight), and Windows
 * (modern Evergreen WebView2 Runtime exposing ICoreWebView2_4).
 * On older runtimes the download silently drops; the WebView itself
 * continues to function normally.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.WebViewDownloadEvent;
import ca.weblite.webview.WebViewDownloadHandler;
import ca.weblite.webview.swing.WebViewComponent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Random;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewDownloadDemo {

    private static final String MODE_DEFAULT = "Default handler (~/Downloads)";
    private static final String MODE_CUSTOM  = "Custom handler (temp dir + log)";
    private static final String MODE_DROP    = "Drop handler (cancel all)";

    public static void main(String[] args) {
        // Heavyweight popups so JFileChooser dropdowns render above
        // the WebView region on macOS / Windows heavyweight.  Safe in
        // lightweight mode (no-op).
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewDownloadDemo::run);
    }

    private static void run() {
        // Start the embedded HTTP server serving three downloadable
        // payloads with Content-Disposition: attachment.
        final HttpServer server;
        final int port;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                "Failed to start embedded HTTP server: " + e.getMessage(),
                "WebViewDownloadDemo", JOptionPane.ERROR_MESSAGE);
            return;
        }
        server.createContext("/", new IndexHandler(port));
        server.createContext("/sample.txt",
            new ByteHandler("sample.txt", "text/plain", 4 * 1024));
        server.createContext("/report.pdf",
            new ByteHandler("report.pdf", "application/pdf", 12 * 1024));
        server.createContext("/installer.bin",
            new ByteHandler("installer.bin",
                "application/octet-stream", 256 * 1024));
        server.start();

        final WebViewComponent wv = WebViewComponent.create();
        final JTextArea log = new JTextArea(10, 80);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        wv.addConsoleListener(new ConsoleListener() {
            @Override public void onMessage(final ConsoleMessage m) {
                SwingUtilities.invokeLater(() ->
                    log.append("[" + m.getLevel() + "] " + m.getText() + "\n"));
            }
        });

        // Pre-create a custom-mode temp directory.  Done eagerly so a
        // mode-switch to Custom doesn't trip a JFileChooser the user
        // might not understand.
        File customDir;
        try {
            customDir = File.createTempFile("webview-download-demo-", "");
            customDir.delete();
            customDir.mkdirs();
        } catch (IOException ex) {
            customDir = new File(System.getProperty("java.io.tmpdir"),
                "webview-download-demo");
            customDir.mkdirs();
        }
        final File customTargetDir = customDir;
        log.append("Custom-mode target directory: "
            + customTargetDir.getAbsolutePath() + "\n");

        final WebViewDownloadHandler customHandler =
            new WebViewDownloadHandler() {
                @Override public File downloadRequested(
                        WebViewDownloadEvent event) {
                    File dest = new File(customTargetDir,
                        event.suggestedFilename());
                    SwingUtilities.invokeLater(() ->
                        log.append("[custom] " + event.suggestedFilename()
                            + " from " + event.sourceUrl()
                            + " (" + event.mimeType()
                            + ", " + event.totalBytes() + " bytes) -> "
                            + dest.getAbsolutePath() + "\n"));
                    return dest;
                }
            };
        final WebViewDownloadHandler droppingHandler =
            new WebViewDownloadHandler() {
                @Override public File downloadRequested(
                        WebViewDownloadEvent event) {
                    SwingUtilities.invokeLater(() ->
                        log.append("[drop] cancelled "
                            + event.suggestedFilename() + " from "
                            + event.sourceUrl() + "\n"));
                    return null;
                }
            };
        final WebViewDownloadHandler defaultLoggingHandler =
            new WebViewDownloadHandler() {
                @Override public File downloadRequested(
                        WebViewDownloadEvent event) {
                    // Delegate to the framework DEFAULT's logic, then log
                    // the chosen path.  Calling DEFAULT.downloadRequested
                    // works because the default impl is stateless.
                    File chosen = WebViewDownloadHandler.DEFAULT
                        .downloadRequested(event);
                    final File c = chosen;
                    SwingUtilities.invokeLater(() ->
                        log.append("[default] " + event.suggestedFilename()
                            + " from " + event.sourceUrl() + " -> "
                            + (c == null ? "(cancelled — collision ceiling)"
                                         : c.getAbsolutePath()) + "\n"));
                    return chosen;
                }
            };

        // Default mode at startup.
        wv.setDownloadHandler(defaultLoggingHandler);

        final JComboBox<String> modeBox = new JComboBox<>(new String[] {
            MODE_DEFAULT, MODE_CUSTOM, MODE_DROP
        });
        modeBox.addActionListener(e -> {
            String mode = (String) modeBox.getSelectedItem();
            if (MODE_CUSTOM.equals(mode)) {
                wv.setDownloadHandler(customHandler);
                log.append("--- Mode -> Custom (temp dir) ---\n");
            } else if (MODE_DROP.equals(mode)) {
                wv.setDownloadHandler(droppingHandler);
                log.append("--- Mode -> Drop (cancel all) ---\n");
            } else {
                wv.setDownloadHandler(defaultLoggingHandler);
                log.append("--- Mode -> Default (~/Downloads) ---\n");
            }
        });

        final JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Mode:"));
        top.add(modeBox);
        top.add(new JLabel("  Server: http://127.0.0.1:" + port + "/"));

        final JSplitPane split = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, wv, new JScrollPane(log));
        split.setResizeWeight(0.7);

        final JFrame frame = new JFrame("WebView Download Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.setSize(new Dimension(1024, 768));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                server.stop(0);
            }
        });
        frame.setVisible(true);

        // Load the index page after the frame is shown so the WebView
        // is sized correctly first.
        wv.setUrl("http://127.0.0.1:" + port + "/");
    }

    // ------------------------------------------------------------------
    // HTTP handlers.
    // ------------------------------------------------------------------

    private static final class IndexHandler implements HttpHandler {
        private final int port;
        IndexHandler(int port) { this.port = port; }
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!"/".equals(ex.getRequestURI().getPath())) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            String html =
                "<!doctype html><html><head><meta charset='utf-8'>"
              + "<title>WebView Download Demo</title>"
              + "<style>body{font:14px/1.4 system-ui,sans-serif;"
              + "padding:1em;max-width:48em;margin:0 auto;}"
              + "h1{font-size:1.2em;margin:0 0 .4em 0;}"
              + ".row{margin:.6em 0;}a{display:inline-block;padding:.3em .6em;"
              + "background:#eef;border-radius:.2em;text-decoration:none;"
              + "color:#036;margin-right:.4em;}</style></head><body>"
              + "<h1>WebView Download Demo</h1>"
              + "<p>Click a link to trigger a download.  Re-click to "
              + "exercise the default handler's (N) de-duplication.</p>"
              + "<div class='row'>"
              + "<a href='/sample.txt' download>sample.txt (4 KB)</a>"
              + "<a href='/report.pdf' download>report.pdf (12 KB)</a>"
              + "<a href='/installer.bin' download>installer.bin (256 KB)</a>"
              + "</div>"
              + "<p style='color:#666;font-size:12px'>"
              + "Server: http://127.0.0.1:" + port + "/.  "
              + "Switch handler modes via the dropdown above.</p>"
              + "</body></html>";
            byte[] body = html.getBytes("UTF-8");
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static final class ByteHandler implements HttpHandler {
        private final String filename;
        private final String mime;
        private final int sizeBytes;
        ByteHandler(String filename, String mime, int sizeBytes) {
            this.filename = filename;
            this.mime = mime;
            this.sizeBytes = sizeBytes;
        }
        @Override public void handle(HttpExchange ex) throws IOException {
            byte[] payload = new byte[sizeBytes];
            new Random(filename.hashCode()).nextBytes(payload);
            ex.getResponseHeaders().add("Content-Type", mime);
            ex.getResponseHeaders().add("Content-Disposition",
                "attachment; filename=\"" + filename + "\"");
            ex.sendResponseHeaders(200, payload.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(payload);
            }
        }
    }
}
