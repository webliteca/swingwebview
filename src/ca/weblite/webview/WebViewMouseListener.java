/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Listener for DOM mouse events observed by an embedded
 * {@code WebViewComponent}.  Registered via
 * {@code WebViewComponent.addWebViewMouseListener}.
 *
 * <p>Currently only {@link #contextMenuRequested(WebViewMouseEvent)} fires;
 * future event kinds ({@code mousePressed}, {@code mouseClicked},
 * {@code dragStarted}, &hellip;) will be added as {@code default} methods on
 * this interface so callers compiled against today's contract continue to
 * work unchanged.
 *
 * <p><strong>Threading.</strong> All callbacks are invoked on the Swing Event
 * Dispatch Thread.  Implementations may touch Swing state directly.  Listener
 * work that may block for non-trivial time should be pushed to an
 * application-owned executor &mdash; the same advice that applies to every
 * Swing listener.
 *
 * <p><strong>Exception isolation.</strong> Exceptions thrown from this
 * callback are caught by the dispatcher and forwarded to
 * {@link Thread#getDefaultUncaughtExceptionHandler()}.  They never reach the
 * native engine and never break the fan-out to subsequent listeners.
 *
 * <p><strong>Default-menu suppression.</strong> Registering any listener
 * automatically suppresses the platform's built-in context menu.  Callers
 * that want the platform default back must explicitly call
 * {@code WebViewComponent.setDefaultContextMenuEnabled(true)}; the listener
 * still fires in that mode.
 */
@FunctionalInterface
public interface WebViewMouseListener {

    /**
     * Invoked once per DOM {@code contextmenu} event observed in the embedded
     * page.  The event carries the click position in viewport / page /
     * screen coordinates plus a {@link DomTarget} descriptor of the element
     * under the cursor.
     *
     * @param event a non-null event describing the right-click.
     */
    void contextMenuRequested(WebViewMouseEvent event);
}
