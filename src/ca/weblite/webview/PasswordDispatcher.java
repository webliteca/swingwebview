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
 * {@link WebViewComponent#setSavePasswordHandler},
 * {@link WebViewComponent#setFillPasswordHandler}, and the
 * {@code getCredential}/{@code getAllCredentials}/{@code saveCredential}/
 * {@code deleteCredential} methods.  This class is {@code public} only
 * because the consuming Swing subclasses live in a different package —
 * the same reason {@link DialogDispatcher} is public.
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
        // Canvas 26 4a: the username-first flow. A two-step login (Google, Microsoft,
        // Okta) puts the user name on a page of its own, so the password page has no
        // field to read. Remember what was typed on the earlier step -- in
        // sessionStorage, which is origin- and tab-scoped and survives the navigation
        // between the two documents -- and supply it only when the submitted form
        // offers no user name of its own. Five-minute window and the OTP guard mirror
        // Chromium's kSingleUsernameTimeToLive and its is_likely_otp rule.
        "var UKEY='__webview_pw_user__';var UTTL=300000;var memU=null;\n" +
        "function uPut(o){try{sessionStorage.setItem(UKEY,JSON.stringify(o));}catch(e){}memU=o;}\n" +
        "function uRead(){try{var r=sessionStorage.getItem(UKEY);if(r)return JSON.parse(r);}catch(e){}return memU;}\n" +
        "function isOtpish(n,a,v){var h=((n||'')+' '+(a||'')).toLowerCase();" +
        "if(/otp|one[-_ ]?time|passcode|verification|verify|2fa|mfa|security[-_ ]?code|token|pin/.test(h))return true;" +
        "return /^[0-9]{3,8}$/.test(v);}\n" +
        "function hasPassword(form){try{return !!(form&&form.querySelector&&" +
        "form.querySelector('input[type=password]'));}catch(e){return false;}}\n" +
        "function rememberUserName(el){try{if(!isTexty(el))return;var v=(el.value||'').trim();if(!v)return;" +
        "if(hasPassword(el.form))return;" +
        "uPut({v:v,t:Date.now(),n:(el.name||el.id||''),a:(el.getAttribute&&el.getAttribute('autocomplete'))||''," +
        "o:location.origin});}catch(e){}}\n" +
        "function rememberedUserName(){try{var c=uRead();if(!c||!c.v)return '';" +
        "if(c.o!==location.origin)return '';if(Date.now()-c.t>UTTL)return '';" +
        "if(isOtpish(c.n,c.a,c.v))return '';return c.v;}catch(e){return '';}}\n" +
        "document.addEventListener('change',function(ev){try{rememberUserName(ev.target);}catch(e){}},true);\n" +
        "document.addEventListener('submit',function(ev){try{var t=ev.target;" +
        "if(t&&!hasPassword(t)&&t.elements){for(var i=0;i<t.elements.length;i++){" +
        "if(isTexty(t.elements[i])&&(t.elements[i].value||'').trim()){rememberUserName(t.elements[i]);break;}}}}" +
        "catch(e){}},true);\n" +
        "var lastPost=0;\n" +
        "function capture(){try{var f=findFields();if(!f||!f.pass)return;var pv=f.pass.value;if(!pv)return;" +
        "var now=Date.now();if(now-lastPost<400)return;lastPost=now;" +
        "var uv=f.user?f.user.value:'';if(!uv)uv=rememberedUserName();" +
        "post('S|'+b64e(uv)+'|'+b64e(pv));}catch(e){}}\n" +
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
    private volatile WebViewFillPasswordHandler fillHandler =
        WebViewFillPasswordHandler.DEFAULT;
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

    /** Replace the autofill-consent policy; {@code null} restores
     *  {@link WebViewFillPasswordHandler#DEFAULT}. */
    public void setFillHandler(WebViewFillPasswordHandler h) {
        fillHandler = (h == null) ? WebViewFillPasswordHandler.DEFAULT : h;
    }

    /** @return the active autofill-consent policy; never {@code null}. */
    public WebViewFillPasswordHandler getFillHandler() { return fillHandler; }

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

    public List<WebViewCredential> getAllCredentials() {
        return store.findAll();
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
        // Run the store dedup check off the EDT and the native message
        // thread; only a new or changed credential reaches the prompt.
        io.execute(new Runnable() {
            @Override public void run() { maybePrompt(origin, user, pass); }
        });
    }

    /** On the io worker: drop the submission when it is already stored
     *  unchanged; otherwise marshal the save prompt to the EDT. */
    private void maybePrompt(String origin, String user, String pass) {
        if (disposed || !enabled) return;
        if (isAlreadyStored(origin, user, pass)) return; // unchanged: no prompt
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { runPrompt(origin, user, pass); }
        });
    }

    /** @return whether an identical {@code {origin, username, password}}
     *  credential is already stored. Compares username AND password
     *  explicitly, since {@link WebViewCredential#equals} ignores the
     *  password. Fails open (returns {@code false}) if the store read
     *  throws, so a save is still offered on a transient store error. */
    private boolean isAlreadyStored(String origin, String user, String pass) {
        try {
            for (WebViewCredential c : store.findAll(origin)) {
                if (c.username().equals(user) && c.password().equals(pass)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            forward(t);
        }
        return false;
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
        final WebViewCredential cred = c.get();
        // Marshal the consent decision (and the fire-and-forget eval) to
        // the EDT.  The keychain read above already ran off the EDT; only
        // the host-controlled fill policy and the eval run here.
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { fillOnEdt(origin, cred); }
        });
    }

    private void fillOnEdt(String origin, WebViewCredential cred) {
        if (disposed || !enabled) return;
        FillPasswordDisposition d;
        try {
            WebViewFillPasswordEvent ev =
                new WebViewFillPasswordEvent(source, origin, cred.username());
            d = fillHandler.onAutofillRequested(ev);
        } catch (Throwable t) {
            forward(t); // handler threw: fill nothing, stay responsive
            return;
        }
        if (d != FillPasswordDisposition.FILL) return; // DONT_FILL: skip
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
