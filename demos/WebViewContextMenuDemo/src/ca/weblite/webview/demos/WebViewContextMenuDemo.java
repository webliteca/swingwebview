/*
 * MIT License
 *
 * Exercises the right-click context-menu API on WebViewComponent:
 * WebViewMouseListener / WebViewMouseEvent / DomTarget / WebViewContextMenu,
 * plus the setDefaultContextMenuEnabled override.  Designed for manual
 * verification against the acceptance criteria listed in
 *   requirements/[User-story-2]webview-context-menu-and-dom-mouse-events.md
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.DomTarget;
import ca.weblite.webview.WebViewMouseEvent;
import ca.weblite.webview.swing.WebViewComponent;
import ca.weblite.webview.swing.WebViewContextMenu;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewContextMenuDemo {

    public static void main(String[] args) {
        // Heavyweight prerequisite: force JPopupMenu to use heavyweight
        // top-levels so popups paint ABOVE the native WebView region.
        // No effect on the lightweight component, safe to set globally.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewContextMenuDemo::run);
    }

    private static void run() {
        logPlatformPath();

        final WebViewComponent wv = WebViewComponent.create();

        // Inline page that exercises every DomTarget field path:
        //   - plain text + selection
        //   - link (with nested span so AC5's nearest-ancestor walk fires)
        //   - image with alt/title
        //   - editable input, password input (value MUST be scrubbed),
        //     and a textarea
        //   - data-* attributes (AC: curated attrs map)
        wv.setUrl("data:text/html;charset=utf-8,"
            + "<!doctype html><html><head><meta charset='utf-8'>"
            + "<title>Context menu demo</title>"
            + "<style>body{font-family:sans-serif;padding:24px;line-height:1.6}"
            + "h2{margin-top:0}.box{border:1px solid #ccc;padding:12px;margin:8px 0}"
            + "</style></head><body>"
            + "<h2 id='title' class='hero big' data-section='heading'>Right-click anywhere</h2>"
            + "<div class='box'>"
            + "<p>Plain text paragraph. Select some of <b>this text</b>"
            + " and right-click to see <code>DomTarget.selectionText()</code>.</p>"
            + "</div>"
            + "<div class='box'>"
            + "<p>Right-click the <a href='https://example.com/foo' data-track='cta'>"
            + "link with a <span>nested span</span></a> to exercise"
            + " <code>linkHref()</code> (the nearest-ancestor walk surfaces"
            + " the href even when the click lands on the span).</p>"
            + "</div>"
            + "<div class='box'>"
            + "<p>Right-click the image to see <code>imageSrc()</code>:</p>"
            + "<img src='data:image/svg+xml;utf8,"
            +   "%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%22120%22 height=%2280%22%3E"
            +   "%3Crect width=%22120%22 height=%2280%22 fill=%22%23bcd%22/%3E"
            +   "%3Ctext x=%2260%22 y=%2245%22 font-family=%22sans-serif%22 font-size=%2218%22"
            +     " text-anchor=%22middle%22%3Ekitten%3C/text%3E"
            +   "%3C/svg%3E' alt='a kitten' title='hover for title'>"
            + "</div>"
            + "<div class='box'>"
            + "<p>Editable: <input type='text' value='some text' name='note'>"
            + " (password: <input type='password' value='hunter2' name='pw'>"
            + " &mdash; the value MUST appear as the empty string)</p>"
            + "<p><textarea rows='2' cols='40'>typing here</textarea></p>"
            + "</div>"
            + "<p style='color:#888;font-size:12px'>Tip: use the Demo menu"
            + " to flip the platform-default override on/off and watch the"
            + " behaviour change.</p>"
            + "</body></html>");

        wv.setPreferredSize(new Dimension(900, 540));

        // ----- Event-log panel -----
        final JTextArea log = new JTextArea(12, 80);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(log);

        // Also pipe page console output through, useful when debugging
        // a builder.
        wv.addConsoleListener(new ConsoleListener() {
            @Override
            public void onMessage(ConsoleMessage msg) {
                log.append("[console] " + msg.toString() + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }
        });

        // ----- WebViewContextMenu: build a context-aware popup -----
        new WebViewContextMenu(event -> {
            String edtTag = SwingUtilities.isEventDispatchThread()
                ? "" : "  [NOT-EDT!]";
            log.append("[ctx] " + event.toString() + edtTag + "\n");
            log.append("      target=" + event.target().toString() + "\n");
            if (!event.target().selectionText().isEmpty()) {
                log.append("      selection=\"" + event.target().selectionText() + "\"\n");
            }
            if (!event.target().attributes().isEmpty()) {
                log.append("      attrs=" + event.target().attributes() + "\n");
            }
            log.setCaretPosition(log.getDocument().getLength());

            JPopupMenu menu = new JPopupMenu();
            DomTarget t = event.target();

            // Header: descriptive (disabled) item.
            JMenuItem header = new JMenuItem(t.tagName()
                + (t.id().isEmpty() ? "" : ("#" + t.id()))
                + " @ (" + event.clientX() + "," + event.clientY() + ")");
            header.setEnabled(false);
            menu.add(header);
            menu.addSeparator();

            if (t.linkHref() != null) {
                JMenuItem open = new JMenuItem("Open Link in Browser");
                open.addActionListener(a ->
                    log.append("[demo] open: " + t.linkHref() + "\n"));
                menu.add(open);
                JMenuItem copyLink = new JMenuItem("Copy Link URL");
                copyLink.addActionListener(a ->
                    log.append("[demo] copy link: " + t.linkHref() + "\n"));
                menu.add(copyLink);
            }
            if (t.imageSrc() != null) {
                String shortSrc = t.imageSrc().length() > 40
                    ? t.imageSrc().substring(0, 40) + "..." : t.imageSrc();
                JMenuItem copyImg = new JMenuItem("Copy Image URL: " + shortSrc);
                copyImg.addActionListener(a ->
                    log.append("[demo] copy image src\n"));
                menu.add(copyImg);
            }
            if (t.mediaSrc() != null) {
                menu.add(new JMenuItem("Copy Media URL"));
            }
            if (t.isContentEditable()) {
                menu.add(new JMenuItem("Cut"));
                menu.add(new JMenuItem("Copy"));
                menu.add(new JMenuItem("Paste"));
                menu.add(new JMenuItem("Select All"));
            }
            if (!t.selectionText().isEmpty()) {
                String sel = t.selectionText();
                if (sel.length() > 30) sel = sel.substring(0, 30) + "...";
                JMenuItem copySel = new JMenuItem("Copy Selection: \"" + sel + "\"");
                copySel.addActionListener(a ->
                    log.append("[demo] copy selection of " +
                        t.selectionText().length() + " chars\n"));
                menu.add(copySel);
            }

            // Always-present fallback when no context-specific items applied.
            if (menu.getComponentCount() <= 2) {
                menu.add(new JMenuItem("Inspect..."));
            }
            return menu;
        }).attachTo(wv);

        // ----- Demo menu (toggle the default-menu override) -----
        JMenuBar menuBar = new JMenuBar();
        JMenu demo = new JMenu("Demo");

        JCheckBoxMenuItem enableDefault = new JCheckBoxMenuItem(
            "Enable platform default context menu (override)", false);
        enableDefault.addActionListener(a -> {
            wv.setDefaultContextMenuEnabled(enableDefault.isSelected());
            log.append("[demo] setDefaultContextMenuEnabled("
                + enableDefault.isSelected() + ") -> isDefaultContextMenuEnabled() = "
                + wv.isDefaultContextMenuEnabled()
                + "  (effective suppress depends on listener count)\n");
        });
        demo.add(enableDefault);

        JMenuItem reservedTest = new JMenuItem(
            "Try binding reserved name (should throw)");
        reservedTest.addActionListener(a -> {
            try {
                wv.addJavascriptCallback("__webview_dom_event",
                    arg -> log.append("[demo] unreachable\n"));
                log.append("[demo] BUG: reserved name accepted!\n");
            } catch (IllegalArgumentException ex) {
                log.append("[demo] reserved-name reject OK: "
                    + ex.getMessage() + "\n");
            }
        });
        demo.add(reservedTest);

        menuBar.add(demo);

        // ----- Frame layout -----
        JFrame frame = new JFrame("WebView Context Menu demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(menuBar);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wv, logScroll);
        split.setResizeWeight(0.65);
        frame.add(split, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
    }

    private static void logPlatformPath() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");
        WebViewComponent.Mode mode = WebViewComponent.resolveDefaultMode();
        System.err.println("[demo] platform: os.name=\"" + osName
            + "\" os.arch=\"" + osArch + "\" -> mode=" + mode);
        System.err.println("[demo] right-click anywhere in the page; the"
            + " Java-built JPopupMenu should appear at the click point.");
        System.err.println("[demo] the platform default context menu should"
            + " NOT appear until you check 'Enable platform default' in the"
            + " Demo menu.");
    }
}
