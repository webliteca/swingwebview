/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.File;

/**
 * A compiled content rule list (Canvas 37 D1): the handle that
 * {@link WebViewContentRules#compile} and {@link WebViewContentRules#lookup}
 * complete with, and that {@code WebViewComponent.addContentRuleList}
 * applies to a view.
 *
 * <p>An immutable value naming a list by its store directory and
 * identifier.  It holds no native resource: the engine keeps the compiled
 * list in a process-wide registry under that name.  Two handles are equal
 * when both their directory and identifier are equal.
 *
 * <p>Applying two lists with the same identifier from different stores to
 * one view is not supported: each engine keys applied lists by identifier,
 * so the second replaces the first.
 */
public final class WebViewContentRuleList {

    private final File storeDirectory;
    private final String identifier;
    /** Canvas 37 D1: the parsed rules, on Windows only; null elsewhere. */
    private final ContentRuleMatcher matcher;

    WebViewContentRuleList(File storeDirectory, String identifier,
                           ContentRuleMatcher matcher) {
        this.storeDirectory = storeDirectory.getAbsoluteFile();
        this.identifier = identifier;
        this.matcher = matcher;
    }

    /** @return the name the list was compiled under */
    public String identifier() {
        return identifier;
    }

    /** @return the absolute store directory the list lives in */
    public File storeDirectory() {
        return storeDirectory;
    }

    /** @return the parsed rules on Windows, {@code null} on other engines */
    ContentRuleMatcher matcher() {
        return matcher;
    }

    /** @return the native registry key, {@code dir + "\n" + identifier} */
    String key() {
        return key(storeDirectory, identifier);
    }

    static String key(File dir, String identifier) {
        return dir.getAbsolutePath() + "\n" + identifier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebViewContentRuleList)) return false;
        WebViewContentRuleList other = (WebViewContentRuleList) o;
        return identifier.equals(other.identifier)
            && storeDirectory.equals(other.storeDirectory);
    }

    @Override
    public int hashCode() {
        return 31 * storeDirectory.hashCode() + identifier.hashCode();
    }

    @Override
    public String toString() {
        return "WebViewContentRuleList[" + identifier + " @ " + storeDirectory + "]";
    }
}
