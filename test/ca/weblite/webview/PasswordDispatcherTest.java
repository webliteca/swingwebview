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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PasswordDispatcher} — exercise the Java contract
 * (capture → save-policy → store, autofill push, enable/disable gating,
 * store/handler seams, EDT marshaling, exception isolation, redaction)
 * without a live native engine or the OS Keychain.  A
 * {@link StubComponent} captures the {@code eval} the fill path emits.
 */
public class PasswordDispatcherTest {

    /** Minimal {@link WebViewComponent} that records eval() calls. */
    private static final class StubComponent extends WebViewComponent {
        final BlockingQueue<String> evalCalls = new LinkedBlockingQueue<String>();
        @Override public WebViewComponent setUrl(String url) { return this; }
        @Override public String getUrl() { return ""; }
        @Override public WebViewComponent setDebug(boolean debug) { return this; }
        @Override public WebViewComponent addOnBeforeLoad(String js) { return this; }
        @Override public WebViewComponent eval(String js) {
            evalCalls.add(js);
            return this;
        }
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
    private PasswordDispatcher dispatcher;
    private InMemoryCredentialStore store;
    private Thread.UncaughtExceptionHandler priorHandler;
    private AtomicReference<Throwable> uncaught;

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Before public void setUp() {
        source = new StubComponent();
        dispatcher = new PasswordDispatcher(source);
        store = new InMemoryCredentialStore();
        dispatcher.setStore(store);
        uncaught = new AtomicReference<Throwable>();
        priorHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(
            new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable e) {
                    uncaught.set(e);
                }
            });
    }

    @After public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(priorHandler);
        dispatcher.disposeAll();
    }

    /** Flush the EDT so any invokeLater task has run. */
    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() { public void run() { } });
    }

    private static boolean awaitTrue(java.util.concurrent.Callable<Boolean> c)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(c.call())) return true;
            Thread.sleep(10);
        }
        return Boolean.TRUE.equals(c.call());
    }

    @Test public void saveDispositionStoresDecodedCredential() throws Exception {
        dispatcher.setHandler(new WebViewSavePasswordHandler() {
            @Override public SavePasswordDisposition onLoginSubmitted(
                    WebViewSavePasswordEvent e) {
                return SavePasswordDisposition.SAVE;
            }
        });
        dispatcher.dispatchLoginSubmitted("https://example.com/login",
            b64("alice"), b64("s3cret"));
        assertTrue(awaitTrue(new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return store.find("https://example.com").isPresent();
            }
        }));
        WebViewCredential c = store.find("https://example.com").get();
        assertEquals("alice", c.username());
        assertEquals("s3cret", c.password());
    }

    @Test public void dontSaveStoresNothing() throws Exception {
        dispatcher.setHandler(new WebViewSavePasswordHandler() {
            @Override public SavePasswordDisposition onLoginSubmitted(
                    WebViewSavePasswordEvent e) {
                return SavePasswordDisposition.DONT_SAVE;
            }
        });
        dispatcher.dispatchLoginSubmitted("https://example.com/login",
            b64("alice"), b64("s3cret"));
        flushEdt();
        Thread.sleep(50);
        assertFalse(store.find("https://example.com").isPresent());
    }

    @Test public void handlerRunsOnEdt() throws Exception {
        final AtomicBoolean onEdt = new AtomicBoolean(false);
        final Object done = new Object();
        final AtomicBoolean fired = new AtomicBoolean(false);
        dispatcher.setHandler(new WebViewSavePasswordHandler() {
            @Override public SavePasswordDisposition onLoginSubmitted(
                    WebViewSavePasswordEvent e) {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                synchronized (done) { fired.set(true); done.notifyAll(); }
                return SavePasswordDisposition.DONT_SAVE;
            }
        });
        dispatcher.dispatchLoginSubmitted("https://example.com", b64("u"), b64("p"));
        synchronized (done) {
            if (!fired.get()) done.wait(3000);
        }
        assertTrue("handler must run on the EDT", onEdt.get());
    }

    @Test public void disabledSuppressesAutomaticButKeepsProgrammaticApi()
            throws Exception {
        dispatcher.setEnabled(false);
        dispatcher.setHandler(new WebViewSavePasswordHandler() {
            @Override public SavePasswordDisposition onLoginSubmitted(
                    WebViewSavePasswordEvent e) {
                return SavePasswordDisposition.SAVE;
            }
        });
        dispatcher.dispatchLoginSubmitted("https://example.com", b64("a"), b64("p"));
        dispatcher.dispatchFillRequested("https://example.com");
        flushEdt();
        Thread.sleep(50);
        assertFalse(store.find("https://example.com").isPresent());
        assertTrue(source.evalCalls.isEmpty());
        // Programmatic API still works.
        dispatcher.saveCredential(
            new WebViewCredential("https://example.com", "bob", "pw"));
        assertEquals("bob", dispatcher.getCredential("https://example.com").get().username());
    }

    @Test public void handlerExceptionIsolatedAndStoresNothing() throws Exception {
        dispatcher.setHandler(new WebViewSavePasswordHandler() {
            @Override public SavePasswordDisposition onLoginSubmitted(
                    WebViewSavePasswordEvent e) {
                throw new RuntimeException("boom");
            }
        });
        // Must not throw out of dispatch.
        dispatcher.dispatchLoginSubmitted("https://example.com", b64("a"), b64("p"));
        flushEdt();
        Thread.sleep(50);
        assertFalse(store.find("https://example.com").isPresent());
        assertNotNull("exception should reach the uncaught handler", uncaught.get());
    }

    @Test public void nullStoreAndHandlerRestoreDefaults() {
        dispatcher.setStore(null);
        assertNotNull(dispatcher.getStore());
        assertTrue(dispatcher.getStore() instanceof NativeCredentialStore);
        dispatcher.setHandler(null);
        assertSame(WebViewSavePasswordHandler.DEFAULT, dispatcher.getHandler());
    }

    @Test public void fillPushesEncodedCredentialViaEval() throws Exception {
        store.save(new WebViewCredential("https://example.com", "alice", "s3cret"));
        dispatcher.dispatchFillRequested("https://example.com/login");
        String js = source.evalCalls.poll(3, TimeUnit.SECONDS);
        assertNotNull("fill should eval a __webview_pw_fill__ call", js);
        assertTrue(js.contains("__webview_pw_fill__"));
        // The password must be base64url-encoded in the eval, never the literal.
        assertFalse(js.contains("s3cret"));
        assertTrue(js.contains(b64("s3cret")));
    }

    @Test public void fillWithNoCredentialDoesNotEval() throws Exception {
        dispatcher.dispatchFillRequested("https://example.com/login");
        String js = source.evalCalls.poll(300, TimeUnit.MILLISECONDS);
        assertNull("no stored credential must not trigger eval", js);
    }

    @Test public void fillOnDifferentOriginIsExact() throws Exception {
        store.save(new WebViewCredential("https://example.com", "alice", "s3cret"));
        dispatcher.dispatchFillRequested("https://evil.com/login");
        String js = source.evalCalls.poll(300, TimeUnit.MILLISECONDS);
        assertNull("autofill must be origin-exact", js);
    }

    @Test public void unparseableFrameUrlDropped() throws Exception {
        store.save(new WebViewCredential("https://example.com", "alice", "s3cret"));
        dispatcher.dispatchFillRequested("about:blank");
        String js = source.evalCalls.poll(300, TimeUnit.MILLISECONDS);
        assertNull(js);
    }

    @Test public void eventToStringRedactsPassword() {
        WebViewSavePasswordEvent e = new WebViewSavePasswordEvent(
            source, "https://example.com", "alice", "s3cret");
        assertFalse(e.toString().contains("s3cret"));
        assertTrue(e.toString().contains("***"));
    }
}
