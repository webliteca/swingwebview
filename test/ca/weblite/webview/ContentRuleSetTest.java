/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import static ca.weblite.webview.WebViewContentRuleCallback.ALLOW;
import static ca.weblite.webview.WebViewContentRuleCallback.BLOCK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Canvas 37 Op 8: a component's applied lists. */
public class ContentRuleSetTest {

    private static final File DIR = new File("/tmp/rules");
    private static final String PAGE = "https://example.com/";
    private static final int IMAGE = ContentRuleMatcher.ResourceType.IMAGE.ordinal();

    private static WebViewContentRuleList list(String id, String json) {
        return new WebViewContentRuleList(DIR, id, ContentRuleMatcher.parse(json));
    }

    private static final String BLOCK_ADS =
        "[{\"trigger\":{\"url-filter\":\"/ads/\"},\"action\":{\"type\":\"block\"}}]";
    private static final String IGNORE_ALL =
        "[{\"trigger\":{\"url-filter\":\".*\"},\"action\":{\"type\":\"ignore-previous-rules\"}}]";

    @Test
    public void orderAndReAddMovesToEnd() {
        ContentRuleSet s = new ContentRuleSet();
        s.add(list("a", BLOCK_ADS));
        s.add(list("b", BLOCK_ADS));
        s.add(list("a", BLOCK_ADS));
        assertEquals("b", s.lists().get(0).identifier());
        assertEquals("a", s.lists().get(1).identifier());
        assertTrue(s.remove("b"));
        assertEquals(1, s.lists().size());
        s.clear();
        assertTrue(s.isEmpty());
    }

    @Test
    public void listsDecideIndependently() {
        ContentRuleSet s = new ContentRuleSet();
        s.add(list("ads", BLOCK_ADS));
        s.add(list("exceptions", IGNORE_ALL));
        // A later list's ignore-previous-rules does not unblock an earlier list.
        assertEquals(BLOCK, s.decide("https://c.net/ads/x.png", PAGE, IMAGE));
        // Within one list it does.
        ContentRuleSet one = new ContentRuleSet();
        one.add(list("both", "[" + BLOCK_ADS.substring(1, BLOCK_ADS.length() - 1) + ","
            + IGNORE_ALL.substring(1)));
        assertEquals(ALLOW, one.decide("https://c.net/ads/x.png", PAGE, IMAGE));
        assertEquals(ALLOW, new ContentRuleSet().decide("https://c.net/ads/x", PAGE, IMAGE));
    }

    @Test
    public void cosmeticScriptMergesUnconditionalSelectors() {
        ContentRuleSet s = new ContentRuleSet();
        assertEquals("", s.cosmeticScript());
        s.add(list("ads", BLOCK_ADS));
        assertEquals("", s.cosmeticScript());
        s.add(list("css", "["
            + "{\"trigger\":{\"url-filter\":\".*\"},\"action\":{\"type\":\"css-display-none\","
            + "\"selector\":\".ad-slot\"}},"
            + "{\"trigger\":{\"url-filter\":\".*\"},\"action\":{\"type\":\"css-display-none\","
            + "\"selector\":\"#banner\"}},"
            + "{\"trigger\":{\"url-filter\":\".*\",\"if-domain\":[\"*example.com\"]},"
            + "\"action\":{\"type\":\"css-display-none\",\"selector\":\".promo\"}}]"));
        String js = s.cosmeticScript();
        assertTrue(js, js.contains("{\"a\":\"c\",\"s\":[\".ad-slot\",\"#banner\"]}"));
        assertTrue(js, js.contains("\"id\":[\"*example.com\"]"));
        assertTrue(js, js.startsWith("(function(){"));
    }

    @Test
    public void popupRecordTakenOnceAndBounded() {
        List<WebViewContentRuleList> l = Collections.singletonList(list("a", BLOCK_ADS));
        ContentRuleSet.recordForPopup(7L, l);
        assertEquals(l, ContentRuleSet.takeForPopup(7L));
        assertNull(ContentRuleSet.takeForPopup(7L));
        for (long i = 100; i < 100 + ContentRuleSet.POPUP_MAX + 1; i++) {
            ContentRuleSet.recordForPopup(i, l);
        }
        assertNull(ContentRuleSet.takeForPopup(100L));
        assertEquals(l, ContentRuleSet.takeForPopup(100L + ContentRuleSet.POPUP_MAX));
        ContentRuleSet.recordForPopup(9L, Collections.<WebViewContentRuleList>emptyList());
        assertNull(ContentRuleSet.takeForPopup(9L));
    }
}
