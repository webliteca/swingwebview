/*
 * MIT License
 *
 * Demo for camera / microphone / screen-capture permission requests
 * (Canvas 35 D10).  Serves demo://app/ from a custom scheme (a secure
 * context).  Each button on the page asks for devices; the application's
 * WebViewPermissionHandler answers, by default after asking you in a Swing
 * dialog.  The page shows the stream, or the error name on refusal, and
 * whether this engine has WebRTC (RTCPeerConnection).
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.PermissionDecision;
import ca.weblite.webview.WebViewPermissionHandler;
import ca.weblite.webview.WebViewPermissionRequest;
import ca.weblite.webview.WebViewSchemeHandler;
import ca.weblite.webview.WebViewSchemeResponse;
import ca.weblite.webview.WebViewSchemes;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;

public class WebViewMediaPermissionDemo {

    private static final String PAGE = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<style>body{font:14px sans-serif;margin:12px}button{margin:2px}"
            + "video{width:320px;height:240px;background:#222;display:block;margin-top:8px}"
            + "#out{margin-top:6px;font-weight:bold}</style></head><body>"
            + "<div>Secure context: <b id='sec'></b> &middot; WebRTC (RTCPeerConnection): <b id='rtc'></b></div>"
            + "<div style='margin-top:8px'>"
            + "<button onclick=\"ask({video:true},false)\">Camera</button>"
            + "<button onclick=\"ask({audio:true},false)\">Microphone</button>"
            + "<button onclick=\"ask({video:true,audio:true},false)\">Camera + microphone</button>"
            + "<button onclick=\"ask({video:true},true)\">Share screen</button>"
            + "<button onclick=\"stop()\">Stop</button></div>"
            + "<div id='out'>Press a button.</div><video id='v' autoplay playsinline muted></video>"
            + "<script>"
            + "document.getElementById('sec').textContent=String(window.isSecureContext);"
            + "document.getElementById('rtc').textContent=(typeof RTCPeerConnection==='function')?'yes':'no';"
            + "var cur=null;"
            + "function stop(){if(cur){cur.getTracks().forEach(function(t){t.stop();});cur=null;}"
            + " document.getElementById('v').srcObject=null;}"
            + "function ask(c,display){stop();var out=document.getElementById('out');out.textContent='Waiting for an answer...';"
            + " try{var md=navigator.mediaDevices;var p=display?md.getDisplayMedia(c):md.getUserMedia(c);"
            + " p.then(function(s){cur=s;document.getElementById('v').srcObject=s;"
            + "  out.textContent='Got a stream: '+s.getTracks().map(function(t){return t.kind+' ('+t.label+')';}).join(', ');})"
            + "  .catch(function(e){out.textContent='Refused: '+e.name+' - '+e.message;});}"
            + " catch(e){out.textContent='Not available: '+e.name+' - '+e.message;}}"
            + "</script></body></html>";

    private static JTextArea log;

    public static void main(String[] args) throws Exception {
        WebViewSchemes.register("demo", new WebViewSchemeHandler() {
            @Override
            public void handle(ca.weblite.webview.WebViewSchemeRequest request,
                               ca.weblite.webview.WebViewSchemeResponder responder) {
                responder.respond(WebViewSchemeResponse.ok("text/html; charset=utf-8",
                        PAGE.getBytes(StandardCharsets.UTF_8)));
            }
        });
        EventQueue.invokeLater(WebViewMediaPermissionDemo::show);
    }

    private static void show() {
        final JFrame frame = new JFrame("WebViewMediaPermissionDemo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        final WebViewComponent wv = WebViewComponent.create();
        wv.setPreferredSize(new Dimension(760, 420));

        log = new JTextArea(10, 80);
        log.setEditable(false);

        final JComboBox<String> policy = new JComboBox<String>(new String[] {
            "Ask me (dialog)", "Always allow", "Always deny", "Engine default (useDefault)",
            "No handler (engine decides)"});
        final WebViewPermissionHandler handler = new WebViewPermissionHandler() {
            @Override
            public void permissionRequested(final WebViewPermissionRequest req) {
                log("asked: origin=" + req.origin() + " kinds=" + req.kinds() + " url=" + req.url()
                        + " (thread " + Thread.currentThread().getName() + ")");
                switch (policy.getSelectedIndex()) {
                    case 1: answer(req, PermissionDecision.ALLOW); return;
                    case 2: answer(req, PermissionDecision.DENY); return;
                    case 3: answer(req, PermissionDecision.DEFAULT); return;
                    default: break;
                }
                // Runs on the EDT, and nothing waits for it: a modal dialog is fine.
                Object[] options = {"Allow", "Deny", "Engine default"};
                int r = JOptionPane.showOptionDialog(frame,
                        req.origin() + " wants to use: " + req.kinds(),
                        "Permission request", JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                answer(req, r == 0 ? PermissionDecision.ALLOW
                        : r == 2 ? PermissionDecision.DEFAULT : PermissionDecision.DENY);
            }
        };
        policy.addActionListener(e -> {
            wv.setPermissionHandler(policy.getSelectedIndex() == 4 ? null : handler);
            log("policy: " + policy.getSelectedItem());
        });
        wv.setPermissionHandler(handler);

        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel(" Policy: "), BorderLayout.WEST);
        top.add(policy, BorderLayout.CENTER);
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(wv, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, north, new JScrollPane(log));
        split.setResizeWeight(0.75);
        frame.getContentPane().add(split);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        wv.setUrl("demo://app/");
        log("mode=" + WebViewComponent.resolveDefaultMode()
                + "  isPermissionHandlerSupported=" + WebViewComponent.isPermissionHandlerSupported());
        // Canvas 35 D13: on macOS, a key missing here means camera / microphone
        // requests are denied (macOS would otherwise terminate the process).
        java.util.List<String> missing = WebViewComponent.missingMacUsageDescriptions();
        if (!missing.isEmpty()) {
            log("WARNING: this process's Info.plist lacks " + missing
                    + " -- those devices will be denied (see README)");
        }
    }

    private static void answer(WebViewPermissionRequest req, PermissionDecision d) {
        boolean first = req.answer(d);
        log("answered " + d + (first ? "" : " (ignored: already answered)"));
    }

    private static void log(final String s) {
        final String line = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + "  " + s + "\n";
        EventQueue.invokeLater(() -> log.append(line));
    }
}
