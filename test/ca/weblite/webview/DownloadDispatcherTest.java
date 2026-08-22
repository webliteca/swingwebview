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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Unit tests for {@link DownloadDispatcher} — exercise the Java
 * contract (handler registration, EDT marshaling, drop semantics,
 * refusal, exception isolation, progress coalescing, exactly-once
 * completion, dispose semantics, and filename sanitisation) without a
 * live native engine.  GUI and per-platform integration are verified by
 * {@code WebViewDownloadDemo}; this file covers the dispatcher's logic.
 */
public class DownloadDispatcherTest {

    /** Minimal {@link WebViewComponent} subclass usable as a
     *  dispatcher source — every abstract method returns {@code this}
     *  or a sentinel.  Never actually attaches to a native peer. */
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

    /** Recording handler: a fixed destination (or {@code null} to
     *  refuse) plus every progress and completion event it saw. */
    private static final class RecordingHandler
            implements WebViewDownloadHandler {
        final File destination;
        final List<WebViewDownloadProgressEvent> progress =
            Collections.synchronizedList(
                new ArrayList<WebViewDownloadProgressEvent>());
        final List<WebViewDownloadCompleteEvent> completed =
            Collections.synchronizedList(
                new ArrayList<WebViewDownloadCompleteEvent>());
        volatile WebViewDownloadEvent lastRequest;
        volatile int requestCount = 0;

        RecordingHandler(File destination) { this.destination = destination; }

        @Override public File downloadRequested(WebViewDownloadEvent e) {
            requestCount++;
            lastRequest = e;
            return destination;
        }
        @Override public void downloadProgress(
                WebViewDownloadProgressEvent e) { progress.add(e); }
        @Override public void downloadCompleted(
                WebViewDownloadCompleteEvent e) { completed.add(e); }
    }

    private StubComponent source;
    private DownloadDispatcher dispatcher;
    private Thread.UncaughtExceptionHandler priorHandler;
    private AtomicReference<Throwable> uncaught;

    @Before
    public void setUp() {
        source = new StubComponent();
        dispatcher = new DownloadDispatcher(source);
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

    /** Let every already-queued EDT task run to completion. */
    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { }
        });
    }

    private static File tempTarget(String name) {
        return new File(System.getProperty("java.io.tmpdir"), name);
    }

    // -----------------------------------------------------------------
    // Handler-reference lifecycle.
    // -----------------------------------------------------------------

    @Test
    public void testDefaultHandlerIsDEFAULT() {
        assertSame(WebViewDownloadHandler.DEFAULT, dispatcher.getHandler());
    }

    @Test
    public void testNullInstallsDropHandlerNotDefault() {
        dispatcher.setHandler(null);
        assertNotNull(dispatcher.getHandler());
        assertNotSame(WebViewDownloadHandler.DEFAULT, dispatcher.getHandler());
        assertSame(DownloadDispatcher.DROP, dispatcher.getHandler());
        dispatcher.setHandler(WebViewDownloadHandler.DEFAULT);
        assertSame(WebViewDownloadHandler.DEFAULT, dispatcher.getHandler());
    }

    // -----------------------------------------------------------------
    // The destination decision.  (Story AC2, AC3, AC11, AC13.)
    // -----------------------------------------------------------------

    @Test
    public void testCustomHandlerChoosesDestination() throws Exception {
        File target = tempTarget("fixed.bin");
        RecordingHandler h = new RecordingHandler(target);
        dispatcher.setHandler(h);

        String path = dispatcher.dispatchDownloadRequested(
            1L, "https://example.com/a.bin", "a.bin",
            "APPLICATION/Octet-Stream", 2048L, "https://example.com/");

        assertEquals(target.getAbsolutePath(), path);
        assertEquals(1, h.requestCount);
        assertEquals("a.bin", h.lastRequest.suggestedFileName());
        assertEquals(2048L, h.lastRequest.totalBytes());
        assertTrue(h.lastRequest.sizeKnown());
        // MIME types are lower-cased on the way in.
        assertEquals("application/octet-stream", h.lastRequest.mimeType());
        assertEquals("https://example.com/", h.lastRequest.pageUrl());
    }

    @Test
    public void testRefusalReturnsNullAndReportsOneFailure() throws Exception {
        RecordingHandler h = new RecordingHandler(null);
        dispatcher.setHandler(h);

        String path = dispatcher.dispatchDownloadRequested(
            7L, "https://example.com/a.bin", "a.bin", "", -1L, "");

        assertNull(path);
        drainEdt();
        assertEquals(1, h.completed.size());
        WebViewDownloadCompleteEvent e = h.completed.get(0);
        assertEquals(7L, e.id());
        assertFalse(e.success());
        assertNull(e.destination());
        assertTrue(e.failureReason().length() > 0);
        assertEquals(0L, e.receivedBytes());
    }

    @Test
    public void testRefusalSwallowsLaterNativeFailure() throws Exception {
        RecordingHandler h = new RecordingHandler(null);
        dispatcher.setHandler(h);

        dispatcher.dispatchDownloadRequested(
            8L, "https://example.com/a.bin", "a.bin", "", -1L, "");
        // The native side cancels the transfer and reports the
        // cancellation; the id is already latched, so it is dropped.
        dispatcher.dispatchDownloadCompleted(8L, false, "Cancelled by user", 0L);
        drainEdt();

        assertEquals(1, h.completed.size());
    }

    @Test
    public void testThrowingHandlerRefusesAndIsIsolated() throws Exception {
        final List<WebViewDownloadCompleteEvent> done =
            Collections.synchronizedList(
                new ArrayList<WebViewDownloadCompleteEvent>());
        dispatcher.setHandler(new WebViewDownloadHandler() {
            @Override public File downloadRequested(WebViewDownloadEvent e) {
                throw new IllegalStateException("boom");
            }
            @Override public void downloadCompleted(
                    WebViewDownloadCompleteEvent e) { done.add(e); }
        });

        String path = dispatcher.dispatchDownloadRequested(
            9L, "https://example.com/a.bin", "a.bin", "", -1L, "");

        assertNull(path);
        drainEdt();
        assertEquals(1, done.size());
        assertFalse(done.get(0).success());
        assertNotNull(uncaught.get());
        assertEquals("boom", uncaught.get().getMessage());
    }

    @Test
    public void testDropHandlerRefusesEverything() throws Exception {
        dispatcher.setHandler(null);
        String path = dispatcher.dispatchDownloadRequested(
            10L, "https://example.com/a.bin", "a.bin", "", -1L, "");
        assertNull(path);
    }

    // -----------------------------------------------------------------
    // Progress.  (Story AC5, AC6, plus the coalescing rule.)
    // -----------------------------------------------------------------

    @Test
    public void testProgressIsReportedWithDeclaredTotal() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("p.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            20L, "https://example.com/p.bin", "p.bin", "", 10485760L, "");

        dispatcher.dispatchDownloadProgress(20L, 5242880L, 10485760L);
        drainEdt();

        assertEquals(1, h.progress.size());
        WebViewDownloadProgressEvent e = h.progress.get(0);
        assertEquals(20L, e.id());
        assertEquals(5242880L, e.receivedBytes());
        assertEquals(10485760L, e.totalBytes());
        assertTrue(e.sizeKnown());
        assertEquals(0.5, e.fraction(), 0.0001);
    }

    @Test
    public void testUnknownSizeStaysUnknownNotZero() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("q.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            21L, "https://example.com/q.bin", "q.bin", "", -1L, "");

        dispatcher.dispatchDownloadProgress(21L, 4096L, -1L);
        drainEdt();

        assertEquals(1, h.progress.size());
        WebViewDownloadProgressEvent e = h.progress.get(0);
        assertEquals(-1L, e.totalBytes());
        assertFalse(e.sizeKnown());
        assertEquals(-1.0, e.fraction(), 0.0001);
    }

    @Test
    public void testProgressIsCoalescedAndKeepsLatestCounts()
            throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("r.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            22L, "https://example.com/r.bin", "r.bin", "", 8192000L, "");

        final int bursts = 1000;
        for (int i = 1; i <= bursts; i++) {
            dispatcher.dispatchDownloadProgress(22L, i * 8192L, 8192000L);
        }
        drainEdt();

        assertTrue("expected coalescing, saw " + h.progress.size() + " events",
            h.progress.size() < bursts);
        assertTrue(h.progress.size() >= 1);
        WebViewDownloadProgressEvent last =
            h.progress.get(h.progress.size() - 1);
        assertEquals(bursts * 8192L, last.receivedBytes());
    }

    @Test
    public void testProgressStaysMonotonicWhenEventsArriveOutOfOrder()
            throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("s.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            23L, "https://example.com/s.bin", "s.bin", "", 1000L, "");

        dispatcher.dispatchDownloadProgress(23L, 900L, 1000L);
        dispatcher.dispatchDownloadProgress(23L, 100L, 1000L);
        drainEdt();

        assertTrue(h.progress.size() >= 1);
        assertEquals(900L,
            h.progress.get(h.progress.size() - 1).receivedBytes());
    }

    @Test
    public void testProgressForUnknownIdIsDropped() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("t.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadProgress(999L, 1L, 2L);
        drainEdt();
        assertEquals(0, h.progress.size());
    }

    // -----------------------------------------------------------------
    // Completion.  (Story AC7, AC8.)
    // -----------------------------------------------------------------

    @Test
    public void testCompletionIsReportedOnceOnSuccess() throws Exception {
        File target = tempTarget("u.bin");
        RecordingHandler h = new RecordingHandler(target);
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            30L, "https://example.com/u.bin", "u.bin", "", 100L, "");

        dispatcher.dispatchDownloadCompleted(30L, true, "", 100L);
        drainEdt();

        assertEquals(1, h.completed.size());
        WebViewDownloadCompleteEvent e = h.completed.get(0);
        assertTrue(e.success());
        assertEquals(target, e.destination());
        assertEquals(100L, e.receivedBytes());
        assertEquals("", e.failureReason());
    }

    @Test
    public void testDuplicateCompletionIsSwallowed() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("v.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            31L, "https://example.com/v.bin", "v.bin", "", 100L, "");

        dispatcher.dispatchDownloadCompleted(31L, true, "", 100L);
        dispatcher.dispatchDownloadCompleted(31L, true, "", 100L);
        drainEdt();

        assertEquals(1, h.completed.size());
    }

    @Test
    public void testFailedThenFinishedReportsOnlyTheFirst() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("w.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            32L, "https://example.com/w.bin", "w.bin", "", 100L, "");

        // WebKitGTK can emit both for one abandoned transfer.
        dispatcher.dispatchDownloadCompleted(32L, false, "Network failed", 40L);
        dispatcher.dispatchDownloadCompleted(32L, true, "", 40L);
        drainEdt();

        assertEquals(1, h.completed.size());
        WebViewDownloadCompleteEvent e = h.completed.get(0);
        assertFalse(e.success());
        assertEquals("Network failed", e.failureReason());
        assertEquals(40L, e.receivedBytes());
    }

    @Test
    public void testProgressAfterCompletionIsDropped() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("x.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            33L, "https://example.com/x.bin", "x.bin", "", 100L, "");
        dispatcher.dispatchDownloadCompleted(33L, true, "", 100L);
        drainEdt();
        int before = h.progress.size();

        dispatcher.dispatchDownloadProgress(33L, 100L, 100L);
        drainEdt();

        assertEquals(before, h.progress.size());
    }

    // -----------------------------------------------------------------
    // Disposal.  (Story AC12, AC13.)
    // -----------------------------------------------------------------

    @Test
    public void testDisposeRefusesWithoutCallingHandler() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("y.bin"));
        dispatcher.setHandler(h);
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());

        String path = dispatcher.dispatchDownloadRequested(
            40L, "https://example.com/y.bin", "y.bin", "", 100L, "");

        assertNull(path);
        assertEquals(0, h.requestCount);
        drainEdt();
        assertEquals(0, h.completed.size());
    }

    @Test
    public void testDisposeSilencesInFlightReports() throws Exception {
        RecordingHandler h = new RecordingHandler(tempTarget("z.bin"));
        dispatcher.setHandler(h);
        dispatcher.dispatchDownloadRequested(
            41L, "https://example.com/z.bin", "z.bin", "", 100L, "");
        dispatcher.disposeAll();

        dispatcher.dispatchDownloadProgress(41L, 50L, 100L);
        dispatcher.dispatchDownloadCompleted(41L, true, "", 100L);
        drainEdt();

        assertEquals(0, h.progress.size());
        assertEquals(0, h.completed.size());
    }

    @Test
    public void testDisposeIsIdempotent() {
        dispatcher.disposeAll();
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());
    }

    // -----------------------------------------------------------------
    // Filename sanitisation.  (Story AC9, AC10 and the Safeguards.)
    // -----------------------------------------------------------------

    @Test
    public void testTraversalIsReducedToALeafName() {
        assertEquals("passwd", DownloadDispatcher.sanitiseFileName(
            "../../../../etc/passwd", "https://evil.example/x"));
        assertEquals("evil.exe", DownloadDispatcher.sanitiseFileName(
            "..\\..\\evil.exe", "https://evil.example/x"));
        assertEquals("passwd", DownloadDispatcher.sanitiseFileName(
            "/etc/passwd", "https://evil.example/x"));
        assertEquals("x.dll", DownloadDispatcher.sanitiseFileName(
            "C:\\Windows\\System32\\x.dll", "https://evil.example/x"));
    }

    @Test
    public void testMissingNameFallsBackToUrlSegmentThenToDownload() {
        assertEquals("report.pdf", DownloadDispatcher.sanitiseFileName(
            null, "https://example.com/files/report.pdf?token=abc#frag"));
        assertEquals("report.pdf", DownloadDispatcher.sanitiseFileName(
            "", "https://example.com/files/report.pdf"));
        assertEquals("download", DownloadDispatcher.sanitiseFileName(
            null, "https://example.com/"));
        assertEquals("download", DownloadDispatcher.sanitiseFileName(
            null, null));
    }

    @Test
    public void testNamesThatSanitiseAwayFallBackToDownload() {
        assertEquals("download",
            DownloadDispatcher.sanitiseFileName("...", "https://x/"));
        assertEquals("download",
            DownloadDispatcher.sanitiseFileName("..", "https://x/"));
        assertEquals("download",
            DownloadDispatcher.sanitiseFileName("   ", "https://x/"));
    }

    @Test
    public void testUrlSegmentIsPercentDecoded() {
        assertEquals("Q3 report.pdf", DownloadDispatcher.sanitiseFileName(
            null, "https://example.com/Q3%20report.pdf"));
        // A literal '+' in a path is not a space.
        assertEquals("a+b.txt", DownloadDispatcher.sanitiseFileName(
            null, "https://example.com/a+b.txt"));
    }

    @Test
    public void testWindowsIllegalCharactersAreStripped() {
        assertEquals("ab.txt", DownloadDispatcher.sanitiseFileName(
            "a<>:\"|?*b.txt", "https://x/"));
        assertEquals("ab.txt", DownloadDispatcher.sanitiseFileName(
            "a\u0000\u0007b.txt", "https://x/"));
    }

    @Test
    public void testTrailingDotsAndSpacesAreTrimmed() {
        assertEquals("evil.exe", DownloadDispatcher.sanitiseFileName(
            "evil.exe.", "https://x/"));
        assertEquals("evil.exe", DownloadDispatcher.sanitiseFileName(
            "  evil.exe  ", "https://x/"));
    }

    @Test
    public void testReservedWindowsDeviceNamesArePrefixed() {
        assertEquals("_CON.txt",
            DownloadDispatcher.sanitiseFileName("CON.txt", "https://x/"));
        assertEquals("_nul",
            DownloadDispatcher.sanitiseFileName("nul", "https://x/"));
        assertEquals("_LPT1.dat",
            DownloadDispatcher.sanitiseFileName("LPT1.dat", "https://x/"));
        // Not reserved: a device name with more to it.
        assertEquals("CONSOLE.txt",
            DownloadDispatcher.sanitiseFileName("CONSOLE.txt", "https://x/"));
    }

    @Test
    public void testLongNamesAreBoundedWithTheExtensionIntact() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 396; i++) sb.append('a');
        sb.append(".txt");
        String out = DownloadDispatcher.sanitiseFileName(
            sb.toString(), "https://x/");
        assertEquals(255, out.length());
        assertTrue(out.endsWith(".txt"));
    }

    @Test
    public void testSanitiseNeverReturnsNullOrEmpty() {
        String[] nasty = {
            null, "", "   ", "/", "\\", "..", "...", "././.",
            "\u0000", "<>:\"|?*", ".", "/////"
        };
        for (String n : nasty) {
            String out = DownloadDispatcher.sanitiseFileName(n, null);
            assertNotNull(out);
            assertTrue("empty for input <" + n + ">", out.length() > 0);
        }
    }
}
