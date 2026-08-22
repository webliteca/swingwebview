/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.io.File;

/**
 * Immutable carrier of a download's single terminal outcome, surfaced
 * to {@link WebViewDownloadHandler#downloadCompleted}.
 *
 * <p><strong>Delivered exactly once per download.</strong>
 * {@link DownloadDispatcher} latches the outcome, so a handler sees one
 * event whether the engine reported success, reported failure, reported
 * both (WebKitGTK can emit {@code failed} and then {@code finished} for
 * one abandoned transfer), or the download was refused before it
 * started.
 *
 * <p>{@link #destination()} is {@code null} only when the download was
 * refused before a destination existed — a handler that returned
 * {@code null}, a handler that threw, or a drop handler installed via
 * {@code setDownloadHandler(null)}.
 */
public final class WebViewDownloadCompleteEvent {

    private final WebViewComponent source;
    private final long id;
    private final File destination;
    private final boolean success;
    private final String failureReason;
    private final long receivedBytes;

    WebViewDownloadCompleteEvent(WebViewComponent source, long id,
                                 File destination, boolean success,
                                 String failureReason, long receivedBytes) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.id = id;
        this.destination = destination;
        this.success = success;
        this.failureReason = success
            ? "" : (failureReason == null ? "" : failureReason);
        this.receivedBytes = receivedBytes < 0 ? 0 : receivedBytes;
    }

    /** @return the WebView component the download started in; never null. */
    public WebViewComponent source() { return source; }

    /** @return the download's identity, matching the
     *  {@link WebViewDownloadEvent#id()} that opened it. */
    public long id() { return id; }

    /** @return the file the handler chose, or {@code null} when the
     *  download was refused before a destination existed. */
    public File destination() { return destination; }

    /** @return whether the complete body reached
     *  {@link #destination()}. */
    public boolean success() { return success; }

    /** @return a human-readable description of what went wrong; never
     *  null, and always empty when {@link #success()}. */
    public String failureReason() { return failureReason; }

    /** @return bytes written before the outcome; {@code 0} for a
     *  refused download. */
    public long receivedBytes() { return receivedBytes; }

    @Override
    public String toString() {
        return "WebViewDownloadCompleteEvent[id=" + id
            + ", success=" + success
            + ", received=" + receivedBytes
            + ", file=" + (destination == null ? "<none>" : destination.getName())
            + (success ? "" : ", reason=" + failureReason) + "]";
    }
}
