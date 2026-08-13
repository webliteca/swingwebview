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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link WebViewCredentialStore} that keeps credentials in memory only,
 * with the same origin-keying and recency semantics as
 * {@link NativeCredentialStore} but no OS persistence.  Intended for unit
 * tests and for hosts that do not want credentials written to the OS
 * secret store.
 */
public final class InMemoryCredentialStore implements WebViewCredentialStore {

    private final ConcurrentHashMap<String, List<Rec>> byOrigin =
        new ConcurrentHashMap<String, List<Rec>>();

    /** Strictly-increasing save clock: guarantees a later save always
     *  outranks an earlier one even when the wall clock has not advanced
     *  between them, so recency ordering is deterministic. */
    private final AtomicLong clock = new AtomicLong(0L);

    private long nextMillis() {
        long now = System.currentTimeMillis();
        while (true) {
            long prev = clock.get();
            long next = now > prev ? now : prev + 1;
            if (clock.compareAndSet(prev, next)) return next;
        }
    }

    @Override
    public void save(WebViewCredential credential) {
        if (credential == null) return;
        String origin = Origins.canonical(credential.origin());
        if (origin == null) return;
        List<Rec> list = byOrigin.get(origin);
        if (list == null) {
            list = Collections.synchronizedList(new ArrayList<Rec>());
            List<Rec> existing = byOrigin.putIfAbsent(origin, list);
            if (existing != null) list = existing;
        }
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).username.equals(credential.username())) {
                    list.remove(i);
                }
            }
            list.add(new Rec(credential.username(),
                nextMillis(), credential.password()));
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
        List<Rec> list = byOrigin.get(o);
        if (list == null) return Collections.emptyList();
        List<Rec> copy;
        synchronized (list) {
            copy = new ArrayList<Rec>(list);
        }
        Collections.sort(copy, REC_ORDER);
        List<WebViewCredential> out = new ArrayList<WebViewCredential>(copy.size());
        for (Rec r : copy) {
            out.add(new WebViewCredential(o, r.username, r.password));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public boolean delete(String origin, String username) {
        String o = Origins.canonical(origin);
        if (o == null || username == null) return false;
        List<Rec> list = byOrigin.get(o);
        if (list == null) return false;
        synchronized (list) {
            boolean removed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).username.equals(username)) {
                    list.remove(i);
                    removed = true;
                }
            }
            return removed;
        }
    }

    private static final Comparator<Rec> REC_ORDER = new Comparator<Rec>() {
        @Override public int compare(Rec a, Rec b) {
            if (a.savedAtMillis != b.savedAtMillis) {
                return a.savedAtMillis > b.savedAtMillis ? -1 : 1;
            }
            return a.username.compareTo(b.username);
        }
    };

    private static final class Rec {
        final String username;
        final long savedAtMillis;
        final String password;
        Rec(String username, long savedAtMillis, String password) {
            this.username = username;
            this.savedAtMillis = savedAtMillis;
            this.password = password;
        }
    }
}
