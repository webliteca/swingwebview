/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One media-capture request from a page, handed to a
 * {@link WebViewPermissionHandler} (Canvas 35 D2), and the means to answer
 * it.
 *
 * <p>The answer methods may be called from any thread, at any time; only the
 * first answer counts, and each returns whether it was that first answer.
 * The string accessors never return null.
 */
public final class WebViewPermissionRequest {

    /** Where an answer goes: the dispatcher. */
    interface Answerer {
        void answered(WebViewPermissionRequest request, PermissionDecision decision);
    }

    private final WebViewComponent source;
    private final long id;
    private final String origin;
    private final String url;
    private final Set<MediaCaptureKind> kinds;
    private final Answerer answerer;
    private final AtomicBoolean answered = new AtomicBoolean(false);

    WebViewPermissionRequest(WebViewComponent source, long id, String origin,
                             String url, Set<MediaCaptureKind> kinds,
                             Answerer answerer) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.id = id;
        this.url = url == null ? "" : url;
        String o = Origins.canonical(origin);
        if (o == null) o = Origins.canonical(this.url);
        this.origin = o == null ? "" : o;
        this.kinds = kinds;
        this.answerer = answerer;
    }

    /** @return the component whose page asked; never null. */
    public WebViewComponent source() { return source; }

    /**
     * @return the requesting origin, {@code scheme://host[:port]}; empty when
     *         the page has none (e.g. {@code about:blank}).  On Linux and
     *         Windows it is derived from {@link #url()}; on Linux that is the
     *         top page's address even when a frame asked.
     */
    public String origin() { return origin; }

    /** @return the address the engine reports for the request; may be
     *  empty. */
    public String url() { return url; }

    /** @return the devices asked for; unmodifiable, never empty. */
    public Set<MediaCaptureKind> kinds() { return kinds; }

    /** @return whether {@code kind} is among {@link #kinds()}. */
    public boolean has(MediaCaptureKind kind) { return kinds.contains(kind); }

    /** Grant the devices.  @return whether this was the first answer. */
    public boolean allow() { return answer(PermissionDecision.ALLOW); }

    /** Refuse.  @return whether this was the first answer. */
    public boolean deny() { return answer(PermissionDecision.DENY); }

    /** Let the engine decide, as with no handler.  @return whether this was
     *  the first answer. */
    public boolean useDefault() { return answer(PermissionDecision.DEFAULT); }

    /**
     * Answer the request.  {@code null} denies.
     *
     * @return {@code true} when this was the first answer and took effect
     */
    public boolean answer(PermissionDecision decision) {
        if (!answered.compareAndSet(false, true)) return false;
        answerer.answered(this, decision == null ? PermissionDecision.DENY : decision);
        return true;
    }

    /** @return whether the request has been answered. */
    public boolean isAnswered() { return answered.get(); }

    long id() { return id; }

    @Override
    public String toString() {
        return "WebViewPermissionRequest{origin=" + origin + ", url=" + url
            + ", kinds=" + kinds + "}";
    }
}
