/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Per-component hub for application items in the engine's own context menu
 * (Canvas 36 D4, D5).  Internal: public only because the Swing components
 * live in another package; not part of the supported API.
 *
 * <p>{@link #requested} is called inline on the engine's UI thread just
 * before the engine opens its menu: it builds the
 * {@link WebViewContextMenuContext}, asks every contributor, remembers the
 * resulting <em>shown menu</em> under a fresh token and returns the native
 * wire answer.  {@link #selected} is called by the native menu item the user
 * chose; only the newest shown menu can fire, and only once.  There is no
 * JavaScript path to {@link #selected}.  Neither method ever throws.
 */
public final class ContextMenuDispatcher {

    /** The engine reports a link under the pointer. */
    public static final int FLAG_LINK = 1;
    /** The engine reports an image under the pointer. */
    public static final int FLAG_IMAGE = 2;
    /** The engine reports audio or video under the pointer. */
    public static final int FLAG_MEDIA = 4;
    /** The engine reports editable content under the pointer. */
    public static final int FLAG_EDITABLE = 8;
    /** The engine reports a selection under the pointer. */
    public static final int FLAG_SELECTION = 16;
    /** The other flags and strings come from a native hit test. */
    public static final int FLAG_NATIVE = 256;

    /** How long a Canvas 9 record stays usable (D4). */
    static final long RECORD_MAX_AGE_MILLIS = 2000L;
    /** Most items one menu carries (D4). */
    static final int MAX_ITEMS = 32;

    private final WebViewComponent source;
    private final WebViewMouseDispatcher mouseDispatcher;
    private final CopyOnWriteArrayList<WebViewContextMenuContributor> contributors =
        new CopyOnWriteArrayList<WebViewContextMenuContributor>();
    private final AtomicLong nextToken = new AtomicLong(1L);
    private final AtomicReference<Shown> shown = new AtomicReference<Shown>();
    private volatile boolean disposed;

    /** One shown menu: its token, the items in native order, the context. */
    private static final class Shown {
        final long token;
        final List<WebViewContextMenuItem> items;
        final WebViewContextMenuContext context;

        Shown(long token, List<WebViewContextMenuItem> items,
              WebViewContextMenuContext context) {
            this.token = token;
            this.items = items;
            this.context = context;
        }
    }

    public ContextMenuDispatcher(WebViewComponent source,
                                 WebViewMouseDispatcher mouseDispatcher) {
        if (source == null) throw new NullPointerException("source");
        if (mouseDispatcher == null) throw new NullPointerException("mouseDispatcher");
        this.source = source;
        this.mouseDispatcher = mouseDispatcher;
    }

    /**
     * Whether the loaded native library supports contributed items (Canvas
     * 36 D8); false when it predates the feature or cannot be loaded.
     */
    public static boolean isAvailable() {
        try {
            return WebViewNative.webview_context_menu_available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Add a contributor; asked after those already registered. */
    public void add(WebViewContextMenuContributor contributor) {
        if (contributor == null) throw new NullPointerException("contributor");
        contributors.add(contributor);
    }

    /** Remove one registration of {@code contributor}; null is ignored. */
    public void remove(WebViewContextMenuContributor contributor) {
        if (contributor == null) return;
        contributors.remove(contributor);
    }

    /** @return whether any contributor is registered */
    public boolean hasContributors() {
        return !contributors.isEmpty();
    }

    /** Stop answering: later requests add nothing and choices are ignored. */
    public void dispose() {
        disposed = true;
        shown.set(null);
    }

    /**
     * The engine is about to open its menu (D4).  See
     * {@link WebViewContextMenuCallback#onContextMenuRequested}.
     *
     * @return null, or the wire answer: the token in decimal, then one
     *         element per item, {@code '1'}/{@code '0'} followed by the label
     */
    public String[] requested(int flags, String linkUrl, String imageUrl,
                              String mediaUrl, String selectionText,
                              int x, int y) {
        try {
            WebViewMouseDispatcher.Recent rec =
                mouseDispatcher.takeRecent(RECORD_MAX_AGE_MILLIS);
            if (disposed || contributors.isEmpty()) {
                shown.set(null);
                return null;
            }
            WebViewContextMenuContext ctx = buildContext(flags, linkUrl,
                imageUrl, mediaUrl, selectionText, x, y, rec);
            List<WebViewContextMenuItem> items = new ArrayList<WebViewContextMenuItem>();
            for (WebViewContextMenuContributor c : contributors) {
                List<WebViewContextMenuItem> got;
                try {
                    got = c.contribute(ctx);
                } catch (Throwable t) {
                    report(t);
                    continue;
                }
                if (got == null) continue;
                for (WebViewContextMenuItem it : got) {
                    if (it == null) continue;
                    if (items.size() >= MAX_ITEMS) break;
                    items.add(it);
                }
            }
            if (items.isEmpty()) {
                shown.set(null);
                return null;
            }
            long token = nextToken.getAndIncrement();
            shown.set(new Shown(token, Collections.unmodifiableList(items), ctx));
            String[] out = new String[items.size() + 1];
            out[0] = Long.toString(token);
            for (int i = 0; i < items.size(); i++) {
                WebViewContextMenuItem it = items.get(i);
                out[i + 1] = (it.isEnabled() ? "1" : "0") + it.label();
            }
            return out;
        } catch (Throwable t) {
            report(t);
            return null;
        }
    }

    /**
     * The user chose item {@code index} of the menu {@code token} (D5).
     * Fires the item's action on the EDT at most once, and only for the
     * newest shown menu.
     */
    public void selected(long token, int index) {
        try {
            if (disposed) return;
            Shown s = shown.get();
            if (s == null || s.token != token) return;
            if (index < 0 || index >= s.items.size()) return;
            if (!shown.compareAndSet(s, null)) return;
            final WebViewContextMenuItem item = s.items.get(index);
            if (!item.isEnabled()) return;
            final WebViewContextMenuContext ctx = s.context;
            Runnable r = new Runnable() {
                @Override public void run() {
                    if (disposed) return;
                    try {
                        item.action().accept(ctx);
                    } catch (Throwable t) {
                        report(t);
                    }
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeLater(r);
            }
        } catch (Throwable t) {
            report(t);
        }
    }

    private WebViewContextMenuContext buildContext(int flags, String linkUrl,
            String imageUrl, String mediaUrl, String selectionText,
            int x, int y, WebViewMouseDispatcher.Recent rec) {
        DomTarget target = rec == null ? null : rec.event.target();
        String link;
        String image;
        String media;
        boolean editable;
        String selection;
        if ((flags & FLAG_NATIVE) != 0) {
            link = emptyToNull(linkUrl);
            image = emptyToNull(imageUrl);
            media = emptyToNull(mediaUrl);
            editable = (flags & FLAG_EDITABLE) != 0;
            if (selectionText != null) {
                selection = selectionText;
            } else if ((flags & FLAG_SELECTION) != 0) {
                selection = recordSelection(rec);
            } else {
                selection = "";
            }
        } else if (target != null) {
            link = emptyToNull(target.linkHref());
            image = emptyToNull(target.imageSrc());
            media = emptyToNull(target.mediaSrc());
            editable = target.isContentEditable();
            selection = recordSelection(rec);
        } else {
            link = null;
            image = null;
            media = null;
            editable = false;
            selection = "";
        }
        Point point = null;
        if (rec != null) {
            point = rec.event.toComponentPoint();
        } else if (x >= 0 && y >= 0) {
            point = new Point(x, y);
        }
        Rectangle bounds = null;
        if (!selection.isEmpty() && rec != null && rec.selectionBounds != null
                && rec.selectionBounds.width > 0 && rec.selectionBounds.height > 0) {
            bounds = rec.selectionBounds;
        }
        return new WebViewContextMenuContext(source, selection, link, image,
            media, editable, point, bounds, target);
    }

    private static String recordSelection(WebViewMouseDispatcher.Recent rec) {
        if (rec == null) return "";
        String s = rec.event.target().selectionText();
        if (s != null && !s.isEmpty()) return s;
        return rec.controlSelection;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static void report(Throwable t) {
        Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
        if (h != null) {
            try { h.uncaughtException(Thread.currentThread(), t); }
            catch (Throwable ignored) {}
        } else {
            t.printStackTrace();
        }
    }
}
