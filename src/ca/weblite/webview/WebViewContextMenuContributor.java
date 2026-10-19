/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.util.List;

/**
 * Adds application items to the browser engine's <em>own</em> right-click
 * menu (Canvas 36 D1).  Install one with
 * {@link ca.weblite.webview.swing.WebViewComponent#addContextMenuContributor}.
 *
 * <p>The engine's menu keeps every item it would show anyway (Copy, Look Up,
 * spelling suggestions, Back, Reload, Inspect Element in debug mode, ...).
 * The items returned here are appended after them, below one separator.
 * This is the <em>augment</em> model; {@link WebViewMouseListener} is the
 * <em>replace</em> model.  While a mouse listener suppresses the engine's
 * menu (or the page calls {@code preventDefault()} on {@code contextmenu}),
 * no engine menu opens and contributors are not asked.  With
 * {@code setDefaultContextMenuEnabled(true)} both run.
 *
 * <h2>Threading</h2>
 * {@link #contribute} runs on the <strong>engine's UI thread</strong>,
 * <strong>synchronously</strong> and <strong>off the Swing EDT</strong>,
 * while the engine waits to open its menu.  It MUST be fast, thread-safe,
 * and MUST NOT touch Swing.  The action of the item the user chooses runs
 * later, on the EDT.
 */
@FunctionalInterface
public interface WebViewContextMenuContributor {

    /**
     * Decide which items to add for this right-click.
     *
     * @param context what the right-click hit; describes page content and
     *                may be shaped by page script, so treat it as untrusted
     * @return the items to append, in order; {@code null} or empty adds
     *         nothing
     */
    List<WebViewContextMenuItem> contribute(WebViewContextMenuContext context);
}
