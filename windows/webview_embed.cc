/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Embedded-mode webview support for Swing/AWT on Windows (WebView2).
 *
 * Creates a child HWND of the JAWT-provided AWT Canvas HWND and hosts an
 * IWebView2WebView controller inside it.  The WebView2 environment is created
 * and operated on a dedicated worker thread per embedded WebView, which owns
 * the child HWND and pumps its own message queue.  Operations from Java
 * (navigate, eval, etc.) are marshaled to that thread with PostThreadMessage.
 *
 * NOTE: This file is compiled alongside windows/webview.cc and uses the same
 * WebView2 SDK headers (Microsoft.Web.WebView2.0.8.355) bundled in
 * windows/script/.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <atomic>
#include <condition_variable>
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

namespace embed_win {

using DispatchFn = std::function<void()>;

struct Binding {
    jobject fn;
    jclass cls;
    std::string name;
};

// Custom messages dispatched onto the WebView2 thread.
static const UINT WM_EMBED_DISPATCH = WM_APP + 1;
static const UINT WM_EMBED_QUIT = WM_APP + 2;

struct JawtLock {
    JAWT awt{};
    JAWT_DrawingSurface *ds = nullptr;
    JAWT_DrawingSurfaceInfo *dsi = nullptr;
    jint lock = 0;
    bool ok = false;

    JawtLock(JNIEnv *env, jobject component) {
        awt.version = JAWT_VERSION_9;
        if (!JAWT_GetAWT(env, &awt)) {
            awt.version = JAWT_VERSION_1_4;
            if (!JAWT_GetAWT(env, &awt)) return;
        }
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

struct Engine {
    HWND parent = nullptr;
    HWND child = nullptr;
    DWORD thread_id = 0;
    HANDLE thread = nullptr;
    IWebView2WebView *webview = nullptr;
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;
    bool debug = false;

    std::mutex bindings_mutex;
};

// Forward declaration of the WebView2 COM handler implemented below.
class CreateHandler;

class CreateHandler
    : public IWebView2CreateWebView2EnvironmentCompletedHandler,
      public IWebView2CreateWebViewCompletedHandler {
public:
    using cb_t = std::function<void(IWebView2WebView *)>;
    CreateHandler(HWND hwnd, cb_t cb) : m_window(hwnd), m_cb(std::move(cb)) {}
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID, LPVOID *) override {
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT,
                                     IWebView2Environment *env) override {
        env->CreateWebView(m_window, this);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT,
                                     IWebView2WebView *webview) override {
        webview->AddRef();
        m_cb(webview);
        return S_OK;
    }
private:
    HWND m_window;
    cb_t m_cb;
};

static void engine_on_message(Engine *e, const wchar_t *msg) {
    if (!msg) return;
    // Convert wide string to UTF-8.
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
        if (e && e->webview) {
            RECT r;
            GetClientRect(hwnd, &r);
            e->webview->put_Bounds(r);
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

static void engine_thread(Engine *e, HWND parent, int width, int height,
                          bool debug, std::atomic<bool> *ready,
                          std::atomic<bool> *ok) {
    ensure_class_registered();
    e->child = CreateWindowEx(0, "WebViewEmbedChild", "",
                              WS_CHILD | WS_VISIBLE,
                              0, 0, width, height, parent,
                              nullptr, GetModuleHandle(nullptr), nullptr);
    if (!e->child) {
        *ok = false; *ready = true;
        return;
    }
    SetWindowLongPtr(e->child, GWLP_USERDATA, (LONG_PTR)e);

    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    std::atomic_flag flag = ATOMIC_FLAG_INIT;
    flag.test_and_set();

    auto *handler = new CreateHandler(e->child, [&](IWebView2WebView *wv) {
        e->webview = wv;
        flag.clear();
    });
    HRESULT res = CreateWebView2EnvironmentWithDetails(nullptr, nullptr,
                                                       nullptr, handler);
    if (res != S_OK) {
        *ok = false; *ready = true;
        return;
    }

    // Pump messages until the controller is ready.
    MSG msg{};
    while (flag.test_and_set() && GetMessage(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    if (e->webview) {
        RECT r;
        GetClientRect(e->child, &r);
        e->webview->put_Bounds(r);
        // Wire up message bridge for window.external.invoke.
        // (Simplified: we rely on AddScriptToExecuteOnDocumentCreated and a
        //  WebMessageReceived equivalent.  See full SDK for details.)
    }

    *ok = (e->webview != nullptr); *ready = true;

    // Main message loop for this WebView's thread.  Operations are
    // dispatched here via PostThreadMessage(WM_EMBED_DISPATCH).
    while (GetMessage(&msg, nullptr, 0, 0)) {
        if (msg.message == WM_EMBED_DISPATCH) {
            auto *fn = (DispatchFn *)msg.lParam;
            (*fn)();
            delete fn;
            continue;
        }
        if (msg.message == WM_EMBED_QUIT) {
            break;
        }
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    if (e->webview) {
        e->webview->Release();
        e->webview = nullptr;
    }
    if (e->child) {
        DestroyWindow(e->child);
        e->child = nullptr;
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
    if (!lock.ok || !lock.dsi->platformInfo) return nullptr;
    auto *info = (JAWT_Win32DrawingSurfaceInfo *)lock.dsi->platformInfo;
    HWND parent = info->hwnd;
    if (!parent) return nullptr;
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
    // Spin briefly until the worker indicates readiness.
    while (!ready.load()) {
        Sleep(1);
    }
    if (!ok.load()) {
        // Tell the thread to quit (best effort).
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
    // The thread is detached; it tears down its own resources on quit.
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
  (JNIEnv *, jclass, jlong wv, jint x, jint y, jint w, jint h) {
    auto *e = (Engine *)wv;
    if (!e) return;
    embed_win::dispatch_to_thread(e, [e, x, y, w, h] {
        if (e->child) {
            SetWindowPos(e->child, nullptr, x, y, w, h,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        if (e->webview) {
            RECT r{0, 0, w, h};
            e->webview->put_Bounds(r);
        }
    });
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1pump
  (JNIEnv *, jclass, jlong /*wv*/, jint /*wait*/) {
    // The dedicated WebView2 worker thread handles its own pumping.
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

    // Install the bind shim.
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
