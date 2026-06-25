/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-Canvas-14 Definition of Done: exercises name registration (sync +
 * async), reserved-name / invalid-name rejection, inbound-envelope parsing,
 * that a synchronous handler runs OFF the calling thread, async-handler
 * resolve, handler-throw &rarr; reject, malformed-payload silent drop, and
 * dispose.  A mock {@link FunctionDispatcher.FunctionSink} stands in for the
 * native engine so the tests run without a live WebView peer.
 */
public class FunctionDispatcherTest {

    private static final String RESOLVE_PREFIX = "window.__webview_fn_resolve__('";

    /** Mock sink: records before-load scripts and queues every eval. */
    private static final class RecordingSink implements FunctionDispatcher.FunctionSink {
        final List<String> beforeLoad = new ArrayList<String>();
        final LinkedBlockingQueue<String> evals = new LinkedBlockingQueue<String>();

        @Override
        public synchronized void eval(String js) {
            evals.add(js);
        }

        @Override
        public synchronized void addOnBeforeLoad(String js) {
            beforeLoad.add(js);
        }

        /** Wait for the next resolve() eval and return its decoded
         *  {@code <id>|<ok>|<payload>} record, or fail on timeout. */
        String awaitResolve() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                String js = evals.poll(
                    Math.max(1, deadline - System.currentTimeMillis()),
                    TimeUnit.MILLISECONDS);
                if (js == null) break;
                if (js.startsWith(RESOLVE_PREFIX)) {
                    String b64 = js.substring(RESOLVE_PREFIX.length(),
                        js.length() - "')".length());
                    return new String(Base64.getDecoder().decode(b64),
                        StandardCharsets.UTF_8);
                }
            }
            fail("no resolve() eval observed within timeout");
            return null;
        }
    }

    /** Build the native-bind envelope the inbound binding callback receives:
     *  base64 of {@code <id>|<name>|<arg>} wrapped as
     *  {@code {"name":"__webview_fn_call__","seq":0,"args":["<b64>"]}}. */
    private static String envelope(long id, String name, String arg) {
        String pipe = id + "|" + name + "|" + arg;
        String b64 = Base64.getEncoder().encodeToString(
            pipe.getBytes(StandardCharsets.UTF_8));
        return "{\"name\":\"__webview_fn_call__\",\"seq\":0,\"args\":[\"" + b64 + "\"]}";
    }

    @Test
    public void registerSync_installsWrapperForCurrentAndFuture() {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        d.registerSync("greet", new JavascriptFunction() {
            @Override public String run(String arg) { return "hi " + arg; }
        });
        // addOnBeforeLoad (future navigations) + a one-shot eval (current doc).
        assertEquals(1, sink.beforeLoad.size());
        assertTrue(sink.beforeLoad.get(0).contains("window[\"greet\"]"));
        assertTrue(sink.evals.contains(sink.beforeLoad.get(0)));
    }

    @Test
    public void syncHandler_resolvesWithReturnValue_offCallingThread() throws Exception {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        final AtomicReference<String> handlerThread = new AtomicReference<String>();
        d.registerSync("rev", new JavascriptFunction() {
            @Override public String run(String arg) {
                handlerThread.set(Thread.currentThread().getName());
                return new StringBuilder(arg).reverse().toString();
            }
        });
        String dispatchThread = Thread.currentThread().getName();
        d.dispatch(envelope(7, "rev", "abc"));
        assertEquals("7|1|cba", sink.awaitResolve());
        assertNotEquals("sync handler must run off the calling thread",
            dispatchThread, handlerThread.get());
        assertTrue(handlerThread.get().startsWith("webview-fn-"));
    }

    @Test
    public void syncHandler_argWithPipes_isPreservedVerbatim() throws Exception {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        d.registerSync("echo", new JavascriptFunction() {
            @Override public String run(String arg) { return arg; }
        });
        d.dispatch(envelope(1, "echo", "a|b|c"));
        assertEquals("1|1|a|b|c", sink.awaitResolve());
    }

    @Test
    public void syncHandler_throw_rejectsWithMessage() throws Exception {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        d.registerSync("boom", new JavascriptFunction() {
            @Override public String run(String arg) throws Exception {
                throw new IllegalStateException("kaboom");
            }
        });
        d.dispatch(envelope(3, "boom", "x"));
        assertEquals("3|0|kaboom", sink.awaitResolve());
    }

    @Test
    public void asyncHandler_resolvesWhenFutureCompletes() throws Exception {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        final CompletableFuture<String> gate = new CompletableFuture<String>();
        d.registerAsync("later", new AsyncJavascriptFunction() {
            @Override public CompletableFuture<String> run(String arg) {
                return gate;
            }
        });
        d.dispatch(envelope(9, "later", "ignored"));
        gate.complete("done");
        assertEquals("9|1|done", sink.awaitResolve());
    }

    @Test
    public void asyncHandler_exceptionalFuture_rejects() throws Exception {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        d.registerAsync("fail", new AsyncJavascriptFunction() {
            @Override public CompletableFuture<String> run(String arg) {
                CompletableFuture<String> f = new CompletableFuture<String>();
                f.completeExceptionally(new RuntimeException("nope"));
                return f;
            }
        });
        d.dispatch(envelope(2, "fail", "x"));
        assertEquals("2|0|nope", sink.awaitResolve());
    }

    @Test
    public void unknownFunction_rejects() throws Exception {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        d.dispatch(envelope(5, "missing", "x"));
        assertEquals("5|0|no such function: missing", sink.awaitResolve());
    }

    @Test
    public void reservedAndInvalidNames_rejected() {
        FunctionDispatcher d = new FunctionDispatcher(new RecordingSink(), "Test");
        JavascriptFunction noop = new JavascriptFunction() {
            @Override public String run(String arg) { return ""; }
        };
        try {
            d.registerSync("__webview_evil", noop);
            fail("expected IllegalArgumentException for reserved prefix");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            d.registerSync("has space", noop);
            fail("expected IllegalArgumentException for invalid identifier");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            d.registerSync(null, noop);
            fail("expected NullPointerException for null name");
        } catch (NullPointerException expected) { /* ok */ }
    }

    @Test
    public void malformedPayloads_silentlyDropped() {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        d.dispatch(null);
        d.dispatch("not json");
        d.dispatch("{\"args\":[\"%%%not-base64%%%\"]}");
        // No resolve eval should have been emitted.
        assertTrue("malformed input must not emit a resolve", sink.evals.isEmpty());
    }

    @Test
    public void dispose_isIdempotent_andStopsRegistration() {
        RecordingSink sink = new RecordingSink();
        FunctionDispatcher d = new FunctionDispatcher(sink, "Test");
        d.disposeAll();
        d.disposeAll(); // idempotent, no throw
        d.registerSync("late", new JavascriptFunction() {
            @Override public String run(String arg) { return "x"; }
        });
        // Registration after dispose is a no-op: no wrapper installed.
        assertTrue(sink.beforeLoad.isEmpty());
    }
}
