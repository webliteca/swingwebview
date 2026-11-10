/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.PrintOutcome;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletionException;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The native print dialog (Canvas 38).  Loads a document about four printed
 * pages long and offers: a Swing "Print…" button ({@code WebViewComponent.print()},
 * whose outcome is shown), a button inside the page that calls
 * {@code window.print()}, and a "Deny page prints" checkbox that installs a
 * {@code WebViewPrintHandler} refusing the page's requests.
 *
 * <p>Check by hand (see the README): the dialog's preview shows four pages
 * starting at the top of the document even when scrolled; Cancel reports
 * CANCELLED; the page's button opens the same dialog unless denied.
 */
public class WebViewPrintDemo {

    public static void main(String[] args) throws Exception {
        final File html = File.createTempFile("webview-print-demo", ".html");
        html.deleteOnExit();
        Files.write(html.toPath(), page().getBytes(StandardCharsets.UTF_8));
        SwingUtilities.invokeLater(() -> show(html));
    }

    private static String page() {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset='utf-8'><title>Print demo</title>")
         .append("<style>body{font:16px/1.6 serif;margin:2em}h1{margin-top:0}")
         .append("@media print{button{display:none}}</style></head><body>")
         .append("<h1>Native print dialog demo</h1>")
         .append("<p><button id='p' onclick='window.print()'>window.print()</button></p>");
        for (int i = 1; i <= 120; i++) {
            b.append("<p>Paragraph ").append(i)
             .append(" — the dialog's preview should show every page of this document, ")
             .append("starting at the top, whatever is scrolled into view.</p>");
        }
        b.append("<p><strong>The last line of the document.</strong></p></body></html>");
        return b.toString();
    }

    private static void show(File html) {
        JFrame frame = new JFrame("WebView Print Demo");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        final WebViewComponent wv = WebViewComponent.create();
        final JLabel status = new JLabel("Print dialog supported: "
                + WebViewComponent.isPrintDialogSupported());
        JButton print = new JButton("Print…");
        print.addActionListener(e -> {
            status.setText("Printing…");
            wv.print().whenComplete((PrintOutcome o, Throwable t) -> {
                if (t != null) {
                    Throwable c = t instanceof CompletionException && t.getCause() != null
                            ? t.getCause() : t;
                    status.setText("Failed: " + c.getMessage());
                } else {
                    status.setText("Outcome: " + o);
                }
            });
        });
        final JCheckBox deny = new JCheckBox("Deny page prints");
        deny.addActionListener(e -> wv.setPrintHandler(deny.isSelected()
                ? (c, url) -> {
                    status.setText("Denied a page print from " + url);
                    return false;
                }
                : null));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(print);
        bar.add(deny);
        bar.add(status);
        frame.getContentPane().add(bar, BorderLayout.NORTH);
        frame.getContentPane().add(wv, BorderLayout.CENTER);
        frame.setSize(900, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        wv.setUrl(html.toURI().toString());
    }
}
