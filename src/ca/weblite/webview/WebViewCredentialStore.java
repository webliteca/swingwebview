/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.util.List;
import java.util.Optional;

/**
 * Storage seam for WebView credentials.  The default implementation,
 * {@link NativeCredentialStore}, is backed by the OS-native secret store
 * (macOS Keychain, Linux libsecret, Windows Credential Manager).  A host
 * may install a different implementation — for example
 * {@link InMemoryCredentialStore} for tests — via
 * {@link ca.weblite.webview.swing.WebViewComponent#setCredentialStore}.
 *
 * <p>Implementations must be safe to call from any thread and must not
 * throw when the backing store is unavailable — they degrade to
 * "no result" ({@code find}/{@code findAll} empty, {@code save}/
 * {@code delete} a no-op) rather than propagating an exception.
 */
public interface WebViewCredentialStore {

    /**
     * Insert or overwrite the credential for its {@code {origin,
     * username}}.  A previously stored password for the same
     * {@code {origin, username}} is replaced.
     */
    void save(WebViewCredential credential);

    /**
     * @return the most-recently-saved credential for {@code origin}, or
     *         empty if none is stored.
     */
    Optional<WebViewCredential> find(String origin);

    /**
     * @return every credential stored for {@code origin},
     *         most-recently-saved first, as an unmodifiable list (empty
     *         when none).
     */
    List<WebViewCredential> findAll(String origin);

    /**
     * Remove the credential for {@code {origin, username}}.
     *
     * @return whether a credential was actually removed.
     */
    boolean delete(String origin, String username);
}
