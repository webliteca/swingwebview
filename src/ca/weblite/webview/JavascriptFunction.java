/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction.
 */
package ca.weblite.webview;

/**
 * A Java-backed JavaScript function with a <em>synchronous</em> handler.
 *
 * <p>Register one with
 * {@code addJavascriptFunction(String name, JavascriptFunction fn)} and it
 * appears in the page as a global that returns a Promise:
 *
 * <pre>{@code
 * webView.addJavascriptFunction("greet", arg -> "Hello, " + arg + "!");
 * // in the page:  const msg = await window.greet("world");  // "Hello, world!"
 * }</pre>
 *
 * <p><strong>Threading.</strong> The library invokes {@link #run(String)} on a
 * background worker thread &mdash; never on the engine's UI thread &mdash; so a
 * slow handler cannot freeze the UI or deadlock the engine against the Swing
 * EDT. You may do blocking work here.
 *
 * <p><strong>Result.</strong> The returned {@code String} resolves the page-side
 * Promise verbatim (string passthrough). For structured data, return JSON text
 * and {@code JSON.parse} it in the page. A {@code null} return resolves to the
 * empty string.
 *
 * <p><strong>Errors.</strong> Throwing any exception rejects the page-side
 * Promise with an {@code Error} carrying this exception's message.
 *
 * <p>For inherently-asynchronous work (where the handler itself awaits a
 * future), use {@link AsyncJavascriptFunction} instead.
 */
@FunctionalInterface
public interface JavascriptFunction {

    /**
     * Compute the result for one page-side invocation.
     *
     * @param arg the single string argument the JavaScript caller passed
     *            (never {@code null}; the empty string when the caller passed
     *            {@code null}/{@code undefined}).
     * @return the value the page-side Promise resolves to; {@code null} is
     *         treated as the empty string.
     * @throws Exception any failure; the page-side Promise rejects with an
     *         {@code Error} carrying {@link Throwable#getMessage()}.
     */
    String run(String arg) throws Exception;
}
