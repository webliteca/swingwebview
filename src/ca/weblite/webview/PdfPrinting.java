/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The capability probe and shared future plumbing behind print-to-PDF
 * (Canvas 29).  Internal to the library; applications use
 * {@link ca.weblite.webview.swing.WebViewComponent#printToPdf(File, PdfOptions)}.
 */
public final class PdfPrinting {

    public static final String NOT_AVAILABLE =
            "PDF printing is not available in this version of the native library.";
    public static final String NOT_ATTACHED = "The WebView is not attached yet.";
    public static final String CLOSED = "The WebView was closed.";

    private PdfPrinting() {
    }

    /**
     * Whether the loaded native library can print to PDF.  An older native
     * without the entry point answers {@code false} rather than throwing
     * (Canvas 29 D4).
     */
    public static boolean isAvailable() {
        try {
            return WebViewNative.webview_pdf_available();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Why a print to {@code out} with {@code o} cannot start, or {@code null}
     * when it can.
     */
    public static String precheck(File out, PdfOptions o) {
        return precheck(out, o, isAvailable());
    }

    /**
     * {@link #precheck(File, PdfOptions)} with the capability answer given
     * rather than probed.
     */
    public static String precheck(File out, PdfOptions o, boolean available) {
        if (out == null) {
            return "Say where to write the PDF.";
        }
        String invalid = (o == null ? PdfOptions.letter() : o).validate();
        if (invalid != null) {
            return invalid;
        }
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return "The folder " + parent + " does not exist.";
        }
        if (!available) {
            return NOT_AVAILABLE;
        }
        return null;
    }

    /** A future that has already failed with {@code reason}. */
    public static CompletableFuture<File> failed(String reason) {
        CompletableFuture<File> f = new CompletableFuture<File>();
        f.completeExceptionally(new IOException(reason));
        return f;
    }

    /**
     * A new request for {@code out} whose future completes on
     * {@code completeOn}.
     */
    public static Request request(File out, Executor completeOn) {
        return new Request(out, completeOn);
    }

    /**
     * One print request: the future the caller holds and the callback the
     * native side answers.  The future completes at most once; later
     * answers are ignored.
     */
    public static final class Request {
        public final CompletableFuture<File> future =
                new CompletableFuture<File>();
        public final WebViewPdfCallback callback;
        private final AtomicBoolean done = new AtomicBoolean();
        private final File out;
        private final Executor completeOn;

        Request(File out, Executor completeOn) {
            this.out = out;
            this.completeOn = completeOn;
            this.callback = new WebViewPdfCallback() {
                @Override
                public void onPdfFinished(boolean ok, String error) {
                    finish(ok, error);
                }
            };
        }

        /** Fail the request with {@code reason}, unless it already finished. */
        public void fail(String reason) {
            finish(false, reason);
        }

        private void finish(final boolean ok, final String error) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            completeOn.execute(new Runnable() {
                @Override
                public void run() {
                    if (ok) {
                        future.complete(out);
                    } else {
                        future.completeExceptionally(new IOException(
                                error == null || error.isEmpty()
                                        ? "The page could not be printed to PDF."
                                        : error));
                    }
                }
            });
        }
    }

    /**
     * Runs one engine's prints one at a time, in order (Canvas 29 D12): an
     * engine asked to print while a print is running drops or garbles the
     * second.  The next print starts from the callback of the one before.
     */
    public static final class Queue {
        private final ArrayDeque<Object[]> waiting = new ArrayDeque<Object[]>();
        private boolean busy;

        /**
         * Start {@code start} with a callback that answers {@code cb} and
         * then starts the next queued print — now if the engine is idle,
         * otherwise when the running print finishes.
         */
        public void submit(Consumer<WebViewPdfCallback> start,
                           WebViewPdfCallback cb) {
            synchronized (this) {
                if (busy) {
                    waiting.add(new Object[] {start, cb});
                    return;
                }
                busy = true;
            }
            run(start, cb);
        }

        /** Answer every print not yet started with {@code reason}. */
        public void failWaiting(String reason) {
            Object[][] dropped;
            synchronized (this) {
                dropped = waiting.toArray(new Object[0][]);
                waiting.clear();
            }
            for (Object[] w : dropped) {
                ((WebViewPdfCallback) w[1]).onPdfFinished(false, reason);
            }
        }

        private void run(Consumer<WebViewPdfCallback> start,
                         final WebViewPdfCallback cb) {
            final AtomicBoolean answered = new AtomicBoolean();
            try {
                start.accept(new WebViewPdfCallback() {
                    @Override
                    public void onPdfFinished(boolean ok, String error) {
                        if (!answered.compareAndSet(false, true)) {
                            return;
                        }
                        try {
                            cb.onPdfFinished(ok, error);
                        } finally {
                            next();
                        }
                    }
                });
            } catch (RuntimeException | Error t) {
                if (answered.compareAndSet(false, true)) {
                    try {
                        cb.onPdfFinished(false, String.valueOf(t.getMessage()));
                    } finally {
                        next();
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void next() {
            Object[] w;
            synchronized (this) {
                w = waiting.poll();
                if (w == null) {
                    busy = false;
                    return;
                }
            }
            run((Consumer<WebViewPdfCallback>) w[0], (WebViewPdfCallback) w[1]);
        }
    }
}
