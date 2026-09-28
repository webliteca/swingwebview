package ca.weblite.webview;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * Canvas 30 D1–D4: the registry — names, reserved schemes, the freeze at the first engine, and the
 * capability check (story 8 AC8, AC9, AC10).
 */
public class WebViewSchemesTest {

    private final List<String[]> installs = new ArrayList<String[]>();
    private static final WebViewSchemeHandler NOOP = new WebViewSchemeHandler() {
        @Override
        public void handle(WebViewSchemeRequest request, WebViewSchemeResponder responder) {
            responder.respond(WebViewSchemeResponse.text(200, "ok"));
        }
    };

    @Before
    public void seams() {
        WebViewSchemes.resetForTests();
        WebViewSchemes.available = supported(true);
        WebViewSchemes.installer = new WebViewSchemes.Installer() {
            @Override
            public void install(String[] schemes, Object dispatcher) {
                installs.add(schemes);
            }
        };
        WebViewSchemes.sink = new SchemeDispatcher.Sink() {
            @Override
            public void respond(long id, int status, String[] headerPairs, byte[] body) {
            }
        };
    }

    @After
    public void reset() {
        WebViewSchemes.resetForTests();
    }

    private static BooleanSupplier supported(final boolean b) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return b;
            }
        };
    }

    private static String refusal(String scheme, WebViewSchemeHandler h) {
        try {
            WebViewSchemes.register(scheme, h);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    @Test
    public void aValidNameRegisters() {
        WebViewSchemes.register("Demo", NOOP);
        WebViewSchemes.register("my-app+x.v2", NOOP);
        assertEquals(Arrays.asList("demo", "my-app+x.v2"), WebViewSchemes.registeredSchemes());
    }

    @Test
    public void theWebsOwnSchemesAreRefused() {                                  // AC8
        assertEquals("“https” cannot be registered: it is one of the web's own schemes.",
                refusal("https", NOOP));
        for (String s : new String[] {"file", "ws", "about", "javascript", "Data", "blob", "wss", "ftp", "http"}) {
            assertTrue(s, refusal(s, NOOP).contains("one of the web's own schemes"));
        }
    }

    @Test
    public void invalidNamesAreRefused() {
        for (String s : new String[] {"a", "9x", "bad_name", "abcdefghijklmnopqrstuvwxyzabcdefg", "", null}) {
            String r = refusal(s, NOOP);
            assertNotNull(String.valueOf(s), r);
            assertTrue(r, r.contains("is not a valid scheme name"));
        }
    }

    @Test
    public void duplicatesAndMissingHandlersAreRefused() {
        WebViewSchemes.register("demo", NOOP);
        assertEquals("“demo” is already registered.", refusal("DEMO", NOOP));
        assertEquals("A handler is required.", refusal("other", null));
    }

    @Test
    public void anOlderNativeSaysSo() {                                          // AC10
        WebViewSchemes.available = supported(false);
        assertFalse(WebViewSchemes.isSupported());
        try {
            WebViewSchemes.register("demo", NOOP);
            fail();
        } catch (UnsupportedOperationException e) {
            assertEquals("Custom URL schemes are not available in this version of the native library", e.getMessage());
        }
    }

    @Test
    public void aThrowingProbeIsNotSupported() {
        WebViewSchemes.available = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                throw new UnsatisfiedLinkError("old native");
            }
        };
        assertFalse(WebViewSchemes.isSupported());
    }

    @Test
    public void registeringAfterTheFirstEngineIsRefused() {                      // AC9
        WebViewSchemes.freezeForEngine();
        try {
            WebViewSchemes.register("demo", NOOP);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("Custom schemes must be registered before the first WebView is created.", e.getMessage());
        }
    }

    @Test
    public void nothingRegisteredInstallsNothing() {                             // D2
        WebViewSchemes.freezeForEngine();
        assertTrue(installs.isEmpty());
        assertNull(WebViewSchemes.dispatcher());
    }

    @Test
    public void theFirstEngineInstallsOnce() {                                   // D1
        WebViewSchemes.register("demo", NOOP);
        WebViewSchemes.freezeForEngine();
        WebViewSchemes.freezeForEngine();
        assertEquals(1, installs.size());
        assertArrayEquals(new String[] {"demo"}, installs.get(0));
        assertNotNull(WebViewSchemes.dispatcher());
    }

    @Test
    public void theDefaultProbeIsFalseWhenHeadlessWithoutLoadingNative() {     // D4 "AWT first"
        org.junit.Assume.assumeTrue(java.awt.GraphicsEnvironment.isHeadless());
        WebViewSchemes.resetForTests();
        assertFalse(WebViewSchemes.isSupported());
    }
}
