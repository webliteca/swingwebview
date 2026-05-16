/*
 * MIT License
 *
 * Exercises the DevTools-open API and the JS-console-capture API of
 * WebViewComponent.  Designed for manual verification against the
 * acceptance criteria listed in
 *   requirements/[User-story-1]swing-webview-devtools-and-console-api.md
 *
 * See the README in this demo's directory for which AC each menu
 * action / observation maps to.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.swing.WebViewComponent;

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

public class WebViewConsoleDemo {

    public static void main(String[] args) {
        // The heavyweight component places a native peer above lightweight
        // Swing menu popups; switching the toolkit defaults makes our
        // Tools menu's popup appear above the WebView region too.  Safe
        // for lightweight mode (no effect).
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewConsoleDemo::run);
    }

    private static void run() {
        logPlatformPath();

        // Use the factory so the platform default (lightweight on Linux,
        // heavyweight elsewhere) is picked, matching what most callers do.
        final WebViewComponent wv = WebViewComponent.create();

        // setDebug(true) MUST happen BEFORE the component is displayed.
        // openDevTools() returns false otherwise (AC5 / AC6 of
        // STORY-001-001).  Without this the inspector wouldn't open on
        // Linux or Windows.
        wv.setDebug(true);

        // A self-contained page that exercises every console.* level on a
        // button click and emits one console.log at page load so console
        // listeners see at least one message immediately.  Inline data:
        // URI -> no network required.
        wv.setUrl("data:text/html;charset=utf-8,"
            + "<!doctype html><html><head><meta charset='utf-8'>"
            + "<title>Console demo</title></head><body style='font-family:sans-serif;padding:24px'>"
            + "<h2>WebView console capture</h2>"
            + "<p>Click each button to fire that console method.  All five"
            + "  levels and source/line info should appear in the Java"
            + "  capture panel below.</p>"
            + "<p>"
            + "<button onclick=\"console.log('log()', 'with', 42)\">console.log</button> "
            + "<button onclick=\"console.info('hi from info()')\">console.info</button> "
            + "<button onclick=\"console.warn('careful — warn()')\">console.warn</button> "
            + "<button onclick=\"console.error('oops — error()')\">console.error</button> "
            + "<button onclick=\"console.debug('debug() trace')\">console.debug</button>"
            + "</p>"
            + "<p>"
            + "<button onclick=\"console.log({a:1, b:[2,3]}, 'mixed', 4.5)\">log(non-string args)</button> "
            + "<button onclick=\"for(var i=0;i&lt;5;i++)console.log('burst', i)\">log burst x5</button> "
            + "<button onclick=\"console.log()\">log() with no args</button>"
            + "</p>"
            + "<script>console.log('hello from page load');</script>"
            + "</body></html>");

        wv.setPreferredSize(new Dimension(900, 500));

        // ----- Console-capture panel (a JTextArea) -----
        final JTextArea capture = new JTextArea(12, 80);
        capture.setEditable(false);
        capture.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane captureScroll = new JScrollPane(capture);

        // The listener runs on the EDT (AC7) so directly touching
        // JTextArea state is safe.  Each ConsoleMessage uses the
        // canonical toString() format declared in canvas-5 norms:
        //     [LEVEL] source:line text
        ConsoleListener listener = new ConsoleListener() {
            @Override
            public void onMessage(ConsoleMessage msg) {
                String edtTag = SwingUtilities.isEventDispatchThread()
                    ? "" : "  [NOT-EDT!]";
                capture.append(msg.toString() + edtTag + "\n");
                capture.setCaretPosition(capture.getDocument().getLength());
            }
        };
        wv.addConsoleListener(listener);

        // ----- Tools menu -----
        JMenuBar menuBar = new JMenuBar();
        JMenu tools = new JMenu("Tools");

        JMenuItem openDevTools = new JMenuItem("Open DevTools");
        openDevTools.addActionListener(e -> {
            boolean opened = wv.openDevTools();
            System.err.println("[demo] openDevTools() -> " + opened
                + " (on macOS this is expected to be false; on Linux/Windows"
                + " with debug=true expect true)");
            capture.append("[demo] openDevTools() returned: " + opened + "\n");
        });
        tools.add(openDevTools);

        JCheckBoxMenuItem mirrorToStdout =
            new JCheckBoxMenuItem("Mirror console to System.out", false);
        mirrorToStdout.addActionListener(e -> {
            if (mirrorToStdout.isSelected()) {
                wv.setConsoleOutput(System.out);
                capture.append("[demo] setConsoleOutput(System.out) installed\n");
            } else {
                wv.setConsoleOutput(null);
                capture.append("[demo] setConsoleOutput(null) cleared\n");
            }
        });
        tools.add(mirrorToStdout);

        JMenuItem reservedNameTest =
            new JMenuItem("Try reserved binding (should throw)");
        reservedNameTest.addActionListener(e -> {
            try {
                wv.addJavascriptCallback("__webview_console__",
                    arg -> capture.append("[demo] unreachable\n"));
                capture.append("[demo] BUG: reserved name not rejected!\n");
            } catch (IllegalArgumentException ex) {
                capture.append("[demo] reserved-name reject OK: "
                    + ex.getMessage() + "\n");
            }
        });
        tools.add(reservedNameTest);

        menuBar.add(tools);

        // ----- Frame layout -----
        JFrame frame = new JFrame("WebView Console + DevTools demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(menuBar);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wv, captureScroll);
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
        System.err.println("[demo] expected DevTools behaviour:");
        System.err.println("[demo]   Linux heavyweight   -> opens WebKitGTK Web Inspector window (AC1)");
        System.err.println("[demo]   Linux lightweight   -> opens WebKitGTK Web Inspector window (AC4)");
        System.err.println("[demo]   Windows heavyweight -> opens Chromium DevTools window (AC2)");
        System.err.println("[demo]   macOS  heavyweight  -> returns false; right-click -> Inspect Element instead (AC3)");
    }
}
