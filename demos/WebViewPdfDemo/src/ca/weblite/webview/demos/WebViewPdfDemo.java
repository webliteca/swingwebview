/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.PdfOptions;
import ca.weblite.webview.swing.WebViewComponent;
import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Print-to-PDF demo (Canvas 29).  Loads a two-page report laid out with CSS
 * page boxes — a dark full-bleed first page and a plain second page — and
 * prints it to {@code ~/webview-pdf-demo.pdf} (US Letter, zero margins) and
 * {@code ~/webview-pdf-demo-a4.pdf} (A4, 0.5 in margins).
 *
 * <p>With {@code -Dpdfdemo.auto=true} it prints both files once the page has
 * loaded, plus a print into a missing folder, logs the outcomes and exits
 * (status 0 only when both PDFs were written and the bad path failed).
 */
public class WebViewPdfDemo {

    private static final String PAGE =
        "<!doctype html><html><head><meta charset='utf-8'><style>"
        + "@page { size: 8.5in 11in; margin: 0 }"
        + "html, body { margin: 0; padding: 0 }"
        + ".page { width: 8.5in; height: 11in; box-sizing: border-box;"
        + "  padding: 1in; page-break-after: always; break-after: page;"
        + "  font-family: sans-serif; overflow: hidden }"
        + ".page:last-child { page-break-after: auto; break-after: auto }"
        + ".cover { background: #1b2a41; color: #fff;"
        + "  -webkit-print-color-adjust: exact; print-color-adjust: exact }"
        + "h1 { font-size: 40pt; margin-top: 3in }"
        + "</style></head><body>"
        + "<section class='page cover'><h1>Quarterly Report</h1>"
        + "<p>Page 1 of 2 &mdash; a full-bleed dark cover.</p></section>"
        + "<section class='page'><h2>Details</h2>"
        + "<p>Page 2 of 2 &mdash; plain white.</p></section>"
        + "</body></html>";

    private static JTextArea log;

    public static void main(String[] args) throws IOException {
        final File html = File.createTempFile("webview-pdf-demo", ".html");
        html.deleteOnExit();
        Files.write(html.toPath(), PAGE.getBytes(StandardCharsets.UTF_8));
        final boolean auto = Boolean.getBoolean("pdfdemo.auto");
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { buildUi(html, auto); }
        });
    }

    private static void buildUi(File html, boolean auto) {
        JFrame frame = new JFrame("WebView PDF Demo");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(900, 720);
        frame.setLocationRelativeTo(null);

        final WebViewComponent wv = WebViewComponent.create();
        log = new JTextArea(8, 80);
        log.setEditable(false);
        log.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        final File home = new File(System.getProperty("user.home"));
        final File letter = new File(home, "webview-pdf-demo.pdf");
        final File a4 = new File(home, "webview-pdf-demo-a4.pdf");

        JButton printLetter = new JButton("Print Letter PDF");
        printLetter.addActionListener(e -> report("letter", wv.printToPdf(letter)));
        JButton printA4 = new JButton("Print A4 PDF (0.5in margins)");
        printA4.addActionListener(e -> report("a4",
            wv.printToPdf(a4, PdfOptions.a4().withMargins(0.5))));
        JButton badPath = new JButton("Print into a missing folder");
        badPath.addActionListener(e -> report("missing-folder",
            wv.printToPdf(new File(home, "no-such-folder/x.pdf"))));

        JPanel controls = new JPanel();
        controls.add(printLetter);
        controls.add(printA4);
        controls.add(badPath);
        frame.add(controls, BorderLayout.NORTH);
        frame.add(wv, BorderLayout.CENTER);
        frame.add(new JScrollPane(log), BorderLayout.SOUTH);
        frame.setVisible(true);

        append("PDF printing supported: "
            + WebViewComponent.isPdfPrintingSupported());
        wv.setUrl(html.toURI().toString());

        if (auto) {
            Timer t = new Timer(4000, e -> {
                CompletableFuture<File> f1 = report("letter", wv.printToPdf(letter));
                CompletableFuture<File> f2 = report("a4",
                    wv.printToPdf(a4, PdfOptions.a4().withMargins(0.5)));
                CompletableFuture<File> f3 = report("missing-folder",
                    wv.printToPdf(new File(home, "no-such-folder/x.pdf")));
                CompletableFuture.allOf(f1, f2).handle((v, err) -> {
                    boolean ok = err == null && f3.isCompletedExceptionally()
                        && letter.length() > 0 && a4.length() > 0;
                    // Exit after the EDT has logged every result.
                    SwingUtilities.invokeLater(() -> {
                        System.out.println("PDF demo " + (ok ? "PASSED" : "FAILED"));
                        System.exit(ok ? 0 : 1);
                    });
                    return null;
                });
            });
            t.setRepeats(false);
            t.start();
            Timer watchdog = new Timer(60000, e -> {
                System.out.println("PDF demo FAILED: timed out");
                System.exit(2);
            });
            watchdog.setRepeats(false);
            watchdog.start();
        }
    }

    private static CompletableFuture<File> report(final String label,
                                                  CompletableFuture<File> f) {
        append(label + ": printing ...");
        f.whenComplete((file, err) -> append(label + ": "
            + (err == null ? "wrote " + file + " (" + file.length()
                    + " bytes, on EDT: "
                    + SwingUtilities.isEventDispatchThread() + ")"
                : "failed - " + err.getMessage())));
        return f;
    }

    private static void append(String line) {
        System.out.println(line);
        if (log != null) {
            log.append(line + "\n");
        }
    }
}
