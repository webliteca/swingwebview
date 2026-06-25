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

import java.util.concurrent.CompletableFuture;

/**
 * A Java-backed JavaScript function with an <em>asynchronous</em> handler.
 *
 * <p>Register one with
 * {@code addJavascriptFunction(String name, AsyncJavascriptFunction fn)} and it
 * appears in the page as a global that returns a Promise:
 *
 * <pre>{@code
 * webView.addJavascriptFunction("lookup", arg ->
 *     myService.lookupAsync(arg));            // returns CompletableFuture<String>
 * // in the page:  const row = await window.lookup("42");
 * }</pre>
 *
 * <p>Use this overload when the work is already asynchronous (the handler
 * returns a future rather than blocking). For ordinary blocking work, prefer
 * {@link JavascriptFunction}, which the library runs on a worker thread for
 * you.
 *
 * <p><strong>Threading.</strong> {@link #run(String)} is invoked on the
 * engine's binding-callback thread and MUST return promptly with a future
 * &mdash; it MUST NOT block. The library never calls {@code get()} on the
 * returned future; it attaches a completion callback, so the round trip stays
 * deadlock-free.
 *
 * <p><strong>Result.</strong> When the future completes normally its
 * {@code String} value resolves the page-side Promise (string passthrough; a
 * {@code null} value resolves to the empty string). When it completes
 * exceptionally, the page-side Promise rejects with an {@code Error} carrying
 * the exception message.
 */
@FunctionalInterface
public interface AsyncJavascriptFunction {

    /**
     * Begin computing the result for one page-side invocation.
     *
     * @param arg the single string argument the JavaScript caller passed
     *            (never {@code null}).
     * @return a future that completes with the value the page-side Promise
     *         resolves to, or completes exceptionally to reject it. Returning
     *         {@code null} resolves the Promise to the empty string.
     */
    CompletableFuture<String> run(String arg);
}
