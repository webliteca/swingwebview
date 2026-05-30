/*
 * MIT License
 *
 * Embedded-mode WebView2 (Chromium-Edge) for Swing/AWT on Windows.
 *
 * Creates a child HWND of the JAWT-provided AWT Canvas HWND and hosts an
 * ICoreWebView2Controller + ICoreWebView2 inside it.  The WebView2
 * environment runs on a dedicated thread.  Operations from Java (navigate,
 * eval, set_bounds, ...) marshal to that thread via PostThreadMessage.
 *
 * Built against the stable WebView2 SDK (Microsoft.Web.WebView2, 1.0.x),
 * statically linked via WebView2LoaderStatic.lib.  Requires the system-wide
 * WebView2 Runtime (ships with current Windows 11 / Edge).
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
// WIN32_LEAN_AND_MEAN excludes objbase.h, which defines `interface` (=struct)
// used pervasively by WebView2.h's COM declarations.  Pull it in explicitly.
#include <objbase.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <jawt.h>
#include <jawt_md.h>

#include "ca_weblite_webview_WebViewNative.h"
#include "WebView2.h"

#define WV_LOG(fmt, ...) do { \
    fprintf(stderr, "[webview-embed] " fmt "\n", ##__VA_ARGS__); \
    fflush(stderr); \
} while (0)

namespace embed_win {

static const UINT WM_EMBED_DISPATCH = WM_APP + 1;
static const UINT WM_EMBED_QUIT     = WM_APP + 2;

using DispatchFn = std::function<void()>;

struct JawtLock {
    JAWT awt{};
    JAWT_DrawingSurface *ds = nullptr;
    JAWT_DrawingSurfaceInfo *dsi = nullptr;
    jint lock = 0;
    bool ok = false;

    JawtLock(JNIEnv *env, jobject component) {
#if defined(JAWT_VERSION_9)
        awt.version = JAWT_VERSION_9;
        if (!JAWT_GetAWT(env, &awt)) {
            awt.version = JAWT_VERSION_1_4;
            if (!JAWT_GetAWT(env, &awt)) return;
        }
#else
        awt.version = JAWT_VERSION_1_4;
        if (!JAWT_GetAWT(env, &awt)) return;
#endif
        ds = awt.GetDrawingSurface(env, component);
        if (!ds) return;
        lock = ds->Lock(ds);
        if (lock & JAWT_LOCK_ERROR) {
            awt.FreeDrawingSurface(ds);
            ds = nullptr;
            return;
        }
        dsi = ds->GetDrawingSurfaceInfo(ds);
        if (!dsi) {
            ds->Unlock(ds);
            awt.FreeDrawingSurface(ds);
            ds = nullptr;
            return;
        }
        ok = true;
    }
    ~JawtLock() {
        if (ds) {
            if (dsi) ds->FreeDrawingSurfaceInfo(dsi);
            ds->Unlock(ds);
            awt.FreeDrawingSurface(ds);
        }
    }
};

struct Binding {
    std::string name;
    jobject fn = nullptr;
    jclass cls = nullptr;
};

struct Engine {
    HWND parent = nullptr;
    HWND child = nullptr;
    DWORD thread_id = 0;
    HANDLE thread = nullptr;
    ICoreWebView2Controller *controller = nullptr;
    ICoreWebView2 *webview = nullptr;
    EventRegistrationToken message_token{};
    EventRegistrationToken got_focus_token{};
    EventRegistrationToken lost_focus_token{};
    // EventRegistrationToken for the ScriptDialogOpening handler
    // registered in create_engine.  Not explicitly removed in
    // destroy_engine -- the controller / webview Release calls
    // there detach all event handlers transitively (same as
    // message_token / got_focus_token / lost_focus_token).
    EventRegistrationToken script_dialog_token{};
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;
    bool debug = false;
    std::mutex bindings_mutex;
    // JNI global ref to the registered WebViewFocusCallback, or nullptr.
    // Invoked from the WebView2 controller's GotFocus / LostFocus events.
    jobject focus_callback = nullptr;
    // JNI global ref to the registered WebViewClickCallback, or nullptr.
    // Invoked from the WM_PARENTNOTIFY hook in EmbedWndProc each time the
    // WebView2 child HWND receives a mouse-button-down message -- see
    // Operation 13 of the heavyweight-embedding Canvas.  Used by the
    // Swing wrapper to dismiss any open JPopupMenu when the user clicks
    // into the WebView (AWT's MouseGrabber AWTEventListener never sees
    // those clicks because they reach the WebView2 HWND directly).
    jobject click_callback = nullptr;
    // JNI global ref to the registered WebViewDialogCallback, or nullptr.
    // Storage only in this canvas (STORY-004-001) -- STORY-004-003 wires
    // ICoreWebView2::add_ScriptDialogOpening + AreDefaultScriptDialogsEnabled(FALSE)
    // to actually invoke this callback for JS alert / confirm / prompt.
    // The Windows file picker remains OS-native (WebView2 exposes no
    // public hook for <input type=file>), so this callback is never
    // invoked for the file-picker event kind on Windows.
    jobject dialog_callback = nullptr;
    // EventRegistrationToken for the DownloadStarting handler
    // registered (when ICoreWebView2_4 is available) in create_engine.
    // Same teardown rationale as script_dialog_token -- the controller /
    // webview Release calls detach event handlers transitively, but
    // when the QueryInterface for _4 failed at create time, this
    // token's value field stays 0 and the teardown-time remove call
    // (guarded below) becomes a no-op.
    EventRegistrationToken download_starting_token{};
    // JNI global ref to the registered WebViewDownloadCallback, or
    // nullptr.  Read by DownloadStartingHandler::Invoke below.  Same
    // teardown ordering as dialog_callback: cleared BEFORE the
    // controller / webview Release so any in-flight Invoke observes
    // a null field and short-circuits to put_Cancel(TRUE).
    jobject download_callback = nullptr;
};

static void fire_focus_callback(Engine *e, bool became) {
    if (!e || !e->focus_callback) return;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(e->focus_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(cls, "invoke", "(Z)V");
            if (m) {
                env->CallVoidMethod(e->focus_callback, m, (jboolean)became);
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) jvm->DetachCurrentThread();
}

// Invoke the Java WebViewClickCallback registered on the engine, if any.
// Called from EmbedWndProc when WM_PARENTNOTIFY arrives with a mouse-down
// trigger; the Java callback marshals to the EDT internally before
// touching Swing state.  Mirrors fire_focus_callback shape but the Java
// method has signature ()V (no boolean payload).
static void fire_click_callback(Engine *e) {
    if (!e || !e->click_callback) return;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jclass cls = env->GetObjectClass(e->click_callback);
        if (cls) {
            jmethodID m = env->GetMethodID(cls, "invoke", "()V");
            if (m) {
                env->CallVoidMethod(e->click_callback, m);
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (detach) jvm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------
// JS-initiated UI dialog bridge for WebView2.
//
// Three helpers mirroring the Linux fire_dialog_* shape (canvas-12 /
// STORY-004-002), used by ScriptDialogHandler::Invoke below.  Each helper
// attaches the spawned worker thread to the JVM, calls one of the three
// WebViewDialogCallback methods, sanitises any pending Java exception, and
// detaches.  Method names / signatures are byte-identical across all three
// platform binaries -- the same Java WebViewDialogCallback interface is the
// target.
//
// No fire_dialog_file_picker on Windows: WebView2 exposes no public hook
// for <input type=file>, so the file picker continues to use the OS-native
// Common Item Dialog and WebViewFilePickerEvent never fires on Windows.
// Documented platform limitation per canvas-11 / STORY-004-003 AC4.
// ---------------------------------------------------------------------------

static void fire_dialog_alert(JavaVM *jvm, jobject callback,
                              const char *message,
                              const char *pageUrl,
                              const char *frameUrl) {
    if (!jvm || !callback) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return;
    }
    jstring jmsg = env->NewStringUTF(message ? message : "");
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onAlert",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (m) {
            env->CallVoidMethod(callback, m, jmsg, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmsg) env->DeleteLocalRef(jmsg);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    if (detach) jvm->DetachCurrentThread();
}

static jboolean fire_dialog_confirm(JavaVM *jvm, jobject callback,
                                    const char *message,
                                    const char *pageUrl,
                                    const char *frameUrl) {
    if (!jvm || !callback) return JNI_FALSE;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return JNI_FALSE;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return JNI_FALSE;
    }
    jboolean result = JNI_FALSE;
    jstring jmsg = env->NewStringUTF(message ? message : "");
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onConfirm",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
        if (m) {
            result = env->CallBooleanMethod(callback, m, jmsg, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                result = JNI_FALSE;
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmsg) env->DeleteLocalRef(jmsg);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    if (detach) jvm->DetachCurrentThread();
    return result;
}

// Caller MUST free() the returned string when done.  Returns nullptr
// for cancel (Java returned null) or any error path -- the completion
// lambda treats nullptr as cancel and skips the put_ResultText / Accept
// calls so WebView2 reports null to the page.
static char *fire_dialog_prompt(JavaVM *jvm, jobject callback,
                                const char *message,
                                const char *defaultValue,
                                const char *pageUrl,
                                const char *frameUrl) {
    if (!jvm || !callback) return nullptr;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            return nullptr;
        }
        detach = true;
    }
    if (!env) {
        if (detach) jvm->DetachCurrentThread();
        return nullptr;
    }
    char *result = nullptr;
    jstring jmsg = env->NewStringUTF(message ? message : "");
    jstring jdefault = env->NewStringUTF(defaultValue ? defaultValue : "");
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onPrompt",
            "(Ljava/lang/String;Ljava/lang/String;"
            "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        if (m) {
            jstring jresult = (jstring)env->CallObjectMethod(
                callback, m, jmsg, jdefault, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                jresult = nullptr;
            }
            if (jresult) {
                const char *cstr = env->GetStringUTFChars(jresult, nullptr);
                if (cstr) {
                    size_t n = strlen(cstr);
                    result = (char *)malloc(n + 1);
                    if (result) memcpy(result, cstr, n + 1);
                    env->ReleaseStringUTFChars(jresult, cstr);
                }
                env->DeleteLocalRef(jresult);
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmsg) env->DeleteLocalRef(jmsg);
    if (jdefault) env->DeleteLocalRef(jdefault);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    if (detach) jvm->DetachCurrentThread();
    return result;  // caller owns; free with free()
}

// IUnknown helper -- gives each WebView2 callback proper refcounting and
// QueryInterface support.  The interfaces we implement are all single-
// inheritance (Iface : IUnknown), so a templated base keeps boilerplate low.
template <typename Iface>
class CallbackBase : public Iface {
public:
    ULONG STDMETHODCALLTYPE AddRef() override { return ++m_ref; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG n = --m_ref;
        if (n == 0) delete this;
        return n;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, LPVOID *ppv) override {
        if (!ppv) return E_POINTER;
        if (iid == __uuidof(Iface) || iid == IID_IUnknown) {
            *ppv = static_cast<Iface*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
protected:
    virtual ~CallbackBase() = default;
private:
    std::atomic<ULONG> m_ref{1};
};

class EnvHandler : public CallbackBase<
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler> {
public:
    using Cb = std::function<void(HRESULT, ICoreWebView2Environment*)>;
    explicit EnvHandler(Cb cb) : m_cb(std::move(cb)) {}
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result,
                                     ICoreWebView2Environment *env) override {
        m_cb(result, env);
        return S_OK;
    }
private:
    Cb m_cb;
};

class ControllerHandler : public CallbackBase<
    ICoreWebView2CreateCoreWebView2ControllerCompletedHandler> {
public:
    using Cb = std::function<void(HRESULT, ICoreWebView2Controller*)>;
    explicit ControllerHandler(Cb cb) : m_cb(std::move(cb)) {}
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result,
                                     ICoreWebView2Controller *ctrl) override {
        m_cb(result, ctrl);
        return S_OK;
    }
private:
    Cb m_cb;
};

// Forward declarations.
static void engine_on_message(Engine *e, LPCWSTR msg);
static std::wstring utf8_to_wide(const char *s);
static std::string wide_to_utf8(LPCWSTR w);
static void dispatch_to_thread(Engine *e, DispatchFn fn);

class FocusHandler : public CallbackBase<
    ICoreWebView2FocusChangedEventHandler> {
public:
    FocusHandler(Engine *e, bool became) : m_engine(e), m_became(became) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2Controller *,
                                     IUnknown *) override {
        fire_focus_callback(m_engine, m_became);
        return S_OK;
    }
private:
    Engine *m_engine;
    bool m_became;
};

class MsgHandler : public CallbackBase<
    ICoreWebView2WebMessageReceivedEventHandler> {
public:
    explicit MsgHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2WebMessageReceivedEventArgs *args) override {
        if (!args) return S_OK;
        LPWSTR msg = nullptr;
        if (FAILED(args->TryGetWebMessageAsString(&msg)) || !msg) {
            // Fallback: postMessage(object) -> JSON
            args->get_WebMessageAsJson(&msg);
        }
        if (msg) {
            engine_on_message(m_engine, msg);
            CoTaskMemFree(msg);
        }
        return S_OK;
    }
private:
    Engine *m_engine;
};

// ScriptDialogHandler -- bridges WebView2's ScriptDialogOpening event to the
// per-engine Java DialogDispatcher (canvas-13 / STORY-004-003).  Invoke runs
// on the WebView2 worker thread per Microsoft's threading model; we must NOT
// block it synchronously waiting for the EDT (the completion-side
// dispatch_to_thread would self-deadlock).  Instead we GetDeferral, spawn a
// short-lived std::thread for the JNI hop into DialogDispatcher (which does
// SwingUtilities.invokeAndWait), and once the answer is in hand we
// dispatch_to_thread back onto the WebView2 worker to call
// Accept / put_ResultText / Complete in the right COM apartment.
//
// `args` and `deferral` are AddRef'd before the worker thread launches and
// Release'd inside the completion lambda -- WebView2 would otherwise release
// the args between the S_OK return and the completion.
//
// On null dialog_callback the safe-default path runs (alert: Accept; confirm:
// don't Accept -> page sees false; prompt: don't put_ResultText / Accept ->
// page sees null) so the page's JS thread always resumes.  before-unload
// routes to fire_dialog_confirm, matching Linux behaviour.  File picker is
// NOT intercepted -- WebView2 has no public hook.
class ScriptDialogHandler : public CallbackBase<
    ICoreWebView2ScriptDialogOpeningEventHandler> {
public:
    explicit ScriptDialogHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2ScriptDialogOpeningEventArgs *args) override {
        if (!args || !m_engine) return S_OK;

        COREWEBVIEW2_SCRIPT_DIALOG_KIND kind =
            COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT;
        args->get_Kind(&kind);

        LPWSTR uri_w = nullptr;
        LPWSTR msg_w = nullptr;
        LPWSTR def_w = nullptr;
        args->get_Uri(&uri_w);
        args->get_Message(&msg_w);
        if (kind == COREWEBVIEW2_SCRIPT_DIALOG_KIND_PROMPT) {
            args->get_DefaultText(&def_w);
        }
        std::string uri = wide_to_utf8(uri_w);
        std::string msg = wide_to_utf8(msg_w);
        std::string def = wide_to_utf8(def_w);
        if (uri_w) CoTaskMemFree(uri_w);
        if (msg_w) CoTaskMemFree(msg_w);
        if (def_w) CoTaskMemFree(def_w);

        ICoreWebView2Deferral *deferral = nullptr;
        HRESULT hr = args->GetDeferral(&deferral);
        if (FAILED(hr) || !deferral) {
            // Can't defer -- the SDK refused to hand us a deferral.  Rare;
            // log and let WebView2's default-suppressed flow take its
            // course (page receives the JS-spec default for each kind).
            WV_LOG("GetDeferral failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            return S_OK;
        }
        args->AddRef();

        Engine *e = m_engine;
        std::thread([e, args, deferral, kind, uri, msg, def] {
            jobject cb = e ? e->dialog_callback : nullptr;
            JavaVM *jvm = e ? e->jvm : nullptr;
            jboolean confirmed = JNI_FALSE;
            char *prompt_answer = nullptr;
            switch (kind) {
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT:
                    if (cb) fire_dialog_alert(
                        jvm, cb, msg.c_str(),
                        uri.c_str(), uri.c_str());
                    break;
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_CONFIRM:
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD:
                    if (cb) confirmed = fire_dialog_confirm(
                        jvm, cb, msg.c_str(),
                        uri.c_str(), uri.c_str());
                    break;
                case COREWEBVIEW2_SCRIPT_DIALOG_KIND_PROMPT:
                    if (cb) prompt_answer = fire_dialog_prompt(
                        jvm, cb, msg.c_str(), def.c_str(),
                        uri.c_str(), uri.c_str());
                    break;
                default:
                    break;
            }

            // Marshal back onto the WebView2 worker thread; ICoreWebView2*
            // methods are apartment-bound to the engine's worker, so
            // Accept / put_ResultText / Complete must NOT run on the Java
            // worker thread we're currently in.
            dispatch_to_thread(e, [args, deferral, kind, confirmed,
                                   prompt_answer] {
                switch (kind) {
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT:
                        args->Accept();
                        break;
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_CONFIRM:
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD:
                        if (confirmed == JNI_TRUE) args->Accept();
                        // else: don't Accept -- WebView2 returns false to
                        // the page.
                        break;
                    case COREWEBVIEW2_SCRIPT_DIALOG_KIND_PROMPT:
                        if (prompt_answer != nullptr) {
                            std::wstring wtxt = utf8_to_wide(prompt_answer);
                            args->put_ResultText(wtxt.c_str());
                            args->Accept();
                            free(prompt_answer);
                        }
                        // else: don't put_ResultText, don't Accept --
                        // WebView2 returns null to the page.
                        break;
                    default:
                        // Unknown future SDK kind: just Complete the
                        // deferral without Accept; the page sees the
                        // JS-spec default.
                        break;
                }
                deferral->Complete();
                deferral->Release();
                args->Release();
            });
        }).detach();

        return S_OK;
    }
private:
    Engine *m_engine;
};

// DownloadStartingHandler -- bridges WebView2's DownloadStarting event to
// the per-engine Java DownloadDispatcher (STORY-005-001).  Sibling of
// ScriptDialogHandler above; same GetDeferral + std::thread + dispatch_to
// _thread pattern.
//
// Microsoft's WebView2 threading model: Invoke runs on the WebView2
// worker thread.  We must NOT block it synchronously waiting for the
// EDT (the completion-side dispatch_to_thread would self-deadlock).
// GetDeferral, AddRef the args, spawn a short-lived std::thread for the
// JNI hop into DownloadDispatcher (which does SwingUtilities.invokeAndWait),
// then dispatch_to_thread back onto the WebView2 worker to call
// put_ResultFilePath / put_Cancel / Complete in the right COM apartment.
//
// On null download_callback the safe-default path runs (cancel via
// put_Cancel(TRUE)) so the engine never hangs.
class DownloadStartingHandler : public CallbackBase<
    ICoreWebView2DownloadStartingEventHandler> {
public:
    explicit DownloadStartingHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2DownloadStartingEventArgs *args) override {
        if (!args || !m_engine) return S_OK;

        // Grab the DownloadOperation -- the per-download object exposes
        // the response metadata (URI, MIME, total bytes) we need.
        ICoreWebView2DownloadOperation *op = nullptr;
        args->get_DownloadOperation(&op);

        // Read URI / MIME / TotalBytesToReceive off the operation.
        LPWSTR uri_w = nullptr;
        LPWSTR mime_w = nullptr;
        if (op) {
            op->get_Uri(&uri_w);
            op->get_MimeType(&mime_w);
        }
        std::string uri = wide_to_utf8(uri_w);
        std::string mime = wide_to_utf8(mime_w);
        if (uri_w) CoTaskMemFree(uri_w);
        if (mime_w) CoTaskMemFree(mime_w);

        INT64 total_bytes = -1;
        if (op) {
            op->get_TotalBytesToReceive(&total_bytes);
        }

        // The engine populates args->ResultFilePath with the default
        // chosen path (typically ~/Downloads/<filename>); extract just
        // the filename to surface as event.suggestedFilename().
        // Hand-rolled basename to avoid adding the shlwapi.h
        // dependency (PathFindFileNameW would need shlwapi.lib in
        // the link line).
        LPWSTR result_path_w = nullptr;
        args->get_ResultFilePath(&result_path_w);
        std::string suggested_filename;
        if (result_path_w) {
            LPCWSTR base = result_path_w;
            LPCWSTR p = result_path_w;
            for (; *p; ++p) {
                if (*p == L'\\' || *p == L'/') {
                    base = p + 1;
                }
            }
            suggested_filename = wide_to_utf8(base);
            CoTaskMemFree(result_path_w);
        }

        ICoreWebView2Deferral *deferral = nullptr;
        HRESULT hr = args->GetDeferral(&deferral);
        if (FAILED(hr) || !deferral) {
            // SDK refused to hand us a deferral -- rare.  Without
            // deferral we can't async-resolve; let WebView2 run its
            // default UI.  Releases acquired earlier.
            WV_LOG("DownloadStarting GetDeferral failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            if (op) op->Release();
            return S_OK;
        }
        args->AddRef();

        Engine *e = m_engine;
        std::thread([e, args, deferral, op, suggested_filename, uri, mime,
                     total_bytes] {
            jobject cb = e ? e->download_callback : nullptr;
            JavaVM *jvm = e ? e->jvm : nullptr;
            std::string java_path;
            if (cb && jvm) {
                JNIEnv *env = nullptr;
                bool detach = false;
                if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
                    jvm->AttachCurrentThread((void **)&env, nullptr);
                    detach = true;
                }
                if (env) {
                    jclass cls = env->GetObjectClass(cb);
                    if (cls) {
                        jmethodID mid = env->GetMethodID(
                            cls, "onDownloadStarting",
                            "(Ljava/lang/String;Ljava/lang/String;"
                            "Ljava/lang/String;J)Ljava/lang/String;");
                        if (mid) {
                            jstring jname = env->NewStringUTF(
                                suggested_filename.c_str());
                            jstring juri = env->NewStringUTF(uri.c_str());
                            jstring jmime = env->NewStringUTF(mime.c_str());
                            jobject ret = env->CallObjectMethod(
                                cb, mid, jname, juri, jmime,
                                (jlong)total_bytes);
                            if (env->ExceptionCheck()) {
                                env->ExceptionDescribe();
                                env->ExceptionClear();
                            } else if (ret) {
                                const char *utf = env->GetStringUTFChars(
                                    (jstring)ret, nullptr);
                                if (utf) {
                                    java_path = std::string(utf);
                                    env->ReleaseStringUTFChars(
                                        (jstring)ret, utf);
                                }
                                env->DeleteLocalRef(ret);
                            }
                            if (jname) env->DeleteLocalRef(jname);
                            if (juri) env->DeleteLocalRef(juri);
                            if (jmime) env->DeleteLocalRef(jmime);
                        }
                        env->DeleteLocalRef(cls);
                    }
                    if (detach) jvm->DetachCurrentThread();
                }
            }

            // Marshal back onto the WebView2 worker for the COM-apartment-
            // bound put_ResultFilePath / put_Cancel / Complete calls.
            std::string captured_path = java_path;
            dispatch_to_thread(e, [args, deferral, op, captured_path] {
                if (captured_path.empty()) {
                    args->put_Cancel(TRUE);
                } else {
                    std::wstring wpath = utf8_to_wide(captured_path);
                    args->put_ResultFilePath(wpath.c_str());
                }
                deferral->Complete();
                deferral->Release();
                args->Release();
                if (op) op->Release();
            });
        }).detach();

        return S_OK;
    }
private:
    Engine *m_engine;
};

static void engine_on_message(Engine *e, LPCWSTR msg) {
    if (!msg) return;
    int n = WideCharToMultiByte(CP_UTF8, 0, msg, -1, nullptr, 0, nullptr, nullptr);
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, msg, -1, &s[0], n, nullptr, nullptr);
    if (!s.empty() && s.back() == '\0') s.pop_back();

    auto pos = s.find("\"name\":\"");
    if (pos == std::string::npos) return;
    auto start = pos + 8;
    auto end = s.find('"', start);
    if (end == std::string::npos) return;
    std::string name = s.substr(start, end - start);

    Binding *b = nullptr;
    {
        std::lock_guard<std::mutex> lk(e->bindings_mutex);
        auto it = e->bindings.find(name);
        if (it == e->bindings.end()) return;
        b = it->second;
    }
    JNIEnv *env = nullptr;
    bool detach = false;
    if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        e->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    jmethodID mid = env->GetMethodID(b->cls, "invoke", "(Ljava/lang/String;J)V");
    if (mid) {
        jstring js = env->NewStringUTF(s.c_str());
        env->CallVoidMethod(b->fn, mid, js, (jlong)e);
        env->DeleteLocalRef(js);
    }
    if (detach) e->jvm->DetachCurrentThread();
}

static LRESULT CALLBACK EmbedWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    Engine *e = (Engine *)GetWindowLongPtr(hwnd, GWLP_USERDATA);
    switch (msg) {
    case WM_SIZE:
        if (e && e->controller) {
            RECT r;
            GetClientRect(hwnd, &r);
            e->controller->put_Bounds(r);
        }
        return 0;
    case WM_PARENTNOTIFY:
        // Windows posts WM_PARENTNOTIFY to the parent HWND whenever a
        // direct child receives a mouse-button-down message.  Our child
        // HWND ("WebViewEmbedChild") is the immediate parent of the
        // WebView2-created HWND, so a click on the WebView2 surface
        // arrives here as WM_PARENTNOTIFY with LOWORD(wParam) carrying
        // the original message id.  This is the Windows half of the
        // cross-platform native click hook used to dismiss any open
        // Swing JPopupMenu when the user clicks into the WebView -- AWT's
        // MouseGrabber AWTEventListener never sees these clicks because
        // they reach the WebView2 HWND directly rather than through AWT.
        if (e) {
            switch (LOWORD(wp)) {
            case WM_LBUTTONDOWN:
            case WM_RBUTTONDOWN:
            case WM_MBUTTONDOWN:
            case WM_XBUTTONDOWN:
                fire_click_callback(e);
                break;
            default:
                break;
            }
        }
        return DefWindowProc(hwnd, msg, wp, lp);
    default:
        return DefWindowProc(hwnd, msg, wp, lp);
    }
}

static ATOM ensure_class_registered() {
    static ATOM atom = 0;
    if (atom != 0) return atom;
    WNDCLASSEX wc{};
    wc.cbSize = sizeof(wc);
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = "WebViewEmbedChild";
    wc.lpfnWndProc = EmbedWndProc;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    atom = RegisterClassEx(&wc);
    return atom;
}

static void engine_thread(Engine *e, HWND /*parent*/, int width, int height,
                          bool /*debug*/, std::atomic<bool> *ready,
                          std::atomic<bool> *ok) {
    ensure_class_registered();
    e->child = CreateWindowEx(0, "WebViewEmbedChild", "",
                              WS_CHILD | WS_VISIBLE,
                              0, 0, width, height, e->parent,
                              nullptr, GetModuleHandle(nullptr), nullptr);
    if (!e->child) {
        WV_LOG("CreateWindowEx for child HWND failed: GetLastError=%lu",
               GetLastError());
        *ok = false; *ready = true;
        return;
    }
    SetWindowLongPtr(e->child, GWLP_USERDATA, (LONG_PTR)e);

    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    std::atomic_flag init_done = ATOMIC_FLAG_INIT;
    init_done.test_and_set();
    HRESULT init_res = S_OK;

    auto *env_handler = new EnvHandler(
        [&](HRESULT r, ICoreWebView2Environment *env) {
            if (FAILED(r) || !env) {
                WV_LOG("CreateCoreWebView2EnvironmentWithOptions "
                       "completion failed: HRESULT=0x%08lx",
                       (unsigned long)r);
                init_res = r;
                init_done.clear();
                return;
            }
            auto *ctrl_handler = new ControllerHandler(
                [&](HRESULT r2, ICoreWebView2Controller *ctrl) {
                    if (FAILED(r2) || !ctrl) {
                        WV_LOG("CreateCoreWebView2Controller completion "
                               "failed: HRESULT=0x%08lx",
                               (unsigned long)r2);
                        init_res = r2;
                        init_done.clear();
                        return;
                    }
                    ctrl->AddRef();
                    e->controller = ctrl;
                    HRESULT r3 = ctrl->get_CoreWebView2(&e->webview);
                    if (FAILED(r3) || !e->webview) {
                        WV_LOG("get_CoreWebView2 failed: HRESULT=0x%08lx",
                               (unsigned long)r3);
                        init_res = r3;
                        init_done.clear();
                        return;
                    }
                    e->webview->AddRef();

                    RECT rc;
                    GetClientRect(e->child, &rc);
                    ctrl->put_Bounds(rc);
                    ctrl->put_IsVisible(TRUE);

                    ICoreWebView2Settings *settings = nullptr;
                    if (SUCCEEDED(e->webview->get_Settings(&settings)) &&
                        settings) {
                        settings->put_AreDevToolsEnabled(
                            e->debug ? TRUE : FALSE);
                        settings->put_AreDefaultContextMenusEnabled(TRUE);
                        // Suppress WebView2's built-in alert / confirm /
                        // prompt dialogs.  Java drives the response via
                        // the ScriptDialogHandler registered below.
                        // STORY-004-003.
                        settings->put_AreDefaultScriptDialogsEnabled(FALSE);
                        settings->Release();
                    }

                    // Shim window.external.invoke to use the modern
                    // WebView2 message channel.  Existing demos and the
                    // standalone API both call window.external.invoke.
                    e->webview->AddScriptToExecuteOnDocumentCreated(
                        L"window.external = { invoke: s => "
                        L"window.chrome.webview.postMessage(s) };",
                        nullptr);

                    auto *mh = new MsgHandler(e);
                    e->webview->add_WebMessageReceived(mh, &e->message_token);
                    mh->Release();

                    // Wire JS-initiated dialogs (alert / confirm / prompt
                    // / before-unload) to the per-engine Java
                    // DialogDispatcher via the dialog_callback global ref.
                    // WebView2's built-in dialogs are suppressed by
                    // put_AreDefaultScriptDialogsEnabled(FALSE) above.
                    // File picker (<input type=file>) is NOT intercepted
                    // -- WebView2 exposes no public hook; the OS-native
                    // Common Item Dialog continues to appear as before.
                    // STORY-004-003.
                    auto *sdh = new ScriptDialogHandler(e);
                    e->webview->add_ScriptDialogOpening(
                        sdh, &e->script_dialog_token);
                    sdh->Release();

                    // Wire browser-initiated downloads (STORY-005-001) to
                    // the per-engine Java DownloadDispatcher via the
                    // download_callback global ref.  Available only on
                    // ICoreWebView2_4 (modern Evergreen WebView2 Runtime);
                    // older runtimes lack the interface and downloads
                    // continue to silently drop -- documented as a
                    // platform caveat.
                    ICoreWebView2_4 *webview4 = nullptr;
                    HRESULT qhr = e->webview->QueryInterface(
                        IID_PPV_ARGS(&webview4));
                    if (SUCCEEDED(qhr) && webview4) {
                        auto *dsh = new DownloadStartingHandler(e);
                        webview4->add_DownloadStarting(
                            dsh, &e->download_starting_token);
                        dsh->Release();
                        webview4->Release();
                    } else {
                        WV_LOG("ICoreWebView2_4 not available; "
                               "downloads disabled. HRESULT=0x%08lx",
                               (unsigned long)qhr);
                    }

                    // Hook GotFocus / LostFocus on the controller so the
                    // Java side can suppress and restore the previously-
                    // focused JTextComponent's caret while WebView2 holds
                    // Win32 keyboard focus.  Two separate handler
                    // instances because the same callback signature has
                    // no way to distinguish got vs lost from the args.
                    auto *gh = new FocusHandler(e, true);
                    ctrl->add_GotFocus(gh, &e->got_focus_token);
                    gh->Release();
                    auto *lh = new FocusHandler(e, false);
                    ctrl->add_LostFocus(lh, &e->lost_focus_token);
                    lh->Release();

                    init_done.clear();
                });
            HRESULT r2 = env->CreateCoreWebView2Controller(
                e->child, ctrl_handler);
            ctrl_handler->Release();
            if (FAILED(r2)) {
                WV_LOG("CreateCoreWebView2Controller call failed: "
                       "HRESULT=0x%08lx", (unsigned long)r2);
                init_res = r2;
                init_done.clear();
            }
        });
    HRESULT res = CreateCoreWebView2EnvironmentWithOptions(
        nullptr, nullptr, nullptr, env_handler);
    env_handler->Release();
    if (FAILED(res)) {
        WV_LOG("CreateCoreWebView2EnvironmentWithOptions failed: "
               "HRESULT=0x%08lx", (unsigned long)res);
        if (res == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)) {
            WV_LOG("  The WebView2 runtime is not installed.  Install it:");
            WV_LOG("  https://developer.microsoft.com/microsoft-edge/webview2/");
        }
        *ok = false; *ready = true;
        return;
    }

    // Pump until env+controller flow completes.
    MSG msg{};
    while (init_done.test_and_set() && GetMessage(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    if (!e->webview || FAILED(init_res)) {
        WV_LOG("WebView2 init did not produce an ICoreWebView2");
        *ok = false; *ready = true;
        return;
    }

    *ok = true; *ready = true;

    // Main loop -- Java -> native ops arrive as WM_EMBED_DISPATCH messages.
    while (GetMessage(&msg, nullptr, 0, 0)) {
        if (msg.message == WM_EMBED_DISPATCH) {
            auto *fn = (DispatchFn *)msg.lParam;
            (*fn)();
            delete fn;
            continue;
        }
        if (msg.message == WM_EMBED_QUIT) break;
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    if (e->controller) {
        e->controller->Close();
        e->controller->Release();
        e->controller = nullptr;
    }
    if (e->webview) {
        e->webview->Release();
        e->webview = nullptr;
    }
    CoUninitialize();
}

static void dispatch_to_thread(Engine *e, DispatchFn fn) {
    if (!e || e->thread_id == 0) return;
    auto *holder = new DispatchFn(std::move(fn));
    PostThreadMessage(e->thread_id, WM_EMBED_DISPATCH, 0, (LPARAM)holder);
}

static Engine *create_engine(JNIEnv *env, jobject component, int debug) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) {
        WV_LOG("JAWT lock failed (ok=%d dsi=%p)",
               lock.ok ? 1 : 0,
               lock.dsi ? lock.dsi->platformInfo : nullptr);
        return nullptr;
    }
    auto *info = (JAWT_Win32DrawingSurfaceInfo *)lock.dsi->platformInfo;
    HWND parent = info->hwnd;
    if (!parent) {
        WV_LOG("JAWT platform info had no HWND");
        return nullptr;
    }
    RECT r;
    GetClientRect(parent, &r);
    int width = r.right - r.left;
    int height = r.bottom - r.top;
    if (width <= 0) width = 1;
    if (height <= 0) height = 1;

    auto *e = new Engine();
    e->parent = parent;
    e->debug = debug != 0;
    env->GetJavaVM(&e->jvm);

    std::atomic<bool> ready{false};
    std::atomic<bool> ok{false};
    std::thread t(engine_thread, e, parent, width, height, e->debug,
                  &ready, &ok);
    e->thread_id = GetThreadId(t.native_handle());
    e->thread = t.native_handle();
    t.detach();
    while (!ready.load()) {
        Sleep(1);
    }
    if (!ok.load()) {
        PostThreadMessage(e->thread_id, WM_EMBED_QUIT, 0, 0);
        delete e;
        return nullptr;
    }
    return e;
}

static void destroy_engine(Engine *e) {
    if (!e) return;
    if (e->thread_id) {
        PostThreadMessage(e->thread_id, WM_EMBED_QUIT, 0, 0);
    }
    // Release the focus callback global ref BEFORE the WebView2 worker
    // thread tears down -- the GotFocus / LostFocus handlers can fire
    // during teardown (LostFocus in particular fires when the controller
    // is closed) and we don't want them invoking a callback into a freed
    // Java global ref.
    if (e->focus_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->focus_callback);
        e->focus_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the click callback global ref.  Cleared after
    // the focus callback above but before the worker thread teardown so
    // a late WM_PARENTNOTIFY arriving during destruction reads a null
    // field instead of invoking a freed ref.
    if (e->click_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Symmetric cleanup for the dialog callback global ref.  Storage
    // only in this canvas; STORY-004-003 will fire the ScriptDialogOpening
    // event handler off this field, so the symmetric cleanup is required
    // even though STORY-004-001 itself never invokes the callback on
    // Windows.
    if (e->dialog_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the download callback global ref.  Cleared
    // BEFORE the controller / webview Release calls so any in-flight
    // DownloadStartingHandler::Invoke observes a null field and
    // short-circuits to put_Cancel(TRUE) on the worker thread.
    if (e->download_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    {
        std::lock_guard<std::mutex> lk(e->bindings_mutex);
        for (auto &kv : e->bindings) {
            Binding *b = kv.second;
            JNIEnv *env = nullptr;
            bool detach = false;
            if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
                e->jvm->AttachCurrentThread((void **)&env, nullptr);
                detach = true;
            }
            if (env) {
                env->DeleteGlobalRef(b->fn);
                env->DeleteGlobalRef(b->cls);
            }
            if (detach) e->jvm->DetachCurrentThread();
            delete b;
        }
        e->bindings.clear();
    }
    delete e;
}

static std::wstring utf8_to_wide(const char *s) {
    if (!s) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, &w[0], n);
    if (!w.empty() && w.back() == L'\0') w.pop_back();
    return w;
}

// Convert a (possibly null) UTF-16 LPWSTR to a UTF-8 std::string.
// Returns the empty string for null / empty input or on WideCharToMultiByte
// failure.  Used by ScriptDialogHandler::Invoke to copy the COM-allocated
// args strings into UTF-8 std::strings BEFORE the JNI worker thread runs,
// so we can CoTaskMemFree the LPWSTRs while still on the WebView2 worker
// (mirrors the inline pattern already used in engine_on_message).
static std::string wide_to_utf8(LPCWSTR w) {
    if (!w || *w == L'\0') return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0,
                                nullptr, nullptr);
    if (n <= 0) return std::string();
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    if (!s.empty() && s.back() == '\0') s.pop_back();
    return s;
}

} // namespace embed_win

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

using embed_win::Binding;
using embed_win::Engine;
using embed_win::JawtLock;

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_jawt_1get_1window_1handle
  (JNIEnv *env, jclass, jobject component) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) return 0;
    auto *info = (JAWT_Win32DrawingSurfaceInfo *)lock.dsi->platformInfo;
    return (jlong)(uintptr_t)info->hwnd;
}

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1create
  (JNIEnv *env, jclass, jobject component, jint debug) {
    Engine *e = embed_win::create_engine(env, component, debug);
    return (jlong)e;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1destroy
  (JNIEnv *, jclass, jlong wv) {
    embed_win::destroy_engine((Engine *)wv);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1bounds
  (JNIEnv *, jclass, jlong wv, jint /*x*/, jint /*y*/, jint w, jint h) {
    auto *e = (Engine *)wv;
    if (!e) return;
    // The Java side sends x,y in AWT-window content-pane coordinates (used
    // by the macOS WKWebView path, which is a sibling of the canvas).  On
    // Windows our child HWND is parented directly under the canvas's HWND,
    // so it should always sit at (0,0) relative to its parent -- using the
    // window-relative x,y would offset us by the canvas's own position.
    embed_win::dispatch_to_thread(e, [e, w, h] {
        if (e->child) {
            SetWindowPos(e->child, nullptr, 0, 0, w, h,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        if (e->controller) {
            RECT r{0, 0, w, h};
            e->controller->put_Bounds(r);
        }
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1visible
  (JNIEnv *, jclass, jlong wv, jint visible) {
    auto *e = (Engine *)wv;
    if (!e) return;
    embed_win::dispatch_to_thread(e, [e, visible] {
        if (e->controller) e->controller->put_IsVisible(visible != 0 ? TRUE : FALSE);
        if (e->child) ShowWindow(e->child, visible != 0 ? SW_SHOWNA : SW_HIDE);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1request_1focus
  (JNIEnv *, jclass, jlong wv) {
    auto *e = (Engine *)wv;
    if (!e) return;
    embed_win::dispatch_to_thread(e, [e] {
        if (e->controller) {
            e->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
        }
    });
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1pump
  (JNIEnv *, jclass, jlong /*wv*/, jint /*wait*/) {
    // Worker thread handles its own pumping.
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1navigate
  (JNIEnv *env, jclass, jlong wv, jstring url) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *u = env->GetStringUTFChars(url, nullptr);
    std::wstring w = embed_win::utf8_to_wide(u);
    env->ReleaseStringUTFChars(url, u);
    embed_win::dispatch_to_thread(e, [e, w] {
        if (e->webview) e->webview->Navigate(w.c_str());
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1init
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *s = env->GetStringUTFChars(js, nullptr);
    std::wstring w = embed_win::utf8_to_wide(s);
    env->ReleaseStringUTFChars(js, s);
    embed_win::dispatch_to_thread(e, [e, w] {
        if (e->webview) e->webview->AddScriptToExecuteOnDocumentCreated(
            w.c_str(), nullptr);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1eval
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *s = env->GetStringUTFChars(js, nullptr);
    std::wstring w = embed_win::utf8_to_wide(s);
    env->ReleaseStringUTFChars(js, s);
    embed_win::dispatch_to_thread(e, [e, w] {
        if (e->webview) e->webview->ExecuteScript(w.c_str(), nullptr);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1bind
  (JNIEnv *env, jclass, jlong wv, jstring name, jobject fn, jlong /*arg*/) {
    auto *e = (Engine *)wv;
    if (!e) return;
    const char *n = env->GetStringUTFChars(name, nullptr);
    Binding *b = new Binding();
    b->name = n ? n : "";
    b->fn = env->NewGlobalRef(fn);
    jclass cls = env->GetObjectClass(fn);
    b->cls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    env->ReleaseStringUTFChars(name, n);

    {
        std::lock_guard<std::mutex> lk(e->bindings_mutex);
        e->bindings[b->name] = b;
    }

    std::string js =
        std::string("(function(){var n='") + b->name + "';" +
        "window[n]=function(){"
        "  var me=window[n];"
        "  if(!me.callbacks){me.callbacks={};me.errors={};}"
        "  var seq=(me.lastSeq||0)+1;me.lastSeq=seq;"
        "  var p=new Promise(function(res,rej){me.callbacks[seq]=res;me.errors[seq]=rej;});"
        "  window.external.invoke(JSON.stringify({name:n,seq:seq,"
        "    args:Array.prototype.slice.call(arguments)}));"
        "  return p;"
        "};})()";
    std::wstring wjs = embed_win::utf8_to_wide(js.c_str());
    embed_win::dispatch_to_thread(e, [e, wjs] {
        if (e->webview) e->webview->AddScriptToExecuteOnDocumentCreated(
            wjs.c_str(), nullptr);
    });
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1dispatch
  (JNIEnv *env, jclass, jlong wv, jobject callback) {
    auto *e = (Engine *)wv;
    if (!e) return;
    JavaVM *jvm = e->jvm;
    jobject ref = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    jclass gcls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    embed_win::dispatch_to_thread(e, [jvm, ref, gcls] {
        JNIEnv *e2 = nullptr;
        bool detach = false;
        if (jvm->GetEnv((void **)&e2, JNI_VERSION_1_6) != JNI_OK) {
            jvm->AttachCurrentThread((void **)&e2, nullptr);
            detach = true;
        }
        jmethodID m = e2->GetMethodID(gcls, "run", "()V");
        if (m) e2->CallVoidMethod(ref, m);
        e2->DeleteGlobalRef(ref);
        e2->DeleteGlobalRef(gcls);
        if (detach) jvm->DetachCurrentThread();
    });
}

// ---------------------------------------------------------------------------
// Offscreen / lightweight JNI exports.  Lightweight is Linux-only today;
// Windows stubs return 0 / no-op so WebViewLightweightComponent falls back
// to its empty Swing background.
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1create
  (JNIEnv *, jclass, jint, jint, jint) { return 0; }

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1destroy
  (JNIEnv *, jclass, jlong) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1resize
  (JNIEnv *, jclass, jlong, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1navigate
  (JNIEnv *, jclass, jlong, jstring) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1snapshot
  (JNIEnv *, jclass, jlong, jintArray, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1button
  (JNIEnv *, jclass, jlong, jint, jint, jint, jint, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1motion
  (JNIEnv *, jclass, jlong, jint, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1scroll
  (JNIEnv *, jclass, jlong, jint, jint, jdouble, jdouble, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1key_1event
  (JNIEnv *, jclass, jlong, jint, jint, jint, jint) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1init
  (JNIEnv *, jclass, jlong, jstring) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1eval
  (JNIEnv *, jclass, jlong, jstring) {}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1bind
  (JNIEnv *, jclass, jlong, jstring, jobject, jlong) {}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1open_1devtools
  (JNIEnv *, jclass, jlong) { return 0; }

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1execute_1editing_1command
  (JNIEnv *, jclass, jlong, jint) {}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1open_1devtools
  (JNIEnv *, jclass, jlong wv) {
    auto *e = (Engine *)wv;
    if (!e) return 0;
    // Marshal to the WebView2 worker thread (the thread the controller
    // was created on) and call OpenDevToolsWindow.  Synchronously wait
    // for the worker to report success/failure so the JNI return value
    // accurately reflects whether the window opened.  The wait is
    // bounded by normal WebView2 method dispatch.
    std::atomic<bool> done{false};
    std::atomic<int> result{0};
    embed_win::dispatch_to_thread(e, [e, &done, &result] {
        if (!e->webview) { result.store(0); done.store(true); return; }
        ICoreWebView2Settings *settings = nullptr;
        BOOL enabled = FALSE;
        if (SUCCEEDED(e->webview->get_Settings(&settings)) && settings) {
            settings->get_AreDevToolsEnabled(&enabled);
            settings->Release();
        }
        if (!enabled) { result.store(0); done.store(true); return; }
        HRESULT hr = e->webview->OpenDevToolsWindow();
        if (FAILED(hr)) {
            WV_LOG("OpenDevToolsWindow failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            result.store(0);
        } else {
            result.store(1);
        }
        done.store(true);
    });
    while (!done.load()) {
        Sleep(1);
    }
    return (jint)result.load();
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1is_1native_1first_1responder
  (JNIEnv *, jclass, jlong) {
    // Windows has no notion of "first responder"; the focus-cooperation
    // dispatcher heuristic is macOS-only.  Returning 0 means the Java
    // dispatcher falls back to its standard AWT-focus-owner gating.
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1focus_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Store the Java callback (JNI global ref) on the Engine.  The
    // ICoreWebView2 GotFocus / LostFocus handlers (registered during
    // engine creation) read this field and invoke the callback so the
    // Java side can mirror WebView2's focus state into Swing -- e.g.,
    // suppress and restore the previously-focused JTextComponent's
    // caret while WebView2 holds Win32 keyboard focus.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->focus_callback) {
        env->DeleteGlobalRef(e->focus_callback);
        e->focus_callback = nullptr;
    }
    if (cb) {
        e->focus_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1click_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Store the Java callback (JNI global ref) on the Engine.  The
    // WM_PARENTNOTIFY hook in EmbedWndProc reads this field and invokes
    // the callback for every WM_LBUTTONDOWN / WM_RBUTTONDOWN /
    // WM_MBUTTONDOWN / WM_XBUTTONDOWN that the WebView2 child HWND
    // receives, so Swing can dismiss any open JPopupMenu when the user
    // clicks into the WebView.  Without this hook AWT's MouseGrabber
    // AWTEventListener never sees the click and the popup stays open.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->click_callback) {
        env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
    }
    if (cb) {
        e->click_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1dialog_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Store the Java callback (JNI global ref) on the Engine.
    // STORY-004-001 only ships storage on Windows -- STORY-004-003
    // wires the ICoreWebView2::add_ScriptDialogOpening event handler
    // off this field plus put_AreDefaultScriptDialogsEnabled(FALSE) so
    // built-in WebView2 dialogs are suppressed and JS alert / confirm /
    // prompt route through Java instead.  <input type=file> remains
    // OS-native on Windows -- WebView2 exposes no public hook for it.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1dialog_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // Windows has no offscreen engine; OffscreenWebView.create returns
    // null on Windows so this JNI bridge should never be reached.
    // Stub it for link-symmetry across all three native binaries.
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1download_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Store the Java WebViewDownloadCallback (JNI global ref) on the
    // Engine.  The DownloadStartingHandler::Invoke reads this field
    // when the WebView2 worker fires DownloadStarting; null means
    // "cancel".  Per Canvas Decision 2: Windows has no offscreen
    // engine, so only the heavyweight branch is wired.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->download_callback) {
        env->DeleteGlobalRef(e->download_callback);
        e->download_callback = nullptr;
    }
    if (cb) {
        e->download_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1download_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // No offscreen engine on Windows; stub for link-symmetry.
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1release_1native_1focus
  (JNIEnv *, jclass, jlong wv) {
    // When AWT moves its Java-side focus owner to a Swing component, Win32
    // keyboard focus does NOT automatically follow -- if the user had
    // clicked into the WebView2 child HWND first, that HWND keeps focus
    // and steals subsequent keystrokes from AWT.  Force focus back to
    // the AWT-owned parent HWND so the new Swing focus owner actually
    // receives keystrokes.
    //
    // Win32 focus is per-thread.  The WebView2 worker thread holds focus
    // on its own HWND; the AWT-owned parent HWND lives on the EDT.  We
    // need to share input state between the two before SetFocus can
    // transfer focus across them.
    //
    // Done synchronously on the calling thread (the EDT, since this
    // JNI is invoked from the global focus-owner PropertyChangeListener
    // on the EDT) -- not via dispatch_to_thread, because the WebView2
    // worker may be busy with rendering or JS and we want SetFocus to
    // happen BEFORE the user's next keystroke.  AttachThreadInput merges
    // the two threads' input queues, so SetFocus from the EDT will
    // transfer focus from the WebView2 HWND in the worker thread's
    // queue to the parent HWND in the EDT's queue.
    auto *e = (Engine *)wv;
    if (!e || !e->parent) return;
    // The JAWT-provided HWND on Windows is the heavyweight Canvas peer,
    // NOT the JFrame's HWND.  The URL JTextField is a lightweight Swing
    // component drawn inside the JFrame's HWND area; for Win32 keystrokes
    // to reach AWT and be routed to the JTextField, we need focus on the
    // top-level window, not on the Canvas (which is a sibling of the
    // toolbar containing the JTextField).
    HWND target = GetAncestor(e->parent, GA_ROOT);
    if (!target) target = e->parent;
    DWORD edt_tid = GetCurrentThreadId();
    DWORD wv2_tid = e->thread_id;
    bool debug = getenv("WEBVIEW_DEBUG_SHORTCUT") != nullptr;
    if (wv2_tid != 0 && wv2_tid != edt_tid) {
        if (!AttachThreadInput(edt_tid, wv2_tid, TRUE)) {
            if (debug) {
                WV_LOG("[webview-focus] AttachThreadInput(edt=%lu,wv2=%lu) "
                       "failed: %lu",
                       (unsigned long)edt_tid, (unsigned long)wv2_tid,
                       (unsigned long)GetLastError());
            }
            return;
        }
        HWND prev = GetFocus();
        HWND now = SetFocus(target);
        AttachThreadInput(edt_tid, wv2_tid, FALSE);
        if (debug) {
            WV_LOG("[webview-focus] SetFocus(target=%p canvas=%p) prev=%p "
                   "after=%p now=%p edt=%lu wv2=%lu",
                   (void *)target, (void *)e->parent,
                   (void *)prev, (void *)now, (void *)GetFocus(),
                   (unsigned long)edt_tid, (unsigned long)wv2_tid);
        }
    } else {
        HWND prev = GetFocus();
        HWND now = SetFocus(target);
        if (debug) {
            WV_LOG("[webview-focus] same-thread SetFocus(target=%p) "
                   "prev=%p after=%p now=%p",
                   (void *)target, (void *)prev, (void *)now,
                   (void *)GetFocus());
        }
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1attach_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    auto *e = (Engine *)wv;
    if (!e || !cb) return;
    // Windows attach is synchronous: by the time the Java factory calls
    // this entry, embed_win::create_engine has already returned a
    // populated Engine pointer, so the engine is fully in ATTACHED
    // state.  Fire onResolved(true, null) immediately; the Java handler
    // (EmbeddedWebView.AttachCallback.onResolved) marshals via
    // SwingUtilities.invokeLater so the WebViewAttachListener actually
    // fires on the next EDT tick regardless of which thread invoked
    // this JNI entry.
    jclass cls = env->GetObjectClass(cb);
    if (!cls) return;
    jmethodID m = env->GetMethodID(cls, "onResolved",
                                   "(ZLjava/lang/String;)V");
    if (m) {
        env->CallVoidMethod(cb, m, (jboolean)JNI_TRUE, (jstring)nullptr);
    }
    env->DeleteLocalRef(cls);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1execute_1editing_1command
  (JNIEnv *, jclass, jlong wv, jint cmdId) {
    auto *e = (Engine *)wv;
    if (!e) return;
    // WebView2 exposes no first-class editing-command IPC; route via
    // document.execCommand on the WebView2 worker thread.  This reliably
    // triggers the focused element's clipboard handlers and matches the
    // semantics we get from the Cocoa / GTK sides.  Fire-and-forget --
    // no callback, no result wait.
    const wchar_t *js = nullptr;
    switch (cmdId) {
        case 1: js = L"document.execCommand('cut')";       break;
        case 2: js = L"document.execCommand('copy')";      break;
        case 3: js = L"document.execCommand('paste')";     break;
        case 4: js = L"document.execCommand('selectAll')"; break;
        default: return;
    }
    std::wstring wjs = js;
    embed_win::dispatch_to_thread(e, [e, wjs] {
        if (e->webview) e->webview->ExecuteScript(wjs.c_str(), nullptr);
    });
}

} // extern "C"
