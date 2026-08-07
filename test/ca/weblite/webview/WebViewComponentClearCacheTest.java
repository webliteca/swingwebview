/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the {@code WebViewComponent} cache-purge contract (Canvas 22)
 * — forward-to-peer, no-op-when-detached, and chaining — without a native
 * peer.  On-device verification that a previously-cached resource is
 * re-requested after {@code clearCache()} (and that cookies/login survive) is a
 * manual step (per the no-automated-GUI-tests policy).
 */
public class WebViewComponentClearCacheTest {

    /** Minimal {@link WebViewComponent} that records {@code clearCacheOnPeer}
     *  invocations, forwarding only when it reports an attached peer — the
     *  same null-check shape the real heavyweight/lightweight overrides use. */
    private static final class StubComponent extends WebViewComponent {
        final AtomicInteger clearCount = new AtomicInteger(0);
        volatile boolean attached = false;

        @Override protected void clearCacheOnPeer() {
            if (attached) clearCount.incrementAndGet();
        }

        @Override public WebViewComponent setUrl(String url) { return this; }
        @Override public String getUrl() { return ""; }
        @Override public WebViewComponent setDebug(boolean debug) { return this; }
        @Override public WebViewComponent addOnBeforeLoad(String js) { return this; }
        @Override public WebViewComponent eval(String js) { return this; }
        @Override public CompletableFuture<String> evalAsync(String js) {
            CompletableFuture<String> f = new CompletableFuture<String>();
            f.completeExceptionally(new IllegalStateException("stub"));
            return f;
        }
        @Override public WebViewComponent addJavascriptCallback(
                String name, WebView.JavascriptCallback cb) { return this; }
        @Override public WebViewComponent addJavascriptFunction(
                String name, JavascriptFunction fn) { return this; }
        @Override public WebViewComponent addJavascriptFunction(
                String name, AsyncJavascriptFunction fn) { return this; }
        @Override public WebViewComponent dispatch(Runnable r) { return this; }
        @Override public void dispose() { }
    }

    @Test
    public void testClearCacheChains() {
        StubComponent c = new StubComponent();
        assertSame("clearCache must chain", c, c.clearCache());
    }

    @Test
    public void testForwardsToPeerWhenAttached() {
        StubComponent c = new StubComponent();
        c.attached = true;
        c.clearCache();
        assertEquals(1, c.clearCount.get());
    }

    @Test
    public void testNoOpWhenDetached() {
        StubComponent c = new StubComponent();
        // No peer attached: the hook must not forward, and nothing throws.
        c.clearCache();
        assertEquals(0, c.clearCount.get());
    }

    @Test
    public void testForwardsEachCall() {
        StubComponent c = new StubComponent();
        c.attached = true;
        c.clearCache();
        c.clearCache();
        c.clearCache();
        assertEquals(3, c.clearCount.get());
    }
}
