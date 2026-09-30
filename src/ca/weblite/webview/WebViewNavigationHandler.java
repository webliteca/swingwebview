/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Application hook deciding whether a page may navigate (Canvas 34 D1).
 * Install one via
 * {@link ca.weblite.webview.swing.WebViewComponent#setNavigationHandler}.
 *
 * <h2>What it is asked about</h2>
 * Every navigation of the component's view <em>and of its frames</em>,
 * before the engine sends any request: links (including {@code download}
 * links), script navigation ({@code location.href}, {@code assign},
 * {@code replace}), forms, meta refreshes, back/forward, reload, server
 * redirects, and a frame's first page — including {@code about:blank} and
 * {@code about:srcdoc}, which a handler that restricts addresses usually
 * wants to allow.  New windows are not asked about here; the
 * {@link WebViewPopupHandler} decides those.
 *
 * <p>A refused navigation never starts: nothing is sent, and the page stays
 * as it was, with no error page.  This is what a page's content security
 * policy cannot do — it confines what a page loads, not where the page
 * goes.
 *
 * <h2>Threading</h2>
 * {@link #navigationRequested} runs on the <strong>native UI
 * thread</strong>, <strong>synchronously</strong> and <strong>off the Swing
 * EDT</strong>: the engine needs the decision before it can continue, and
 * cannot round-trip to the EDT without risking a deadlock.
 * Implementations MUST be fast, thread-safe, and MUST NOT touch Swing
 * state.
 *
 * <h2>Failure</h2>
 * A handler that throws refuses the navigation; the throwable goes to the
 * default uncaught-exception handler.  The {@linkplain #DEFAULT default}
 * allows everything, which is the behaviour without this feature; passing
 * {@code null} to {@code setNavigationHandler} installs it.
 */
public interface WebViewNavigationHandler {

    /**
     * Decide whether a navigation may proceed.  Runs on the native UI
     * thread, synchronously, off the EDT — keep it fast and free of Swing
     * access.
     *
     * @param event the navigation
     * @return {@code true} to let it proceed, {@code false} to refuse it
     */
    boolean navigationRequested(WebViewNavigationEvent event);

    /** The framework default: allows every navigation.  Stateless; safe to
     *  share across components and threads. */
    WebViewNavigationHandler DEFAULT = new WebViewNavigationHandler() {
        @Override
        public boolean navigationRequested(WebViewNavigationEvent event) {
            return true;
        }
    };
}
