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

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Unit tests for {@link DownloadDispatcher} — exercise the Java
 * contract (handler registration, EDT marshaling, drop semantics,
 * exception isolation, event-constructor sanitisation, dispose
 * semantics) without a live native engine. End-to-end behaviour
 * across the three native engines is verified by
 * {@code WebViewDownloadDemo}; this file covers the dispatcher's
 * logic.
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
        @Override public WebViewComponent dispatch(Runnable r) { return this; }
        @Override public void dispose() { }
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
                @Override public void uncaughtException(Thread t, Throwable e) {
                    uncaught.set(e);
                }
            });
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(priorHandler);
    }

    // ---------------------------------------------------------------------
    // Handler invariants.
    // ---------------------------------------------------------------------

    @Test
    public void getHandler_defaultsToFrameworkDefault() {
        assertSame(WebViewDownloadHandler.DEFAULT, dispatcher.getHandler());
    }

    @Test
    public void setHandler_replacesHandler() {
        WebViewDownloadHandler custom = new WebViewDownloadHandler() {
            @Override public File downloadRequested(WebViewDownloadEvent e) {
                return null;
            }
        };
        dispatcher.setHandler(custom);
        assertSame(custom, dispatcher.getHandler());
    }

    @Test
    public void setHandler_null_installsDrop() {
        dispatcher.setHandler(null);
        // The DROP singleton is package-private; we can verify
        // behaviour: getHandler is non-null, and dispatchDownload
        // returns null without UI.
        assertNotNull(dispatcher.getHandler());
        String path = dispatcher.dispatchDownload(
            "x.txt", "https://example.com/x.txt", "text/plain", 0L);
        assertNull(path);
    }

    @Test
    public void getHandler_neverReturnsNullEvenAfterDispose() {
        dispatcher.disposeAll();
        assertNotNull(dispatcher.getHandler());
    }

    // ---------------------------------------------------------------------
    // EDT marshaling.
    // ---------------------------------------------------------------------

    @Test
    public void dispatchDownload_runsHandlerOnEdt() throws Exception {
        final AtomicBoolean wasOnEdt = new AtomicBoolean(false);
        dispatcher.setHandler(new WebViewDownloadHandler() {
            @Override public File downloadRequested(WebViewDownloadEvent e) {
                wasOnEdt.set(SwingUtilities.isEventDispatchThread());
                return new File("/tmp/x");
            }
        });
        // Call from this (non-EDT) test thread.
        String path = dispatcher.dispatchDownload(
            "x.txt", "https://example.com/x.txt", "text/plain", 0L);
        assertNotNull(path);
        assertTrue("handler did not run on EDT", wasOnEdt.get());
    }

    @Test
    public void dispatchDownload_returnsAbsolutePath() {
        dispatcher.setHandler(new WebViewDownloadHandler() {
            @Override public File downloadRequested(WebViewDownloadEvent e) {
                return new File(System.getProperty("java.io.tmpdir"),
                    "x.bin");
            }
        });
        String path = dispatcher.dispatchDownload(
            "x.bin", "https://example.com/x.bin",
            "application/octet-stream", 123L);
        assertNotNull(path);
        assertTrue("expected absolute path, got " + path,
            new File(path).isAbsolute());
    }

    @Test
    public void dispatchDownload_returnsNullWhenHandlerReturnsNull() {
        dispatcher.setHandler(new WebViewDownloadHandler() {
            @Override public File downloadRequested(WebViewDownloadEvent e) {
                return null;
            }
        });
        String path = dispatcher.dispatchDownload(
            "x.txt", "https://example.com/x.txt", "text/plain", 0L);
        assertNull(path);
    }

    // ---------------------------------------------------------------------
    // Exception isolation.
    // ---------------------------------------------------------------------

    @Test
    public void dispatchDownload_isolatesHandlerException() {
        dispatcher.setHandler(new WebViewDownloadHandler() {
            @Override public File downloadRequested(WebViewDownloadEvent e) {
                throw new RuntimeException("boom");
            }
        });
        String path = dispatcher.dispatchDownload(
            "x.txt", "https://example.com/x.txt", "text/plain", 0L);
        assertNull("handler exception should cancel (return null)", path);
        assertNotNull("exception should be forwarded to uncaught handler",
            uncaught.get());
        assertEquals("boom", uncaught.get().getMessage());
    }

    // ---------------------------------------------------------------------
    // Dispose semantics.
    // ---------------------------------------------------------------------

    @Test
    public void disposeAll_returnsNullWithoutInvokingHandler() {
        final AtomicInteger invocations = new AtomicInteger(0);
        dispatcher.setHandler(new WebViewDownloadHandler() {
            @Override public File downloadRequested(WebViewDownloadEvent e) {
                invocations.incrementAndGet();
                return new File("/tmp/x");
            }
        });
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());
        String path = dispatcher.dispatchDownload(
            "x.txt", "https://example.com/x.txt", "text/plain", 0L);
        assertNull(path);
        assertEquals("handler must not run after dispose",
            0, invocations.get());
    }

    @Test
    public void isDisposed_defaultsFalse() {
        assertFalse(dispatcher.isDisposed());
    }

    // ---------------------------------------------------------------------
    // Event constructor — sanitisation and field coercion.
    // ---------------------------------------------------------------------

    @Test
    public void event_sanitisesPathSeparators() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "../../etc/passwd",
            "https://evil.example/x", "text/plain", 0L);
        assertFalse("filename must not contain /",
            e.suggestedFilename().contains("/"));
        assertFalse("filename must not contain \\",
            e.suggestedFilename().contains("\\"));
        assertFalse("filename must not start with .",
            e.suggestedFilename().startsWith("."));
    }

    @Test
    public void event_sanitisesBackslashes() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "..\\..\\windows\\system32",
            "https://evil.example/x", "text/plain", 0L);
        assertFalse(e.suggestedFilename().contains("\\"));
        assertFalse(e.suggestedFilename().contains("/"));
    }

    @Test
    public void event_substitutesDownloadForEmptyFilename() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "", "https://example.com/", "", 0L);
        assertEquals("download", e.suggestedFilename());
    }

    @Test
    public void event_substitutesDownloadForAllDotsFilename() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "...", "https://example.com/", "", 0L);
        assertEquals("download", e.suggestedFilename());
    }

    @Test
    public void event_coercesNullStringsToEmpty() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "x.txt", null, null, 42L);
        assertEquals("", e.sourceUrl());
        assertEquals("", e.mimeType());
    }

    @Test
    public void event_coercesNegativeTotalBytesToMinusOne() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "x.txt", "https://example.com/x.txt", "text/plain", -5L);
        assertEquals(-1L, e.totalBytes());
    }

    @Test
    public void event_preservesPositiveTotalBytes() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "x.txt", "https://example.com/x.txt", "text/plain", 4096L);
        assertEquals(4096L, e.totalBytes());
    }

    @Test
    public void event_preservesMinusOneAsUnknownSentinel() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "x.txt", "https://example.com/x.txt", "text/plain", -1L);
        assertEquals(-1L, e.totalBytes());
    }

    @Test(expected = NullPointerException.class)
    public void event_throwsOnNullSource() {
        new WebViewDownloadEvent(
            null, "x.txt", "https://example.com/x.txt", "text/plain", 0L);
    }

    @Test
    public void event_sourceAccessorReturnsConstructorArg() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "x.txt", "https://example.com/x.txt", "text/plain", 0L);
        assertSame(source, e.source());
    }

    // ---------------------------------------------------------------------
    // Default handler — ~/Downloads with de-duplication.
    // ---------------------------------------------------------------------

    @Test
    public void defaultHandler_returnsPathInsideDownloadsFolder() {
        WebViewDownloadEvent e = new WebViewDownloadEvent(
            source, "unique-test-" + System.nanoTime() + ".tmp",
            "https://example.com/x", "text/plain", 0L);
        File chosen = WebViewDownloadHandler.DEFAULT.downloadRequested(e);
        assertNotNull("default handler returned null in clean home", chosen);
        File expectedDir = new File(System.getProperty("user.home"),
            "Downloads");
        assertEquals(expectedDir.getAbsolutePath(),
            chosen.getParentFile().getAbsolutePath());
        assertEquals(e.suggestedFilename(), chosen.getName());
    }

    @Test
    public void defaultHandler_dedupesOnCollision() throws Exception {
        // Create a unique filename that doesn't yet exist, write it,
        // then ask the default handler for the same name — it should
        // hand back a "<stem> (1).<ext>" path.
        String base = "dispatcher-test-" + System.nanoTime();
        File dir = new File(System.getProperty("user.home"), "Downloads");
        if (!dir.exists() && !dir.mkdirs()) {
            // Home directory unwritable in this env -- skip rather than fail.
            return;
        }
        File first = new File(dir, base + ".tmp");
        try {
            assertTrue("setup: failed to create first file",
                first.createNewFile());
            WebViewDownloadEvent e = new WebViewDownloadEvent(
                source, base + ".tmp", "https://example.com/x",
                "text/plain", 0L);
            File chosen = WebViewDownloadHandler.DEFAULT.downloadRequested(e);
            assertNotNull(chosen);
            assertEquals(base + " (1).tmp", chosen.getName());
        } finally {
            first.delete();
        }
    }

    @Test
    public void defaultHandler_handlesFilenameWithoutExtension() throws Exception {
        String base = "no-ext-" + System.nanoTime();
        File dir = new File(System.getProperty("user.home"), "Downloads");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File first = new File(dir, base);
        try {
            assertTrue(first.createNewFile());
            WebViewDownloadEvent e = new WebViewDownloadEvent(
                source, base, "https://example.com/x", "", 0L);
            File chosen = WebViewDownloadHandler.DEFAULT.downloadRequested(e);
            assertNotNull(chosen);
            assertEquals(base + " (1)", chosen.getName());
        } finally {
            first.delete();
        }
    }
}
