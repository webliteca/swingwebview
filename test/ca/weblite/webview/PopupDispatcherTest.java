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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Unit tests for {@link PopupDispatcher} — exercise the Java contract
 * (handler registration, drop semantics, the synchronous off-EDT
 * allow/deny gate, EDT-marshalled open/close notifications, open→close
 * correlation, exception isolation, dispose semantics) without a live
 * native engine.  GUI integration is verified by {@code WebViewPopupDemo}.
 */
public class PopupDispatcherTest {

    /** Minimal {@link WebViewComponent} subclass usable as a dispatcher
     *  source; never attaches to a native peer. */
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

    private StubComponent source;
    private PopupDispatcher dispatcher;
    private Thread.UncaughtExceptionHandler priorHandler;
    private AtomicReference<Throwable> uncaught;

    @Before
    public void setUp() {
        source = new StubComponent();
        dispatcher = new PopupDispatcher(source);
        priorHandler = Thread.getDefaultUncaughtExceptionHandler();
        uncaught = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler(
            new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    uncaught.compareAndSet(null, e);
                }
            });
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(priorHandler);
    }

    // -----------------------------------------------------------------
    // Handler-reference lifecycle.
    // -----------------------------------------------------------------

    @Test
    public void testDefaultHandlerIsDEFAULT() {
        assertSame(WebViewPopupHandler.DEFAULT, dispatcher.getHandler());
    }

    @Test
    public void testGetHandlerNeverReturnsNull() {
        assertNotNull(dispatcher.getHandler());
        dispatcher.setHandler(null);
        assertNotNull(dispatcher.getHandler());
        dispatcher.setHandler(WebViewPopupHandler.DEFAULT);
        assertNotNull(dispatcher.getHandler());
    }

    @Test
    public void testSetHandlerNullInstallsDrop() {
        dispatcher.setHandler(null);
        WebViewPopupHandler h = dispatcher.getHandler();
        assertNotNull(h);
        assertNotSame("DROP must not be identity-equal to DEFAULT",
            WebViewPopupHandler.DEFAULT, h);
    }

    @Test
    public void testSetHandlerCustomIsReturnedByGetter() {
        WebViewPopupHandler custom = new WebViewPopupHandler() {};
        dispatcher.setHandler(custom);
        assertSame(custom, dispatcher.getHandler());
    }

    @Test
    public void testSetHandlerDefaultAfterNullResets() {
        dispatcher.setHandler(null);
        assertNotSame(WebViewPopupHandler.DEFAULT, dispatcher.getHandler());
        dispatcher.setHandler(WebViewPopupHandler.DEFAULT);
        assertSame(WebViewPopupHandler.DEFAULT, dispatcher.getHandler());
    }

    // -----------------------------------------------------------------
    // Allow/deny gate.
    // -----------------------------------------------------------------

    @Test
    public void testDefaultHandlerAllows() {
        assertTrue(dispatcher.dispatchPopupRequested(
            "https://e.com", "_blank", true, 500, 650, "https://o.com"));
    }

    @Test
    public void testDropHandlerBlocks() {
        dispatcher.setHandler(null);
        assertFalse(dispatcher.dispatchPopupRequested(
            "https://e.com", "_blank", true, -1, -1, "https://o.com"));
    }

    @Test
    public void testPopupRequestedRunsOffEdtAndReturnsHandlerValue() {
        // The test thread is NOT the EDT; the gate must run the handler
        // inline here (off the EDT) and return its value synchronously.
        final AtomicReference<Boolean> onEdt = new AtomicReference<Boolean>();
        final AtomicReference<WebViewPopupEvent> received =
            new AtomicReference<WebViewPopupEvent>();
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                received.set(e);
                return false;
            }
        });
        boolean allowed = dispatcher.dispatchPopupRequested(
            "https://e.com/auth", "authwin", true, 520, 640, "https://o.com");
        assertFalse(allowed);
        assertEquals("gate must run off the EDT", Boolean.FALSE, onEdt.get());
        WebViewPopupEvent ev = received.get();
        assertNotNull(ev);
        assertSame(source, ev.source());
        assertEquals("https://e.com/auth", ev.targetUrl());
        assertEquals("authwin", ev.targetName());
        assertTrue(ev.userGesture());
        assertEquals(520, ev.width());
        assertEquals(640, ev.height());
        assertEquals("https://o.com", ev.pageUrl());
    }

    @Test
    public void testPopupRequestedUnspecifiedSizeIsMinusOne() {
        final AtomicReference<WebViewPopupEvent> received =
            new AtomicReference<WebViewPopupEvent>();
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                received.set(e);
                return true;
            }
        });
        dispatcher.dispatchPopupRequested("u", "", false, -1, -1, "p");
        assertEquals(-1, received.get().width());
        assertEquals(-1, received.get().height());
        assertFalse(received.get().userGesture());
    }

    // -----------------------------------------------------------------
    // Open / close notifications (EDT-marshalled) + correlation.
    // -----------------------------------------------------------------

    @Test
    public void testPopupOpenedNotifiedOnEdt() throws Exception {
        final AtomicReference<Boolean> onEdt = new AtomicReference<Boolean>();
        final AtomicReference<WebViewPopupEvent> received =
            new AtomicReference<WebViewPopupEvent>();
        final CountDownLatch latch = new CountDownLatch(1);
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public void popupOpened(WebViewPopupEvent e) {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                received.set(e);
                latch.countDown();
            }
        });
        dispatcher.dispatchPopupOpened(
            42L, "https://e.com", "_blank", true, 500, 650, "https://o.com");
        assertTrue("popupOpened not delivered",
            latch.await(2, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, onEdt.get());
        assertEquals("https://e.com", received.get().targetUrl());
        assertEquals(500, received.get().width());
    }

    @Test
    public void testPopupClosedReceivesSameEventInstanceAsOpen()
            throws Exception {
        final AtomicReference<WebViewPopupEvent> opened =
            new AtomicReference<WebViewPopupEvent>();
        final AtomicReference<WebViewPopupEvent> closed =
            new AtomicReference<WebViewPopupEvent>();
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public void popupOpened(WebViewPopupEvent e) {
                opened.set(e);
                openLatch.countDown();
            }
            @Override public void popupClosed(WebViewPopupEvent e) {
                closed.set(e);
                closeLatch.countDown();
            }
        });
        dispatcher.dispatchPopupOpened(
            7L, "https://e.com", "n", true, 500, 650, "https://o.com");
        assertTrue(openLatch.await(2, TimeUnit.SECONDS));
        dispatcher.dispatchPopupClosed(7L, "ignored-url", "ignored-page");
        assertTrue(closeLatch.await(2, TimeUnit.SECONDS));
        // Correlation: close reuses the rich event stored at open.
        assertSame(opened.get(), closed.get());
        assertEquals("https://e.com", closed.get().targetUrl());
    }

    @Test
    public void testPopupClosedWithoutOpenBuildsMinimalEvent()
            throws Exception {
        final AtomicReference<WebViewPopupEvent> closed =
            new AtomicReference<WebViewPopupEvent>();
        final CountDownLatch latch = new CountDownLatch(1);
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public void popupClosed(WebViewPopupEvent e) {
                closed.set(e);
                latch.countDown();
            }
        });
        dispatcher.dispatchPopupClosed(999L, "https://late.com", "https://o.com");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(closed.get());
        assertEquals("https://late.com", closed.get().targetUrl());
        assertEquals("https://o.com", closed.get().pageUrl());
        assertEquals(-1, closed.get().width());
    }

    // -----------------------------------------------------------------
    // Exception isolation.
    // -----------------------------------------------------------------

    @Test
    public void testPopupRequestedExceptionBlocksAndIsolated() {
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                throw new RuntimeException("boom-req");
            }
        });
        // A thrown decision blocks the popup (returns false) and does not
        // propagate to the native caller.
        assertFalse(dispatcher.dispatchPopupRequested(
            "u", "n", true, -1, -1, "p"));
        waitForUncaught();
        assertNotNull(uncaught.get());
        assertEquals("boom-req", uncaught.get().getMessage());
    }

    @Test
    public void testPopupOpenedExceptionIsolated() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public void popupOpened(WebViewPopupEvent e) {
                try {
                    throw new RuntimeException("boom-open");
                } finally {
                    latch.countDown();
                }
            }
        });
        dispatcher.dispatchPopupOpened(1L, "u", "n", true, -1, -1, "p");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        waitForUncaught();
        assertNotNull(uncaught.get());
    }

    // -----------------------------------------------------------------
    // Disposal semantics.
    // -----------------------------------------------------------------

    @Test
    public void testDisposedDispatcherReturnsFallbacks() {
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                throw new AssertionError("handler invoked after dispose");
            }
            @Override public void popupOpened(WebViewPopupEvent e) {
                throw new AssertionError("handler invoked after dispose");
            }
            @Override public void popupClosed(WebViewPopupEvent e) {
                throw new AssertionError("handler invoked after dispose");
            }
        });
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());
        assertFalse(dispatcher.dispatchPopupRequested(
            "u", "n", true, -1, -1, "p"));
        dispatcher.dispatchPopupOpened(1L, "u", "n", true, -1, -1, "p");
        dispatcher.dispatchPopupClosed(1L, "u", "p");
    }

    @Test
    public void testDisposeAllIsIdempotent() {
        dispatcher.disposeAll();
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());
    }

    // -----------------------------------------------------------------
    // Disposition gate (Canvas 18).
    // -----------------------------------------------------------------

    @Test
    public void testDispositionDefaultDerivesFromBooleanTrue() {
        // A legacy handler overriding only popupRequested (true) maps to
        // NATIVE_WINDOW via the popupDisposition default.
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                return true;
            }
        });
        assertEquals(PopupDisposition.NATIVE_WINDOW.ordinal(),
            dispatcher.dispatchPopupDisposition("u", "n", true, -1, -1, "p"));
    }

    @Test
    public void testDispositionDefaultDerivesFromBooleanFalse() {
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                return false;
            }
        });
        assertEquals(PopupDisposition.BLOCK.ordinal(),
            dispatcher.dispatchPopupDisposition("u", "n", true, -1, -1, "p"));
    }

    @Test
    public void testDispositionDefaultHandlerIsNativeWindow() {
        // DEFAULT handler: popupRequested() returns true -> NATIVE_WINDOW.
        assertEquals(PopupDisposition.NATIVE_WINDOW.ordinal(),
            dispatcher.dispatchPopupDisposition("u", "n", true, 500, 650, "p"));
    }

    @Test
    public void testDispositionDropHandlerBlocks() {
        dispatcher.setHandler(null); // DROP
        assertEquals(PopupDisposition.BLOCK.ordinal(),
            dispatcher.dispatchPopupDisposition("u", "n", true, -1, -1, "p"));
    }

    @Test
    public void testDispositionAdoptRunsOffEdtAndReturnsOrdinal() {
        final AtomicReference<Boolean> onEdt = new AtomicReference<Boolean>();
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public PopupDisposition popupDisposition(
                    WebViewPopupEvent e) {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                return PopupDisposition.ADOPT;
            }
        });
        int d = dispatcher.dispatchPopupDisposition(
            "https://e.com/auth", "authwin", true, 520, 640, "https://o.com");
        assertEquals(PopupDisposition.ADOPT.ordinal(), d);
        assertEquals("disposition must run off the EDT",
            Boolean.FALSE, onEdt.get());
    }

    @Test
    public void testDispositionNullReturnBlocks() {
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public PopupDisposition popupDisposition(
                    WebViewPopupEvent e) {
                return null;
            }
        });
        assertEquals(PopupDisposition.BLOCK.ordinal(),
            dispatcher.dispatchPopupDisposition("u", "n", true, -1, -1, "p"));
    }

    @Test
    public void testDispositionExceptionBlocksAndIsolated() {
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public PopupDisposition popupDisposition(
                    WebViewPopupEvent e) {
                throw new RuntimeException("boom-disp");
            }
        });
        assertEquals(PopupDisposition.BLOCK.ordinal(),
            dispatcher.dispatchPopupDisposition("u", "n", true, -1, -1, "p"));
        waitForUncaught();
        assertNotNull(uncaught.get());
        assertEquals("boom-disp", uncaught.get().getMessage());
    }

    @Test
    public void testDisposedDispatcherDispositionBlocks() {
        dispatcher.disposeAll();
        assertEquals(PopupDisposition.BLOCK.ordinal(),
            dispatcher.dispatchPopupDisposition("u", "n", true, -1, -1, "p"));
    }

    // -----------------------------------------------------------------
    // Adopt notification, claim-once, and reclaim (Canvas 18).
    // -----------------------------------------------------------------

    @Test
    public void testAdoptableNotifiedOnEdtAndPending() throws Exception {
        final AtomicReference<Boolean> onEdt = new AtomicReference<Boolean>();
        final AtomicReference<Long> gotId = new AtomicReference<Long>();
        final CountDownLatch latch = new CountDownLatch(1);
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public void popupAdoptable(WebViewPopupEvent e,
                                                 long popupId) {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                gotId.set(popupId);
                latch.countDown();
            }
        });
        dispatcher.dispatchPopupAdoptable(
            77L, "https://e.com", "win", true, 520, 640, "https://o.com");
        assertTrue("popupAdoptable not delivered",
            latch.await(2, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, onEdt.get());
        assertEquals(Long.valueOf(77L), gotId.get());
        assertEquals(1, dispatcher.pendingAdoptCount());
    }

    @Test
    public void testClaimAdoptReturnsStoredEventAndClearsPending() {
        dispatcher.dispatchPopupAdoptable(
            5L, "https://e.com", "win", true, 520, 640, "https://o.com");
        WebViewPopupEvent ev = dispatcher.claimAdopt(5L);
        assertNotNull(ev);
        assertSame(source, ev.source());
        assertEquals("https://e.com", ev.targetUrl());
        assertEquals(520, ev.width());
        assertEquals(0, dispatcher.pendingAdoptCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testClaimAdoptUnknownIdThrowsIAE() {
        dispatcher.claimAdopt(123456L);
    }

    @Test(expected = IllegalStateException.class)
    public void testClaimAdoptTwiceThrowsISE() {
        dispatcher.dispatchPopupAdoptable(
            9L, "u", "n", true, -1, -1, "p");
        dispatcher.claimAdopt(9L);      // first claim ok
        dispatcher.claimAdopt(9L);      // second claim -> ISE
    }

    @Test
    public void testReclaimAdoptsDiscardsViaSinkOnDispose() {
        final java.util.List<Long> discarded =
            new java.util.concurrent.CopyOnWriteArrayList<Long>();
        dispatcher.setReclaimSink(new PopupDispatcher.ReclaimSink() {
            @Override public void discard(long popupId) {
                discarded.add(Long.valueOf(popupId));
            }
        });
        dispatcher.dispatchPopupAdoptable(1L, "u", "n", true, -1, -1, "p");
        dispatcher.dispatchPopupAdoptable(2L, "u", "n", true, -1, -1, "p");
        assertEquals(2, dispatcher.pendingAdoptCount());
        dispatcher.disposeAll();
        assertEquals(0, dispatcher.pendingAdoptCount());
        assertTrue(discarded.contains(Long.valueOf(1L)));
        assertTrue(discarded.contains(Long.valueOf(2L)));
    }

    @Test
    public void testClaimedAdoptIsNotReclaimed() {
        final java.util.List<Long> discarded =
            new java.util.concurrent.CopyOnWriteArrayList<Long>();
        dispatcher.setReclaimSink(new PopupDispatcher.ReclaimSink() {
            @Override public void discard(long popupId) {
                discarded.add(Long.valueOf(popupId));
            }
        });
        dispatcher.dispatchPopupAdoptable(3L, "u", "n", true, -1, -1, "p");
        dispatcher.claimAdopt(3L);      // claimed -> removed from pending
        dispatcher.disposeAll();
        assertFalse("a claimed adoption must not be reclaimed",
            discarded.contains(Long.valueOf(3L)));
    }

    @Test
    public void testDisposedDispatcherAdoptableIsNoOp() {
        dispatcher.setHandler(new WebViewPopupHandler() {
            @Override public void popupAdoptable(WebViewPopupEvent e,
                                                 long popupId) {
                throw new AssertionError("handler invoked after dispose");
            }
        });
        dispatcher.disposeAll();
        dispatcher.dispatchPopupAdoptable(1L, "u", "n", true, -1, -1, "p");
        assertEquals(0, dispatcher.pendingAdoptCount());
    }

    // -----------------------------------------------------------------
    // Constructor null-checks.
    // -----------------------------------------------------------------

    @Test(expected = NullPointerException.class)
    public void testConstructorRejectsNullSource() {
        new PopupDispatcher(null);
    }

    // -----------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------

    private void waitForUncaught() {
        long deadline = System.currentTimeMillis() + 500;
        while (uncaught.get() == null
            && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
