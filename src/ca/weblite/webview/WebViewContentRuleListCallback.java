/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Native-facing completion for the content rule list store (Canvas 37 D4).
 * The native layer calls {@link #onComplete} exactly once per compile,
 * look-up or remove, on a native thread attached to the JVM, never inside
 * the JNI call that started the operation.  Implementations must not throw.
 */
public interface WebViewContentRuleListCallback {

    /**
     * @param ok    {@code true} when the operation succeeded (a look-up that
     *              found nothing also succeeded)
     * @param found {@code false} only when a look-up or remove found no
     *              list under the identifier
     * @param error the engine's reason when {@code ok} is {@code false},
     *              else {@code null} or empty
     */
    void onComplete(boolean ok, boolean found, String error);
}
