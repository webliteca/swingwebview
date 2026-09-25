/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

/** {@link PdfPrinting.Queue}: one print at a time per engine (Canvas 29 D12). */
public class PdfPrintingQueueTest {

    /** Records starts and hands back the callback each start was given. */
    private static final class Engine {
        final List<String> started = new ArrayList<String>();
        final List<WebViewPdfCallback> running = new ArrayList<WebViewPdfCallback>();

        java.util.function.Consumer<WebViewPdfCallback> start(final String name) {
            return cb -> {
                started.add(name);
                running.add(cb);
            };
        }
    }

    private static WebViewPdfCallback recorder(final List<String> answers,
                                               final String name) {
        return (ok, error) -> answers.add(name + ":" + ok + ":" + error);
    }

    @Test
    public void testSecondPrintWaitsForTheFirst() {
        Engine e = new Engine();
        List<String> answers = new ArrayList<String>();
        PdfPrinting.Queue q = new PdfPrinting.Queue();
        q.submit(e.start("a"), recorder(answers, "a"));
        q.submit(e.start("b"), recorder(answers, "b"));
        q.submit(e.start("c"), recorder(answers, "c"));
        assertEquals("[a]", e.started.toString());

        e.running.get(0).onPdfFinished(true, null);
        assertEquals("[a, b]", e.started.toString());
        e.running.get(1).onPdfFinished(false, "boom");
        assertEquals("[a, b, c]", e.started.toString());
        e.running.get(2).onPdfFinished(true, null);
        assertEquals("[a:true:null, b:false:boom, c:true:null]", answers.toString());

        // Idle again: the next print starts at once.
        q.submit(e.start("d"), recorder(answers, "d"));
        assertEquals("[a, b, c, d]", e.started.toString());
    }

    @Test
    public void testASecondAnswerFromTheEngineIsIgnored() {
        Engine e = new Engine();
        List<String> answers = new ArrayList<String>();
        PdfPrinting.Queue q = new PdfPrinting.Queue();
        q.submit(e.start("a"), recorder(answers, "a"));
        q.submit(e.start("b"), recorder(answers, "b"));
        e.running.get(0).onPdfFinished(true, null);
        e.running.get(0).onPdfFinished(false, "late");
        assertEquals("[a:true:null]", answers.toString());
        assertEquals("[a, b]", e.started.toString());
    }

    @Test
    public void testAThrowingStartIsAnsweredAndTheQueueMovesOn() {
        Engine e = new Engine();
        List<String> answers = new ArrayList<String>();
        PdfPrinting.Queue q = new PdfPrinting.Queue();
        q.submit(e.start("a"), recorder(answers, "a"));
        q.submit(cb -> { throw new IllegalStateException("gone"); },
                recorder(answers, "b"));
        q.submit(e.start("c"), recorder(answers, "c"));
        e.running.get(0).onPdfFinished(true, null);
        assertEquals("[a:true:null, b:false:gone]", answers.toString());
        assertEquals("[a, c]", e.started.toString());
    }

    @Test
    public void testFailWaitingAnswersOnlyUnstartedPrints() {
        Engine e = new Engine();
        List<String> answers = new ArrayList<String>();
        PdfPrinting.Queue q = new PdfPrinting.Queue();
        q.submit(e.start("a"), recorder(answers, "a"));
        q.submit(e.start("b"), recorder(answers, "b"));
        q.submit(e.start("c"), recorder(answers, "c"));
        q.failWaiting(PdfPrinting.CLOSED);
        assertEquals("[b:false:The WebView was closed., c:false:The WebView was closed.]",
                answers.toString());
        assertEquals("[a]", e.started.toString());
        e.running.get(0).onPdfFinished(true, null);
        assertEquals("[a]", e.started.toString());
    }
}
