/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;
import java.awt.Point;

/**
 * Immutable value object describing a single DOM mouse event observed by an
 * embedded {@code WebViewComponent}.  Delivered to {@link WebViewMouseListener}
 * callbacks on the Swing Event Dispatch Thread.
 *
 * <p>This release surfaces only {@code contextmenu} events; the field set is
 * designed so future event kinds ({@code mousedown}, {@code dblclick},
 * {@code dragstart}, &hellip;) can be added without expanding the type.
 *
 * <p>Coordinate frames:
 * <ul>
 *   <li>{@link #clientX()}/{@link #clientY()} &mdash; CSS pixels relative to
 *       the page viewport.  These are also the Swing component-relative
 *       coordinates of the click on every supported platform (the WebView
 *       region's AWT coordinate space matches the page viewport 1:1 in
 *       logical pixels).</li>
 *   <li>{@link #pageX()}/{@link #pageY()} &mdash; CSS pixels relative to the
 *       top-left of the document, accounting for scroll position.</li>
 *   <li>{@link #screenX()}/{@link #screenY()} &mdash; CSS pixels relative to
 *       the screen origin.</li>
 * </ul>
 *
 * <p>Use {@link #toComponentPoint()} when calling
 * {@code JPopupMenu.show(Component, int, int)} against {@link #source()}, and
 * {@link #toScreenPoint()} when an absolute-screen anchor is needed.
 */
public final class WebViewMouseEvent {

    /** The DOM {@code contextmenu} event type string. */
    public static final String EVENT_CONTEXT_MENU = "contextmenu";

    private final String type;
    private final int button;
    private final int modifierBits;
    private final int clientX;
    private final int clientY;
    private final int pageX;
    private final int pageY;
    private final int screenX;
    private final int screenY;
    private final long timeStamp;
    private final DomTarget target;
    private final WebViewComponent source;

    WebViewMouseEvent(String type, int button, int modifierBits,
                      int clientX, int clientY,
                      int pageX, int pageY,
                      int screenX, int screenY,
                      long timeStamp,
                      DomTarget target, WebViewComponent source) {
        if (type == null) throw new NullPointerException("type");
        if (target == null) throw new NullPointerException("target");
        if (source == null) throw new NullPointerException("source");
        if (button < 1 || button > 3) {
            throw new IllegalArgumentException(
                "button must be 1, 2, or 3 (got " + button + ")");
        }
        this.type = type;
        this.button = button;
        this.modifierBits = modifierBits;
        this.clientX = clientX;
        this.clientY = clientY;
        this.pageX = pageX;
        this.pageY = pageY;
        this.screenX = screenX;
        this.screenY = screenY;
        this.timeStamp = timeStamp;
        this.target = target;
        this.source = source;
    }

    /** @return the DOM event type, currently always {@value #EVENT_CONTEXT_MENU}. */
    public String type() { return type; }

    /** @return the mouse button: 1 = primary, 2 = middle, 3 = secondary. */
    public int button() { return button; }

    /** @return {@code true} when Shift was held during the event. */
    public boolean isShiftDown() { return (modifierBits & 1) != 0; }

    /** @return {@code true} when Ctrl was held during the event. */
    public boolean isCtrlDown() { return (modifierBits & 2) != 0; }

    /** @return {@code true} when Alt (or Option on macOS) was held. */
    public boolean isAltDown() { return (modifierBits & 4) != 0; }

    /** @return {@code true} when Meta (Command on macOS, Windows key on Windows). */
    public boolean isMetaDown() { return (modifierBits & 8) != 0; }

    /** @return viewport-relative CSS-pixel X. */
    public int clientX() { return clientX; }
    /** @return viewport-relative CSS-pixel Y. */
    public int clientY() { return clientY; }

    /** @return document-relative CSS-pixel X (accounts for scroll). */
    public int pageX() { return pageX; }
    /** @return document-relative CSS-pixel Y. */
    public int pageY() { return pageY; }

    /**
     * @return screen-relative CSS-pixel X.
     *
     * <p>On macOS WKWebView 12.x and earlier this value may be unreliable
     * (some versions report viewport coordinates here).  When that's
     * a concern, prefer
     * {@code SwingUtilities.convertPointToScreen(event.toComponentPoint(), event.source())}.
     */
    public int screenX() { return screenX; }
    /** @return screen-relative CSS-pixel Y; see {@link #screenX()} for caveats. */
    public int screenY() { return screenY; }

    /**
     * @return the DOM event's timestamp truncated to a whole millisecond.
     * Monotonic; origin is the top-level document's time origin (typically
     * navigation start), NOT wall-clock.
     */
    public long timeStamp() { return timeStamp; }

    /** @return descriptor of the DOM element under the cursor; never null. */
    public DomTarget target() { return target; }

    /** @return the component the event originated from; never null. */
    public WebViewComponent source() { return source; }

    /**
     * @return a new {@link Point} containing {@link #clientX()} and
     * {@link #clientY()}, suitable for
     * {@code JPopupMenu.show(event.source(), p.x, p.y)}.  Heavyweight callers
     * must set {@code JPopupMenu.setDefaultLightWeightPopupEnabled(false)} at
     * startup or the popup will paint behind the native WebView surface.
     */
    public Point toComponentPoint() {
        return new Point(clientX, clientY);
    }

    /**
     * @return a new {@link Point} containing {@link #screenX()} and
     * {@link #screenY()}.  See {@link #screenX()} for the macOS reliability
     * caveat.
     */
    public Point toScreenPoint() {
        return new Point(screenX, screenY);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("WebViewMouseEvent[").append(type)
          .append(" button=").append(button)
          .append(" at (").append(clientX).append(',').append(clientY)
          .append(") target=").append(target.tagName());
        if (!target.id().isEmpty()) {
            sb.append('#').append(target.id());
        }
        sb.append(']');
        return sb.toString();
    }
}
