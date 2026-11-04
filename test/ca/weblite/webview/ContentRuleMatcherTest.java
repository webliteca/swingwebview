/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import static ca.weblite.webview.ContentRuleMatcher.ResourceType.DOCUMENT;
import static ca.weblite.webview.ContentRuleMatcher.ResourceType.FETCH;
import static ca.weblite.webview.ContentRuleMatcher.ResourceType.IMAGE;
import static ca.weblite.webview.ContentRuleMatcher.ResourceType.SCRIPT;
import static ca.weblite.webview.WebViewContentRuleCallback.ALLOW;
import static ca.weblite.webview.WebViewContentRuleCallback.BLOCK;
import static ca.weblite.webview.WebViewContentRuleCallback.BLOCK_COOKIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

/** Canvas 37 Op 8: the portable rule matcher used on Windows. */
public class ContentRuleMatcherTest {

    private static final String PAGE = "https://news.example.com/article";

    private static ContentRuleMatcher m(String json) {
        return ContentRuleMatcher.parse(json);
    }

    private static void rejects(String json, String fragment) {
        try {
            ContentRuleMatcher.parse(json);
            fail("expected rejection: " + json);
        } catch (WebViewContentRuleException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains(fragment));
        }
    }

    @Test
    public void rejectsInvalidLists() {
        rejects("{}", "must be a JSON array");
        rejects("[]", "empty");
        rejects("[1", "invalid JSON");
        rejects("[{\"action\":{\"type\":\"block\"}}]", "rule 0: missing trigger");
        rejects("[{\"trigger\":{}}]", "rule 0: missing action");
        rejects("[{\"trigger\":{},\"action\":{\"type\":\"block\"}}]", "url-filter");
        rejects("[{\"trigger\":{\"url-filter\":\"(\"},\"action\":{\"type\":\"block\"}}]",
            "invalid regular expression");
        rejects("[{\"trigger\":{\"url-filter\":\".*\"},\"action\":{\"type\":\"zap\"}}]",
            "unknown action.type");
        rejects("[{\"trigger\":{\"url-filter\":\".*\"},\"action\":{\"type\":\"css-display-none\"}}]",
            "selector");
        rejects("[{\"trigger\":{\"url-filter\":\".*\",\"if-domain\":[\"a.com\"],"
            + "\"unless-domain\":[\"b.com\"]},\"action\":{\"type\":\"block\"}}]",
            "both if-domain and unless-domain");
        rejects("[{\"trigger\":{\"url-filter\":\".*\",\"resource-type\":[\"gif\"]},"
            + "\"action\":{\"type\":\"block\"}}]", "unknown resource-type");
        rejects("[{\"trigger\":{\"url-filter\":\".*\"},\"action\":{\"type\":\"block\"}},"
            + "{\"trigger\":{\"url-filter\":\".*\"},\"action\":{}}]", "rule 1:");
    }

    @Test
    public void blocksByUrlFilter() {
        ContentRuleMatcher m = m("[{\"trigger\":{\"url-filter\":\"/ads/\"},"
            + "\"action\":{\"type\":\"block\"}}]");
        assertEquals(BLOCK, m.decide("https://cdn.example.net/ads/banner.png", PAGE, IMAGE));
        assertEquals(ALLOW, m.decide("https://cdn.example.net/ok/logo.png", PAGE, IMAGE));
    }

    @Test
    public void caseSensitivity() {
        ContentRuleMatcher insensitive = m("[{\"trigger\":{\"url-filter\":\"AdServer\"},"
            + "\"action\":{\"type\":\"block\"}}]");
        assertEquals(BLOCK, insensitive.decide("https://x.com/adserver.js", PAGE, SCRIPT));
        ContentRuleMatcher sensitive = m("[{\"trigger\":{\"url-filter\":\"AdServer\","
            + "\"url-filter-is-case-sensitive\":true},\"action\":{\"type\":\"block\"}}]");
        assertEquals(ALLOW, sensitive.decide("https://x.com/adserver.js", PAGE, SCRIPT));
        assertEquals(BLOCK, sensitive.decide("https://x.com/AdServer.js", PAGE, SCRIPT));
    }

    @Test
    public void domainConditions() {
        ContentRuleMatcher onlyOn = m("[{\"trigger\":{\"url-filter\":\"tracker\","
            + "\"if-domain\":[\"example.com\"]},\"action\":{\"type\":\"block\"}}]");
        assertEquals(ALLOW, onlyOn.decide("https://t.net/tracker.js", PAGE, SCRIPT));
        assertEquals(BLOCK, onlyOn.decide("https://t.net/tracker.js", "https://example.com/", SCRIPT));

        ContentRuleMatcher withSubs = m("[{\"trigger\":{\"url-filter\":\"tracker\","
            + "\"if-domain\":[\"*example.com\"]},\"action\":{\"type\":\"block\"}}]");
        assertEquals(BLOCK, withSubs.decide("https://t.net/tracker.js", PAGE, SCRIPT));
        assertEquals(ALLOW, withSubs.decide("https://t.net/tracker.js",
            "https://notexample.com/", SCRIPT));

        ContentRuleMatcher unless = m("[{\"trigger\":{\"url-filter\":\"/ads/\","
            + "\"unless-domain\":[\"*example.com\"]},\"action\":{\"type\":\"block\"}}]");
        assertEquals(ALLOW, unless.decide("https://c.net/ads/x.png", PAGE, IMAGE));
        assertEquals(BLOCK, unless.decide("https://c.net/ads/x.png", "https://other.org/", IMAGE));
    }

    @Test
    public void resourceTypesIncludingRaw() {
        ContentRuleMatcher images = m("[{\"trigger\":{\"url-filter\":\"/ads/\","
            + "\"resource-type\":[\"image\"]},\"action\":{\"type\":\"block\"}}]");
        assertEquals(BLOCK, images.decide("https://c.net/ads/x", PAGE, IMAGE));
        assertEquals(ALLOW, images.decide("https://c.net/ads/x", PAGE, SCRIPT));
        ContentRuleMatcher raw = m("[{\"trigger\":{\"url-filter\":\"/beacon\","
            + "\"resource-type\":[\"raw\"]},\"action\":{\"type\":\"block\"}}]");
        assertEquals(BLOCK, raw.decide("https://c.net/beacon", PAGE, FETCH));
        assertEquals(ALLOW, raw.decide("https://c.net/beacon", PAGE, IMAGE));
    }

    @Test
    public void loadType() {
        ContentRuleMatcher third = m("[{\"trigger\":{\"url-filter\":\"\\\\.js\","
            + "\"load-type\":[\"third-party\"]},\"action\":{\"type\":\"block\"}}]");
        assertEquals(ALLOW, third.decide("https://static.example.com/a.js", PAGE, SCRIPT));
        assertEquals(BLOCK, third.decide("https://cdn.other.net/a.js", PAGE, SCRIPT));
        // co.uk: bbc.co.uk and news.bbc.co.uk are the same site; other.co.uk is not.
        assertEquals(ALLOW, third.decide("https://news.bbc.co.uk/a.js",
            "https://www.bbc.co.uk/", SCRIPT));
        assertEquals(BLOCK, third.decide("https://other.co.uk/a.js",
            "https://www.bbc.co.uk/", SCRIPT));
        ContentRuleMatcher first = m("[{\"trigger\":{\"url-filter\":\"\\\\.js\","
            + "\"load-type\":[\"first-party\"]},\"action\":{\"type\":\"block\"}}]");
        assertEquals(BLOCK, first.decide("https://static.example.com/a.js", PAGE, SCRIPT));
        // The page document itself is first party.
        assertEquals(ALLOW, third.decide("https://x.org/p.js", "https://x.org/p.js", DOCUMENT));
    }

    @Test
    public void ignorePreviousRulesAndBlockCookies() {
        ContentRuleMatcher m = m("["
            + "{\"trigger\":{\"url-filter\":\"/ads/\"},\"action\":{\"type\":\"block\"}},"
            + "{\"trigger\":{\"url-filter\":\".*\",\"if-domain\":[\"*example.com\"]},"
            + "\"action\":{\"type\":\"ignore-previous-rules\"}},"
            + "{\"trigger\":{\"url-filter\":\"track\"},\"action\":{\"type\":\"block-cookies\"}}]");
        assertEquals(ALLOW, m.decide("https://c.net/ads/x.png", PAGE, IMAGE));
        assertEquals(BLOCK, m.decide("https://c.net/ads/x.png", "https://other.org/", IMAGE));
        assertEquals(BLOCK_COOKIES, m.decide("https://c.net/track", PAGE, FETCH));
    }

    @Test
    public void indexFindsDomainStyleAndGenericRules() {
        ContentRuleMatcher m = m("["
            + "{\"trigger\":{\"url-filter\":\"^[^:]+:(//)?([^/]+\\\\.)?doubleclick\\\\.net[/:]\"},"
            + "\"action\":{\"type\":\"block\"}},"
            + "{\"trigger\":{\"url-filter\":\".*/q$\"},"
            + "\"action\":{\"type\":\"block\"}}]");
        assertEquals(1, m.genericCount());
        assertEquals(BLOCK, m.decide("https://ad.doubleclick.net/x", PAGE, IMAGE));
        assertEquals(BLOCK, m.decide("https://doubleclick.net/x", PAGE, IMAGE));
        assertEquals(ALLOW, m.decide("https://notdoubleclick.com/x", PAGE, IMAGE));
        assertEquals(BLOCK, m.decide("https://c.net/q", PAGE, IMAGE));
    }

    @Test
    public void requiredLiterals() {
        assertEquals(Arrays.asList("/ads/"), ContentRuleMatcher.requiredLiterals("/ads/"));
        assertEquals(Arrays.asList(":", "doubleclick.net"),
            ContentRuleMatcher.requiredLiterals("^[^:]+:(//)?([^/]+\\.)?doubleclick\\.net[/:]"));
        assertEquals(Arrays.asList("http", "://ads.example.com/"),
            ContentRuleMatcher.requiredLiterals("^https?://ads\\.example\\.com/"));
        assertEquals(Arrays.asList("ba", "x"), ContentRuleMatcher.requiredLiterals("ba+x"));
        assertEquals(Arrays.asList("ab", "yz"), ContentRuleMatcher.requiredLiterals("abc*yz"));
        assertTrue(ContentRuleMatcher.requiredLiterals("ads|track").isEmpty());
        assertEquals(Arrays.asList("id="), ContentRuleMatcher.requiredLiterals("\\d+id=\\w"));
    }

    @Test
    public void fiftyThousandRulesAreFast() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50000; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"trigger\":{\"url-filter\":\"^[^:]+:(//)?([^/]+\\\\.)?host")
              .append(i).append("\\\\.com[/:]\"},\"action\":{\"type\":\"block\"}}");
        }
        sb.append(']');
        ContentRuleMatcher m = m(sb.toString());
        assertEquals(50000, m.size());
        long start = System.nanoTime();
        int blocked = 0;
        for (int i = 0; i < 1000; i++) {
            if (m.decide("https://cdn.host" + (i * 37) + ".com/path/to/resource.js?x=" + i,
                    PAGE, SCRIPT) == BLOCK) {
                blocked++;
            }
        }
        long ms = (System.nanoTime() - start) / 1000000L;
        assertEquals(1000, blocked);
        assertTrue("1,000 decisions took " + ms + " ms", ms < 1000);
    }
}
