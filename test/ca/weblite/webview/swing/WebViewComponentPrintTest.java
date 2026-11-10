/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.AsyncJavascriptFunction;
import ca.weblite.webview.JavascriptFunction;
import ca.weblite.webview.PrintDialogs;
import ca.weblite.webview.PrintOutcome;
import ca.weblite.webview.WebView;
import ca.weblite.webview.WebViewPrintCallback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * The {@code WebViewComponent} print-dialog contract (Canvas 38 op 5) without a
 * native peer.  The dialogs themselves are verified with the demo.
 */
public class WebViewComponentPrintTest {

    /** Stub whose "peer" records the callback it was handed. */
    private static final class StubComponent extends WebViewComponent {
        volatile boolean available = true;
        volatile boolean attached = true;
        volatile WebViewPrintCallback lastCb;
        volatile int peerCalls;

        @Override protected boolean printDialogAvailable() { return available; }

        @Override protected boolean printOnPeer(WebViewPrintCallback cb) {
            if (!attached) return false;
            peerCalls++;
            lastCb = cb;
            return true;
        }

        void close() { failPendingPrint(); }

        void pageAsks(String url) { dispatchPrintRequest(url); }

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

    private static String failure(CompletableFuture<PrintOutcome> f) throws Exception {
        try {
            f.get(5, TimeUnit.SECONDS);
            fail("expected a failure");
            return null;
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
            return e.getCause().getMessage();
        }
    }

    /** Run the EDT queue up to now. */
    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    public void unavailableFailsAtOnce() throws Exception {
        StubComponent c = new StubComponent();
        c.available = false;
        assertEquals(PrintDialogs.NOT_AVAILABLE, failure(c.print()));
        assertEquals(0, c.peerCalls);
    }

    @Test
    public void noPeerIsNotAttached() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = false;
        assertEquals(PrintDialogs.NOT_ATTACHED, failure(c.print()));
    }

    @Test
    public void outcomeArrivesOnTheEdt() throws Exception {
        StubComponent c = new StubComponent();
        CompletableFuture<PrintOutcome> f = c.print();
        flushEdt();
        AtomicBoolean onEdt = new AtomicBoolean();
        CompletableFuture<Void> seen = f.thenAccept(o -> onEdt.set(SwingUtilities.isEventDispatchThread()));
        c.lastCb.onPrintFinished(PrintOutcome.PRINTED.ordinal(), null);
        assertEquals(PrintOutcome.PRINTED, f.get(5, TimeUnit.SECONDS));
        seen.get(5, TimeUnit.SECONDS);
        assertTrue(onEdt.get());
    }

    @Test
    public void secondPrintIsBusyAndFirstUnaffected() throws Exception {
        StubComponent c = new StubComponent();
        CompletableFuture<PrintOutcome> first = c.print();
        CompletableFuture<PrintOutcome> second = c.print();
        assertEquals(PrintDialogs.BUSY, failure(second));
        assertFalse(first.isDone());
        assertEquals(1, c.peerCalls);
        c.lastCb.onPrintFinished(PrintOutcome.CANCELLED.ordinal(), null);
        assertEquals(PrintOutcome.CANCELLED, first.get(5, TimeUnit.SECONDS));
        flushEdt();
        // Once answered, a new print may start.
        CompletableFuture<PrintOutcome> third = c.print();
        flushEdt();
        assertEquals(2, c.peerCalls);
        c.lastCb.onPrintFinished(PrintOutcome.OPENED.ordinal(), null);
        assertEquals(PrintOutcome.OPENED, third.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void closingFailsThePendingPrintAndIgnoresALateAnswer() throws Exception {
        StubComponent c = new StubComponent();
        CompletableFuture<PrintOutcome> f = c.print();
        flushEdt();
        WebViewPrintCallback cb = c.lastCb;
        SwingUtilities.invokeAndWait(c::close);
        assertEquals(PrintDialogs.CLOSED, failure(f));
        cb.onPrintFinished(PrintOutcome.PRINTED.ordinal(), null);
        assertEquals(PrintDialogs.CLOSED, failure(f));
    }

    @Test
    public void pageRequestWithoutHandlerPrints() throws Exception {
        StubComponent c = new StubComponent();
        assertNull(c.getPrintHandler());
        c.pageAsks("https://example.com/receipt");
        flushEdt();
        assertEquals(1, c.peerCalls);
    }

    @Test
    public void denyingHandlerSeesTheUrlAndNothingOpens() throws Exception {
        StubComponent c = new StubComponent();
        AtomicReference<String> asked = new AtomicReference<String>();
        AtomicReference<WebViewComponent> who = new AtomicReference<WebViewComponent>();
        c.setPrintHandler((component, url) -> {
            who.set(component);
            asked.set(url);
            return false;
        });
        c.pageAsks("https://example.com/ticket");
        flushEdt();
        assertEquals("https://example.com/ticket", asked.get());
        assertSame(c, who.get());
        assertEquals(0, c.peerCalls);
    }

    @Test
    public void allowingHandlerPrints() throws Exception {
        StubComponent c = new StubComponent();
        c.setPrintHandler((component, url) -> true);
        c.pageAsks("https://example.com/");
        flushEdt();
        assertEquals(1, c.peerCalls);
    }

    @Test
    public void throwingHandlerRefuses() throws Exception {
        StubComponent c = new StubComponent();
        c.setPrintHandler((component, url) -> { throw new IllegalStateException("boom"); });
        c.pageAsks("https://example.com/");
        flushEdt();
        assertEquals(0, c.peerCalls);
    }

    @Test
    public void pageRequestWhilePrintingIsDropped() throws Exception {
        StubComponent c = new StubComponent();
        c.print();
        flushEdt();
        c.pageAsks("https://example.com/");
        flushEdt();
        assertEquals(1, c.peerCalls);
    }
}
