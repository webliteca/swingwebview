/*
 * MIT License
 *
 * Reproduces an EDT <-> AppKit main-thread mutual-wait deadlock that the
 * v1.0.5 cocoa_run_on_main bridge (PR #30, commit dcda4cf) can exhibit
 * when a JavaScript callback synchronously rendezvous with the EDT at
 * the same instant the EDT enters cocoa_run_on_main.
 *
 * Mechanism:
 *
 *   (1) The page calls a bound JS function on setInterval(1ms). Each
 *       call runs the WKScriptMessageHandler on the AppKit main thread,
 *       which JNI-calls into the Java callback "poke". The callback
 *       does SwingUtilities.invokeAndWait(R) -- posts R to the EDT
 *       queue and blocks the main thread on a semaphore until R runs.
 *
 *   (2) A javax.swing.Timer fires a synthetic Cmd-C KeyEvent at 10ms
 *       cadence, dispatched via KeyboardFocusManager.dispatchEvent so
 *       WebViewHeavyweightComponent's editingShortcutDispatcher picks
 *       it up regardless of who has Swing focus. Focus is parked on a
 *       JTextField outside the WebView, so handleEditingShortcut
 *       evaluates embedded.isNativeFirstResponder() -- which fires the
 *       v1.0.5 sync cocoa_run_on_main bridge.
 *
 *   (3) Race window: a Cmd-C event is enqueued on the EDT at a moment
 *       when poke is mid-flight in invokeAndWait. EDT picks up Cmd-C
 *       (FIFO, ahead of R since R was posted after Cmd-C was already
 *       queued), enters performSelectorOnMainThread:waitUntilDone:YES,
 *       blocks on the perform semaphore. R sits in the EDT queue
 *       behind the now-blocked Cmd-C handler. Main is blocked in
 *       invokeAndWait waiting on R. Each side waits for the other ->
 *       deadlock.
 *
 * A daemon watchdog pings the EDT every 100ms and dumps all stack
 * traces + Runtime.halt(1)s if the EDT goes silent for > 5s.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.WebView;
import ca.weblite.webview.swing.WebViewHeavyweightComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;

public class WebViewDeadlockRepro {

    // setInterval(1) -> page calls poke() every ~1ms, keeping main thread
    // frequently parked inside invokeAndWait waiting on EDT. Wider blocking
    // windows make the race resolve faster; browsers clamp setInterval to
    // 1ms minimum so this is as tight as we can go without rAF chaining.
    private static final String TEST_PAGE_URL =
        "data:text/html;charset=utf-8," +
        "<html><body style='font-family:sans-serif;padding:16px'>" +
        "<h2>Deadlock repro</h2>" +
        "<p>Page is firing <code>window.poke()</code> on " +
        "<code>setInterval(1ms)</code>.</p>" +
        "<p>Press <b>Start repro</b> below to begin firing synthetic " +
        "Cmd-C at the EDT every 10ms.</p>" +
        "<p>Expected: the app freezes within a couple of seconds; the " +
        "watchdog dumps stack traces and halts the JVM.</p>" +
        "<script>" +
        "  setInterval(function(){ if (window.poke) window.poke('tick'); }, 1);" +
        "</script>" +
        "</body></html>";

    // EDT liveness watchdog: posts an invokeLater every 100ms that
    // updates lastEdtTick. If 5s elapse without a tick, the EDT is
    // wedged -- dump all stack traces and halt.
    private static volatile long lastEdtTick = System.currentTimeMillis();

    public static void main(String[] args) {
        // Heavyweight WebView lives above lightweight popups; match
        // other demos for consistency even though we use no popups.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewDeadlockRepro::run);
    }

    private static void run() {
        WebViewHeavyweightComponent wv = new WebViewHeavyweightComponent();
        wv.setUrl(TEST_PAGE_URL);

        // Java side of the JS bridge. poke fires on the AppKit main
        // thread (where WKScriptMessageHandler delivers script messages)
        // and synchronously rendezvous with the EDT via invokeAndWait.
        // The Runnable is a no-op -- the deadlock-relevant fact is that
        // main is blocked on the EDT semaphore for the duration.
        wv.addJavascriptCallback("poke", new WebView.JavascriptCallback() {
            @Override
            public void run(String arg) {
                try {
                    SwingUtilities.invokeAndWait(new Runnable() {
                        @Override public void run() { /* no-op */ }
                    });
                } catch (Exception ignored) { /* shutdown / interrupt noise */ }
            }
        });

        // Non-WebView component to hold Swing focus so the editing-
        // shortcut handler sees focusInWebView == false and falls
        // through to isNativeFirstResponder (the sync bridge call).
        // The handler evaluates isNativeFirstResponder unconditionally
        // either way -- having focus here just makes the demo's intent
        // visually obvious.
        JTextField focusHolder = new JTextField("(focus parking)");
        focusHolder.setEditable(false);

        // Status label that updates from the EDT via a Swing Timer; it
        // freezes the instant the EDT wedges, giving the human observer
        // a live indicator independent of the watchdog.
        final JLabel status = new JLabel("EDT alive");
        Timer statusTimer = new Timer(200, ev -> status.setText(
            "EDT alive  t+" + (System.currentTimeMillis() % 100000)));
        statusTimer.start();

        // Synthetic Cmd-C dispatcher. SHORTCUT_MASK in
        // WebViewHeavyweightComponent comes from getMenuShortcutKeyMask
        // (old-style modifiers), so we use the same source here. The
        // editingShortcutDispatcher installed by addNotify is a global
        // KeyEventDispatcher on KeyboardFocusManager; dispatchEvent
        // routes through it regardless of which component has focus.
        @SuppressWarnings("deprecation")
        final int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        Timer cmdCTimer = new Timer(10, ev -> {
            KeyEvent ke = new KeyEvent(
                focusHolder, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), shortcutMask,
                KeyEvent.VK_C, 'c');
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .dispatchEvent(ke);
        });

        JButton startBtn = new JButton("Start repro");
        JButton stopBtn = new JButton("Stop repro");
        stopBtn.setEnabled(false);
        startBtn.addActionListener(ev -> {
            cmdCTimer.start();
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        });
        stopBtn.addActionListener(ev -> {
            cmdCTimer.stop();
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        });

        JPanel buttons = new JPanel();
        buttons.add(startBtn);
        buttons.add(stopBtn);
        buttons.add(status);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        south.add(focusHolder, BorderLayout.SOUTH);

        JFrame frame = new JFrame("WebView v1.0.5 deadlock repro");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(wv, BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);
        frame.setPreferredSize(new Dimension(900, 700));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        focusHolder.requestFocusInWindow();

        // Start the watchdog after the frame is visible, so initial
        // EDT-heavy work (peer creation on first paint, etc.) doesn't
        // trip it.
        startWatchdog();
    }

    private static void startWatchdog() {
        lastEdtTick = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                EventQueue.invokeLater(
                    () -> lastEdtTick = System.currentTimeMillis());
                long gap = System.currentTimeMillis() - lastEdtTick;
                if (gap > 5000) {
                    System.err.println();
                    System.err.println("======================================================");
                    System.err.println("DEADLOCK DETECTED -- EDT silent for " + gap + " ms");
                    System.err.println("======================================================");
                    dumpThreads();
                    Runtime.getRuntime().halt(1);
                }
            }
        }, "edt-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void dumpThreads() {
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            Thread th = e.getKey();
            System.err.println();
            System.err.println("Thread: " + th.getName()
                + " (id=" + th.getId() + ", state=" + th.getState() + ")");
            for (StackTraceElement frame : e.getValue()) {
                System.err.println("    at " + frame);
            }
        }
    }
}
