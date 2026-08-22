/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable carrier of one browser-initiated download request, surfaced
 * to {@link WebViewDownloadHandler#downloadRequested}.
 *
 * <p>{@link #suggestedFileName()} has <strong>already been
 * sanitised</strong> by {@link DownloadDispatcher} before this event is
 * constructed: it is a bare leaf name with no directory separators of
 * either flavour, no parent-directory segments, no control characters,
 * no characters illegal on Windows, no trailing dots or spaces, no
 * reserved Windows device name, bounded in length, and never empty.
 * The wire-supplied name it derives from is attacker-controlled, so a
 * handler must still not join it onto a parent path the page can
 * influence.
 *
 * <p>{@link #totalBytes()} is {@code -1} when the server declared no
 * {@code Content-Length} — never {@code 0}, which would be
 * indistinguishable from a genuinely empty body. Use
 * {@link #sizeKnown()} rather than testing for a magic value.
 */
public final class WebViewDownloadEvent {

    private final WebViewComponent source;
    private final long id;
    private final String url;
    private final String suggestedFileName;
    private final String mimeType;
    private final long totalBytes;
    private final String pageUrl;

    WebViewDownloadEvent(WebViewComponent source, long id, String url,
                         String suggestedFileName, String mimeType,
                         long totalBytes, String pageUrl) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.id = id;
        this.url = url == null ? "" : url;
        this.suggestedFileName = suggestedFileName;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.totalBytes = totalBytes;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
    }

    /** @return the WebView component the download started in; never null. */
    public WebViewComponent source() { return source; }

    /** @return this download's identity, stable across every event the
     *  library reports for it.  Distinct from the destination file,
     *  which is unknown at request time and may repeat across
     *  sequential downloads. */
    public long id() { return id; }

    /** @return the URL the bytes come from; never null, may be empty. */
    public String url() { return url; }

    /** @return the sanitised leaf file name to suggest to the user;
     *  never null, never empty.  See the class Javadoc for what
     *  sanitisation guarantees. */
    public String suggestedFileName() { return suggestedFileName; }

    /** @return the server-declared content type, lower-cased; never
     *  null, may be empty when the server declared none. */
    public String mimeType() { return mimeType; }

    /** @return the expected size in bytes, or {@code -1} when the
     *  server declared no {@code Content-Length}. */
    public long totalBytes() { return totalBytes; }

    /** @return whether {@link #totalBytes()} carries a real size. */
    public boolean sizeKnown() { return totalBytes >= 0; }

    /** @return the URL of the page that initiated the download; never
     *  null, may be empty. */
    public String pageUrl() { return pageUrl; }

    @Override
    public String toString() {
        String u = url.length() > 80 ? url.substring(0, 80) + "…" : url;
        return "WebViewDownloadEvent[id=" + id
            + ", file=" + suggestedFileName
            + ", mimeType=" + mimeType
            + ", totalBytes=" + totalBytes
            + ", url=" + u + "]";
    }
}
