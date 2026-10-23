/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.util.function.Consumer;

/**
 * One item an application adds to the engine's own right-click menu
 * (Canvas 36 D2).  Returned from
 * {@link WebViewContextMenuContributor#contribute}.
 *
 * <p>Immutable.  The {@linkplain #label() label} is plain text and is shown
 * exactly as written on every platform: no mnemonics, no markup.  The
 * {@linkplain #id() id} belongs to the caller; the library never
 * interprets it.  A {@linkplain #isEnabled() disabled} item is shown
 * greyed and can never fire.
 *
 * <p>When the user chooses the item, its {@linkplain #action() action}
 * runs on the Swing EDT with the {@link WebViewContextMenuContext} of the
 * right-click that opened the menu.
 */
public final class WebViewContextMenuItem {

    private final String id;
    private final String label;
    private final boolean enabled;
    private final Consumer<WebViewContextMenuContext> action;

    private WebViewContextMenuItem(String id, String label, boolean enabled,
                                   Consumer<WebViewContextMenuContext> action) {
        this.id = id;
        this.label = label;
        this.enabled = enabled;
        this.action = action;
    }

    /**
     * Create an enabled item.
     *
     * @param id     the caller's identifier for the item; non-null
     * @param label  the plain-text label; non-null, non-empty, no U+0000
     * @param action run on the EDT when the item is chosen; non-null
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if the label is empty or contains U+0000
     */
    public static WebViewContextMenuItem of(String id, String label,
            Consumer<WebViewContextMenuContext> action) {
        if (id == null) throw new NullPointerException("id");
        if (label == null) throw new NullPointerException("label");
        if (action == null) throw new NullPointerException("action");
        if (label.isEmpty()) {
            throw new IllegalArgumentException("label must not be empty");
        }
        if (label.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("label must not contain U+0000");
        }
        return new WebViewContextMenuItem(id, label, true, action);
    }

    /** @return a copy of this item, enabled or disabled */
    public WebViewContextMenuItem withEnabled(boolean enabled) {
        return new WebViewContextMenuItem(id, label, enabled, action);
    }

    /** @return the caller's identifier */
    public String id() { return id; }

    /** @return the plain-text label */
    public String label() { return label; }

    /** @return whether the item can be chosen */
    public boolean isEnabled() { return enabled; }

    /** @return the action run on the EDT when the item is chosen */
    public Consumer<WebViewContextMenuContext> action() { return action; }

    @Override
    public String toString() {
        return "WebViewContextMenuItem[" + id + " \"" + label + "\""
            + (enabled ? "" : " disabled") + "]";
    }
}
