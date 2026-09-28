/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable event describing an imminent autofill, handed to the
 * {@link WebViewFillPasswordHandler} to decide whether the stored
 * credential should be injected into the page.
 *
 * <p>The {@code origin} is the canonical scheme+host+port derived
 * natively from the committed frame URL — never a value supplied by page
 * JavaScript.
 *
 * <p><strong>This event deliberately carries no password.</strong> The
 * autofill-consent decision needs only the identity being filled
 * (origin + username), so the secret is never exposed to host consent
 * code — including a handler that performs an OS biometric /
 * re-authentication check.
 */
public final class WebViewFillPasswordEvent {

    private final WebViewComponent source;
    private final String origin;
    private final String username;

    WebViewFillPasswordEvent(WebViewComponent source, String origin,
                             String username) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.origin = origin == null ? "" : origin;
        this.username = username == null ? "" : username;
    }

    /** @return the component whose page is about to be autofilled. */
    public WebViewComponent source() { return source; }

    /** @return the canonical origin (scheme+host+port) being filled. */
    public String origin() { return origin; }

    /** @return the username of the credential being filled (may be empty). */
    public String username() { return username; }

    /** @return a debug string; there is no password to redact. */
    @Override
    public String toString() {
        return "WebViewFillPasswordEvent[origin=" + origin
            + ", username=" + username + "]";
    }
}
