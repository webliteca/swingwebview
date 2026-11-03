/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Compile, look up and remove content rule lists (Canvas 37 D2–D4): rules in
 * WebKit's content-blocker JSON format that make the engine refuse matching
 * requests before they are sent ({@code block}), send them without cookies
 * ({@code block-cookies}), hide matching elements ({@code css-display-none})
 * or cancel earlier rules ({@code ignore-previous-rules}).
 *
 * <p>A list is compiled once, under an identifier, into a store directory the
 * application chooses, and kept there: the next launch {@link #lookup}s it
 * instead of compiling again.  The resulting {@link WebViewContentRuleList}
 * is applied to a view with {@code WebViewComponent.addContentRuleList}.
 *
 * <pre>{@code
 * File store = new File(appDataDir, "content-rules");
 * WebViewContentRules.lookup(store, "ads-v1")
 *     .thenCompose(l -> l != null ? CompletableFuture.completedFuture(l)
 *                                 : WebViewContentRules.compile(store, "ads-v1", json))
 *     .thenAccept(l -> SwingUtilities.invokeLater(() -> view.addContentRuleList(l)));
 * }</pre>
 *
 * <p><strong>Engines.</strong> macOS compiles with {@code WKContentRuleListStore}
 * and Linux with {@code WebKitUserContentFilterStore} (WebKitGTK 2.24+); both
 * keep the compiled form in the directory.  Windows has no compiler: the list
 * is validated and parsed in Java, the JSON is kept in the directory as
 * {@code <identifier>.json}, and each request is matched in Java.  See
 * {@code ContentRuleMatcher} for the few rules Windows treats differently.
 *
 * <p><strong>Threading.</strong> Every method may be called from any thread.
 * The returned futures complete later, off the EDT, on a native or worker
 * thread; marshal to the EDT before touching Swing.
 */
public final class WebViewContentRules {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** Windows: parsed lists by registry key (Canvas 37 D4). */
    private static final ConcurrentHashMap<String, WebViewContentRuleList> WINDOWS_REGISTRY =
        new ConcurrentHashMap<>();

    private WebViewContentRules() {
    }

    /**
     * Canvas 37 D3: whether the loaded native library can apply content rule
     * lists.  False for an older native, and on Linux for a WebKitGTK older
     * than 2.24.
     */
    public static boolean isSupported() {
        try {
            return WebViewNative.webview_content_rules_available();
        } catch (UnsatisfiedLinkError ule) {
            return false;
        } catch (NoClassDefFoundError ncdfe) {
            return false;
        }
    }

    /**
     * Compile {@code json} and store it under {@code identifier} in
     * {@code storeDirectory}, replacing any list stored under that name.
     * Views that applied the old list keep it until it is applied again.
     *
     * @return a future completing with the compiled list, or exceptionally
     *         with a {@link WebViewContentRuleException} giving the engine's
     *         reason (invalid rules, unsupported runtime, I/O failure)
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code identifier} is not 1–128 of
     *         {@code [A-Za-z0-9._-]}
     */
    public static CompletableFuture<WebViewContentRuleList> compile(
            File storeDirectory, final String identifier, final String json) {
        final File dir = prepare(storeDirectory, identifier);
        if (json == null) throw new NullPointerException("json");
        if (isWindows()) {
            return CompletableFuture.supplyAsync(() -> {
                ContentRuleMatcher m = ContentRuleMatcher.parse(json);
                File out = jsonFile(dir, identifier);
                File tmp = new File(dir, identifier + ".json.tmp");
                try {
                    Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
                    Files.move(tmp.toPath(), out.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ex) {
                    tmp.delete();
                    throw new WebViewContentRuleException(
                        "could not store " + identifier + ": " + ex.getMessage(), ex);
                }
                WebViewContentRuleList list = new WebViewContentRuleList(dir, identifier, m);
                WINDOWS_REGISTRY.put(list.key(), list);
                return list;
            });
        }
        final CompletableFuture<WebViewContentRuleList> f = new CompletableFuture<>();
        if (!isSupported()) {
            f.completeExceptionally(unsupported());
            return f;
        }
        WebViewNative.webview_content_rules_compile(dir.getAbsolutePath(), identifier, json,
            new WebViewContentRuleListCallback() {
                @Override
                public void onComplete(boolean ok, boolean found, String error) {
                    if (ok) {
                        f.complete(new WebViewContentRuleList(dir, identifier, null));
                    } else {
                        f.completeExceptionally(new WebViewContentRuleException(
                            error == null || error.isEmpty()
                                ? "could not compile " + identifier : error));
                    }
                }
            });
        return f;
    }

    /**
     * Look up the list stored under {@code identifier}, without compiling.
     *
     * @return a future completing with the list, or with {@code null} when
     *         none is stored under that name; exceptionally on an unsupported
     *         runtime or a stored list that can no longer be read
     */
    public static CompletableFuture<WebViewContentRuleList> lookup(
            File storeDirectory, final String identifier) {
        final File dir = prepare(storeDirectory, identifier);
        if (isWindows()) {
            return CompletableFuture.supplyAsync(() -> {
                File in = jsonFile(dir, identifier);
                if (!in.isFile()) return null;
                WebViewContentRuleList cached =
                    WINDOWS_REGISTRY.get(WebViewContentRuleList.key(dir, identifier));
                if (cached != null) return cached;
                String json;
                try {
                    json = new String(Files.readAllBytes(in.toPath()), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new WebViewContentRuleException(
                        "could not read " + identifier + ": " + ex.getMessage(), ex);
                }
                WebViewContentRuleList list =
                    new WebViewContentRuleList(dir, identifier, ContentRuleMatcher.parse(json));
                WINDOWS_REGISTRY.put(list.key(), list);
                return list;
            });
        }
        final CompletableFuture<WebViewContentRuleList> f = new CompletableFuture<>();
        if (!isSupported()) {
            f.completeExceptionally(unsupported());
            return f;
        }
        WebViewNative.webview_content_rules_lookup(dir.getAbsolutePath(), identifier,
            new WebViewContentRuleListCallback() {
                @Override
                public void onComplete(boolean ok, boolean found, String error) {
                    if (!ok) {
                        f.completeExceptionally(new WebViewContentRuleException(
                            error == null || error.isEmpty()
                                ? "could not look up " + identifier : error));
                    } else {
                        f.complete(found ? new WebViewContentRuleList(dir, identifier, null)
                                         : null);
                    }
                }
            });
        return f;
    }

    /**
     * Remove the list stored under {@code identifier}.  Completes normally when
     * nothing was stored, and on an unsupported runtime.  Views that applied
     * the list keep it until it is removed from them.
     */
    public static CompletableFuture<Void> remove(File storeDirectory, final String identifier) {
        final File dir = prepare(storeDirectory, identifier);
        if (isWindows()) {
            return CompletableFuture.runAsync(() -> {
                WINDOWS_REGISTRY.remove(WebViewContentRuleList.key(dir, identifier));
                File in = jsonFile(dir, identifier);
                if (in.exists() && !in.delete()) {
                    throw new WebViewContentRuleException("could not remove " + identifier);
                }
            });
        }
        final CompletableFuture<Void> f = new CompletableFuture<>();
        if (!isSupported()) {
            f.complete(null);
            return f;
        }
        WebViewNative.webview_content_rules_remove(dir.getAbsolutePath(), identifier,
            new WebViewContentRuleListCallback() {
                @Override
                public void onComplete(boolean ok, boolean found, String error) {
                    if (ok) {
                        f.complete(null);
                    } else {
                        f.completeExceptionally(new WebViewContentRuleException(
                            error == null || error.isEmpty()
                                ? "could not remove " + identifier : error));
                    }
                }
            });
        return f;
    }

    private static File prepare(File storeDirectory, String identifier) {
        if (storeDirectory == null) throw new NullPointerException("storeDirectory");
        if (identifier == null) throw new NullPointerException("identifier");
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                "identifier must be 1-128 of [A-Za-z0-9._-]: " + identifier);
        }
        File dir = storeDirectory.getAbsoluteFile();
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File jsonFile(File dir, String identifier) {
        return new File(dir, identifier + ".json");
    }

    private static WebViewContentRuleException unsupported() {
        return new WebViewContentRuleException(
            "content rule lists are not supported by this native library or engine");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
