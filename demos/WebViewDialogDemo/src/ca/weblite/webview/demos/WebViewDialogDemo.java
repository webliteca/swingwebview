/*
 * MIT License
 *
 * Exercises the browser-initiated UI dialog API on WebViewComponent.
 * Designed for manual verification against the acceptance criteria in
 *   requirements/[User-story-4]browser-initiated-ui-dialogs.md
 *
 * Switch among three handler modes via the combo box at the top:
 *   - Default      : framework's stock Swing dialogs (JOptionPane / JFileChooser).
 *   - Custom       : programmatic answers + log entries (no UI).
 *   - Drop (null)  : suppress all dialogs without UI.
 *
 * macOS coverage is wired in STORY-004-001 (this demo's reference story).
 * On Linux and Windows the embedded engine still uses its built-in
 * dialogs until STORY-004-002 and STORY-004-003 land.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.WebViewAlertEvent;
import ca.weblite.webview.WebViewConfirmEvent;
import ca.weblite.webview.WebViewDialogHandler;
import ca.weblite.webview.WebViewFilePickerEvent;
import ca.weblite.webview.WebViewPromptEvent;
import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.io.File;
import java.util.Collections;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

public class WebViewDialogDemo {

    private static final String MODE_DEFAULT = "Default handler (Swing dialogs)";
    private static final String MODE_CUSTOM  = "Custom handler (programmatic answers)";
    private static final String MODE_DROP    = "Drop handler (no UI)";

    public static void main(String[] args) {
        // Heavyweight popups so any nested JFileChooser / JOptionPane
        // pop-ups render above the WebView region on macOS / Windows
        // heavyweight.  Safe in lightweight mode (no-op).
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewDialogDemo::run);
    }

    private static void run() {
        final WebViewComponent wv = WebViewComponent.create();

        // Inline test page: four buttons + two file inputs, each
        // logging its result through console.log so the bottom pane
        // shows the JS-side return value (and proves the JS thread
        // actually resumed after the dialog closed).
        final String html =
            "<!doctype html><html><head><meta charset='utf-8'>"
          + "<title>WebView Dialog Demo</title>"
          + "<style>"
          + "  body{font:14px/1.4 system-ui,sans-serif;padding:1em;"
          + "    max-width:48em;margin:0 auto;}"
          + "  h1{font-size:1.2em;margin:0 0 .4em 0;}"
          + "  button{font-size:14px;margin:0 .4em .4em 0;"
          + "    padding:.3em .6em;}"
          + "  .row{margin:.5em 0;}"
          + "  code{background:#eef;padding:0 .3em;border-radius:.2em;}"
          + "</style></head><body>"
          + "<h1>WebView Dialog Demo</h1>"
          + "<div class='row'>"
          + "  <button onclick=\"alert('hello world'); console.log('alert returned');\">Alert</button>"
          + "  <button onclick=\"console.log('confirm =', confirm('proceed?'));\">Confirm</button>"
          + "  <button onclick=\"console.log('prompt =', JSON.stringify(prompt('name?', 'default')));\">Prompt</button>"
          + "</div>"
          + "<div class='row'>"
          + "  <label>Single file (.png/.jpg): "
          + "    <input type='file' accept='.png,.jpg' "
          + "      onchange=\"console.log('single files =', this.files.length); "
          + "        for(var i=0;i&lt;this.files.length;i++) console.log('  -', this.files[i].name);\">"
          + "  </label>"
          + "</div>"
          + "<div class='row'>"
          + "  <label>Multiple files (image/* + .pdf): "
          + "    <input type='file' accept='image/*,.pdf' multiple "
          + "      onchange=\"console.log('multi files =', this.files.length); "
          + "        for(var i=0;i&lt;this.files.length;i++) console.log('  -', this.files[i].name);\">"
          + "  </label>"
          + "</div>"
          + "<p style='color:#666;font-size:12px'>"
          + "Switch handler mode via the combo box above.  Watch the log "
          + "pane to confirm the JS thread resumes after each dialog."
          + "</p>"
          + "</body></html>";

        // Top: handler-mode picker + label.
        final JComboBox<String> modeCombo = new JComboBox<String>(
            new String[] { MODE_DEFAULT, MODE_CUSTOM, MODE_DROP });
        final JLabel modeLabel = new JLabel(
            "  Handler mode: ", JLabel.LEFT);

        // Bottom: log pane.  Captures both the demo's append() output
        // and the console.* output from the embedded page.
        final JTextArea log = new JTextArea(8, 80);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(false);
        final JScrollPane logScroll = new JScrollPane(log);

        // Wire the mode picker.
        modeCombo.addActionListener(ev -> {
            String mode = (String) modeCombo.getSelectedItem();
            installHandler(wv, mode, line -> append(log, line));
            append(log, "[demo] handler mode → " + mode);
        });

        // Capture console.* into the log pane.
        wv.addConsoleListener(new ConsoleListener() {
            @Override public void onMessage(ConsoleMessage msg) {
                append(log, "[console] " + msg.toString());
            }
        });

        // Top control bar.
        final JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topBar.add(modeLabel);
        topBar.add(modeCombo);

        // Center: WebView + bottom log.
        final JSplitPane center = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, wv, logScroll);
        center.setResizeWeight(0.7);

        final JFrame frame = new JFrame("WebView Dialog Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(topBar, BorderLayout.NORTH);
        frame.getContentPane().add(center, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(900, 700));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Install the initial handler before navigating so the very
        // first alert / confirm / prompt the page might issue is
        // routed through Java.
        installHandler(wv, MODE_DEFAULT, line -> append(log, line));
        append(log, "[demo] handler mode → " + MODE_DEFAULT);

        // Load the inline test page via data: URL.  Using a data: URL
        // (rather than addOnBeforeLoad + about:blank) keeps the demo
        // self-contained and ensures the page's history origin is
        // stable for AC18's pageUrl/frameUrl checks.
        wv.setUrl("data:text/html;charset=utf-8," + urlEncode(html));
    }

    private static void installHandler(
            WebViewComponent wv, String mode, LogSink log) {
        if (MODE_DEFAULT.equals(mode)) {
            wv.setDialogHandler(WebViewDialogHandler.DEFAULT);
            return;
        }
        if (MODE_DROP.equals(mode)) {
            wv.setDialogHandler(null);
            return;
        }
        // MODE_CUSTOM
        wv.setDialogHandler(new WebViewDialogHandler() {
            @Override
            public void alertOpened(WebViewAlertEvent e) {
                log.print("[custom] alertOpened: " + e.message());
            }
            @Override
            public boolean confirmOpened(WebViewConfirmEvent e) {
                log.print("[custom] confirmOpened: " + e.message()
                    + " → returning true");
                return true;
            }
            @Override
            public String promptOpened(WebViewPromptEvent e) {
                log.print("[custom] promptOpened: " + e.message()
                    + " (default=" + e.defaultValue()
                    + ") → returning \"hardcoded\"");
                return "hardcoded";
            }
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                log.print("[custom] filePickerOpened: multiple="
                    + e.multiple()
                    + " extensions=" + e.acceptedExtensions()
                    + " mimeTypes=" + e.acceptedMimeTypes()
                    + " → returning [/tmp/preselected.txt]");
                return Collections.singletonList(
                    new File("/tmp/preselected.txt"));
            }
        });
    }

    private static void append(JTextArea log, String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    /** Tiny URL-encoder for the data: URL.  Only escapes characters
     *  that confuse the URL parsers we care about (space, # / ? & %)
     *  — the inline HTML contains none of those except spaces. */
    private static String urlEncode(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') sb.append("%20");
            else if (c == '#') sb.append("%23");
            else if (c == '%') sb.append("%25");
            else if (c == '?') sb.append("%3F");
            else if (c == '&') sb.append("%26");
            else if (c == '"') sb.append("%22");
            else if (c == '\n') sb.append("%0A");
            else sb.append(c);
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface LogSink { void print(String line); }
}
