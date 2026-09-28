/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Unit tests for {@link WebViewCredential}. */
public class WebViewCredentialTest {

    @Test public void accessors() {
        WebViewCredential c =
            new WebViewCredential("https://example.com", "alice", "s3cret");
        assertEquals("https://example.com", c.origin());
        assertEquals("alice", c.username());
        assertEquals("s3cret", c.password());
    }

    @Test public void nullArgsRejected() {
        try { new WebViewCredential(null, "u", "p"); fail(); }
        catch (NullPointerException e) { assertEquals("origin", e.getMessage()); }
        try { new WebViewCredential("o", null, "p"); fail(); }
        catch (NullPointerException e) { assertEquals("username", e.getMessage()); }
        try { new WebViewCredential("o", "u", null); fail(); }
        catch (NullPointerException e) { assertEquals("password", e.getMessage()); }
    }

    @Test public void equalityIgnoresPassword() {
        WebViewCredential a = new WebViewCredential("https://x.com", "alice", "p1");
        WebViewCredential b = new WebViewCredential("https://x.com", "alice", "p2");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        WebViewCredential c = new WebViewCredential("https://x.com", "bob", "p1");
        assertNotEquals(a, c);
    }

    @Test public void toStringRedactsPassword() {
        WebViewCredential c =
            new WebViewCredential("https://example.com", "alice", "s3cret");
        String s = c.toString();
        assertFalse("password must not appear in toString", s.contains("s3cret"));
        assertTrue(s.contains("alice"));
        assertTrue(s.contains("***"));
    }
}
