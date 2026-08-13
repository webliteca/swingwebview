/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Unit tests for {@link Origins#canonical}. */
public class OriginsTest {

    @Test public void httpsDefaultPortDropped() {
        assertEquals("https://example.com", Origins.canonical("https://example.com"));
        assertEquals("https://example.com", Origins.canonical("https://example.com:443"));
        assertEquals("https://example.com",
            Origins.canonical("https://EXAMPLE.com/login?x=1#frag"));
    }

    @Test public void httpDefaultPortDropped() {
        assertEquals("http://example.com", Origins.canonical("http://example.com:80/"));
    }

    @Test public void nonDefaultPortKept() {
        assertEquals("https://example.com:8443",
            Origins.canonical("https://example.com:8443/login"));
    }

    @Test public void schemeIsPartOfOrigin() {
        assertEquals("http://example.com", Origins.canonical("http://example.com"));
        assertEquals("https://example.com", Origins.canonical("https://example.com"));
        // http and https canonicalise to distinct origins.
        org.junit.Assert.assertNotEquals(
            Origins.canonical("http://example.com"),
            Origins.canonical("https://example.com"));
    }

    @Test public void hostLowercased() {
        assertEquals("https://svc.example.com",
            Origins.canonical("https://SVC.Example.COM"));
    }

    @Test public void opaqueOrHostlessReturnsNull() {
        assertNull(Origins.canonical("about:blank"));
        assertNull(Origins.canonical("data:text/html,<b>hi</b>"));
        assertNull(Origins.canonical(""));
        assertNull(Origins.canonical("   "));
        assertNull(Origins.canonical(null));
        assertNull(Origins.canonical("not a url"));
    }
}
