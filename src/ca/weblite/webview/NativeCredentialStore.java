/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The default {@link WebViewCredentialStore}, backed by the OS-native
 * secret store through process-global JNI primitives — macOS Keychain in
 * this iteration; Linux libsecret and Windows Credential Manager in the
 * follow-up canvases.
 *
 * <p>Credentials are keyed by canonical origin (see {@link Origins}).
 * Every record carries a {@code savedAtMillis} timestamp stored alongside
 * the password so recency ordering ({@code find} returns the
 * most-recently-saved) is uniform across all platforms regardless of what
 * metadata the native store keeps.
 *
 * <p>All operations degrade gracefully: if the native store is
 * unavailable or errors, {@code save}/{@code delete} become no-ops and
 * {@code find}/{@code findAll} return empty — never a thrown exception,
 * never a logged password.
 */
public final class NativeCredentialStore implements WebViewCredentialStore {

    /** Library namespace isolating these items from any other secret-store
     *  entries (e.g. Safari's iCloud Keychain items). */
    static final String SERVICE = "ca.weblite.webview.passwords";

    /** @return whether the platform secret store is usable. */
    public boolean isAvailable() {
        try {
            return WebViewNative.webview_cred_store_available();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void save(WebViewCredential credential) {
        if (credential == null) return;
        String origin = Origins.canonical(credential.origin());
        if (origin == null) return;
        try {
            WebViewNative.webview_cred_store_save(SERVICE, origin,
                credential.username(), credential.password(),
                System.currentTimeMillis());
        } catch (Throwable t) {
            // Store unavailable / errored: degrade to no-op.  Never log
            // the password.
        }
    }

    @Override
    public Optional<WebViewCredential> find(String origin) {
        List<WebViewCredential> all = findAll(origin);
        return all.isEmpty() ? Optional.<WebViewCredential>empty()
                             : Optional.of(all.get(0));
    }

    @Override
    public List<WebViewCredential> findAll(String origin) {
        String o = Origins.canonical(origin);
        if (o == null) return Collections.emptyList();
        String[] flat;
        try {
            flat = WebViewNative.webview_cred_store_find(SERVICE, o);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        if (flat == null || flat.length < 3) return Collections.emptyList();
        List<Row> rows = new ArrayList<Row>(flat.length / 3);
        for (int i = 0; i + 2 < flat.length; i += 3) {
            String username = flat[i];
            long millis = parseMillis(flat[i + 1]);
            String password = flat[i + 2];
            if (username == null || password == null) continue;
            rows.add(new Row(new WebViewCredential(o, username, password),
                millis));
        }
        Collections.sort(rows, ROW_ORDER);
        List<WebViewCredential> out = new ArrayList<WebViewCredential>(rows.size());
        for (Row r : rows) out.add(r.credential);
        return Collections.unmodifiableList(out);
    }

    @Override
    public List<WebViewCredential> findAll() {
        String[] flat;
        try {
            flat = WebViewNative.webview_cred_store_find_all(SERVICE);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        if (flat == null || flat.length < 4) return Collections.emptyList();
        List<Row> rows = new ArrayList<Row>(flat.length / 4);
        for (int i = 0; i + 3 < flat.length; i += 4) {
            String origin = flat[i];
            String username = flat[i + 1];
            long millis = parseMillis(flat[i + 2]);
            String password = flat[i + 3];
            if (origin == null || username == null || password == null) continue;
            rows.add(new Row(new WebViewCredential(origin, username, password),
                millis));
        }
        Collections.sort(rows, ROW_ORDER);
        List<WebViewCredential> out = new ArrayList<WebViewCredential>(rows.size());
        for (Row r : rows) out.add(r.credential);
        return Collections.unmodifiableList(out);
    }

    @Override
    public boolean delete(String origin, String username) {
        String o = Origins.canonical(origin);
        if (o == null || username == null) return false;
        try {
            return WebViewNative.webview_cred_store_delete(SERVICE, o, username);
        } catch (Throwable t) {
            return false;
        }
    }

    private static long parseMillis(String s) {
        if (s == null) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException nfe) {
            return 0L;
        }
    }

    /** Most-recently-saved first; ties broken by origin then username
     *  ascending for deterministic ordering (the origin tie-break is a
     *  no-op for the origin-scoped {@code findAll(origin)} where every row
     *  shares one origin, and orders the cross-origin {@code findAll()}). */
    private static final Comparator<Row> ROW_ORDER = new Comparator<Row>() {
        @Override public int compare(Row a, Row b) {
            if (a.savedAtMillis != b.savedAtMillis) {
                return a.savedAtMillis > b.savedAtMillis ? -1 : 1;
            }
            int byOrigin = a.credential.origin().compareTo(b.credential.origin());
            if (byOrigin != 0) return byOrigin;
            return a.credential.username().compareTo(b.credential.username());
        }
    };

    private static final class Row {
        final WebViewCredential credential;
        final long savedAtMillis;
        Row(WebViewCredential credential, long savedAtMillis) {
            this.credential = credential;
            this.savedAtMillis = savedAtMillis;
        }
    }
}
