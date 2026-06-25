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

#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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

// Explicitly resolve JAWT_GetAWT through dlopen/dlsym instead of relying on
// the static reference + -Wl,-undefined,dynamic_lookup chain.  In practice
// the dynamic lookup on macOS can resolve the symbol to something other than
// the real libjawt entry point (e.g. a stub installed by another framework),
// which manifests as JAWT_GetAWT silently returning JNI_FALSE for every
// version mask.  Going through dlsym anchors us to the JDK's libjawt.
using jawt_get_awt_fn = jboolean (*)(JNIEnv *, JAWT *);

static jawt_get_awt_fn resolve_jawt_get_awt() {
    static jawt_get_awt_fn cached = nullptr;
    static bool resolved = false;
    if (resolved) return cached;
    resolved = true;

    // First try the process-default scope.  If libjawt is already loaded
    // (e.g. via System.loadLibrary("jawt")) this finds the real entry point
    // without us having to know the JDK install layout.
    void *sym = dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
    if (sym != nullptr) {
        fprintf(stderr,
            "[webview-embed] Resolved JAWT_GetAWT via RTLD_DEFAULT at %p\n",
            sym);
        cached = reinterpret_cast<jawt_get_awt_fn>(sym);
        return cached;
    }
    fprintf(stderr,
        "[webview-embed] JAWT_GetAWT not visible in RTLD_DEFAULT; "
        "trying explicit dlopen of libjawt.\n");

    // Walk a few candidate paths.  Order: just the soname (uses dyld search
    // path), then $JAVA_HOME variants for the common JDK layouts (JDK 9+
    // uses $JAVA_HOME/lib; JDK 8 puts libjawt in jre/lib/<arch> on Linux
    // and jre/lib on macOS).
    const char *home = getenv("JAVA_HOME");
    std::vector<std::string> candidates;
    candidates.push_back("libjawt.dylib");
    candidates.push_back("libjawt.so");
    if (home != nullptr) {
        std::string h(home);
        candidates.push_back(h + "/jre/lib/libjawt.dylib");
        candidates.push_back(h + "/lib/libjawt.dylib");
        candidates.push_back(h + "/jre/lib/libjawt.so");
        candidates.push_back(h + "/lib/libjawt.so");
        candidates.push_back(h + "/jre/lib/amd64/libjawt.so");
        candidates.push_back(h + "/jre/lib/aarch64/libjawt.so");
        candidates.push_back(h + "/jre/lib/arm/libjawt.so");
        candidates.push_back(h + "/jre/lib/i386/libjawt.so");
    }
    void *handle = nullptr;
    for (const auto &path : candidates) {
        handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (handle != nullptr) {
            fprintf(stderr,
                "[webview-embed] dlopen'd libjawt from \"%s\"\n",
                path.c_str());
            break;
        }
    }
    if (handle == nullptr) {
        fprintf(stderr,
            "[webview-embed] dlopen libjawt failed for all candidates "
            "(last dlerror: %s).\n", dlerror());
        return nullptr;
    }
    sym = dlsym(handle, "JAWT_GetAWT");
    if (sym == nullptr) {
        fprintf(stderr,
            "[webview-embed] dlsym JAWT_GetAWT failed: %s\n", dlerror());
        return nullptr;
    }
    fprintf(stderr,
        "[webview-embed] Resolved JAWT_GetAWT via dlsym at %p\n", sym);
    cached = reinterpret_cast<jawt_get_awt_fn>(sym);
    return cached;
}

struct JawtLock {
    JAWT awt;
    JAWT_DrawingSurface *ds = nullptr;
    JAWT_DrawingSurfaceInfo *dsi = nullptr;
    jint lock = 0;
    bool ok = false;

    JawtLock(JNIEnv *env, jobject component) {
        jawt_get_awt_fn fn = resolve_jawt_get_awt();
        if (fn == nullptr) {
            fprintf(stderr,
                "[webview-embed] No JAWT_GetAWT symbol available; "
                "cannot attach embed peer.\n");
            return;
        }

        // Try the newest JAWT version mask we know about first, then fall
        // back through older masks.  On macOS the CALAYER flag has to be
        // OR'd in for the platformInfo to expose JAWT_SurfaceLayers.
        std::vector<jint> masks;
#if defined(WEBVIEW_COCOA) && defined(JAWT_MACOSX_USE_CALAYER)
#if defined(JAWT_VERSION_9)
        masks.push_back(JAWT_VERSION_9 | JAWT_MACOSX_USE_CALAYER);
#endif
        masks.push_back(JAWT_VERSION_1_7 | JAWT_MACOSX_USE_CALAYER);
        masks.push_back(JAWT_VERSION_1_4 | JAWT_MACOSX_USE_CALAYER);
#endif
#if defined(JAWT_VERSION_9)
        masks.push_back(JAWT_VERSION_9);
#endif
        masks.push_back(JAWT_VERSION_1_7);
        masks.push_back(JAWT_VERSION_1_4);

        bool got = false;
        for (jint m : masks) {
            awt.version = m;
            if (fn(env, &awt)) {
                fprintf(stderr,
                    "[webview-embed] JAWT_GetAWT succeeded with mask 0x%x\n",
                    (unsigned)m);
                got = true;
                break;
            }
        }
        if (!got) {
            fprintf(stderr,
                "[webview-embed] JAWT_GetAWT rejected every version mask "
                "we know about; the JDK does not appear to expose JAWT.\n");
            return;
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
            // Embedding via XReparentWindow requires a real X11 GdkDisplay
            // on both sides.  Force the X11 backend in case GTK would
            // otherwise pick Wayland or some other backend (e.g. in a
            // Parallels / virtual desktop session that exposes both).
            gdk_set_allowed_backends("x11");
            // Use the simple input-method module rather than the system
            // default (ibus / fcitx / etc.).  On the offscreen embed
            // path the system IMs were observed to commit special-key
            // control characters as text -- e.g. typing Backspace
            // inserted 0x08 into the field instead of triggering the
            // DeleteBackward command, and Delete inserted 0x7F (which
            // renders as a block glyph in most fonts).  The simple
            // IM is a pass-through that handles dead keys and Compose
            // sequences only and lets special keys reach WebKit as
            // commands.  Setting GTK_IM_MODULE before gtk_init.  The
            // setenv overwrite=0 form respects an explicit user
            // override from the environment.
            setenv("GTK_IM_MODULE", "gtk-im-context-simple", 0);
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

    // The X11 GdkFrameClock paces itself against _NET_WM_FRAME_DRAWN
    // ClientMessages from the window manager.  Our reparented popup
    // has no WM relationship (the WM only manages the AWT top-level
    // frame), so the clock waits forever for vsync pulses that never
    // come.  gdk_frame_clock_begin_updating is supposed to drive it
    // anyway but doesn't in practice on this code path -- experiment
    // confirms only a handful of phase events across the entire
    // session.  Instead, drive paints from a plain g_timeout at
    // ~60Hz.  On a healthy GTK stack this is a redundant tick that
    // WebKit's internal damage tracking optimizes away; on the
    // virtualized X stacks where the clock stalls, it provides the
    // working paint cycle that's otherwise missing.
    guint redraw_timer_id = 0;

    bool debug = false;

    // Bindings: name -> Binding*
    std::map<std::string, Binding *> bindings;

    JavaVM *jvm = nullptr;

    // JNI global ref to the registered WebViewClickCallback, or nullptr.
    // Invoked from the gtk_gesture_multi_press "pressed" handler each
    // time the user presses a mouse button anywhere on the embedded
    // WebKitWebView -- see Operation 13 of the heavyweight-embedding
    // Canvas.  Cleared in gtk_destroy_engine BEFORE the widget is
    // destroyed so a late press cannot fire into a freed ref.
    jobject click_callback = nullptr;

    // JNI global ref to the registered WebViewDialogCallback, or nullptr.
    // Storage only in this canvas (STORY-004-001) -- the signal handler
    // wiring that actually invokes the callback lands in STORY-004-002
    // (script-dialog + run-file-chooser on WebKitWebView).  The field
    // is declared here so the JNI bridge function can store / clear
    // the global ref without dropping the canvas-004-002 changes.
    jobject dialog_callback = nullptr;

    Engine() {}
    ~Engine() {}
};

// Invoke the Java WebViewClickCallback registered on the engine, if any.
// Called from the GTK main thread; the Java callback is responsible for
// marshalling to the EDT before touching Swing state.  Mirrors the cocoa
// branch's fire_focus_callback shape but with no boolean payload because
// WebViewClickCallback.invoke is a no-arg method.
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
// JS-initiated UI dialog bridge for WebKitGTK.
//
// Connects to the `script-dialog` and `run-file-chooser` signals on each
// WebKitWebView (both heavyweight gtk_create_engine and lightweight
// gtk_off_create_engine).  Returning TRUE from each handler suppresses the
// default GTK dialog; the application drives the response by invoking
// WebViewDialogCallback methods on the per-engine dialog_callback global ref
// and feeding the answer back via webkit_script_dialog_*_set_* /
// webkit_file_chooser_request_select_files / _cancel.
//
// All four `fire_dialog_*` helpers mirror fire_click_callback's shape:
// defensive AttachCurrentThread + detach-only-if-we-attached, resolve method
// id per call (no caching), ExceptionCheck/Clear after every Call*Method.
// Strings returned from fire_dialog_prompt are g_strdup'd (caller g_free's);
// arrays returned from fire_dialog_file_picker are g_new0+g_strdup'd (caller
// g_strfreev's).  WebKitGTK copies both internally per its docs.
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
                    result = g_strdup(cstr);
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
    return result;  // caller owns; free with g_free
}

// Build a jobjectArray of UTF-8 jstrings from a NULL-terminated
// const gchar*const* (may be NULL).  Returns a freshly-allocated
// local ref; the caller is responsible for DeleteLocalRef on the
// outer array.  Element local refs are released inside this helper.
static jobjectArray nullterm_array_to_jstring_array(
        JNIEnv *env, jclass stringCls, const gchar *const *arr) {
    jsize n = 0;
    if (arr) {
        while (arr[n] != nullptr) n++;
    }
    jobjectArray ja = env->NewObjectArray(n, stringCls, nullptr);
    if (!ja) return nullptr;
    for (jsize i = 0; i < n; i++) {
        jstring js = env->NewStringUTF(arr[i]);
        if (js) {
            env->SetObjectArrayElement(ja, i, js);
            env->DeleteLocalRef(js);
        }
    }
    return ja;
}

static gchar **fire_dialog_file_picker(JavaVM *jvm, jobject callback,
                                       gboolean multiple,
                                       const gchar *const *mimeTypes,
                                       const gchar *const *extensions,
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
    gchar **result = nullptr;
    jclass stringCls = env->FindClass("java/lang/String");
    if (!stringCls) {
        if (detach) jvm->DetachCurrentThread();
        return nullptr;
    }
    jobjectArray jmimes = nullterm_array_to_jstring_array(
        env, stringCls, mimeTypes);
    jobjectArray jexts = nullterm_array_to_jstring_array(
        env, stringCls, extensions);
    jstring jpage = env->NewStringUTF(pageUrl ? pageUrl : "");
    jstring jframe = env->NewStringUTF(frameUrl ? frameUrl : "");
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        jmethodID m = env->GetMethodID(
            cls, "onFilePicker",
            "(Z[Ljava/lang/String;[Ljava/lang/String;"
            "Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;");
        if (m) {
            jobjectArray jresult = (jobjectArray)env->CallObjectMethod(
                callback, m,
                (jboolean)(multiple ? JNI_TRUE : JNI_FALSE),
                jmimes, jexts, jpage, jframe);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                jresult = nullptr;
            }
            if (jresult) {
                jsize len = env->GetArrayLength(jresult);
                if (len > 0) {
                    result = g_new0(gchar *, (gsize)len + 1);
                    gsize idx = 0;
                    for (jsize i = 0; i < len; i++) {
                        jstring js = (jstring)env->GetObjectArrayElement(
                            jresult, i);
                        if (!js) continue;
                        const char *cstr = env->GetStringUTFChars(js, nullptr);
                        if (cstr) {
                            result[idx++] = g_strdup(cstr);
                            env->ReleaseStringUTFChars(js, cstr);
                        }
                        env->DeleteLocalRef(js);
                    }
                    if (idx == 0) {
                        // Defensive: every entry was null / unreadable.
                        g_strfreev(result);
                        result = nullptr;
                    }
                }
                env->DeleteLocalRef(jresult);
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (jmimes) env->DeleteLocalRef(jmimes);
    if (jexts) env->DeleteLocalRef(jexts);
    if (jpage) env->DeleteLocalRef(jpage);
    if (jframe) env->DeleteLocalRef(jframe);
    env->DeleteLocalRef(stringCls);
    if (detach) jvm->DetachCurrentThread();
    return result;  // caller owns; free with g_strfreev
}

// Shared inner dispatcher for the `script-dialog` signal.  Engine-agnostic;
// the per-engine wrappers (on_script_dialog_engine /
// on_script_dialog_off_engine) pull jvm + dialog_callback + page URL out
// of their respective Engine / OffEngine struct and call into this
// function.  Always returns TRUE — the GTK default dialog is permanently
// suppressed for any WebViewComponent-managed engine.  Even on the
// null-callback / JNI-attach-failure path, every branch resolves the
// dialog (via the engine-specific set_* APIs or by not calling them) so
// the page's JS thread always resumes within bounded time.
static gboolean handle_script_dialog(JavaVM *jvm, jobject dialog_callback,
                                     const gchar *page_url,
                                     WebKitScriptDialog *dialog) {
    if (!dialog) return TRUE;
    WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
    const gchar *message = webkit_script_dialog_get_message(dialog);
    const char *frame_url = page_url ? page_url : "";
    switch (type) {
        case WEBKIT_SCRIPT_DIALOG_ALERT:
            if (dialog_callback) {
                fire_dialog_alert(jvm, dialog_callback,
                                  message, page_url, frame_url);
            }
            // No set_* call needed for alert.
            break;
        case WEBKIT_SCRIPT_DIALOG_CONFIRM:
        case WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM: {
            jboolean ok = JNI_FALSE;
            if (dialog_callback) {
                ok = fire_dialog_confirm(jvm, dialog_callback,
                                         message, page_url, frame_url);
            }
            webkit_script_dialog_confirm_set_confirmed(
                dialog, ok == JNI_TRUE);
            break;
        }
        case WEBKIT_SCRIPT_DIALOG_PROMPT: {
            const gchar *def =
                webkit_script_dialog_prompt_get_default_text(dialog);
            char *answer = nullptr;
            if (dialog_callback) {
                answer = fire_dialog_prompt(jvm, dialog_callback,
                                            message, def ? def : "",
                                            page_url, frame_url);
            }
            if (answer) {
                webkit_script_dialog_prompt_set_text(dialog, answer);
                g_free(answer);
            }
            // If answer is null: do NOT call _set_text.  WebKitGTK treats
            // the absence of a _set_text call as "user cancelled" and the
            // page sees prompt() return null — exactly the JS-spec cancel
            // semantic.
            break;
        }
        default:
            // Unknown dialog kind: suppress the GTK default but don't
            // forward to Java.  Conservative — future WebKitGTK versions
            // may add new dialog types we haven't taught the handler
            // about yet.
            break;
    }
    return TRUE;
}

// Shared inner dispatcher for the `run-file-chooser` signal.  Same
// engine-agnostic shape as handle_script_dialog.  Always returns TRUE so
// the GTK default file picker never fires; always resolves the request
// via either _select_files (accept) or _cancel (cancel / null callback /
// empty result) so the page's JS `change` event fires within bounded
// time.
static gboolean handle_run_file_chooser(JavaVM *jvm, jobject dialog_callback,
                                        const gchar *page_url,
                                        WebKitFileChooserRequest *request) {
    if (!request) return TRUE;
    if (!dialog_callback) {
        webkit_file_chooser_request_cancel(request);
        return TRUE;
    }
    gboolean multiple =
        webkit_file_chooser_request_get_select_multiple(request);
    const gchar *const *mime_types =
        webkit_file_chooser_request_get_mime_types(request);
    const char *frame_url = page_url ? page_url : "";

    // WebKitGTK's file-chooser API does not expose the original `.ext`
    // strings the page wrote (only the opaque GtkFileFilter via
    // _get_mime_types_filter), so we pass an empty extensions array.
    // The Java-side DialogDispatcher.normaliseExtensions handles the
    // empty case correctly; the default JFileChooser falls through to
    // "show all files".  Documented Linux limitation per the canvas.
    gchar **paths = fire_dialog_file_picker(
        jvm, dialog_callback, multiple, mime_types,
        /* extensions */ nullptr, page_url, frame_url);

    if (!paths) {
        webkit_file_chooser_request_cancel(request);
    } else {
        webkit_file_chooser_request_select_files(
            request, (const gchar *const *)paths);
        // WebKitGTK copies the paths internally per its docs, so free
        // our array immediately.
        g_strfreev(paths);
    }
    return TRUE;
}

// Per-Engine wrapper for the `script-dialog` signal.  Reads page URL,
// jvm, and dialog_callback from the heavyweight Engine struct and
// delegates to handle_script_dialog.  The OffEngine counterpart is
// defined alongside the OffEngine struct further down so its forward
// dependency is satisfied.
static gboolean on_script_dialog_engine(WebKitWebView *web,
                                        WebKitScriptDialog *dialog,
                                        gpointer user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_script_dialog(e->jvm, e->dialog_callback, uri, dialog);
}

static gboolean on_run_file_chooser_engine(WebKitWebView *web,
                                           WebKitFileChooserRequest *request,
                                           gpointer user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_run_file_chooser(e->jvm, e->dialog_callback, uri, request);
}

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
        // GTK_WINDOW_TOPLEVEL rather than POPUP.  POPUP is heavily
        // special-cased in GTK: many WMs ignore it for activation,
        // it gets override-redirect semantics, and its focus
        // bookkeeping is partial.  TOPLEVEL goes through GTK's
        // full focus/activation machinery, which the focus
        // research brief identified as a likely contributor to
        // the visible-text-input-feedback regression on this
        // reparented-popup-with-no-WM-relationship setup.
        // set_decorated(FALSE) suppresses the titlebar so the
        // TOPLEVEL window doesn't paint chrome inside the AWT
        // canvas region.
        e->window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_decorated(GTK_WINDOW(e->window), FALSE);
        // Don't set app_paintable -- with that flag set, GTK skips
        // painting a default background, which leaves the X11 default
        // (often black) showing through whenever the WebKit view fails
        // to fully cover the GdkWindow.  Letting GTK paint the theme
        // background means a render bug shows up as white, not black,
        // and reduces visual artifacts during live resize.
        gtk_widget_realize(e->window);

        GdkWindow *gdkw = gtk_widget_get_window(e->window);
        if (!gdkw) {
            fprintf(stderr,
                "[webview-embed] gtk_widget_get_window returned NULL "
                "after realize; aborting attach.\n");
            return;
        }
        if (!GDK_IS_X11_WINDOW(gdkw)) {
            fprintf(stderr,
                "[webview-embed] GdkWindow is not an X11 window (display "
                "backend is %s).  Heavyweight embedding requires X11.\n",
                gdk_display_get_name(gdk_window_get_display(gdkw)));
            return;
        }

        Window child = GDK_WINDOW_XID(gdkw);
        Display *gdkd = GDK_WINDOW_XDISPLAY(gdkw);
        fprintf(stderr,
            "[webview-embed] Reparenting GTK X11 window 0x%lx under AWT "
            "Canvas X11 window 0x%lx (display %s).\n",
            (unsigned long)child, (unsigned long)e->parent_xid,
            gdk_display_get_name(gdk_window_get_display(gdkw)));

        // Size the GTK window to match the AWT canvas's current X11
        // bounds so the very first frame doesn't show up as the GTK
        // default ~200x200 in the corner.  Java's first setBounds call
        // will refresh it once paint() fires on the canvas.
        {
            Window root_w;
            int gx = 0, gy = 0;
            unsigned int gw = 1, gh = 1, gb = 0, gd = 0;
            if (XGetGeometry(gdkd, e->parent_xid, &root_w,
                             &gx, &gy, &gw, &gh, &gb, &gd) &&
                gw > 0 && gh > 0) {
                gtk_window_resize(GTK_WINDOW(e->window), (int)gw, (int)gh);
                gdk_window_move_resize(gdkw, 0, 0, (int)gw, (int)gh);
            }
        }

        // Reparent under the AWT canvas.  We use the GDK display since AWT
        // and GTK may have different Display* handles for the same X server.
        // Don't XMapWindow here -- gtk_widget_show_all below will call
        // gdk_window_show which maps via the X server through GDK, keeping
        // GDK's mapped-state tracking in sync with reality.
        XReparentWindow(gdkd, child, e->parent_xid, 0, 0);
        XSync(gdkd, False);

        e->web = webkit_web_view_new();
        e->manager =
            webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(e->web));

        // Force software compositing.  WebKitGTK's hardware-accelerated
        // path uses DMA-BUF / GL surfaces that frequently fail when the
        // hosting GdkWindow has been XReparented into a foreign X11 tree
        // (or when the system's GL stack is virtualized, e.g. in a
        // Parallels ARM VM).  Software rendering follows reparenting
        // reliably.
        {
            WebKitSettings *s =
                webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
            webkit_settings_set_hardware_acceleration_policy(
                s, WEBKIT_HARDWARE_ACCELERATION_POLICY_NEVER);
        }

        // Force an opaque white background so a failure to render leaves
        // the WebView white (visibly empty) rather than black (which can
        // look identical to "X11 window with no content yet").
        {
            GdkRGBA white = {1.0, 1.0, 1.0, 1.0};
            webkit_web_view_set_background_color(
                WEBKIT_WEB_VIEW(e->web), &white);
        }

        // Log load lifecycle events.  The actual repaint pumping is
        // handled by the GdkFrameClock hookup below -- no need to
        // queue_draw from here, the frame clock fires every vsync and
        // WebKit's internal damage tracking decides whether anything
        // changed.  G_CALLBACK is a single-argument macro; cast to
        // GCallback by hand so the preprocessor doesn't split the
        // lambda parameter list on commas.
        auto on_load_changed =
            +[](WebKitWebView *wv, WebKitLoadEvent ev, gpointer) {
                static const char *names[] = {
                    "started", "redirected", "committed", "finished"
                };
                int idx = (int)ev;
                const char *n = (idx >= 0 && idx < 4) ? names[idx] : "?";
                const char *uri = webkit_web_view_get_uri(wv);
                fprintf(stderr,
                    "[webview-embed] load-%s: %s\n",
                    n, uri ? uri : "(null)");
            };
        auto on_load_failed =
            +[](WebKitWebView *, WebKitLoadEvent, const char *uri,
                GError *err, gpointer) -> gboolean {
                fprintf(stderr,
                    "[webview-embed] load-failed: uri=%s err=%s\n",
                    uri ? uri : "(null)",
                    (err && err->message) ? err->message : "(no message)");
                return FALSE;
            };
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "load-changed",
                         (GCallback)on_load_changed, nullptr);
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "load-failed",
                         (GCallback)on_load_failed, nullptr);

        // Wire JS-initiated dialogs (alert / confirm / prompt /
        // <input type=file>) to the per-engine Java DialogDispatcher via
        // the dialog_callback global ref.  Returning TRUE from each
        // handler suppresses the default GTK dialog; the Java side
        // decides what UI (if any) to show, with default behaviour being
        // Swing dialogs anchored on the host JFrame.  See
        // WebViewDialogHandler for the full contract.  Identical
        // registration site in gtk_off_create_engine — both engines
        // share the inner handle_script_dialog / handle_run_file_chooser
        // logic via the per-engine wrappers.
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "script-dialog",
                         (GCallback)on_script_dialog_engine, e);
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "run-file-chooser",
                         (GCallback)on_run_file_chooser_engine, e);

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

        // Click-to-focus on Linux.  X11 routes key events by
        // XSetInputFocus rather than pointer position, so without an
        // explicit focus handoff a click in the WebView lights up the
        // pointer but text fields never receive keystrokes -- the
        // AWT top-level frame keeps system focus.
        //
        // A previous attempt connected to the "button-press-event"
        // signal on the WebKitWebView.  That broke WebView rendering
        // entirely (suspected fast-path collision inside webkit2gtk's
        // input pipeline).  GtkGestureMultiPress sits on the modern
        // GtkEventController API and observes clicks *alongside* the
        // normal event-signal pipeline rather than intercepting it,
        // so it should leave WebKit's input handling untouched.
        {
            GtkGesture *click =
                gtk_gesture_multi_press_new(e->web);
            // Listen for any mouse button, not just primary, so a
            // right-click also grabs focus.
            gtk_gesture_single_set_button(
                GTK_GESTURE_SINGLE(click), 0);
            // Note: leaving the propagation phase at the default
            // BUBBLE.  An earlier revision set CAPTURE so the focus
            // grab would happen before WebKit reacts to the click,
            // but CAPTURE somehow broke WebKit's rendering pipeline
            // entirely.  BUBBLE observes alongside WebKit's normal
            // handling and is rendering-safe.
            auto on_pressed =
                +[](GtkGestureMultiPress *, gint, gdouble x, gdouble y,
                    gpointer data) {
                    Engine *eng = static_cast<Engine *>(data);
                    if (!eng || !eng->web) return;
                    // X11 focus: target the WebKitWebView's own
                    // GdkWindow (child of the popup) so the X server
                    // routes keystrokes directly to the widget that
                    // wants them, not to the popup's outer window.
                    GdkWindow *wgw = gtk_widget_get_window(eng->web);
                    if (wgw && GDK_IS_X11_WINDOW(wgw)) {
                        XSetInputFocus(GDK_WINDOW_XDISPLAY(wgw),
                                       GDK_WINDOW_XID(wgw),
                                       RevertToParent, CurrentTime);
                        XSync(GDK_WINDOW_XDISPLAY(wgw), False);
                    }
                    // GTK focus chain: tell GTK that the WebKitWebView
                    // is the focus widget within the popup, so any
                    // keys that arrive get dispatched to it.
                    gtk_widget_grab_focus(eng->web);
                    fprintf(stderr,
                        "[webview-embed] click @ (%.0f,%.0f) -> "
                        "focus grabbed (web_xid=0x%lx)\n",
                        x, y,
                        wgw ? (unsigned long)GDK_WINDOW_XID(wgw) : 0UL);
                    // Notify Java of the press so Swing can dismiss any
                    // open JPopupMenu / JMenu / JComboBox dropdown.  AWT's
                    // BasicPopupMenuUI MouseGrabber listener never sees
                    // these clicks because they reach the embedded
                    // WebKitWebView's GdkWindow directly rather than via
                    // AWT's event queue.  Added AFTER the focus-grab work
                    // above so the existing focus behaviour is preserved
                    // exactly.
                    fire_click_callback(eng);
                };
            g_signal_connect(click, "pressed",
                             (GCallback)on_pressed, e);
            // The gesture is owned by the widget; it stays alive
            // for as long as the WebKitWebView does.
        }

        // Drive the GTK paint pipeline from a plain g_timeout at ~60Hz.
        // See the redraw_timer_id field comment on Engine for the
        // rationale; in short, the X11 GdkFrameClock won't pace itself
        // on a reparented popup that has no WM relationship, so we
        // run the queue_draw + process_updates pair ourselves on a
        // timer.  WebKit's internal damage tracking decides whether
        // anything actually needs repainting; calls with no damage
        // are effectively no-ops.
        auto redraw_tick =
            +[](gpointer data) -> gboolean {
                Engine *eng = static_cast<Engine *>(data);
                if (!eng || !eng->web) return G_SOURCE_REMOVE;
                if (gtk_widget_get_visible(eng->web)) {
                    gtk_widget_queue_draw(eng->web);
                    if (eng->window) {
                        GdkWindow *pgw =
                            gtk_widget_get_window(eng->window);
                        if (pgw) {
                            G_GNUC_BEGIN_IGNORE_DEPRECATIONS
                            gdk_window_process_updates(pgw, TRUE);
                            G_GNUC_END_IGNORE_DEPRECATIONS
                        }
                    }
                }
                return G_SOURCE_CONTINUE;
            };
        e->redraw_timer_id = g_timeout_add(16, redraw_tick, e);
        fprintf(stderr,
            "[webview-embed] repaint timer started (id=%u, period=16ms).\n",
            (unsigned)e->redraw_timer_id);

        // Verbose pipeline instrumentation -- enabled with
        // DEBUG_WEBVIEW_EMBED=1 because the per-frame signals are too
        // chatty for normal use.  When set, this tells us:
        //   - whether ::draw fires on the WebKitWebView at all
        //     (no => paint pipeline is dead before WebKit ever runs)
        //   - whether each GdkFrameClock phase fires
        //     (no => begin_updating isn't taking effect on this window)
        //   - widget state right after show_all
        //     (mapped/realized/drawable/viewable + allocation)
        if (getenv("DEBUG_WEBVIEW_EMBED")) {
            auto on_draw =
                +[](GtkWidget *w, cairo_t *, gpointer name) -> gboolean {
                    static guint counter = 0;
                    guint c = ++counter;
                    if (c < 8 || (c % 60) == 0) {
                        fprintf(stderr,
                            "[webview-embed] draw#%u on %s (%p)\n",
                            c, (const char *)name, (void *)w);
                    }
                    return FALSE;
                };
            g_signal_connect(e->web, "draw",
                             (GCallback)on_draw, (gpointer)"WebKitWebView");
            g_signal_connect(e->window, "draw",
                             (GCallback)on_draw, (gpointer)"popup");

            GdkWindow *gdkw_pop = gtk_widget_get_window(e->window);
            GdkFrameClock *clk = gdkw_pop
                ? gdk_window_get_frame_clock(gdkw_pop) : nullptr;
            if (clk) {
                auto on_phase =
                    +[](GdkFrameClock *, gpointer name) {
                        static guint counters[8] = {0};
                        const char *n = (const char *)name;
                        guint h = (guint)((uintptr_t)name & 7);
                        guint c = ++counters[h];
                        if (c < 4 || (c % 240) == 0) {
                            fprintf(stderr,
                                "[webview-embed] frame-clock %s #%u\n", n, c);
                        }
                    };
                g_signal_connect(clk, "before-paint",
                                 (GCallback)on_phase, (gpointer)"before-paint");
                g_signal_connect(clk, "update",
                                 (GCallback)on_phase, (gpointer)"update");
                g_signal_connect(clk, "layout",
                                 (GCallback)on_phase, (gpointer)"layout");
                g_signal_connect(clk, "paint",
                                 (GCallback)on_phase, (gpointer)"paint");
                g_signal_connect(clk, "after-paint",
                                 (GCallback)on_phase, (gpointer)"after-paint");
            }

            GtkAllocation alloc = {0, 0, 0, 0};
            if (e->web) gtk_widget_get_allocation(e->web, &alloc);
            GdkWindow *pgw = gtk_widget_get_window(e->window);
            fprintf(stderr,
                "[webview-embed] state after show_all: popup mapped=%d "
                "realized=%d drawable=%d viewable=%d, webview mapped=%d "
                "realized=%d drawable=%d allocation=%dx%d at (%d,%d)\n",
                gtk_widget_get_mapped(e->window),
                gtk_widget_get_realized(e->window),
                gtk_widget_is_drawable(e->window),
                pgw ? gdk_window_is_viewable(pgw) : -1,
                e->web ? gtk_widget_get_mapped(e->web) : -1,
                e->web ? gtk_widget_get_realized(e->web) : -1,
                e->web ? gtk_widget_is_drawable(e->web) : -1,
                alloc.width, alloc.height, alloc.x, alloc.y);
        }

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
    // Release the click callback's JNI global ref BEFORE we destroy the
    // GtkWidget tree so any pressed signal already dispatched but not yet
    // run sees a null field instead of invoking a freed ref.  Symmetric
    // with the click-callback clear in EmbeddedWebView.dispose() on the
    // Java side -- belt-and-suspenders coverage for callbacks installed
    // via setClickCallback that never made it through that path.
    if (e->click_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    // Same treatment for the dialog-callback global ref.  Stored only
    // in this canvas; STORY-004-002 will fire signal handlers off
    // this field, so symmetric cleanup is required even though
    // STORY-004-001 itself never invokes the callback on Linux.
    if (e->dialog_callback) {
        JNIEnv *env = nullptr;
        bool detach = false;
        if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            e->jvm->AttachCurrentThread((void **)&env, nullptr);
            detach = true;
        }
        if (env) env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
        if (detach) e->jvm->DetachCurrentThread();
    }
    GtkPump::instance().run_sync([&] {
        if (e->redraw_timer_id) {
            g_source_remove(e->redraw_timer_id);
            e->redraw_timer_id = 0;
        }
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

static void gtk_set_bounds(Engine *e, int /*x*/, int /*y*/,
                           int width, int height) {
    // The Java side passes (x,y) as the canvas's position in the AWT
    // Window's content-pane coordinates because the Mac path needs that
    // (its host is NSWindow.contentView, not the canvas).  On Linux the
    // host is the canvas's own X11 window, which is already correctly
    // positioned/sized by AWT, so we always place the GTK child at
    // (0,0) of it and just match the size.
    GtkPump::instance().run_async([=] {
        if (!e->window) return;
        int w = width > 0 ? width : 1;
        int h = height > 0 ? height : 1;
        gtk_window_resize(GTK_WINDOW(e->window), w, h);
        GdkWindow *gdkw = gtk_widget_get_window(e->window);
        if (gdkw) {
            gdk_window_move_resize(gdkw, 0, 0, w, h);
        }
        // Force a repaint after resize -- in some virtualized X11 setups
        // the resize does not on its own generate an Expose, leaving the
        // newly-revealed regions blank.
        gtk_widget_queue_draw(e->window);
        if (e->web) gtk_widget_queue_draw(e->web);
    });
}

static void gtk_navigate(Engine *e, std::string url) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        fprintf(stderr,
            "[webview-embed] webkit_web_view_load_uri: %s\n", url.c_str());
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

static void gtk_set_visible(Engine *e, bool visible) {
    GtkPump::instance().run_async([=] {
        if (!e->window) return;
        if (visible) gtk_widget_show(e->window);
        else gtk_widget_hide(e->window);
    });
}

// Move X11 input focus to the embedded WebView's X11 window.  X11 routes
// key events based on input focus, not pointer position; on the AWT
// embed path the WM has only ever assigned input focus to the AWT
// top-level frame, so without this call keystrokes typed while the
// pointer is over the WebView region go to the AWT frame (and get
// swallowed there) instead of the WebKit widget.
//
// We target the WebKitWebView's own GdkWindow rather than the popup's
// outer window.  WebKitWebView is a windowed GtkContainer (creates its
// own X11 child window inside the popup), so giving X11 focus to that
// inner window lets the X server route keystrokes straight to the
// widget that wants them.
static void gtk_request_focus(Engine *e) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *wgw = gtk_widget_get_window(e->web);
        if (wgw && GDK_IS_X11_WINDOW(wgw)) {
            XSetInputFocus(GDK_WINDOW_XDISPLAY(wgw),
                           GDK_WINDOW_XID(wgw),
                           RevertToParent, CurrentTime);
            XSync(GDK_WINDOW_XDISPLAY(wgw), False);
        }
        gtk_widget_grab_focus(e->web);
    });
}

static void gtk_bind(Engine *e, Binding *b) {
    e->bindings[b->name] = b;
}

// Register (or clear, when cb is null) the Java WebViewClickCallback for
// this engine.  The pressed signal handler installed in gtk_create_engine
// fires this callback via fire_click_callback so Swing can dismiss any
// open JPopupMenu when the user clicks into the WebView -- AWT's
// MouseGrabber AWTEventListener cannot see those clicks because they
// reach the WebKitWebView's GdkWindow directly rather than via AWT.
// Always deletes any previously installed global ref before installing
// the new one, even when cb is null.  Mirrors cocoa_set_focus_callback.
static void gtk_set_click_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->click_callback) {
        env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
    }
    if (cb) {
        e->click_callback = env->NewGlobalRef(cb);
    }
}

// Register (or clear, when cb is null) the Java WebViewDialogCallback
// for this engine.  STORY-004-001 stores the global ref but does NOT
// install the WebKitWebView script-dialog / run-file-chooser signal
// handlers that actually invoke it -- those land in STORY-004-002.
// Implementing the storage lifecycle here keeps the JNI bridge linkable
// across all three platforms and lets STORY-004-002 ship by only
// touching the signal-handler wiring.
static void gtk_set_dialog_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

// ===========================================================================
// Linux / GTK lightweight (offscreen) engine
//
// Renders the WebKitWebView into a GtkOffscreenWindow which never touches
// the user's screen.  Java polls the latest pixels via JNI and blits them
// into a JComponent itself.  The whole heavyweight AWT/X11/GTK focus and
// frame-clock circus is bypassed -- we own the paint cycle, Java owns the
// AWT event flow.
// ===========================================================================

struct OffEngine {
    GtkWidget *window = nullptr;    // GtkOffscreenWindow
    GtkWidget *web = nullptr;       // WebKitWebView
    WebKitUserContentManager *manager = nullptr;
    int width = 1;
    int height = 1;
    bool debug = false;
    std::map<std::string, Binding *> bindings;
    JavaVM *jvm = nullptr;

    // JNI global ref to the registered WebViewDialogCallback, or nullptr.
    // Set by gtk_off_set_dialog_callback; cleared in gtk_off_destroy_engine.
    // Invoked by the script-dialog / run-file-chooser signal handlers
    // installed in gtk_off_create_engine, which route through the shared
    // handle_script_dialog / handle_run_file_chooser inner functions
    // declared in the heavyweight engine block above.
    jobject dialog_callback = nullptr;
};

// Per-OffEngine wrapper for the `script-dialog` signal.  Reads page URL,
// jvm, and dialog_callback from the lightweight OffEngine struct and
// delegates to the shared handle_script_dialog.  Mirrors
// on_script_dialog_engine — single divergence is the user_data cast type.
// Per the canvas Safeguards: behaviour MUST be byte-identical to the
// heavyweight variant, which is achieved by sharing handle_script_dialog.
static gboolean on_script_dialog_off_engine(WebKitWebView *web,
                                            WebKitScriptDialog *dialog,
                                            gpointer user_data) {
    OffEngine *e = static_cast<OffEngine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_script_dialog(e->jvm, e->dialog_callback, uri, dialog);
}

static gboolean on_run_file_chooser_off_engine(
        WebKitWebView *web, WebKitFileChooserRequest *request,
        gpointer user_data) {
    OffEngine *e = static_cast<OffEngine *>(user_data);
    if (!e) return TRUE;
    const gchar *uri = webkit_web_view_get_uri(web);
    return handle_run_file_chooser(e->jvm, e->dialog_callback, uri, request);
}

// Parallel of engine_on_message for OffEngine: parse the {name, seq, args}
// envelope produced by window.external.invoke / the bind shim, look the
// binding up by name, and forward the raw JSON payload to the Java
// callback's WebViewNativeCallback.invoke(String, long).
static void off_engine_on_message(OffEngine *e, const char *msg) {
    if (msg == nullptr) return;
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

static OffEngine *gtk_off_create_engine(JNIEnv *env,
                                        int width, int height, jint debug) {
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    auto *e = new OffEngine();
    env->GetJavaVM(&e->jvm);
    e->width = width;
    e->height = height;
    e->debug = debug != 0;

    bool ok = false;
    GtkPump::instance().run_sync([&] {
        e->window = gtk_offscreen_window_new();
        e->web = webkit_web_view_new();
        e->manager =
            webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(e->web));

        // Software compositing -- same rationale as the heavyweight engine:
        // hardware-accelerated paths assume on-screen surfaces, and we are
        // offscreen by design.
        WebKitSettings *s =
            webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
        webkit_settings_set_hardware_acceleration_policy(
            s, WEBKIT_HARDWARE_ACCELERATION_POLICY_NEVER);
        if (e->debug) {
            webkit_settings_set_enable_developer_extras(s, TRUE);
            webkit_settings_set_enable_write_console_messages_to_stdout(s, TRUE);
        }

        // Wire up the "external" script-message channel exactly as the
        // embed path does (see gtk_create_engine lines 553-573).  This
        // is what makes window.external.invoke -- and therefore the
        // bind-shim's webview_offscreen_bind plumbing and the
        // ConsoleDispatcher console capture -- work on the lightweight
        // component.
        g_signal_connect(
            e->manager, "script-message-received::external",
            G_CALLBACK(+[](WebKitUserContentManager *,
                           WebKitJavascriptResult *r, gpointer arg) {
                auto *eng = static_cast<OffEngine *>(arg);
                JSCValue *v = webkit_javascript_result_get_js_value(r);
                char *str = jsc_value_to_string(v);
                off_engine_on_message(eng, str);
                g_free(str);
            }),
            e);
        webkit_user_content_manager_register_script_message_handler(
            e->manager, "external");

        // Wire JS-initiated dialogs to the per-engine Java DialogDispatcher.
        // Same shape as gtk_create_engine; the offscreen variants of the
        // signal handlers route through OffEngine instead of Engine but
        // call the same inner handle_script_dialog /
        // handle_run_file_chooser logic.
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "script-dialog",
                         (GCallback)on_script_dialog_off_engine, e);
        g_signal_connect(WEBKIT_WEB_VIEW(e->web), "run-file-chooser",
                         (GCallback)on_run_file_chooser_off_engine, e);

        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(
                "window.external={invoke:function(s){"
                "window.webkit.messageHandlers.external.postMessage(s);}};",
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL));

        // Opaque white background so transparent / empty regions don't
        // surface premultiplied artefacts when blitted into Swing.
        GdkRGBA white = {1.0, 1.0, 1.0, 1.0};
        webkit_web_view_set_background_color(
            WEBKIT_WEB_VIEW(e->web), &white);

        // Disable WebKit's input method context entirely.  In our
        // offscreen embed all input arrives synthesized from Java
        // AWT, so there is nothing for an IM to compose; leaving an
        // IM in place was observed to swallow special keys -- ibus
        // / fcitx / even gtk-im-context-simple committed the
        // control character of Backspace (0x08) and Delete (0x7F)
        // as text input, leaving the field with no visible change
        // (BS) or a block glyph (DEL) instead of triggering the
        // DeleteBackward / DeleteForward editor commands.
        // Disabling IM forces every key event through WebKit's
        // editor-command lookup, which maps Backspace etc. correctly.
        webkit_web_view_set_input_method_context(
            WEBKIT_WEB_VIEW(e->web), NULL);

        // Wire the "external" script-message handler so the bind shim's
        // window.external.invoke(JSON) round-trips back into Java.  The
        // shim and envelope are identical to the heavyweight engine --
        // bindings are the single source of truth for the
        // window.<name>(...) contract across both modes.
        g_signal_connect(
            e->manager, "script-message-received::external",
            G_CALLBACK(+[](WebKitUserContentManager *m,
                           WebKitJavascriptResult *r, gpointer arg) {
                auto *eng = static_cast<OffEngine *>(arg);
                JSCValue *v = webkit_javascript_result_get_js_value(r);
                char *s = jsc_value_to_string(v);
                off_engine_on_message(eng, s);
                g_free(s);
            }),
            e);
        webkit_user_content_manager_register_script_message_handler(
            e->manager, "external");
        webkit_user_content_manager_add_script(
            e->manager,
            webkit_user_script_new(
                "window.external={invoke:function(s){"
                "window.webkit.messageHandlers.external.postMessage(s);}};",
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL));

        gtk_widget_set_size_request(e->web, e->width, e->height);
        gtk_container_add(GTK_CONTAINER(e->window), e->web);
        gtk_window_set_default_size(GTK_WINDOW(e->window),
                                    e->width, e->height);
        gtk_widget_show_all(e->window);

        // Tell WebKit it has focus so input fields can show carets
        // and the page is treated as "active" for compositing.  GTK
        // normally only emits focus-in-event when the toplevel
        // GtkWindow gains WM focus -- which never happens for our
        // offscreen toplevel -- so without this synthetic event
        // WebKit thinks the page is permanently inactive and skips
        // caret painting.
        gtk_widget_grab_focus(e->web);
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (gw) {
            GdkEvent *fe = gdk_event_new(GDK_FOCUS_CHANGE);
            fe->focus_change.window = (GdkWindow *)g_object_ref(gw);
            fe->focus_change.send_event = TRUE;
            fe->focus_change.in = TRUE;
            gtk_main_do_event(fe);
            gdk_event_free(fe);
        }

        ok = (e->web != nullptr && e->window != nullptr);
    });
    if (!ok) {
        delete e;
        return nullptr;
    }
    fprintf(stderr,
        "[webview-embed] offscreen engine ready (%dx%d)\n",
        e->width, e->height);
    return e;
}

static void gtk_off_destroy_engine(OffEngine *e) {
    if (!e) return;
    GtkPump::instance().run_sync([&] {
        if (e->window) {
            gtk_widget_destroy(e->window);
            e->window = nullptr;
            e->web = nullptr;
        }
    });
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

// Register (or clear, when cb is null) the Java WebViewDialogCallback
// for this offscreen engine.  STORY-004-001 stores the global ref but
// does NOT install the WebKitGTK signal handlers; STORY-004-002 wires
// the script-dialog + run-file-chooser handlers on the offscreen
// WebKitWebView.
static void gtk_off_set_dialog_callback(OffEngine *e, JNIEnv *env,
                                        jobject cb) {
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

static void gtk_off_init_script(OffEngine *e, std::string js) {
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

static void gtk_off_eval(OffEngine *e, std::string js) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        webkit_web_view_run_javascript(WEBKIT_WEB_VIEW(e->web), js.c_str(),
                                       NULL, NULL, NULL);
    });
}

static void gtk_off_bind(OffEngine *e, Binding *b) {
    e->bindings[b->name] = b;
}

static void gtk_off_resize(OffEngine *e, int w, int h) {
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    GtkPump::instance().run_async([=] {
        if (!e->web || !e->window) return;
        e->width = w;
        e->height = h;
        gtk_widget_set_size_request(e->web, w, h);
        gtk_window_resize(GTK_WINDOW(e->window), w, h);
    });
}

static void gtk_off_navigate(OffEngine *e, std::string url) {
    GtkPump::instance().run_async([=] {
        if (!e->web) return;
        fprintf(stderr,
            "[webview-embed] offscreen load_uri: %s\n", url.c_str());
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(e->web), url.c_str());
    });
}

// Open the WebKitGTK Web Inspector for the offscreen WebView in a separate
// OS-level GtkWindow.  The inspector belongs to the WebView's own
// process and is unaffected by the host Swing window's visibility.
// Returns 1 on success, 0 if developer-extras is disabled or the
// inspector is unavailable.
static int gtk_off_open_devtools(OffEngine *e) {
    if (!e || !e->web) return 0;
    int result = 0;
    GtkPump::instance().run_sync([&] {
        if (!e->web) return;
        WebKitSettings *s =
            webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
        if (!s || !webkit_settings_get_enable_developer_extras(s)) return;
        WebKitWebInspector *insp =
            webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web));
        if (!insp) return;
        webkit_web_inspector_show(insp);
        result = 1;
    });
    return result;
}

// Execute Cut/Copy/Paste/SelectAll against the focused frame of the
// offscreen WebKitGTK widget.  Mirrors gtk_execute_editing_command for
// the heavyweight engine -- same switch-on-cmdId + GtkPump::run_async +
// webkit_web_view_execute_editing_command pattern, just over OffEngine.
//
// cmdId values are the EditingCommand contract: 1=CUT, 2=COPY, 3=PASTE,
// 4=SELECT_ALL.  Unknown cmdIds are silently dropped.
static void gtk_off_execute_editing_command(OffEngine *e, int cmdId) {
    if (!e || !e->web) return;
    const char *command = nullptr;
    switch (cmdId) {
        case 1: command = WEBKIT_EDITING_COMMAND_CUT;        break;
        case 2: command = WEBKIT_EDITING_COMMAND_COPY;       break;
        case 3: command = WEBKIT_EDITING_COMMAND_PASTE;      break;
        case 4: command = WEBKIT_EDITING_COMMAND_SELECT_ALL; break;
        default: return;
    }
    GtkPump::instance().run_async([e, command] {
        if (!e || !e->web) return;
        webkit_web_view_execute_editing_command(
            WEBKIT_WEB_VIEW(e->web), command);
    });
}

// Same idea for the heavyweight embed-path engine.  Lives here next to
// the offscreen variant so both implementations are visible side by side.
static int gtk_open_devtools(Engine *e) {
    if (!e || !e->web) return 0;
    int result = 0;
    GtkPump::instance().run_sync([&] {
        if (!e->web) return;
        WebKitSettings *s =
            webkit_web_view_get_settings(WEBKIT_WEB_VIEW(e->web));
        if (!s || !webkit_settings_get_enable_developer_extras(s)) return;
        WebKitWebInspector *insp =
            webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(e->web));
        if (!insp) return;
        webkit_web_inspector_show(insp);
        result = 1;
    });
    return result;
}

// Execute Cut/Copy/Paste/SelectAll on the focused frame of the embedded
// WebKitGTK widget.  Marshals to the GTK pump thread asynchronously; the
// editing-command primitive operates against the WebView's current focused
// frame internally, so we don't have to do focus-routing ourselves.
//
// cmdId values are the EditingCommand contract: 1=CUT, 2=COPY, 3=PASTE,
// 4=SELECT_ALL.  Unknown cmdIds are silently dropped.
static void gtk_execute_editing_command(Engine *e, int cmdId) {
    if (!e || !e->web) return;
    const char *command = nullptr;
    switch (cmdId) {
        case 1: command = WEBKIT_EDITING_COMMAND_CUT;        break;
        case 2: command = WEBKIT_EDITING_COMMAND_COPY;       break;
        case 3: command = WEBKIT_EDITING_COMMAND_PASTE;      break;
        case 4: command = WEBKIT_EDITING_COMMAND_SELECT_ALL; break;
        default: return;
    }
    GtkPump::instance().run_async([e, command] {
        if (!e || !e->web) return;
        webkit_web_view_execute_editing_command(
            WEBKIT_WEB_VIEW(e->web), command);
    });
}

// Mouse event injection.  Java listeners on WebViewLightweightComponent
// translate AWT MouseEvents into these calls; we synthesize a GdkEvent
// and dispatch through gtk_main_do_event, which routes through GTK's
// normal event pipeline so WebKit sees them just like a real click.
static void gtk_off_mouse_button(OffEngine *e, bool press,
                                 int x, int y, int button, int modifiers,
                                 int click_count) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *pointer = seat ? gdk_seat_get_pointer(seat) : nullptr;

        GdkEventType type;
        if (press) {
            type = (click_count >= 3) ? GDK_3BUTTON_PRESS
                 : (click_count == 2) ? GDK_2BUTTON_PRESS
                 :                       GDK_BUTTON_PRESS;
        } else {
            type = GDK_BUTTON_RELEASE;
        }

        GdkEvent *ev = gdk_event_new(type);
        ev->button.window = (GdkWindow *)g_object_ref(gw);
        ev->button.send_event = TRUE;
        ev->button.time = GDK_CURRENT_TIME;
        ev->button.x = x;
        ev->button.y = y;
        ev->button.x_root = x;
        ev->button.y_root = y;
        ev->button.state = (GdkModifierType)modifiers;
        ev->button.button = button;
        ev->button.device = pointer;
        if (pointer) gdk_event_set_device(ev, pointer);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

static void gtk_off_mouse_motion(OffEngine *e, int x, int y, int modifiers) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *pointer = seat ? gdk_seat_get_pointer(seat) : nullptr;

        GdkEvent *ev = gdk_event_new(GDK_MOTION_NOTIFY);
        ev->motion.window = (GdkWindow *)g_object_ref(gw);
        ev->motion.send_event = TRUE;
        ev->motion.time = GDK_CURRENT_TIME;
        ev->motion.x = x;
        ev->motion.y = y;
        ev->motion.x_root = x;
        ev->motion.y_root = y;
        ev->motion.state = (GdkModifierType)modifiers;
        ev->motion.is_hint = FALSE;
        ev->motion.device = pointer;
        if (pointer) gdk_event_set_device(ev, pointer);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

static void gtk_off_mouse_scroll(OffEngine *e, int x, int y,
                                 double dx, double dy, int modifiers) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *pointer = seat ? gdk_seat_get_pointer(seat) : nullptr;

        GdkEvent *ev = gdk_event_new(GDK_SCROLL);
        ev->scroll.window = (GdkWindow *)g_object_ref(gw);
        ev->scroll.send_event = TRUE;
        ev->scroll.time = GDK_CURRENT_TIME;
        ev->scroll.x = x;
        ev->scroll.y = y;
        ev->scroll.x_root = x;
        ev->scroll.y_root = y;
        ev->scroll.state = (GdkModifierType)modifiers;
        ev->scroll.direction = GDK_SCROLL_SMOOTH;
        ev->scroll.delta_x = dx;
        ev->scroll.delta_y = dy;
        ev->scroll.device = pointer;
        if (pointer) gdk_event_set_device(ev, pointer);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

// Key event injection.  Java KeyListeners on
// WebViewLightweightComponent translate AWT KeyEvents to a GDK
// keyval (via the GdkInput helper) and forward via these calls.
// We also focus the WebKitWebView once on first key press so GTK's
// internal focus chain agrees that this widget should receive the
// dispatched key event.
//
// hardware_keycode is derived from the keyval via the active keymap.
// WebKit's editor command lookup uses the keycode (combined with the
// group / level) to identify the physical key; with hardware_keycode
// left at 0 it falls into a "treat as character input" path and ends
// up inserting the Unicode of the keysym (0x08 for BackSpace, 0x7F
// for Delete) instead of executing DeleteBackward / DeleteForward.
static void gtk_off_key_event(OffEngine *e, bool press,
                              int keyval, int modifiers,
                              bool is_modifier_key) {
    GtkPump::instance().run_async([=] {
        if (!e || !e->web) return;
        GdkWindow *gw = gtk_widget_get_window(e->web);
        if (!gw) return;
        GdkDisplay *display = gtk_widget_get_display(e->web);
        GdkSeat *seat = gdk_display_get_default_seat(display);
        GdkDevice *keyboard = seat ? gdk_seat_get_keyboard(seat) : nullptr;

        guint hwcode = 0;
        guint8 group = 0;
        GdkKeymap *km = gdk_keymap_get_for_display(display);
        if (km) {
            GdkKeymapKey *keys = nullptr;
            gint n_keys = 0;
            if (gdk_keymap_get_entries_for_keyval(km, (guint)keyval,
                                                  &keys, &n_keys)
                && n_keys > 0 && keys != nullptr) {
                hwcode = keys[0].keycode;
                group = (guint8)keys[0].group;
            }
            if (keys) g_free(keys);
        }

        if (!gtk_widget_has_focus(e->web)) {
            gtk_widget_grab_focus(e->web);
        }

        GdkEvent *ev =
            gdk_event_new(press ? GDK_KEY_PRESS : GDK_KEY_RELEASE);
        ev->key.window = (GdkWindow *)g_object_ref(gw);
        ev->key.send_event = TRUE;
        ev->key.time = GDK_CURRENT_TIME;
        ev->key.state = (GdkModifierType)modifiers;
        ev->key.keyval = (guint)keyval;
        ev->key.length = 0;
        ev->key.string = nullptr;
        ev->key.hardware_keycode = (guint16)hwcode;
        ev->key.group = group;
        ev->key.is_modifier = is_modifier_key ? 1 : 0;
        if (keyboard) gdk_event_set_device(ev, keyboard);
        gtk_main_do_event(ev);
        gdk_event_free(ev);
    });
}

// Copies the current contents of the offscreen window into the caller-
// supplied Java int[].  Pixels are CAIRO_FORMAT_ARGB32 -- 0xAARRGGBB per
// pixel on both little- and big-endian builds (matches Java
// BufferedImage TYPE_INT_ARGB).  The Java array must be at least w*h ints.
//
// Implementation note: we allocate our own image surface and ask the
// offscreen GtkWindow to draw into it via gtk_widget_draw.  We do NOT
// use gtk_offscreen_window_get_surface -- it's (transfer-none) so we
// must not destroy what it returns, and its internal surface is only
// populated after a frame-clock-driven draw, which on this code path
// isn't reliably ticking.  Drawing on demand into a surface we own
// sidesteps both lifetime and timing concerns.
static void gtk_off_snapshot_into(OffEngine *e, JNIEnv *env,
                                  jintArray dest, jint w, jint h) {
    if (!e || w < 1 || h < 1) return;
    jsize len = env->GetArrayLength(dest);
    if (len < (jsize)((size_t)w * (size_t)h)) return;

    // Default-fill the temp buffer with opaque white so any region not
    // actually drawn by WebKit shows up as expected background colour
    // rather than uninitialised memory.
    std::vector<uint32_t> tmp((size_t)w * (size_t)h, 0xFFFFFFFFu);

    GtkPump::instance().run_sync([&] {
        if (!e->window || !e->web) return;
        cairo_surface_t *dst = cairo_image_surface_create(
            CAIRO_FORMAT_ARGB32, w, h);
        if (!dst || cairo_surface_status(dst) != CAIRO_STATUS_SUCCESS) {
            if (dst) cairo_surface_destroy(dst);
            return;
        }
        cairo_t *cr = cairo_create(dst);
        if (cr && cairo_status(cr) == CAIRO_STATUS_SUCCESS) {
            gtk_widget_draw(e->window, cr);
        }
        if (cr) cairo_destroy(cr);

        cairo_surface_flush(dst);
        unsigned char *data = cairo_image_surface_get_data(dst);
        int stride = cairo_image_surface_get_stride(dst);
        if (data) {
            for (int y = 0; y < h; y++) {
                std::memcpy(tmp.data() + (size_t)y * (size_t)w,
                            data + (size_t)y * (size_t)stride,
                            (size_t)w * 4);
            }
        }
        cairo_surface_destroy(dst);
    });

    // Now safely on the calling thread: copy temp buffer into the Java
    // int[].  SetIntArrayRegion is thread-safe per the JNI spec.
    env->SetIntArrayRegion(dest, 0, (jsize)((size_t)w * (size_t)h),
                           reinterpret_cast<const jint *>(tmp.data()));
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

// Struct-return-safe dispatch.  msg<>() above is only valid for selectors
// whose return value comes back in registers (and for passing struct-by-value
// *arguments*).  Selectors that *return* a struct larger than 16 bytes --
// notably NSRect / CGRect (32 bytes), e.g. -[NSView bounds] -- must NOT go
// through plain objc_msgSend on x86_64: the SysV ABI passes such returns via a
// hidden pointer in the first integer-argument register, which shifts self/cmd
// by one slot so the runtime dereferences the stack return-buffer as the
// receiver -- a SIGSEGV in objc_msgSend.  x86_64 must dispatch these through
// objc_msgSend_stret; arm64 has no objc_msgSend_stret and returns large
// structs via the x8 register, so plain objc_msgSend is correct there.  See
// the "ABI-correct Objective-C struct-return dispatch on macOS" norm in the
// heavyweight-embedding Canvas (issue #36).
template <typename Ret, typename... Args>
static inline Ret msg_stret(id receiver, SEL selector, Args... args) {
    using Fn = Ret (*)(id, SEL, Args...);
#if defined(__x86_64__)
    return reinterpret_cast<Fn>(objc_msgSend_stret)(receiver, selector, args...);
#else
    return reinterpret_cast<Fn>(objc_msgSend)(receiver, selector, args...);
#endif
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
    // The NSView hosting the WKWebView as a real subview.  WKWebView's
    // CARemoteLayer-based rendering only engages once the view is part of
    // an NSView hierarchy that ends in an NSWindow.  On Corretto 8 macOS
    // arm64 (and OpenJDK builds with a similar layer-only design) there is
    // no per-Canvas AWT NSView; we attach to NSWindow.contentView and use
    // the caller-supplied AWT canvas position to set the WKWebView's
    // frame within it.
    id host_view = nullptr;
    // True when host_view is a per-Canvas AWT NSView whose bounds already
    // match the canvas (so WKWebView frame is just (0,0,w,h)).  False when
    // host_view is NSWindow.contentView and we have to honor the caller's
    // (x,y) and translate AWT top-left coords into Cocoa bottom-left.
    bool host_is_awt = false;

    // JNI global ref to the registered WebViewFocusCallback, or nullptr.
    // Invoked by the swizzled becomeFirstResponder / resignFirstResponder
    // implementations.
    jobject focus_callback = nullptr;

    // JNI global ref to the registered WebViewClickCallback, or nullptr.
    // Invoked by the swizzled mouseDown: / rightMouseDown: /
    // otherMouseDown: implementations after the original IMP has run, so
    // Swing can dismiss any open JPopupMenu when the user clicks into
    // the WebView -- AWT's MouseGrabber AWTEventListener cannot see
    // those clicks because the heavyweight peer receives them through
    // the AppKit responder chain rather than AWT's event queue.
    jobject click_callback = nullptr;

    // Mirrored first-responder state.  Written on the AppKit main thread
    // by the KVO observer callback on NSWindow.firstResponder (see
    // WebviewEmbedKvoObserver below); read lock-free from any thread by
    // cocoa_is_first_responder.  A stale read by at most one event-loop
    // tick is acceptable; a single bool admits no torn reads.
    std::atomic<bool> is_first_responder{false};

    // Destroyed flag.  Set true as the FIRST action inside the destroy
    // lambda (cocoa_destroy_engine), before any AppKit teardown runs.
    // Every other async-on-main lambda (navigate / eval / init_script /
    // set_bounds / set_visible / request_focus / execute_editing_command
    // / bind) reads this at fire time and short-circuits cleanly if true.
    // The primary correctness guarantee is dispatch-queue FIFO ordering +
    // EDT-only enqueueing; this flag is belt-and-suspenders against
    // (a) destroy-from-non-EDT, (b) future code changes that violate the
    // EDT-only-enqueue invariant, and (c) the cocoa_eval late-fire path
    // that needs to short-circuit if the engine is gone.
    std::atomic<bool> destroyed{false};

    // KVO observer wired against the host window's firstResponder key
    // path; lazily registered on the first non-nil window the WKWebView
    // sees, and re-registered if the WKWebView moves between windows at
    // runtime.  Owned by the engine (retained); released in the destroy
    // lambda before the WKWebView itself is released.
    id kvo_observer = nullptr;

    // Last NSWindow the KVO observer was registered against.  Used to
    // unregister cleanly during destroy or on window change.  Weak
    // (unretained) back-reference; AppKit owns the window's lifecycle.
    id observed_window = nullptr;

    // Attach-completion resolution state.  Coordinated between
    // (a) the AppKit-main-thread epilogue inside cocoa_create_engine,
    // which sets attach_resolved+attach_ok+attach_failure_message and
    // fires the callback if one is registered; and (b) the EDT-thread
    // cocoa_set_attach_callback, which stores the callback and fires
    // it immediately if attach is already resolved.  Guarded by
    // attach_callback_mutex.  The callback is fired exactly once: the
    // mutex serialises the "store callback + fire if resolved" and
    // "set resolved + fire if callback present" windows, and the
    // callback fields are cleared after firing.
    std::mutex attach_callback_mutex;
    bool attach_resolved = false;
    bool attach_ok = false;
    std::string attach_failure_message;
    jobject attach_callback = nullptr;
    jclass attach_callback_cls = nullptr;

    // JNI global ref to the registered WebViewDialogCallback, or
    // nullptr.  Invoked by the WKUIDelegate selectors below for each
    // JS-initiated alert / confirm / prompt and for <input type=file>
    // clicks.  The selector waits for the Java side's return value
    // (via DialogDispatcher's invokeAndWait EDT hop) before invoking
    // the platform's completion handler, which is what releases the
    // page's JS thread.  Cleared in cocoa_destroy_engine BEFORE the
    // ui_delegate is released so any in-flight selector reads a null
    // field instead of a freed ref.
    jobject dialog_callback = nullptr;

    // Per-engine WKUIDelegate instance assigned to e->webview via
    // setUIDelegate:.  Retained by us (we hold the only strong ref);
    // released in cocoa_destroy_engine after we clear the WKWebView's
    // uiDelegate property.
    id ui_delegate = nullptr;
};

// Process-global map from WKWebView (id) to its owning Engine*.  Populated
// in cocoa_create_engine after WKWebView alloc; cleared in
// cocoa_destroy_engine.  Guarded by g_webview_map_mutex because the
// swizzled responder hooks fire on the AppKit main thread while
// create/destroy run on EDT-driven native code.
static std::mutex g_webview_map_mutex;
static std::map<id, Engine *> g_webview_map;

// The EDT↔AppKit-main synchronous bridge has been eliminated.  Per
// Canvas 6 Norms (the macOS sync EDT→AppKit-main bridge prohibition),
// every per-engine native operation runs via cocoa_run_on_main_async
// (FIFO dispatch_get_main_queue) or inlines when already on main; any
// AppKit-thread state the EDT needs to read is mirrored into an
// std::atomic on the Engine (see Engine::is_first_responder, fed by
// the KVO observer on NSWindow.firstResponder, walked below).  The
// historical scaffolding -- cocoa_run_on_main, WebViewAwtMainBridge,
// performWork:, ensure_awt_main_bridge, awt_bridge_box,
// awt_main_bridge_perform_impl, the g_awt_main_bridge_* statics, and
// the AwtBridgeWork heap-allocated work item -- has been removed.  Do
// NOT reintroduce any of them; the structural reason is documented in
// the canvas's "Eliminate Sync EDT↔AppKit Bridge" approach entry.

static void cocoa_run_on_main_async(std::function<void()> f) {
    BOOL is_main = msg<BOOL>(objc_cls("NSThread"), sel("isMainThread"));
    if (is_main) {
        f();
        return;
    }
    struct Holder { std::function<void()> f; };
    Holder *h = new Holder{std::move(f)};
    dispatch_async_f(dispatch_get_main_queue(), h, +[](void *p) {
        Holder *g = static_cast<Holder *>(p);
        g->f();
        delete g;
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

// ---------------------------------------------------------------------------
// First-responder hook on WKWebView.
//
// We swizzle becomeFirstResponder / resignFirstResponder on the WKWebView
// class so we can mirror the native focus state back into Swing.  Swizzling
// is class-wide: it affects every WKWebView in the process, including any
// the host application created independently of this library.  That's
// fine because each swizzled implementation looks up the receiver in
// g_webview_map and silently no-ops if we don't own it.
//
// The Java callback is invoked via JNI on whatever thread AppKit drove
// the responder change on (typically the AppKit main thread).  Java-side
// callers MUST marshal to the EDT before touching Swing state -- the
// callback contract documents this explicitly.
// ---------------------------------------------------------------------------

typedef BOOL (*BoolFromIdSel)(id, SEL);
static BoolFromIdSel g_orig_becomeFirstResponder = nullptr;
static BoolFromIdSel g_orig_resignFirstResponder = nullptr;
static std::once_flag g_focus_swizzle_once;

// WKScriptMessageHandler delegate class used by every Cocoa engine for the
// window.external.invoke bridge.  objc_allocateClassPair returns Nil if the
// class name is already registered, so the allocation/registration MUST run
// exactly once per JVM -- the same pattern as g_focus_swizzle_once above.
// See issue #21 and the Canvas constraint on cocoa_create_engine.
static std::once_flag g_webview_embed_delegate_once;
static Class g_webview_embed_delegate_cls = nil;

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

static BOOL swizzled_become_first_responder(id self, SEL _cmd) {
    BOOL result = g_orig_becomeFirstResponder
        ? g_orig_becomeFirstResponder(self, _cmd)
        : NO;
    if (result) {
        Engine *eng = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_webview_map_mutex);
            auto it = g_webview_map.find(self);
            if (it != g_webview_map.end()) eng = it->second;
        }
        if (eng) fire_focus_callback(eng, true);
    }
    return result;
}

static BOOL swizzled_resign_first_responder(id self, SEL _cmd) {
    BOOL result = g_orig_resignFirstResponder
        ? g_orig_resignFirstResponder(self, _cmd)
        : NO;
    if (result) {
        Engine *eng = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_webview_map_mutex);
            auto it = g_webview_map.find(self);
            if (it != g_webview_map.end()) eng = it->second;
        }
        if (eng) fire_focus_callback(eng, false);
    }
    return result;
}

static void install_focus_swizzle() {
    std::call_once(g_focus_swizzle_once, [] {
        Class wk = (Class)objc_cls("WKWebView");
        if (!wk) return;
        Method become = class_getInstanceMethod(wk, sel("becomeFirstResponder"));
        Method resign = class_getInstanceMethod(wk, sel("resignFirstResponder"));
        if (!become || !resign) return;
        g_orig_becomeFirstResponder = (BoolFromIdSel)method_setImplementation(
            become, (IMP)swizzled_become_first_responder);
        g_orig_resignFirstResponder = (BoolFromIdSel)method_setImplementation(
            resign, (IMP)swizzled_resign_first_responder);
    });
}

static Class get_webview_embed_delegate_cls() {
    std::call_once(g_webview_embed_delegate_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedDelegate", 0);
        class_addProtocol(c, objc_getProtocol("WKScriptMessageHandler"));
        class_addMethod(
            c,
            sel("userContentController:didReceiveScriptMessage:"),
            (IMP)(+[](id self, SEL, id, id m) {
                Engine *eng = (Engine *)objc_getAssociatedObject(self, "eng");
                id body = msg(m, sel("body"));
                const char *s = msg<const char *>(body, sel("UTF8String"));
                engine_on_message(eng, s);
            }),
            "v@:@@");
        objc_registerClassPair(c);
        g_webview_embed_delegate_cls = c;
    });
    return g_webview_embed_delegate_cls;
}

// ---------------------------------------------------------------------------
// WKUIDelegate hook for JS-initiated dialogs.
//
// WKWebView consults its uiDelegate to know what to do for
// runJavaScriptAlertPanelWithMessage: / runJavaScriptConfirmPanel: /
// runJavaScriptTextInputPanel: / runOpenPanelWithParameters:.  If the
// uiDelegate is nil -- which is the default -- WKWebView SILENTLY DROPS
// these requests: alert() returns immediately, confirm() returns false,
// prompt() returns null, and <input type=file> clicks open no picker.
// Installing a uiDelegate that implements these selectors restores the
// expected JS behaviour and lets the host customise it via
// WebViewDialogHandler.
//
// Selectors are invoked on the AppKit main thread.  Each selector takes
// a completionHandler block that MUST be invoked exactly once to release
// the page's JavaScript thread; the dispatcher's invokeAndWait hop to
// the Swing EDT happens between selector entry and completion-handler
// invocation, so AppKit main is parked during the modal Swing dialog.
// That is the correct JS-contract behaviour: the page is frozen while
// the dialog is open.
//
// Class registration mirrors get_webview_embed_delegate_cls above
// (once-per-JVM call_once + objc_allocateClassPair + class_addMethod +
// objc_registerClassPair).
// ---------------------------------------------------------------------------
static std::once_flag g_webview_embed_ui_delegate_once;
static Class g_webview_embed_ui_delegate_cls = nil;

// Helper: convert an NSString (or nil) to a fresh jstring via UTF-8.
// Returns nullptr for nil input.  Caller is responsible for releasing
// the local ref via DeleteLocalRef when done.
static jstring ns_to_jstring(JNIEnv *env, id ns) {
    if (!ns) return nullptr;
    const char *cstr = msg<const char *>(ns, sel("UTF8String"));
    if (!cstr) return nullptr;
    return env->NewStringUTF(cstr);
}

// Helper: read the top-level page URL from the WKWebView.
static jstring page_url_jstring(JNIEnv *env, id webView) {
    if (!webView) return nullptr;
    id url = msg(webView, sel("URL"));
    if (!url) return nullptr;
    id abs = msg(url, sel("absoluteString"));
    return ns_to_jstring(env, abs);
}

// Helper: read the URL of the frame that initiated the dialog.  When
// frame is nil or its request has no URL, fall back to the page URL.
static jstring frame_url_jstring(JNIEnv *env, id frame, id webView) {
    if (frame) {
        id req = msg(frame, sel("request"));
        if (req) {
            id url = msg(req, sel("URL"));
            if (url) {
                id abs = msg(url, sel("absoluteString"));
                jstring js = ns_to_jstring(env, abs);
                if (js) return js;
            }
        }
    }
    return page_url_jstring(env, webView);
}

// UTF-8 std::string variants of the three helpers above.  Used by the
// WKUIDelegate IMPs to capture string inputs synchronously on AppKit
// main BEFORE launching the worker thread that does the JNI hop --
// std::string captures are thread-safe and don't require us to retain
// NSString instances across thread boundaries.
static std::string ns_string_to_utf8(id ns) {
    if (!ns) return std::string();
    const char *cstr = msg<const char *>(ns, sel("UTF8String"));
    return cstr ? std::string(cstr) : std::string();
}

static std::string page_url_utf8(id webView) {
    if (!webView) return std::string();
    id url = msg(webView, sel("URL"));
    if (!url) return std::string();
    id abs = msg(url, sel("absoluteString"));
    return ns_string_to_utf8(abs);
}

static std::string frame_url_utf8(id frame, id webView) {
    if (frame) {
        id req = msg(frame, sel("request"));
        if (req) {
            id url = msg(req, sel("URL"));
            if (url) {
                id abs = msg(url, sel("absoluteString"));
                std::string s = ns_string_to_utf8(abs);
                if (!s.empty()) return s;
            }
        }
    }
    return page_url_utf8(webView);
}

// Read an NSArray<NSString*> (or nil) into a std::vector<std::string>.
// Used by impl_run_open_panel to snapshot the mime / extension arrays
// on AppKit main before handing off to the worker thread.
static std::vector<std::string> ns_array_to_utf8_vector(id nsArray) {
    std::vector<std::string> out;
    if (!nsArray) return out;
    long count = msg<long>(nsArray, sel("count"));
    out.reserve((size_t)count);
    for (long i = 0; i < count; i++) {
        id ns = msg<id, long>(nsArray, sel("objectAtIndex:"), i);
        out.push_back(ns_string_to_utf8(ns));
    }
    return out;
}

// Helper: convert an NSArray<NSString*> (or nil) to a fresh
// jobjectArray of UTF-8 jstrings.  Returns an empty array for nil
// input -- never returns nullptr -- so the Java side can assume
// non-null arrays.  Caller is responsible for releasing the local ref.
static jobjectArray ns_array_to_jstring_array(JNIEnv *env, id nsArray) {
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    if (!nsArray) {
        jobjectArray empty = env->NewObjectArray(0, strCls, nullptr);
        env->DeleteLocalRef(strCls);
        return empty;
    }
    long count = msg<long>(nsArray, sel("count"));
    jobjectArray arr = env->NewObjectArray(
        (jsize)count, strCls, nullptr);
    for (long i = 0; i < count; i++) {
        id ns = msg<id, long>(nsArray, sel("objectAtIndex:"), i);
        jstring js = ns_to_jstring(env, ns);
        if (js) {
            env->SetObjectArrayElement(arr, (jsize)i, js);
            env->DeleteLocalRef(js);
        }
    }
    env->DeleteLocalRef(strCls);
    return arr;
}

// Ensure the current AppKit thread is attached to the JVM and return
// the JNIEnv.  Sets *attached to true when this call attached (the
// caller must detach before returning to AppKit).  Returns nullptr on
// failure -- caller must invoke the platform completion handler with
// the safe default.
static JNIEnv *ensure_jni_env(JavaVM *jvm, bool *attached) {
    *attached = false;
    if (!jvm) return nullptr;
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        *attached = true;
    }
    return env;
}

// IMP: -[WebviewEmbedUIDelegate webView:runJavaScriptAlertPanelWithMessage:
//                               initiatedByFrame:completionHandler:]
// ----- Deferral pattern (canvas-11 + bug-fix for the synchronous
// AppKit-main / EDT deadlock) -----------------------------------------
//
// WKUIDelegate selectors are invoked on the AppKit main thread.  Doing
// SwingUtilities.invokeAndWait directly from here deadlocks: the EDT
// shows a modal JOptionPane, which creates an NSWindow under the hood,
// which requires AppKit main thread work -- and AppKit main is blocked
// in invokeAndWait waiting for the EDT to return.  Classic.
//
// Fix: copy the completion handler block, return from the selector
// immediately, and run the JNI hop on a worker thread.  When Java
// returns the answer, dispatch_async back onto AppKit main to invoke
// the completion handler (WKWebView requires its completion handlers
// to fire on the thread they were delivered on -- AppKit main).  Page's
// JS thread stays suspended for the duration (the completion handler
// hasn't fired yet), which is exactly the JS-contract behaviour we want.
//
// Same deferral pattern Windows uses via GetDeferral + dispatch_to_thread
// (canvas-13).  Linux doesn't need it because the GTK pump thread is
// already decoupled from AWT's EDT thread.
//
// Block lifetime: blocks passed as ObjC method arguments are stack-
// allocated and become invalid once the selector returns.  Calling
// -copy moves the block to the heap and retains it; we balance with
// -release after we invoke (or skip invoking) it.
static void impl_run_alert(id self, SEL, id webView, id message,
                           id frame, id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");

    // Copy the completion handler block so it survives past selector
    // return.  See block-lifetime note above.
    id ch = msg(completionHandler, sel("copy"));

    // Capture string inputs synchronously while still on AppKit main;
    // std::string captures cross thread boundaries trivially without
    // having to retain NSStrings.
    std::string msg_utf8 = ns_string_to_utf8(message);
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        // No Java handler wired (yet, or after disposal).  Fire
        // completion synchronously with the safe default -- we're
        // already on AppKit main, no need for dispatch_async.
        ((void (^)(void))ch)();
        msg(ch, sel("release"));
        return;
    }

    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    // Hand off to a worker thread.  AppKit main returns immediately
    // so the EDT can use AppKit for the modal Swing dialog without
    // contention.  Detached thread because we never need to join.
    std::thread([jvm, cb, msg_utf8, page_url, frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            // JVM attach failed; can't call Java.  Still must fire
            // the completion handler so the page's JS thread resumes.
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(void))ch)();
                msg(ch, sel("release"));
            });
            return;
        }
        jstring jmsg = env->NewStringUTF(msg_utf8.c_str());
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onAlert",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            if (mid) {
                env->CallVoidMethod(cb, mid, jmsg, jpage, jframe);
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
        jvm->DetachCurrentThread();

        // Java returned.  Fire the completion handler on AppKit main
        // (WKWebView requires it) and release our copy of the block.
        dispatch_async(dispatch_get_main_queue(), ^{
            ((void (^)(void))ch)();
            msg(ch, sel("release"));
        });
    }).detach();
}

// IMP: -[WebviewEmbedUIDelegate webView:runJavaScriptConfirmPanelWithMessage:
//                               initiatedByFrame:completionHandler:]
static void impl_run_confirm(id self, SEL, id webView, id message,
                             id frame, id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    id ch = msg(completionHandler, sel("copy"));
    std::string msg_utf8 = ns_string_to_utf8(message);
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        ((void (^)(BOOL))ch)(NO);
        msg(ch, sel("release"));
        return;
    }
    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    std::thread([jvm, cb, msg_utf8, page_url, frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(BOOL))ch)(NO);
                msg(ch, sel("release"));
            });
            return;
        }
        jboolean result = JNI_FALSE;
        jstring jmsg = env->NewStringUTF(msg_utf8.c_str());
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onConfirm",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
            if (mid) {
                result = env->CallBooleanMethod(
                    cb, mid, jmsg, jpage, jframe);
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
        jvm->DetachCurrentThread();

        BOOL outcome = (result == JNI_TRUE ? YES : NO);
        dispatch_async(dispatch_get_main_queue(), ^{
            ((void (^)(BOOL))ch)(outcome);
            msg(ch, sel("release"));
        });
    }).detach();
}

// IMP: -[WebviewEmbedUIDelegate webView:runJavaScriptTextInputPanelWithPrompt:
//                               defaultText:initiatedByFrame:completionHandler:]
static void impl_run_prompt(id self, SEL, id webView, id prompt,
                            id defaultText, id frame,
                            id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    id ch = msg(completionHandler, sel("copy"));
    std::string msg_utf8 = ns_string_to_utf8(prompt);
    std::string default_utf8 = ns_string_to_utf8(defaultText);
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        ((void (^)(id))ch)(nil);
        msg(ch, sel("release"));
        return;
    }
    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    std::thread([jvm, cb, msg_utf8, default_utf8, page_url,
                 frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(id))ch)(nil);
                msg(ch, sel("release"));
            });
            return;
        }
        // Capture the result as a std::string with a "cancelled" flag
        // we send to the main-thread block.  std::string can't
        // distinguish "" from null, so use a separate bool.
        std::string result_utf8;
        bool cancelled = true;
        jstring jmsg = env->NewStringUTF(msg_utf8.c_str());
        jstring jdefault = env->NewStringUTF(default_utf8.c_str());
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());
        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onPrompt",
                "(Ljava/lang/String;Ljava/lang/String;"
                "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
            if (mid) {
                jstring jresult = (jstring)env->CallObjectMethod(
                    cb, mid, jmsg, jdefault, jpage, jframe);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    jresult = nullptr;
                }
                if (jresult) {
                    const char *cstr =
                        env->GetStringUTFChars(jresult, nullptr);
                    if (cstr) {
                        result_utf8 = cstr;
                        cancelled = false;
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
        jvm->DetachCurrentThread();

        // Build the NSString result on AppKit main before invoking
        // the completion handler -- nil means cancel (page sees null
        // per the JS contract), non-nil means the entered text.
        dispatch_async(dispatch_get_main_queue(), ^{
            id text = cancelled ? nil : ns_str(result_utf8.c_str());
            ((void (^)(id))ch)(text);
            msg(ch, sel("release"));
        });
    }).detach();
}

// IMP: -[WebviewEmbedUIDelegate webView:runOpenPanelWithParameters:
//                               initiatedByFrame:completionHandler:]
//
// _acceptedMIMETypes and _acceptedFileExtensions are documented in
// WebKit source and have been stable across macOS releases since
// 10.12, but they are NOT part of the public WKWebKit headers.  Probe
// them via the ObjC runtime's respondsToSelector: (see below) rather
// than blind invocation so a hypothetical future macOS that hides them
// yields empty arrays here and the default JFileChooser shows all
// files unfiltered (the page's own client-side accept validation
// continues to work).  We avoid @try/@catch because this file is
// compiled as plain C++, not Objective-C++.
static void impl_run_open_panel(id self, SEL, id webView, id parameters,
                                id frame, id completionHandler) {
    Engine *e = (Engine *)objc_getAssociatedObject(self, "eng");
    id ch = msg(completionHandler, sel("copy"));

    // Snapshot parameters on AppKit main.  WKOpenPanelParameters and
    // its private _acceptedMIMETypes / _acceptedFileExtensions
    // accessors must be invoked here, not from a worker thread, since
    // WKWebView's object graph isn't guaranteed thread-safe.
    BOOL multiple = NO;
    std::vector<std::string> mime_types;
    std::vector<std::string> ext_types;
    if (parameters) {
        multiple = msg<BOOL>(parameters, sel("allowsMultipleSelection"));
        // _acceptedMIMETypes / _acceptedFileExtensions are private
        // (underscore-prefixed) accessors on WKOpenPanelParameters that
        // have been stable since macOS 10.12.  Probe respondsToSelector:
        // rather than blindly invoking them so a hypothetical future
        // macOS that removes or renames the selectors degrades
        // gracefully (we pass an empty array to Java and the default
        // JFileChooser shows all files; the page's own client-side
        // `accept` validation still works).
        //
        // Probing via the ObjC runtime keeps this file as plain C++ --
        // the Objective-C++ @try/@catch syntax can't be used because
        // src_c/webview_embed.cpp is compiled with `c++`, not
        // `clang++ -x objective-c++`.
        SEL mime_sel = sel("_acceptedMIMETypes");
        if (msg<BOOL, SEL>(parameters, sel("respondsToSelector:"),
                           mime_sel)) {
            mime_types = ns_array_to_utf8_vector(
                msg<id>(parameters, mime_sel));
        }
        SEL ext_sel = sel("_acceptedFileExtensions");
        if (msg<BOOL, SEL>(parameters, sel("respondsToSelector:"),
                           ext_sel)) {
            ext_types = ns_array_to_utf8_vector(
                msg<id>(parameters, ext_sel));
        }
    }
    std::string page_url = page_url_utf8(webView);
    std::string frame_url = frame_url_utf8(frame, webView);

    if (!e || !e->dialog_callback) {
        ((void (^)(id))ch)(nil);
        msg(ch, sel("release"));
        return;
    }
    JavaVM *jvm = e->jvm;
    jobject cb = e->dialog_callback;

    std::thread([jvm, cb, multiple, mime_types, ext_types,
                 page_url, frame_url, ch]() {
        JNIEnv *env = nullptr;
        if (jvm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK
                || !env) {
            dispatch_async(dispatch_get_main_queue(), ^{
                ((void (^)(id))ch)(nil);
                msg(ch, sel("release"));
            });
            return;
        }

        // Build the input string-arrays in this worker (NewStringUTF
        // requires a JNIEnv).
        jclass strCls = env->FindClass("java/lang/String");
        auto vec_to_jarray = [&](const std::vector<std::string> &v) {
            jobjectArray a = env->NewObjectArray(
                (jsize)v.size(), strCls, nullptr);
            for (size_t i = 0; i < v.size(); i++) {
                jstring s = env->NewStringUTF(v[i].c_str());
                if (s) {
                    env->SetObjectArrayElement(a, (jsize)i, s);
                    env->DeleteLocalRef(s);
                }
            }
            return a;
        };
        jobjectArray jmimes = vec_to_jarray(mime_types);
        jobjectArray jexts = vec_to_jarray(ext_types);
        jstring jpage = env->NewStringUTF(page_url.c_str());
        jstring jframe = env->NewStringUTF(frame_url.c_str());

        // Capture chosen file paths as a std::vector<std::string> so
        // we can build the NSArray<NSURL*> back on AppKit main (NSURL
        // construction is technically thread-safe but staying on main
        // keeps the WKWebView completion-handler invocation simple).
        std::vector<std::string> chosen_paths;

        jclass cls = env->GetObjectClass(cb);
        if (cls) {
            jmethodID mid = env->GetMethodID(cls, "onFilePicker",
                "(Z[Ljava/lang/String;[Ljava/lang/String;"
                "Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;");
            if (mid) {
                jobjectArray jresult = (jobjectArray)env->CallObjectMethod(
                    cb, mid,
                    (jboolean)(multiple == YES ? JNI_TRUE : JNI_FALSE),
                    jmimes, jexts, jpage, jframe);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    jresult = nullptr;
                }
                if (jresult) {
                    jsize n = env->GetArrayLength(jresult);
                    chosen_paths.reserve((size_t)n);
                    for (jsize i = 0; i < n; i++) {
                        jstring js = (jstring)env->GetObjectArrayElement(
                            jresult, i);
                        if (!js) continue;
                        const char *cstr =
                            env->GetStringUTFChars(js, nullptr);
                        if (cstr) {
                            chosen_paths.emplace_back(cstr);
                            env->ReleaseStringUTFChars(js, cstr);
                        }
                        env->DeleteLocalRef(js);
                    }
                    env->DeleteLocalRef(jresult);
                }
            }
            env->DeleteLocalRef(cls);
        }
        if (jmimes) env->DeleteLocalRef(jmimes);
        if (jexts) env->DeleteLocalRef(jexts);
        if (jpage) env->DeleteLocalRef(jpage);
        if (jframe) env->DeleteLocalRef(jframe);
        if (strCls) env->DeleteLocalRef(strCls);
        jvm->DetachCurrentThread();

        // Build NSArray<NSURL*> on AppKit main and invoke completion.
        // Empty paths vector → nil → user cancelled (empty FileList).
        dispatch_async(dispatch_get_main_queue(), ^{
            id urls = nil;
            if (!chosen_paths.empty()) {
                id NSURLcls = objc_cls("NSURL");
                id arr = msg(objc_cls("NSMutableArray"),
                             sel("arrayWithCapacity:"),
                             (long)chosen_paths.size());
                for (const std::string &path : chosen_paths) {
                    id path_ns = ns_str(path.c_str());
                    id url = msg<id, id>(
                        NSURLcls, sel("fileURLWithPath:"), path_ns);
                    if (url) {
                        msg<void, id>(arr, sel("addObject:"), url);
                    }
                }
                long count = msg<long>(arr, sel("count"));
                if (count > 0) urls = arr;
            }
            ((void (^)(id))ch)(urls);
            msg(ch, sel("release"));
        });
    }).detach();
}

static Class get_webview_embed_ui_delegate_cls() {
    std::call_once(g_webview_embed_ui_delegate_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedUIDelegate", 0);
        class_addProtocol(c, objc_getProtocol("WKUIDelegate"));
        class_addMethod(
            c,
            sel("webView:runJavaScriptAlertPanelWithMessage:"
                "initiatedByFrame:completionHandler:"),
            (IMP)impl_run_alert, "v@:@@@@");
        class_addMethod(
            c,
            sel("webView:runJavaScriptConfirmPanelWithMessage:"
                "initiatedByFrame:completionHandler:"),
            (IMP)impl_run_confirm, "v@:@@@@");
        class_addMethod(
            c,
            sel("webView:runJavaScriptTextInputPanelWithPrompt:"
                "defaultText:initiatedByFrame:completionHandler:"),
            (IMP)impl_run_prompt, "v@:@@@@@");
        class_addMethod(
            c,
            sel("webView:runOpenPanelWithParameters:"
                "initiatedByFrame:completionHandler:"),
            (IMP)impl_run_open_panel, "v@:@@@@");
        objc_registerClassPair(c);
        g_webview_embed_ui_delegate_cls = c;
    });
    return g_webview_embed_ui_delegate_cls;
}

// Register (or clear, when cb is null) the Java WebViewDialogCallback
// for this engine.  Mirrors cocoa_set_focus_callback /
// cocoa_set_click_callback.  The WKUIDelegate IMPs above read
// e->dialog_callback on every dispatch, so installing the global ref
// here is the single place that wires Java handler into the selector
// hot path.
static void cocoa_set_dialog_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->dialog_callback) {
        env->DeleteGlobalRef(e->dialog_callback);
        e->dialog_callback = nullptr;
    }
    if (cb) {
        e->dialog_callback = env->NewGlobalRef(cb);
    }
}

// ---------------------------------------------------------------------------
// Mouse-down hook on WKWebView.
//
// We swizzle -[WKWebView mouseDown:], -[WKWebView rightMouseDown:], and
// -[WKWebView otherMouseDown:] so we can notify Java each time the user
// presses any mouse button inside the WebView.  This is the macOS half of
// the cross-platform native click hook that drives Swing-side popup
// dismissal: AWT's BasicPopupMenuUI MouseGrabber listener never sees
// clicks that land in the WKWebView because they reach it via the AppKit
// responder chain rather than through AWT's event queue, so an open
// JPopupMenu would otherwise stay open when the user clicked the
// WebView.
//
// Each swizzled implementation calls the original IMP FIRST so WebKit's
// normal click handling (link clicks, text selection, form interaction,
// becomeFirstResponder) is unaffected; the click callback fires
// afterwards.  Swizzling is class-wide: any WKWebView in the process is
// affected, including ones the host application created independently.
// The g_webview_map lookup returns nullptr for those and we silently
// skip the callback -- behaviour for unrelated WKWebViews is preserved.
//
// The Java callback runs on whatever thread AppKit drove the click on
// (the AppKit main thread for normal user input); Java-side callers MUST
// marshal to the EDT before touching Swing state.  See WebViewClickCallback
// for the contract.
// ---------------------------------------------------------------------------

typedef void (*VoidFromIdSelEvent)(id, SEL, id);
static VoidFromIdSelEvent g_orig_mouseDown = nullptr;
static VoidFromIdSelEvent g_orig_rightMouseDown = nullptr;
static VoidFromIdSelEvent g_orig_otherMouseDown = nullptr;
static std::once_flag g_click_swizzle_once;

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

static void swizzled_mouse_down(id self, SEL _cmd, id event) {
    if (g_orig_mouseDown) g_orig_mouseDown(self, _cmd, event);
    Engine *eng = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        auto it = g_webview_map.find(self);
        if (it != g_webview_map.end()) eng = it->second;
    }
    if (eng) fire_click_callback(eng);
}

static void swizzled_right_mouse_down(id self, SEL _cmd, id event) {
    if (g_orig_rightMouseDown) g_orig_rightMouseDown(self, _cmd, event);
    Engine *eng = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        auto it = g_webview_map.find(self);
        if (it != g_webview_map.end()) eng = it->second;
    }
    if (eng) fire_click_callback(eng);
}

static void swizzled_other_mouse_down(id self, SEL _cmd, id event) {
    if (g_orig_otherMouseDown) g_orig_otherMouseDown(self, _cmd, event);
    Engine *eng = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        auto it = g_webview_map.find(self);
        if (it != g_webview_map.end()) eng = it->second;
    }
    if (eng) fire_click_callback(eng);
}

static void install_click_swizzle() {
    std::call_once(g_click_swizzle_once, [] {
        Class wk = (Class)objc_cls("WKWebView");
        if (!wk) return;
        Method down = class_getInstanceMethod(wk, sel("mouseDown:"));
        Method rdown = class_getInstanceMethod(wk, sel("rightMouseDown:"));
        Method odown = class_getInstanceMethod(wk, sel("otherMouseDown:"));
        if (!down || !rdown || !odown) return;
        g_orig_mouseDown = (VoidFromIdSelEvent)method_setImplementation(
            down, (IMP)swizzled_mouse_down);
        g_orig_rightMouseDown = (VoidFromIdSelEvent)method_setImplementation(
            rdown, (IMP)swizzled_right_mouse_down);
        g_orig_otherMouseDown = (VoidFromIdSelEvent)method_setImplementation(
            odown, (IMP)swizzled_other_mouse_down);
    });
}

// Walk the responder chain from [window firstResponder] upward through
// superview looking for e->webview.  Runs on the AppKit main thread
// (invoked from the KVO observer below and from the post-window-attach
// recompute path).  Identical logic to the previous synchronous
// cocoa_is_first_responder probe -- correctly handles inner WebKit
// content views (NSResponder subclasses inside WKWebView) by walking
// up the view hierarchy until it either reaches e->webview (match) or
// runs out of superviews (no match).  Stores the result into
// e->is_first_responder so cocoa_is_first_responder can read it
// lock-free with no thread hop.
static void cocoa_recompute_first_responder_on_main(Engine *e) {
    if (!e || !e->webview) {
        if (e) e->is_first_responder.store(false);
        return;
    }
    id window = msg(e->webview, sel("window"));
    if (!window) {
        e->is_first_responder.store(false);
        return;
    }
    id fr = msg(window, sel("firstResponder"));
    if (!fr) {
        e->is_first_responder.store(false);
        return;
    }
    SEL is_kind_of_view = sel("isKindOfClass:");
    id view_cls = objc_cls("NSView");
    if (!msg<BOOL, id>(fr, is_kind_of_view, view_cls)) {
        // First responder isn't an NSView (e.g. NSWindow itself) -- not us.
        e->is_first_responder.store(false);
        return;
    }
    for (id v = fr; v; v = msg(v, sel("superview"))) {
        if (v == e->webview) {
            e->is_first_responder.store(true);
            return;
        }
    }
    e->is_first_responder.store(false);
}

// Returns 1 if the engine's WKWebView (or a descendant view in its
// subview hierarchy -- WebKit content view, etc.) is currently the
// first responder of its NSWindow.  Lock-free read of the mirrored
// state maintained by the KVO observer registered on
// NSWindow.firstResponder; no AppKit-main-thread hop, no deadlock
// surface.  Safe to call from any thread, but in practice fired only
// from the EDT (the editing-shortcut dispatcher in
// WebViewHeavyweightComponent).
static int cocoa_is_first_responder(Engine *e) {
    if (!e) return 0;
    return e->is_first_responder.load() ? 1 : 0;
}

// ---------------------------------------------------------------------------
// KVO observer for NSWindow.firstResponder + WKWebView.window.
//
// The Engine struct holds an std::atomic<bool> is_first_responder that
// mirrors AppKit-main-thread state for lock-free EDT reads.  Two key paths
// drive updates:
//   1. NSWindow.firstResponder fires for every focus transition anywhere
//      in the host window's responder hierarchy.  Each firing walks the
//      responder chain via cocoa_recompute_first_responder_on_main and
//      stores the result in the atomic.
//   2. WKWebView.window fires when the WKWebView is added to / removed
//      from / moved between NSWindows.  When the property goes non-nil,
//      we register the firstResponder observer on the new window and
//      recompute immediately.  When it changes to a different window we
//      unregister from the previous window and register on the new one.
//      When it goes nil (e.g. just before removeFromSuperview during
//      destroy) we unregister and clear the cache.
//
// The Objective-C class is registered exactly once per JVM via
// std::call_once -- same pattern as the existing WKWebView swizzles and
// the WebviewEmbedDelegate class registration.  Each engine creates one
// instance of the class and stores the back-pointer to its owning Engine*
// via objc_setAssociatedObject (OBJC_ASSOCIATION_ASSIGN -- Engine is not
// an Obj-C object, so the runtime won't try to retain/release it).
// ---------------------------------------------------------------------------

static std::once_flag g_kvo_observer_once;
static Class g_kvo_observer_cls = nil;

static const char KVO_ENGINE_KEY[] = "eng";
// Distinct context pointers let observeValueForKeyPath: tell the two
// key paths apart without string-comparing the keypath every fire.
static int kvo_ctx_first_responder = 0;
static int kvo_ctx_window = 0;

// Forward declaration: defined after cocoa_destroy_engine so the
// KVO observer can reference engine teardown helpers.
static void cocoa_kvo_register_on_window(Engine *e, id window);
static void cocoa_kvo_unregister_from_window(Engine *e);

static void kvo_observe_impl(id self, SEL /*_cmd*/, id /*keyPath*/,
                             id /*object*/, id /*change*/, void *context) {
    auto *e = (Engine *)objc_getAssociatedObject(self, KVO_ENGINE_KEY);
    if (!e) return;
    if (e->destroyed.load()) return;
    if (context == &kvo_ctx_first_responder) {
        cocoa_recompute_first_responder_on_main(e);
    } else if (context == &kvo_ctx_window) {
        // WKWebView's window keypath changed.  Re-aim the firstResponder
        // observer at the new window (or unregister if nil) and
        // recompute.
        id new_window = e->webview ? msg(e->webview, sel("window"))
                                   : (id)nullptr;
        if (new_window != e->observed_window) {
            cocoa_kvo_unregister_from_window(e);
            if (new_window) {
                cocoa_kvo_register_on_window(e, new_window);
            }
        }
        cocoa_recompute_first_responder_on_main(e);
    }
}

static void ensure_kvo_observer_class() {
    std::call_once(g_kvo_observer_once, [] {
        Class c = objc_allocateClassPair((Class)objc_cls("NSObject"),
                                         "WebviewEmbedKvoObserver", 0);
        // observeValueForKeyPath:ofObject:change:context: --
        // signature "v@:@@@^v"
        //   v   void
        //   @   id self
        //   :   SEL _cmd
        //   @   id keyPath  (NSString)
        //   @   id object
        //   @   id change   (NSDictionary)
        //   ^v  void *context
        class_addMethod(
            c,
            sel("observeValueForKeyPath:ofObject:change:context:"),
            (IMP)kvo_observe_impl,
            "v@:@@@^v");
        objc_registerClassPair(c);
        g_kvo_observer_cls = c;
    });
}

// Register the engine's KVO observer against `window`'s firstResponder
// key path.  Caller MUST hold the AppKit main thread.  Idempotent
// against repeated registration on the same window (the second call
// is a no-op).
static void cocoa_kvo_register_on_window(Engine *e, id window) {
    if (!e || !window) return;
    if (e->observed_window == window) return;
    if (!e->kvo_observer) return;
    msg<void, id, id, unsigned long, void *>(
        window,
        sel("addObserver:forKeyPath:options:context:"),
        e->kvo_observer,
        ns_str("firstResponder"),
        (unsigned long)0,           // no NSKeyValueObservingOptions flags
        &kvo_ctx_first_responder);
    e->observed_window = window;
}

// Unregister the engine's KVO observer from its currently-observed
// window (if any).  Caller MUST hold the AppKit main thread.
static void cocoa_kvo_unregister_from_window(Engine *e) {
    if (!e || !e->observed_window || !e->kvo_observer) return;
    msg<void, id, id, void *>(
        e->observed_window,
        sel("removeObserver:forKeyPath:context:"),
        e->kvo_observer,
        ns_str("firstResponder"),  // forKeyPath:
        &kvo_ctx_first_responder); // context:
    // Template params: (Ret=void, Args = id observer, id keyPath, void* context)
    e->observed_window = nullptr;
}

// Install the engine's KVO observer.  Allocates the observer object,
// associates it with the Engine pointer, attaches the WKWebView.window
// observer (so we react to window changes), and -- if the WKWebView
// already has a window -- registers the firstResponder observer on
// that window and seeds the atomic.  Caller MUST hold the AppKit main
// thread.
static void cocoa_kvo_install(Engine *e) {
    if (!e || !e->webview) return;
    ensure_kvo_observer_class();
    id observer = msg((id)g_kvo_observer_cls, sel("new"));
    if (!observer) return;
    objc_setAssociatedObject(observer, KVO_ENGINE_KEY, (id)e,
                             OBJC_ASSOCIATION_ASSIGN);
    e->kvo_observer = observer;
    msg<void, id, id, unsigned long, void *>(
        e->webview,
        sel("addObserver:forKeyPath:options:context:"),
        observer,
        ns_str("window"),
        (unsigned long)0,
        &kvo_ctx_window);
    id w = msg(e->webview, sel("window"));
    if (w) {
        cocoa_kvo_register_on_window(e, w);
        cocoa_recompute_first_responder_on_main(e);
    }
}

// Tear down the engine's KVO observer.  Reverses cocoa_kvo_install;
// caller MUST hold the AppKit main thread, and MUST call this BEFORE
// any view release in the destroy lambda (AppKit logs a warning and
// may crash if an observed object is released while observers are
// still registered).
static void cocoa_kvo_teardown(Engine *e) {
    if (!e || !e->kvo_observer) return;
    cocoa_kvo_unregister_from_window(e);
    if (e->webview) {
        msg<void, id, id, void *>(
            e->webview,
            sel("removeObserver:forKeyPath:context:"),
            e->kvo_observer,
            ns_str("window"),         // forKeyPath:
            &kvo_ctx_window);         // context:
    }
    objc_setAssociatedObject(e->kvo_observer, KVO_ENGINE_KEY, (id)nullptr,
                             OBJC_ASSOCIATION_ASSIGN);
    msg<void>(e->kvo_observer, sel("release"));
    e->kvo_observer = nullptr;
}

// ---------------------------------------------------------------------------
// Attach-completion callback.
//
// The Java side registers an AttachCallback bridge object via
// cocoa_set_attach_callback before the EmbeddedWebView factory returns.
// The macOS async attach epilogue inside cocoa_create_engine, on
// success or failure, calls cocoa_attach_signal_complete to publish the
// outcome.  Two ordering cases:
//   (a) Java registers the callback BEFORE the async epilogue fires
//       (typical case): cocoa_set_attach_callback stores the global ref;
//       cocoa_attach_signal_complete sees the stored callback and fires
//       it from the main thread.
//   (b) Java registers the callback AFTER the async epilogue fires
//       (race window narrow but possible): the async epilogue stores the
//       resolution state without anyone to call; cocoa_set_attach_callback
//       sees attach_resolved==true and fires the callback from the EDT.
// Both paths are guarded by attach_callback_mutex.  The callback fires
// exactly once -- both paths null out the callback fields after firing.
// ---------------------------------------------------------------------------

// Caller MUST hold e->attach_callback_mutex.  Invokes the registered
// Java callback's onResolved(boolean, String) method and clears the
// stored callback fields.  Attaches the current thread to the JVM if
// needed (mirrors the fire_focus_callback pattern).
static void cocoa_attach_invoke_callback_locked(Engine *e) {
    if (!e || !e->attach_callback || !e->attach_callback_cls) return;
    bool ok = e->attach_ok;
    std::string msg_text = e->attach_failure_message;
    JavaVM *jvm = e->jvm;
    if (!jvm) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread((void **)&env, nullptr);
        detach = true;
    }
    if (env) {
        jmethodID m = env->GetMethodID(
            e->attach_callback_cls, "onResolved",
            "(ZLjava/lang/String;)V");
        if (m) {
            jstring js = (!ok && !msg_text.empty())
                ? env->NewStringUTF(msg_text.c_str()) : nullptr;
            env->CallVoidMethod(e->attach_callback, m,
                                (jboolean)(ok ? JNI_TRUE : JNI_FALSE), js);
            if (js) env->DeleteLocalRef(js);
        }
        env->DeleteGlobalRef(e->attach_callback);
        env->DeleteGlobalRef(e->attach_callback_cls);
    }
    e->attach_callback = nullptr;
    e->attach_callback_cls = nullptr;
    if (detach) jvm->DetachCurrentThread();
}

// Called from the async attach epilogue inside cocoa_create_engine
// (on the AppKit main thread).  Records the outcome and fires the
// callback if one is already registered.
static void cocoa_attach_signal_complete(Engine *e, bool ok,
                                         std::string failure_message) {
    if (!e) return;
    std::lock_guard<std::mutex> lk(e->attach_callback_mutex);
    if (e->attach_resolved) return;  // idempotent
    e->attach_resolved = true;
    e->attach_ok = ok;
    e->attach_failure_message = std::move(failure_message);
    if (e->attach_callback) {
        cocoa_attach_invoke_callback_locked(e);
    }
}

// Java entry point: register the AttachCallback bridge object.  Called
// from EmbeddedWebView.attach on the EDT immediately after
// webview_embed_create returns.  Stores a JNI global ref; if the
// async attach epilogue has already completed (case (b) above), fires
// the callback immediately.
static void cocoa_set_attach_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e || !env) return;
    std::lock_guard<std::mutex> lk(e->attach_callback_mutex);
    // If we already have a callback registered, drop the old one
    // (defensive -- the Java factory wires this exactly once, but make
    // the contract robust).
    if (e->attach_callback) {
        env->DeleteGlobalRef(e->attach_callback);
        e->attach_callback = nullptr;
    }
    if (e->attach_callback_cls) {
        env->DeleteGlobalRef(e->attach_callback_cls);
        e->attach_callback_cls = nullptr;
    }
    if (cb) {
        e->attach_callback = env->NewGlobalRef(cb);
        jclass cls = env->GetObjectClass(cb);
        e->attach_callback_cls = (jclass)env->NewGlobalRef(cls);
        env->DeleteLocalRef(cls);
    }
    if (e->attach_resolved && e->attach_callback) {
        cocoa_attach_invoke_callback_locked(e);
    }
}

static void cocoa_set_focus_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->focus_callback) {
        env->DeleteGlobalRef(e->focus_callback);
        e->focus_callback = nullptr;
    }
    if (cb) {
        e->focus_callback = env->NewGlobalRef(cb);
    }
}

// Register (or clear, when cb is null) the Java WebViewClickCallback for
// this engine.  The swizzled mouseDown: / rightMouseDown: /
// otherMouseDown: implementations call fire_click_callback for any
// WKWebView found in g_webview_map; that function reads this field, so
// installing the global ref here is the single place that wires Java
// callbacks into the swizzled hot path.  Mirrors cocoa_set_focus_callback.
static void cocoa_set_click_callback(Engine *e, JNIEnv *env, jobject cb) {
    if (!e) return;
    if (e->click_callback) {
        env->DeleteGlobalRef(e->click_callback);
        e->click_callback = nullptr;
    }
    if (cb) {
        e->click_callback = env->NewGlobalRef(cb);
    }
}

// Synchronous prologue + async epilogue.  Returns the freshly-allocated
// Engine* (cast to jlong by the JNI export) immediately after the JAWT
// surface-layers handoff; the WKWebView creation, host-NSView discovery,
// addSubview:, configuration, and KVO observer install run later on the
// AppKit main thread.  The async epilogue calls cocoa_attach_signal_
// complete on completion to drive the Java-side AttachState transition.
//
// Only synchronous failure (JAWT lock failure) returns nullptr; every
// other failure surfaces via the attach-completion callback's onResolved
// (false, "<message>") path so the Java side can observe it through a
// registered WebViewAttachListener.
//
// The C++ Engine struct itself is NOT deleted on async-attach failure --
// the contract is that the Java side owns the EmbeddedWebView wrapper
// and will eventually call dispose() (typically via removeNotify in the
// heavyweight component), which triggers cocoa_destroy_engine to free
// the Engine.  The async failure path therefore leaves the Engine in a
// partially-initialised state but with destroyed==false, so the user
// can still call dispose() to clean up.  destroyed is set to true only
// inside cocoa_destroy_engine.
static Engine *cocoa_create_engine(JNIEnv *env, jobject parentComponent,
                                   jlong /*display*/, jint debug) {
    auto *e = new Engine();
    env->GetJavaVM(&e->jvm);
    e->debug = debug != 0;

    // Resolve the JAWT surface layers object up front, then release the
    // surface lock immediately -- holding it across a hop to the AppKit
    // main thread (the async epilogue below) could deadlock with
    // AppKit's redraw loop.  This is the only step that requires the
    // calling thread's JNIEnv, so it stays in the synchronous prologue.
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

    // Async epilogue: AppKit-side setup on the main thread.  Captures e
    // by value (pointer); never blocks the EDT.  dispatch_get_main_queue
    // is serial FIFO, so subsequent cocoa_navigate / cocoa_eval / etc.
    // blocks enqueued by the EDT before the user's first listener call
    // are guaranteed to fire AFTER this block on the main thread.  Each
    // of those blocks already null-checks e->webview / e->manager and
    // silently no-ops if cleared -- so an op enqueued before the
    // epilogue creates the WKWebView simply finds it ready by the time
    // it fires.
    cocoa_run_on_main_async([e] {
        if (e->destroyed.load()) {
            // The user called dispose() between the prologue returning
            // and this block firing.  The destroy lambda is enqueued
            // AFTER this block; bail without doing AppKit-side work so
            // the destroy lambda has nothing to tear down.
            cocoa_attach_signal_complete(e, false,
                "EmbeddedWebView disposed before attach completed");
            return;
        }
        // Install the WKWebView first-responder swizzle once per JVM
        // BEFORE creating the WKWebView, so any becomeFirstResponder
        // calls during init are routed through our hook from the start.
        install_focus_swizzle();
        // Same reasoning for the mouseDown: / rightMouseDown: /
        // otherMouseDown: swizzle that drives native click notifications
        // back to Swing (used for outside-click popup dismissal -- see
        // Operation 13 of the heavyweight-embedding Canvas).
        install_click_swizzle();

        e->config = msg(objc_cls("WKWebViewConfiguration"), sel("new"));
        e->manager = msg(e->config, sel("userContentController"));
        id wv = msg(objc_cls("WKWebView"), sel("alloc"));
        wv = msg<id, CGRect, id>(
            wv, sel("initWithFrame:configuration:"),
            CGRectMake(0, 0, 800, 600), e->config);
        if (!wv) {
            // Release the partial AppKit allocations so they don't leak
            // before reporting the failure.  e->manager is an
            // autoreleased getter from config so does not need explicit
            // release; e->config does.
            e->manager = nullptr;
            if (e->config) {
                msg<void>(e->config, sel("release"));
                e->config = nullptr;
            }
            cocoa_attach_signal_complete(e, false,
                "WKWebView allocation failed");
            return;
        }
        e->webview = wv;

        // Register the WKWebView in the engine map so the swizzled
        // responder hooks can find their Engine pointer.
        {
            std::lock_guard<std::mutex> lk(g_webview_map_mutex);
            g_webview_map[e->webview] = e;
        }

        // Find a hostable NSView.  Walk up the windowLayer's superlayer
        // chain and use whatever NSView class we encounter to reach the
        // owning NSWindow.contentView -- the layer-only AWT design used by
        // Corretto 8 macOS arm64 has no per-Canvas AWT NSView, so we
        // attach the WKWebView to contentView and convert windowLayer's
        // frame on every setBounds to keep it overlaid on the canvas.  If
        // we happen to find an AWT-named view (other JDK layouts) we use
        // it directly; coordinates there are already canvas-relative.
        id ns_view_cls = objc_cls("NSView");
        SEL is_kind_of_class = sel("isKindOfClass:");
        id window_layer = e->surface_layers
            ? msg(e->surface_layers, sel("windowLayer"))
            : (id)nullptr;
        id host = nullptr;
        const char *host_kind = "(none)";
        bool host_is_awt = false;
        for (id l = window_layer; l; l = msg(l, sel("superlayer"))) {
            id ld = msg(l, sel("delegate"));
            if (!ld) continue;
            if (!msg<BOOL>(ld, is_kind_of_class, ns_view_cls)) continue;
            Class cls = object_getClass(ld);
            const char *cls_name = cls ? class_getName(cls) : "<unknown>";
            if (std::strstr(cls_name, "AWT") != nullptr) {
                host = ld;
                host_kind = cls_name;
                host_is_awt = true;
                break;
            }
            // Not an AWT view -- treat this as a stepping stone to
            // NSWindow.contentView.  We don't break here because a later
            // (further-up) layer might have an AWT NSView delegate.
            if (host == nullptr) {
                id window = msg(ld, sel("window"));
                if (window) {
                    id cv = msg(window, sel("contentView"));
                    if (cv) {
                        host = cv;
                        host_kind = "NSWindow.contentView";
                    }
                }
            }
            fprintf(stderr,
                "[webview-embed] Found NSView %s; %s\n", cls_name,
                host_is_awt ? "using directly" :
                (host ? "deferring to NSWindow.contentView" : "no window"));
        }

        if (host != nullptr) {
            msg<void>(host, sel("retain"));
            e->host_view = host;
            e->host_is_awt = host_is_awt;
            // contentView is often not layer-backed until something forces
            // it; make sure it is so AppKit composites WKWebView properly.
            msg<void, BOOL>(host, sel("setWantsLayer:"), YES);
            msg<void, id>(host, sel("addSubview:"), e->webview);
            fprintf(stderr,
                "[webview-embed] WKWebView added as subview of %s at %p\n",
                host_kind, host);
            // Hide the WKWebView until the first setBounds positions it
            // correctly; otherwise the placeholder 800x600 frame from init
            // shows up at the bottom of contentView (Cocoa origin) and
            // briefly covers the URL bar.
            msg<void, CGRect>(e->webview, sel("setFrame:"),
                              CGRectMake(0, 0, 0, 0));
        } else if (e->surface_layers) {
            // Layer-only fallback (won't render WKWebView content, but
            // keeps the API surface intact for layer-friendly engines).
            msg<void, BOOL>(e->webview, sel("setWantsLayer:"), YES);
            id layer = msg(e->webview, sel("layer"));
            msg<void, id>(e->surface_layers, sel("setLayer:"), layer);
            fprintf(stderr,
                "[webview-embed] WARNING: could not locate any host NSView; "
                "falling back to layer-only attach. WKWebView content will "
                "not render in this mode.\n");
        }

        // External-message bridge: register a script message handler.
        // The ObjC delegate class is registered exactly once per JVM (see
        // get_webview_embed_delegate_cls); every engine instantiates a
        // fresh delegate object from the cached Class.
        Class delegate_cls = get_webview_embed_delegate_cls();
        id delegate = msg((id)delegate_cls, sel("new"));
        objc_setAssociatedObject(delegate, "eng", (id)e, OBJC_ASSOCIATION_ASSIGN);
        msg<void, id, id>(e->manager,
                          sel("addScriptMessageHandler:name:"), delegate,
                          ns_str("external"));

        // Browser-dialog bridge: install a WKUIDelegate so JS-initiated
        // alert / confirm / prompt and <input type=file> requests flow
        // through Java (DialogDispatcher → WebViewDialogHandler) instead
        // of being silently dropped (default behaviour when uiDelegate
        // is nil).  Each engine constructs a fresh delegate object from
        // the cached Class so the per-engine Engine pointer can be
        // stashed via objc_setAssociatedObject for the selector IMPs to
        // recover.
        Class ui_delegate_cls = get_webview_embed_ui_delegate_cls();
        id ui_delegate = msg((id)ui_delegate_cls, sel("new"));
        objc_setAssociatedObject(
            ui_delegate, "eng", (id)e, OBJC_ASSOCIATION_ASSIGN);
        msg<void, id>(e->webview, sel("setUIDelegate:"), ui_delegate);
        // We hold the only strong ref to the delegate (the WKWebView's
        // uiDelegate is a weak reference per WebKit convention).  Stash
        // it on the engine so cocoa_destroy_engine can release it.
        e->ui_delegate = ui_delegate;
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
            // macOS 13.3+ exposes -[WKWebView setInspectable:] as a
            // public BOOL property; enabling it exposes the Web
            // Inspector via the Safari Develop menu (remote inspection)
            // and is required for right-click -> Inspect Element to
            // function on those OS versions.  On macOS 12.x and earlier
            // the selector does not exist and is silently skipped --
            // the legacy developerExtrasEnabled flag alone is
            // sufficient for in-process inspection there.
            if (msg<BOOL>(e->webview, sel("respondsToSelector:"),
                          sel("setInspectable:"))) {
                msg<void, BOOL>(e->webview, sel("setInspectable:"), YES);
            }
        }

        // Install the KVO observer on the WKWebView's window keypath --
        // when the WKWebView lands in an NSWindow, the observer
        // registers a firstResponder observer on that window and seeds
        // the cached atomic so cocoa_is_first_responder can read it
        // lock-free.  See cocoa_kvo_install above.
        cocoa_kvo_install(e);

        // Signal attach completion.  cocoa_attach_signal_complete fires
        // the Java AttachCallback immediately if one is already
        // registered (typical case -- the Java factory installs the
        // callback synchronously before any wider event-loop time slice
        // elapses); otherwise it just stores the resolution, and
        // cocoa_set_attach_callback fires it when the registration
        // arrives.
        cocoa_attach_signal_complete(e, true, "");
    });
    return e;
}

// Asynchronous engine destroy.  Returns immediately on the calling
// thread (typically the EDT) after a small Java-side cleanup; the
// AppKit teardown, view-hierarchy removal, KVO observer unregister,
// binding global-ref release, and finally `delete e` run on the
// AppKit main thread in a single async-on-main lambda.
//
// Pre-async cleanup (calling thread):
//   1. Erase from g_webview_map so the swizzled responder / mouse hooks
//      cannot find the engine again.  This window MUST be as small as
//      possible -- the calling thread runs this synchronously before
//      any subsequent async ops can be enqueued.
//   2. DeleteGlobalRef on focus_callback and click_callback so the
//      hooks (which had read the fields up to this point) cannot fire
//      Java callbacks against freed refs.  Uses the calling thread's
//      JNIEnv via attach/detach.
//
// Async cleanup (AppKit main thread, FIFO after any previously-enqueued
// per-engine op):
//   1. Set destroyed=true as the FIRST action so any LATER fires of
//      previously-enqueued lambdas (defence-in-depth -- FIFO ordering
//      makes this impossible in normal operation) see the flag.
//   2. Tear down the KVO observer BEFORE any view release (AppKit logs
//      a warning if an observed object is released while observers are
//      still registered).
//   3. removeFromSuperview, release retained AppKit objects.
//   4. Drain e->bindings (releasing the per-binding Java global refs).
//   5. Release any still-pending attach callback global ref (the
//      typical case is that it was already cleared inside attach
//      resolution, but a never-resolved attach + dispose pattern would
//      land here).
//   6. delete e.
static void cocoa_destroy_engine(Engine *e) {
    if (!e) return;
    // Drop the webview from the engine map BEFORE any teardown work --
    // the swizzled responder hooks can fire at any moment during destroy
    // (AppKit unwinds the view hierarchy and resigns first responder),
    // and we don't want them invoking a callback into a freed Engine.
    if (e->webview) {
        std::lock_guard<std::mutex> lk(g_webview_map_mutex);
        g_webview_map.erase(e->webview);
    }
    // Drop the focus-callback global ref (if any) on the EDT-driven
    // thread that holds the JNIEnv -- we cannot delete a global ref
    // from inside the AppKit-main lambda below without re-attaching.
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
    // Same treatment for the click-callback global ref.  Cleared after
    // the webview map entry above so the swizzled mouseDown: hooks read
    // a null field instead of a freed ref if they race against destroy.
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
    // Drop the dialog-callback global ref BEFORE the async teardown
    // lambda runs, so any in-flight WKUIDelegate selector observes a
    // null callback field and invokes its completion handler with the
    // safe default (returning the WebKit JS thread cleanly).  Released
    // here -- outside the async lambda -- because the lambda runs on
    // the AppKit main thread later and we still have the EDT JNIEnv
    // available right now (matches the click_callback cleanup above).
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
    cocoa_run_on_main_async([e] {
        // Mark destroyed FIRST.  Any LATER-firing lambdas that read this
        // flag short-circuit; FIFO ordering on the main queue makes
        // "later" impossible in normal operation but the flag is the
        // canvas-mandated belt-and-suspenders.
        e->destroyed.store(true);

        // KVO observer unregistration MUST happen BEFORE any view
        // release -- AppKit warns (and may crash) if an observed object
        // is released with observers still attached.
        cocoa_kvo_teardown(e);


        if (e->webview) {
            // Clear the WKWebView's uiDelegate BEFORE releasing the
            // WKWebView so any in-flight delegate selector observes
            // the cleared field (WKWebView holds a weak reference to
            // uiDelegate per Apple's convention).
            msg<void, id>(e->webview, sel("setUIDelegate:"), (id)nullptr);
            // If we added the WKWebView as a subview, remove it before
            // releasing so AppKit unwinds the view hierarchy cleanly.
            msg<void>(e->webview, sel("removeFromSuperview"));
        }
        if (e->ui_delegate) {
            msg<void>(e->ui_delegate, sel("release"));
            e->ui_delegate = nullptr;
        }
        if (e->host_view) {
            msg<void>(e->host_view, sel("release"));
            e->host_view = nullptr;
        }
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

        // Drain e->bindings on the main thread.  Each binding holds
        // two Java global refs (the callback and its class); attach to
        // the JVM via the engine's stored JavaVM* to release them.
        for (auto &kv : e->bindings) {
            Binding *b = kv.second;
            JNIEnv *env2 = nullptr;
            bool detach = false;
            if (e->jvm && e->jvm->GetEnv((void **)&env2, JNI_VERSION_1_6) != JNI_OK) {
                e->jvm->AttachCurrentThread((void **)&env2, nullptr);
                detach = true;
            }
            if (env2) {
                env2->DeleteGlobalRef(b->fn);
                env2->DeleteGlobalRef(b->cls);
            }
            if (detach && e->jvm) e->jvm->DetachCurrentThread();
            delete b;
        }
        e->bindings.clear();

        // Release any still-pending attach-callback global ref.  Under
        // normal flow cocoa_attach_signal_complete cleared this when
        // the callback fired, but a never-resolved-then-disposed
        // engine (rare: user disposes while the async epilogue's
        // destroyed-check above bailed) would leave it set.
        {
            std::lock_guard<std::mutex> lk(e->attach_callback_mutex);
            if (e->attach_callback || e->attach_callback_cls) {
                JNIEnv *env2 = nullptr;
                bool detach = false;
                if (e->jvm && e->jvm->GetEnv((void **)&env2, JNI_VERSION_1_6) != JNI_OK) {
                    e->jvm->AttachCurrentThread((void **)&env2, nullptr);
                    detach = true;
                }
                if (env2) {
                    if (e->attach_callback) env2->DeleteGlobalRef(e->attach_callback);
                    if (e->attach_callback_cls) env2->DeleteGlobalRef(e->attach_callback_cls);
                }
                e->attach_callback = nullptr;
                e->attach_callback_cls = nullptr;
                if (detach && e->jvm) e->jvm->DetachCurrentThread();
            }
        }
        delete e;
    });
}

// Update the WKWebView's frame so it overlays exactly the AWT canvas
// region.  When host_view is a per-Canvas AWT NSView its bounds already
// match the canvas, so the WKWebView just fills it.  Otherwise host_view
// is NSWindow.contentView and the caller's (x,y,w,h) -- the canvas
// position in NSWindow content-pane coords with AWT's top-left origin --
// is translated into Cocoa's bottom-left coords.
static void cocoa_set_bounds(Engine *e, int x, int y, int w, int h) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview || !e->host_view) return;
        CGFloat fx = (CGFloat)x;
        CGFloat fy = (CGFloat)y;
        CGFloat fw = (CGFloat)w;
        CGFloat fh = (CGFloat)h;
        if (e->host_is_awt) {
            msg<void, CGRect>(e->webview, sel("setFrame:"),
                              CGRectMake(0, 0, fw, fh));
            return;
        }
        CGRect b = msg_stret<CGRect>(e->host_view, sel("bounds"));
        BOOL flipped = msg<BOOL>(e->host_view, sel("isFlipped"));
        CGFloat outY = flipped
            ? fy
            : (b.origin.y + b.size.height - fy - fh);
        msg<void, CGRect>(e->webview, sel("setFrame:"),
                          CGRectMake(b.origin.x + fx, outY, fw, fh));
    });
}

static void cocoa_navigate(Engine *e, std::string url) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        // WKWebView's loadRequest: silently refuses data: URLs (a
        // long-standing WKWebView restriction) -- the page renders blank
        // with no error.  Route data:text/html through
        // loadHTMLString:baseURL: instead, decoding the body from base64
        // or percent-encoding as the prefix indicates.
        static const std::string DATA_HTML = "data:text/html";
        if (url.compare(0, DATA_HTML.size(), DATA_HTML) == 0) {
            size_t comma = url.find(',');
            if (comma != std::string::npos) {
                std::string meta = url.substr(0, comma);
                std::string body = url.substr(comma + 1);
                id html = nullptr;
                if (meta.find(";base64") != std::string::npos) {
                    id b64 = ns_str(body.c_str());
                    id data = msg<id, id, unsigned long>(
                        msg<id>(objc_cls("NSData"), sel("alloc")),
                        sel("initWithBase64EncodedString:options:"),
                        b64, (unsigned long)0);
                    if (data) {
                        // NSUTF8StringEncoding == 4
                        html = msg<id, id, unsigned long>(
                            msg<id>(objc_cls("NSString"), sel("alloc")),
                            sel("initWithData:encoding:"),
                            data, (unsigned long)4);
                    }
                } else {
                    id raw = ns_str(body.c_str());
                    id decoded = msg<id>(
                        raw, sel("stringByRemovingPercentEncoding"));
                    html = decoded ? decoded : raw;
                }
                if (html) {
                    msg<void, id, id>(e->webview,
                                      sel("loadHTMLString:baseURL:"),
                                      html, (id)nullptr);
                    return;
                }
            }
        }
        id nsurl = msg<id, id>(objc_cls("NSURL"), sel("URLWithString:"),
                               ns_str(url.c_str()));
        id req = msg<id, id>(objc_cls("NSURLRequest"),
                             sel("requestWithURL:"), nsurl);
        msg<void, id>(e->webview, sel("loadRequest:"), req);
    });
}

static void cocoa_init_script(Engine *e, std::string js) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->manager) return;
        id script = msg(objc_cls("WKUserScript"), sel("alloc"));
        script = msg<id, id, long, BOOL>(
            script, sel("initWithSource:injectionTime:forMainFrameOnly:"),
            ns_str(js.c_str()), (long)0, YES);
        msg<void, id>(e->manager, sel("addUserScript:"), script);
    });
}

static void cocoa_eval(Engine *e, std::string js) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        msg<void, id, id>(e->webview,
                          sel("evaluateJavaScript:completionHandler:"),
                          ns_str(js.c_str()), (id)nullptr);
    });
}

static void cocoa_set_visible(Engine *e, bool visible) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        msg<void, BOOL>(e->webview, sel("setHidden:"), visible ? NO : YES);
    });
}

static void cocoa_request_focus(Engine *e) {
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        id win = msg(e->webview, sel("window"));
        if (win) {
            msg<BOOL, id>(win, sel("makeFirstResponder:"), e->webview);
        }
    });
}

// Convert the per-engine binding registration to async-on-main so it
// serialises against the script-message-handler delegate's reads of
// e->bindings (which also run on main).  FIFO main-queue ordering
// guarantees the bind block fires AFTER the attach epilogue (which
// creates the WKWebView and sets up the script-message-handler) and
// BEFORE any cocoa_navigate enqueued by the caller after the bind --
// so the binding is registered in e->bindings before the page can call
// it from JS.
static void cocoa_bind(Engine *e, Binding *b) {
    if (!e || !b) {
        if (b) delete b;
        return;
    }
    cocoa_run_on_main_async([e, b] {
        if (e->destroyed.load()) {
            // Engine destroyed before this bind could fire.  Release
            // the Binding's Java global refs and delete it; no engine
            // to attach it to.
            JNIEnv *env = nullptr;
            bool detach = false;
            if (e->jvm && e->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
                e->jvm->AttachCurrentThread((void **)&env, nullptr);
                detach = true;
            }
            if (env) {
                if (b->fn) env->DeleteGlobalRef(b->fn);
                if (b->cls) env->DeleteGlobalRef(b->cls);
            }
            if (detach && e->jvm) e->jvm->DetachCurrentThread();
            delete b;
            return;
        }
        e->bindings[b->name] = b;
    });
}

// macOS has no public API to programmatically open the Web Inspector; the
// only public entry points are right-click -> Inspect Element (gated by
// developerExtrasEnabled, already set in debug mode) and Safari ->
// Develop menu (gated by setInspectable:YES, also set in debug mode).
// Returning 0 signals to the Java side that no programmatic open
// happened, so the boolean openDevTools() return surfaces the macOS
// limitation honestly.
static int cocoa_open_devtools(Engine *e) {
    (void)e;
    return 0;
}

// Dispatch Cut/Copy/Paste/SelectAll directly to the embedded WKWebView.
// Initial design used [NSApp sendAction:... to:nil from:webview] so the
// responder chain would route to the inner focused DOM element, but in
// the AWT-embedded setup (WKWebView parented under NSWindow.contentView,
// which is AWT's NSView) the AppKit first responder is not reliably the
// WKWebView -- AWT keeps system focus on its own view -- so the
// responder walk never reaches WKWebView and the action no-ops.
//
// Sending the action directly to WKWebView side-steps the chain entirely.
// WKWebView's implementations of cut:/copy:/paste:/selectAll: delegate to
// the WebKit page's current selection / focused element internally, so
// the operation hits the correct in-page target regardless of AppKit
// first-responder state.  Guard with respondsToSelector: so a missing
// selector on an older SDK fails silently instead of aborting.
//
// cmdId values are the EditingCommand contract: 1=CUT, 2=COPY, 3=PASTE,
// 4=SELECT_ALL.  Unknown cmdIds are silently dropped.
static void cocoa_execute_editing_command(Engine *e, int cmdId) {
    if (!e) return;
    // No early bail on e->webview: the engine may still be in async
    // attach (e->webview not yet set) -- FIFO ordering on the main
    // queue means the lambda below sees a populated e->webview by the
    // time it fires.
    SEL action = nullptr;
    const char *name = nullptr;
    switch (cmdId) {
        case 1: action = sel("cut:");        name = "cut:";        break;
        case 2: action = sel("copy:");       name = "copy:";       break;
        case 3: action = sel("paste:");      name = "paste:";      break;
        case 4: action = sel("selectAll:");  name = "selectAll:";  break;
        default: return;
    }
    cocoa_run_on_main_async([=] {
        if (!e || e->destroyed.load()) return;
        if (!e->webview) return;
        if (!msg<BOOL, SEL>(e->webview, sel("respondsToSelector:"),
                            action)) {
            if (getenv("WEBVIEW_DEBUG_SHORTCUT")) {
                fprintf(stderr,
                    "[webview-editing-shortcut] cocoa: WKWebView does not "
                    "respond to %s, dropping\n", name);
            }
            return;
        }
        if (getenv("WEBVIEW_DEBUG_SHORTCUT")) {
            fprintf(stderr,
                "[webview-editing-shortcut] cocoa: dispatching %s "
                "directly to WKWebView %p\n", name, (void *)e->webview);
        }
        msg<void, id>(e->webview, action, (id)nullptr);
    });
}

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

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1visible
  (JNIEnv *, jclass, jlong wv, jint visible) {
#ifdef WEBVIEW_GTK
    embed::gtk_set_visible((Engine *)wv, visible != 0);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_visible((Engine *)wv, visible != 0);
#else
    (void)wv; (void)visible;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1request_1focus
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    embed::gtk_request_focus((Engine *)wv);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_request_focus((Engine *)wv);
#else
    (void)wv;
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
    embed::cocoa_run_on_main_async(fn);
#endif
#else
    (void)env; (void)wv; (void)callback;
#endif
}

// ---------------------------------------------------------------------------
// Offscreen / lightweight JNI exports (Linux-only for now).
// macOS and Windows return 0 / no-op from these entry points; their
// lightweight implementations are scaffolded but not yet wired.
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1create
  (JNIEnv *env, jclass, jint w, jint h, jint debug) {
#ifdef WEBVIEW_GTK
    return (jlong)embed::gtk_off_create_engine(env, (int)w, (int)h, debug);
#else
    (void)env; (void)w; (void)h; (void)debug;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1destroy
  (JNIEnv *, jclass, jlong peer) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_destroy_engine((embed::OffEngine *)peer);
#else
    (void)peer;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1resize
  (JNIEnv *, jclass, jlong peer, jint w, jint h) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_resize((embed::OffEngine *)peer, (int)w, (int)h);
#else
    (void)peer; (void)w; (void)h;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1navigate
  (JNIEnv *env, jclass, jlong peer, jstring url) {
#ifdef WEBVIEW_GTK
    const char *u = env->GetStringUTFChars(url, nullptr);
    embed::gtk_off_navigate((embed::OffEngine *)peer, u ? u : "");
    env->ReleaseStringUTFChars(url, u);
#else
    (void)env; (void)peer; (void)url;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1snapshot
  (JNIEnv *env, jclass, jlong peer, jintArray pixels, jint w, jint h) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_snapshot_into((embed::OffEngine *)peer, env, pixels, w, h);
#else
    (void)env; (void)peer; (void)pixels; (void)w; (void)h;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1button
  (JNIEnv *, jclass, jlong peer, jint press, jint x, jint y, jint button,
   jint modifiers, jint click_count) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_mouse_button((embed::OffEngine *)peer, press != 0,
                                (int)x, (int)y, (int)button,
                                (int)modifiers, (int)click_count);
#else
    (void)peer; (void)press; (void)x; (void)y; (void)button;
    (void)modifiers; (void)click_count;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1motion
  (JNIEnv *, jclass, jlong peer, jint x, jint y, jint modifiers) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_mouse_motion((embed::OffEngine *)peer,
                                (int)x, (int)y, (int)modifiers);
#else
    (void)peer; (void)x; (void)y; (void)modifiers;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1mouse_1scroll
  (JNIEnv *, jclass, jlong peer, jint x, jint y, jdouble dx, jdouble dy,
   jint modifiers) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_mouse_scroll((embed::OffEngine *)peer,
                                (int)x, (int)y, (double)dx, (double)dy,
                                (int)modifiers);
#else
    (void)peer; (void)x; (void)y; (void)dx; (void)dy; (void)modifiers;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1key_1event
  (JNIEnv *, jclass, jlong peer, jint press, jint keyval, jint modifiers,
   jint is_modifier_key) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_key_event((embed::OffEngine *)peer, press != 0,
                             (int)keyval, (int)modifiers,
                             is_modifier_key != 0);
#else
    (void)peer; (void)press; (void)keyval; (void)modifiers;
    (void)is_modifier_key;
#endif
}

// ---------------------------------------------------------------------------
// DevTools open + offscreen JS bridge / dispatch JNI exports.
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1open_1devtools
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    return (jint)embed::gtk_open_devtools((Engine *)wv);
#elif defined(WEBVIEW_COCOA)
    return (jint)embed::cocoa_open_devtools((Engine *)wv);
#else
    (void)wv;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1execute_1editing_1command
  (JNIEnv *, jclass, jlong wv, jint cmdId) {
#ifdef WEBVIEW_GTK
    embed::gtk_execute_editing_command((Engine *)wv, (int)cmdId);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_execute_editing_command((Engine *)wv, (int)cmdId);
#else
    (void)wv; (void)cmdId;
#endif
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1is_1native_1first_1responder
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_COCOA
    return (jint)embed::cocoa_is_first_responder((Engine *)wv);
#else
    (void)wv;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1focus_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
#ifdef WEBVIEW_COCOA
    embed::cocoa_set_focus_callback((Engine *)wv, env, cb);
#else
    (void)env; (void)wv; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1click_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
#ifdef WEBVIEW_GTK
    embed::gtk_set_click_callback((Engine *)wv, env, cb);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_click_callback((Engine *)wv, env, cb);
#else
    (void)env; (void)wv; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1release_1native_1focus
  (JNIEnv *, jclass, jlong wv) {
    // No-op on macOS and Linux: AppKit / X11 focus handling is already
    // adequate.  Windows has its own implementation in
    // windows/webview_embed.cc that performs the cross-thread SetFocus.
    (void)wv;
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1attach_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    auto *e = (Engine *)wv;
    if (!e) return;
#if defined(WEBVIEW_COCOA)
    // macOS attach is asynchronous; the callback connects the Java
    // EmbeddedWebView's AttachState machine to the async epilogue's
    // resolution.  cocoa_set_attach_callback handles both orderings
    // (callback registered before vs. after the async epilogue
    // completes) under attach_callback_mutex.
    embed::cocoa_set_attach_callback(e, env, cb);
#else
    // GTK / unsupported: attach is synchronous (the engine is fully
    // ready by the time webview_embed_create returns), so signal
    // completion immediately.  Fire onResolved(true, null) from the
    // calling thread (typically the EDT); the Java handler marshals
    // via SwingUtilities.invokeLater so the listener actually fires on
    // the next EDT tick regardless of which thread we are on here.
    if (!cb) return;
    jclass cls = env->GetObjectClass(cb);
    if (!cls) return;
    jmethodID m = env->GetMethodID(cls, "onResolved",
                                   "(ZLjava/lang/String;)V");
    if (m) {
        env->CallVoidMethod(cb, m, (jboolean)JNI_TRUE, (jstring)nullptr);
    }
    env->DeleteLocalRef(cls);
#endif
}

// The two dialog-callback JNI bridges below are wrapped in `extern "C"`
// so the symbols emitted match the JVM's JNI lookup expectations
// (`Java_<classpath>_<method>` with C linkage).  The existing focus /
// click / release-native-focus / set-attach-callback bridges above get
// this treatment from `ca_weblite_webview_WebViewNative.h`'s
// `extern "C" { ... }` wrapper, but the dialog setters were added
// without regenerating that header so they need the explicit linkage
// spec.  Regenerating the header is a cosmetic follow-up; the explicit
// `extern "C"` here keeps the symbols callable from the JVM today.
extern "C" {

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1dialog_1callback
  (JNIEnv *env, jclass, jlong wv, jobject cb) {
    if (wv == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_set_dialog_callback((embed::Engine *)wv, env, cb);
#elif defined(WEBVIEW_COCOA)
    embed::cocoa_set_dialog_callback((embed::Engine *)wv, env, cb);
#else
    (void)env; (void)cb;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1set_1dialog_1callback
  (JNIEnv *env, jclass, jlong peer, jobject cb) {
    if (peer == 0) return;
#ifdef WEBVIEW_GTK
    embed::gtk_off_set_dialog_callback((embed::OffEngine *)peer, env, cb);
#else
    // macOS / Windows have no offscreen engine; the Java
    // OffscreenWebView.setDialogCallback never gets here because
    // OffscreenWebView.create returns null on those platforms.
    (void)env; (void)cb;
#endif
}

} // extern "C"

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1init
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_off_init_script((embed::OffEngine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1eval
  (JNIEnv *env, jclass, jlong wv, jstring js) {
    const char *s = env->GetStringUTFChars(js, nullptr);
#ifdef WEBVIEW_GTK
    embed::gtk_off_eval((embed::OffEngine *)wv, s ? s : "");
#else
    (void)wv;
#endif
    env->ReleaseStringUTFChars(js, s);
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1bind
  (JNIEnv *env, jclass, jlong wv, jstring name, jobject fn, jlong /*arg*/) {
#ifdef WEBVIEW_GTK
    auto *e = (embed::OffEngine *)wv;
    if (!e) return;
    const char *n = env->GetStringUTFChars(name, nullptr);
    embed::Binding *b = new embed::Binding();
    b->name = n ? n : "";
    b->fn = env->NewGlobalRef(fn);
    jclass cls = env->GetObjectClass(fn);
    b->cls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);

    // Byte-identical to the heavyweight bind shim at
    // webview_embed.cpp:1791-1801 so the window.<name>(...) contract
    // is the same in both modes.
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

    embed::gtk_off_bind(e, b);
    embed::gtk_off_init_script(e, js);
    env->ReleaseStringUTFChars(name, n);
#else
    (void)env; (void)wv; (void)name; (void)fn;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1dispatch
  (JNIEnv *env, jclass, jlong wv, jobject callback) {
#ifdef WEBVIEW_GTK
    auto *e = (embed::OffEngine *)wv;
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
    embed::GtkPump::instance().run_async(fn);
#else
    (void)env; (void)wv; (void)callback;
#endif
}

JNIEXPORT jint JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1open_1devtools
  (JNIEnv *, jclass, jlong wv) {
#ifdef WEBVIEW_GTK
    return (jint)embed::gtk_off_open_devtools((embed::OffEngine *)wv);
#else
    (void)wv;
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_ca_weblite_webview_WebViewNative_webview_1offscreen_1execute_1editing_1command
  (JNIEnv *, jclass, jlong wv, jint cmdId) {
#ifdef WEBVIEW_GTK
    embed::gtk_off_execute_editing_command(
        (embed::OffEngine *)wv, (int)cmdId);
#else
    (void)wv; (void)cmdId;
#endif
}
