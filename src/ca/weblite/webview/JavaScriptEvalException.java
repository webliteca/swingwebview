/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Thrown to surface a JavaScript-side failure from
 * {@code evalAsync(String)} as the cause of a
 * {@link java.util.concurrent.CompletableFuture}'s exceptional
 * completion.
 *
 * <p>One of three JS-originated failure modes:
 * <ul>
 *   <li>Synchronous {@code throw} inside the user snippet.</li>
 *   <li>Rejection of a returned {@code Promise}.</li>
 *   <li>{@code TypeError} from {@code JSON.stringify} on the
 *       resolved value (unserializable types: circular references,
 *       host objects in some engines).</li>
 * </ul>
 *
 * <p>The {@link #getMessage()} text is the JS-side error message
 * verbatim (typically {@code error.message} or {@code String(error)}
 * when no {@code message} is present). It contains no Java-side
 * stack frames or peer pointers.
 *
 * <p>Lifecycle violations (calling {@code evalAsync} before
 * {@code show()} on a standalone {@code WebView}, before display on
 * a {@code WebViewComponent}, or after dispose) surface as
 * {@link IllegalStateException} instead, so callers can
 * {@code instanceof}-distinguish "the page said no" from "we have
 * no page".
 *
 * <p>Retrieval from a {@code CompletableFuture}: continuations
 * registered via {@code .exceptionally} or {@code .handle} receive a
 * {@code java.util.concurrent.CompletionException} whose
 * {@link Throwable#getCause()} is a {@code JavaScriptEvalException}.
 * Callers commonly write:
 * <pre>
 *   future.exceptionally(t -&gt; {
 *       Throwable cause = t.getCause();
 *       if (cause instanceof JavaScriptEvalException) {
 *           // ... handle JS-side failure ...
 *       }
 *       return fallback;
 *   });
 * </pre>
 */
public class JavaScriptEvalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JavaScriptEvalException(String message) {
        super(message);
    }
}
