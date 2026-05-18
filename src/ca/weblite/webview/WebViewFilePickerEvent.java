/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable carrier of one {@code <input type="file">} click raised by
 * the embedded page, surfaced to
 * {@link WebViewDialogHandler#filePickerOpened}.
 *
 * <p>The accepted-extensions and accepted-mime-types lists are derived
 * from the page's {@code <input accept="...">} attribute and normalised
 * by {@link DialogDispatcher} before construction: lowercase, leading
 * dot stripped (extensions only), duplicates removed in insertion
 * order.  Both lists are never null and are unmodifiable views; empty
 * means the page imposed no restriction.
 */
public final class WebViewFilePickerEvent {

    private final WebViewComponent source;
    private final boolean multiple;
    private final List<String> acceptedExtensions;
    private final List<String> acceptedMimeTypes;
    private final String pageUrl;
    private final String frameUrl;

    WebViewFilePickerEvent(WebViewComponent source, boolean multiple,
                           List<String> acceptedExtensions,
                           List<String> acceptedMimeTypes,
                           String pageUrl, String frameUrl) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.multiple = multiple;
        this.acceptedExtensions = freeze(acceptedExtensions);
        this.acceptedMimeTypes = freeze(acceptedMimeTypes);
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.frameUrl = frameUrl == null ? "" : frameUrl;
    }

    private static List<String> freeze(List<String> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<String>(in));
    }

    /** @return the WebView component the file picker originated from; never null. */
    public WebViewComponent source() { return source; }

    /** @return {@code true} when the page's {@code <input>} carries the
     *  {@code multiple} attribute. */
    public boolean multiple() { return multiple; }

    /** @return unmodifiable list of normalised file extensions (lower-case,
     *  no leading dot, deduplicated, insertion order preserved); empty
     *  when the page imposed no extension constraint.  Never null. */
    public List<String> acceptedExtensions() { return acceptedExtensions; }

    /** @return unmodifiable list of normalised MIME types (lower-case,
     *  deduplicated, insertion order preserved); empty when the page
     *  imposed no MIME constraint.  Never null. */
    public List<String> acceptedMimeTypes() { return acceptedMimeTypes; }

    /** @return the top-level document URL; never null, may be empty. */
    public String pageUrl() { return pageUrl; }

    /** @return the initiating frame's URL; never null, may be empty. */
    public String frameUrl() { return frameUrl; }

    @Override
    public String toString() {
        return "WebViewFilePickerEvent[multiple=" + multiple
            + ", extensions=" + acceptedExtensions.size()
            + ", mimeTypes=" + acceptedMimeTypes.size()
            + ", pageUrl=" + pageUrl + "]";
    }
}
