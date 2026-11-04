/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * {@link WebViewComponent#addContentRuleList} instead.  This class is
 * {@code public} only because the consuming Swing subclasses live in a
 * different package, like {@link NavigationDispatcher}.
 *
 * <p>A component's applied content rule lists, in order (Canvas 37 D6), and
 * on Windows the request decision (D9) and the cosmetic script (D11) over
 * them.  Also holds the opener's lists for popups awaiting adoption (D7).
 *
 * <p><strong>Threading:</strong> every method may be called from any
 * thread; {@link #decide} runs inline on the WebView2 UI thread and never
 * throws.
 */
public final class ContentRuleSet {

    /** Canvas 37 D7: how many popups' opener lists are remembered. */
    static final int POPUP_MAX = 64;

    /** Selectors inserted per {@code insertRule} call before falling back to
     *  one at a time (Canvas 37 D11). */
    private static final int SELECTOR_CHUNK = 64;

    private final Object lock = new Object();
    private final List<WebViewContentRuleList> lists = new ArrayList<>();

    private static final Map<Long, List<WebViewContentRuleList>> POPUPS =
        new LinkedHashMap<Long, List<WebViewContentRuleList>>() {
            private static final long serialVersionUID = 1L;
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<Long, List<WebViewContentRuleList>> eldest) {
                return size() > POPUP_MAX;
            }
        };

    /**
     * Canvas 37 D9: {@code true} where the engine has no rule compiler and the
     * component answers each request itself (Windows / WebView2).
     */
    public static boolean usesJavaMatcher() {
        return WebViewContentRules.isWindows();
    }

    /** Add {@code list} at the end; an identifier already present is moved
     *  to the end (Canvas 37 D6). */
    public void add(WebViewContentRuleList list) {
        if (list == null) throw new NullPointerException("list");
        synchronized (lock) {
            removeLocked(list.identifier());
            lists.add(list);
        }
    }

    /** @return {@code true} when a list with this identifier was removed */
    public boolean remove(String identifier) {
        synchronized (lock) {
            return removeLocked(identifier);
        }
    }

    private boolean removeLocked(String identifier) {
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).identifier().equals(identifier)) {
                lists.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Remove every list. */
    public void clear() {
        synchronized (lock) {
            lists.clear();
        }
    }

    /** @return the applied lists, in order (a snapshot) */
    public List<WebViewContentRuleList> lists() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(lists));
        }
    }

    /** @return {@code true} when no list is applied */
    public boolean isEmpty() {
        synchronized (lock) {
            return lists.isEmpty();
        }
    }

    /**
     * Canvas 37 D9, D10: the decision for one request over every applied list
     * that carries parsed rules (Windows).  Each list decides on its own, as a
     * WebKit content extension does; the request is blocked when any list
     * blocks.  Never throws: a throwable answers
     * {@link WebViewContentRuleCallback#ALLOW} and goes to the default
     * uncaught-exception handler.
     */
    public int decide(String url, String pageUrl, int resourceType) {
        try {
            List<WebViewContentRuleList> snapshot = lists();
            ContentRuleMatcher.ResourceType type =
                ContentRuleMatcher.ResourceType.fromOrdinal(resourceType);
            int answer = WebViewContentRuleCallback.ALLOW;
            for (WebViewContentRuleList l : snapshot) {
                ContentRuleMatcher m = l.matcher();
                if (m == null) continue;
                int d = m.decide(url, pageUrl, type);
                if (d == WebViewContentRuleCallback.BLOCK) return d;
                if (d == WebViewContentRuleCallback.BLOCK_COOKIES) answer = d;
            }
            return answer;
        } catch (Throwable t) {
            forwardUncaught(t);
            return WebViewContentRuleCallback.ALLOW;
        }
    }

    /**
     * Canvas 37 D11: the document-created script that hides the elements the
     * applied lists' {@code css-display-none} rules select.  Empty when no list
     * has cosmetic rules.
     */
    public String cosmeticScript() {
        List<WebViewContentRuleList> snapshot = lists();
        StringBuilder data = new StringBuilder("[");
        boolean anyCss = false;
        boolean firstList = true;
        for (WebViewContentRuleList l : snapshot) {
            ContentRuleMatcher m = l.matcher();
            if (m == null) continue;
            List<ContentRuleMatcher.Rule> rules = m.cosmeticRules();
            if (rules.isEmpty()) continue;
            if (!firstList) data.append(',');
            firstList = false;
            data.append('[');
            boolean firstEntry = true;
            List<String> merged = null;
            for (ContentRuleMatcher.Rule r : rules) {
                boolean css = r.action == ContentRuleMatcher.ACT_CSS_DISPLAY_NONE;
                anyCss |= css;
                if (css && r.unconditional() && ContentRuleMatcher.matchesEveryPage(r.urlFilter)) {
                    if (merged == null) merged = new ArrayList<>();
                    merged.add(r.selector);
                    continue;
                }
                if (merged != null) {
                    firstEntry = appendEntry(data, firstEntry, null, merged);
                    merged = null;
                }
                List<String> one = css ? Collections.singletonList(r.selector) : null;
                firstEntry = appendEntry(data, firstEntry, r, one);
            }
            if (merged != null) appendEntry(data, firstEntry, null, merged);
            data.append(']');
        }
        data.append(']');
        if (!anyCss) return "";
        return COSMETIC_PREFIX + data + COSMETIC_SUFFIX;
    }

    /** Append one entry: {@code r == null} is a merged unconditional group. */
    private static boolean appendEntry(StringBuilder sb, boolean first,
                                       ContentRuleMatcher.Rule r, List<String> selectors) {
        if (!first) sb.append(',');
        sb.append('{');
        if (r == null || r.action == ContentRuleMatcher.ACT_CSS_DISPLAY_NONE) {
            sb.append("\"a\":\"c\"");
        } else {
            sb.append("\"a\":\"i\"");
        }
        if (r != null) {
            sb.append(",\"f\":").append(quote(r.urlFilter));
            if (r.caseSensitive) sb.append(",\"cs\":1");
            appendList(sb, "id", r.ifDomain);
            appendList(sb, "ud", r.unlessDomain);
            appendList(sb, "it", r.ifTopUrl);
            appendList(sb, "ut", r.unlessTopUrl);
            if (r.resourceMask != -1
                    && (r.resourceMask & ContentRuleMatcher.ResourceType.DOCUMENT.bit()) == 0) {
                // A cosmetic rule limited to other resource types never
                // applies to a page.
                sb.append(",\"never\":1");
            }
            if (!r.topFrameAllowed) sb.append(",\"cf\":1");
        }
        if (selectors != null) {
            appendList(sb, "s", selectors.toArray(new String[0]));
        }
        sb.append('}');
        return false;
    }

    private static void appendList(StringBuilder sb, String key, String[] values) {
        if (values == null) return;
        sb.append(",\"").append(key).append("\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(values[i]));
        }
        sb.append(']');
    }

    /** A JSON / JavaScript string literal. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case ' ': sb.append("\\u2028"); break;
                case ' ': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // Canvas 37 D11: the cosmetic runtime.  `D` is an array of lists, each an
    // array of entries {a:'c'|'i', f, cs, id, ud, it, ut, never, cf, s}.
    private static final String COSMETIC_PREFIX =
        "(function(){try{var D=";
    private static final String COSMETIC_SUFFIX =
        ";var top=true;try{top=window.top===window;}catch(e){top=false;}"
        + "var page=location.href;"
        + "if(!top){try{var ao=location.ancestorOrigins;"
        + "if(ao&&ao.length){page=ao[ao.length-1]+'/';}}catch(e){}}"
        + "var host='';try{host=new URL(page).hostname.toLowerCase();}catch(e){}"
        + "function rx(s,cs){try{return new RegExp(s,cs?'':'i');}catch(e){return null;}}"
        + "function dm(l){for(var i=0;i<l.length;i++){var d=l[i];"
        + "if(d.charAt(0)==='*'){d=d.slice(1);"
        + "if(host===d||host.slice(-d.length-1)==='.'+d)return true;}"
        + "else if(host===d)return true;}return false;}"
        + "function anyRx(l,cs){for(var i=0;i<l.length;i++){var r=rx(l[i],cs);"
        + "if(r&&r.test(page))return true;}return false;}"
        + "var out=[];"
        + "for(var L=0;L<D.length;L++){var sel=[];var es=D[L];"
        + "for(var k=0;k<es.length;k++){var e=es[k];"
        + "if(e.never)continue;if(e.cf&&top)continue;"
        + "if(e.f!==undefined){var r=rx(e.f,e.cs);if(!r||!r.test(page))continue;}"
        + "if(e.id&&!dm(e.id))continue;if(e.ud&&dm(e.ud))continue;"
        + "if(e.it&&!anyRx(e.it,e.cs))continue;if(e.ut&&anyRx(e.ut,e.cs))continue;"
        + "if(e.a==='i'){sel=[];continue;}"
        + "for(var j=0;j<e.s.length;j++)sel.push(e.s[j]);}"
        + "for(var j2=0;j2<sel.length;j2++)out.push(sel[j2]);}"
        + "if(!out.length)return;"
        + "function ins(){var st=document.createElement('style');"
        + "st.setAttribute('data-webview-content-rules','');"
        + "(document.head||document.documentElement).appendChild(st);var sh=st.sheet;"
        + "for(var i=0;i<out.length;i+=" + SELECTOR_CHUNK + "){"
        + "var c=out.slice(i,i+" + SELECTOR_CHUNK + ");"
        + "try{sh.insertRule(c.join(',')+'{display:none!important}',sh.cssRules.length);}"
        + "catch(x){for(var m=0;m<c.length;m++){"
        + "try{sh.insertRule(c[m]+'{display:none!important}',sh.cssRules.length);}"
        + "catch(y){}}}}}"
        + "if(document.documentElement){ins();}else{"
        + "var mo=new MutationObserver(function(){if(document.documentElement){"
        + "mo.disconnect();ins();}});mo.observe(document,{childList:true});}"
        + "}catch(e){}})();";

    // ------------------------------------------------------------------
    // Popup seeding (Canvas 37 D7)
    // ------------------------------------------------------------------

    /** Remember the opener's lists for a popup awaiting adoption.  Keeps the
     *  newest {@link #POPUP_MAX}; an empty list records nothing. */
    public static void recordForPopup(long popupId, List<WebViewContentRuleList> openerLists) {
        if (openerLists == null || openerLists.isEmpty()) return;
        synchronized (POPUPS) {
            POPUPS.put(popupId, new ArrayList<>(openerLists));
        }
    }

    /** @return the opener's lists for {@code popupId}, once; {@code null}
     *  when none were recorded */
    public static List<WebViewContentRuleList> takeForPopup(long popupId) {
        synchronized (POPUPS) {
            return POPUPS.remove(popupId);
        }
    }

    private static void forwardUncaught(Throwable t) {
        Thread th = Thread.currentThread();
        Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
        if (h == null) h = th.getUncaughtExceptionHandler();
        if (h != null) {
            try {
                h.uncaughtException(th, t);
            } catch (Throwable ignored) {
                // never throw to native
            }
        }
    }
}
