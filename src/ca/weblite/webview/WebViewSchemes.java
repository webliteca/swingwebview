package ca.weblite.webview;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Custom URL schemes served by the application (Canvas 30): register a scheme and its handler at
 * start-up, and every request to {@code scheme://…} from a WebView component is answered in Java,
 * with no local server and no port.
 *
 * <p><b>Register before the first WebView.</b> Engines fix their schemes when they are created, so
 * the registry is frozen by the first engine a component creates (D1). Registering after that is
 * refused. An application that registers nothing never touches the scheme natives (D2).
 *
 * <p>macOS, Linux and Windows serve schemes; {@link #isSupported()} is false only against a native
 * library built before this feature, and {@link #register} then says so.
 */
public final class WebViewSchemes {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9+.-]{1,31}");
    private static final Set<String> WEB_SCHEMES = new HashSet<String>(Arrays.asList(
            "http", "https", "file", "data", "blob", "about", "javascript", "ws", "wss", "ftp"));

    /** The installer the first engine calls when anything is registered; a seam for tests. */
    interface Installer {
        void install(String[] schemes, Object dispatcher);
    }

    /**
     * The native probe (D4). It runs at start-up, before any window, so it starts the AWT toolkit
     * before loading {@link WebViewNative}: on macOS that load pulls in {@code libawt_lwawt}, which
     * crashes the JVM if AWT has not started. Headless, no component can exist, so it answers false
     * without loading the native library.
     */
    private static final BooleanSupplier NATIVE_PROBE = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
            if (GraphicsEnvironment.isHeadless()) return false;
            Toolkit.getDefaultToolkit();
            return WebViewNative.webview_scheme_available();
        }
    };
    private static final Installer NATIVE_INSTALLER = new Installer() {
        @Override
        public void install(String[] schemes, Object dispatcher) {
            WebViewNative.webview_scheme_install(schemes, dispatcher);
        }
    };
    private static final SchemeDispatcher.Sink NATIVE_SINK = new SchemeDispatcher.Sink() {
        @Override
        public void respond(long id, int status, String[] headerPairs, byte[] body) {
            WebViewNative.webview_scheme_respond(id, status, headerPairs, body);
        }
    };

    private static final Map<String, WebViewSchemeHandler> handlers = new LinkedHashMap<String, WebViewSchemeHandler>();
    private static boolean frozen;
    /** Anchors the dispatcher native holds a global ref to (D16). */
    private static SchemeDispatcher dispatcher;

    static BooleanSupplier available = NATIVE_PROBE;
    static Installer installer = NATIVE_INSTALLER;
    static SchemeDispatcher.Sink sink = NATIVE_SINK;

    private WebViewSchemes() {
    }

    /** Whether the loaded native library serves custom schemes on this platform (D4). */
    public static boolean isSupported() {
        try {
            return available.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Serve {@code scheme} with {@code handler}. Call at start-up, before any WebView component
     * creates its engine.
     *
     * @throws IllegalArgumentException for a missing handler, an invalid or reserved name, or a
     *                                  scheme already registered
     * @throws IllegalStateException    after the first WebView was created
     * @throws UnsupportedOperationException when the native library cannot serve schemes
     */
    public static synchronized void register(String scheme, WebViewSchemeHandler handler) {
        if (handler == null) throw new IllegalArgumentException("A handler is required.");
        if (!isSupported()) {
            throw new UnsupportedOperationException(
                    "Custom URL schemes are not available in this version of the native library");
        }
        if (frozen) {
            throw new IllegalStateException("Custom schemes must be registered before the first WebView is created.");
        }
        String name = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("\u201C" + scheme + "\u201D is not a valid scheme name: 2\u201332 "
                    + "characters, a letter first, then letters, digits, '+', '-' or '.'.");
        }
        if (WEB_SCHEMES.contains(name)) {
            throw new IllegalArgumentException("\u201C" + name + "\u201D cannot be registered: it is one of the "
                    + "web's own schemes.");
        }
        if (handlers.containsKey(name)) {
            throw new IllegalArgumentException("\u201C" + name + "\u201D is already registered.");
        }
        handlers.put(name, handler);
    }

    /** The registered schemes, lower-case, in registration order. */
    public static synchronized List<String> registeredSchemes() {
        return Collections.unmodifiableList(new ArrayList<String>(handlers.keySet()));
    }

    /**
     * Freeze the registry; called by each engine wrapper immediately before it creates its native
     * engine (D1). The first call installs the schemes into native — only when there are any (D2).
     */
    static synchronized void freezeForEngine() {
        if (frozen) return;
        frozen = true;
        if (handlers.isEmpty()) return;
        dispatcher = new SchemeDispatcher(handlers, sink);
        try {
            installer.install(handlers.keySet().toArray(new String[0]), dispatcher);
        } catch (Throwable t) {
            Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
            if (h != null) h.uncaughtException(Thread.currentThread(), t);
            else t.printStackTrace();
        }
    }

    /** The installed dispatcher, or null; for tests. */
    static synchronized SchemeDispatcher dispatcher() {
        return dispatcher;
    }

    /** Back to a fresh, unfrozen registry with the native seams; for tests only. */
    static synchronized void resetForTests() {
        handlers.clear();
        frozen = false;
        dispatcher = null;
        available = NATIVE_PROBE;
        installer = NATIVE_INSTALLER;
        sink = NATIVE_SINK;
    }
}
