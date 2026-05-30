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
 * <p>Field semantics:
 * <ul>
 *   <li>{@link #source()} — the {@link WebViewComponent} the download
 *       originated in. Never {@code null}.</li>
 *   <li>{@link #suggestedFilename()} — engine-supplied filename. The
 *       constructor performs a defensive sanitisation pass: path
 *       separators ({@code /} and {@code \}) are replaced with
 *       {@code _}; leading {@code .} characters are stripped; an empty
 *       post-sanitisation result is substituted with {@code "download"}.
 *       This is defence-in-depth against engine sanitisation
 *       regressions — a malicious {@code Content-Disposition: filename=
 *       "../../etc/passwd"} cannot escape the chosen destination
 *       directory even if the engine itself missed it.</li>
 *   <li>{@link #sourceUrl()} — the URL the response was fetched from.
 *       Empty string when the engine reports no value (never
 *       {@code null}).</li>
 *   <li>{@link #mimeType()} — engine-supplied MIME type. Empty string
 *       when not available (never {@code null}).</li>
 *   <li>{@link #totalBytes()} — engine-supplied content length, or
 *       {@code -1} when unknown (chunked transfer encoding, missing
 *       {@code Content-Length} header). Negative values from the
 *       engine are coerced to the canonical {@code -1} sentinel.</li>
 * </ul>
 */
public final class WebViewDownloadEvent {

    private final WebViewComponent source;
    private final String suggestedFilename;
    private final String sourceUrl;
    private final String mimeType;
    private final long totalBytes;

    WebViewDownloadEvent(WebViewComponent source, String suggestedFilename,
                         String sourceUrl, String mimeType, long totalBytes) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        this.source = source;
        this.suggestedFilename = sanitiseFilename(suggestedFilename);
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.totalBytes = totalBytes < 0 ? -1L : totalBytes;
    }

    public WebViewComponent source() {
        return source;
    }

    public String suggestedFilename() {
        return suggestedFilename;
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public String mimeType() {
        return mimeType;
    }

    public long totalBytes() {
        return totalBytes;
    }

    @Override
    public String toString() {
        String urlPart = sourceUrl.length() > 80
            ? sourceUrl.substring(0, 80) + "..."
            : sourceUrl;
        return "WebViewDownloadEvent[filename=" + suggestedFilename
            + ", url=" + urlPart
            + ", mime=" + mimeType
            + ", totalBytes=" + totalBytes
            + "]";
    }

    /**
     * Defensive filename sanitisation. Replaces {@code /} and {@code \}
     * with {@code _} so a malicious or buggy engine cannot direct a
     * write outside the chosen destination directory. Strips any
     * leading {@code .} run so a server cannot create a hidden file
     * the user might not notice. Empty input or post-sanitisation
     * empty result becomes {@code "download"}.
     */
    static String sanitiseFilename(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "download";
        }
        String s = raw.replace('/', '_').replace('\\', '_');
        int i = 0;
        while (i < s.length() && s.charAt(i) == '.') {
            i++;
        }
        if (i > 0) {
            s = s.substring(i);
        }
        if (s.isEmpty()) {
            return "download";
        }
        return s;
    }
}
