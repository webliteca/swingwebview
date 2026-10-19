/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Unit tests for {@link ContextMenuDispatcher} and the contributed-item value
 * types (Canvas 36 D2-D5, D7), without a live native engine.  The native
 * menus are verified by {@code WebViewNativeContextMenuDemo}.
 */
public class ContextMenuDispatcherTest {

    /** Minimal {@link WebViewComponent} usable as a dispatcher source. */
    private static final class StubComponent extends WebViewComponent {
        @Override public WebViewComponent setUrl(String url) { return this; }
        @Override public String getUrl() { return ""; }
        @Override public WebViewComponent setDebug(boolean debug) { return this; }
        @Override public WebViewComponent addOnBeforeLoad(String js) { return this; }
        @Override public WebViewComponent eval(String js) { return this; }
        @Override public CompletableFuture<String> evalAsync(String js) {
            CompletableFuture<String> f = new CompletableFuture<String>();
            f.completeExceptionally(new IllegalStateException("stub"));
            return f;
        }
        @Override public WebViewComponent addJavascriptCallback(
                String name, WebView.JavascriptCallback cb) { return this; }
        @Override public WebViewComponent addJavascriptFunction(
                String name, JavascriptFunction fn) { return this; }
        @Override public WebViewComponent addJavascriptFunction(
                String name, AsyncJavascriptFunction fn) { return this; }
        @Override public WebViewComponent dispatch(Runnable r) { return this; }
        @Override public void dispose() { }
    }

    private static final int NATIVE = ContextMenuDispatcher.FLAG_NATIVE;

    private StubComponent component;
    private WebViewMouseDispatcher mouse;
    private ContextMenuDispatcher dispatcher;
    private Thread.UncaughtExceptionHandler savedHandler;
    private final List<Throwable> uncaught = new ArrayList<Throwable>();
    private final List<String> chosen = new ArrayList<String>();
    private final List<WebViewContextMenuContext> chosenContexts =
        new ArrayList<WebViewContextMenuContext>();
    private final List<Boolean> chosenOnEdt = new ArrayList<Boolean>();

    @Before
    public void setUp() {
        component = new StubComponent();
        mouse = new WebViewMouseDispatcher(component);
        dispatcher = new ContextMenuDispatcher(component, mouse);
        savedHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                synchronized (uncaught) { uncaught.add(e); }
            }
        });
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler);
    }

    private Consumer<WebViewContextMenuContext> record(final String id) {
        return new Consumer<WebViewContextMenuContext>() {
            @Override public void accept(WebViewContextMenuContext c) {
                chosen.add(id);
                chosenContexts.add(c);
                chosenOnEdt.add(SwingUtilities.isEventDispatchThread());
            }
        };
    }

    private WebViewContextMenuItem item(String id, String label) {
        return WebViewContextMenuItem.of(id, label, record(id));
    }

    private static WebViewContextMenuContributor fixed(final WebViewContextMenuItem... items) {
        return new WebViewContextMenuContributor() {
            @Override public List<WebViewContextMenuItem> contribute(WebViewContextMenuContext c) {
                return Arrays.asList(items);
            }
        };
    }

    /** Captures the context each request builds. */
    private final AtomicReference<WebViewContextMenuContext> seen =
        new AtomicReference<WebViewContextMenuContext>();

    private WebViewContextMenuContributor capturing() {
        return new WebViewContextMenuContributor() {
            @Override public List<WebViewContextMenuItem> contribute(WebViewContextMenuContext c) {
                seen.set(c);
                return Collections.singletonList(item("cap", "Cap"));
            }
        };
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() { @Override public void run() { } });
    }

    /** A Canvas 9 record as the shim would have produced it. */
    private WebViewMouseDispatcher.Recent record(String selection, String link,
            String image, boolean editable, int clientX, int clientY,
            Rectangle bounds, String control, long nanos) {
        DomTarget t = new DomTarget(image != null ? "IMG" : "P", "", null, null,
            link, image, null, editable, selection, "about:blank", "about:blank");
        WebViewMouseEvent ev = new WebViewMouseEvent("contextmenu", 3, 0,
            clientX, clientY, clientX, clientY, clientX, clientY, 0L, t, component);
        return new WebViewMouseDispatcher.Recent(ev, bounds, control, nanos);
    }

    private void storeRecord(String selection, String link, String image,
                             boolean editable, int x, int y, Rectangle bounds,
                             String control) {
        mouse.storeRecent(record(selection, link, image, editable, x, y, bounds,
            control, System.nanoTime()));
    }

    // ------------------------------------------------------------------ D4

    @Test
    public void noContributorAnswersNull() {
        assertNull(dispatcher.requested(NATIVE, null, null, null, null, 1, 2));
        assertFalse(dispatcher.hasContributors());
    }

    @Test
    public void itemsProduceTheWireAnswer() {
        dispatcher.add(fixed(item("a", "Ask"), item("b", "Off").withEnabled(false)));
        String[] wire = dispatcher.requested(NATIVE, null, null, null, null, 1, 2);
        assertArrayEquals(new String[] {"1", "1Ask", "0Off"}, wire);
        String[] next = dispatcher.requested(NATIVE, null, null, null, null, 1, 2);
        assertEquals("2", next[0]);
    }

    @Test
    public void contributorsAskedInOrderAndConcatenated() {
        final List<String> order = new ArrayList<String>();
        dispatcher.add(new WebViewContextMenuContributor() {
            @Override public List<WebViewContextMenuItem> contribute(WebViewContextMenuContext c) {
                order.add("first");
                return Arrays.asList(item("a", "A"), null);
            }
        });
        dispatcher.add(new WebViewContextMenuContributor() {
            @Override public List<WebViewContextMenuItem> contribute(WebViewContextMenuContext c) {
                order.add("second");
                return null;
            }
        });
        dispatcher.add(fixed(item("b", "B")));
        String[] wire = dispatcher.requested(NATIVE, null, null, null, null, -1, -1);
        assertEquals(Arrays.asList("first", "second"), order);
        assertArrayEquals(new String[] {"1", "1A", "1B"}, wire);
    }

    @Test
    public void atMostThirtyTwoItems() {
        List<WebViewContextMenuItem> many = new ArrayList<WebViewContextMenuItem>();
        for (int i = 0; i < 40; i++) many.add(item("i" + i, "Item " + i));
        dispatcher.add(fixed(many.toArray(new WebViewContextMenuItem[0])));
        String[] wire = dispatcher.requested(NATIVE, null, null, null, null, -1, -1);
        assertEquals(33, wire.length);
    }

    @Test
    public void throwingContributorIsSkipped() {
        final RuntimeException boom = new RuntimeException("boom");
        dispatcher.add(new WebViewContextMenuContributor() {
            @Override public List<WebViewContextMenuItem> contribute(WebViewContextMenuContext c) {
                throw boom;
            }
        });
        dispatcher.add(fixed(item("a", "Ask")));
        String[] wire = dispatcher.requested(NATIVE, null, null, null, null, -1, -1);
        assertArrayEquals(new String[] {"1", "1Ask"}, wire);
        assertEquals(1, uncaught.size());
        assertSame(boom, uncaught.get(0));
    }

    @Test
    public void removedContributorNoLongerAsked() {
        WebViewContextMenuContributor c = fixed(item("a", "Ask"));
        dispatcher.add(c);
        dispatcher.remove(c);
        dispatcher.remove(null);
        assertNull(dispatcher.requested(NATIVE, null, null, null, null, -1, -1));
    }

    @Test(expected = NullPointerException.class)
    public void addRejectsNull() {
        dispatcher.add(null);
    }

    // ------------------------------------------------------------------ D5

    @Test
    public void selectedFiresOnceOnTheEdtWithTheContext() throws Exception {
        dispatcher.add(fixed(item("a", "Ask"), item("b", "Other")));
        storeRecord("hello", null, null, false, 10, 20, new Rectangle(1, 2, 30, 40), "");
        String[] wire = dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION,
            null, null, null, null, 5, 6);
        long token = Long.parseLong(wire[0]);
        dispatcher.selected(token, 0);
        dispatcher.selected(token, 0);
        dispatcher.selected(token, 1);
        flushEdt();
        assertEquals(Collections.singletonList("a"), chosen);
        assertEquals(Collections.singletonList(Boolean.TRUE), chosenOnEdt);
        WebViewContextMenuContext c = chosenContexts.get(0);
        assertSame(component, c.source());
        assertEquals("hello", c.selectionText());
        assertEquals(new Point(10, 20), c.point());
        assertEquals(new Rectangle(1, 2, 30, 40), c.selectionBounds());
    }

    @Test
    public void wrongTokenOldTokenAndBadIndexIgnored() throws Exception {
        dispatcher.add(fixed(item("a", "Ask")));
        long first = Long.parseLong(dispatcher.requested(NATIVE, null, null, null, null, 1, 1)[0]);
        long second = Long.parseLong(dispatcher.requested(NATIVE, null, null, null, null, 1, 1)[0]);
        dispatcher.selected(first, 0);           // older menu
        dispatcher.selected(second + 99, 0);     // unknown token
        dispatcher.selected(second, 1);          // out of range
        dispatcher.selected(second, -1);
        flushEdt();
        assertTrue(chosen.isEmpty());
        dispatcher.selected(second, 0);
        flushEdt();
        assertEquals(Collections.singletonList("a"), chosen);
    }

    @Test
    public void disabledItemNeverFires() throws Exception {
        dispatcher.add(fixed(item("a", "Ask").withEnabled(false)));
        long token = Long.parseLong(dispatcher.requested(NATIVE, null, null, null, null, 1, 1)[0]);
        dispatcher.selected(token, 0);
        flushEdt();
        assertTrue(chosen.isEmpty());
    }

    @Test
    public void newerRequestWithoutItemsInvalidatesOlderMenu() throws Exception {
        final boolean[] give = {true};
        dispatcher.add(new WebViewContextMenuContributor() {
            @Override public List<WebViewContextMenuItem> contribute(WebViewContextMenuContext c) {
                return give[0] ? Collections.singletonList(item("a", "Ask")) : null;
            }
        });
        long token = Long.parseLong(dispatcher.requested(NATIVE, null, null, null, null, 1, 1)[0]);
        give[0] = false;
        assertNull(dispatcher.requested(NATIVE, null, null, null, null, 1, 1));
        dispatcher.selected(token, 0);
        flushEdt();
        assertTrue(chosen.isEmpty());
    }

    @Test
    public void disposedDispatcherIgnoresEverything() throws Exception {
        dispatcher.add(fixed(item("a", "Ask")));
        long token = Long.parseLong(dispatcher.requested(NATIVE, null, null, null, null, 1, 1)[0]);
        dispatcher.dispose();
        dispatcher.selected(token, 0);
        flushEdt();
        assertTrue(chosen.isEmpty());
        assertNull(dispatcher.requested(NATIVE, null, null, null, null, 1, 1));
    }

    @Test
    public void throwingActionReachesUncaughtHandler() throws Exception {
        final RuntimeException boom = new RuntimeException("action");
        dispatcher.add(fixed(WebViewContextMenuItem.of("a", "Ask",
            new Consumer<WebViewContextMenuContext>() {
                @Override public void accept(WebViewContextMenuContext c) { throw boom; }
            })));
        long token = Long.parseLong(dispatcher.requested(NATIVE, null, null, null, null, 1, 1)[0]);
        dispatcher.selected(token, 0);
        flushEdt();
        assertEquals(Collections.singletonList((Throwable) boom), uncaught);
    }

    // ----------------------------------------------------- context assembly

    @Test
    public void nativeFlagsAndUrisWin() {
        dispatcher.add(capturing());
        storeRecord("", "record-link", null, false, 3, 4, null, "");
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_LINK
                | ContextMenuDispatcher.FLAG_EDITABLE,
            "https://example.com/a", "", null, null, 7, 8);
        WebViewContextMenuContext c = seen.get();
        assertEquals("https://example.com/a", c.linkUrl());
        assertNull(c.imageUrl());
        assertNull(c.mediaUrl());
        assertTrue(c.isEditable());
        assertEquals("", c.selectionText());
        assertFalse(c.hasSelection());
        assertEquals(new Point(3, 4), c.point());
        assertNotNull(c.target());
    }

    @Test
    public void nativeSelectionTextWins() {
        dispatcher.add(capturing());
        storeRecord("from record", null, null, false, 3, 4, new Rectangle(0, 0, 5, 5), "");
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION,
            null, null, null, "from engine", 1, 1);
        assertEquals("from engine", seen.get().selectionText());
        assertEquals(new Rectangle(0, 0, 5, 5), seen.get().selectionBounds());
    }

    @Test
    public void recordSelectionOnlyWithSelectionFlag() {
        dispatcher.add(capturing());
        storeRecord("from record", null, null, false, 3, 4, new Rectangle(0, 0, 5, 5), "");
        dispatcher.requested(NATIVE, null, null, null, null, 1, 1);
        assertEquals("", seen.get().selectionText());
        assertNull(seen.get().selectionBounds());

        storeRecord("from record", null, null, false, 3, 4, null, "");
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION,
            null, null, null, null, 1, 1);
        assertEquals("from record", seen.get().selectionText());
    }

    @Test
    public void controlSelectionIsTheFallback() {
        dispatcher.add(capturing());
        storeRecord("", null, null, true, 3, 4, new Rectangle(1, 1, 9, 9), "typed text");
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION
                | ContextMenuDispatcher.FLAG_EDITABLE, null, null, null, null, 1, 1);
        assertEquals("typed text", seen.get().selectionText());
        assertEquals(new Rectangle(1, 1, 9, 9), seen.get().selectionBounds());
    }

    @Test
    public void withoutNativeFlagEverythingComesFromTheRecord() {
        dispatcher.add(capturing());
        storeRecord("sel", "https://l/", "https://i/cat.png", true, 11, 12, null, "");
        dispatcher.requested(0, null, null, null, null, 50, 60);
        WebViewContextMenuContext c = seen.get();
        assertEquals("sel", c.selectionText());
        assertEquals("https://l/", c.linkUrl());
        assertEquals("https://i/cat.png", c.imageUrl());
        assertTrue(c.isEditable());
        assertEquals(new Point(11, 12), c.point());
    }

    @Test
    public void withoutNativeFlagOrRecordTheContextIsEmpty() {
        dispatcher.add(capturing());
        dispatcher.requested(0, "ignored", null, null, null, 50, 60);
        WebViewContextMenuContext c = seen.get();
        assertEquals("", c.selectionText());
        assertNull(c.linkUrl());
        assertFalse(c.isEditable());
        assertEquals(new Point(50, 60), c.point());
        assertNull(c.target());
    }

    @Test
    public void pointFallsBackToNativeThenNull() {
        dispatcher.add(capturing());
        dispatcher.requested(NATIVE, null, null, null, null, 5, 6);
        assertEquals(new Point(5, 6), seen.get().point());
        dispatcher.requested(NATIVE, null, null, null, null, -1, 6);
        assertNull(seen.get().point());
        assertNull(seen.get().toScreenPoint());
    }

    @Test
    public void staleRecordIgnoredAndRecordUsedOnce() {
        dispatcher.add(capturing());
        mouse.storeRecent(record("old", null, null, false, 3, 4, null, "",
            System.nanoTime() - 5000L * 1000000L));
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION,
            null, null, null, null, 1, 1);
        assertEquals("", seen.get().selectionText());
        assertNull(seen.get().target());

        storeRecord("fresh", null, null, false, 3, 4, null, "");
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION,
            null, null, null, null, 1, 1);
        assertEquals("fresh", seen.get().selectionText());
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION,
            null, null, null, null, 1, 1);
        assertEquals("", seen.get().selectionText());
    }

    @Test
    public void contextCopiesPointsAndRectangles() {
        dispatcher.add(capturing());
        storeRecord("s", null, null, false, 3, 4, new Rectangle(1, 2, 3, 4), "");
        dispatcher.requested(NATIVE | ContextMenuDispatcher.FLAG_SELECTION,
            null, null, null, null, 1, 1);
        WebViewContextMenuContext c = seen.get();
        c.point().x = 99;
        c.selectionBounds().width = 99;
        assertEquals(new Point(3, 4), c.point());
        assertEquals(new Rectangle(1, 2, 3, 4), c.selectionBounds());
    }

    // ------------------------------------------------------- record parsing

    @Test
    public void dispatchedRecordIsKeptWithTrailingFields() {
        String rec = "1|contextmenu|3|0|10|20|10|20|10|20|5|P|||||||0|"
            + b64("Hello") + "|" + b64("about:blank") + "|" + b64("about:blank")
            + "|40|41|42|43|" + b64("ctl");
        mouse.dispatch("{\"name\":\"__webview_dom_event\",\"seq\":1,\"args\":[\""
            + b64(rec) + "\"]}");
        WebViewMouseDispatcher.Recent r = mouse.takeRecent(2000);
        assertNotNull(r);
        assertEquals("Hello", r.event.target().selectionText());
        assertEquals(new Rectangle(40, 41, 42, 43), r.selectionBounds);
        assertEquals("ctl", r.controlSelection);
        assertNull(mouse.takeRecent(2000));
    }

    @Test
    public void canvasNineRecordWithoutTrailingFieldsStillParses() {
        String rec = "1|contextmenu|3|0|10|20|10|20|10|20|5|P|||||||0||"
            + b64("about:blank") + "|" + b64("about:blank");
        mouse.dispatch("{\"name\":\"x\",\"seq\":1,\"args\":[\"" + b64(rec) + "\"]}");
        WebViewMouseDispatcher.Recent r = mouse.takeRecent(2000);
        assertNotNull(r);
        assertNull(r.selectionBounds);
        assertEquals("", r.controlSelection);
    }

    private static String b64(String s) {
        try {
            return java.util.Base64.getEncoder().encodeToString(s.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------- item (D2)

    @Test
    public void itemValidation() {
        Consumer<WebViewContextMenuContext> a = record("x");
        try { WebViewContextMenuItem.of(null, "L", a); fail(); }
        catch (NullPointerException e) { assertEquals("id", e.getMessage()); }
        try { WebViewContextMenuItem.of("x", null, a); fail(); }
        catch (NullPointerException e) { assertEquals("label", e.getMessage()); }
        try { WebViewContextMenuItem.of("x", "L", null); fail(); }
        catch (NullPointerException e) { assertEquals("action", e.getMessage()); }
        try { WebViewContextMenuItem.of("x", "", a); fail(); }
        catch (IllegalArgumentException expected) { }
        try { WebViewContextMenuItem.of("x", "a\u0000b", a); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void withEnabledCopies() {
        WebViewContextMenuItem on = item("x", "Label_&<b>");
        WebViewContextMenuItem off = on.withEnabled(false);
        assertTrue(on.isEnabled());
        assertFalse(off.isEnabled());
        assertEquals("x", off.id());
        assertEquals("Label_&<b>", off.label());
        assertSame(on.action(), off.action());
    }
}
