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
import java.util.function.Function;

/**
 * Unit tests for the per-destination User-Agent resolver contract (Canvas 21,
 * 1.5.0) — precedence, fall-through, and failure containment — without a native
 * peer.  On-device verification that a pop-up child's <em>first</em> request
 * carries the resolver's UA is a manual step (per the no-automated-GUI-tests
 * policy); see the resolver toggle in {@code WebViewAdoptPopupDemo}.
 */
public class WebViewComponentUserAgentResolverTest {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
    private static final String SAFARI =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/18.6 Safari/605.1.15";

    /** Minimal {@link WebViewComponent} that records what reaches the peer and
     *  reports {@code setUrl} through the resolved-UA path, as the real
     *  subclasses do; never attaches to a native peer. */
    private static final class StubComponent extends WebViewComponent {
        final AtomicReference<String> lastApplied = new AtomicReference<String>();
        final AtomicInteger applyCount = new AtomicInteger(0);
        final AtomicReference<Function<String, String>> lastResolver =
                new AtomicReference<Function<String, String>>();

        @Override protected void applyUserAgentToPeer(String ua) {
            lastApplied.set(ua);
            applyCount.incrementAndGet();
        }

        @Override protected void applyUserAgentResolverToPeer(Function<String, String> r) {
            lastResolver.set(r);
        }

        /** Expose the protected precedence helper to the tests. */
        String resolveFor(String url) { return resolveUserAgentFor(url); }

        /** Drive the live-navigation path the real subclasses use. */
        void navigate(String url) { applyResolvedUserAgentFor(url); }

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

    /** A resolver that answers only for hosts containing {@code needle}. */
    private static Function<String, String> only(final String needle, final String ua) {
        return new Function<String, String>() {
            @Override public String apply(String url) {
                return url != null && url.contains(needle) ? ua : null;
            }
        };
    }

    @Test
    public void testDefaultResolverIsNull() {
        StubComponent c = new StubComponent();
        assertNull("no resolver by default", c.getUserAgentResolver());
        assertNull("no UA at all resolves to the engine default", c.resolveFor("https://x/"));
    }

    @Test
    public void testResolverRoundTripsAndChains() {
        StubComponent c = new StubComponent();
        Function<String, String> r = only("slack.com", CHROME);
        assertSame("setter chains", c, c.setUserAgentResolver(r));
        assertSame("getter round-trips", r, c.getUserAgentResolver());
        assertSame("resolver reaches the peer", r, c.lastResolver.get());
        c.setUserAgentResolver(null);
        assertNull("null clears the resolver", c.getUserAgentResolver());
    }

    @Test
    public void testResolverWinsOverStaticUserAgent() {
        StubComponent c = new StubComponent();
        c.setUserAgent(SAFARI);
        c.setUserAgentResolver(only("slack.com", CHROME));
        assertEquals("the resolver decides for its own host",
                CHROME, c.resolveFor("https://app.slack.com/client"));
    }

    @Test
    public void testNullReturnFallsThroughToStaticUserAgent() {
        StubComponent c = new StubComponent();
        c.setUserAgent(SAFARI);
        c.setUserAgentResolver(only("slack.com", CHROME));
        assertEquals("a declining resolver yields to the static UA",
                SAFARI, c.resolveFor("https://accounts.google.com/signin"));
    }

    @Test
    public void testBlankReturnFallsThroughRatherThanClearing() {
        StubComponent c = new StubComponent();
        c.setUserAgent(SAFARI);
        c.setUserAgentResolver(new Function<String, String>() {
            @Override public String apply(String url) { return "   "; }
        });
        assertEquals("a blank return is a decline, not the engine default",
                SAFARI, c.resolveFor("https://example.com/"));
    }

    @Test
    public void testNoStaticUserAgentAndDecliningResolverYieldsEngineDefault() {
        StubComponent c = new StubComponent();
        c.setUserAgentResolver(only("slack.com", CHROME));
        assertNull("neither level answers, so the engine default stands",
                c.resolveFor("https://example.com/"));
    }

    @Test
    public void testThrowingResolverFallsThroughInsteadOfPropagating() {
        StubComponent c = new StubComponent();
        c.setUserAgent(SAFARI);
        c.setUserAgentResolver(new Function<String, String>() {
            @Override public String apply(String url) {
                throw new IllegalStateException("resolver blew up");
            }
        });
        assertEquals("a throwing resolver must never break a navigation",
                SAFARI, c.resolveFor("https://example.com/"));
    }

    @Test
    public void testBlankUrlIsNotHandedToTheResolver() {
        StubComponent c = new StubComponent();
        c.setUserAgent(SAFARI);
        c.setUserAgentResolver(new Function<String, String>() {
            @Override public String apply(String url) { return CHROME; }
        });
        assertEquals("a blank destination has no host to key on",
                SAFARI, c.resolveFor("   "));
        assertEquals("a null destination likewise", SAFARI, c.resolveFor(null));
    }

    @Test
    public void testNavigationAppliesPerDestinationAndSkipsUnchanged() {
        StubComponent c = new StubComponent();
        c.setUserAgent(SAFARI);
        c.setUserAgentResolver(only("slack.com", CHROME));
        c.applyCount.set(0);

        c.navigate("https://app.slack.com/client");
        assertEquals("Slack gets the resolver's UA", CHROME, c.lastApplied.get());
        assertEquals(1, c.applyCount.get());

        c.navigate("https://app.slack.com/client/other");
        assertEquals("an unchanged UA is not re-entered into the engine",
                1, c.applyCount.get());

        c.navigate("https://accounts.google.com/signin");
        assertEquals("Google falls through to the static UA", SAFARI, c.lastApplied.get());
        assertEquals(2, c.applyCount.get());
    }
}
