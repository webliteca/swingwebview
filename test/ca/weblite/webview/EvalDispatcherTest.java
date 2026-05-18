/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Per-Canvas-10 Definition of Done: exercises the in-flight map's id
 * allocation, dispose draining, malformed-payload silent drop, and the
 * {@code marshalToEdt} toggle.  A mock {@link EvalDispatcher.EvalSink}
 * stands in for the native engine so the tests run without a live
 * WebView peer.
 */
public class EvalDispatcherTest {

    /** Mock sink that records the last wrapped snippet passed in. */
    private static final class RecordingSink implements EvalDispatcher.EvalSink {
        volatile String lastJs;
        @Override
        public void eval(String js) {
            this.lastJs = js;
        }
    }

    /** Mock sink whose {@code eval} throws synchronously. */
    private static final class ThrowingSink implements EvalDispatcher.EvalSink {
        @Override
        public void eval(String js) {
            throw new IllegalStateException("sink boom");
        }
    }

    /** Mock sink that ignores every call. */
    private static final EvalDispatcher.EvalSink NOOP_SINK = new EvalDispatcher.EvalSink() {
        @Override
        public void eval(String js) {
            // intentionally empty
        }
    };

    /**
     * Build a native-bind envelope identical to what the {@code SHIM_JS}
     * posts back through the resolver binding: the JS shim base64-encodes
     * a pipe-separated {@code <id>|<okFlag>|<payload>} record and the
     * embed/offscreen bind layer wraps the call as
     * {@code {"name":"...","seq":...,"args":["<b64>"]}}.
     */
    private static String envelope(long id, boolean ok, String payload) {
        String pipe = id + "|" + (ok ? "1" : "0") + "|" + payload;
        String b64 = Base64.getEncoder().encodeToString(pipe.getBytes(StandardCharsets.UTF_8));
        return "{\"name\":\"" + EvalDispatcher.CHANNEL_NAME
            + "\",\"seq\":\"0\",\"args\":[\"" + b64 + "\"]}";
    }

    /**
     * Extract the request id the wrapper template embedded into the
     * wrapped JS snippet.  The template includes a literal
     * {@code var __id=<N>;} at the top of the IIFE.
     */
    private static long extractId(String wrapped) {
        assertNotNull("wrapped JS must not be null", wrapped);
        int s = wrapped.indexOf("var __id=");
        assertTrue("expected 'var __id=' in wrapped JS, was: " + wrapped, s >= 0);
        s += "var __id=".length();
        int e = wrapped.indexOf(';', s);
        assertTrue("missing terminator after __id literal", e > s);
        return Long.parseLong(wrapped.substring(s, e));
    }

    // ---------------------------------------------------------------
    // Constructor input validation
    // ---------------------------------------------------------------

    @Test(expected = NullPointerException.class)
    public void constructor_rejectsNullSink() {
        new EvalDispatcher(null, false, "label");
    }

    @Test(expected = NullPointerException.class)
    public void constructor_rejectsNullDisposeLabel() {
        new EvalDispatcher(NOOP_SINK, false, null);
    }

    @Test
    public void constants_areStable() {
        // The reserved binding name is part of the public wire contract
        // between the JS shim and the Java side — guard against accidental
        // rename.
        assertEquals("__webview_eval_result__", EvalDispatcher.CHANNEL_NAME);
        assertTrue("SHIM_JS must define the idempotency installer flag",
            EvalDispatcher.SHIM_JS.contains("__webview_eval_installed__"));
        assertTrue("SHIM_JS must export the post helper",
            EvalDispatcher.SHIM_JS.contains("__webview_eval_post"));
    }

    // ---------------------------------------------------------------
    // evalAsync input validation
    // ---------------------------------------------------------------

    @Test(expected = NullPointerException.class)
    public void evalAsync_rejectsNullSnippet() {
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.evalAsync(null);
    }

    // ---------------------------------------------------------------
    // Id allocation
    // ---------------------------------------------------------------

    @Test
    public void evalAsync_idsAreMonotonicAndStartFromOne() {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");

        d.evalAsync("return 1;");
        long first = extractId(sink.lastJs);
        d.evalAsync("return 2;");
        long second = extractId(sink.lastJs);
        d.evalAsync("return 3;");
        long third = extractId(sink.lastJs);

        assertEquals("first id must be 1 (AtomicLong starts at 0, incrementAndGet)", 1L, first);
        assertEquals(2L, second);
        assertEquals(3L, third);
    }

    @Test
    public void evalAsync_idsAreUniqueAcrossManyCalls() {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");
        Set<Long> seen = new HashSet<Long>();
        for (int i = 0; i < 200; i++) {
            d.evalAsync("return " + i + ";");
            assertTrue("id must be unique", seen.add(extractId(sink.lastJs)));
        }
        assertEquals(200, seen.size());
    }

    @Test
    public void evalAsync_wrappedSnippetEmbedsUserJs() {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");
        d.evalAsync("return 42;");
        assertNotNull(sink.lastJs);
        assertTrue("wrapped output must contain user snippet verbatim",
            sink.lastJs.contains("return 42;"));
        assertTrue("wrapped output must cache the sink before user JS runs",
            sink.lastJs.contains("var __sink_post=window.__webview_eval_post;"));
    }

    // ---------------------------------------------------------------
    // Successful dispatch resolves the future
    // ---------------------------------------------------------------

    @Test
    public void dispatch_completesFutureWithJsonStringifiedValue() throws Exception {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");
        CompletableFuture<String> f = d.evalAsync("return 1+2;");
        long id = extractId(sink.lastJs);
        d.dispatch(envelope(id, true, "3"));
        assertEquals("3", f.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void dispatch_completesFutureExceptionallyWithJavaScriptEvalException() {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");
        CompletableFuture<String> f = d.evalAsync("foo.bar();");
        long id = extractId(sink.lastJs);
        d.dispatch(envelope(id, false, "foo is not defined"));

        assertTrue(f.isCompletedExceptionally());
        try {
            f.get(1, TimeUnit.SECONDS);
            fail("expected ExecutionException");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            assertTrue("cause must be JavaScriptEvalException, was: " + cause,
                cause instanceof JavaScriptEvalException);
            assertEquals("foo is not defined", cause.getMessage());
        } catch (Exception other) {
            fail("expected ExecutionException, got " + other);
        }
    }

    @Test
    public void dispatch_payloadWithPipesPreservesEntireRestField() throws Exception {
        // Pipes inside the JSON-stringified value must survive the parser
        // (the parser only splits at the first two pipes).
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");
        CompletableFuture<String> f = d.evalAsync("return 'has|pipes|in|value';");
        long id = extractId(sink.lastJs);
        d.dispatch(envelope(id, true, "\"has|pipes|in|value\""));
        assertEquals("\"has|pipes|in|value\"", f.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void dispatch_secondCallWithSameIdIsSilentlyDropped() throws Exception {
        // Indirect proof that successful dispatch removes the entry from
        // the pending map: a second dispatch with the same id finds no
        // entry and must not throw or overwrite the already-resolved
        // future.
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");
        CompletableFuture<String> f = d.evalAsync("return 1;");
        long id = extractId(sink.lastJs);
        d.dispatch(envelope(id, true, "1"));
        assertEquals("1", f.get(1, TimeUnit.SECONDS));

        // Second dispatch with the same id: silent drop.
        d.dispatch(envelope(id, true, "999"));
        // Resolved value is unchanged.
        assertEquals("1", f.getNow("nope"));
    }

    // ---------------------------------------------------------------
    // Malformed-payload silent drop
    // ---------------------------------------------------------------

    @Test
    public void dispatch_dropsNullPayload() {
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.dispatch(null);  // must not throw
    }

    @Test
    public void dispatch_dropsEmptyAndUnrecognizedPayloads() {
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.dispatch("");
        d.dispatch("{}");
        d.dispatch("not json at all");
        d.dispatch("{\"name\":\"x\"}");                       // no args
        d.dispatch("{\"args\":[]}");                          // empty args
        d.dispatch("{\"args\":[123]}");                       // non-string arg
    }

    @Test
    public void dispatch_dropsMalformedBase64() {
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.dispatch("{\"name\":\"x\",\"seq\":\"0\",\"args\":[\"@@@not-base64@@@\"]}");
    }

    @Test
    public void dispatch_dropsPayloadWithoutPipes() {
        String b64 = Base64.getEncoder().encodeToString("no-pipes-here".getBytes(StandardCharsets.UTF_8));
        String env = "{\"name\":\"x\",\"seq\":\"0\",\"args\":[\"" + b64 + "\"]}";
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.dispatch(env);  // must not throw
    }

    @Test
    public void dispatch_dropsPayloadWithOnlyOnePipe() {
        String b64 = Base64.getEncoder().encodeToString("1|1".getBytes(StandardCharsets.UTF_8));
        String env = "{\"name\":\"x\",\"seq\":\"0\",\"args\":[\"" + b64 + "\"]}";
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.dispatch(env);  // must not throw (missing payload separator)
    }

    @Test
    public void dispatch_dropsPayloadWithNonNumericId() {
        String b64 = Base64.getEncoder().encodeToString("abc|1|val".getBytes(StandardCharsets.UTF_8));
        String env = "{\"name\":\"x\",\"seq\":\"0\",\"args\":[\"" + b64 + "\"]}";
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.dispatch(env);  // must not throw
    }

    @Test
    public void dispatch_dropsUnknownId() {
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.dispatch(envelope(999L, true, "value"));  // no pending future for 999
    }

    @Test
    public void dispatch_unknownOkFlagTreatedAsError() {
        // The parser is defensive: anything other than the exact string
        // "1" routes to the error path.
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "T");
        CompletableFuture<String> f = d.evalAsync("return 1;");
        long id = extractId(sink.lastJs);
        // Craft an envelope with okFlag = "2" (neither "1" nor "0").
        String pipe = id + "|2|some error";
        String b64 = Base64.getEncoder().encodeToString(pipe.getBytes(StandardCharsets.UTF_8));
        String env = "{\"name\":\"x\",\"seq\":\"0\",\"args\":[\"" + b64 + "\"]}";
        d.dispatch(env);
        assertTrue(f.isCompletedExceptionally());
        try {
            f.getNow(null);
            fail();
        } catch (CompletionException ce) {
            assertTrue(ce.getCause() instanceof JavaScriptEvalException);
            assertEquals("some error", ce.getCause().getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Dispose drain
    // ---------------------------------------------------------------

    @Test
    public void disposeAllPending_completesEveryPendingFutureExceptionally() {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "MyEngine");
        CompletableFuture<String> f1 = d.evalAsync("return 1;");
        CompletableFuture<String> f2 = d.evalAsync("return 2;");
        CompletableFuture<String> f3 = d.evalAsync("return 3;");

        assertFalse(f1.isDone());
        assertFalse(f2.isDone());
        assertFalse(f3.isDone());

        d.disposeAllPending();

        for (CompletableFuture<String> f : new CompletableFuture[]{f1, f2, f3}) {
            assertTrue(f.isCompletedExceptionally());
            try {
                f.getNow(null);
                fail("expected exceptional completion");
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                assertTrue("cause must be IllegalStateException, was " + cause,
                    cause instanceof IllegalStateException);
                assertEquals("MyEngine disposed", cause.getMessage());
            }
        }
    }

    @Test
    public void disposeAllPending_isIdempotent() {
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, false, "T");
        d.disposeAllPending();
        d.disposeAllPending();  // must not throw or re-process
        d.disposeAllPending();
    }

    @Test
    public void evalAsync_afterDisposeReturnsAlreadyFailedFuture() {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, false, "Eng");
        d.disposeAllPending();

        CompletableFuture<String> f = d.evalAsync("return 1;");
        assertTrue(f.isDone());
        assertTrue(f.isCompletedExceptionally());
        try {
            f.getNow(null);
            fail();
        } catch (CompletionException ce) {
            assertTrue(ce.getCause() instanceof IllegalStateException);
            assertEquals("Eng disposed", ce.getCause().getMessage());
        }
        assertEquals("sink must NOT have been invoked after dispose",
            null, sink.lastJs);
    }

    @Test
    public void evalAsync_afterDisposeUsesDisposeLabelInMessage() {
        EvalDispatcher d1 = new EvalDispatcher(NOOP_SINK, false, "WebView");
        EvalDispatcher d2 = new EvalDispatcher(NOOP_SINK, false, "EmbeddedWebView");
        EvalDispatcher d3 = new EvalDispatcher(NOOP_SINK, false, "OffscreenWebView");
        d1.disposeAllPending();
        d2.disposeAllPending();
        d3.disposeAllPending();
        for (Object[] row : new Object[][]{
                {d1, "WebView disposed"},
                {d2, "EmbeddedWebView disposed"},
                {d3, "OffscreenWebView disposed"}}) {
            CompletableFuture<String> f = ((EvalDispatcher) row[0]).evalAsync("x");
            try {
                f.getNow(null);
                fail();
            } catch (CompletionException ce) {
                assertEquals(row[1], ce.getCause().getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // Sink throwing surfaces as exceptional future
    // ---------------------------------------------------------------

    @Test
    public void evalAsync_sinkSyncThrowSurfacesAsExceptionalFuture() {
        EvalDispatcher d = new EvalDispatcher(new ThrowingSink(), false, "T");
        CompletableFuture<String> f = d.evalAsync("return 1;");
        assertTrue("future must be completed exceptionally when sink throws",
            f.isCompletedExceptionally());
        try {
            f.getNow(null);
            fail();
        } catch (CompletionException ce) {
            assertTrue(ce.getCause() instanceof IllegalStateException);
            assertEquals("sink boom", ce.getCause().getMessage());
        }
    }

    // ---------------------------------------------------------------
    // marshalToEdt toggle
    // ---------------------------------------------------------------

    @Test
    public void inlineMode_continuationsRunOnDispatchThread() throws Exception {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, /* marshalToEdt */ false, "T");
        CompletableFuture<String> f = d.evalAsync("return 1;");
        long id = extractId(sink.lastJs);

        final AtomicReference<String> completedOn = new AtomicReference<String>();
        CompletableFuture<Void> done = f.thenAccept(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String s) {
                completedOn.set(Thread.currentThread().getName());
            }
        });

        Thread dispatchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                d.dispatch(envelope(id, true, "1"));
            }
        }, "test-dispatch-thread");
        dispatchThread.start();
        done.get(2, TimeUnit.SECONDS);

        assertEquals("inline mode must complete future on the dispatch thread",
            "test-dispatch-thread", completedOn.get());
        assertNotEquals("inline mode must NOT hop to the EDT",
            "AWT-EventQueue", completedOn.get());
    }

    @Test
    public void edtMode_continuationsRunOnSwingEdt() throws Exception {
        RecordingSink sink = new RecordingSink();
        EvalDispatcher d = new EvalDispatcher(sink, /* marshalToEdt */ true, "T");
        CompletableFuture<String> f = d.evalAsync("return 1;");
        long id = extractId(sink.lastJs);

        final AtomicReference<Boolean> wasOnEdt = new AtomicReference<Boolean>();
        CompletableFuture<Void> done = f.thenAccept(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String s) {
                wasOnEdt.set(SwingUtilities.isEventDispatchThread());
            }
        });

        Thread dispatchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                d.dispatch(envelope(id, true, "1"));
            }
        }, "test-dispatch-thread");
        dispatchThread.start();
        done.get(2, TimeUnit.SECONDS);

        assertEquals(Boolean.TRUE, wasOnEdt.get());
    }

    @Test
    public void disposeAllPending_completionsAreInlineEvenWhenMarshalToEdtIsTrue() throws Exception {
        // Per Canvas 10 Operation §5: dispose completions are NOT
        // marshalled to the EDT even when marshalToEdt = true, to keep
        // the dispose path deterministic when dispose itself runs from
        // the EDT.  We assert this by triggering dispose from a regular
        // worker thread and checking the future's exceptional
        // completion is observable immediately (not deferred to an
        // invokeLater cycle).
        EvalDispatcher d = new EvalDispatcher(NOOP_SINK, true, "T");
        CompletableFuture<String> f = d.evalAsync("return 1;");
        assertFalse(f.isDone());
        d.disposeAllPending();
        // Without an EDT hop the future is observably done synchronously.
        assertTrue("dispose completion must be inline", f.isDone());
        assertTrue(f.isCompletedExceptionally());
    }
}
