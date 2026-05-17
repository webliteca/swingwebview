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
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;
    bool debug = false;
    std::mutex bindings_mutex;
};

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
  (JNIEnv *, jclass, jlong, jobject) {
    // No-op on Windows: the macOS-specific WKWebView swizzle has no
    // counterpart on WebView2.  The Java side still calls this so the
    // JNI symbol must exist for System.loadLibrary to resolve.
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
