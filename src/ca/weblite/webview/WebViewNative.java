/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ca.weblite.webview;

import ca.weblite.webview.nativelib.NativeLoader;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author shannah
 */
public class WebViewNative {
    
    static {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                NativeLoader.loadLibrary("WebView2Loader");
            }
            // Make sure libjawt is in the process before our dylib is bound.
            // The embed engine references JAWT_GetAWT and AWT does not pull
            // libjawt in transitively on macOS, so without this the first
            // JAWT call would dereference an unresolved symbol and SIGSEGV
            // at PC=0 (the dyld lazy-binding stub never resolved it).
            try {
                System.loadLibrary("jawt");
            } catch (UnsatisfiedLinkError ignored) {
                // Some JDK distributions don't ship libjawt as a standalone
                // loadable library, or AWT has already pulled it in.  In
                // either case we can proceed; the symbol will resolve when
                // the webview library is loaded next.
            }
            NativeLoader.loadLibrary("webview");
        } catch (IOException ex) {
            Logger.getLogger(WebViewNative.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
     // Creates a new webview instance. If debug is non-zero - developer tools will
// be enabled (if the platform supports them). Window parameter can be a
// pointer to the native window handle. If it's non-null - then child WebView
// is embedded into the given parent window. Otherwise a new window is created.
// Depending on the platform, a GtkWindow, NSWindow or HWND pointer can be
// passed here.
native static long webview_create(int debug, long window);

// Destroys a webview and closes the native window.
native static void webview_destroy(long w);

// Runs the main loop until it's terminated. After this function exits - you
// must destroy the webview.
native static void webview_run(long w);

// Stops the main loop. It is safe to call this function from another other
// background thread.
native static void webview_terminate(long w);

// Posts a function to be executed on the main thread. You normally do not need
// to call this function, unless you want to tweak the native window.
native static void
webview_dispatch(long w, Runnable callback, long arg);

// Returns a native window handle pointer. When using GTK backend the pointer
// is GtkWindow pointer, when using Cocoa backend the pointer is NSWindow
// pointer, when using Win32 backend the pointer is HWND pointer.
native static long webview_get_window(long w);

// Updates the title of the native window. Must be called from the UI thread.
native static void webview_set_title(long w, String title);

// Updates native window position and size.
// TODO: implement x/y and describe possible flags.
native static void webview_set_bounds(long w, int x, int y, int width,
                                    int height, int flags);



// Navigates webview to the given URL. URL may be a data URI, i.e.
// "data:text/text,<html>...</html>". It is often ok not to url-encode it
// properly, webview will re-encode it for you.
native static void webview_navigate(long w, String url);

// Injects JavaScript code at the initialization of the new page. Every time
// the webview will open a the new page - this initialization code will be
// executed. It is guaranteed that code is executed before window.onload.
native static void webview_init(long w, String js);

// Evaluates arbitrary JavaScript code. Evaluation happens asynchronously, also
// the result of the expression is ignored. Use RPC bindings if you want to
// receive notifications about the results of the evaluation.
native static void webview_eval(long w, String js);

// Binds a native C callback so that it will appear under the given name as a
// global JavaScript function. Internally it uses webview_init(). Callback
// receives a request string and a user-provided argument pointer. Request
// string is a JSON array of all the arguments passed to the JavaScript
// function.
native static void webview_bind(long w, String name,
                              WebViewNativeCallback fn, long arg);


// --------------------------------------------------------------------------
// Swing / embedding API.
//
// These entry points allow a WebView to be created as a child of an existing
// native window owned by another toolkit (typically Swing/AWT, via JAWT), so
// the WebView can be embedded inside a heavyweight Component instead of
// creating its own top-level window.  The host application is responsible
// for driving its own UI event loop -- webview_run() is NOT called for
// embedded WebViews.
// --------------------------------------------------------------------------

// Returns the native window handle of a Swing/AWT heavyweight Component, or 0.
// Intended for diagnostics and advanced integrations; the embedded WebView
// creation path resolves the handle internally and does not require this.
// The handle is interpreted as:
//   - Linux:   an X11 Window (XID) cast to a jlong.
//   - macOS:   a pointer to a JAWT SurfaceLayers id cast to a jlong (only
//              valid while the JAWT drawing surface is locked, which is not
//              the case after this method returns -- use with care).
//   - Windows: an HWND cast to a jlong.
// The component must be displayable (addNotify() called) before invoking this.
native static long jawt_get_window_handle(java.awt.Component c);

// Creates a WebView attached to the given AWT Component.  The component must
// be heavyweight and already displayable.  Returns an opaque pointer that
// must be passed to the remaining webview_embed_* methods, or 0 on failure.
native static long webview_embed_create(java.awt.Component parent, int debug);

// Positions and resizes the embedded WebView within its parent.  Coordinates
// are in the parent's local coordinate space, in pixels.
native static void webview_embed_set_bounds(long w, int x, int y, int width, int height);

// Pumps one iteration of the platform UI loop for the embedded WebView.
// If waitForEvent is non-zero, the call blocks until at least one event has
// been processed.  Otherwise it returns immediately after processing any
// events that are already available.  This is only required on platforms
// whose UI loop is not already being driven by the host (notably Linux/GTK,
// where the GTK main loop is independent of AWT's X11 event loop).
// On macOS and Windows this is a no-op when the host's event loop is already
// running.
native static int webview_embed_pump(long w, int waitForEvent);

// Releases the resources associated with an embedded WebView and detaches it
// from its native parent.  Equivalent to webview_destroy for embedded
// instances but ensures any pump thread is stopped first.
native static void webview_embed_destroy(long w);

// The remaining embed entry points mirror their non-embedded counterparts but
// operate on the opaque pointer returned by webview_embed_create.  They are
// kept distinct so the embed engine implementation can evolve independently
// from the legacy top-level WebView engine.
native static void webview_embed_navigate(long w, String url);
native static void webview_embed_init(long w, String js);
native static void webview_embed_eval(long w, String js);
native static void webview_embed_bind(long w, String name,
                                      WebViewNativeCallback fn, long arg);
native static void webview_embed_dispatch(long w, Runnable callback);

// Show or hide the embedded WebView in-place.  Used to track Swing
// visibility changes -- e.g., JTabbedPane tab switches, parent setVisible.
// visible != 0 makes the native view visible; 0 hides it (without
// destroying the engine).
native static void webview_embed_set_visible(long w, int visible);


}
