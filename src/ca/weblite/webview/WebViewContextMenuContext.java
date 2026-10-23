/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.awt.Point;
import java.awt.Rectangle;
import javax.swing.SwingUtilities;

/**
 * What a right-click that opens the engine's own menu hit (Canvas 36 D3).
 * Handed to {@link WebViewContextMenuContributor#contribute} and to the
 * action of the {@link WebViewContextMenuItem} the user chooses.
 *
 * <p>Immutable.  Strings documented as never-null are {@code ""} when there
 * is nothing; the others are {@code null}.  Points and rectangles are in the
 * component's coordinates (page viewport CSS pixels, as
 * {@link WebViewMouseEvent#toComponentPoint()}), and each accessor returns
 * a fresh copy.
 *
 * <p><strong>Untrusted data.</strong> Every field describes page content and
 * may be shaped by page script (a page can change its selection, its links,
 * or the record the library reads from it).  Treat the values as untrusted
 * input.
 */
public final class WebViewContextMenuContext {

    private final WebViewComponent source;
    private final String selectionText;
    private final String linkUrl;
    private final String imageUrl;
    private final String mediaUrl;
    private final boolean editable;
    private final Point point;
    private final Rectangle selectionBounds;
    private final DomTarget target;

    WebViewContextMenuContext(WebViewComponent source, String selectionText,
                              String linkUrl, String imageUrl, String mediaUrl,
                              boolean editable, Point point,
                              Rectangle selectionBounds, DomTarget target) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.selectionText = selectionText == null ? "" : selectionText;
        this.linkUrl = linkUrl;
        this.imageUrl = imageUrl;
        this.mediaUrl = mediaUrl;
        this.editable = editable;
        this.point = point == null ? null : new Point(point);
        this.selectionBounds = selectionBounds == null ? null : new Rectangle(selectionBounds);
        this.target = target;
    }

    /** @return the component whose menu this is */
    public WebViewComponent source() { return source; }

    /** @return the selected text; never null, {@code ""} when none */
    public String selectionText() { return selectionText; }

    /** @return whether there is selected text */
    public boolean hasSelection() { return !selectionText.isEmpty(); }

    /** @return the link under the pointer, or null */
    public String linkUrl() { return linkUrl; }

    /** @return the image under the pointer, or null */
    public String imageUrl() { return imageUrl; }

    /** @return the audio or video under the pointer, or null */
    public String mediaUrl() { return mediaUrl; }

    /** @return whether the click was in editable content */
    public boolean isEditable() { return editable; }

    /** @return the click in component coordinates, or null when unknown */
    public Point point() { return point == null ? null : new Point(point); }

    /**
     * @return the selection's rectangle in component coordinates, or null
     *         when there is no selection or it is unknown
     */
    public Rectangle selectionBounds() {
        return selectionBounds == null ? null : new Rectangle(selectionBounds);
    }

    /**
     * @return the Canvas 9 descriptor of the clicked element, or null when
     *         the page's right-click record was not available
     */
    public DomTarget target() { return target; }

    /**
     * The click on screen, via
     * {@link SwingUtilities#convertPointToScreen}.  Call on the EDT.
     *
     * @return the screen point, or null when {@link #point()} is null or the
     *         component is not showing
     */
    public Point toScreenPoint() {
        if (point == null || !source.isShowing()) return null;
        Point p = new Point(point);
        SwingUtilities.convertPointToScreen(p, source);
        return p;
    }

    @Override
    public String toString() {
        return "WebViewContextMenuContext[selection=" + selectionText.length()
            + " chars, link=" + linkUrl + ", image=" + imageUrl
            + ", media=" + mediaUrl + ", editable=" + editable
            + ", point=" + (point == null ? "null" : point.x + "," + point.y)
            + ", bounds=" + (selectionBounds == null ? "null"
                : selectionBounds.x + "," + selectionBounds.y + " "
                  + selectionBounds.width + "x" + selectionBounds.height)
            + "]";
    }
}
