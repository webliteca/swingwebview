/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/**
 * The {@code WebViewComponent} print-to-PDF contract (Canvas 29 op 6) without
 * a native peer: the future's outcomes, completion on the EDT, exactly-once,
 * and teardown.  Printing a real page is verified with the demo.
 */
public class WebViewComponentPrintToPdfTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Stub whose "peer" records the callback it was handed. */
    private static final class StubComponent extends WebViewComponent {
        volatile boolean attached = false;
        volatile WebViewPdfCallback lastCb;
        volatile PdfOptions lastOptions;

        @Override protected boolean pdfPrintingAvailable() { return true; }

        @Override protected boolean printToPdfOnPeer(File out, PdfOptions o,
                                                     WebViewPdfCallback cb) {
            if (!attached) return false;
            lastOptions = o;
            lastCb = cb;
            return true;
        }

        void close() {
            failPendingPdf();
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

    private static String failure(CompletableFuture<File> f) throws Exception {
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
            return e.getCause().getMessage();
        }
        fail("expected the print to fail");
        return null;
    }

    /** Wait until the EDT has run everything queued so far. */
    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    public void testNoPeerFailsNotAttached() throws Exception {
        StubComponent c = new StubComponent();
        CompletableFuture<File> f = c.printToPdf(tmp.newFile("a.pdf"));
        assertEquals("The WebView is not attached yet.", failure(f));
    }

    @Test
    public void testMissingFolderFailsWithTheFolder() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = true;
        File dir = new File(tmp.getRoot(), "missing");
        CompletableFuture<File> f = c.printToPdf(new File(dir, "a.pdf"));
        assertEquals("The folder " + dir.getAbsolutePath() + " does not exist.",
                failure(f));
        assertEquals(null, c.lastCb);
    }

    @Test
    public void testInvalidOptionsFailWithTheirSentence() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = true;
        CompletableFuture<File> f = c.printToPdf(tmp.newFile("a.pdf"),
                PdfOptions.letter().withMargins(-1));
        assertEquals("Margins must not be negative.", failure(f));
    }

    @Test
    public void testNullDestination() throws Exception {
        StubComponent c = new StubComponent();
        assertEquals("Say where to write the PDF.", failure(c.printToPdf(null)));
    }

    @Test
    public void testPeerSuccessCompletesWithTheFileOnTheEdt() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = true;
        File out = tmp.newFile("a.pdf");
        CompletableFuture<File> f = c.printToPdf(out);
        final AtomicBoolean onEdt = new AtomicBoolean();
        f.whenComplete((file, err) ->
                onEdt.set(SwingUtilities.isEventDispatchThread()));
        drainEdt();
        assertEquals(8.5, c.lastOptions.getPageWidth(), 1e-9);
        c.lastCb.onPdfFinished(true, null);
        // Drain rather than f.get(): a thread blocked in get() runs pending
        // dependents itself once it wakes, so the whenComplete above could
        // run off the EDT and the check would race.
        drainEdt();
        assertTrue(f.isDone());
        assertSame(out, f.getNow(null));
        assertTrue("completes on the EDT", onEdt.get());
    }

    @Test
    public void testPeerErrorFailsWithItsMessage() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = true;
        CompletableFuture<File> f = c.printToPdf(tmp.newFile("a.pdf"),
                PdfOptions.a4());
        drainEdt();
        assertEquals(8.27, c.lastOptions.getPageWidth(), 1e-9);
        c.lastCb.onPdfFinished(false, "PDF printing needs a newer WebView2 runtime.");
        assertEquals("PDF printing needs a newer WebView2 runtime.", failure(f));
    }

    @Test
    public void testASecondCallbackIsIgnored() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = true;
        File out = tmp.newFile("a.pdf");
        CompletableFuture<File> f = c.printToPdf(out);
        drainEdt();
        c.lastCb.onPdfFinished(true, null);
        c.lastCb.onPdfFinished(false, "late");
        assertSame(out, f.get(5, TimeUnit.SECONDS));
        drainEdt();
        assertFalse(f.isCompletedExceptionally());
    }

    @Test
    public void testClosingFailsPendingPrints() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = true;
        CompletableFuture<File> f1 = c.printToPdf(tmp.newFile("a.pdf"));
        CompletableFuture<File> f2 = c.printToPdf(tmp.newFile("b.pdf"));
        drainEdt();
        SwingUtilities.invokeAndWait(c::close);
        assertEquals("The WebView was closed.", failure(f1));
        assertEquals("The WebView was closed.", failure(f2));
    }

    @Test
    public void testAnsweredPrintsAreNotFailedOnClose() throws Exception {
        StubComponent c = new StubComponent();
        c.attached = true;
        File out = tmp.newFile("a.pdf");
        CompletableFuture<File> f = c.printToPdf(out);
        drainEdt();
        c.lastCb.onPdfFinished(true, null);
        assertSame(out, f.get(5, TimeUnit.SECONDS));
        drainEdt();
        SwingUtilities.invokeAndWait(c::close);
        assertSame(out, f.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSupportProbeNeverThrows() {
        assertEquals(PdfPrinting.isAvailable(),
                WebViewComponent.isPdfPrintingSupported());
    }
}
