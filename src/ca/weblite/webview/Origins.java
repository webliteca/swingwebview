/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * <p><strong>Internal:</strong> the single definition of "origin" used
 * throughout the password-manager feature.  An origin is
 * {@code scheme://host} plus a port only when that port is non-default
 * for the scheme.  Scheme and host are lower-cased; path, query, and
 * fragment are dropped; there is no trailing slash.
 *
 * <p>This is the credential key and the anti-phishing boundary: a
 * credential stored for one origin is only ever offered on that exact
 * origin.  Both {@link WebViewCredentialStore} implementations and
 * {@link PasswordDispatcher} canonicalise through this class so equal
 * origins collapse identically everywhere.
 */
final class Origins {

    private Origins() { }

    /**
     * Canonicalise a URL or origin string to {@code scheme://host[:port]}.
     *
     * @return the canonical origin, or {@code null} when the input is
     *         null/blank, opaque, or hostless (e.g. {@code about:blank},
     *         {@code data:...}) — callers skip a null origin.
     */
    static String canonical(String urlOrOrigin) {
        if (urlOrOrigin == null) return null;
        String trimmed = urlOrOrigin.trim();
        if (trimmed.isEmpty()) return null;
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (host.isEmpty()) return null;
            int port = uri.getPort();
            int def = defaultPort(scheme);
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            if (port != -1 && port != def) {
                sb.append(':').append(port);
            }
            return sb.toString();
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static int defaultPort(String scheme) {
        if ("http".equals(scheme) || "ws".equals(scheme)) return 80;
        if ("https".equals(scheme) || "wss".equals(scheme)) return 443;
        return -1;
    }
}
