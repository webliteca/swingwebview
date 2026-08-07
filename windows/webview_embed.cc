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

    // JNI global ref to the registered WebViewPopupCallback, or nullptr —
    // Canvas 15.  Stored here on Windows; the follow-up Windows coverage
    // canvas wires ICoreWebView2::add_NewWindowRequested off this field
    // (put_NewWindow with a controller from the same environment) plus the
    // child's add_WindowCloseRequested so window.open opens a native window
    // linked to the opener.  Until then window.open stays blocked on Windows.
    jobject popup_callback = nullptr;

    // EventRegistrationToken for the NewWindowRequested handler registered in
    // the controller-ready callback (Canvas 17).  Not explicitly removed in
    // destroy_engine -- the controller / webview Release detaches it
    // transitively, like the other tokens.
    EventRegistrationToken new_window_token{};

    // The ICoreWebView2Environment this engine was created from, AddRef'd at
    // environment-ready and Release'd in destroy_engine (Canvas 17).  The
    // NewWindowRequested handler creates the child popup controller from THIS
    // environment so the popup is a linked view (window.opener / postMessage
    // work) -- a controller from a different environment is not linked.
    ICoreWebView2Environment *environment = nullptr;

    // Canvas 20 (popup adoption).  An ADOPTED popup engine reuses the OPENER
    // engine's WebView2 worker thread, because its ICoreWebView2Controller was
    // created on that thread and WebView2 objects are apartment-bound to their
    // creating thread (they cannot be moved to a fresh thread).  For such an
    // engine `thread_id` is the opener's worker thread and this flag is TRUE,
    // so destroy_engine must NOT post WM_EMBED_QUIT (that would tear down the
    // opener's message loop and the opener engine with it).  Instead it
    // synchronously Close()es the controller/webview on that shared thread.
    // ON-DEVICE-VALIDATION-REQUIRED: shared-worker-thread lifecycle (opener
    // outliving / being disposed before the adopted child).
    bool shared_thread = false;
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

// ---------------------------------------------------------------------------
// Popup (window.open) support — Canvas 17 (Windows / WebView2).
//
// window.open / target=_blank raises ICoreWebView2::NewWindowRequested.  Invoke
// runs on the WebView2 worker thread and must NOT block it, so we use the
// deferral pattern (like ScriptDialogHandler): GetDeferral, hop to Java on a
// short-lived worker for the allow/deny decision, then dispatch_to_thread back
// onto the WebView2 worker to create the child (via put_NewWindow with a
// controller built from the SAME environment, so window.opener / postMessage
// work) and Complete the deferral.  The engine owns the popup HWND; the child's
// WindowCloseRequested destroys it.
// ---------------------------------------------------------------------------

// A child popup's native state: an engine-owned top-level window hosting a
// linked child WebView2.  Freed in PopupWndProc's WM_DESTROY.
struct PopupWindow {
    HWND hwnd = nullptr;
    ICoreWebView2Controller *controller = nullptr;
    ICoreWebView2 *webview = nullptr;
    Engine *opener = nullptr;   // for jvm + popup_callback (notifications)
    jlong popup_id = 0;
    std::string url;
    EventRegistrationToken close_token{};
};

static LRESULT CALLBACK PopupWndProc(HWND hwnd, UINT msg, WPARAM wp,
                                     LPARAM lp) {
    PopupWindow *pw = (PopupWindow *)GetWindowLongPtr(hwnd, GWLP_USERDATA);
    switch (msg) {
    case WM_SIZE:
        if (pw && pw->controller) {
            RECT r;
            GetClientRect(hwnd, &r);
            pw->controller->put_Bounds(r);
        }
        return 0;
    case WM_DESTROY:
        if (pw) {
            if (pw->webview) { pw->webview->Release(); pw->webview = nullptr; }
            if (pw->controller) {
                pw->controller->Close();
                pw->controller->Release();
                pw->controller = nullptr;
            }
            SetWindowLongPtr(hwnd, GWLP_USERDATA, 0);
            delete pw;
        }
        return 0;
    default:
        return DefWindowProc(hwnd, msg, wp, lp);
    }
}

static ATOM ensure_popup_class_registered() {
    static ATOM atom = 0;
    if (atom != 0) return atom;
    WNDCLASSEX wc{};
    wc.cbSize = sizeof(wc);
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = "WebViewEmbedPopup";
    wc.lpfnWndProc = PopupWndProc;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    atom = RegisterClassEx(&wc);
    return atom;
}

// Synchronous allow/deny hop into Java (runs on the JNI worker thread the
// NewWindowRequested handler spins up).  Returns false on null callback,
// attach failure, or any exception (block-on-error default).
static bool fire_popup_requested_win(Engine *e, const char *url, bool gesture,
                                     int w, int h, const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return false;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return false;
        detach = true;
    }
    bool allow = false;
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupRequested",
            "(Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)Z");
        if (mid) {
            jboolean r = env->CallBooleanMethod(e->popup_callback, mid, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = JNI_FALSE;
            }
            allow = (r == JNI_TRUE);
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
    return allow;
}

// Fire onPopupOpened (fire-and-forget; the Java dispatcher marshals to the
// EDT via invokeLater).  Runs on the WebView2 worker thread.
static void fire_popup_opened_win(Engine *e, jlong popup_id, const char *url,
                                  bool gesture, int w, int h,
                                  const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupOpened",
            "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(e->popup_callback, mid, popup_id, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// Fire onPopupClosed.  Runs on the WebView2 worker thread.
static void fire_popup_closed_win(Engine *e, jlong popup_id, const char *url,
                                  const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupClosed",
            "(JLjava/lang/String;Ljava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(e->popup_callback, mid, popup_id, ju, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------
// Popup ADOPTION support — Canvas 20 (Windows / WebView2).
//
// Mirrors the shipped macOS (Canvas 18) + Linux (Canvas 19) adoption backends
// against the SAME platform-agnostic Java contract (no Java changes).  On an
// ADOPT disposition the NewWindowRequested handler builds the linked child
// controller from the opener's SAME environment against a HIDDEN holder HWND
// (never shown), returns the child to WebView2 (so it drives the original
// request -- POST verb+body, window.opener), retains it in
// g_win_retained_popups keyed by a jlong popupId, and fires onPopupAdoptable.
// Later webview_embed_adopt_popup reparents the retained controller into the
// adopting component's realized AWT HWND via put_ParentWindow; an unclaimed
// child is reclaimed by webview_embed_discard_popup.
//
// VALIDATED ON-DEVICE: the core adopt path (retain a POST/opener-linked child,
// reparent it into a WebViewComponent tab via put_ParentWindow, POST body +
// window.opener preserved) has been confirmed working on a real WebView2 stack.
// The remaining ON-DEVICE-VALIDATION markers below flag the teardown / reclaim
// / race edges that the happy-path confirmation does not fully exercise: the
// COM ref-counting on the controller/webview/environment handoffs, the
// apartment-thread (worker-thread) affinity of every ICoreWebView2* call, and
// the JNI global-ref lifecycle across retain -> adopt/discard remain prime
// candidates for on-device scrutiny.
// ---------------------------------------------------------------------------

// Synchronous disposition hop into Java (runs on the JNI worker thread the
// NewWindowRequested handler spins up), returning the PopupDisposition ordinal
// (0=BLOCK, 1=NATIVE_WINDOW, 2=ADOPT).  Returns 0 (BLOCK) on null callback,
// attach failure, or any exception -- the safe block-on-error default.
// Mirrors fire_popup_requested_win but calls onPopupDisposition (returns int).
static jint fire_popup_disposition_win(Engine *e, const char *url, bool gesture,
                                       int w, int h, const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return 0;  // BLOCK
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return 0;
        detach = true;
    }
    jint disposition = 0;
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupDisposition",
            "(Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)I");
        if (mid) {
            jint r = env->CallIntMethod(e->popup_callback, mid, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                r = 0;
            }
            disposition = r;
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
    return disposition;
}

// Fire onPopupAdoptable (fire-and-forget; the Java dispatcher marshals to the
// EDT via invokeLater).  Runs on the WebView2 worker thread.  Same signature
// shape as fire_popup_opened_win, different method name.
static void fire_popup_adoptable_win(Engine *e, jlong popup_id, const char *url,
                                     bool gesture, int w, int h,
                                     const char *page) {
    if (!e || !e->popup_callback || !e->jvm) return;
    JavaVM *jvm = e->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(url ? url : "");
    jstring jn = env->NewStringUTF("");
    jstring jp = env->NewStringUTF(page ? page : "");
    jclass cls = env->GetObjectClass(e->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupAdoptable",
            "(JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(e->popup_callback, mid, popup_id, ju, jn,
                gesture ? JNI_TRUE : JNI_FALSE, (jint)w, (jint)h, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jn) env->DeleteLocalRef(jn);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// A retained-but-unadopted popup child: a linked child WebView2 hosted in a
// HIDDEN holder HWND (never shown), held until webview_embed_adopt_popup
// reparents it or webview_embed_discard_popup reclaims it.  The controller /
// webview / environment are AddRef'd COM references owned by this struct; the
// popup/dialog callbacks are inherited JNI global refs owned by this struct.
struct RetainedPopup {
    ICoreWebView2Controller *controller = nullptr;  // AddRef'd
    ICoreWebView2 *webview = nullptr;               // AddRef'd
    ICoreWebView2Environment *environment = nullptr;// AddRef'd (opener's)
    HWND holder = nullptr;                           // hidden top-level HWND
    JavaVM *jvm = nullptr;
    jlong popup_id = 0;
    std::string url;
    std::string page;
    // Worker thread the controller was created on (== opener engine's
    // thread_id).  Every ICoreWebView2* call on controller/webview MUST run on
    // this apartment thread.
    DWORD worker_thread_id = 0;
    // Inherited callback global refs (new global refs on the opener's, so the
    // retained child keeps working for nested popups/dialogs until adoption
    // transfers ownership to the adopted engine).
    jobject popup_callback = nullptr;
    jobject dialog_callback = nullptr;
    // Retained-phase event handler tokens, removed before the adopted engine
    // registers its own handlers (analogous to Linux's
    // g_signal_handlers_disconnect_by_data).
    EventRegistrationToken new_window_token{};
    EventRegistrationToken script_dialog_token{};
    EventRegistrationToken close_token{};
};

// ON-DEVICE-VALIDATION-REQUIRED: the retained-popup registry is the Windows
// counterpart of the macOS g_retained_popups / Linux g_gtk_retained_popups.
static std::mutex g_win_retained_popups_mutex;
static std::map<jlong, RetainedPopup *> g_win_retained_popups;

// Post a fire-and-forget op onto a specific WebView2 worker thread (the
// apartment that owns a retained child's COM objects).  Falls back to running
// inline if the thread id is unknown (best effort; wrong apartment).
static void post_to_worker_thread(DWORD tid, DispatchFn fn) {
    if (!tid) { fn(); return; }
    auto *holder = new DispatchFn(std::move(fn));
    PostThreadMessage(tid, WM_EMBED_DISPATCH, 0, (LPARAM)holder);
}

// Hidden holder window class for retained popup children.  A plain
// DefWindowProc window that is NEVER shown; it exists only to give the child
// controller a valid parent HWND until adoption reparents it.
static ATOM ensure_popup_holder_class_registered() {
    static ATOM atom = 0;
    if (atom != 0) return atom;
    WNDCLASSEX wc{};
    wc.cbSize = sizeof(wc);
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = "WebViewEmbedPopupHolder";
    wc.lpfnWndProc = DefWindowProc;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    atom = RegisterClassEx(&wc);
    return atom;
}

// Fire onPopupClosed for a retained (never-shown) child, using the inherited
// jvm + popup_callback stored on the RetainedPopup.  Mirrors
// fire_popup_closed_win but sources jvm/callback from the shell rather than an
// Engine (the retained child has no opener-independent Engine yet).
static void fire_popup_closed_retained(RetainedPopup *rp) {
    if (!rp || !rp->popup_callback || !rp->jvm) return;
    JavaVM *jvm = rp->jvm;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    jstring ju = env->NewStringUTF(rp->url.c_str());
    jstring jp = env->NewStringUTF(rp->page.c_str());
    jclass cls = env->GetObjectClass(rp->popup_callback);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "onPopupClosed",
            "(JLjava/lang/String;Ljava/lang/String;)V");
        if (mid) {
            env->CallVoidMethod(rp->popup_callback, mid, rp->popup_id, ju, jp);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (ju) env->DeleteLocalRef(ju);
    if (jp) env->DeleteLocalRef(jp);
    if (detach) jvm->DetachCurrentThread();
}

// Tear down a retained-but-unadopted child.  MUST run on rp->worker_thread_id
// (the COM apartment owning controller/webview) -- callers dispatch it there.
// ON-DEVICE-VALIDATION-REQUIRED: Close/Release ordering + global-ref frees.
static void free_inherited_refs(JavaVM *jvm, jobject popup_cb,
                                jobject dialog_cb) {
    if (!jvm || (!popup_cb && !dialog_cb)) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK || !env)
            return;
        detach = true;
    }
    if (env) {
        if (popup_cb) env->DeleteGlobalRef(popup_cb);
        if (dialog_cb) env->DeleteGlobalRef(dialog_cb);
    }
    if (detach) jvm->DetachCurrentThread();
}

static void retained_popup_teardown(RetainedPopup *rp, bool fire_closed) {
    if (!rp) return;
    if (fire_closed) fire_popup_closed_retained(rp);
    if (rp->webview) { rp->webview->Release(); rp->webview = nullptr; }
    if (rp->controller) {
        rp->controller->Close();
        rp->controller->Release();
        rp->controller = nullptr;
    }
    if (rp->holder) { DestroyWindow(rp->holder); rp->holder = nullptr; }
    if (rp->environment) { rp->environment->Release(); rp->environment = nullptr; }
    free_inherited_refs(rp->jvm, rp->popup_callback, rp->dialog_callback);
    rp->popup_callback = nullptr;
    rp->dialog_callback = nullptr;
    delete rp;
}

// window.close() raised by a retained child BEFORE it is adopted -> discard it.
// Runs on the child's WebView2 worker thread (the opener's), so the COM
// teardown is on the correct apartment.  Claims under the mutex so an adoption
// racing the close wins-or-loses exactly once (whoever removes from the
// registry owns the teardown).
class RetainedPopupCloseHandler : public CallbackBase<
    ICoreWebView2WindowCloseRequestedEventHandler> {
public:
    explicit RetainedPopupCloseHandler(RetainedPopup *rp) : m_rp(rp) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2 *, IUnknown *) override {
        if (!m_rp) return S_OK;
        RetainedPopup *rp = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_win_retained_popups_mutex);
            auto it = g_win_retained_popups.find(m_rp->popup_id);
            if (it != g_win_retained_popups.end() && it->second == m_rp) {
                rp = it->second;
                g_win_retained_popups.erase(it);
            }
        }
        if (rp) retained_popup_teardown(rp, /*fire_closed=*/true);
        return S_OK;
    }
private:
    RetainedPopup *m_rp;
};

// window.close() from the popup (or the user closing the native window).
// Notify onPopupClosed, then DestroyWindow -> PopupWndProc WM_DESTROY frees
// the controller/webview and deletes the PopupWindow.
class PopupCloseHandler : public CallbackBase<
    ICoreWebView2WindowCloseRequestedEventHandler> {
public:
    explicit PopupCloseHandler(PopupWindow *pw) : m_pw(pw) {}
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2 *, IUnknown *) override {
        if (!m_pw) return S_OK;
        fire_popup_closed_win(m_pw->opener, m_pw->popup_id,
                              m_pw->url.c_str(), "");
        if (m_pw->hwnd) DestroyWindow(m_pw->hwnd);
        return S_OK;
    }
private:
    PopupWindow *m_pw;
};

// NewWindowRequested -> allow/deny via Java, then create the linked child in an
// engine-owned top-level window.  Deferral pattern; see the block comment.
class NewWindowRequestedHandler : public CallbackBase<
    ICoreWebView2NewWindowRequestedEventHandler> {
public:
    explicit NewWindowRequestedHandler(Engine *e) : m_engine(e) {}
    HRESULT STDMETHODCALLTYPE Invoke(
        ICoreWebView2 *,
        ICoreWebView2NewWindowRequestedEventArgs *args) override {
        if (!args || !m_engine) return S_OK;

        LPWSTR uri_w = nullptr;
        args->get_Uri(&uri_w);
        std::string uri = wide_to_utf8(uri_w);
        if (uri_w) CoTaskMemFree(uri_w);

        BOOL user_initiated = FALSE;
        args->get_IsUserInitiated(&user_initiated);

        int req_w = -1, req_h = -1;
        ICoreWebView2WindowFeatures *feat = nullptr;
        if (SUCCEEDED(args->get_WindowFeatures(&feat)) && feat) {
            BOOL has_size = FALSE;
            feat->get_HasSize(&has_size);
            if (has_size) {
                UINT32 fw = 0, fh = 0;
                feat->get_Width(&fw);
                feat->get_Height(&fh);
                if (fw > 0) req_w = (int)fw;
                if (fh > 0) req_h = (int)fh;
            }
            feat->Release();
        }

        // Opener document URL (best effort).
        std::string page;
        {
            LPWSTR src = nullptr;
            if (m_engine->webview &&
                SUCCEEDED(m_engine->webview->get_Source(&src)) && src) {
                page = wide_to_utf8(src);
            }
            if (src) CoTaskMemFree(src);
        }

        ICoreWebView2Deferral *deferral = nullptr;
        HRESULT hr = args->GetDeferral(&deferral);
        if (FAILED(hr) || !deferral) {
            WV_LOG("NewWindowRequested GetDeferral failed: HRESULT=0x%08lx",
                   (unsigned long)hr);
            return S_OK;  // WebView2 opens its own default window (fallback)
        }
        args->AddRef();

        Engine *e = m_engine;
        bool gesture = (user_initiated != FALSE);
        std::thread([e, args, deferral, uri, page, gesture, req_w, req_h] {
            // Canvas 20: the disposition switch replaces the Canvas-17 boolean
            // gate.  0=BLOCK, 1=NATIVE_WINDOW (the unchanged Canvas-17
            // engine-owned shown-window path), 2=ADOPT (retain a windowless
            // child for later adoption into a caller-provided component).
            jint disposition = fire_popup_disposition_win(
                e, uri.c_str(), gesture, req_w, req_h, page.c_str());
            dispatch_to_thread(e, [e, args, deferral, uri, page, gesture,
                                   req_w, req_h, disposition] {
                if (disposition == 0) {   // BLOCK
                    args->put_Handled(TRUE);   // block; window.open -> null
                    deferral->Complete();
                    deferral->Release();
                    args->Release();
                    return;
                }
                if (disposition == 2) {   // ADOPT — retain windowless child
                    // Need the opener's environment to build a LINKED child
                    // (window.opener / postMessage / POST replay).  Without it
                    // we cannot honour ADOPT -> block.
                    if (!e->environment) {
                        args->put_Handled(TRUE);
                        deferral->Complete();
                        deferral->Release();
                        args->Release();
                        return;
                    }
                    ensure_popup_holder_class_registered();
                    // Hidden holder top-level window -- NEVER ShowWindow.  The
                    // child renders offscreen here until adoption reparents it
                    // via put_ParentWindow.  (WebView2 requires a real parent
                    // HWND for the controller.)
                    HWND holder = CreateWindowEx(
                        0, "WebViewEmbedPopupHolder", "", WS_OVERLAPPEDWINDOW,
                        CW_USEDEFAULT, CW_USEDEFAULT,
                        req_w > 0 ? req_w : 500, req_h > 0 ? req_h : 650,
                        nullptr, nullptr, GetModuleHandle(nullptr), nullptr);
                    if (!holder) {
                        args->put_Handled(TRUE);
                        deferral->Complete();
                        deferral->Release();
                        args->Release();
                        return;
                    }
                    // Inherit the opener's popup/dialog callbacks as NEW global
                    // refs so the retained child keeps working (nested popups +
                    // dialogs) until adoption transfers them to the adopted
                    // engine.  ON-DEVICE-VALIDATION-REQUIRED: global-ref
                    // lifecycle across retain -> adopt/discard.
                    JavaVM *jvm = e->jvm;
                    jobject inh_popup = nullptr;
                    jobject inh_dialog = nullptr;
                    if (jvm) {
                        JNIEnv *jenv = nullptr;
                        bool jdetach = false;
                        if (jvm->GetEnv((void **)&jenv, JNI_VERSION_1_6)
                                != JNI_OK) {
                            jvm->AttachCurrentThread((void **)&jenv, nullptr);
                            jdetach = true;
                        }
                        if (jenv) {
                            if (e->popup_callback)
                                inh_popup = jenv->NewGlobalRef(e->popup_callback);
                            if (e->dialog_callback)
                                inh_dialog =
                                    jenv->NewGlobalRef(e->dialog_callback);
                        }
                        if (jdetach) jvm->DetachCurrentThread();
                    }

                    RetainedPopup *rp = new RetainedPopup();
                    rp->holder = holder;
                    rp->jvm = jvm;
                    rp->url = uri;
                    rp->page = page;
                    rp->popup_callback = inh_popup;
                    rp->dialog_callback = inh_dialog;
                    rp->worker_thread_id = e->thread_id;
                    rp->environment = e->environment;
                    rp->environment->AddRef();
                    rp->popup_id = (jlong)(LONG_PTR)rp;

                    auto *ctrl_handler = new ControllerHandler(
                        [e, args, deferral, rp, uri, page, gesture,
                         req_w, req_h]
                        (HRESULT r2, ICoreWebView2Controller *ctrl) {
                            if (FAILED(r2) || !ctrl) {
                                WV_LOG("adopt CreateController completion "
                                       "failed: 0x%08lx", (unsigned long)r2);
                                args->put_Handled(TRUE);
                                deferral->Complete();
                                deferral->Release();
                                args->Release();
                                // rp never entered the registry; tear the
                                // half-built shell down on THIS (worker) thread.
                                retained_popup_teardown(rp,
                                                        /*fire_closed=*/false);
                                return;
                            }
                            ctrl->AddRef();
                            rp->controller = ctrl;
                            ICoreWebView2 *child = nullptr;
                            ctrl->get_CoreWebView2(&child);
                            if (child) { child->AddRef(); rp->webview = child; }

                            // Return the LINKED child to WebView2 so it drives
                            // the original request (POST verb+body,
                            // window.opener) into it.
                            args->put_NewWindow(child);
                            args->put_Handled(TRUE);
                            deferral->Complete();

                            // Keep it HIDDEN: no ShowWindow, controller not
                            // visible.  It renders offscreen until adoption.
                            RECT rc;
                            GetClientRect(rp->holder, &rc);
                            ctrl->put_Bounds(rc);
                            ctrl->put_IsVisible(FALSE);

                            if (child) {
                                // Retained-phase handlers reuse the opener
                                // engine's callbacks (opener outlives the
                                // retained child until adoption).  Tokens are
                                // stored so adoption can remove them before the
                                // adopted engine wires its own handlers.
                                auto *nwh = new NewWindowRequestedHandler(e);
                                child->add_NewWindowRequested(
                                    nwh, &rp->new_window_token);
                                nwh->Release();

                                ICoreWebView2Settings *settings = nullptr;
                                if (SUCCEEDED(child->get_Settings(&settings)) &&
                                    settings) {
                                    settings
                                        ->put_AreDefaultScriptDialogsEnabled(
                                            FALSE);
                                    settings->Release();
                                }
                                auto *sdh = new ScriptDialogHandler(e);
                                child->add_ScriptDialogOpening(
                                    sdh, &rp->script_dialog_token);
                                sdh->Release();

                                // window.close() before adoption -> discard.
                                auto *rch = new RetainedPopupCloseHandler(rp);
                                child->add_WindowCloseRequested(
                                    rch, &rp->close_token);
                                rch->Release();
                            }

                            {
                                std::lock_guard<std::mutex> lk(
                                    g_win_retained_popups_mutex);
                                g_win_retained_popups[rp->popup_id] = rp;
                            }

                            // Notify Java: child retained + adoptable.  The Java
                            // dispatcher marshals popupAdoptable to the EDT.
                            fire_popup_adoptable_win(e, rp->popup_id,
                                                     uri.c_str(), gesture,
                                                     req_w, req_h, page.c_str());

                            deferral->Release();
                            args->Release();
                        });
                    HRESULT rc2 = e->environment->CreateCoreWebView2Controller(
                        rp->holder, ctrl_handler);
                    ctrl_handler->Release();
                    if (FAILED(rc2)) {
                        WV_LOG("adopt CreateCoreWebView2Controller call "
                               "failed: 0x%08lx", (unsigned long)rc2);
                        args->put_Handled(TRUE);
                        deferral->Complete();
                        deferral->Release();
                        args->Release();
                        retained_popup_teardown(rp, /*fire_closed=*/false);
                    }
                    return;
                }
                // disposition == 1: NATIVE_WINDOW -- the unchanged Canvas-17
                // engine-owned shown-window path below.
                int W = req_w > 0 ? req_w : 500;
                int H = req_h > 0 ? req_h : 650;
                ensure_popup_class_registered();
                HWND popup = CreateWindowEx(
                    0, "WebViewEmbedPopup", "Popup", WS_OVERLAPPEDWINDOW,
                    CW_USEDEFAULT, CW_USEDEFAULT, W, H,
                    nullptr, nullptr, GetModuleHandle(nullptr), nullptr);
                if (!popup || !e->environment) {
                    if (popup) DestroyWindow(popup);
                    args->put_Handled(TRUE);   // blocked
                    deferral->Complete();
                    deferral->Release();
                    args->Release();
                    return;
                }
                PopupWindow *pw = new PopupWindow();
                pw->hwnd = popup;
                pw->opener = e;
                pw->popup_id = (jlong)(LONG_PTR)pw;
                pw->url = uri;
                SetWindowLongPtr(popup, GWLP_USERDATA, (LONG_PTR)pw);

                auto *ctrl_handler = new ControllerHandler(
                    [e, args, deferral, pw, uri, page, gesture, W, H]
                    (HRESULT r2, ICoreWebView2Controller *ctrl) {
                        if (FAILED(r2) || !ctrl) {
                            WV_LOG("popup CreateController completion failed: "
                                   "0x%08lx", (unsigned long)r2);
                            args->put_Handled(TRUE);   // blocked
                            deferral->Complete();
                            deferral->Release();
                            args->Release();
                            DestroyWindow(pw->hwnd);   // WM_DESTROY frees pw
                            return;
                        }
                        ctrl->AddRef();
                        pw->controller = ctrl;
                        ICoreWebView2 *child = nullptr;
                        ctrl->get_CoreWebView2(&child);
                        if (child) { child->AddRef(); pw->webview = child; }

                        args->put_NewWindow(child);   // LINKED to opener
                        args->put_Handled(TRUE);
                        deferral->Complete();

                        RECT rc;
                        GetClientRect(pw->hwnd, &rc);
                        ctrl->put_Bounds(rc);
                        ctrl->put_IsVisible(TRUE);
                        ShowWindow(pw->hwnd, SW_SHOW);

                        if (child) {
                            // Nested popups + dialogs from inside the popup,
                            // reusing the opener's callbacks.
                            EventRegistrationToken nw_t{};
                            auto *nwh = new NewWindowRequestedHandler(e);
                            child->add_NewWindowRequested(nwh, &nw_t);
                            nwh->Release();

                            ICoreWebView2Settings *settings = nullptr;
                            if (SUCCEEDED(child->get_Settings(&settings)) &&
                                settings) {
                                settings->put_AreDefaultScriptDialogsEnabled(
                                    FALSE);
                                settings->Release();
                            }
                            EventRegistrationToken sd_t{};
                            auto *sdh = new ScriptDialogHandler(e);
                            child->add_ScriptDialogOpening(sdh, &sd_t);
                            sdh->Release();

                            auto *pch = new PopupCloseHandler(pw);
                            child->add_WindowCloseRequested(pch,
                                                            &pw->close_token);
                            pch->Release();
                        }

                        fire_popup_opened_win(e, pw->popup_id, uri.c_str(),
                                              gesture, W, H, page.c_str());

                        deferral->Release();
                        args->Release();
                    });
                HRESULT rc2 = e->environment->CreateCoreWebView2Controller(
                    pw->hwnd, ctrl_handler);
                ctrl_handler->Release();
                if (FAILED(rc2)) {
                    WV_LOG("popup CreateCoreWebView2Controller call failed: "
                           "0x%08lx", (unsigned long)rc2);
                    args->put_Handled(TRUE);
                    deferral->Complete();
                    deferral->Release();
                    args->Release();
                    DestroyWindow(pw->hwnd);   // WM_DESTROY frees pw
                }
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
            // Keep the environment alive so NewWindowRequested can build the
            // child popup controller from it (linked view) -- Canvas 17.
            e->environment = env;
            env->AddRef();
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

                    // window.open / target=_blank -> native popup window
                    // linked to the opener (Canvas 17).
                    auto *nwh = new NewWindowRequestedHandler(e);
                    e->webview->add_NewWindowRequested(
                        nwh, &e->new_window_token);
                    nwh->Release();

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
    if (e->shared_thread) {
        // Canvas 20: an ADOPTED engine shares the opener's WebView2 worker
        // thread.  Posting WM_EMBED_QUIT here would exit the opener's message
        // loop and tear the opener down too.  Instead synchronously Close() the
        // controller/webview and destroy our child HWND on that shared thread,
        // then fall through to free the global refs + delete e.  The opener's
        // loop keeps running.  ON-DEVICE-VALIDATION-REQUIRED: apartment-correct
        // Close/Release on the shared worker thread.
        std::atomic<bool> done{false};
        dispatch_to_thread(e, [e, &done] {
            if (e->controller) {
                e->controller->Close();
                e->controller->Release();
                e->controller = nullptr;
            }
            if (e->webview) {
                e->webview->Release();
                e->webview = nullptr;
            }
            if (e->child) {
                DestroyWindow(e->child);
                e->child = nullptr;
            }
            done.store(true);
        });
        while (!done.load()) Sleep(1);
    } else if (e->thread_id) {
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
    // Symmetric cleanup for the popup callback global ref (Canvas 17).  The
    // NewWindowRequested handler fires off this field, so clear it before the
    // worker thread tears down.
    if (e->popup_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Release the environment ref taken at environment-ready (Canvas 17).
    if (e->environment) {
        e->environment->Release();
        e->environment = nullptr;
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

// Build a normal embedded Engine from a claimed RetainedPopup, reusing its
// child controller/webview (apartment-bound to the opener's worker thread).
// The retained COM references + inherited global refs are TRANSFERRED into the
// returned Engine (rp is a plain shell freed by the dispatched op -- its raw
// pointers are copied, not re-released, so no double-free).  Returns the Engine
// (never null here; the caller already validated the claim).  The reparent +
// handler registration happen asynchronously on the shared worker thread; the
// Engine is returned immediately with a valid controller/webview so Java gets a
// usable peer handle.  ON-DEVICE-VALIDATION-REQUIRED: put_ParentWindow reparent
// of a live (mid-navigation) controller, and the retained->adopted handler
// handoff.
static Engine *adopt_retained_popup(JNIEnv *env, HWND parent, RetainedPopup *rp,
                                    int debug) {
    Engine *e = new Engine();
    e->parent = parent;
    e->debug = debug != 0;
    e->jvm = rp->jvm;
    e->shared_thread = true;                 // reuse opener's worker thread
    e->thread_id = rp->worker_thread_id;
    e->thread = nullptr;
    e->controller = rp->controller;          // transferred (already AddRef'd)
    e->webview = rp->webview;                // transferred (already AddRef'd)
    e->environment = rp->environment;        // transferred (already AddRef'd)
    e->popup_callback = rp->popup_callback;  // inherited global refs transferred
    e->dialog_callback = rp->dialog_callback;

    // Everything below touches WebView2 objects, so it MUST run on the
    // controller's apartment thread (the opener's worker thread).
    post_to_worker_thread(rp->worker_thread_id, [e, parent, rp] {
        ICoreWebView2 *child = e->webview;
        // Remove the retained-phase handlers (bound to the opener engine)
        // before wiring the adopted engine's own handlers, so exactly one
        // handler set survives (analogue of Linux disconnect_by_data).
        if (child) {
            child->remove_NewWindowRequested(rp->new_window_token);
            child->remove_ScriptDialogOpening(rp->script_dialog_token);
            child->remove_WindowCloseRequested(rp->close_token);
        }

        // Create the standard child HWND under the adopting AWT canvas HWND,
        // exactly like a freshly-created engine, so WM_SIZE / WM_PARENTNOTIFY
        // (the click hook) behave identically.
        ensure_class_registered();
        RECT pr;
        GetClientRect(parent, &pr);
        int cw = pr.right - pr.left;
        int chh = pr.bottom - pr.top;
        if (cw <= 0) cw = 1;
        if (chh <= 0) chh = 1;
        e->child = CreateWindowEx(0, "WebViewEmbedChild", "",
                                  WS_CHILD | WS_VISIBLE, 0, 0, cw, chh,
                                  parent, nullptr, GetModuleHandle(nullptr),
                                  nullptr);
        if (e->child) SetWindowLongPtr(e->child, GWLP_USERDATA, (LONG_PTR)e);

        // Reparent the retained controller into the adopting surface and make
        // it visible.
        if (e->controller) {
            e->controller->put_ParentWindow(e->child ? e->child : parent);
            RECT rc;
            GetClientRect(e->child ? e->child : parent, &rc);
            e->controller->put_Bounds(rc);
            e->controller->put_IsVisible(TRUE);

            auto *gh = new FocusHandler(e, true);
            e->controller->add_GotFocus(gh, &e->got_focus_token);
            gh->Release();
            auto *lh = new FocusHandler(e, false);
            e->controller->add_LostFocus(lh, &e->lost_focus_token);
            lh->Release();
        }

        if (child) {
            ICoreWebView2Settings *settings = nullptr;
            if (SUCCEEDED(child->get_Settings(&settings)) && settings) {
                settings->put_AreDevToolsEnabled(e->debug ? TRUE : FALSE);
                settings->put_AreDefaultContextMenusEnabled(TRUE);
                settings->put_AreDefaultScriptDialogsEnabled(FALSE);
                settings->Release();
            }
            child->AddScriptToExecuteOnDocumentCreated(
                L"window.external = { invoke: s => "
                L"window.chrome.webview.postMessage(s) };",
                nullptr);

            auto *mh = new MsgHandler(e);
            child->add_WebMessageReceived(mh, &e->message_token);
            mh->Release();

            auto *sdh = new ScriptDialogHandler(e);
            child->add_ScriptDialogOpening(sdh, &e->script_dialog_token);
            sdh->Release();

            auto *nwh = new NewWindowRequestedHandler(e);
            child->add_NewWindowRequested(nwh, &e->new_window_token);
            nwh->Release();
        }

        // The hidden holder top-level window is no longer needed once the
        // controller has been reparented onto the adopting child HWND above.
        // Destroy it on this (its creating) worker thread to avoid leaking a
        // top-level HWND per adopted popup.  retained_popup_teardown does the
        // same on the discard/close paths.
        if (rp->holder) { DestroyWindow(rp->holder); rp->holder = nullptr; }

        // Free the retained shell.  Its COM references + global refs were
        // TRANSFERRED to e (copied, not re-AddRef'd), so DO NOT release them
        // here; just free the struct.
        delete rp;
    });

    return e;
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

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1popup_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    // Canvas 15 ships the callback storage on Windows; the follow-up
    // Windows coverage canvas wires ICoreWebView2::add_NewWindowRequested
    // off this field.  Until then storing the ref is a harmless no-op and
    // window.open stays blocked on Windows.
    auto *e = (Engine *)wv;
    if (!e) return;
    if (e->popup_callback) {
        env->DeleteGlobalRef(e->popup_callback);
        e->popup_callback = nullptr;
    }
    if (cb) {
        e->popup_callback = env->NewGlobalRef(cb);
    }
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1popup_1callback
  (JNIEnv *, jclass, jlong, jobject) {
    // Windows has no offscreen engine; stub for link-symmetry across all
    // three native binaries.
}

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1adopt_1popup
  (JNIEnv *, jclass, jint, jint, jlong, jint) {
    // Windows has no offscreen engine (webview_offscreen_create returns 0), so
    // adoption into a lightweight component is never triggered here; return 0.
    // Popup adoption on Windows uses the heavyweight webview_embed_adopt_popup
    // path (Canvas 20).  Stub for link-symmetry (Canvas 19 offscreen bridge).
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1discard_1popup
  (JNIEnv *, jclass, jlong, jlong) {
    // Windows has no offscreen engine; stub for link-symmetry.
}

// Custom User-Agent — Canvas 21.  ua == null / empty restores the engine
// default.  Applied on the WebView2 UI thread via ICoreWebView2Settings2;
// no-op on an old runtime lacking the _2 settings interface.  Takes effect on
// the next navigation.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1user_1agent
  (JNIEnv *env, jclass, jlong wv, jstring ua) {
    auto *e = (Engine *)wv;
    if (!e) return;
    std::wstring w;
    if (ua) {
        const char *s = env->GetStringUTFChars(ua, nullptr);
        w = embed_win::utf8_to_wide(s);
        env->ReleaseStringUTFChars(ua, s);
    }
    embed_win::dispatch_to_thread(e, [e, w] {
        if (!e->webview) return;
        ICoreWebView2Settings *settings = nullptr;
        if (SUCCEEDED(e->webview->get_Settings(&settings)) && settings) {
            ICoreWebView2Settings2 *settings2 = nullptr;
            if (SUCCEEDED(settings->QueryInterface(
                    __uuidof(ICoreWebView2Settings2),
                    reinterpret_cast<void **>(&settings2))) && settings2) {
                settings2->put_UserAgent(w.c_str()); // empty -> default
                settings2->Release();
            }
            settings->Release();
        }
    });
}

// Clear the embedded WebView's HTTP resource cache — Canvas 22.  Windows
// coverage is deferred to a follow-up canvas: the ClearBrowsingData purge
// needs an interface (ICoreWebView2Profile2::ClearBrowsingDataAsync) that the
// pinned WebView2 SDK header does not expose, so this is a documented no-op for
// now (mirrors the per-platform staging of Canvas 18/19/20 and the offscreen
// stub below).  The JNI symbol is retained so EmbeddedWebView.clearCache()
// links (no UnsatisfiedLinkError); it simply does nothing on Windows.  Never
// throws via JNI.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1clear_1cache
  (JNIEnv *, jclass, jlong wv) {
    (void)wv;  // Windows clearCache: deferred (see comment above).
}

// Offscreen cache purge — Windows has no offscreen engine; stub for
// link-symmetry with the JNI declaration.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1clear_1cache
  (JNIEnv *, jclass, jlong) {
}

// Adopt a retained popup child (Canvas 20) into `parent`'s realized AWT HWND.
// Resolves the parent HWND via the SAME JAWT path as webview_embed_create,
// claims the RetainedPopup (adopt-once under the mutex; 0 -> Java throws
// IllegalStateException), and reuses its controller/webview.
JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1adopt_1popup
  (JNIEnv *env, jclass, jobject component, jlong popupId, jint debug) {
    // Resolve the parent AWT HWND (same mechanism as create_engine).
    HWND parent = nullptr;
    {
        JawtLock lock(env, component);
        if (!lock.ok || !lock.dsi->platformInfo) {
            WV_LOG("adopt_popup: JAWT lock failed");
            return 0;
        }
        auto *info = (JAWT_Win32DrawingSurfaceInfo *)lock.dsi->platformInfo;
        parent = info->hwnd;
    }
    if (!parent) {
        WV_LOG("adopt_popup: JAWT platform info had no HWND");
        return 0;
    }
    // Claim the retained popup (adopt-once).  0 for unknown/consumed id ->
    // EmbeddedWebView.adopt turns it into IllegalStateException.
    embed_win::RetainedPopup *rp = nullptr;
    {
        std::lock_guard<std::mutex> lk(embed_win::g_win_retained_popups_mutex);
        auto it = embed_win::g_win_retained_popups.find(popupId);
        if (it == embed_win::g_win_retained_popups.end()) return 0;
        rp = it->second;
        embed_win::g_win_retained_popups.erase(it);
    }
    Engine *e = embed_win::adopt_retained_popup(env, parent, rp, debug);
    return (jlong)e;
}

// Discard a retained-but-unadopted popup child (Canvas 20 reclaim path).  `wv`
// (any live embed peer) is unused on Windows -- the retained child is located
// by popupId alone.  Unknown id is a silent no-op.  The COM teardown is
// dispatched onto the child's owning worker thread.
JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1discard_1popup
  (JNIEnv *, jclass, jlong /*wv*/, jlong popupId) {
    embed_win::RetainedPopup *rp = nullptr;
    {
        std::lock_guard<std::mutex> lk(embed_win::g_win_retained_popups_mutex);
        auto it = embed_win::g_win_retained_popups.find(popupId);
        if (it == embed_win::g_win_retained_popups.end()) return;
        rp = it->second;
        embed_win::g_win_retained_popups.erase(it);
    }
    // Tear down on the apartment thread that owns the controller/webview.
    DWORD tid = rp->worker_thread_id;
    embed_win::post_to_worker_thread(tid, [rp] {
        embed_win::retained_popup_teardown(rp, /*fire_closed=*/true);
    });
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
