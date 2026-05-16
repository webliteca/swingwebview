/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Receives structured {@link ConsoleMessage} events captured from the
 * embedded page's JavaScript {@code console.*} calls.  Register listeners
 * on a {@code WebViewComponent} via
 * {@code addConsoleListener(ConsoleListener)}.
 *
 * <p>Callbacks are dispatched on the Swing Event Dispatch Thread so
 * implementations may touch Swing state directly.  A thrown exception
 * does not stop other registered listeners from receiving the same
 * message; it is routed through
 * {@code Thread.getDefaultUncaughtExceptionHandler()}.
 */
public interface ConsoleListener {

    /**
     * Called once per {@code console.log/info/warn/error/debug} call from
     * the embedded page.
     */
    void onMessage(ConsoleMessage message);
}
