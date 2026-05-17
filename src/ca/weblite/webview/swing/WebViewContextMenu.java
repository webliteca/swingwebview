/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview.swing;

import ca.weblite.webview.WebViewMouseEvent;
import ca.weblite.webview.WebViewMouseListener;

import java.awt.Point;
import java.util.function.Function;
import javax.swing.JPopupMenu;

/**
 * Convenience helper that turns each right-click on a {@link WebViewComponent}
 * into a Swing {@link JPopupMenu} shown at the click point.
 *
 * <p>Construct with a builder {@code Function} that returns the
 * {@code JPopupMenu} to show for a given event &mdash; or {@code null} to
 * suppress the popup for that event &mdash; then call {@link #attachTo} to
 * wire the helper to a component.  {@link #detach} removes the underlying
 * mouse-listener subscription.
 *
 * <p><strong>Heavyweight popup prerequisite.</strong> When the underlying
 * component is a {@link WebViewHeavyweightComponent}, lightweight Swing
 * popups paint <em>behind</em> the heavyweight native peer.  Callers MUST
 * set
 * {@link JPopupMenu#setDefaultLightWeightPopupEnabled(boolean)
 * JPopupMenu.setDefaultLightWeightPopupEnabled(false)} at application
 * startup (and ideally also
 * {@code ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false)})
 * so the popup uses a heavyweight top-level and paints above the WebView.
 * See {@code WebViewContextMenuDemo} for the canonical setup.
 *
 * <p><strong>Auto-suppression.</strong> Attaching this helper registers a
 * {@link WebViewMouseListener}, which automatically suppresses the
 * platform's built-in context menu for as long as the helper stays
 * attached.  Detaching reverses the registration; the platform default
 * reappears unless another listener is still registered separately.
 *
 * <p>Example:
 * <pre>{@code
 * JPopupMenu.setDefaultLightWeightPopupEnabled(false);
 * WebViewComponent wv = WebViewComponent.create();
 * new WebViewContextMenu(event -> {
 *     JPopupMenu m = new JPopupMenu();
 *     if (event.target().linkHref() != null) {
 *         m.add(new JMenuItem("Open Link"));
 *     }
 *     m.add(new JMenuItem("Inspect..."));
 *     return m;
 * }).attachTo(wv);
 * }</pre>
 */
public final class WebViewContextMenu {

    private final Function<WebViewMouseEvent, JPopupMenu> menuBuilder;
    private WebViewComponent attached;
    private WebViewMouseListener listener;

    /**
     * @param menuBuilder builder that constructs a {@link JPopupMenu} for
     * each event.  Return {@code null} to suppress the popup for that
     * event; the platform default remains suppressed (suppression is
     * driven by the underlying listener registration, not by what the
     * builder returns).  Must not be {@code null} itself.
     */
    public WebViewContextMenu(Function<WebViewMouseEvent, JPopupMenu> menuBuilder) {
        if (menuBuilder == null) throw new NullPointerException("menuBuilder");
        this.menuBuilder = menuBuilder;
    }

    /**
     * Wire this helper to {@code component}.  Each subsequent right-click
     * on {@code component} invokes the builder and, when the builder
     * returns non-null, shows the menu at the click point.
     *
     * @throws NullPointerException if {@code component} is {@code null}.
     * @throws IllegalStateException if this helper instance has already
     * been attached; construct a fresh {@code WebViewContextMenu} to
     * attach to a different component.
     */
    public WebViewContextMenu attachTo(WebViewComponent component) {
        if (component == null) throw new NullPointerException("component");
        if (attached != null) {
            throw new IllegalStateException(
                "WebViewContextMenu is already attached.");
        }
        listener = new WebViewMouseListener() {
            @Override
            public void contextMenuRequested(WebViewMouseEvent event) {
                JPopupMenu menu = menuBuilder.apply(event);
                if (menu == null) return;
                Point p = event.toComponentPoint();
                menu.show(event.source(), p.x, p.y);
            }
        };
        component.addWebViewMouseListener(listener);
        attached = component;
        return this;
    }

    /**
     * Remove the underlying mouse-listener subscription.  Idempotent: a
     * second call after detach is a silent no-op.  After detach, this
     * helper instance cannot be re-attached &mdash; construct a new
     * instance if needed.
     */
    public void detach() {
        if (attached == null) return;
        attached.removeWebViewMouseListener(listener);
        attached = null;
        listener = null;
    }

    /** @return {@code true} when {@link #attachTo} has run and {@link #detach}
     * has not. */
    public boolean isAttached() {
        return attached != null;
    }
}
