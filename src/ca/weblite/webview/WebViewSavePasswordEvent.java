/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

/**
 * Immutable event describing a login submission the password manager
 * captured inside the embedded page, handed to the
 * {@link WebViewSavePasswordHandler} to decide whether to save it.
 *
 * <p>The {@code origin} is the canonical scheme+host+port derived
 * natively from the committed frame URL — never a value supplied by page
 * JavaScript.
 *
 * <p>{@link #toString()} never emits the password.
 */
public final class WebViewSavePasswordEvent {

    private final WebViewComponent source;
    private final String origin;
    private final String username;
    private final String password;

    WebViewSavePasswordEvent(WebViewComponent source, String origin,
                             String username, String password) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
        this.origin = origin == null ? "" : origin;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    /** @return the component whose page raised the submission. */
    public WebViewComponent source() { return source; }

    /** @return the canonical origin (scheme+host+port) of the submission. */
    public String origin() { return origin; }

    /** @return the captured username (may be empty). */
    public String username() { return username; }

    /** @return the captured password. */
    public String password() { return password; }

    /** @return a debug string that <strong>redacts the password</strong>. */
    @Override
    public String toString() {
        return "WebViewSavePasswordEvent[origin=" + origin
            + ", username=" + username + ", password=***]";
    }
}
