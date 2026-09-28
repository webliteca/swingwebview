package ca.weblite.webview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A request for a registered custom scheme, as the engine made it (Canvas 30 D12).
 *
 * <p>{@link #bodyAvailable()} is false when the engine could not supply a body — WebKitGTK before
 * 2.40 cannot (Canvas 31) — in which case {@link #body()} is empty even for a POST.
 */
public final class WebViewSchemeRequest {

    private final String method;
    private final String url;
    private final String scheme;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final boolean bodyAvailable;

    WebViewSchemeRequest(String method, String url, String[] headerPairs, byte[] body, boolean bodyAvailable) {
        this.method = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT);
        this.url = url == null ? "" : url;
        this.scheme = schemeOf(this.url);
        Map<String, List<String>> h = new LinkedHashMap<String, List<String>>();
        if (headerPairs != null) {
            for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                String name = headerPairs[i];
                if (name == null) continue;
                List<String> values = h.get(name);
                if (values == null) {
                    values = new ArrayList<String>();
                    h.put(name, values);
                }
                values.add(headerPairs[i + 1] == null ? "" : headerPairs[i + 1]);
            }
        }
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        this.headers = Collections.unmodifiableMap(h);
        this.body = body == null ? new byte[0] : body;
        this.bodyAvailable = bodyAvailable;
    }

    /** The lower-case scheme of {@code url}, or "" when it has none. */
    static String schemeOf(String url) {
        int colon = url == null ? -1 : url.indexOf(':');
        return colon <= 0 ? "" : url.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** The upper-case method, e.g. {@code GET} or {@code POST}. */
    public String method() {
        return method;
    }

    /** The full URL, as the engine gave it. */
    public String url() {
        return url;
    }

    /** The lower-case scheme, e.g. {@code demo}. */
    public String scheme() {
        return scheme;
    }

    /** Every header, keeping its case and repeated values. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** The first value of header {@code name}, matched case-insensitively, or null. */
    public String header(String name) {
        if (name == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        }
        return null;
    }

    /** The body; empty when there is none. */
    public byte[] body() {
        return body;
    }

    /** Whether the engine could supply the body at all. */
    public boolean bodyAvailable() {
        return bodyAvailable;
    }

    @Override
    public String toString() {
        return method + " " + url;
    }
}
