/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Embedded-mode webview support for Swing/AWT (Linux GTK and macOS Cocoa).
 *
 * On Linux this reparents a top-level GTK window underneath the X11 window of
 * a JAWT-managed AWT Canvas using XReparentWindow.  The GTK main loop is
 * driven on a dedicated pump thread; all GTK and WebKitGTK operations are
 * marshaled onto that thread.
 *
 * On macOS this creates a WKWebView, sets its layer onto the JAWT
 * SurfaceLayers of the AWT Canvas, and lets AppKit's own run loop (which is
 * already pumping inside the JVM) deliver events.
 */

#include "ca_weblite_webview_WebViewNative.h"

#include <jawt.h>
#include <jawt_md.h>

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#ifdef WEBVIEW_GTK
#include <X11/Xlib.h>
#include <gtk/gtk.h>
#include <gdk/gdk.h>
#include <gdk/gdkx.h>
#include <webkit2/webkit2.h>
#endif

#ifdef WEBVIEW_COCOA
#include <CoreGraphics/CoreGraphics.h>
#include <dispatch/dispatch.h>
#include <objc/objc-runtime.h>
#include <objc/runtime.h>
#endif

namespace embed {

// ---------------------------------------------------------------------------
// JAWT helpers
// ---------------------------------------------------------------------------

struct JawtLock {
    JAWT awt;
    JAWT_DrawingSurface *ds = nullptr;
    JAWT_DrawingSurfaceInfo *dsi = nullptr;
    jint lock = 0;
    bool ok = false;

    JawtLock(JNIEnv *env, jobject component) {
        // Pick the newest JAWT version the JDK headers know about, then
        // progressively fall back.  JAWT_VERSION_9 is missing in JDK 8.
#if defined(JAWT_VERSION_9)
        awt.version = JAWT_VERSION_9;
#elif defined(JAWT_VERSION_1_7)
        awt.version = JAWT_VERSION_1_7;
#else
        awt.version = JAWT_VERSION_1_4;
#endif
#if defined(WEBVIEW_COCOA) && defined(JAWT_MACOSX_USE_CALAYER)
        // On macOS, request the CALayer-based surface so we can attach a
        // WKWebView layer through the JAWT_SurfaceLayers protocol.
        awt.version |= JAWT_MACOSX_USE_CALAYER;
#endif
        if (!JAWT_GetAWT(env, &awt)) {
#if defined(JAWT_VERSION_1_7)
            awt.version = JAWT_VERSION_1_7;
#if defined(WEBVIEW_COCOA) && defined(JAWT_MACOSX_USE_CALAYER)
            awt.version |= JAWT_MACOSX_USE_CALAYER;
#endif
            if (!JAWT_GetAWT(env, &awt))
#endif
            {
                awt.version = JAWT_VERSION_1_4;
                if (!JAWT_GetAWT(env, &awt)) {
                    fprintf(stderr,
                        "[webview-embed] JAWT_GetAWT failed for all "
                        "version masks (tried 0x%x then 0x%x then 0x%x).\n",
#if defined(JAWT_VERSION_9)
                        (unsigned)(JAWT_VERSION_9
#elif defined(JAWT_VERSION_1_7)
                        (unsigned)(JAWT_VERSION_1_7
#else
                        (unsigned)(JAWT_VERSION_1_4
#endif
#if defined(JAWT_MACOSX_USE_CALAYER)
                        | JAWT_MACOSX_USE_CALAYER
#endif
                        ),
                        (unsigned)JAWT_VERSION_1_7,
                        (unsigned)JAWT_VERSION_1_4);
                    return;
                }
            }
        }
        ds = awt.GetDrawingSurface(env, component);
        if (ds == nullptr) {
            fprintf(stderr,
                "[webview-embed] JAWT GetDrawingSurface returned NULL "
                "(component is not displayable or not a heavyweight peer).\n");
            return;
        }
        lock = ds->Lock(ds);
        if (lock & JAWT_LOCK_ERROR) {
            fprintf(stderr,
                "[webview-embed] JAWT Lock returned JAWT_LOCK_ERROR (0x%x).\n",
                (unsigned)lock);
            awt.FreeDrawingSurface(ds);
            ds = nullptr;
            return;
        }
        dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi == nullptr) {
            fprintf(stderr,
                "[webview-embed] JAWT GetDrawingSurfaceInfo returned NULL.\n");
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

// ---------------------------------------------------------------------------
// Cross-platform engine handle
// ---------------------------------------------------------------------------

struct Engine;

// Java callback bridge.  Owned by the engine; ref-counted in JNI globals.
struct Binding {
    jobject fn;             // global ref to WebViewNativeCallback
    jclass cls;             // global ref to its class
    std::string name;
};

using DispatchFn = std::function<void()>;

#ifdef WEBVIEW_GTK
// =========================================================================
// Linux / GTK / X11
// =========================================================================

class GtkPump {
public:
    static GtkPump &instance() {
        static GtkPump p;
        return p;
    }

    // Run f synchronously on the GTK thread, blocking until it completes.
    template <typename F> void run_sync(F &&f) {
        ensure_started();
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
        auto holder = new std::function<void()>([&]() {
            f();
            {
                std::lock_guard<std::mutex> lk(m);
                done = true;
            }
            cv.notify_all();
        });
        g_idle_add_full(G_PRIORITY_HIGH_IDLE,
                        (GSourceFunc)+[](void *data) -> int {
                          auto *cb = static_cast<std::function<void()> *>(data);
                          (*cb)();
                          return G_SOURCE_REMOVE;
                        },
                        holder,
                        +[](void *data) {
                          delete static_cast<std::function<void()> *>(data);
                        });
        std::unique_lock<std::mutex> lk(m);
        cv.wait(lk, [&] { return done; });
    }

    // Run f asynchronously on the GTK thread.
    void run_async(DispatchFn f) {
        ensure_started();
        auto holder = new DispatchFn(std::move(f));
        g_idle_add_full(G_PRIORITY_HIGH_IDLE,
                        (GSourceFunc)+[](void *data) -> int {
                          auto *cb = static_cast<DispatchFn *>(data);
                          (*cb)();
                          return G_SOURCE_REMOVE;
                        },
                        holder,
                        +[](void *data) {
                          delete static_cast<DispatchFn *>(data);
                        });
    }

private:
    GtkPump() = default;

    std::mutex start_mutex;
    std::atomic<bool> started{false};
    std::thread thread;

    void ensure_started() {
        if (started.load()) return;
        std::lock_guard<std::mutex> lk(start_mutex);
        if (started.load()) return;
        std::mutex ready_m;
        std::condition_variable ready_cv;
        bool ready = false;
        thread = std::thread([&] {
            // X11 must be told we're going to use it from multiple threads.
            XInitThreads();
            int argc = 0;
            char **argv = nullptr;
            gtk_init(&argc, &argv);
            {
                std::lock_guard<std::mutex> lk2(ready_m);
                ready = true;
            }
            ready_cv.notify_all();
            gtk_main();
        });
        thread.detach();
        std::unique_lock<std::mutex> lk2(ready_m);
        ready_cv.wait(lk2, [&] { return ready; });
        started.store(true);
    }
};

struct Engine {
    Window parent_xid = 0;
    Display *parent_display = nullptr;

    GtkWidget *window = nullptr;
    GtkWidget *web = nullptr;        // WebKitWebView*
    WebKitUserContentManager *manager = nullptr;

    bool debug = false;

    // Bindings: name -> Binding*
    std::map<std::string, Binding *> bindings;

    JavaVM *jvm = nullptr;

    Engine() {}
    ~Engine() {}
};

static void engine_on_message(Engine *e, const char *msg) {
    if (msg == nullptr) return;
    // The message is JSON: {name, seq, args}.  We mirror the bind format used
    // by webview::webview; for the embed API we just forward the raw payload
    // to the named callback (which is what WebViewNativeCallback does).
    // Simple JSON parse for "name":
    std::string s(msg);
    auto pos = s.find("\"name\":\"");
    if (pos == std::string::npos) return;
    auto start = pos + 8;
    auto end = s.find('"', start);
    if (end == std::string::npos) return;
    std::string name = s.substr(start, end - start);
    auto it = e->bindings.find(name);
    if (it == e->bindings.end()) return;
    Binding *b = it->second;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        e->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    jmethodID mid = env->GetMethodID(b->cls, "invoke", "(Ljava/lang/String;J)V");
    if (mid) {
        jstring js = env->NewStringUTF(msg);
        env->CallVoidMethod(b->fn, mid, js, (jlong)e);
        env->DeleteLocalRef(js);
    }
    if (detach) e->jvm->DetachCurrentThread();
}

static Engine *gtk_create_engine(JNIEnv *env, jobject component, jint debug) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) return nullptr;
    auto *info = (JAWT_X11DrawingSurfaceInfo *)lock.dsi->platformInfo;
    if (info->drawable == 0) return nullptr;
    auto *e = new Engine();
    env->GetJavaVM(&e->jvm);
    e->parent_xid = (Window)info->drawable;
    e->parent_display = info->display;
    e->debug = debug != 0;

    bool ok = false;
    GtkPump::instance().run_sync([&] {
        e->window = gtk_window_new(GTK_WINDOW_POPUP);
        gtk_window_set_decorated(GTK_WINDOW(e->window), FALSE);
        gtk_widget_set_app_paintable(e->window, TRUE);
        gtk_widget_realize(e->window);

        GdkWindow *gdkw = gtk_widget_get_window(e->window);
        if (!gdkw) { return; }

        Window child = GDK_WINDOW_XID(gdkw);
        Display *gdkd = GDK_WINDOW_XDISPLAY(gdkw);

        // Reparent under the AWT canvas.  We use the GDK display since AWT
        // and GTK may have different Display* handles for the same X server.
        XReparentWindow(gdkd, child, e->parent_xid, 0, 0);
        XSync(gdkd, False);

        e->web = webkit_web_view_new();
        e->manager =
            webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(e->web));

        // Wire up the "external" message handler.
        g_signal_connect(
            e->manager, "script-message-received::external",
            G_CALLBACK(+[](WebKitUserContentManager *m,
                           WebKitJavascriptResult *r, gpointer arg) {
                auto *eng = static_cast<Engine *>(arg);
                JSCValue *v = webkit_javascript_result_get_js_value(r);
                char *s = jsc_value_to_string(v);
                engine_on_message(eng, s);
                g_free(s);
            }),
            e);
        webkit_user_content_manager_register_script_message_handler(
            e->manager, "external");
        // Install the same external.invoke shim that the existing engine uses.
        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(
                "window.external={invoke:function(s){"
                "window.webkit.messageHandlers.external.postMessage(s);}};",
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL));

        if (e->debug) {
            WebKitSettings *s =
                webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
            webkit_settings_set_enable_developer_extras(s, TRUE);
            webkit_settings_set_enable_write_console_messages_to_stdout(s, TRUE);
        }

        gtk_container_add(GTK_CONTAINER(e->window), e->web);
        gtk_widget_show_all(e->window);
        ok = true;
    });
    if (!ok) {
        delete e;
        return nullptr;
    }
    return e;
}

static void gtk_destroy_engine(Engine *e) {
    if (!e) return;
    GtkPump::instance().run_sync([&] {
        if (e->window) {
            gtk_widget_destroy(e->window);
            e->window = nullptr;
            e->web = nullptr;
        }
    });
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
    delete e;
}

static void gtk_set_bounds(Engine *e, int x, int y, int width, int height) {
    GtkPump::instance().run_async([=] {
        if (!e->window) return;
        gtk_window_resize(GTK_WINDOW(e->window), width > 0 ? width : 1,
                          height > 0 ? height : 1);
        GdkWindow *gdkw = gtk_widget_get_window(e->window);
        if (gdkw) {
            gdk_window_move_resize(gdkw, x, y, width > 0 ? width : 1,
                                   height > 0 ? height : 1);
        }
    });
}

static void gtk_navigate(Engine *e, std::string url) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(e->web), url.c_str());
    });
}

static void gtk_init_script(Engine *e, std::string js) {
    GtkPump::instance().run_async([=] {
        if (!e->manager) return;
        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(js.c_str(),
                                   WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                                   WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                                   NULL, NULL));
    });
}

static void gtk_eval(Engine *e, std::string js) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        webkit_web_view_run_javascript(WEBKIT_WEB_VIEW(e->web), js.c_str(),
                                       NULL, NULL, NULL);
    });
}

static void gtk_bind(Engine *e, Binding *b) {
    e->bindings[b->name] = b;
}

#endif // WEBVIEW_GTK

#ifdef WEBVIEW_COCOA
// =========================================================================
// macOS / Cocoa / WKWebView
// =========================================================================

static id objc_cls(const char *n) { return (id)objc_getClass(n); }
static SEL sel(const char *n) { return sel_registerName(n); }

// Modern macOS SDKs (Xcode 15+) declare objc_msgSend with no parameters even
// when OBJC_OLD_DISPATCH_PROTOTYPES is defined, and on ARM64 the variadic
// calling convention does not match the ABI used for struct-by-value
// arguments anyway.  Every call therefore has to go through a typed
// function-pointer cast.  msg<>() centralises that pattern.
template <typename Ret = id, typename... Args>
static inline Ret msg(id receiver, SEL selector, Args... args) {
    using Fn = Ret (*)(id, SEL, Args...);
    return reinterpret_cast<Fn>(objc_msgSend)(receiver, selector, args...);
}

static id ns_str(const char *s) {
    return msg(objc_cls("NSString"), sel("stringWithUTF8String:"), s);
}

struct Engine {
    id webview = nullptr;   // WKWebView
    id manager = nullptr;   // WKUserContentController
    id config = nullptr;
    bool debug = false;
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;

    // Reference back into the JAWT-provided SurfaceLayers; we need it on
    // destroy to clear the layer.
    id surface_layers = nullptr;
};

static void cocoa_run_on_main(std::function<void()> f) {
    // If we're already on the AppKit main thread, just run f inline; otherwise
    // dispatch_sync to the main queue.  Synchronously dispatching onto your
    // own queue deadlocks, which is easy to hit when a script-message handler
    // (which runs on the main thread) ends up calling back into the embed API.
    BOOL is_main = msg<BOOL>(objc_cls("NSThread"), sel("isMainThread"));
    if (is_main) {
        f();
        return;
    }
    struct Holder { std::function<void()> f; };
    Holder h{std::move(f)};
    dispatch_sync_f(dispatch_get_main_queue(), &h, +[](void *p) {
        static_cast<Holder *>(p)->f();
    });
}

static void engine_on_message(Engine *e, const char *msg) {
    if (!msg) return;
    std::string s(msg);
    auto pos = s.find("\"name\":\"");
    if (pos == std::string::npos) return;
    auto start = pos + 8;
    auto end = s.find('"', start);
    if (end == std::string::npos) return;
    std::string name = s.substr(start, end - start);
    auto it = e->bindings.find(name);
    if (it == e->bindings.end()) return;
    Binding *b = it->second;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        e->jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    jmethodID mid = env->GetMethodID(b->cls, "invoke", "(Ljava/lang/String;J)V");
    if (mid) {
        jstring js = env->NewStringUTF(msg);
        env->CallVoidMethod(b->fn, mid, js, (jlong)e);
        env->DeleteLocalRef(js);
    }
    if (detach) e->jvm->DetachCurrentThread();
}

static Engine *cocoa_create_engine(JNIEnv *env, jobject parentComponent,
                                   jlong /*display*/, jint debug) {
    auto *e = new Engine();
    env->GetJavaVM(&e->jvm);
    e->debug = debug != 0;

    // Resolve the JAWT surface layers object up front, then release the
    // surface lock immediately -- holding it across a dispatch_sync to the
    // AppKit main thread can deadlock with AppKit's redraw loop.
    {
        JawtLock lock(env, parentComponent);
        if (!lock.ok) {
            delete e;
            return nullptr;
        }
        // On modern JDKs the platformInfo conforms to JAWT_SurfaceLayers and
        // exposes a 'layer' property we can populate.  Retain the id so it
        // survives any AWT-side recreation between attach and destroy.
        e->surface_layers = (id)lock.dsi->platformInfo;
        if (e->surface_layers) {
            msg<void>(e->surface_layers, sel("retain"));
        }
    }

    bool ok = false;
    cocoa_run_on_main([&] {
        e->config = msg(objc_cls("WKWebViewConfiguration"), sel("new"));
        e->manager = msg(e->config, sel("userContentController"));
        e->webview = msg(objc_cls("WKWebView"), sel("alloc"));
        e->webview = msg<id, CGRect, id>(
            e->webview, sel("initWithFrame:configuration:"),
            CGRectMake(0, 0, 800, 600), e->config);

        // wantsLayer + give the WKWebView a CALayer that JAWT can host.
        msg<void, BOOL>(e->webview, sel("setWantsLayer:"), YES);
        id layer = msg(e->webview, sel("layer"));

        // Place the layer into the JAWT-provided SurfaceLayers.
        if (e->surface_layers) {
            msg<void, id>(e->surface_layers, sel("setLayer:"), layer);
        }

        // External-message bridge: register a script message handler.
        // We allocate a tiny ObjC class on the fly to receive messages.
        Class delegate_cls = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                                    "WebviewEmbedDelegate", 0);
        class_addProtocol(delegate_cls,
                          objc_getProtocol("WKScriptMessageHandler"));
        class_addMethod(
            delegate_cls,
            sel("userContentController:didReceiveScriptMessage:"),
            (IMP)(+[](id self, SEL, id, id m) {
                Engine *eng = (Engine *)objc_getAssociatedObject(self, "eng");
                id body = msg(m, sel("body"));
                const char *s = msg<const char *>(body, sel("UTF8String"));
                engine_on_message(eng, s);
            }),
            "v@:@@");
        objc_registerClassPair(delegate_cls);
        id delegate = msg((id)delegate_cls, sel("new"));
        objc_setAssociatedObject(delegate, "eng", (id)e, OBJC_ASSOCIATION_ASSIGN);
        msg<void, id, id>(e->manager,
                          sel("addScriptMessageHandler:name:"), delegate,
                          ns_str("external"));
        // Install the external.invoke shim.
        id script = msg(objc_cls("WKUserScript"), sel("alloc"));
        script = msg<id, id, long, BOOL>(
            script, sel("initWithSource:injectionTime:forMainFrameOnly:"),
            ns_str("window.external={invoke:function(s){"
                   "window.webkit.messageHandlers.external.postMessage(s);}};"),
            (long)0 /* WKUserScriptInjectionTimeAtDocumentStart */,
            YES);
        msg<void, id>(e->manager, sel("addUserScript:"), script);

        if (e->debug) {
            id prefs = msg(e->config, sel("preferences"));
            id one = msg<id, BOOL>(objc_cls("NSNumber"),
                                   sel("numberWithBool:"), YES);
            msg<void, id, id>(prefs, sel("setValue:forKey:"),
                              one, ns_str("developerExtrasEnabled"));
        }
        ok = true;
    });
    if (!ok) {
        delete e;
        return nullptr;
    }
    return e;
}

static void cocoa_destroy_engine(Engine *e) {
    if (!e) return;
    cocoa_run_on_main([&] {
        if (e->surface_layers) {
            msg<void, id>(e->surface_layers, sel("setLayer:"), (id)nullptr);
            msg<void>(e->surface_layers, sel("release"));
            e->surface_layers = nullptr;
        }
        if (e->webview) {
            msg<void>(e->webview, sel("release"));
            e->webview = nullptr;
        }
        if (e->config) {
            msg<void>(e->config, sel("release"));
            e->config = nullptr;
        }
    });
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
    delete e;
}

static void cocoa_set_bounds(Engine *e, int x, int y, int w, int h) {
    cocoa_run_on_main([=] {
        if (!e->webview) return;
        msg<void, CGRect>(e->webview, sel("setFrame:"),
                          CGRectMake(x, y, w, h));
    });
}

static void cocoa_navigate(Engine *e, std::string url) {
    cocoa_run_on_main([=] {
        if (!e->webview) return;
        id nsurl = msg<id, id>(objc_cls("NSURL"), sel("URLWithString:"),
                               ns_str(url.c_str()));
        id req = msg<id, id>(objc_cls("NSURLRequest"),
                             sel("requestWithURL:"), nsurl);
        msg<void, id>(e->webview, sel("loadRequest:"), req);
    });
}

static void cocoa_init_script(Engine *e, std::string js) {
    cocoa_run_on_main([=] {
        if (!e->manager) return;
        id script = msg(objc_cls("WKUserScript"), sel("alloc"));
        script = msg<id, id, long, BOOL>(
            script, sel("initWithSource:injectionTime:forMainFrameOnly:"),
            ns_str(js.c_str()), (long)0, YES);
        msg<void, id>(e->manager, sel("addUserScript:"), script);
    });
}

static void cocoa_eval(Engine *e, std::string js) {
    cocoa_run_on_main([=] {
        if (!e->webview) return;
        msg<void, id, id>(e->webview,
                          sel("evaluateJavaScript:completionHandler:"),
                          ns_str(js.c_str()), (id)nullptr);
    });
}

static void cocoa_bind(Engine *e, Binding *b) { e->bindings[b->name] = b; }

#endif // WEBVIEW_COCOA

} // namespace embed

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

using embed::Binding;
using embed::Engine;
using embed::JawtLock;

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_jawt_1get_1window_1handle
  (JNIEnv *env, jclass, jobject component) {
    JawtLock lock(env, component);
    if (!lock.ok || !lock.dsi->platformInfo) return 0;
#ifdef WEBVIEW_GTK
    auto *info = (JAWT_X11DrawingSurfaceInfo *)lock.dsi->platformInfo;
    return (jlong)info->drawable;
#elif defined(WEBVIEW_COCOA)
    // Return the platformInfo pointer; on macOS this is an id<JAWT_SurfaceLayers>.
    // The value is only meaningful while the JAWT surface is locked, which is
    // not the case once we return.  Callers should prefer webview_embed_create
    // (which holds the lock for the duration of attach).
    return (jlong)lock.dsi->platformInfo;
#else
    return 0;
#endif
}

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1create
  (JNIEnv *env, jclass, jobject component, jint debug) {
#ifdef WEBVIEW_GTK
    Engine *e = embed::gtk_create_engine(env, component, debug);
    return (jlong)e;
#elif defined(WEBVIEW_COCOA)
    Engine *e = embed::cocoa_create_engine(env, component, 0, debug);
    return (jlong)e;
#else
    (void)env; (void)component; (void)debug;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1destroy
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    embed::gtk_destroy_engine((Engine *)wv);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_destroy_engine((Engine *)wv);
#else
    (void)wv;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1bounds
  (JNIEnv *, jclass, jlong wv, jint x, jint y, jint w, jint h) {
#ifdef WEBVIEW_GTK
    embed::gtk_set_bounds((Engine *)wv, x, y, w, h);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_bounds((Engine *)wv, x, y, w, h);
#else
    (void)wv; (void)x; (void)y; (void)w; (void)h;
#endif
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1pump
  (JNIEnv *, jclass, jlong /*wv*/, jint /*wait*/) {
    // On both Linux (dedicated GTK thread) and macOS (AppKit runs in JVM main
    // thread alongside AWT) the host doesn't need to pump the embed loop.
    return 0;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1navigate
  (JNIEnv *env, jclass, jlong wv, jstring url) {
    const char *u = env->GetStringUTFChars(url, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_navigate((Engine *)wv, u ? u : "");
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_navigate((Engine *)wv, u ? u : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(url, u);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1init
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_init_script((Engine *)wv, s ? s : "");
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_init_script((Engine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1eval
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_eval((Engine *)wv, s ? s : "");
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_eval((Engine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
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

    // Install the same bind shim as the existing engine so callers can write
    // window.<name>(args) and receive a JSON {name, seq, args} payload.
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

#ifdef WEBVIEW_GTK
    embed::gtk_bind(e, b);
    embed::gtk_init_script(e, js);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_bind(e, b);
    embed::cocoa_init_script(e, js);
#else
    delete b;
#endif
    env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1dispatch
  (JNIEnv *env, jclass, jlong wv, jobject callback) {
#if defined(WEBVIEW_GTK) || defined(WEBVIEW_COCOA)
    auto *e = (Engine *)wv;
    if (!e) return;
    JavaVM *jvm = e->jvm;
    jobject ref = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    jclass gcls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);

    auto fn = [jvm, ref, gcls] {
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
    };
#ifdef WEBVIEW_GTK
    embed::GtkPump::instance().run_async(fn);
#else
    embed::cocoa_run_on_main(fn);
#endif
#else
    (void)env; (void)wv; (void)callback;
#endif
}
