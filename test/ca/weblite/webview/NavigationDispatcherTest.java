/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link NavigationDispatcher} (Canvas 34 D3, D4): the
 * inline, fail-closed decision and the marking of the application's own
 * navigations, without a live native engine.  The native decision points
 * are verified by {@code WebViewNavigationDemo}.
 */
public class NavigationDispatcherTest {

    /** Minimal {@link WebViewComponent} usable as a dispatcher source. */
    private static final class StubComponent extends WebViewComponent {
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

    /** Records every event and answers {@link #answer}. */
    private static final class Recorder implements WebViewNavigationHandler {
        final List<WebViewNavigationEvent> events = new ArrayList<WebViewNavigationEvent>();
        boolean answer = false;
        @Override public boolean navigationRequested(WebViewNavigationEvent e) {
            events.add(e);
            return answer;
        }
        WebViewNavigationEvent last() { return events.get(events.size() - 1); }
    }

    private final long[] now = {1000L};
    private StubComponent source;
    private NavigationDispatcher dispatcher;
    private Recorder recorder;
    private Thread.UncaughtExceptionHandler priorHandler;
    private AtomicReference<Throwable> uncaught;

    private static final int LINK = NavigationCause.LINK.ordinal();
    private static final int OTHER = NavigationCause.OTHER.ordinal();
    private static final int REDIRECT = NavigationCause.REDIRECT.ordinal();

    @Before
    public void setUp() {
        source = new StubComponent();
        dispatcher = new NavigationDispatcher(source, new NavigationDispatcher.Clock() {
            @Override public long millis() { return now[0]; }
        });
        recorder = new Recorder();
        priorHandler = Thread.getDefaultUncaughtExceptionHandler();
        uncaught = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                uncaught.compareAndSet(null, e);
            }
        });
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(priorHandler);
    }

    @Test
    public void theDefaultAllowsEverything() {
        assertSame(WebViewNavigationHandler.DEFAULT, dispatcher.getHandler());
        assertTrue(dispatcher.dispatch("https://anywhere.example/?d=1", "demo://app/", LINK));
        assertTrue(dispatcher.dispatch("mailto:a@b.example", "", OTHER));
    }

    @Test
    public void nullResetsToTheDefault() {
        dispatcher.setHandler(recorder);
        assertSame(recorder, dispatcher.getHandler());
        dispatcher.setHandler(null);
        assertSame(WebViewNavigationHandler.DEFAULT, dispatcher.getHandler());
        assertTrue(dispatcher.dispatch("https://x.example/", "", LINK));
    }

    @Test
    public void aRefusingHandlerRefusesAndIsToldTheFacts() {                                // AC1
        dispatcher.setHandler(recorder);
        assertFalse(dispatcher.dispatch("http://127.0.0.1:8123/?d=secret", "demo://app/", LINK));
        WebViewNavigationEvent e = recorder.last();
        assertSame(source, e.source());
        assertEquals("http://127.0.0.1:8123/?d=secret", e.url());
        assertEquals("demo://app/", e.currentUrl());
        assertEquals(NavigationCause.LINK, e.cause());
        assertFalse(e.applicationInitiated());
    }

    @Test
    public void nullStringsBecomeEmpty() {
        dispatcher.setHandler(recorder);
        dispatcher.dispatch(null, null, OTHER);
        assertEquals("", recorder.last().url());
        assertEquals("", recorder.last().currentUrl());
    }

    @Test
    public void anAllowingHandlerAllows() {                                                 // AC4
        recorder.answer = true;
        dispatcher.setHandler(recorder);
        assertTrue(dispatcher.dispatch("demo://app/next.html", "demo://app/", LINK));
    }

    @Test
    public void aThrowingHandlerRefuses() {                                                 // AC7
        final RuntimeException boom = new RuntimeException("boom");
        dispatcher.setHandler(new WebViewNavigationHandler() {
            @Override public boolean navigationRequested(WebViewNavigationEvent e) {
                throw boom;
            }
        });
        assertFalse(dispatcher.dispatch("http://127.0.0.1:8123/", "demo://app/", OTHER));
        assertSame(boom, uncaught.get());
    }

    @Test
    public void aDisposedDispatcherRefuses() {
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());
        assertFalse(dispatcher.dispatch("demo://app/", "", OTHER));
        dispatcher.disposeAll();   // idempotent
    }

    @Test
    public void causesMapFromOrdinalsAndUnknownIsOther() {                                  // AC6
        dispatcher.setHandler(recorder);
        for (NavigationCause c : NavigationCause.values()) {
            dispatcher.dispatch("demo://app/", "", c.ordinal());
            assertEquals(c, recorder.last().cause());
        }
        dispatcher.dispatch("demo://app/", "", 99);
        assertEquals(NavigationCause.OTHER, recorder.last().cause());
        dispatcher.dispatch("demo://app/", "", -1);
        assertEquals(NavigationCause.OTHER, recorder.last().cause());
    }

    @Test
    public void theWireOrdinalsAreFixed() {
        assertEquals(0, NavigationCause.LINK.ordinal());
        assertEquals(1, NavigationCause.FORM.ordinal());
        assertEquals(2, NavigationCause.BACK_FORWARD.ordinal());
        assertEquals(3, NavigationCause.RELOAD.ordinal());
        assertEquals(4, NavigationCause.REDIRECT.ordinal());
        assertEquals(5, NavigationCause.OTHER.ordinal());
    }

    @Test
    public void anExpectedUrlMarksTheFirstMatchingNavigationOnly() {                        // AC5
        recorder.answer = true;
        dispatcher.setHandler(recorder);
        dispatcher.expectApplicationNavigation("HTTPS://Example.com");
        dispatcher.dispatch("https://example.com/", "demo://app/", OTHER);
        assertTrue(recorder.last().applicationInitiated());
        dispatcher.dispatch("https://example.com/", "demo://app/", OTHER);
        assertFalse("consumed", recorder.last().applicationInitiated());
    }

    @Test
    public void aPageNavigationToAnotherAddressIsNotMarked() {
        dispatcher.setHandler(recorder);
        dispatcher.expectApplicationNavigation("https://example.com/");
        dispatcher.dispatch("https://elsewhere.example/", "demo://app/", OTHER);
        assertFalse(recorder.last().applicationInitiated());
    }

    @Test
    public void anExpectationExpires() {
        dispatcher.setHandler(recorder);
        dispatcher.expectApplicationNavigation("https://example.com/");
        now[0] += NavigationDispatcher.EXPECT_MILLIS + 1;
        dispatcher.dispatch("https://example.com/", "", OTHER);
        assertFalse(recorder.last().applicationInitiated());
    }

    @Test
    public void onlyTheNewestSixteenExpectationsAreKept() {
        dispatcher.setHandler(recorder);
        for (int i = 0; i <= NavigationDispatcher.EXPECT_MAX; i++) {
            dispatcher.expectApplicationNavigation("https://example.com/" + i);
        }
        dispatcher.dispatch("https://example.com/0", "", OTHER);
        assertFalse("the oldest was dropped", recorder.last().applicationInitiated());
        dispatcher.dispatch("https://example.com/" + NavigationDispatcher.EXPECT_MAX, "", OTHER);
        assertTrue(recorder.last().applicationInitiated());
    }

    @Test
    public void aRedirectInheritsAnAllowedApplicationNavigation() {                         // AC5
        recorder.answer = true;
        dispatcher.setHandler(recorder);
        dispatcher.expectApplicationNavigation("https://example.com/");
        dispatcher.dispatch("https://example.com/", "demo://app/", OTHER);
        dispatcher.dispatch("https://www.example.com/", "demo://app/", REDIRECT);
        assertTrue(recorder.last().applicationInitiated());
        dispatcher.dispatch("https://www.example.com/en/", "demo://app/", REDIRECT);
        assertTrue("a chain of redirects keeps it", recorder.last().applicationInitiated());
    }

    @Test
    public void aRefusedApplicationNavigationPassesNothingOn() {
        dispatcher.setHandler(recorder);   // refuses
        dispatcher.expectApplicationNavigation("https://example.com/");
        dispatcher.dispatch("https://example.com/", "", OTHER);
        assertTrue(recorder.last().applicationInitiated());
        dispatcher.dispatch("https://www.example.com/", "", REDIRECT);
        assertFalse(recorder.last().applicationInitiated());
    }

    @Test
    public void anInterveningPageNavigationResetsTheRedirectMark() {
        recorder.answer = true;
        dispatcher.setHandler(recorder);
        dispatcher.expectApplicationNavigation("https://example.com/");
        dispatcher.dispatch("https://example.com/", "", OTHER);
        dispatcher.dispatch("about:srcdoc", "", OTHER);
        dispatcher.dispatch("https://evil.example/", "", REDIRECT);
        assertFalse(recorder.last().applicationInitiated());
    }

    @Test
    public void aPageRedirectIsNeverMarked() {
        recorder.answer = true;
        dispatcher.setHandler(recorder);
        dispatcher.dispatch("https://page.example/", "", LINK);
        dispatcher.dispatch("https://evil.example/", "", REDIRECT);
        assertFalse(recorder.last().applicationInitiated());
    }

    @Test
    public void theDefaultStillConsumesExpectationsAndMarksRedirects() {
        dispatcher.expectApplicationNavigation("https://example.com/");
        assertTrue(dispatcher.dispatch("https://example.com/", "", OTHER));
        dispatcher.setHandler(recorder);
        dispatcher.dispatch("https://www.example.com/", "", REDIRECT);
        assertTrue(recorder.last().applicationInitiated());
    }

    @Test
    public void normalization() {
        assertEquals("https://example.com/", NavigationDispatcher.normalize("HTTPS://EXAMPLE.com:443"));
        assertEquals("http://example.com/a?b=1", NavigationDispatcher.normalize("http://example.com:80/a?b=1#frag"));
        assertEquals("http://example.com:8080/", NavigationDispatcher.normalize("http://Example.com:8080"));
        assertEquals("demo://app/", NavigationDispatcher.normalize("demo://app"));
        assertEquals("about:blank", NavigationDispatcher.normalize("about:blank"));
        assertEquals("not a url", NavigationDispatcher.normalize("not a url"));
    }

    @Test
    public void theEventDescribesItself() {
        dispatcher.setHandler(recorder);
        dispatcher.dispatch("demo://app/x", "demo://app/", LINK);
        assertNotNull(recorder.last().toString());
        assertTrue(recorder.last().toString().contains("demo://app/x"));
    }

    @Test
    public void supportIsFalseWithoutTheNativeFeature() {
        // Headless test runs have no native library, or an older one: either
        // way isAvailable must answer rather than throw (AC11).
        NavigationDispatcher.isAvailable();
    }
}
