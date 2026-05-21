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
            // WebView2Loader is now statically linked into webview.dll on
            // Windows (via WebView2LoaderStatic.lib), so we no longer need
            // to extract+load a separate WebView2Loader.dll.
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

// Hand keyboard / text-input focus to the embedded WebView.  On Linux
// this calls XSetInputFocus on the embedded popup's X11 window so the
// X server delivers key events to our GTK widget tree rather than to
// the AWT top-level frame; on macOS it calls makeFirstResponder on the
// WKWebView; on Windows it brings the WebView2 child HWND into focus.
// Without this, keyboard input typed while the pointer is over the
// WebView region goes nowhere because the AWT frame still holds
// system focus.
native static void webview_embed_request_focus(long w);

// Open the platform's native DevTools / Web Inspector in a separate OS
// window.  Returns 1 if an inspector window was actually opened (or
// re-focused, on platforms that focus an existing one), 0 if
// unsupported, disabled (debug was not enabled at create time), or the
// underlying SDK call failed.  Never throws via JNI.
//
// Per-platform behaviour:
//   - Linux:   webkit_web_inspector_show on the engine's WebKitWebView.
//              Returns 0 if developer-extras is FALSE on the engine's
//              WebKitSettings.
//   - macOS:   returns 0 unconditionally.  No public WKWebView API
//              programmatically pops the inspector; the inspector is
//              reachable via right-click -> Inspect Element or via the
//              Safari Develop menu when developerExtrasEnabled and
//              isInspectable were set at create time.
//   - Windows: ICoreWebView2::OpenDevToolsWindow on the WebView2 worker
//              thread.  Returns 0 if AreDevToolsEnabled is FALSE.
native static int webview_embed_open_devtools(long w);

// Execute a platform editing command (Cut / Copy / Paste / Select-All)
// against the embedded WebView's current focused element.  cmdId is the
// stable integer from {@link EditingCommand#getNativeId()}; the contract
// is 1=CUT, 2=COPY, 3=PASTE, 4=SELECT_ALL.  Never throws via JNI -- bad
// cmdIds or null engines fall through silently.
//
// Per-platform behaviour:
//   - macOS:   [NSApp sendAction:@selector(cut:|copy:|paste:|selectAll:)
//              to:nil from:webview] on the AppKit main thread.  to:nil
//              resolves against the first responder, which under a
//              focused contentEditable or <input> is the inner DOM
//              element.
//   - Linux:   webkit_web_view_execute_editing_command on the GTK main
//              thread.
//   - Windows: webview->ExecuteScript("document.execCommand(...)") on
//              the WebView2 worker thread.
native static void webview_embed_execute_editing_command(long w, int cmdId);

// Returns 1 if the engine's WKWebView (or one of its inner views) is the
// current first responder of its NSWindow, 0 otherwise.  macOS only;
// Linux / Windows return 0 unconditionally.  Used by the editing-shortcut
// dispatcher to override the JTextComponent-deferral when the user has
// clicked into the WebView but AWT focus stayed on a sibling text widget.
native static int webview_embed_is_native_first_responder(long w);

// Register (or clear, by passing null) a callback to be invoked when the
// engine's WKWebView becomes / resigns first responder.  Used by the
// heavyweight component to drive visual focus cooperation -- suppress and
// restore Swing JTextComponent carets as the user shifts interaction
// between Swing widgets and the WebView.  Linux / Windows: stub (no-op).
native static void webview_embed_set_focus_callback(long w, WebViewFocusCallback cb);

// Register (or clear, by passing null) a callback invoked once per native
// mouse-button press inside the embedded WebView's surface.  Used by the
// heavyweight component to close any open Swing JPopupMenu when the user
// clicks into the WebView -- the native peer receives clicks directly
// from the OS and AWT's MouseGrabber AWTEventListener never sees them,
// so without this hook open popups stay open inconsistently with the
// rest of the Swing UI.  Fires for left / right / middle buttons.  The
// callback is invoked on a native thread; the Java implementation must
// marshal to the EDT before touching Swing state.  Per-platform event
// source: Linux extends the existing gtk-gesture pressed handler; macOS
// swizzles WKWebView mouseDown:/rightMouseDown:/otherMouseDown:; Windows
// hooks WM_PARENTNOTIFY on the parent HWND.  Never throws via JNI; bad
// inputs fall through silently.
native static void webview_embed_set_click_callback(long w, WebViewClickCallback cb);

// Force Win32 keyboard focus back to the AWT-owned parent HWND, so
// subsequent keystrokes route to AWT instead of the WebView2 child
// HWND.  Called from the Java-side global focus-owner listener when
// AWT moves its focus owner to a Swing component outside the WebView.
// macOS / Linux: no-op (AppKit / X11 focus handling is already
// adequate on those platforms).  Never throws via JNI.
native static void webview_embed_release_native_focus(long w);

// Register a one-shot attach-completion callback for the engine.
// The callback object MUST have a method
// {@code void onResolved(boolean ok, String failureMessage)} that the
// native side invokes once the engine's attach resolves — with
// (true, null) on success, or (false, "<step description>") on
// failure. macOS attach is asynchronous: the callback fires later,
// from the AppKit main thread, when the WKWebView setup completes.
// Windows and Linux attach is synchronous: the callback fires
// immediately inside this call (still routed through the EDT via
// SwingUtilities.invokeLater in the Java handler).  Never throws via
// JNI; null callback clears any prior registration.  Used by
// {@link EmbeddedWebView} to drive the {@link AttachState} state
// machine and fire {@link WebViewAttachListener} callbacks on the
// Swing EDT.
native static void webview_embed_set_attach_callback(long w, Object cb);


// ---------------------------------------------------------------------------
// Lightweight / offscreen API (currently Linux-only).
//
// The native engine renders the WebView into a GtkOffscreenWindow that
// never touches the screen; Java pulls pixels via snapshot() and paints
// them itself.  Bypasses all the AWT/GTK/X11 focus and frame-clock
// negotiation that the heavyweight embed path has to fight on Linux.
// ---------------------------------------------------------------------------

// Create the offscreen engine with the given initial pixel dimensions.
// Returns an opaque pointer (jlong) or 0 on unsupported platform / failure.
native static long webview_offscreen_create(int w, int h, int debug);

// Tear down the offscreen engine.
native static void webview_offscreen_destroy(long peer);

// Resize the offscreen WebView's viewport.  Pixel size; the WebView will
// re-layout to fit.
native static void webview_offscreen_resize(long peer, int w, int h);

// Navigate the offscreen WebView to the given URL.
native static void webview_offscreen_navigate(long peer, String url);

// Copy the current pixel contents of the offscreen WebView into the given
// Java int[] (assumed to hold at least w*h pixels in ARGB / 0xAARRGGBB
// format, matching BufferedImage.TYPE_INT_ARGB).
native static void webview_offscreen_snapshot(long peer, int[] pixels,
                                              int w, int h);

// Inject a mouse button press / release into the offscreen WebView.
// press: 1 for press, 0 for release.
// (x, y): pixel coords relative to the WebView.
// button: 1 = primary, 2 = middle, 3 = secondary.
// modifiers: bitmask of GDK modifier constants (GDK_SHIFT_MASK=1,
//   GDK_CONTROL_MASK=4, GDK_MOD1_MASK=8 (alt), GDK_META_MASK=0x10000000).
// click_count: 1 single, 2 double, 3+ triple.
native static void webview_offscreen_mouse_button(long peer, int press,
                                                  int x, int y, int button,
                                                  int modifiers,
                                                  int click_count);

// Inject a mouse-move event.  Same coord / modifier semantics as
// mouse_button.
native static void webview_offscreen_mouse_motion(long peer, int x, int y,
                                                  int modifiers);

// Inject a smooth-scroll event.  dx/dy are scroll deltas in pixel-ish
// units (GDK_SCROLL_SMOOTH semantics).
native static void webview_offscreen_mouse_scroll(long peer, int x, int y,
                                                  double dx, double dy,
                                                  int modifiers);

// Inject a key press / release into the offscreen WebView.
// press: 1 for press, 0 for release.
// keyval: a GDK keysym (printable ASCII characters use the char value
//   directly; special keys use the GDK_KEY_xxx constants from
//   gdk/gdkkeysyms.h, see ca.weblite.webview.GdkInput).
// modifiers: bitmask of GDK modifier constants.
// is_modifier_key: 1 if the keyval is itself a modifier (Shift, Ctrl,
//   Alt, etc.) so WebKit can track modifier-up/down state.
native static void webview_offscreen_key_event(long peer, int press,
                                               int keyval, int modifiers,
                                               int is_modifier_key);

// Install a JavaScript snippet that runs at the start of every new
// document loaded into the offscreen WebView.  Mirrors webview_embed_init.
// No-op on macOS/Windows (where the offscreen engine itself is a stub).
native static void webview_offscreen_init(long peer, String js);

// Inject a JavaScript snippet into the current document of the offscreen
// WebView.  Evaluation is asynchronous and the result is ignored -- use a
// binding (webview_offscreen_bind) to round-trip values back from JS.
// No-op on macOS/Windows.
native static void webview_offscreen_eval(long peer, String js);

// Bind a Java callback so it appears as a global JavaScript function
// window.<name>(arg) inside the offscreen WebView.  The native engine
// round-trips invocations through the same {name, seq, args} envelope as
// the embed engine so page authors see an identical contract.
// No-op on macOS/Windows.
native static void webview_offscreen_bind(long peer, String name,
                                          WebViewNativeCallback fn, long arg);

// Marshal a Runnable onto the offscreen engine's UI (GTK) thread.
// Mirrors webview_embed_dispatch.
native static void webview_offscreen_dispatch(long peer, Runnable callback);

// Open the WebKitGTK Web Inspector for the offscreen WebView in a
// separate OS window.  Returns 1 if opened, 0 if developer-extras was
// not enabled at create time, the engine has no inspector, or this
// platform doesn't support the offscreen engine.  Never throws via JNI.
native static int webview_offscreen_open_devtools(long peer);

// Execute a platform editing command (Cut / Copy / Paste / Select-All)
// against the offscreen WebView's current focused element.  cmdId is the
// stable integer from {@link EditingCommand#getNativeId()} -- the same
// 1=CUT, 2=COPY, 3=PASTE, 4=SELECT_ALL ABI as
// webview_embed_execute_editing_command.  Linux only; macOS / Windows
// stubs no-op.  Never throws via JNI.
native static void webview_offscreen_execute_editing_command(long peer, int cmdId);


}
