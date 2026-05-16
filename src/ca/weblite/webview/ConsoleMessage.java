/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Immutable value object describing a single {@code console.*} call that
 * happened inside an embedded WebView.  Delivered to {@link ConsoleListener}
 * callbacks registered on a {@code WebViewComponent}.
 *
 * <p>{@code sourceUrl} is {@code null} and {@code lineNumber} is {@code -1}
 * when the source location cannot be detected (anonymous inline scripts,
 * {@code eval}, evaluated init scripts).
 */
public final class ConsoleMessage {

    /** Closed enum of intercepted {@code console.*} method levels.  One value
     *  per intercepted method; the names match the uppercase form of the
     *  underlying JS method ({@code log} → {@link #LOG}, etc.). */
    public enum Level {
        LOG, INFO, WARN, ERROR, DEBUG
    }

    private final Level level;
    private final String text;
    private final String sourceUrl;
    private final int lineNumber;

    public ConsoleMessage(Level level, String text, String sourceUrl, int lineNumber) {
        if (level == null) throw new NullPointerException("level");
        if (text == null) throw new NullPointerException("text");
        this.level = level;
        this.text = text;
        this.sourceUrl = sourceUrl;
        this.lineNumber = lineNumber;
    }

    public Level getLevel()      { return level; }
    public String getText()      { return text; }
    public String getSourceUrl() { return sourceUrl; }
    public int    getLineNumber(){ return lineNumber; }

    /**
     * Canonical formatted line.  The exact format is part of the public
     * contract and is also what {@code WebViewComponent.setConsoleOutput}
     * writes per message:
     * <pre>[&lt;LEVEL&gt;] &lt;source&gt;:&lt;line&gt; &lt;text&gt;</pre>
     * <p>When {@code sourceUrl} is {@code null} the substitution
     * {@code &lt;unknown&gt;} is used; when {@code lineNumber} is {@code -1}
     * the {@code :&lt;line&gt;} suffix is omitted.  No trailing newline.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(text.length() + 32);
        sb.append('[').append(level.name()).append("] ");
        sb.append(sourceUrl == null ? "<unknown>" : sourceUrl);
        if (lineNumber >= 0) {
            sb.append(':').append(lineNumber);
        }
        sb.append(' ').append(text);
        return sb.toString();
    }
}
