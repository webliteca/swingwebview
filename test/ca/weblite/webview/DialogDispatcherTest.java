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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Unit tests for {@link DialogDispatcher} — exercise the Java contract
 * (handler registration, EDT marshaling, drop semantics, exception
 * isolation, accept-attribute normalisation, dispose semantics) without
 * a live native engine.  GUI integration is verified by
 * {@code WebViewDialogDemo}; this file covers the dispatcher's logic.
 */
public class DialogDispatcherTest {

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
    private DialogDispatcher dispatcher;
    private Thread.UncaughtExceptionHandler priorHandler;
    private AtomicReference<Throwable> uncaught;

    @Before
    public void setUp() {
        source = new StubComponent();
        dispatcher = new DialogDispatcher(source);
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
        assertSame(WebViewDialogHandler.DEFAULT, dispatcher.getHandler());
    }

    @Test
    public void testGetHandlerNeverReturnsNull() {
        assertNotNull(dispatcher.getHandler());
        dispatcher.setHandler(null);
        assertNotNull(dispatcher.getHandler());
        dispatcher.setHandler(WebViewDialogHandler.DEFAULT);
        assertNotNull(dispatcher.getHandler());
    }

    @Test
    public void testSetHandlerNullInstallsDrop() {
        dispatcher.setHandler(null);
        WebViewDialogHandler h = dispatcher.getHandler();
        assertNotNull(h);
        assertNotSame("DROP must not be identity-equal to DEFAULT",
            WebViewDialogHandler.DEFAULT, h);
    }

    @Test
    public void testSetHandlerCustomIsReturnedByGetter() {
        WebViewDialogHandler custom = new WebViewDialogHandler() {};
        dispatcher.setHandler(custom);
        assertSame(custom, dispatcher.getHandler());
    }

    @Test
    public void testSetHandlerDefaultAfterNullResets() {
        dispatcher.setHandler(null);
        assertNotSame(WebViewDialogHandler.DEFAULT, dispatcher.getHandler());
        dispatcher.setHandler(WebViewDialogHandler.DEFAULT);
        assertSame(WebViewDialogHandler.DEFAULT, dispatcher.getHandler());
    }

    // -----------------------------------------------------------------
    // Drop-handler semantics (setHandler(null)).
    // -----------------------------------------------------------------

    @Test
    public void testDropAlertIsNoOp() {
        dispatcher.setHandler(null);
        // Should return normally without showing anything.
        dispatcher.dispatchAlert("ping", "u", "f");
    }

    @Test
    public void testDropConfirmReturnsFalse() {
        dispatcher.setHandler(null);
        assertFalse(dispatcher.dispatchConfirm("?", "u", "f"));
    }

    @Test
    public void testDropPromptReturnsNull() {
        dispatcher.setHandler(null);
        assertNull(dispatcher.dispatchPrompt("?", "x", "u", "f"));
    }

    @Test
    public void testDropFilePickerReturnsEmpty() {
        dispatcher.setHandler(null);
        String[] r = dispatcher.dispatchFilePicker(
            false, null, null, "u", "f");
        assertNotNull(r);
        assertEquals(0, r.length);
    }

    // -----------------------------------------------------------------
    // Custom handler invocation and event-field plumbing.
    // -----------------------------------------------------------------

    @Test
    public void testDispatchAlertInvokesHandlerOnEdt() {
        final AtomicReference<Boolean> onEdt = new AtomicReference<Boolean>();
        final AtomicReference<WebViewAlertEvent> received =
            new AtomicReference<WebViewAlertEvent>();
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public void alertOpened(WebViewAlertEvent e) {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                received.set(e);
            }
        });
        dispatcher.dispatchAlert("msg", "http://a", "http://b");
        assertEquals(Boolean.TRUE, onEdt.get());
        assertNotNull(received.get());
        assertEquals("msg", received.get().message());
        assertEquals("http://a", received.get().pageUrl());
        assertEquals("http://b", received.get().frameUrl());
        assertSame(source, received.get().source());
    }

    @Test
    public void testDispatchConfirmReturnsHandlerValue() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public boolean confirmOpened(WebViewConfirmEvent e) { return true; }
        });
        assertTrue(dispatcher.dispatchConfirm("?", "u", "f"));
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public boolean confirmOpened(WebViewConfirmEvent e) { return false; }
        });
        assertFalse(dispatcher.dispatchConfirm("?", "u", "f"));
    }

    @Test
    public void testDispatchPromptReturnsHandlerValue() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public String promptOpened(WebViewPromptEvent e) {
                return "answer:" + e.defaultValue();
            }
        });
        assertEquals("answer:x",
            dispatcher.dispatchPrompt("msg", "x", "u", "f"));
    }

    @Test
    public void testDispatchPromptHandlerReturningNullReturnsNull() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public String promptOpened(WebViewPromptEvent e) { return null; }
        });
        assertNull(dispatcher.dispatchPrompt("?", "", "u", "f"));
    }

    @Test
    public void testDispatchFilePickerReturnsHandlerPaths() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                return Arrays.asList(
                    new File("/tmp/a.png"), new File("/tmp/b.png"));
            }
        });
        String[] r = dispatcher.dispatchFilePicker(
            true, new String[0], new String[]{"png"}, "u", "f");
        assertArrayEquals(
            new String[]{"/tmp/a.png", "/tmp/b.png"}, r);
    }

    @Test
    public void testDispatchFilePickerHandlerEmptyReturnsEmpty() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                return Collections.emptyList();
            }
        });
        String[] r = dispatcher.dispatchFilePicker(
            false, null, null, "u", "f");
        assertNotNull(r);
        assertEquals(0, r.length);
    }

    @Test
    public void testDispatchFilePickerHandlerNullReturnsEmpty() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                return null;
            }
        });
        String[] r = dispatcher.dispatchFilePicker(
            false, null, null, "u", "f");
        assertNotNull(r);
        assertEquals(0, r.length);
    }

    // -----------------------------------------------------------------
    // Accept-attribute normalisation.
    // -----------------------------------------------------------------

    @Test
    public void testNormaliseExtensionsLowercaseStripDotDedupe() {
        final AtomicReference<List<String>> seen =
            new AtomicReference<List<String>>();
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                seen.set(new ArrayList<String>(e.acceptedExtensions()));
                return Collections.emptyList();
            }
        });
        dispatcher.dispatchFilePicker(false, null,
            new String[]{".PNG", "JPG", ".png", "", null, "JPEG"},
            "u", "f");
        assertEquals(Arrays.asList("png", "jpg", "jpeg"), seen.get());
    }

    @Test
    public void testNormaliseMimeTypesLowercaseDedupe() {
        final AtomicReference<List<String>> seen =
            new AtomicReference<List<String>>();
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                seen.set(new ArrayList<String>(e.acceptedMimeTypes()));
                return Collections.emptyList();
            }
        });
        dispatcher.dispatchFilePicker(true,
            new String[]{"Image/PNG", "image/png", "image/*"},
            null, "u", "f");
        assertEquals(Arrays.asList("image/png", "image/*"), seen.get());
    }

    @Test
    public void testNormaliseAcceptHandlesNullArrays() {
        final AtomicReference<WebViewFilePickerEvent> seen =
            new AtomicReference<WebViewFilePickerEvent>();
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                seen.set(e);
                return Collections.emptyList();
            }
        });
        dispatcher.dispatchFilePicker(false, null, null, "u", "f");
        assertNotNull(seen.get());
        assertTrue(seen.get().acceptedExtensions().isEmpty());
        assertTrue(seen.get().acceptedMimeTypes().isEmpty());
    }

    // -----------------------------------------------------------------
    // Exception isolation.
    // -----------------------------------------------------------------

    @Test
    public void testHandlerExceptionAlertIsolated() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public void alertOpened(WebViewAlertEvent e) {
                throw new RuntimeException("boom-alert");
            }
        });
        dispatcher.dispatchAlert("x", "u", "f"); // must not throw
        waitForUncaught();
        assertNotNull("uncaught handler should have received the exception",
            uncaught.get());
        assertEquals("boom-alert", uncaught.get().getMessage());
    }

    @Test
    public void testHandlerExceptionConfirmReturnsFalse() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public boolean confirmOpened(WebViewConfirmEvent e) {
                throw new RuntimeException("boom-confirm");
            }
        });
        assertFalse(dispatcher.dispatchConfirm("?", "u", "f"));
        waitForUncaught();
        assertNotNull(uncaught.get());
    }

    @Test
    public void testHandlerExceptionPromptReturnsNull() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public String promptOpened(WebViewPromptEvent e) {
                throw new RuntimeException("boom-prompt");
            }
        });
        assertNull(dispatcher.dispatchPrompt("?", "", "u", "f"));
        waitForUncaught();
        assertNotNull(uncaught.get());
    }

    @Test
    public void testHandlerExceptionFilePickerReturnsEmpty() {
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                throw new RuntimeException("boom-file");
            }
        });
        String[] r = dispatcher.dispatchFilePicker(
            false, null, null, "u", "f");
        assertNotNull(r);
        assertEquals(0, r.length);
        waitForUncaught();
        assertNotNull(uncaught.get());
    }

    // -----------------------------------------------------------------
    // Disposal semantics.
    // -----------------------------------------------------------------

    @Test
    public void testDisposedDispatcherReturnsFallbacks() {
        // Install a handler that would throw if invoked — the dispatcher
        // must NOT invoke it after dispose.
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override public void alertOpened(WebViewAlertEvent e) {
                throw new AssertionError("handler invoked after dispose");
            }
            @Override public boolean confirmOpened(WebViewConfirmEvent e) {
                throw new AssertionError("handler invoked after dispose");
            }
            @Override public String promptOpened(WebViewPromptEvent e) {
                throw new AssertionError("handler invoked after dispose");
            }
            @Override public List<File> filePickerOpened(WebViewFilePickerEvent e) {
                throw new AssertionError("handler invoked after dispose");
            }
        });
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());
        dispatcher.dispatchAlert("x", "u", "f");
        assertFalse(dispatcher.dispatchConfirm("?", "u", "f"));
        assertNull(dispatcher.dispatchPrompt("?", "", "u", "f"));
        String[] r = dispatcher.dispatchFilePicker(false, null, null, "u", "f");
        assertNotNull(r);
        assertEquals(0, r.length);
    }

    @Test
    public void testDisposeAllIsIdempotent() {
        dispatcher.disposeAll();
        dispatcher.disposeAll();
        assertTrue(dispatcher.isDisposed());
    }

    // -----------------------------------------------------------------
    // EDT short-circuit.
    // -----------------------------------------------------------------

    @Test
    public void testDispatchFromEdtShortCircuits() throws Exception {
        final AtomicReference<Boolean> onEdt = new AtomicReference<Boolean>();
        dispatcher.setHandler(new WebViewDialogHandler() {
            @Override
            public boolean confirmOpened(WebViewConfirmEvent e) {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                return true;
            }
        });
        // Dispatch from the EDT itself — must not deadlock and must
        // still report EDT execution.
        final boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                result[0] = dispatcher.dispatchConfirm("?", "u", "f");
            }
        });
        assertTrue(result[0]);
        assertEquals(Boolean.TRUE, onEdt.get());
    }

    // -----------------------------------------------------------------
    // Constructor null-checks.
    // -----------------------------------------------------------------

    @Test(expected = NullPointerException.class)
    public void testConstructorRejectsNullSource() {
        new DialogDispatcher(null);
    }

    // -----------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------

    /** Spin briefly waiting for the uncaught-exception handler to be
     *  invoked.  The dispatcher's exception forwarding is synchronous
     *  from the EDT path, so this should always observe the exception
     *  almost immediately; we still allow a tiny grace window in case
     *  a JVM schedules the uncaught-handler asynchronously. */
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
