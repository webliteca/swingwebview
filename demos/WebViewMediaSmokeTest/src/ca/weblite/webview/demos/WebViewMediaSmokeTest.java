/*
 * MIT License
 *
 * Pass/fail smoke test for HTML media in the lightweight engine (Canvas 33).
 * Exits 0 when every check passes, 1 otherwise, printing one line per check,
 * so it can run unattended under Xvfb.
 *
 * Checks:
 *   A  a <video> whose src is a data:video/webm URL reaches loadedmetadata;
 *   B  a <video> playing a blob: URL made by fetch()ing the same clip from a
 *      registered custom scheme reaches loadedmetadata (Canvas 33 D4: a
 *      custom scheme is never a media src on Linux; pages fetch into a blob);
 *   P  the component still paints the page (a red pixel from the page body).
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.WebViewSchemeHandler;
import ca.weblite.webview.WebViewSchemeResponse;
import ca.weblite.webview.WebViewSchemes;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;

public class WebViewMediaSmokeTest {

    /** A 16x16, 0.2 s VP8 WebM. */
    private static final String CLIP_B64 = ""
            + "GkXfo59ChoEBQveBAULygQRC84EIQoKEd2VibUKHgQJChYECGFOAZwEAAAAAAAH7EU2bdLpNu4tTq4QVSalmU6yBoU"
            + "27i1OrhBZUrmtTrIHYTbuMU6uEElTDZ1OsggEeTbuMU6uEHFO7a1OsggHl7AEAAAAAAABZAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAVSalmsirXsYMPQkBNgI1MYXZmNjAuMTYuMTAwV0GNTGF2ZjYwLjE2LjEwMESJiEBpAAAAAAAAFlSua8Gu"
            + "AQAAAAAAADjXgQFzxYjA0w8C6l3LMJyBACK1nIN1bmSIgQCGhVZfVlA4g4EBI+ODhAvrwgDgibCBELqBEJqBAhJUw2"
            + "f8c3OgY8CAZ8iaRaOHRU5DT0RFUkSHjUxhdmY2MC4xNi4xMDBzc9ZjwItjxYjA0w8C6l3LMGfIoUWjh0VOQ09ERVJE"
            + "h5RMYXZjNjAuMzEuMTAyIGxpYnZweGfIoUWjiERVUkFUSU9ORIeTMDA6MDA6MDAuMjAwMDAwMDAwAB9DtnXB54EAo7"
            + "yBAACAsAIAnQEqEAAQAABHCIWFiIWEiAICAnWqA/gCDP0oAP7/TRL//FhX8WFfxYV/8WFf/PzO7cX85gAcU7trkbuP"
            + "s4EAt4r3gQHxggGf8IED";

    private static final String PAGE = "<!doctype html><html><head><meta charset='utf-8'></head>"
            + "<body style='margin:0;background:#ff0000'>"
            + "<video id='a' preload='metadata' muted width='16' height='16'></video>"
            + "<video id='b' preload='metadata' muted width='16' height='16'></video>"
            + "<script>"
            + "window.__media = {};"
            + "function watch(id){var v=document.getElementById(id);"
            + " v.addEventListener('loadedmetadata',function(){window.__media[id]='loadedmetadata '+v.videoWidth+'x'+v.videoHeight;});"
            + " v.addEventListener('error',function(){window.__media[id]='error '+(v.error?v.error.code:'?');});"
            + " return v;}"
            + "watch('a').src='data:video/webm;base64," + CLIP_B64 + "';"
            + "var b=watch('b');"
            + "fetch('mediatest://app/clip.webm').then(function(r){return r.blob();})"
            + " .then(function(x){b.src=URL.createObjectURL(x);})"
            + " .catch(function(e){window.__media.b='fetch failed '+e;});"
            + "</script></body></html>";

    public static void main(String[] args) throws Exception {
        final byte[] clip = Base64.getDecoder().decode(CLIP_B64);
        WebViewSchemes.register("mediatest", new WebViewSchemeHandler() {
            @Override
            public void handle(ca.weblite.webview.WebViewSchemeRequest request,
                               ca.weblite.webview.WebViewSchemeResponder responder) {
                String path = URI.create(request.url()).getPath();
                if ("/clip.webm".equals(path)) {
                    responder.respond(WebViewSchemeResponse.ok("video/webm", clip)
                            .withHeader("Access-Control-Allow-Origin", "*"));
                } else if (path == null || path.isEmpty() || "/".equals(path)) {
                    responder.respond(WebViewSchemeResponse.ok("text/html; charset=utf-8",
                            PAGE.getBytes(StandardCharsets.UTF_8)));
                } else {
                    responder.respond(WebViewSchemeResponse.text(404, "Not found"));
                }
            }
        });

        final WebViewComponent[] holder = new WebViewComponent[1];
        EventQueue.invokeAndWait(() -> {
            System.err.println("[media-smoke] os.name=" + System.getProperty("os.name")
                    + "  mode=" + WebViewComponent.resolveDefaultMode());
            WebViewComponent wv = WebViewComponent.create(WebViewComponent.Mode.LIGHTWEIGHT);
            wv.setPreferredSize(new Dimension(320, 200));
            JFrame frame = new JFrame("WebViewMediaSmokeTest");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(wv, BorderLayout.CENTER);
            frame.pack();
            frame.setVisible(true);
            wv.setUrl("mediatest://app/");
            holder[0] = wv;
        });
        WebViewComponent wv = holder[0];

        String state = "{}";
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String s = wv.evalAsync("return window.__media || {};").get(2, TimeUnit.SECONDS);
                if (s != null) state = s;
            } catch (Exception ignored) {
                // the page may not be ready yet
            }
            if (state.contains("\"a\"") && state.contains("\"b\"")) break;
            Thread.sleep(250);
        }
        Thread.sleep(500);   // let the pixel pump catch up with the final page

        boolean ok = true;
        ok &= check("A data: video reaches loadedmetadata", field(state, "a"), "loadedmetadata 16x16");
        ok &= check("B scheme fetch -> blob: video reaches loadedmetadata", field(state, "b"), "loadedmetadata 16x16");

        final int[] rgb = new int[1];
        EventQueue.invokeAndWait(() -> {
            BufferedImage img = new BufferedImage(Math.max(1, wv.getWidth()), Math.max(1, wv.getHeight()),
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            wv.paint(g);
            g.dispose();
            rgb[0] = img.getRGB(img.getWidth() / 2, img.getHeight() - 10);
        });
        int r = (rgb[0] >> 16) & 0xff, gr = (rgb[0] >> 8) & 0xff, b = rgb[0] & 0xff;
        boolean red = r > 200 && gr < 60 && b < 60;
        ok &= check("P the page still paints", String.format("#%02x%02x%02x", r, gr, b), red ? null : "mostly red");

        System.err.println("[media-smoke] " + (ok ? "PASSED" : "FAILED") + "  state=" + state);
        System.exit(ok ? 0 : 1);
    }

    /** One check line; {@code expected} null means the caller already decided it passed. */
    private static boolean check(String name, String actual, String expected) {
        boolean pass = expected == null || expected.equals(actual);
        System.err.println("[media-smoke] " + (pass ? "PASS" : "FAIL") + " " + name + ": " + actual
                + (pass ? "" : "  (expected " + expected + ")"));
        return pass;
    }

    /** The string value of {@code key} in a flat JSON object of strings, or "(none)". */
    private static String field(String json, String key) {
        String k = "\"" + key + "\":\"";
        int i = json.indexOf(k);
        if (i < 0) return "(none)";
        int start = i + k.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "(none)" : json.substring(start, end);
    }
}
