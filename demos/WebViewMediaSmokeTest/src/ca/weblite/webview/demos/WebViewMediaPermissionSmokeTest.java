/*
 * MIT License
 *
 * Pass/fail smoke test for camera / microphone / screen-capture permission
 * requests in the Linux lightweight engine (Canvas 35 D11).  Exits 0 when
 * every check passes, 1 otherwise, printing one line per check, so it can run
 * unattended under Xvfb.  Needs WebKitGTK's mock capture devices:
 * run-linux-media-smoketest.sh exports WEBVIEW_MOCK_CAPTURE_DEVICES=1.
 *
 * Phases, each in a fresh lightweight component:
 *   deny      handler denies         {video}        -> NotAllowedError
 *   allow     handler allows         {video,audio}  -> audio+video tracks
 *   later     allows 1.5 s later     {audio}        -> audio track; EDT free
 *   none      no handler             {video}        -> NotAllowedError
 *   default   useDefault()           {video}        -> NotAllowedError
 *   display   handler allows         getDisplayMedia -> handler saw DISPLAY
 *             (informational: SKIP when no request reaches the handler)
 *   webrtc    typeof RTCPeerConnection (informational)
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.MediaCaptureKind;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;

public class WebViewMediaPermissionSmokeTest {

    private static final String PAGE = "<!doctype html><html><head><meta charset='utf-8'></head>"
            + "<body style='margin:0;background:#2050a0'>"
            + "<script>"
            + "window.__result = null;"
            + "window.ask = function(c, display) {"
            + "  window.__result = 'pending';"
            + "  try {"
            + "    var md = navigator.mediaDevices;"
            + "    var p = display ? md.getDisplayMedia(c) : md.getUserMedia(c);"
            + "    p.then(function(s) {"
            + "      var k = s.getTracks().map(function(t){ return t.kind; }).sort().join(',');"
            + "      s.getTracks().forEach(function(t){ t.stop(); });"
            + "      window.__result = 'ok:' + k;"
            + "    }).catch(function(e) { window.__result = 'err:' + e.name; });"
            + "  } catch (e) { window.__result = 'err:' + e.name; }"
            + "};"
            + "</script></body></html>";

    /** What the handler was asked. */
    private static final List<WebViewPermissionRequest> seen =
            Collections.synchronizedList(new ArrayList<WebViewPermissionRequest>());

    private static JFrame frame;
    private static boolean ok = true;

    public static void main(String[] args) throws Exception {
        WebViewSchemes.register("permtest", new WebViewSchemeHandler() {
            @Override
            public void handle(ca.weblite.webview.WebViewSchemeRequest request,
                               ca.weblite.webview.WebViewSchemeResponder responder) {
                responder.respond(WebViewSchemeResponse.ok("text/html; charset=utf-8",
                        PAGE.getBytes(StandardCharsets.UTF_8)));
            }
        });
        EventQueue.invokeAndWait(() -> {
            frame = new JFrame("WebViewMediaPermissionSmokeTest");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setLayout(new BorderLayout());
            frame.setSize(new Dimension(360, 240));
            frame.setVisible(true);
        });
        System.err.println("[perm-smoke] supported="
                + WebViewComponent.isPermissionHandlerSupported()
                + "  WEBVIEW_MOCK_CAPTURE_DEVICES=" + System.getenv("WEBVIEW_MOCK_CAPTURE_DEVICES"));
        ok &= check("S isPermissionHandlerSupported", String.valueOf(
                WebViewComponent.isPermissionHandlerSupported()), "true");

        // 1. deny
        String r = phase(req -> req.deny(), "{video:true}", false, 15);
        ok &= check("1 deny -> NotAllowedError", r, "err:NotAllowedError");
        ok &= check("1 deny: handler kinds", kindsSeen(), EnumSet.of(MediaCaptureKind.CAMERA).toString());
        ok &= check("1 deny: origin", originSeen(), "permtest://app");

        // 2. allow
        r = phase(req -> req.allow(), "{video:true,audio:true}", false, 15);
        ok &= check("2 allow -> audio+video stream", r, "ok:audio,video");
        ok &= check("2 allow: handler kinds", kindsSeen(),
                EnumSet.of(MediaCaptureKind.CAMERA, MediaCaptureKind.MICROPHONE).toString());

        // 3. later, from another thread, with the EDT free while waiting
        final long[] edtMax = new long[1];
        r = phase(req -> new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                // fall through and answer
            }
            req.allow();
        }, "late-answer").start(), "{audio:true}", false, 15, edtMax);
        ok &= check("3 later -> audio stream", r, "ok:audio");
        ok &= check("3 later: EDT responsive while pending (max invokeAndWait ms < 500)",
                String.valueOf(edtMax[0]), edtMax[0] < 500 ? null : "< 500");

        // 4. no handler -> WebKitGTK's own default (refuse)
        r = phase(null, "{video:true}", false, 15);
        ok &= check("4 no handler -> NotAllowedError", r, "err:NotAllowedError");
        ok &= check("4 no handler: nothing asked", String.valueOf(seen.size()), "0");

        // 5. useDefault() -> same as no handler
        r = phase(req -> req.useDefault(), "{video:true}", false, 15);
        ok &= check("5 default -> NotAllowedError", r, "err:NotAllowedError");

        // 6. display (informational)
        r = phase(req -> req.allow(), "{video:true}", true, 8);
        if (seen.isEmpty()) {
            System.err.println("[perm-smoke] SKIP 6 display: no request reached the handler (result "
                    + r + "); WebKitGTK < 2.42 or no display-capture backend here");
        } else {
            ok &= check("6 display: handler kinds", kindsSeen(), EnumSet.of(MediaCaptureKind.DISPLAY).toString());
            System.err.println("[perm-smoke] INFO 6 display result: " + r);
        }

        // 7. WebRTC availability (informational)
        System.err.println("[perm-smoke] INFO 7 typeof RTCPeerConnection: " + lastRtc);

        System.err.println("[perm-smoke] " + (ok ? "PASSED" : "FAILED"));
        System.exit(ok ? 0 : 1);
    }

    private static String lastRtc = "(unknown)";

    private static String phase(WebViewPermissionHandler h, String constraints, boolean display,
                                int timeoutSec) throws Exception {
        return phase(h, constraints, display, timeoutSec, null);
    }

    /** Show the page in a fresh lightweight component with {@code h}, ask, and wait for the outcome. */
    private static String phase(final WebViewPermissionHandler h, String constraints, boolean display,
                                int timeoutSec, long[] edtMax) throws Exception {
        seen.clear();
        final WebViewComponent[] holder = new WebViewComponent[1];
        EventQueue.invokeAndWait(() -> {
            frame.getContentPane().removeAll();
            WebViewComponent wv = WebViewComponent.create(WebViewComponent.Mode.LIGHTWEIGHT);
            if (h != null) {
                wv.setPermissionHandler(req -> {
                    seen.add(req);
                    h.permissionRequested(req);
                });
            }
            frame.getContentPane().add(wv, BorderLayout.CENTER);
            frame.getContentPane().revalidate();
            wv.setUrl("permtest://app/");
            holder[0] = wv;
        });
        WebViewComponent wv = holder[0];
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String s = eval(wv, "return typeof window.ask;");
            if ("\"function\"".equals(s)) break;
            Thread.sleep(200);
        }
        lastRtc = unquote(eval(wv, "return typeof RTCPeerConnection;"));
        wv.eval("window.ask(" + constraints + ", " + display + ");");
        String result = "(none)";
        deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (edtMax != null) {
                long t0 = System.nanoTime();
                EventQueue.invokeAndWait(() -> { });
                edtMax[0] = Math.max(edtMax[0], (System.nanoTime() - t0) / 1_000_000L);
            }
            String s = unquote(eval(wv, "return window.__result;"));
            if (s != null && !s.equals("pending") && !s.equals("null") && !s.isEmpty()) {
                result = s;
                break;
            }
            result = s;
            Thread.sleep(200);
        }
        return result;
    }

    private static String eval(WebViewComponent wv, String js) {
        try {
            return wv.evalAsync(js).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String kindsSeen() {
        if (seen.isEmpty()) return "(no request)";
        Set<MediaCaptureKind> k = seen.get(0).kinds();
        return EnumSet.copyOf(k).toString();
    }

    private static String originSeen() {
        return seen.isEmpty() ? "(no request)" : seen.get(0).origin();
    }

    /** One check line; {@code expected} null means the caller already decided it passed. */
    private static boolean check(String name, String actual, String expected) {
        boolean pass = expected == null || expected.equals(actual);
        System.err.println("[perm-smoke] " + (pass ? "PASS" : "FAIL") + " " + name + ": " + actual
                + (pass ? "" : "  (expected " + expected + ")"));
        return pass;
    }
}
