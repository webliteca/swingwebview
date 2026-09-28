package ca.weblite.webview;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Canvas 30 O1: the response value class. */
public class WebViewSchemeResponseTest {

    @Test
    public void factoriesSetStatusTypeAndBody() {
        WebViewSchemeResponse ok = WebViewSchemeResponse.ok("text/html", "<h1>Hello</h1>".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, ok.status());
        assertEquals("Content-Type", ok.headers().get(0)[0]);
        assertEquals("text/html", ok.headers().get(0)[1]);
        WebViewSchemeResponse text = WebViewSchemeResponse.text(404, "Not found");
        assertEquals(404, text.status());
        assertEquals("Not found", new String(text.body(), StandardCharsets.UTF_8));
        assertEquals("text/plain; charset=utf-8", text.headers().get(0)[1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aStatusBelow100IsRefused() {
        WebViewSchemeResponse.of(99, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aStatusAbove599IsRefused() {
        WebViewSchemeResponse.of(600, null, null);
    }

    @Test
    public void aNullBodyIsEmpty() {
        assertEquals(0, WebViewSchemeResponse.of(204, null, null).body().length);
    }

    @Test
    public void withHeaderCopies() {
        WebViewSchemeResponse a = WebViewSchemeResponse.ok("text/plain", new byte[0]);
        WebViewSchemeResponse b = a.withHeader("X-A", "1");
        assertEquals(1, a.headers().size());
        assertEquals(2, b.headers().size());
    }
}
