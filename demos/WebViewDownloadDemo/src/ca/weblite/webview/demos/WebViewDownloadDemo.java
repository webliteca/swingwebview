/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.WebViewDownloadCompleteEvent;
import ca.weblite.webview.WebViewDownloadEvent;
import ca.weblite.webview.WebViewDownloadHandler;
import ca.weblite.webview.WebViewDownloadProgressEvent;
import ca.weblite.webview.swing.WebViewComponent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Interactive demo for the browser-initiated download channel
 * (Canvas 23 / 24 / 25).
 *
 * <p>Serves three download shapes from a loopback {@link HttpServer}:
 * an {@code <a download>} link to a small text file, a URL whose
 * response carries {@code Content-Disposition: attachment} with a
 * space in the filename, and a {@code .zip} the engine cannot render
 * inline.  A fourth link streams a 10 MB body with a declared
 * {@code Content-Length} so progress reporting is visible, and a fifth
 * streams a chunked body with no declared size so the "unknown size"
 * path is visible too.
 *
 * <p>The <em>Use silent handler</em> checkbox swaps between the stock
 * {@link WebViewDownloadHandler#DEFAULT} save dialog and a handler that
 * writes into a temp folder with no UI, appending progress and
 * completion lines to the log area.  Unchecking it mid-session proves
 * the handler swap is atomic.
 *
 * <p>Manual acceptance checklist lives in
 * {@code demos/WebViewDownloadDemo/README.md}.
 */
public final class WebViewDownloadDemo {

    private static final int SMALL_BYTES = 4096;
    private static final int BIG_BYTES = 10 * 1024 * 1024;

    private static JTextArea log;
    private static File silentDir;

    public static void main(String[] args) throws Exception {
        silentDir = new File(System.getProperty("java.io.tmpdir"),
            "webview-download-demo");
        if (!silentDir.exists() && !silentDir.mkdirs()) {
            throw new IOException("could not create " + silentDir);
        }
        final int port = startServer();

        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { buildUi(port); }
        });
    }

    // -----------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------

    private static void buildUi(int port) {
        JFrame frame = new JFrame("WebView Download Demo");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(1000, 720);
        frame.setLocationRelativeTo(null);

        final WebViewComponent wv = WebViewComponent.create();

        log = new JTextArea(10, 80);
        log.setEditable(false);
        log.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        final JCheckBox silent = new JCheckBox(
            "Use silent handler (writes into " + silentDir + ")");
        silent.addActionListener(e -> {
            if (silent.isSelected()) {
                wv.setDownloadHandler(new LoggingHandler());
                append("handler = silent (writes into " + silentDir + ")");
            } else {
                wv.setDownloadHandler(WebViewDownloadHandler.DEFAULT);
                append("handler = DEFAULT (stock save dialog)");
            }
        });

        JCheckBox refuse = new JCheckBox("Refuse every download (null handler)");
        refuse.addActionListener(e -> {
            if (refuse.isSelected()) {
                wv.setDownloadHandler(null);
                silent.setSelected(false);
                silent.setEnabled(false);
                append("handler = null (drop: every download refused, no UI)");
            } else {
                silent.setEnabled(true);
                wv.setDownloadHandler(WebViewDownloadHandler.DEFAULT);
                append("handler = DEFAULT (stock save dialog)");
            }
        });

        JPanel controls = new JPanel(new BorderLayout());
        JPanel boxes = new JPanel();
        boxes.add(silent);
        boxes.add(refuse);
        controls.add(boxes, BorderLayout.WEST);
        controls.add(new JLabel(" serving on http://127.0.0.1:" + port + "/ "),
            BorderLayout.EAST);

        frame.add(controls, BorderLayout.NORTH);
        frame.add(wv, BorderLayout.CENTER);
        frame.add(new JScrollPane(log), BorderLayout.SOUTH);
        frame.setVisible(true);

        append("handler = DEFAULT (stock save dialog)");
        wv.setUrl("http://127.0.0.1:" + port + "/");
    }

    /** Handler that chooses a destination with no UI and logs every
     *  event, so progress coalescing and exactly-once completion are
     *  directly observable. */
    private static final class LoggingHandler
            implements WebViewDownloadHandler {
        @Override public File downloadRequested(WebViewDownloadEvent e) {
            File dest = new File(silentDir, e.suggestedFileName());
            append("requested  id=" + e.id()
                + " name=" + e.suggestedFileName()
                + " mime=" + e.mimeType()
                + " size=" + (e.sizeKnown() ? e.totalBytes() : "unknown")
                + " -> " + dest);
            return dest;
        }
        @Override public void downloadProgress(
                WebViewDownloadProgressEvent e) {
            append("progress   id=" + e.id()
                + " " + e.receivedBytes() + "/"
                + (e.sizeKnown() ? String.valueOf(e.totalBytes()) : "?")
                + (e.sizeKnown()
                    ? String.format(" (%.0f%%)", e.fraction() * 100.0) : ""));
        }
        @Override public void downloadCompleted(
                WebViewDownloadCompleteEvent e) {
            append("completed  id=" + e.id()
                + " success=" + e.success()
                + " bytes=" + e.receivedBytes()
                + (e.success() ? " -> " + e.destination()
                               : " reason=" + e.failureReason()));
        }
    }

    private static void append(final String line) {
        final String stamped =
            new SimpleDateFormat("HH:mm:ss.SSS").format(new Date())
            + "  " + line;
        if (SwingUtilities.isEventDispatchThread()) {
            log.append(stamped + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() { append(line); }
            });
        }
    }

    // -----------------------------------------------------------------
    // Loopback server serving the five download shapes
    // -----------------------------------------------------------------

    private static int startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                byte[] body = INDEX_HTML.getBytes(Charset.forName("UTF-8"));
                x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, body.length);
                write(x, body);
            }
        });

        // 1. <a download> on a plain text/plain body.
        server.createContext("/notes.txt", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                byte[] body = fill(SMALL_BYTES);
                x.getResponseHeaders().add("Content-Type", "text/plain");
                x.sendResponseHeaders(200, body.length);
                write(x, body);
            }
        });

        // 2. Content-Disposition: attachment, with a space in the name.
        server.createContext("/report", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                byte[] body = fill(SMALL_BYTES);
                x.getResponseHeaders().add("Content-Type", "text/plain");
                x.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"Q3 report.txt\"");
                x.sendResponseHeaders(200, body.length);
                write(x, body);
            }
        });

        // 3. A body the engine will not render inline.
        server.createContext("/bundle.zip", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                byte[] body = zipOf(fill(SMALL_BYTES));
                x.getResponseHeaders().add("Content-Type", "application/zip");
                x.sendResponseHeaders(200, body.length);
                write(x, body);
            }
        });

        // 4. 10 MB with a declared Content-Length -> known-size progress.
        server.createContext("/big.bin", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                x.getResponseHeaders().add("Content-Type", "application/octet-stream");
                x.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"big.bin\"");
                x.sendResponseHeaders(200, BIG_BYTES);
                streamTo(x, BIG_BYTES);
            }
        });

        // 5. Chunked, no Content-Length -> unknown-size progress.
        server.createContext("/stream.bin", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                x.getResponseHeaders().add("Content-Type", "application/octet-stream");
                x.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"stream.bin\"");
                x.sendResponseHeaders(200, 0);   // 0 => chunked
                streamTo(x, BIG_BYTES / 4);
            }
        });

        server.setExecutor(null);
        server.start();
        return server.getAddress().getPort();
    }

    private static void write(HttpExchange x, byte[] body) throws IOException {
        OutputStream os = x.getResponseBody();
        try {
            os.write(body);
        } finally {
            os.close();
        }
    }

    private static void streamTo(HttpExchange x, int total) throws IOException {
        OutputStream os = x.getResponseBody();
        try {
            byte[] chunk = fill(64 * 1024);
            int sent = 0;
            while (sent < total) {
                int n = Math.min(chunk.length, total - sent);
                os.write(chunk, 0, n);
                sent += n;
                // Slow it down enough that progress is legible.
                try { Thread.sleep(2L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            os.close();
        }
    }

    private static byte[] fill(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    private static byte[] zipOf(byte[] content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        try {
            zos.putNextEntry(new ZipEntry("payload.bin"));
            zos.write(content);
            zos.closeEntry();
        } finally {
            zos.close();
        }
        return bos.toByteArray();
    }

    private static final String INDEX_HTML =
        "<!doctype html><meta charset=utf-8>"
        + "<style>body{font:15px system-ui,sans-serif;margin:2rem;max-width:44rem}"
        + "li{margin:.6rem 0}code{background:#eee;padding:.1rem .3rem;border-radius:3px}"
        + "</style>"
        + "<h2>WebView download demo</h2>"
        + "<p>Each link exercises a different download trigger. Watch the log"
        + " below the browser.</p>"
        + "<ul>"
        + "<li><a href=\"/notes.txt\" download=\"notes.txt\">notes.txt</a>"
        + " &mdash; an <code>&lt;a download&gt;</code> on a renderable body</li>"
        + "<li><a href=\"/report\">report</a>"
        + " &mdash; <code>Content-Disposition: attachment</code>,"
        + " filename with a space</li>"
        + "<li><a href=\"/bundle.zip\">bundle.zip</a>"
        + " &mdash; a body the engine will not render inline</li>"
        + "<li><a href=\"/big.bin\">big.bin</a>"
        + " &mdash; 10&nbsp;MB with a declared <code>Content-Length</code>"
        + " (known-size progress)</li>"
        + "<li><a href=\"/stream.bin\">stream.bin</a>"
        + " &mdash; chunked, no <code>Content-Length</code>"
        + " (unknown-size progress)</li>"
        + "</ul>";

    private WebViewDownloadDemo() { }
}
