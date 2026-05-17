/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.  Use
 * {@code addWebViewMouseListener}, {@code removeWebViewMouseListener},
 * {@code setDefaultContextMenuEnabled}, and
 * {@code isDefaultContextMenuEnabled} on {@code WebViewComponent} instead.
 * This class is {@code public} only because the consuming Swing subclasses
 * live in a different package and Java has no cross-package-but-non-public
 * access modifier; matches the existing pattern used by
 * {@link ConsoleDispatcher}, {@code EmbeddedWebView}, and
 * {@code OffscreenWebView}.
 *
 * <p>Per-component fan-out hub for DOM mouse events ({@code contextmenu}
 * today; extensible).  Owns the {@link WebViewMouseListener} registry, the
 * default-menu suppression override flag, and the {@link FlagSink} through
 * which JS-side state changes are pushed to the live native peer.  The
 * subclass-specific bridge ({@code addJavascriptCallback} on the internal
 * {@link #CHANNEL_NAME} channel) calls {@link #dispatch(String)} with the
 * raw JSON message produced by the JS shim; the dispatcher decodes it into
 * a {@link WebViewMouseEvent}, hops onto the EDT, and fans out to every
 * then-registered listener.
 */
public final class WebViewMouseDispatcher {

    /**
     * Reserved JS binding name through which the shim posts mouse events
     * back to Java.  Protected from caller collision by the existing
     * {@code RESERVED_BINDING_PREFIX} check on {@code WebViewComponent}.
     */
    public static final String CHANNEL_NAME = "__webview_dom_event";

    /**
     * Wire-format schema version.  Records with a mismatching version are
     * silently dropped by {@link #dispatch(String)}.  Future incompatible
     * payload changes bump this; trailing-only additions do not require a
     * version bump.
     */
    public static final String SCHEMA_VERSION = "1";

    /**
     * Canonical JavaScript shim installed at document-start into every page
     * loaded by a {@code WebViewComponent}.  Idempotent (guarded by
     * {@code window.__webview_dom_event_installed__}) so re-injection on
     * repeated navigations or sub-frame loads is a no-op.
     *
     * <p>The shim registers a capture-phase {@code contextmenu} listener on
     * {@code document}, builds a base64-encoded pipe-separated record
     * describing the event and the {@link DomTarget}, and posts it through
     * {@code window.__webview_dom_event(record)}.  When
     * {@code window.__webview_dom_event_suppress} is truthy the shim also
     * calls {@code event.preventDefault()} on the way through, suppressing
     * the platform's built-in context menu.
     *
     * <p>Special handling: {@code <input type="password">} reports an empty
     * {@code value} attribute regardless of actual contents.  Selection text
     * is truncated at 64&nbsp;KiB with a trailing {@code "..."}.  Total
     * {@code data-*} payload is capped at 8&nbsp;KiB with the same
     * truncation marker.
     */
    public static final String SHIM_JS =
        "(function(){"
      + "if(window.__webview_dom_event_installed__)return;"
      + "window.__webview_dom_event_installed__=true;"
      + "if(typeof window.__webview_dom_event_suppress==='undefined'){"
      + "  window.__webview_dom_event_suppress=false;"
      + "}"
      + "var enc=function(s){"
      + "  try{return btoa(unescape(encodeURIComponent(String(s==null?'':s))));}"
      + "  catch(e){return '';}"
      + "};"
      + "var topUrl=function(){"
      + "  try{var w=window;while(w!==w.parent){w=w.parent;}return w.location.href;}"
      + "  catch(e){return document.location.href;}"
      + "};"
      + "document.addEventListener('contextmenu',function(ev){"
      + "  try{"
      + "    if(window.__webview_dom_event_suppress){ev.preventDefault();}"
      + "    var t=ev.target;"
      + "    if(!t||t.nodeType!==1){t=document.body||document.documentElement;}"
      + "    var tag=(t.tagName||'').toUpperCase();"
      + "    var id=t.id||'';"
      + "    var classes='';"
      + "    if(t.classList){"
      + "      classes=Array.prototype.slice.call(t.classList).join(' ');"
      + "    }"
      + "    var keys=['href','src','alt','title','name','type','value','role'];"
      + "    var attrs=[];"
      + "    var isPwd=(tag==='INPUT'&&((t.type||'').toLowerCase()==='password'));"
      + "    for(var i=0;i<keys.length;i++){"
      + "      var k=keys[i];"
      + "      if(t.hasAttribute&&t.hasAttribute(k)){"
      + "        var v=t.getAttribute(k);"
      + "        if(v==null)v='';"
      + "        if(k==='value'&&isPwd)v='';"
      + "        attrs.push(k+'='+enc(v));"
      + "      }"
      + "    }"
      + "    if(t.attributes){"
      + "      var dataBudget=8192;"
      + "      for(var j=0;j<t.attributes.length;j++){"
      + "        var a=t.attributes[j];"
      + "        if(a.name.indexOf('data-')===0){"
      + "          var av=a.value||'';"
      + "          if(av.length>dataBudget){av=av.substring(0,Math.max(0,dataBudget))+'...';}"
      + "          attrs.push(a.name+'='+enc(av));"
      + "          dataBudget-=av.length;"
      + "          if(dataBudget<=0)break;"
      + "        }"
      + "      }"
      + "    }"
      + "    var anc=t;var linkHref='';"
      + "    while(anc&&anc!==document.documentElement){"
      + "      if(anc.tagName==='A'&&anc.getAttribute&&anc.getAttribute('href')){"
      + "        linkHref=anc.getAttribute('href');break;"
      + "      }"
      + "      anc=anc.parentElement;"
      + "    }"
      + "    var imageSrc='';"
      + "    if(tag==='IMG'){imageSrc=(t.getAttribute&&t.getAttribute('src'))||'';}"
      + "    var mediaSrc='';"
      + "    if(tag==='AUDIO'||tag==='VIDEO'){"
      + "      mediaSrc=t.currentSrc||(t.getAttribute&&t.getAttribute('src'))||'';"
      + "    }"
      + "    var ce=(t.isContentEditable===true)||(tag==='TEXTAREA')||"
      + "      (tag==='INPUT'&&/^(text|search|email|url|tel|password|number)$/i.test(t.type||''));"
      + "    var sel='';"
      + "    try{"
      + "      var s=window.getSelection?window.getSelection().toString():'';"
      + "      sel=(s.length>65536)?(s.substring(0,65536)+'...'):s;"
      + "    }catch(e){}"
      + "    var modBits=(ev.shiftKey?1:0)|(ev.ctrlKey?2:0)|(ev.altKey?4:0)|(ev.metaKey?8:0);"
      + "    var record='1|contextmenu|'+(ev.button+1)+'|'+modBits+'|'+"
      + "      (ev.clientX|0)+'|'+(ev.clientY|0)+'|'+"
      + "      (ev.pageX|0)+'|'+(ev.pageY|0)+'|'+"
      + "      (ev.screenX|0)+'|'+(ev.screenY|0)+'|'+"
      + "      ((ev.timeStamp|0))+'|'+"
      + "      tag+'|'+enc(id)+'|'+enc(classes)+'|'+enc(attrs.join(','))+'|'+"
      + "      enc(linkHref)+'|'+enc(imageSrc)+'|'+enc(mediaSrc)+'|'+"
      + "      (ce?'1':'0')+'|'+enc(sel)+'|'+enc(topUrl())+'|'+enc(document.location.href);"
      + "    var b64=enc(record);"
      + "    if(window.__webview_dom_event){window.__webview_dom_event(b64);}"
      + "  }catch(e){}"
      + "},true);"
      + "})();";

    /**
     * Sink through which the dispatcher pushes JS-side state changes (the
     * {@code window.__webview_dom_event_suppress} flag) to a live native
     * peer.  Installed by the owning {@code WebViewComponent} subclass when
     * the peer attaches.  Implementations should be tolerant of post-dispose
     * calls; the heavyweight and lightweight components wrap their
     * underlying {@code eval}/{@code addOnBeforeLoad} calls in a
     * try/catch that swallows {@code IllegalStateException} from the peer's
     * {@code checkAlive} guard.
     */
    public interface FlagSink {
        /** Evaluate {@code js} against the current document only. */
        void eval(String js);
        /** Register {@code js} as a document-start script that runs on every navigation. */
        void addOnBeforeLoad(String js);
    }

    private final WebViewComponent source;
    private final CopyOnWriteArrayList<WebViewMouseListener> listeners =
            new CopyOnWriteArrayList<WebViewMouseListener>();
    private volatile boolean defaultEnabled = true;
    private volatile FlagSink flagSink;
    // Guarded by `this`.  Populated only while flagSink == null; drained
    // verbatim into the sink on attachFlagSink.
    private final List<String> pendingPreloads = new ArrayList<String>();

    public WebViewMouseDispatcher(WebViewComponent source) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
    }

    public void addListener(WebViewMouseListener listener) {
        if (listener == null) throw new NullPointerException("listener");
        listeners.add(listener);
        reevaluateSuppression();
    }

    public void removeListener(WebViewMouseListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
        reevaluateSuppression();
    }

    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public void setDefaultEnabled(boolean enabled) {
        defaultEnabled = enabled;
        reevaluateSuppression();
    }

    /**
     * Install the sink that publishes JS-side flag updates to the live peer.
     * Called by the owning component once per peer-attach, after the
     * binding has been registered.  Drains any suppression statements that
     * accumulated while no sink was installed, then publishes one fresh
     * re-evaluation so the current state is in effect immediately.
     */
    public void attachFlagSink(FlagSink sink) {
        if (sink == null) throw new NullPointerException("sink");
        flagSink = sink;
        List<String> drain;
        synchronized (this) {
            drain = new ArrayList<String>(pendingPreloads);
            pendingPreloads.clear();
        }
        for (String s : drain) {
            try { sink.addOnBeforeLoad(s); } catch (RuntimeException ignored) {}
        }
        reevaluateSuppression();
    }

    private void reevaluateSuppression() {
        boolean suppress = !defaultEnabled && !listeners.isEmpty();
        String stmt = "window.__webview_dom_event_suppress=" + suppress + ";";
        FlagSink sink = flagSink;
        if (sink == null) {
            synchronized (this) {
                pendingPreloads.add(stmt);
            }
            return;
        }
        // Apply to the current document AND to every future navigation.
        // try/catch insulates the dispatcher from a peer in mid-teardown
        // (post-dispose `eval` throws IllegalStateException from checkAlive).
        try { sink.eval(stmt); } catch (RuntimeException ignored) {}
        try { sink.addOnBeforeLoad(stmt); } catch (RuntimeException ignored) {}
    }

    /**
     * Decode a raw JSON wrapper produced by the bind shim, parse the
     * base64-encoded record posted from {@link #SHIM_JS}, and fan it out
     * to every then-registered listener on the EDT.
     *
     * <p>Called from whatever native thread the engine's message bridge
     * runs on; thread-safe.  Silently drops messages that don't match the
     * expected wire format.
     */
    public void dispatch(String rawJson) {
        if (rawJson == null) return;
        String b64Outer = extractFirstArg(rawJson);
        if (b64Outer == null) return;
        String record = decodeBase64Utf8(b64Outer);
        if (record == null) return;
        WebViewMouseEvent event = parseRecord(record);
        if (event == null) return;
        deliverOnEdt(event);
    }

    private void deliverOnEdt(final WebViewMouseEvent event) {
        if (SwingUtilities.isEventDispatchThread()) {
            deliver(event);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() { deliver(event); }
            });
        }
    }

    private void deliver(WebViewMouseEvent event) {
        // CopyOnWriteArrayList iteration sees a stable snapshot, so a
        // listener calling removeWebViewMouseListener / addWebViewMouseListener
        // during contextMenuRequested only takes effect for the NEXT event.
        for (WebViewMouseListener l : listeners) {
            try {
                l.contextMenuRequested(event);
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
     * contains no JSON-special characters &mdash; no need for a full JSON
     * escape parser here.  Mirrors {@code ConsoleDispatcher.extractFirstArg}.
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
     * Parse the pipe-separated record posted by {@link #SHIM_JS} into a
     * fully-constructed {@link WebViewMouseEvent}.  Returns {@code null} if
     * the format doesn't match or the schema version is unexpected.
     *
     * <p>Field layout (22 fields):
     * <pre>
     * 0  schema-version ("1")
     * 1  eventType ("contextmenu")
     * 2  button (1..3)
     * 3  modifierBits (decimal int; bit 0 shift, 1 ctrl, 2 alt, 3 meta)
     * 4  clientX     5  clientY
     * 6  pageX       7  pageY
     * 8  screenX     9  screenY
     * 10 timeStamp (ms, truncated)
     * 11 tagName (uppercase ASCII)
     * 12 id (base64 UTF-8)
     * 13 classes (base64 of space-separated names)
     * 14 attrs (base64 of comma-separated "key=valueB64" pairs)
     * 15 linkHref (base64; empty = no link)
     * 16 imageSrc (base64; empty unless tagName=IMG)
     * 17 mediaSrc (base64; empty unless tagName in {AUDIO,VIDEO})
     * 18 contentEditable ("1" or "0")
     * 19 selectionText (base64)
     * 20 pageUrl (base64)
     * 21 frameUrl (base64)
     * </pre>
     */
    private WebViewMouseEvent parseRecord(String record) {
        String[] parts = splitOnPipe(record);
        // Tolerate trailing additional fields (forward-compat) but require
        // at least the 22 we know how to interpret.
        if (parts.length < 22) return null;
        if (!SCHEMA_VERSION.equals(parts[0])) return null;
        try {
            String type = parts[1];
            int button = Integer.parseInt(parts[2]);
            int modifierBits = Integer.parseInt(parts[3]);
            int clientX = Integer.parseInt(parts[4]);
            int clientY = Integer.parseInt(parts[5]);
            int pageX = Integer.parseInt(parts[6]);
            int pageY = Integer.parseInt(parts[7]);
            int screenX = Integer.parseInt(parts[8]);
            int screenY = Integer.parseInt(parts[9]);
            long timeStamp = Long.parseLong(parts[10]);
            String tagName = parts[11];
            String id = decodeStringField(parts[12]);
            String classesStr = decodeStringField(parts[13]);
            String attrsStr = decodeStringField(parts[14]);
            String linkHrefRaw = decodeStringField(parts[15]);
            String imageSrcRaw = decodeStringField(parts[16]);
            String mediaSrcRaw = decodeStringField(parts[17]);
            boolean contentEditable = "1".equals(parts[18]);
            String selectionText = decodeStringField(parts[19]);
            String pageUrl = decodeStringField(parts[20]);
            String frameUrl = decodeStringField(parts[21]);

            List<String> classes;
            if (classesStr.isEmpty()) {
                classes = null;
            } else {
                classes = new ArrayList<String>();
                for (String c : classesStr.split(" ")) {
                    if (!c.isEmpty()) classes.add(c);
                }
            }
            Map<String, String> attrs = parseAttrs(attrsStr);
            String linkHref = linkHrefRaw.isEmpty() ? null : linkHrefRaw;
            String imageSrc = ("IMG".equals(tagName) && !imageSrcRaw.isEmpty())
                ? imageSrcRaw : null;
            String mediaSrc = (("AUDIO".equals(tagName) || "VIDEO".equals(tagName))
                && !mediaSrcRaw.isEmpty()) ? mediaSrcRaw : null;

            DomTarget target = new DomTarget(tagName, id, classes, attrs,
                linkHref, imageSrc, mediaSrc, contentEditable,
                selectionText, pageUrl, frameUrl);
            return new WebViewMouseEvent(type, button, modifierBits,
                clientX, clientY, pageX, pageY, screenX, screenY,
                timeStamp, target, source);
        } catch (NumberFormatException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Split a pipe-separated record into its top-level fields without
     * collapsing empty fields the way {@code String.split} would.  Inner
     * base64 fields never contain a literal {@code '|'}, so a simple
     * scan suffices.
     */
    private static String[] splitOnPipe(String s) {
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '|') count++;
        }
        String[] out = new String[count];
        int idx = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '|') {
                out[idx++] = s.substring(start, i);
                start = i + 1;
            }
        }
        out[idx] = s.substring(start);
        return out;
    }

    private static String decodeStringField(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        String decoded = decodeBase64Utf8(b64);
        return decoded == null ? "" : decoded;
    }

    private static Map<String, String> parseAttrs(String csv) {
        if (csv.isEmpty()) return null;
        Map<String, String> out = new LinkedHashMap<String, String>();
        // Outer separator is ','; inner '=' separates the (unencoded) key
        // from the base64-encoded value.  Keys are restricted to attribute
        // names so neither separator can collide.
        int start = 0;
        for (int i = 0; i <= csv.length(); i++) {
            if (i == csv.length() || csv.charAt(i) == ',') {
                if (i > start) {
                    String pair = csv.substring(start, i);
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String key = pair.substring(0, eq);
                        String valueB64 = pair.substring(eq + 1);
                        out.put(key, decodeStringField(valueB64));
                    }
                }
                start = i + 1;
            }
        }
        return out.isEmpty() ? null : out;
    }
}
