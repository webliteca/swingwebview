/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Immutable {@code {origin, username, password}} credential handled by
 * the WebView password manager.
 *
 * <p>Equality and hashing are on {@code {origin, username}} only — the
 * password is excluded, so re-saving a new password for the same
 * {@code {origin, username}} is treated as the same logical credential
 * (an overwrite, not a duplicate).
 *
 * <p>The {@code origin} is stored verbatim as supplied; canonicalisation
 * (dropping the default port, lower-casing) happens at the store
 * boundary, so callers may construct a credential with any origin string
 * and the store normalises it consistently.
 *
 * <p>{@link #toString()} never emits the password.
 */
public final class WebViewCredential {

    private final String origin;
    private final String username;
    private final String password;

    /**
     * @param origin   the page origin the credential belongs to
     *                 (scheme+host+port); never {@code null}
     * @param username the username; never {@code null} (may be empty for
     *                 password-only forms)
     * @param password the password; never {@code null}
     */
    public WebViewCredential(String origin, String username, String password) {
        if (origin == null) throw new NullPointerException("origin");
        if (username == null) throw new NullPointerException("username");
        if (password == null) throw new NullPointerException("password");
        this.origin = origin;
        this.username = username;
        this.password = password;
    }

    /** @return the page origin (scheme+host+port). */
    public String origin() { return origin; }

    /** @return the username. */
    public String username() { return username; }

    /** @return the password. */
    public String password() { return password; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebViewCredential)) return false;
        WebViewCredential that = (WebViewCredential) o;
        return origin.equals(that.origin) && username.equals(that.username);
    }

    @Override
    public int hashCode() {
        return 31 * origin.hashCode() + username.hashCode();
    }

    /** @return a debug string that <strong>redacts the password</strong>. */
    @Override
    public String toString() {
        return "WebViewCredential[origin=" + origin
            + ", username=" + username + ", password=***]";
    }
}
