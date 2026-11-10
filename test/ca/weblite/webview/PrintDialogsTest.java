/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/** The print-dialog plumbing (Canvas 38 op 2), without natives. */
public class PrintDialogsTest {

    private static final Executor NOW = Runnable::run;

    private static String failure(CompletableFuture<PrintOutcome> f) throws Exception {
        try {
            f.get();
            fail("expected a failure");
            return null;
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
            return e.getCause().getMessage();
        }
    }

    @Test
    public void outcomesMapFromOrdinals() throws Exception {
        assertEquals(PrintOutcome.PRINTED, PrintDialogs.outcomeFor(0));
        assertEquals(PrintOutcome.CANCELLED, PrintDialogs.outcomeFor(1));
        assertEquals(PrintOutcome.OPENED, PrintDialogs.outcomeFor(2));
        assertNull(PrintDialogs.outcomeFor(-1));
        assertNull(PrintDialogs.outcomeFor(3));
        PrintDialogs.Request r = PrintDialogs.request(NOW);
        r.callback.onPrintFinished(1, null);
        assertEquals(PrintOutcome.CANCELLED, r.future.get());
    }

    @Test
    public void firstAnswerWins() throws Exception {
        PrintDialogs.Request r = PrintDialogs.request(NOW);
        r.callback.onPrintFinished(0, null);
        r.fail(PrintDialogs.CLOSED);
        r.callback.onPrintFinished(1, null);
        assertEquals(PrintOutcome.PRINTED, r.future.get());

        PrintDialogs.Request closed = PrintDialogs.request(NOW);
        closed.fail(PrintDialogs.CLOSED);
        closed.callback.onPrintFinished(0, null);
        assertEquals(PrintDialogs.CLOSED, failure(closed.future));
    }

    @Test
    public void failuresCarryTheReasonOrADefault() throws Exception {
        PrintDialogs.Request r = PrintDialogs.request(NOW);
        r.callback.onPrintFinished(-1, "Printing needs macOS 11 or later.");
        assertEquals("Printing needs macOS 11 or later.", failure(r.future));

        PrintDialogs.Request blank = PrintDialogs.request(NOW);
        blank.callback.onPrintFinished(-1, "");
        assertEquals("The page could not be printed.", failure(blank.future));

        assertEquals(PrintDialogs.BUSY, failure(PrintDialogs.failed(PrintDialogs.BUSY)));
    }

    @Test
    public void windowsScriptCallsTheCallbackOnce() {
        String js = PrintDialogs.WINDOWS_SCRIPT;
        assertTrue(js.contains("window." + PrintDialogs.CALLBACK_NAME + "("));
        assertTrue(js.contains("if (window.__swingwebviewPrintHooked) return;"));
        assertTrue(js.contains("window.print = function()"));
    }

    @Test
    public void firstStringArgReadsTheBindingArguments() {
        assertEquals("https://example.com/a?b=1",
                PrintDialogs.firstStringArg("[\"https:\\/\\/example.com\\/a?b=1\"]"));
        assertEquals("say \"hi\"", PrintDialogs.firstStringArg("[\"say \\\"hi\\\"\"]"));
        assertEquals("", PrintDialogs.firstStringArg(null));
        assertEquals("", PrintDialogs.firstStringArg("[]"));
        assertEquals("", PrintDialogs.firstStringArg("[1]"));
    }

    @Test
    public void unavailableWithoutNatives() {
        // The test JVM has no native library loaded under this name.
        PrintDialogs.isAvailable();   // must not throw
    }
}
