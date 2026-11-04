/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The portable reader, validator and matcher of WebKit's content-blocker
 * JSON format (Canvas 37 D10).  WKWebView and WebKitGTK compile the format
 * natively; WebView2 has no compiler, so on Windows the library parses a
 * list with this class and answers each request from it.
 *
 * <p>Immutable after {@link #parse}; safe to share between threads.
 *
 * <p>Differences from WebKit, by necessity:
 * <ul>
 *   <li>third party is decided on an approximation of the public suffix
 *       list ({@link #registrableDomain});</li>
 *   <li>the frame of a subresource is unknown, so a rule limited to
 *       {@code child-frame} never matches;</li>
 *   <li>{@code make-https} and {@code notify} are accepted and ignored;</li>
 *   <li>regular expressions are Java's, which accepts more than WebKit.</li>
 * </ul>
 */
final class ContentRuleMatcher {

    /** Canvas 37 D10: a request's kind.  Ordinals are a wire contract with
     *  the Windows native layer and must not be reordered. */
    enum ResourceType {
        DOCUMENT, IMAGE, STYLE_SHEET, SCRIPT, FONT, MEDIA, FETCH, WEBSOCKET, PING, OTHER;

        private static final ResourceType[] VALUES = values();

        static ResourceType fromOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : OTHER;
        }

        int bit() {
            return 1 << ordinal();
        }
    }

    /** WebKit's cap on the number of rules in one list. */
    static final int MAX_RULES = 150000;

    static final int ACT_BLOCK = 0;
    static final int ACT_BLOCK_COOKIES = 1;
    static final int ACT_CSS_DISPLAY_NONE = 2;
    static final int ACT_IGNORE_PREVIOUS = 3;
    static final int ACT_MAKE_HTTPS = 4;
    static final int ACT_NOTIFY = 5;

    private static final int ALL_TYPES = -1;
    private static final int LOAD_ANY = 0;
    private static final int LOAD_FIRST = 1;
    private static final int LOAD_THIRD = 2;
    private static final int GRAM = 3;

    /** One parsed rule. */
    static final class Rule {
        final int index;
        final String urlFilter;
        final boolean caseSensitive;
        final Pattern pattern;
        final String[] ifDomain;
        final String[] unlessDomain;
        final String[] ifTopUrl;
        final String[] unlessTopUrl;
        final Pattern[] ifTopUrlPatterns;
        final Pattern[] unlessTopUrlPatterns;
        final int resourceMask;
        final int loadType;
        final boolean topFrameAllowed;
        final int action;
        final String selector;

        Rule(int index, String urlFilter, boolean caseSensitive, String[] ifDomain,
             String[] unlessDomain, String[] ifTopUrl, String[] unlessTopUrl,
             int resourceMask, int loadType, boolean topFrameAllowed, int action,
             String selector) {
            this.index = index;
            this.urlFilter = urlFilter;
            this.caseSensitive = caseSensitive;
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            this.pattern = Pattern.compile(urlFilter, flags);
            this.ifDomain = ifDomain;
            this.unlessDomain = unlessDomain;
            this.ifTopUrl = ifTopUrl;
            this.unlessTopUrl = unlessTopUrl;
            this.ifTopUrlPatterns = compileAll(ifTopUrl, flags);
            this.unlessTopUrlPatterns = compileAll(unlessTopUrl, flags);
            this.resourceMask = resourceMask;
            this.loadType = loadType;
            this.topFrameAllowed = topFrameAllowed;
            this.action = action;
            this.selector = selector;
        }

        private static Pattern[] compileAll(String[] sources, int flags) {
            if (sources == null) return null;
            Pattern[] out = new Pattern[sources.length];
            for (int i = 0; i < sources.length; i++) {
                out[i] = Pattern.compile(sources[i], flags);
            }
            return out;
        }

        /** True when the rule has no condition beyond its url-filter. */
        boolean unconditional() {
            return ifDomain == null && unlessDomain == null && ifTopUrl == null
                && unlessTopUrl == null && resourceMask == ALL_TYPES
                && loadType == LOAD_ANY && topFrameAllowed;
        }
    }

    private final List<Rule> rules;
    /** 3-gram → indices into {@link #rules}, ascending. */
    private final Map<String, int[]> index;
    /** Indices of rules with no required 3-gram, ascending. */
    private final int[] generic;

    private ContentRuleMatcher(List<Rule> rules) {
        this.rules = rules;
        // Canvas 37 D10 index: count each rule's grams, file it under its
        // rarest one.
        List<List<String>> grams = new ArrayList<>(rules.size());
        Map<String, Integer> freq = new HashMap<>();
        for (Rule r : rules) {
            List<String> g = r.action == ACT_CSS_DISPLAY_NONE
                ? Collections.<String>emptyList()
                : gramsOf(requiredLiterals(r.urlFilter));
            grams.add(g);
            for (String s : g) {
                Integer n = freq.get(s);
                freq.put(s, n == null ? 1 : n + 1);
            }
        }
        Map<String, List<Integer>> buckets = new HashMap<>();
        List<Integer> gen = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).action == ACT_CSS_DISPLAY_NONE) continue;
            List<String> g = grams.get(i);
            if (g.isEmpty()) {
                gen.add(i);
                continue;
            }
            String best = null;
            int bestN = Integer.MAX_VALUE;
            for (String s : g) {
                int n = freq.get(s);
                if (n < bestN) { bestN = n; best = s; }
            }
            List<Integer> b = buckets.get(best);
            if (b == null) { b = new ArrayList<>(2); buckets.put(best, b); }
            b.add(i);
        }
        this.index = new HashMap<>(buckets.size() * 2);
        for (Map.Entry<String, List<Integer>> e : buckets.entrySet()) {
            this.index.put(e.getKey(), toArray(e.getValue()));
        }
        this.generic = toArray(gen);
    }

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /** @return the number of rules */
    int size() {
        return rules.size();
    }

    /** @return the number of rules no 3-gram indexes (evaluated on every request) */
    int genericCount() {
        return generic.length;
    }

    // ------------------------------------------------------------------
    // Parsing and validation
    // ------------------------------------------------------------------

    /**
     * Parse and validate a rule list.
     *
     * @throws WebViewContentRuleException naming the problem (and the rule
     *         index) when the text is not a valid list
     */
    static ContentRuleMatcher parse(String json) {
        if (json == null) throw new WebViewContentRuleException("rule list is null");
        Object top;
        try {
            top = new JsonReader(json).readDocument();
        } catch (IllegalArgumentException ex) {
            throw new WebViewContentRuleException("invalid JSON: " + ex.getMessage(), ex);
        }
        if (!(top instanceof List)) {
            throw new WebViewContentRuleException("rule list must be a JSON array");
        }
        List<?> arr = (List<?>) top;
        if (arr.isEmpty()) {
            throw new WebViewContentRuleException("rule list is empty");
        }
        if (arr.size() > MAX_RULES) {
            throw new WebViewContentRuleException("rule list has " + arr.size()
                + " rules; the limit is " + MAX_RULES);
        }
        List<Rule> rules = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            rules.add(parseRule(i, arr.get(i)));
        }
        return new ContentRuleMatcher(rules);
    }

    private static Rule parseRule(int i, Object o) {
        if (!(o instanceof Map)) throw fail(i, "rule is not an object");
        Map<?, ?> rule = (Map<?, ?>) o;
        Object t = rule.get("trigger");
        Object a = rule.get("action");
        if (!(t instanceof Map)) throw fail(i, "missing trigger object");
        if (!(a instanceof Map)) throw fail(i, "missing action object");
        Map<?, ?> trigger = (Map<?, ?>) t;
        Map<?, ?> action = (Map<?, ?>) a;

        Object uf = trigger.get("url-filter");
        if (!(uf instanceof String)) throw fail(i, "trigger.url-filter must be a string");
        String urlFilter = (String) uf;
        if (urlFilter.isEmpty()) throw fail(i, "trigger.url-filter is empty");
        Object cs = trigger.get("url-filter-is-case-sensitive");
        if (cs != null && !(cs instanceof Boolean)) {
            throw fail(i, "trigger.url-filter-is-case-sensitive must be a boolean");
        }
        boolean caseSensitive = Boolean.TRUE.equals(cs);

        String[] ifDomain = domains(i, trigger, "if-domain");
        String[] unlessDomain = domains(i, trigger, "unless-domain");
        if (ifDomain != null && unlessDomain != null) {
            throw fail(i, "trigger has both if-domain and unless-domain");
        }
        String[] ifTop = strings(i, trigger, "if-top-url");
        String[] unlessTop = strings(i, trigger, "unless-top-url");
        if (ifTop != null && unlessTop != null) {
            throw fail(i, "trigger has both if-top-url and unless-top-url");
        }
        if ((ifDomain != null || unlessDomain != null) && (ifTop != null || unlessTop != null)) {
            throw fail(i, "trigger mixes domain and top-url conditions");
        }

        int mask = ALL_TYPES;
        String[] types = strings(i, trigger, "resource-type");
        if (types != null) {
            mask = 0;
            for (String s : types) mask |= resourceBits(i, s);
        }
        int loadType = LOAD_ANY;
        String[] loads = strings(i, trigger, "load-type");
        if (loads != null) {
            boolean first = false, third = false;
            for (String s : loads) {
                if ("first-party".equals(s)) first = true;
                else if ("third-party".equals(s)) third = true;
                else throw fail(i, "unknown load-type \"" + s + "\"");
            }
            loadType = first == third ? LOAD_ANY : first ? LOAD_FIRST : LOAD_THIRD;
        }
        boolean topFrameAllowed = true;
        String[] contexts = strings(i, trigger, "load-context");
        if (contexts != null) {
            topFrameAllowed = false;
            for (String s : contexts) {
                if ("top-frame".equals(s)) topFrameAllowed = true;
                else if (!"child-frame".equals(s)) {
                    throw fail(i, "unknown load-context \"" + s + "\"");
                }
            }
        }

        Object typeObj = action.get("type");
        if (!(typeObj instanceof String)) throw fail(i, "action.type must be a string");
        int act;
        switch ((String) typeObj) {
            case "block": act = ACT_BLOCK; break;
            case "block-cookies": act = ACT_BLOCK_COOKIES; break;
            case "css-display-none": act = ACT_CSS_DISPLAY_NONE; break;
            case "ignore-previous-rules": act = ACT_IGNORE_PREVIOUS; break;
            case "make-https": act = ACT_MAKE_HTTPS; break;
            case "notify": act = ACT_NOTIFY; break;
            default: throw fail(i, "unknown action.type \"" + typeObj + "\"");
        }
        String selector = null;
        if (act == ACT_CSS_DISPLAY_NONE) {
            Object sel = action.get("selector");
            if (!(sel instanceof String) || ((String) sel).trim().isEmpty()) {
                throw fail(i, "css-display-none needs a non-empty action.selector");
            }
            selector = (String) sel;
        }
        try {
            return new Rule(i, urlFilter, caseSensitive, ifDomain, unlessDomain, ifTop,
                unlessTop, mask, loadType, topFrameAllowed, act, selector);
        } catch (PatternSyntaxException ex) {
            throw fail(i, "invalid regular expression: " + ex.getDescription());
        }
    }

    private static int resourceBits(int i, String s) {
        switch (s) {
            case "document":
            case "svg-document": return ResourceType.DOCUMENT.bit();
            case "image": return ResourceType.IMAGE.bit();
            case "style-sheet": return ResourceType.STYLE_SHEET.bit();
            case "script": return ResourceType.SCRIPT.bit();
            case "font": return ResourceType.FONT.bit();
            case "media": return ResourceType.MEDIA.bit();
            case "raw": return ResourceType.FETCH.bit() | ResourceType.WEBSOCKET.bit()
                | ResourceType.OTHER.bit();
            case "fetch": return ResourceType.FETCH.bit();
            case "websocket": return ResourceType.WEBSOCKET.bit();
            case "other": return ResourceType.OTHER.bit();
            case "ping": return ResourceType.PING.bit();
            case "popup": return 0;
            default: throw fail(i, "unknown resource-type \"" + s + "\"");
        }
    }

    private static String[] domains(int i, Map<?, ?> trigger, String key) {
        String[] d = strings(i, trigger, key);
        if (d == null) return null;
        for (int k = 0; k < d.length; k++) d[k] = d[k].toLowerCase(Locale.ROOT);
        return d;
    }

    private static String[] strings(int i, Map<?, ?> trigger, String key) {
        Object v = trigger.get(key);
        if (v == null) return null;
        if (!(v instanceof List)) throw fail(i, "trigger." + key + " must be an array");
        List<?> l = (List<?>) v;
        if (l.isEmpty()) throw fail(i, "trigger." + key + " is empty");
        String[] out = new String[l.size()];
        for (int k = 0; k < out.length; k++) {
            Object s = l.get(k);
            if (!(s instanceof String)) {
                throw fail(i, "trigger." + key + " must hold strings");
            }
            out[k] = (String) s;
        }
        return out;
    }

    private static WebViewContentRuleException fail(int i, String what) {
        return new WebViewContentRuleException("rule " + i + ": " + what);
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * Canvas 37 D10: this list's decision for one request.
     *
     * @return {@link WebViewContentRuleCallback#ALLOW},
     *         {@link WebViewContentRuleCallback#BLOCK} or
     *         {@link WebViewContentRuleCallback#BLOCK_COOKIES}
     */
    int decide(String url, String pageUrl, ResourceType type) {
        if (url == null || url.isEmpty()) return WebViewContentRuleCallback.ALLOW;
        if (pageUrl == null || pageUrl.isEmpty()) pageUrl = url;
        String lower = url.toLowerCase(Locale.ROOT);
        int[] candidates = candidates(lower);
        if (candidates.length == 0) return WebViewContentRuleCallback.ALLOW;

        RequestFacts facts = new RequestFacts(url, pageUrl, type);
        boolean block = false, cookies = false;
        for (int idx : candidates) {
            Rule r = rules.get(idx);
            if (r.action == ACT_CSS_DISPLAY_NONE || r.action == ACT_MAKE_HTTPS
                    || r.action == ACT_NOTIFY) {
                continue;
            }
            if (!triggerMatches(r, facts)) continue;
            switch (r.action) {
                case ACT_IGNORE_PREVIOUS: block = false; cookies = false; break;
                case ACT_BLOCK: block = true; break;
                case ACT_BLOCK_COOKIES: cookies = true; break;
                default: break;
            }
        }
        if (block) return WebViewContentRuleCallback.BLOCK;
        if (cookies) return WebViewContentRuleCallback.BLOCK_COOKIES;
        return WebViewContentRuleCallback.ALLOW;
    }

    private int[] candidates(String lowerUrl) {
        int n = lowerUrl.length();
        int[][] hits = new int[Math.max(0, n - GRAM + 1) + 1][];
        int count = 0, total = generic.length;
        hits[count++] = generic;
        for (int i = 0; i + GRAM <= n; i++) {
            int[] b = index.get(lowerUrl.substring(i, i + GRAM));
            if (b != null) {
                hits[count++] = b;
                total += b.length;
            }
        }
        int[] all = new int[total];
        int p = 0;
        for (int i = 0; i < count; i++) {
            System.arraycopy(hits[i], 0, all, p, hits[i].length);
            p += hits[i].length;
        }
        Arrays.sort(all);
        int w = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[i - 1]) all[w++] = all[i];
        }
        return w == all.length ? all : Arrays.copyOf(all, w);
    }

    private static final class RequestFacts {
        final String url;
        final String pageUrl;
        final ResourceType type;
        final String pageHost;
        private Boolean thirdParty;

        RequestFacts(String url, String pageUrl, ResourceType type) {
            this.url = url;
            this.pageUrl = pageUrl;
            this.type = type;
            this.pageHost = hostOf(pageUrl);
        }

        boolean thirdParty() {
            if (thirdParty == null) {
                if (type == ResourceType.DOCUMENT && url.equals(pageUrl)) {
                    thirdParty = Boolean.FALSE;
                } else {
                    thirdParty = !registrableDomain(hostOf(url))
                        .equals(registrableDomain(pageHost));
                }
            }
            return thirdParty;
        }
    }

    private static boolean triggerMatches(Rule r, RequestFacts f) {
        if (r.resourceMask != ALL_TYPES && (r.resourceMask & f.type.bit()) == 0) return false;
        if (!r.topFrameAllowed) return false;
        if (r.loadType == LOAD_FIRST && f.thirdParty()) return false;
        if (r.loadType == LOAD_THIRD && !f.thirdParty()) return false;
        if (!pageConditionsMatch(r, f.pageHost, f.pageUrl)) return false;
        return r.pattern.matcher(f.url).find();
    }

    /** Domain and top-url conditions, against the page (shared with the
     *  cosmetic evaluation's contract). */
    static boolean pageConditionsMatch(Rule r, String pageHost, String pageUrl) {
        if (r.ifDomain != null && !domainListMatches(r.ifDomain, pageHost)) return false;
        if (r.unlessDomain != null && domainListMatches(r.unlessDomain, pageHost)) return false;
        if (r.ifTopUrlPatterns != null && !anyFinds(r.ifTopUrlPatterns, pageUrl)) return false;
        if (r.unlessTopUrlPatterns != null && anyFinds(r.unlessTopUrlPatterns, pageUrl)) return false;
        return true;
    }

    private static boolean anyFinds(Pattern[] ps, String s) {
        for (Pattern p : ps) if (p.matcher(s).find()) return true;
        return false;
    }

    static boolean domainListMatches(String[] list, String host) {
        if (host == null || host.isEmpty()) return false;
        for (String d : list) {
            if (d.startsWith("*")) {
                String base = d.substring(1);
                if (host.equals(base) || host.endsWith("." + base)) return true;
            } else if (host.equals(d)) {
                return true;
            }
        }
        return false;
    }

    static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = new URI(url).getHost();
            if (h != null) return h.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            // fall through to the manual parse
        }
        int s = url.indexOf("://");
        if (s < 0) return "";
        int start = s + 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        String auth = url.substring(start, end);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        if (auth.startsWith("[")) {
            int close = auth.indexOf(']');
            return (close > 0 ? auth.substring(0, close + 1) : auth).toLowerCase(Locale.ROOT);
        }
        int colon = auth.indexOf(':');
        if (colon >= 0) auth = auth.substring(0, colon);
        return auth.toLowerCase(Locale.ROOT);
    }

    private static final java.util.Set<String> SECOND_LEVEL = new java.util.HashSet<>(
        Arrays.asList("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go"));

    /** Canvas 37 D10: an approximation of the registrable domain (eTLD+1). */
    static String registrableDomain(String host) {
        if (host == null || host.isEmpty()) return "";
        if (host.startsWith("[") || host.matches("[0-9.]+")) return host;
        String[] labels = host.split("\\.");
        if (labels.length <= 2) return host;
        int n = labels.length;
        String tld = labels[n - 1];
        String second = labels[n - 2];
        int take = tld.length() == 2 && SECOND_LEVEL.contains(second) ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - take; i < n; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Required literal text (Canvas 37 D10 index)
    // ------------------------------------------------------------------

    /** @return the maximal literal runs every match of {@code regex} contains */
    static List<String> requiredLiterals(String regex) {
        List<String> runs = new ArrayList<>();
        if (hasTopLevelAlternation(regex)) return runs;
        StringBuilder cur = new StringBuilder();
        int n = regex.length();
        int i = 0;
        while (i < n) {
            char c = regex.charAt(i);
            if (c == '[') {
                flush(runs, cur);
                i = skipClass(regex, i);
                i = skipQuantifier(regex, i);
                continue;
            }
            if (c == '(') {
                flush(runs, cur);
                i = skipGroup(regex, i);
                i = skipQuantifier(regex, i);
                continue;
            }
            char lit;
            int next;
            if (c == '\\') {
                if (i + 1 >= n) { flush(runs, cur); break; }
                char e = regex.charAt(i + 1);
                next = i + 2;
                if (Character.isLetterOrDigit(e)) {
                    flush(runs, cur);
                    i = skipQuantifier(regex, next);
                    continue;
                }
                lit = e;
            } else if (".^$|)]}".indexOf(c) >= 0 || c == '*' || c == '+' || c == '?' || c == '{') {
                flush(runs, cur);
                i = c == '{' ? skipBrace(regex, i) : i + 1;
                continue;
            } else {
                lit = c;
                next = i + 1;
            }
            char q = next < n ? regex.charAt(next) : 0;
            if (q == '?' || q == '*' || q == '{') {
                flush(runs, cur);
                i = skipQuantifier(regex, next);
                continue;
            }
            cur.append(lit);
            if (q == '+') {
                flush(runs, cur);
                i = skipQuantifier(regex, next);
                continue;
            }
            i = next;
        }
        flush(runs, cur);
        return runs;
    }

    private static void flush(List<String> runs, StringBuilder cur) {
        if (cur.length() > 0) {
            runs.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static int skipQuantifier(String s, int i) {
        int n = s.length();
        if (i >= n) return i;
        char c = s.charAt(i);
        if (c == '{') i = skipBrace(s, i);
        else if (c == '?' || c == '*' || c == '+') i++;
        else return i;
        if (i < n && (s.charAt(i) == '?' || s.charAt(i) == '+')) i++;  // lazy/possessive
        return i;
    }

    private static int skipBrace(String s, int i) {
        int close = s.indexOf('}', i);
        return close < 0 ? s.length() : close + 1;
    }

    private static int skipClass(String s, int i) {
        int n = s.length();
        int j = i + 1;
        if (j < n && s.charAt(j) == '^') j++;
        if (j < n && s.charAt(j) == ']') j++;
        while (j < n && s.charAt(j) != ']') {
            if (s.charAt(j) == '\\') j++;
            j++;
        }
        return Math.min(n, j + 1);
    }

    private static int skipGroup(String s, int i) {
        int n = s.length();
        int depth = 0;
        int j = i;
        while (j < n) {
            char c = s.charAt(j);
            if (c == '\\') { j += 2; continue; }
            if (c == '[') { j = skipClass(s, j); continue; }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return j + 1;
            }
            j++;
        }
        return n;
    }

    private static boolean hasTopLevelAlternation(String s) {
        int n = s.length();
        int depth = 0;
        for (int j = 0; j < n; j++) {
            char c = s.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == '[') { j = skipClass(s, j) - 1; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '|' && depth == 0) return true;
        }
        return false;
    }

    private static List<String> gramsOf(List<String> runs) {
        List<String> out = new ArrayList<>();
        for (String run : runs) {
            String r = run.toLowerCase(Locale.ROOT);
            for (int i = 0; i + GRAM <= r.length(); i++) {
                String g = r.substring(i, i + GRAM);
                if (!out.contains(g)) out.add(g);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Cosmetic export (Canvas 37 D10, D11)
    // ------------------------------------------------------------------

    /** @return the {@code css-display-none} and {@code ignore-previous-rules}
     *  rules, in order */
    List<Rule> cosmeticRules() {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (r.action == ACT_CSS_DISPLAY_NONE || r.action == ACT_IGNORE_PREVIOUS) out.add(r);
        }
        return out;
    }

    /** True when a url-filter matches every http(s) page address. */
    static boolean matchesEveryPage(String urlFilter) {
        return ".*".equals(urlFilter) || ".".equals(urlFilter)
            || "^https?://".equals(urlFilter) || "^https?://.*".equals(urlFilter)
            || "^https?:".equals(urlFilter) || "https?://".equals(urlFilter);
    }

    // ------------------------------------------------------------------
    // A small JSON reader (Canvas 37 D10): objects, arrays, strings,
    // numbers, booleans and null.
    // ------------------------------------------------------------------

    static final class JsonReader {
        private final String s;
        private int p;

        JsonReader(String s) {
            this.s = s;
        }

        Object readDocument() {
            ws();
            Object v = value();
            ws();
            if (p != s.length()) throw err("unexpected trailing text");
            return v;
        }

        private Object value() {
            if (p >= s.length()) throw err("unexpected end of input");
            char c = s.charAt(p);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            p++;
            ws();
            if (peek() == '}') { p++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw err("expected a string key");
                String k = string();
                ws();
                if (peek() != ':') throw err("expected ':'");
                p++;
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                p++;
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            p++;
            ws();
            if (peek() == ']') { p++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                p++;
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        private String string() {
            p++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (p >= s.length()) throw err("unterminated string");
                char c = s.charAt(p++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (p >= s.length()) throw err("unterminated escape");
                    char e = s.charAt(p++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (p + 4 > s.length()) throw err("bad \\u escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("bad \\u escape");
                            }
                            p += 4;
                            break;
                        default: throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = p;
            if (peek() == '-') p++;
            while (p < s.length() && "0123456789.eE+-".indexOf(s.charAt(p)) >= 0) p++;
            try {
                return Double.valueOf(s.substring(start, p));
            } catch (NumberFormatException ex) {
                throw err("bad number");
            }
        }

        private Object literal(String word, Object v) {
            if (!s.startsWith(word, p)) throw err("unexpected token");
            p += word.length();
            return v;
        }

        private char peek() {
            return p < s.length() ? s.charAt(p) : 0;
        }

        private void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        private IllegalArgumentException err(String what) {
            return new IllegalArgumentException(what + " at offset " + p);
        }
    }
}
