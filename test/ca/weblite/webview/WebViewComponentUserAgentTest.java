/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for the {@code WebViewComponent} custom User-Agent contract
 * (Canvas 21) — store / reset / get and the live-apply hook — without a
 * native peer.  On-device verification of the actual HTTP {@code User-Agent}
 * header is a manual step (per the no-automated-GUI-tests policy).
 */
public class WebViewComponentUserAgentTest {

    /** Minimal {@link WebViewComponent} that records {@code applyUserAgentToPeer}
     *  invocations; never attaches to a native peer. */
    private static final class StubComponent extends WebViewComponent {
        final AtomicReference<String> lastApplied = new AtomicReference<String>();
        final AtomicInteger applyCount = new AtomicInteger(0);

        @Override protected void applyUserAgentToPeer(String ua) {
            lastApplied.set(ua);
            applyCount.incrementAndGet();
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
    public void testDefaultUserAgentIsNull() {
        assertNull(new StubComponent().getUserAgent());
    }

    @Test
    public void testSetUserAgentStoresAndReturns() {
        StubComponent c = new StubComponent();
        String ua = "Mozilla/5.0 … Version/18.3 Safari/605.1.15";
        assertSame("setUserAgent must chain", c, c.setUserAgent(ua));
        assertEquals(ua, c.getUserAgent());
        assertEquals(ua, c.lastApplied.get());
        assertEquals(1, c.applyCount.get());
    }

    @Test
    public void testNullResetsToDefault() {
        StubComponent c = new StubComponent();
        c.setUserAgent("x");
        c.setUserAgent(null);
        assertNull(c.getUserAgent());
        assertNull("reset must apply null to the peer", c.lastApplied.get());
    }

    @Test
    public void testEmptyStringResetsToDefault() {
        StubComponent c = new StubComponent();
        c.setUserAgent("x");
        c.setUserAgent("");
        assertNull(c.getUserAgent());
        assertNull(c.lastApplied.get());
    }

    @Test
    public void testApplyHookFiresEveryCall() {
        StubComponent c = new StubComponent();
        c.setUserAgent("a");
        c.setUserAgent("b");
        c.setUserAgent(null);
        assertEquals(3, c.applyCount.get());
        assertNull(c.getUserAgent());
    }
}
