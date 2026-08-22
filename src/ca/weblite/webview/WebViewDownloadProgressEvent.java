/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.io.File;

/**
 * Immutable snapshot of an in-flight download's progress, surfaced to
 * {@link WebViewDownloadHandler#downloadProgress}.
 *
 * <p><strong>Progress is lossy but monotonic.</strong>
 * {@link DownloadDispatcher} coalesces bursts so at most one progress
 * event per download is queued on the EDT at a time; a handler
 * therefore sees the latest counts rather than every chunk the engine
 * wrote. {@link #receivedBytes()} never decreases across the events
 * reported for one {@link #id()}.
 */
public final class WebViewDownloadProgressEvent {

    private final WebViewComponent source;
    private final long id;
    private final File destination;
    private final long receivedBytes;
    private final long totalBytes;

    WebViewDownloadProgressEvent(WebViewComponent source, long id,
                                 File destination, long receivedBytes,
                                 long totalBytes) {
        if (source == null) throw new NullPointerException("source");
        if (destination == null) throw new NullPointerException("destination");
        this.source = source;
        this.id = id;
        this.destination = destination;
        this.receivedBytes = receivedBytes < 0 ? 0 : receivedBytes;
        this.totalBytes = totalBytes;
    }

    /** @return the WebView component the download started in; never null. */
    public WebViewComponent source() { return source; }

    /** @return the download's identity, matching the
     *  {@link WebViewDownloadEvent#id()} that opened it. */
    public long id() { return id; }

    /** @return the file the handler chose for this download; never null. */
    public File destination() { return destination; }

    /** @return bytes written so far; never negative, never decreasing
     *  across the events reported for one {@link #id()}. */
    public long receivedBytes() { return receivedBytes; }

    /** @return the expected size in bytes, or {@code -1} when unknown. */
    public long totalBytes() { return totalBytes; }

    /** @return whether {@link #totalBytes()} carries a real size. */
    public boolean sizeKnown() { return totalBytes >= 0; }

    /** @return completion in {@code [0.0, 1.0]}, or {@code -1.0} when
     *  the size is unknown.  Convenience for driving a progress bar —
     *  a bar fed {@code -1.0} should render indeterminate rather than
     *  empty. */
    public double fraction() {
        if (totalBytes <= 0) return -1.0;
        double f = (double) receivedBytes / (double) totalBytes;
        return f > 1.0 ? 1.0 : f;
    }

    @Override
    public String toString() {
        return "WebViewDownloadProgressEvent[id=" + id
            + ", received=" + receivedBytes
            + ", total=" + totalBytes
            + ", file=" + destination.getName() + "]";
    }
}
