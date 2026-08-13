/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import ca.weblite.webview.swing.WebViewComponent;

import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import javax.swing.SwingUtilities;

/**
 * <p><strong>Internal:</strong> not part of the public API surface.
 * Drive the password manager via
 * {@link WebViewComponent#setPasswordManagerEnabled},
 * {@link WebViewComponent#setCredentialStore},
 * {@link WebViewComponent#setSavePasswordHandler}, and the
 * {@code getCredential}/{@code saveCredential}/{@code deleteCredential}
 * methods.  This class is {@code public} only because the consuming
 * Swing subclasses live in a different package — the same reason
 * {@link DialogDispatcher} is public.
 *
 * <p>Per-component hub for the password manager.  Holds the active
 * {@link WebViewCredentialStore}, the {@link WebViewSavePasswordHandler},
 * and the enabled flag; receives login-submission and fill-request
 * events the injected {@link #SHIM_JS} raised (via
 * {@link WebViewPasswordCallback}); shows the save prompt on the EDT and
 * performs store I/O on a worker; pushes autofill back into the page via
 * {@code eval}.
 *
 * <p><strong>Threading — divergence from {@link DialogDispatcher}.</strong>
 * A login submission has no synchronous JS contract, so this dispatcher
 * is NON-blocking: it marshals the save prompt with
 * {@link SwingUtilities#invokeLater} (never {@code invokeAndWait}) and
 * runs all secret-store I/O on a private single-thread executor, so the
 * native message thread and the EDT are never parked on keychain I/O.
 */
public final class PasswordDispatcher {

    /**
     * Shared detection/fill script, injected at document-start on every
     * backend via {@code addOnBeforeLoad}.  It reports login submissions
     * and requests autofill over the native {@code __webview_pw__}
     * channel, and exposes a single write-only fill entrypoint
     * {@code window.__webview_pw_fill__(b64user, b64pass)}.  It exposes
     * no way for page script to read a stored credential.
     */
    public static final String SHIM_JS =
        "(function(){\n" +
        "try{\n" +
        "if(window.__webview_pw_installed__)return;\n" +
        "window.__webview_pw_installed__=true;\n" +
        "function b64e(s){try{var u=unescape(encodeURIComponent(s));var b=btoa(u);" +
        "return b.replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');}catch(e){return '';}}\n" +
        "function b64d(s){try{s=s.replace(/-/g,'+').replace(/_/g,'/');" +
        "while(s.length%4)s+='=';return decodeURIComponent(escape(atob(s)));}catch(e){return '';}}\n" +
        "function post(p){try{if(window.webkit&&window.webkit.messageHandlers&&" +
        "window.webkit.messageHandlers.__webview_pw__){window.webkit.messageHandlers.__webview_pw__.postMessage(p);}" +
        "else if(window.chrome&&window.chrome.webview){window.chrome.webview.postMessage('__webview_pw__:'+p);}}catch(e){}}\n" +
        "function isTexty(el){if(!el||el.tagName!=='INPUT')return false;" +
        "var t=(el.type||'text').toLowerCase();return t==='text'||t==='email'||t==='tel'||t==='';}\n" +
        "function findFields(){var pass=document.querySelector('input[type=password]');if(!pass)return null;" +
        "var user=null;var form=pass.form;" +
        "if(form){var els=form.elements;var pi=-1;for(var i=0;i<els.length;i++){if(els[i]===pass){pi=i;break;}}" +
        "for(var j=pi-1;j>=0;j--){if(isTexty(els[j])){user=els[j];break;}}" +
        "if(!user){for(var k=0;k<els.length;k++){if(isTexty(els[k])){user=els[k];break;}}}}" +
        "else{var all=document.querySelectorAll('input');var pidx=-1;for(var a=0;a<all.length;a++){if(all[a]===pass){pidx=a;break;}}" +
        "for(var b=pidx-1;b>=0;b--){if(isTexty(all[b])){user=all[b];break;}}}" +
        "return {user:user,pass:pass};}\n" +
        "function setVal(el,v){try{var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
        "if(d&&d.set){d.set.call(el,v);}else{el.value=v;}}catch(e){el.value=v;}" +
        "try{el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}}\n" +
        "window.__webview_pw_fill__=function(bu,bp){try{var f=findFields();if(!f)return;" +
        "if(f.user&&bu)setVal(f.user,b64d(bu));if(f.pass)setVal(f.pass,b64d(bp));}catch(e){}};\n" +
        "var lastPost=0;\n" +
        "function capture(){try{var f=findFields();if(!f||!f.pass)return;var pv=f.pass.value;if(!pv)return;" +
        "var now=Date.now();if(now-lastPost<400)return;lastPost=now;" +
        "var uv=f.user?f.user.value:'';post('S|'+b64e(uv)+'|'+b64e(pv));}catch(e){}}\n" +
        "document.addEventListener('submit',function(ev){try{var t=ev.target;" +
        "if(t&&t.querySelector&&t.querySelector('input[type=password]'))capture();}catch(e){}},true);\n" +
        "document.addEventListener('click',function(ev){try{var el=ev.target;" +
        "if(!el)return;var tag=(el.tagName||'').toUpperCase();var role=el.getAttribute&&el.getAttribute('role');" +
        "var isBtn=tag==='BUTTON'||(tag==='INPUT'&&/^(submit|button)$/i.test(el.type||''))||role==='button';" +
        "if(!isBtn)return;var f=findFields();if(f&&f.pass&&!f.pass.form&&f.pass.value)capture();}catch(e){}},true);\n" +
        "function requestFill(){try{if(findFields())post('F');}catch(e){}}\n" +
        "function ready(){requestFill();try{var seen=!!findFields();var mo=new MutationObserver(function(){" +
        "if(!seen&&findFields()){seen=true;post('F');mo.disconnect();}});" +
        "mo.observe(document.documentElement,{childList:true,subtree:true});" +
        "setTimeout(function(){try{mo.disconnect();}catch(e){}},10000);}catch(e){}}\n" +
        "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',ready);else ready();\n" +
        "}catch(e){}\n" +
        "})();";

    private final WebViewComponent source;
    private volatile WebViewCredentialStore store = new NativeCredentialStore();
    private volatile WebViewSavePasswordHandler handler =
        WebViewSavePasswordHandler.DEFAULT;
    private volatile boolean enabled = true;
    private volatile boolean disposed = false;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
        new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "webview-password-io");
                t.setDaemon(true);
                return t;
            }
        });

    public PasswordDispatcher(WebViewComponent source) {
        if (source == null) throw new NullPointerException("source");
        this.source = source;
    }

    // ---- enabled / store / handler seams -------------------------------

    public void setEnabled(boolean e) { enabled = e; }
    public boolean isEnabled() { return enabled; }

    /** Replace the store; {@code null} restores a fresh
     *  {@link NativeCredentialStore}. */
    public void setStore(WebViewCredentialStore s) {
        store = (s == null) ? new NativeCredentialStore() : s;
    }

    /** @return the active store; never {@code null}. */
    public WebViewCredentialStore getStore() { return store; }

    /** Replace the save-policy; {@code null} restores
     *  {@link WebViewSavePasswordHandler#DEFAULT}. */
    public void setHandler(WebViewSavePasswordHandler h) {
        handler = (h == null) ? WebViewSavePasswordHandler.DEFAULT : h;
    }

    /** @return the active save-policy; never {@code null}. */
    public WebViewSavePasswordHandler getHandler() { return handler; }

    // ---- programmatic API (synchronous on the caller thread) -----------

    public void saveCredential(WebViewCredential c) {
        if (c == null) throw new NullPointerException("credential");
        store.save(c);
    }

    public Optional<WebViewCredential> getCredential(String origin) {
        return store.find(origin);
    }

    public List<WebViewCredential> getCredentials(String origin) {
        return store.findAll(origin);
    }

    public boolean deleteCredential(String origin, String username) {
        return store.delete(origin, username);
    }

    // ---- native-facing dispatch ---------------------------------------

    /** A login form was submitted; {@code frameUrl} is the native-stamped
     *  trusted origin source, the username/password are base64url. */
    public void dispatchLoginSubmitted(String frameUrl, String b64User,
                                       String b64Pass) {
        if (disposed || !enabled) return;
        final String origin = Origins.canonical(frameUrl);
        if (origin == null) return;
        final String user;
        final String pass;
        try {
            user = base64UrlDecode(b64User);
            pass = base64UrlDecode(b64Pass);
        } catch (IllegalArgumentException iae) {
            return; // malformed payload: drop silently
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { runPrompt(origin, user, pass); }
        });
    }

    /** The page requested autofill; {@code frameUrl} is the native-stamped
     *  trusted origin source. */
    public void dispatchFillRequested(String frameUrl) {
        if (disposed || !enabled) return;
        final String origin = Origins.canonical(frameUrl);
        if (origin == null) return;
        io.execute(new Runnable() {
            @Override public void run() { doFill(origin); }
        });
    }

    /** Flip into disposed state; automatic dispatch stops and the worker
     *  executor is released.  Idempotent. */
    public void disposeAll() {
        disposed = true;
        io.shutdownNow();
    }

    public boolean isDisposed() { return disposed; }

    // ---- internals ----------------------------------------------------

    private void runPrompt(String origin, String user, String pass) {
        if (disposed || !enabled) return;
        WebViewSavePasswordEvent ev =
            new WebViewSavePasswordEvent(source, origin, user, pass);
        SavePasswordDisposition d;
        try {
            d = handler.onLoginSubmitted(ev);
        } catch (Throwable t) {
            forward(t);
            return;
        }
        if (d == SavePasswordDisposition.SAVE) {
            final WebViewCredential c = new WebViewCredential(origin, user, pass);
            io.execute(new Runnable() {
                @Override public void run() { safeSave(c); }
            });
        }
    }

    private void doFill(String origin) {
        Optional<WebViewCredential> c;
        try {
            c = store.find(origin);
        } catch (Throwable t) {
            forward(t);
            return;
        }
        if (c == null || !c.isPresent()) return;
        WebViewCredential cred = c.get();
        String js = "window.__webview_pw_fill__('"
            + base64UrlEncode(cred.username()) + "','"
            + base64UrlEncode(cred.password()) + "')";
        try {
            source.eval(js);
        } catch (Throwable t) {
            forward(t); // never log js — it embeds the password
        }
    }

    private void safeSave(WebViewCredential c) {
        try {
            store.save(c);
        } catch (Throwable t) {
            forward(t);
        }
    }

    private static String base64UrlEncode(String s) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 always present
        }
    }

    private static String base64UrlDecode(String s) {
        if (s == null) return "";
        byte[] raw = Base64.getUrlDecoder().decode(s);
        try {
            return new String(raw, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void forward(Throwable t) {
        try {
            Thread.UncaughtExceptionHandler h =
                Thread.getDefaultUncaughtExceptionHandler();
            if (h != null) {
                h.uncaughtException(Thread.currentThread(), t);
            } else {
                t.printStackTrace();
            }
        } catch (Throwable ignored) {
            try { t.printStackTrace(); } catch (Throwable ignored2) { }
        }
    }
}
