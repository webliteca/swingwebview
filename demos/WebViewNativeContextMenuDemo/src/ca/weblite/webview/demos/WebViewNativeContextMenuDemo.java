/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.WebViewContextMenuContext;
import ca.weblite.webview.WebViewContextMenuContributor;
import ca.weblite.webview.WebViewContextMenuItem;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Demo for application items in the engine's own context menu (Canvas 36,
 * STORY-012-001).
 *
 * <p>Right-click the page: the engine's own menu opens (on Linux, at the
 * pointer), with every native item, and the application's items appended
 * after a separator: <b>Ask</b> when there is selected text, <b>Open Link
 * Externally…</b> on a link, a disabled sample, and a label with
 * {@code _}, {@code &} and markup that must show literally.  Chosen items
 * are logged with what the click hit.
 *
 * <p>In auto mode ({@code -Dncmdemo.auto=true}, or {@code NCMDEMO_AUTO=1}
 * through the run scripts) the demo selects the paragraph, right-clicks it
 * with a {@link Robot}, captures the screen to {@code build/ncmdemo-menu.png}
 * (or {@code -Dncmdemo.shot=<file>}), chooses <b>Ask</b> from the keyboard,
 * checks what it was told, prints {@code PASS} or {@code FAIL} and exits 0 or 1.
 */
public class WebViewNativeContextMenuDemo {

    private static final String SELECTED = "Hello selectable world";

    private static final String PAGE = "<!doctype html><html><head><meta charset='utf-8'>"
        + "<style>body{font:20px sans-serif;margin:32px}"
        + "p#p{font-size:28px}input,textarea{font-size:18px;display:block;margin:12px 0}</style>"
        + "</head><body>"
        + "<p id='p'>" + SELECTED + "</p>"
        + "<p>Select some text and right-click it for <b>Ask</b>. "
        + "<a href='https://example.com/docs'>A link</a> gets <b>Open Link Externally…</b>.</p>"
        + "<img alt='square' width='60' height='60' src=\"data:image/svg+xml,"
        + "%3Csvg xmlns='http://www.w3.org/2000/svg' width='60' height='60'%3E"
        + "%3Crect width='60' height='60' fill='%23c33'/%3E%3C/svg%3E\">"
        + "<input value='text field: select me'>"
        + "<textarea rows='2' cols='40'>textarea: select me too</textarea>"
        + "</body></html>";

    public static void main(String[] args) throws Exception {
        final boolean auto = Boolean.getBoolean("ncmdemo.auto");
        System.out.println("isContextMenuContributionSupported = "
            + WebViewComponent.isContextMenuContributionSupported());
        if (auto && !WebViewComponent.isContextMenuContributionSupported()) {
            System.out.println("FAIL");
            System.exit(1);
        }

        final AtomicReference<WebViewContextMenuContext> asked =
            new AtomicReference<WebViewContextMenuContext>();
        final AtomicReference<Boolean> askedOnEdt = new AtomicReference<Boolean>();
        final CountDownLatch askLatch = new CountDownLatch(1);
        final AtomicReference<WebViewComponent> webRef = new AtomicReference<WebViewComponent>();
        final AtomicReference<JFrame> frameRef = new AtomicReference<JFrame>();
        final JTextArea log = new JTextArea(8, 80);
        log.setEditable(false);

        final Consumer<String> logLine = new Consumer<String>() {
            @Override public void accept(final String s) {
                System.out.println(s);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() { log.append(s + "\n"); }
                });
            }
        };

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                JFrame f = new JFrame("Native context menu demo (Canvas 36)");
                f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                WebViewComponent web = WebViewComponent.create();
                web.setDebug(true);
                web.addContextMenuContributor(new WebViewContextMenuContributor() {
                    // Runs on the engine's UI thread, off the EDT: fast, no Swing.
                    @Override
                    public List<WebViewContextMenuItem> contribute(WebViewContextMenuContext c) {
                        logLine.accept("contribute: " + c);
                        List<WebViewContextMenuItem> items = new ArrayList<WebViewContextMenuItem>();
                        if (c.hasSelection()) {
                            items.add(WebViewContextMenuItem.of("ask", "Ask",
                                new Consumer<WebViewContextMenuContext>() {
                                    @Override public void accept(WebViewContextMenuContext ctx) {
                                        logLine.accept("Ask chosen: \"" + ctx.selectionText()
                                            + "\" bounds=" + ctx.selectionBounds()
                                            + " point=" + ctx.point()
                                            + " screen=" + ctx.toScreenPoint());
                                        askedOnEdt.set(SwingUtilities.isEventDispatchThread());
                                        asked.set(ctx);
                                        askLatch.countDown();
                                    }
                                }));
                        }
                        if (auto) return items;   // keep Ask last for the keyboard pick
                        if (c.linkUrl() != null) {
                            items.add(WebViewContextMenuItem.of("open-link",
                                "Open Link Externally…", logChoice(logLine)));
                        }
                        items.add(WebViewContextMenuItem.of("disabled", "Disabled sample",
                            logChoice(logLine)).withEnabled(false));
                        items.add(WebViewContextMenuItem.of("literal",
                            "Literal: snake_case & <b>no markup</b> 😀",
                            logChoice(logLine)));
                        return items;
                    }
                });
                web.setUrl("data:text/html;charset=utf-8;base64,"
                    + Base64.getEncoder().encodeToString(
                        PAGE.getBytes(StandardCharsets.UTF_8)));
                f.getContentPane().add(web, BorderLayout.CENTER);
                f.getContentPane().add(new JScrollPane(log), BorderLayout.SOUTH);
                f.setBounds(60, 40, 900, 640);
                f.setVisible(true);
                webRef.set(web);
                frameRef.set(f);
            }
        });

        if (!auto) return;

        boolean pass = false;
        try {
            pass = runAuto(webRef.get(), asked, askedOnEdt, askLatch);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private static Consumer<WebViewContextMenuContext> logChoice(final Consumer<String> logLine) {
        return new Consumer<WebViewContextMenuContext>() {
            @Override public void accept(WebViewContextMenuContext ctx) {
                logLine.accept("chosen with " + ctx);
            }
        };
    }

    /** The automatic check: select, right-click, capture, choose Ask, verify. */
    private static boolean runAuto(final WebViewComponent web,
                                   AtomicReference<WebViewContextMenuContext> asked,
                                   AtomicReference<Boolean> askedOnEdt,
                                   CountDownLatch askLatch) throws Exception {
        Thread.sleep(3000);   // page load
        String rect = web.evalAsync(
            "return (function(){var p=document.getElementById('p');"
            + "var r=document.createRange();r.selectNodeContents(p);"
            + "var s=getSelection();s.removeAllRanges();s.addRange(r);"
            + "var b=r.getBoundingClientRect();"
            + "return Math.round(b.left)+','+Math.round(b.top)+','"
            + "+Math.round(b.width)+','+Math.round(b.height);})()")
            .get(10, TimeUnit.SECONDS);
        System.out.println("selection rect = " + rect);
        String[] parts = rect.replace("\"", "").split(",");
        final Rectangle sel = new Rectangle(Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()),
            Integer.parseInt(parts[3].trim()));
        final AtomicReference<Point> origin = new AtomicReference<Point>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { origin.set(web.getLocationOnScreen()); }
        });
        int cx = sel.x + sel.width / 2;
        int cy = sel.y + sel.height / 2;
        Robot robot = new Robot();
        robot.setAutoDelay(40);
        robot.mouseMove(origin.get().x + cx, origin.get().y + cy);
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        Thread.sleep(1500);
        File shot = new File(System.getProperty("ncmdemo.shot", "build/ncmdemo-menu.png"));
        if (shot.getParentFile() != null) shot.getParentFile().mkdirs();
        BufferedImage img = robot.createScreenCapture(
            new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize()));
        ImageIO.write(img, "png", shot);
        System.out.println("menu captured to " + shot.getAbsolutePath());
        // GTK and Win32 menus: Up from no selection highlights the last item,
        // which is Ask in auto mode.
        robot.keyPress(KeyEvent.VK_UP);
        robot.keyRelease(KeyEvent.VK_UP);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        if (!askLatch.await(5, TimeUnit.SECONDS)) {
            System.out.println("check Ask chosen: FAIL (never told)");
            return false;
        }
        WebViewContextMenuContext c = asked.get();
        boolean ok = true;
        ok &= check("Ask chosen on the EDT", Boolean.TRUE.equals(askedOnEdt.get()));
        ok &= check("selection text", SELECTED.equals(c.selectionText()));
        Rectangle b = c.selectionBounds();
        ok &= check("selection bounds", b != null && Math.abs(b.x - sel.x) <= 2
            && Math.abs(b.y - sel.y) <= 2 && Math.abs(b.width - sel.width) <= 2);
        Point p = c.point();
        ok &= check("click point", p != null && Math.abs(p.x - cx) <= 2 && Math.abs(p.y - cy) <= 2);
        ok &= check("source", c.source() == web);
        return ok;
    }

    private static boolean check(String name, boolean ok) {
        System.out.println("check " + name + ": " + (ok ? "ok" : "FAIL"));
        return ok;
    }
}
