/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link InMemoryCredentialStore} (also exercises the
 *  origin-keying + recency contract shared with the native store). */
public class InMemoryCredentialStoreTest {

    private InMemoryCredentialStore store;

    @Before public void setUp() { store = new InMemoryCredentialStore(); }

    @Test public void saveThenFind() {
        store.save(new WebViewCredential("https://svc.example.com", "bob", "pw123"));
        Optional<WebViewCredential> c = store.find("https://svc.example.com");
        assertTrue(c.isPresent());
        assertEquals("bob", c.get().username());
        assertEquals("pw123", c.get().password());
    }

    @Test public void overwriteKeepsSingleRecord() {
        store.save(new WebViewCredential("https://svc.example.com", "bob", "oldpw"));
        store.save(new WebViewCredential("https://svc.example.com", "bob", "newpw"));
        List<WebViewCredential> all = store.findAll("https://svc.example.com");
        assertEquals(1, all.size());
        assertEquals("newpw", all.get(0).password());
    }

    @Test public void multipleUsernamesRetainedFindMostRecent() {
        store.save(new WebViewCredential("https://svc.example.com", "bob", "pw1"));
        store.save(new WebViewCredential("https://svc.example.com", "carol", "pw2"));
        List<WebViewCredential> all = store.findAll("https://svc.example.com");
        assertEquals(2, all.size());
        // find() returns the most-recently-saved (carol).
        assertEquals("carol", store.find("https://svc.example.com").get().username());
    }

    @Test public void deleteRemoves() {
        store.save(new WebViewCredential("https://svc.example.com", "bob", "pw"));
        assertTrue(store.delete("https://svc.example.com", "bob"));
        assertFalse(store.find("https://svc.example.com").isPresent());
        assertFalse(store.delete("https://svc.example.com", "bob"));
    }

    @Test public void originCanonicalisedOnSaveAndQuery() {
        // Stored with an explicit default port + path; queried bare.
        store.save(new WebViewCredential("https://example.com:443/login", "a", "p"));
        assertTrue(store.find("https://example.com").isPresent());
        // A different scheme/port is a different origin.
        assertFalse(store.find("http://example.com").isPresent());
        assertFalse(store.find("https://example.com:8443").isPresent());
    }

    @Test public void unparseableOriginIsNoOp() {
        store.save(new WebViewCredential("about:blank", "a", "p"));
        assertTrue(store.findAll("about:blank").isEmpty());
    }
}
