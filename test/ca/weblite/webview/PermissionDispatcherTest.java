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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link PermissionDispatcher} (Canvas 35 D3, D4): the
 * no-handler fast path, the pending table, first-answer-wins, failure and
 * disposal, without a live native engine.  The engines themselves are
 * verified by {@code WebViewMediaPermissionSmokeTest} (Linux).
 */
public class PermissionDispatcherTest {

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

    /** Records every answer that reaches native. */
    private static final class Sink implements PermissionDispatcher.Sink {
        final List<long[]> answers = new ArrayList<long[]>();
        @Override public synchronized void respond(long id, int decision) {
            answers.add(new long[] {id, decision});
            notifyAll();
        }
        synchronized int count() { return answers.size(); }
        synchronized long[] get(int i) { return answers.get(i); }
    }

    /** Runs "EDT" work inline so a test sees it at once. */
    private static final Executor INLINE = new Executor() {
        @Override public void execute(Runnable r) { r.run(); }
    };

    private StubComponent source;
    private Sink sink;
    private PermissionDispatcher dispatcher;
    private Thread.UncaughtExceptionHandler savedUncaught;
    private final List<Throwable> uncaught = new ArrayList<Throwable>();

    @Before
    public void setUp() {
        source = new StubComponent();
        sink = new Sink();
        dispatcher = new PermissionDispatcher(source, sink, INLINE);
        savedUncaught = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                synchronized (uncaught) { uncaught.add(e); }
            }
        });
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(savedUncaught);
    }

    @Test
    public void noHandlerAnswersDefaultAtOnce() {
        assertNull(dispatcher.getHandler());
        assertEquals(PermissionDecision.DEFAULT.ordinal(),
            dispatcher.dispatch(1, "", "permtest://app/", 1));
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, sink.count());
    }

    @Test
    public void disposedDenies() {
        dispatcher.setHandler(r -> r.allow());
        dispatcher.disposeAll();
        assertEquals(PermissionDecision.DENY.ordinal(),
            dispatcher.dispatch(1, "", "permtest://app/", 1));
        assertEquals(0, sink.count());
    }

    @Test
    public void unknownKindBitsAnswerDefault() {
        dispatcher.setHandler(r -> r.allow());
        assertEquals(PermissionDecision.DEFAULT.ordinal(),
            dispatcher.dispatch(1, "", "permtest://app/", 64));
        assertEquals(0, sink.count());
    }

    @Test
    public void handlerSeesRequestAndAllowReachesSink() {
        final AtomicReference<WebViewPermissionRequest> seen =
            new AtomicReference<WebViewPermissionRequest>();
        dispatcher.setHandler(r -> { seen.set(r); r.allow(); });
        assertEquals(PermissionDispatcher.PENDING,
            dispatcher.dispatch(7, "", "permtest://app/x", 3));
        WebViewPermissionRequest r = seen.get();
        assertNotNull(r);
        assertSame(source, r.source());
        assertEquals("permtest://app", r.origin());
        assertEquals("permtest://app/x", r.url());
        assertEquals(EnumSet.of(MediaCaptureKind.CAMERA, MediaCaptureKind.MICROPHONE), r.kinds());
        assertTrue(r.has(MediaCaptureKind.CAMERA));
        assertFalse(r.has(MediaCaptureKind.DISPLAY));
        assertEquals(1, sink.count());
        assertEquals(7L, sink.get(0)[0]);
        assertEquals(PermissionDecision.ALLOW.ordinal(), (int) sink.get(0)[1]);
        assertEquals(0, dispatcher.pendingCount());
    }

    @Test
    public void engineOriginWinsOverUrl() {
        final AtomicReference<WebViewPermissionRequest> seen =
            new AtomicReference<WebViewPermissionRequest>();
        dispatcher.setHandler(r -> seen.set(r));
        dispatcher.dispatch(1, "https://Frame.Example:443", "https://page.example/", 1);
        assertEquals("https://frame.example", seen.get().origin());
    }

    @Test
    public void firstAnswerWins() {
        final AtomicReference<Boolean> second = new AtomicReference<Boolean>();
        dispatcher.setHandler(r -> { assertTrue(r.allow()); second.set(r.deny()); });
        dispatcher.dispatch(1, "", "permtest://app/", 1);
        assertFalse(second.get());
        assertEquals(1, sink.count());
        assertEquals(PermissionDecision.ALLOW.ordinal(), (int) sink.get(0)[1]);
    }

    @Test
    public void nullAnswerDenies() {
        dispatcher.setHandler(r -> r.answer(null));
        dispatcher.dispatch(1, "", "permtest://app/", 2);
        assertEquals(PermissionDecision.DENY.ordinal(), (int) sink.get(0)[1]);
    }

    @Test
    public void throwingHandlerDeniesAndReportsUncaught() {
        final RuntimeException boom = new RuntimeException("boom");
        dispatcher.setHandler(r -> { throw boom; });
        assertEquals(PermissionDispatcher.PENDING,
            dispatcher.dispatch(1, "", "permtest://app/", 1));
        assertEquals(1, sink.count());
        assertEquals(PermissionDecision.DENY.ordinal(), (int) sink.get(0)[1]);
        synchronized (uncaught) { assertSame(boom, uncaught.get(0)); }
    }

    @Test
    public void answerLaterFromAnotherThreadReachesSink() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        dispatcher.setHandler(r -> new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            r.allow();
            done.countDown();
        }).start());
        assertEquals(PermissionDispatcher.PENDING,
            dispatcher.dispatch(5, "", "permtest://app/", 2));
        assertEquals(1, dispatcher.pendingCount());
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, sink.count());
        assertEquals(5L, sink.get(0)[0]);
        assertEquals(PermissionDecision.ALLOW.ordinal(), (int) sink.get(0)[1]);
    }

    @Test
    public void disposeDeniesEveryPendingRequest() {
        dispatcher.setHandler(r -> { /* never answers */ });
        dispatcher.dispatch(1, "", "permtest://app/", 1);
        dispatcher.dispatch(2, "", "permtest://app/", 2);
        assertEquals(2, dispatcher.pendingCount());
        dispatcher.disposeAll();
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(2, sink.count());
        assertEquals(PermissionDecision.DENY.ordinal(), (int) sink.get(0)[1]);
        assertEquals(PermissionDecision.DENY.ordinal(), (int) sink.get(1)[1]);
    }

    @Test
    public void handlerCanBeRemoved() {
        dispatcher.setHandler(r -> r.allow());
        dispatcher.setHandler(null);
        assertEquals(PermissionDecision.DEFAULT.ordinal(),
            dispatcher.dispatch(1, "", "permtest://app/", 1));
    }

    @Test
    public void fromBitsIgnoresUnknownBits() {
        assertEquals(EnumSet.of(MediaCaptureKind.DISPLAY), MediaCaptureKind.fromBits(4 | 8 | 128));
        assertTrue(MediaCaptureKind.fromBits(0).isEmpty());
        assertEquals(EnumSet.allOf(MediaCaptureKind.class), MediaCaptureKind.fromBits(7));
    }
}
