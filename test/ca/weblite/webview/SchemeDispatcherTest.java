package ca.weblite.webview;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Canvas 30 D5–D11: the dispatcher — answered exactly once, off the calling thread, with 500, 504,
 * 404 and 413 for the failure cases, and D11's headers (story 8 AC3, AC5, AC6, AC7 and the
 * concurrency expectation).
 */
public class SchemeDispatcherTest {

    /** One answer the sink received. */
    static final class Answer {
        final long id;
        final int status;
        final String[] headers;
        final byte[] body;

        Answer(long id, int status, String[] headers, byte[] body) {
            this.id = id;
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                if (headers[i].equalsIgnoreCase(name)) return headers[i + 1];
            }
            return null;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final class Recorder implements SchemeDispatcher.Sink {
        final List<Answer> answers = Collections.synchronizedList(new ArrayList<Answer>());
        private final CountDownLatch latch;

        Recorder(int expected) {
            latch = new CountDownLatch(expected);
        }

        @Override
        public void respond(long id, int status, String[] headerPairs, byte[] body) {
            answers.add(new Answer(id, status, headerPairs, body));
            latch.countDown();
        }

        Answer await() throws InterruptedException {
            assertTrue("no answer arrived", latch.await(5, TimeUnit.SECONDS));
            return answers.get(0);
        }
    }

    private static SchemeDispatcher dispatcher(Recorder sink, String scheme, WebViewSchemeHandler h) {
        Map<String, WebViewSchemeHandler> m = new HashMap<String, WebViewSchemeHandler>();
        m.put(scheme, h);
        return new SchemeDispatcher(m, sink);
    }

    private static void get(SchemeDispatcher d, long id, String url) {
        d.onSchemeRequest(id, "GET", url, new String[0], new byte[0], true);
    }

    private static final WebViewSchemeHandler HELLO = new WebViewSchemeHandler() {
        @Override
        public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
            responder.respond(WebViewSchemeResponse.ok("text/html", "<h1>Hello</h1>".getBytes(StandardCharsets.UTF_8))
                    .withHeader("Content-Length", "999"));
        }
    };

    @Test
    public void anAnswerReachesTheSinkOnceWithD11Headers() throws Exception {
        Recorder sink = new Recorder(1);
        SchemeDispatcher d = dispatcher(sink, "demo", HELLO);
        get(d, 1, "demo://app/index.html");
        Answer a = sink.await();
        Thread.sleep(50);
        assertEquals(1, sink.answers.size());
        assertEquals(1, a.id);
        assertEquals(200, a.status);
        assertEquals("text/html", a.header("Content-Type"));
        assertEquals("demo://app", a.header("Access-Control-Allow-Origin"));
        assertNull(a.header("Content-Length"));
        assertEquals("<h1>Hello</h1>", a.text());
        assertEquals(0, d.pendingCount());
    }

    @Test
    public void aDefaultContentTypeIsAdded() throws Exception {
        Recorder sink = new Recorder(1);
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                responder.respond(WebViewSchemeResponse.of(204, null, new byte[0]));
            }
        });
        get(d, 1, "demo://app/x");
        assertEquals("application/octet-stream", sink.await().header("Content-Type"));
    }

    @Test
    public void aLateAnswerFromAnotherThreadArrives() throws Exception {             // AC5
        Recorder sink = new Recorder(1);
        final AtomicReference<Thread> handlerThread = new AtomicReference<Thread>();
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, final WebViewSchemeResponder responder) {
                handlerThread.set(Thread.currentThread());
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                        }
                        responder.respond(WebViewSchemeResponse.text(200, "late"));
                    }
                }).start();
            }
        });
        long before = System.nanoTime();
        get(d, 7, "demo://app/slow");
        long returned = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertTrue("onSchemeRequest blocked for " + returned + " ms", returned < 150);
        Answer a = sink.await();
        assertEquals("late", a.text());
        assertNotSame(Thread.currentThread(), handlerThread.get());
        assertTrue(handlerThread.get().getName().startsWith("webview-scheme-"));
        assertTrue(handlerThread.get().isDaemon());
    }

    @Test
    public void aThrowingHandlerGives500() throws Exception {                        // AC6
        Recorder sink = new Recorder(1);
        Thread.UncaughtExceptionHandler saved = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
            }
        });
        try {
            SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
                @Override
                public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                    throw new IllegalStateException("broken");
                }
            });
            get(d, 1, "demo://app/broken");
            assertEquals(500, sink.await().status);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved);
        }
    }

    @Test
    public void aSilentHandlerTimesOutWith504() throws Exception {                   // AC7
        Recorder sink = new Recorder(1);
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
            }
        });
        d.timeoutMs = 100;
        get(d, 1, "demo://app/hang");
        assertEquals(504, sink.await().status);
        assertEquals(0, d.pendingCount());
    }

    @Test
    public void answeringTwiceReachesTheSinkOnce() throws Exception {
        Recorder sink = new Recorder(1);
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                responder.respond(WebViewSchemeResponse.text(200, "first"));
                responder.respond(WebViewSchemeResponse.text(201, "second"));
            }
        });
        get(d, 1, "demo://app/x");
        assertEquals("first", sink.await().text());
        Thread.sleep(100);
        assertEquals(1, sink.answers.size());
    }

    @Test
    public void aCancelledRequestSendsNothing() throws Exception {
        Recorder sink = new Recorder(1);
        final CountDownLatch called = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) throws Exception {
                called.countDown();
                release.await();
                responder.respond(WebViewSchemeResponse.text(200, "too late"));
            }
        });
        d.timeoutMs = 200;
        get(d, 1, "demo://app/x");
        assertTrue(called.await(5, TimeUnit.SECONDS));
        d.onSchemeCancelled(1);
        release.countDown();
        Thread.sleep(400);
        assertTrue(sink.answers.isEmpty());
        assertEquals(0, d.pendingCount());
    }

    @Test
    public void anUnknownSchemeGives404() throws Exception {
        Recorder sink = new Recorder(1);
        SchemeDispatcher d = dispatcher(sink, "demo", HELLO);
        get(d, 1, "other://app/x");
        assertEquals(404, sink.await().status);
    }

    @Test
    public void anOversizedRequestGives413WithoutCallingTheHandler() throws Exception {
        Recorder sink = new Recorder(1);
        final AtomicBoolean called = new AtomicBoolean();
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                called.set(true);
                responder.respond(WebViewSchemeResponse.text(200, "ok"));
            }
        });
        d.onSchemeRequest(1, "POST", "demo://app/api", new String[0],
                new byte[SchemeDispatcher.MAX_REQUEST_BYTES + 1], true);
        assertEquals(413, sink.await().status);
        Thread.sleep(50);
        assertFalse(called.get());
    }

    @Test
    public void anOversizedResponseGives500() throws Exception {
        Recorder sink = new Recorder(1);
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                responder.respond(WebViewSchemeResponse.of(200, null, new byte[SchemeDispatcher.MAX_RESPONSE_BYTES + 1]));
            }
        });
        get(d, 1, "demo://app/huge");
        Answer a = sink.await();
        assertEquals(500, a.status);
        assertTrue(a.body.length < 1024);
    }

    @Test
    public void thePostBodyAndMethodPassThrough() throws Exception {                 // AC3
        Recorder sink = new Recorder(1);
        final AtomicReference<WebViewSchemeRequest> seen = new AtomicReference<WebViewSchemeRequest>();
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                seen.set(request);
                responder.respond(WebViewSchemeResponse.ok("application/json", request.body()));
            }
        });
        d.onSchemeRequest(1, "POST", "demo://app/api/save", new String[] {"Content-Type", "application/json"},
                "{\"n\":3}".getBytes(StandardCharsets.UTF_8), true);
        Answer a = sink.await();
        assertEquals("POST", seen.get().method());
        assertEquals("demo://app/api/save", seen.get().url());
        assertEquals("{\"n\":3}", new String(seen.get().body(), StandardCharsets.UTF_8));
        assertEquals("{\"n\":3}", a.text());
    }

    @Test
    public void repeatedHeadersAreKeptAndLookupIgnoresCase() throws Exception {
        Recorder sink = new Recorder(1);
        final AtomicReference<WebViewSchemeRequest> seen = new AtomicReference<WebViewSchemeRequest>();
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                seen.set(request);
                responder.respond(WebViewSchemeResponse.text(200, "ok"));
            }
        });
        d.onSchemeRequest(1, "GET", "demo://app/x",
                new String[] {"Accept", "text/html", "Accept", "application/json", "X-Token", "abc"},
                new byte[0], true);
        sink.await();
        assertEquals("abc", seen.get().header("x-token"));
        assertEquals(Arrays.asList("text/html", "application/json"), seen.get().headers().get("Accept"));
        assertEquals("text/html", seen.get().header("ACCEPT"));
    }

    @Test
    public void bodyAvailableFalsePassesThrough() throws Exception {
        Recorder sink = new Recorder(1);
        final AtomicReference<WebViewSchemeRequest> seen = new AtomicReference<WebViewSchemeRequest>();
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
                seen.set(request);
                responder.respond(WebViewSchemeResponse.text(200, "ok"));
            }
        });
        d.onSchemeRequest(1, "POST", "demo://app/x", new String[0], null, false);
        sink.await();
        assertFalse(seen.get().bodyAvailable());
        assertEquals(0, seen.get().body().length);
    }

    @Test
    public void fiftyConcurrentRequestsAreEachAnsweredOnce() throws Exception {      // NF
        final int n = 50;
        Recorder sink = new Recorder(n);
        SchemeDispatcher d = dispatcher(sink, "demo", new WebViewSchemeHandler() {
            @Override
            public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) throws Exception {
                Thread.sleep((long) (Math.random() * 20));
                responder.respond(WebViewSchemeResponse.ok("text/plain", request.body()));
            }
        });
        for (int i = 0; i < n; i++) {
            d.onSchemeRequest(i, "POST", "demo://app/img/" + i, new String[0],
                    ("body-" + i).getBytes(StandardCharsets.UTF_8), true);
        }
        sink.await();
        Thread.sleep(100);
        assertEquals(n, sink.answers.size());
        boolean[] seen = new boolean[n];
        synchronized (sink.answers) {
            for (Answer a : sink.answers) {
                assertFalse("answered twice: " + a.id, seen[(int) a.id]);
                seen[(int) a.id] = true;
                assertEquals("body-" + a.id, a.text());
            }
        }
        assertEquals(0, d.pendingCount());
    }

    @Test
    public void originOfTakesSchemeAndHost() {
        assertEquals("demo://app", SchemeDispatcher.originOf("demo://app/index.html?x=1"));
        assertEquals("demo://app", SchemeDispatcher.originOf("demo://app#top"));
        assertNull(SchemeDispatcher.originOf("demo:nohost"));
    }
}
