/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable descriptor of the DOM element under the cursor when a
 * {@link WebViewMouseEvent} fires.  Carries the information most useful for
 * building a context-aware menu: tag, id, classes, a curated subset of
 * attributes, the nearest ancestor link, image/media source, content
 * editability, the current text selection, and page/frame URLs.
 *
 * <p>The {@link #attributes()} map is curated, not exhaustive.  It contains
 * the entries from {@code href / src / alt / title / name / type / value /
 * role} that the element actually has, plus any {@code data-*} attributes
 * present (subject to an 8&nbsp;KiB total cap; overflow is truncated with a
 * trailing {@code "..."}).
 *
 * <p>{@link #selectionText()} is capped at 64&nbsp;KiB UTF-8; longer
 * selections are truncated with a trailing {@code "..."} so callers can
 * detect that they didn't see everything.
 *
 * <p>When {@code document.designMode = 'on'} or a top-level element is
 * {@code contenteditable}, every descendant reports
 * {@link #isContentEditable()} as {@code true} &mdash; that's the correct
 * DOM semantics, not a defect.  Cross-origin iframes are invisible to this
 * type: events from inside a cross-origin frame never reach the top-level
 * listener and therefore produce no callback at all.
 */
public final class DomTarget {

    private final String tagName;
    private final String id;
    private final List<String> classes;
    private final Map<String, String> attributes;
    private final String linkHref;
    private final String imageSrc;
    private final String mediaSrc;
    private final boolean contentEditable;
    private final String selectionText;
    private final String pageUrl;
    private final String frameUrl;

    DomTarget(String tagName, String id,
              List<String> classes, Map<String, String> attributes,
              String linkHref, String imageSrc, String mediaSrc,
              boolean contentEditable, String selectionText,
              String pageUrl, String frameUrl) {
        if (tagName == null) throw new NullPointerException("tagName");
        if (id == null) throw new NullPointerException("id");
        if (selectionText == null) throw new NullPointerException("selectionText");
        if (pageUrl == null) throw new NullPointerException("pageUrl");
        if (frameUrl == null) throw new NullPointerException("frameUrl");
        this.tagName = tagName;
        this.id = id;
        this.classes = (classes == null || classes.isEmpty())
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<String>(classes));
        this.attributes = (attributes == null || attributes.isEmpty())
            ? Collections.<String, String>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        this.linkHref = linkHref;
        this.imageSrc = imageSrc;
        this.mediaSrc = mediaSrc;
        this.contentEditable = contentEditable;
        this.selectionText = selectionText;
        this.pageUrl = pageUrl;
        this.frameUrl = frameUrl;
    }

    /** @return the element's tag name in uppercase ASCII (e.g. {@code "DIV"}). */
    public String tagName() { return tagName; }

    /** @return the element's {@code id} attribute, or {@code ""} (never null). */
    public String id() { return id; }

    /**
     * @return the element's class list in source order; never null.
     * Returns an empty, immutable list when the element has no class attribute.
     */
    public List<String> classes() { return classes; }

    /**
     * @return a curated, immutable, never-null map of attribute values
     * present on the element.  See class-level Javadoc for the curation
     * rules and size caps.
     */
    public Map<String, String> attributes() { return attributes; }

    /**
     * @return the {@code href} of the nearest ancestor {@code <a>} element
     * (including this element if it is itself an anchor), or {@code null}
     * when no ancestor link exists.
     */
    public String linkHref() { return linkHref; }

    /**
     * @return the {@code src} of the target when it is an {@code <img>},
     * otherwise {@code null}.
     */
    public String imageSrc() { return imageSrc; }

    /**
     * @return the current playback source (preferring {@code currentSrc}) of
     * the target when it is an {@code <audio>} or {@code <video>} element,
     * otherwise {@code null}.
     */
    public String mediaSrc() { return mediaSrc; }

    /**
     * @return {@code true} when the target is content-editable: either
     * {@code isContentEditable} is {@code true} on the DOM node, or it is an
     * {@code <input>} of a text-like type ({@code text}, {@code search},
     * {@code email}, {@code url}, {@code tel}, {@code password},
     * {@code number}), or it is a {@code <textarea>}.
     */
    public boolean isContentEditable() { return contentEditable; }

    /**
     * @return the current {@code window.getSelection().toString()} value at
     * the moment of the event, or {@code ""} when there is no selection.
     * Capped at 64&nbsp;KiB with a trailing {@code "..."} on truncation.
     */
    public String selectionText() { return selectionText; }

    /** @return the top-level document URL. */
    public String pageUrl() { return pageUrl; }

    /**
     * @return the URL of the immediate document the target is in; equals
     * {@link #pageUrl()} unless the target lives in a same-origin iframe.
     */
    public String frameUrl() { return frameUrl; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("DomTarget[").append(tagName);
        if (!id.isEmpty()) sb.append('#').append(id);
        if (!classes.isEmpty()) sb.append(" classes=").append(classes.size());
        if (!attributes.isEmpty()) sb.append(" attrs=").append(attributes.size());
        if (linkHref != null) sb.append(" link");
        if (imageSrc != null) sb.append(" image");
        if (mediaSrc != null) sb.append(" media");
        if (contentEditable) sb.append(" editable");
        if (!selectionText.isEmpty()) sb.append(" sel=").append(selectionText.length());
        sb.append(']');
        return sb.toString();
    }
}
