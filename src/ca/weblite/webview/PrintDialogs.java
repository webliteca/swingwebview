/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The capability probe, messages and shared future plumbing behind the
 * native print dialog (Canvas 38).  Internal to the library; applications use
 * {@link ca.weblite.webview.swing.WebViewComponent#print()}.
 */
public final class PrintDialogs {

    public static final String NOT_AVAILABLE =
            "Printing is not available in this version of the native library.";
    public static final String BUSY = "A print is already in progress.";
    public static final String RUNTIME_TOO_OLD =
            "Printing needs a newer WebView2 runtime.";
    public static final String LINUX_HEAVYWEIGHT =
            "Printing on Linux needs the lightweight WebView component.";
    public static final String NOT_ATTACHED = PdfPrinting.NOT_ATTACHED;
    public static final String CLOSED = PdfPrinting.CLOSED;

    /** The default reason for a failure the engine did not explain. */
    static final String FAILED = "The page could not be printed.";

    /** The JavaScript callback the Windows script calls (Canvas 38 D6). */
    public static final String CALLBACK_NAME = "__swingwebviewPrint";

    /**
     * Document-start script used on Windows while a
     * {@link WebViewPrintHandler} is installed (Canvas 38 D6): replaces
     * {@code window.print} with a call to {@link #CALLBACK_NAME}, once per
     * document.
     */
    public static final String WINDOWS_SCRIPT =
            "(function(){"
            + "if (window.__swingwebviewPrintHooked) return;"
            + "window.__swingwebviewPrintHooked = true;"
            + "window.print = function(){"
            + "try { window." + CALLBACK_NAME + "(String(location.href)); } catch (e) {}"
            + "};"
            + "})();";

    private PrintDialogs() {
    }

    /**
     * Whether the loaded native library can show the print dialog (Canvas 38
     * D3).  An older native without the entry point answers {@code false}.
     */
    public static boolean isAvailable() {
        try {
            return WebViewNative.webview_print_dialog_available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** A future that has already failed with {@code reason}. */
    public static CompletableFuture<PrintOutcome> failed(String reason) {
        CompletableFuture<PrintOutcome> f = new CompletableFuture<PrintOutcome>();
        f.completeExceptionally(new IOException(reason));
        return f;
    }

    /** A new request whose future completes on {@code completeOn}. */
    public static Request request(Executor completeOn) {
        return new Request(completeOn);
    }

    /** The outcome for a native ordinal, or {@code null} for a failure. */
    static PrintOutcome outcomeFor(int ordinal) {
        PrintOutcome[] all = PrintOutcome.values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }

    /**
     * The first argument of a binding's JSON argument array when it is a
     * string, else {@code ""} (Canvas 38 op 2).  Used for the Windows script's
     * {@code location.href}.
     */
    public static String firstStringArg(String json) {
        if (json == null) {
            return "";
        }
        String t = json.trim();
        if (!t.startsWith("[\"")) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 2; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\' && i + 1 < t.length()) {
                char n = t.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                out.append(c);
            }
        }
        return "";
    }

    /**
     * One print-dialog request: the future the caller holds and the callback
     * the native side answers.  The first answer wins; later ones are ignored
     * (Canvas 38 D5).
     */
    public static final class Request {
        public final CompletableFuture<PrintOutcome> future =
                new CompletableFuture<PrintOutcome>();
        public final WebViewPrintCallback callback;
        private final AtomicBoolean done = new AtomicBoolean();
        private final Executor completeOn;

        Request(Executor completeOn) {
            this.completeOn = completeOn;
            this.callback = new WebViewPrintCallback() {
                @Override
                public void onPrintFinished(int outcome, String error) {
                    finish(outcome, error);
                }
            };
        }

        /** Fail the request with {@code reason}, unless it already finished. */
        public void fail(String reason) {
            finish(-1, reason);
        }

        private void finish(final int outcome, final String error) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            completeOn.execute(new Runnable() {
                @Override
                public void run() {
                    PrintOutcome o = outcomeFor(outcome);
                    if (o != null) {
                        future.complete(o);
                    } else {
                        future.completeExceptionally(new IOException(
                                error == null || error.isEmpty() ? FAILED : error));
                    }
                }
            });
        }
    }
}
