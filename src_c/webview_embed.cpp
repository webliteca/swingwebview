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
};

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

        // Make sure GTK considers the WebKitWebView focused before we
        // dispatch a key event -- gtk_main_do_event routes by
        // focus_widget on the toplevel.
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
        ev->key.hardware_keycode = 0;
        ev->key.group = 0;
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
};

static void cocoa_run_on_main(std::function<void()> f) {
    // If we're already on the AppKit main thread, just run f inline; otherwise
    // dispatch_sync to the main queue.  Synchronously dispatching onto your
    // own queue deadlocks, which is easy to hit when a script-message handler
    // (which runs on the main thread) ends up calling back into the embed API.
    //
    // This MUST NOT be called from the EDT during a live AppKit operation
    // (window resize / drag etc.) -- main's run loop is in
    // NSEventTrackingRunLoopMode during those and the main dispatch queue is
    // not reliably drained from sync waiters on other threads, causing the
    // EDT to block forever.  Use cocoa_run_on_main_async for fire-and-forget
    // calls (setBounds, navigate, eval, ...).  Reserve the sync version for
    // attach/detach where we genuinely need to wait for the result.
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
        if (e->webview) {
            // If we added the WKWebView as a subview, remove it before
            // releasing so AppKit unwinds the view hierarchy cleanly.
            msg<void>(e->webview, sel("removeFromSuperview"));
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

// Update the WKWebView's frame so it overlays exactly the AWT canvas
// region.  When host_view is a per-Canvas AWT NSView its bounds already
// match the canvas, so the WKWebView just fills it.  Otherwise host_view
// is NSWindow.contentView and the caller's (x,y,w,h) -- the canvas
// position in NSWindow content-pane coords with AWT's top-left origin --
// is translated into Cocoa's bottom-left coords.
static void cocoa_set_bounds(Engine *e, int x, int y, int w, int h) {
    cocoa_run_on_main_async([=] {
        if (!e || !e->webview || !e->host_view) return;
        CGFloat fx = (CGFloat)x;
        CGFloat fy = (CGFloat)y;
        CGFloat fw = (CGFloat)w;
        CGFloat fh = (CGFloat)h;
        if (e->host_is_awt) {
            msg<void, CGRect>(e->webview, sel("setFrame:"),
                              CGRectMake(0, 0, fw, fh));
            return;
        }
        CGRect b = msg<CGRect>(e->host_view, sel("bounds"));
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
        if (!e->webview) return;
        id nsurl = msg<id, id>(objc_cls("NSURL"), sel("URLWithString:"),
                               ns_str(url.c_str()));
        id req = msg<id, id>(objc_cls("NSURLRequest"),
                             sel("requestWithURL:"), nsurl);
        msg<void, id>(e->webview, sel("loadRequest:"), req);
    });
}

static void cocoa_init_script(Engine *e, std::string js) {
    cocoa_run_on_main_async([=] {
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
        if (!e->webview) return;
        msg<void, id, id>(e->webview,
                          sel("evaluateJavaScript:completionHandler:"),
                          ns_str(js.c_str()), (id)nullptr);
    });
}

static void cocoa_set_visible(Engine *e, bool visible) {
    cocoa_run_on_main_async([=] {
        if (!e->webview) return;
        msg<void, BOOL>(e->webview, sel("setHidden:"), visible ? NO : YES);
    });
}

static void cocoa_request_focus(Engine *e) {
    cocoa_run_on_main_async([=] {
        if (!e->webview) return;
        id win = msg(e->webview, sel("window"));
        if (win) {
            msg<BOOL, id>(win, sel("makeFirstResponder:"), e->webview);
        }
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
