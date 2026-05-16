/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * the {@code addConsoleListener}, {@code removeConsoleListener},
 * {@code setConsoleOutput}, and {@code getConsoleOutput} methods on
 * {@code WebViewComponent} instead.  This class is {@code public} only
 * because the consuming Swing subclasses live in a different package and
 * Java has no cross-package-but-non-public access modifier; matches the
 * existing pattern used by {@code EmbeddedWebView},
 * {@code OffscreenWebView}, and {@code WebViewNativeCallback}.
 *
 * <p>Per-component fan-out hub for captured console messages.  Owned by
 * every {@code WebViewComponent} instance.  Listeners and the optional
 * {@link PrintStream} output sink are registered against this dispatcher;
 * the subclass-specific bridge ({@code addJavascriptCallback} on the
 * internal {@code __webview_console__} channel) calls
 * {@link #dispatch(String)} with the raw JSON message produced by the JS
 * shim, and the dispatcher decodes it into a {@link ConsoleMessage}, hops
 * onto the EDT, and fans out to every then-registered listener.
 */
public final class ConsoleDispatcher {

    /**
     * Canonical JavaScript shim injected at document-start into every page
     * loaded by a {@code WebViewComponent}.  Wraps the five intercepted
     * {@code console.*} methods (log/info/warn/error/debug); for each call:
     *
     * <ol>
     *   <li>Invokes the original captured-by-closure method first, so the
     *       platform's native DevTools Console panel and (on Linux debug
     *       builds) stdout still see the output untouched.</li>
     *   <li>Best-effort parses {@code new Error().stack} to recover the
     *       source URL + line number of the caller.</li>
     *   <li>Builds the canonical pipe-separated payload
     *       {@code <LEVEL>|<sourceUrl>|<lineNumber>|<textLength>|<text>}.</li>
     *   <li>Base64-encodes the UTF-8 bytes of that payload so it can pass
     *       through the existing JSON-args bind channel without escape
     *       interference, then calls
     *       {@code window.__webview_console__(b64)}.</li>
     * </ol>
     *
     * <p>The shim guards against re-entry (a listener calling
     * {@code console.log} in JS does not loop), and is idempotent across
     * page loads via a window-level installed flag.
     */
    public static final String SHIM_JS =
        "(function(){"
      + "if(window.__webview_console_installed__)return;"
      + "window.__webview_console_installed__=true;"
      + "var utf8len=function(s){"
      + "  try{return unescape(encodeURIComponent(s)).length;}catch(e){return s.length;}"
      + "};"
      + "var b64=function(s){"
      + "  try{return btoa(unescape(encodeURIComponent(s)));}catch(e){return '';}"
      + "};"
      + "var parseSrc=function(){"
      + "  try{"
      + "    var st=(new Error()).stack||'';"
      + "    var lines=st.split('\\n');"
      + "    for(var i=0;i<lines.length;i++){"
      + "      var L=lines[i];"
      + "      if(L.indexOf('__webview_console')!==-1)continue;"
      + "      if(L.indexOf('parseSrc')!==-1||L.indexOf('post')!==-1)continue;"
      + "      var m=L.match(/at\\s+(?:.*?\\()?([^()\\s]+):(\\d+):\\d+\\)?\\s*$/);"
      + "      if(!m)m=L.match(/@([^()\\s]+):(\\d+):\\d+$/);"
      + "      if(m)return [m[1],parseInt(m[2],10)];"
      + "    }"
      + "  }catch(e){}"
      + "  return ['',-1];"
      + "};"
      + "var post=function(level,args){"
      + "  try{"
      + "    var text=Array.prototype.map.call(args,function(a){"
      + "      try{return String(a);}catch(e){return '[unstringifiable]';}"
      + "    }).join(' ');"
      + "    var loc=parseSrc();"
      + "    var payload=level+'|'+loc[0]+'|'+loc[1]+'|'+utf8len(text)+'|'+text;"
      + "    if(window.__webview_console__){"
      + "      window.__webview_console__(b64(payload));"
      + "    }"
      + "  }catch(e){}"
      + "};"
      + "var levels=['log','info','warn','error','debug'];"
      + "var ups=['LOG','INFO','WARN','ERROR','DEBUG'];"
      + "var inShim=false;"
      + "for(var i=0;i<levels.length;i++){"
      + "  (function(idx){"
      + "    var orig=console[levels[idx]];"
      + "    console[levels[idx]]=function(){"
      + "      try{if(orig)orig.apply(console,arguments);}catch(e){}"
      + "      if(inShim)return;"
      + "      inShim=true;"
      + "      try{post(ups[idx],arguments);}finally{inShim=false;}"
      + "    };"
      + "  })(i);"
      + "}"
      + "})();";

    /** Internal binding name reserved on every engine for console capture. */
    public static final String CHANNEL_NAME = "__webview_console__";

    private final List<ConsoleListener> listeners = new CopyOnWriteArrayList<ConsoleListener>();
    private volatile PrintStream outputStream;
    private volatile ConsoleListener outputStreamListener;

    public ConsoleDispatcher() {}

    public void addListener(ConsoleListener listener) {
        if (listener == null) throw new NullPointerException("listener");
        listeners.add(listener);
    }

    public void removeListener(ConsoleListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    public void setOutputStream(PrintStream stream) {
        // Remove the previous redirect listener (if any) first so we don't
        // accumulate stale internal listeners across multiple setOutputStream
        // calls.
        ConsoleListener prev = outputStreamListener;
        if (prev != null) {
            listeners.remove(prev);
            outputStreamListener = null;
        }
        outputStream = stream;
        if (stream != null) {
            final PrintStream ps = stream;
            ConsoleListener redirect = new ConsoleListener() {
                @Override
                public void onMessage(ConsoleMessage msg) {
                    try {
                        ps.println(msg.toString());
                    } catch (RuntimeException re) {
                        // PrintStream itself swallows IOException, but a
                        // user subclass might throw — never let it propagate
                        // out of the dispatcher chain.
                    }
                }
            };
            outputStreamListener = redirect;
            listeners.add(redirect);
        }
    }

    public PrintStream getOutputStream() {
        return outputStream;
    }

    /**
     * Decode a raw JSON wrapper produced by the bind shim, extract and
     * base64-decode the payload posted from {@link #SHIM_JS}, parse the
     * pipe-separated fields into a {@link ConsoleMessage}, and fan it out
     * to every then-registered listener on the EDT.
     *
     * <p>Called from whatever native thread the engine's message bridge
     * runs on; thread-safe.  Silently drops messages that don't match the
     * expected wire format.
     */
    public void dispatch(String rawJson) {
        if (rawJson == null) return;
        String b64Payload = extractFirstArg(rawJson);
        if (b64Payload == null) return;
        String payload = decodeBase64Utf8(b64Payload);
        if (payload == null) return;
        ConsoleMessage msg = parsePayload(payload);
        if (msg == null) return;
        deliverOnEdt(msg);
    }

    private void deliverOnEdt(final ConsoleMessage msg) {
        if (SwingUtilities.isEventDispatchThread()) {
            deliver(msg);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() { deliver(msg); }
            });
        }
    }

    private void deliver(ConsoleMessage msg) {
        // CopyOnWriteArrayList iteration sees a stable snapshot, so a
        // listener calling removeConsoleListener / addConsoleListener
        // during onMessage only takes effect for the NEXT message.
        for (ConsoleListener l : listeners) {
            try {
                l.onMessage(msg);
            } catch (Throwable t) {
                Thread.UncaughtExceptionHandler h =
                    Thread.getDefaultUncaughtExceptionHandler();
                if (h != null) {
                    try { h.uncaughtException(Thread.currentThread(), t); }
                    catch (Throwable ignored) {}
                } else {
                    t.printStackTrace();
                }
            }
        }
    }

    /**
     * Extract the first arg from a bind-shim JSON wrapper of the form
     * {@code {"name":"...","seq":...,"args":["<value>"]}}.  Returns
     * {@code null} if the format doesn't match.  Assumes the value is a
     * single string and was base64-encoded by {@link #SHIM_JS}, so it
     * contains no JSON-special characters — no need for a full JSON
     * escape parser here.
     */
    private static String extractFirstArg(String json) {
        int argsIdx = json.indexOf("\"args\":[");
        if (argsIdx < 0) return null;
        int quoteStart = json.indexOf('"', argsIdx + 8);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String decodeBase64Utf8(String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            return new String(bytes, "UTF-8");
        } catch (IllegalArgumentException e) {
            return null;
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * Parse the canonical pipe-separated payload
     * {@code <LEVEL>|<sourceUrl>|<lineNumber>|<textLength>|<text>} into a
     * {@link ConsoleMessage}.  Returns {@code null} if the format doesn't
     * match.
     */
    private static ConsoleMessage parsePayload(String payload) {
        int p1 = payload.indexOf('|');
        if (p1 < 0) return null;
        int p2 = payload.indexOf('|', p1 + 1);
        if (p2 < 0) return null;
        int p3 = payload.indexOf('|', p2 + 1);
        if (p3 < 0) return null;
        int p4 = payload.indexOf('|', p3 + 1);
        if (p4 < 0) return null;
        String levelStr = payload.substring(0, p1);
        String srcStr   = payload.substring(p1 + 1, p2);
        String lineStr  = payload.substring(p2 + 1, p3);
        // textLength is informational only — Java side just takes the
        // remainder of the string.  The field is there to keep the format
        // robust against pipes / newlines inside text on the wire.
        String text     = payload.substring(p4 + 1);

        ConsoleMessage.Level level;
        try {
            level = ConsoleMessage.Level.valueOf(levelStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int lineNumber;
        try {
            lineNumber = Integer.parseInt(lineStr);
        } catch (NumberFormatException e) {
            lineNumber = -1;
        }
        String sourceUrl = srcStr.isEmpty() ? null : srcStr;
        return new ConsoleMessage(level, text, sourceUrl, lineNumber);
    }
}
