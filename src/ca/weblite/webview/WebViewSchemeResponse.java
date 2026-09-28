package ca.weblite.webview;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a {@link WebViewSchemeHandler} answers a custom-scheme request with: a status, headers and a
 * body (Canvas 30). Immutable; build one with the static factories and {@link #withHeader}.
 *
 * <p>The library sets {@code Content-Length} itself and drops any the handler sets; it adds
 * {@code Content-Type: application/octet-stream} when none is given, and
 * {@code Access-Control-Allow-Origin} for the request's own origin unless the handler sets one
 * (Canvas 30 D11).
 */
public final class WebViewSchemeResponse {

    private final int status;
    private final List<String[]> headers;
    private final byte[] body;

    private WebViewSchemeResponse(int status, List<String[]> headers, byte[] body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("A status must be between 100 and 599: " + status);
        }
        this.status = status;
        this.headers = Collections.unmodifiableList(new ArrayList<String[]>(headers));
        this.body = body == null ? new byte[0] : body;
    }

    /** A 200 answer with {@code contentType} and {@code body}. */
    public static WebViewSchemeResponse ok(String contentType, byte[] body) {
        return of(200, contentType, body);
    }

    /** An answer of {@code status} whose body is {@code text}, as UTF-8 plain text. */
    public static WebViewSchemeResponse text(int status, String text) {
        return of(status, "text/plain; charset=utf-8",
                (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    /** An answer of {@code status} with {@code contentType} (may be null) and {@code body}. */
    public static WebViewSchemeResponse of(int status, String contentType, byte[] body) {
        List<String[]> h = new ArrayList<String[]>();
        if (contentType != null) h.add(new String[] {"Content-Type", contentType});
        return new WebViewSchemeResponse(status, h, body);
    }

    /** A copy of this answer with one more header. */
    public WebViewSchemeResponse withHeader(String name, String value) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("A header needs a name.");
        List<String[]> h = new ArrayList<String[]>(headers);
        h.add(new String[] {name, value == null ? "" : value});
        return new WebViewSchemeResponse(status, h, body);
    }

    public int status() {
        return status;
    }

    /** Name/value pairs, in the order added; a name may repeat. */
    public List<String[]> headers() {
        return headers;
    }

    /** The body; never null. */
    public byte[] body() {
        return body;
    }
}
