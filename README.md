# WebView

A cross-platform native WebView component for embedding in Java Swing
applications.  Java port of the tiny, light-weight
[WebView](https://github.com/zserge/webview) by
[Serge Zaitsev](https://zserge.com).

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>webview</artifactId>
    <version>1.0.9</version>
</dependency>
```

The jar bundles the native libraries for macOS, Linux, and Windows — no
additional native install step is required, with one exception:

* **Windows** requires the system-wide Microsoft Edge WebView2 Runtime,
  which ships with current Windows 11 / Edge.  On older Windows, install
  the Evergreen Runtime from
  <https://developer.microsoft.com/microsoft-edge/webview2/>.

## Platform support

| Platform | Heavyweight | Lightweight |
|---|---|---|
| **macOS** (Cocoa / WKWebView) | Full (rendering, input, resize, tab visibility) | Stub — falls back to default Swing background |
| **Linux** (WebKitGTK / X11) | Rendering, mouse, scroll, resize, tab switching work.  Visible text-input feedback (caret blink, characters appearing as typed) is **unreliable** because of how GTK frame-clock and focus interact with `XReparentWindow` under a foreign (non-GTK) parent. | **Full** — rendering + mouse (click, drag, scroll, hover) + keyboard (typing, Backspace, Delete, arrows, function keys, common modifiers) |
| **Windows** (WebView2) | Full (rendering, input, resize, tab visibility) on Windows 11 | Stub |

The `WebViewComponent.create()` factory picks the right mode for the
current platform (heavyweight on macOS / Windows, lightweight on
Linux), so most callers don't need to think about it.

### Clipboard & editing shortcuts

The standard platform shortcut (`Cmd` on macOS, `Ctrl` on Linux /
Windows) + `C` / `V` / `X` / `A` performs Copy / Paste / Cut /
Select-All inside the embedded WebView on all platforms.  A
`KeyEventDispatcher` installed on the component routes the shortcut to
the native editing primitive — `[WKWebView copy:/paste:/cut:/selectAll:]`
on macOS, `webkit_web_view_execute_editing_command` on Linux,
`document.execCommand` on Windows.  Sibling Swing widgets (a
`JTextField` in a toolbar above the WebView, etc.) keep their default
shortcut handling — the dispatcher only fires when the user is actually
interacting with the WebView.

## Quick start

```java
import ca.weblite.webview.swing.WebViewComponent;
import javax.swing.*;
import java.awt.*;

public class Demo {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WebViewComponent wv = WebViewComponent.create();
            wv.setUrl("https://example.com");
            wv.setPreferredSize(new Dimension(900, 600));

            JFrame frame = new JFrame("WebView Demo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(wv, BorderLayout.CENTER);
            frame.pack();
            frame.setVisible(true);
        });
    }
}
```

## Choosing a mode

`WebViewComponent.create()` returns whichever implementation is best
for the current platform.  Two concrete subclasses both extend
`WebViewComponent`:

* **`WebViewHeavyweightComponent`** — embeds the native WebView as a
  child of the underlying heavyweight AWT peer.  Renders directly to
  screen pixels.  Native compositing means the highest fidelity and
  lowest overhead, but it interacts with Swing Z-order the way every
  heavyweight AWT component does — it paints above any overlapping
  lightweight Swing components in the same window (see "Heavyweight
  popup notes" below).
* **`WebViewLightweightComponent`** — renders the WebView into an
  offscreen surface, ships the pixels to Java, and Swing paints them
  into a regular `JComponent`.  Composites cleanly with arbitrary Swing
  widgets and Z-order.  Higher per-frame cost than heavyweight; mouse
  and keyboard input is forwarded from Swing.

To force a specific mode, either set the `ca.weblite.webview.mode`
system property to `heavyweight` or `lightweight` (case-insensitive),
or call the factory explicitly:

```java
import ca.weblite.webview.swing.WebViewComponent;
import ca.weblite.webview.swing.WebViewComponent.Mode;

WebViewComponent wv = WebViewComponent.create(Mode.HEAVYWEIGHT);
// or Mode.LIGHTWEIGHT
```

You can also instantiate `WebViewHeavyweightComponent` or
`WebViewLightweightComponent` directly if you need to.

### Heavyweight popup notes

When using `WebViewHeavyweightComponent`, native Swing popups
(`JComboBox` dropdowns, `JMenu`, tooltips) render *behind* the
WebView's heavyweight peer unless you opt into heavyweight popup mode
at app start:

```java
JPopupMenu.setDefaultLightWeightPopupEnabled(false);
ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
```

This makes popups appear as real OS windows that sit above heavyweight
peers.  Lightweight mode does not need this.

The embedded WebView does **not** take ownership of the host
application's event loop.

### Lightweight notes

The lightweight component renders WebKit into a `GtkOffscreenWindow`,
snapshots `cairo_image_surface_t` pixels at ~30Hz into a
`BufferedImage`, and paints that into the `JComponent` via
`paintComponent`.  AWT `MouseEvent`s and `KeyEvent`s are translated to
`GdkEvent`s and injected via `gtk_main_do_event`.  Notes:

* The WebKitWebView's IM context is disabled because all input arrives
  already-decoded from AWT.  This means CJK / IME composition is
  **not** available in the lightweight component on Linux today.  Dead
  keys and Compose key sequences (e.g. `é`, `ñ`) work for
  ASCII-Latin-1 layouts but not for IME-driven layouts.
* Right-click context menus and `<select>` dropdowns from inside the
  page log a `gdk_window_move_to_rect: assertion 'window->transient_for'`
  warning and don't visibly appear — WebKit tries to position them
  relative to a toplevel that doesn't exist in our offscreen model.
  Not fatal; just a missing piece of UI for those interactions.
* Heavyweight popup interop is *not* needed in lightweight mode —
  Swing components like `JComboBox` and tooltips composite over the
  WebView with their normal lightweight rendering.

### Heavyweight platform notes

* **Linux (GTK / WebKitGTK / X11)** — the WebView's GTK window is
  reparented under the JAWT-managed X11 window via `XReparentWindow`.
  A dedicated GTK pump thread drives the WebKitGTK main loop
  independently of AWT's X11 event loop.  A 60Hz `g_timeout` drives
  the paint pipeline (the X11 GdkFrameClock won't pace itself on a
  reparented popup that has no WM relationship).  Requires
  `libwebkit2gtk-4.0-dev` or `libwebkit2gtk-4.1-dev` plus `libxt-dev`
  (JDK 8's `jawt_md.h` pulls in X11 Intrinsics).
* **macOS (Cocoa / WKWebView)** — the WKWebView is added as a real
  subview of `NSWindow.contentView` (looked up through the layer
  hierarchy from the JAWT `windowLayer`), so WebKit's CARemoteLayer
  compositing engages and input dispatch goes through AppKit's normal
  responder chain.  All input works end-to-end.
* **Windows (WebView2)** — a child `HWND` is created under the AWT
  canvas HWND and an `ICoreWebView2Controller` + `ICoreWebView2` are
  hosted inside it (modern stable WebView2 SDK).  Each embedded
  WebView runs on its own worker thread that pumps a private message
  queue.  `WebView2LoaderStatic.lib` is linked statically so we ship
  just `webview.dll`, no separate `WebView2Loader.dll`.  The system
  WebView2 Runtime (part of Edge / Windows 11) provides the actual
  Chromium binaries.

### Focus cooperation (macOS + Windows heavyweight)

The AWT focus chain and the native focus chain (AppKit responder /
Win32 keyboard focus) are independent on these platforms, and the
heavyweight WebView's native peer holds native focus in a way AWT
doesn't observe.  Two consequences are handled automatically:

* When the user clicks into the WebView, the previously-focused Swing
  `JTextComponent`'s caret is hidden (visual cue that typing now lands
  in the WebView).  macOS hooks `becomeFirstResponder` on the
  `WKWebView` via a runtime class swizzle; Windows hooks
  `ICoreWebView2Controller::add_GotFocus`.
* When the user clicks back to a Swing component in the same window,
  the suppressed caret is restored and its blink timer is restarted
  via a synthetic `FocusEvent.FOCUS_GAINED`.  On Windows we
  additionally force Win32 keyboard focus back to the JFrame HWND
  (cross-thread `SetFocus` via `AttachThreadInput`) so subsequent
  keystrokes actually reach the Swing component — WebView2 otherwise
  keeps Win32 focus on its child HWND and steals keystrokes.

For debugging, set `-Dca.weblite.webview.debugShortcut=true` (Java
side) and `WEBVIEW_DEBUG_SHORTCUT=1` (native side) to log the
dispatcher decisions and Win32 `SetFocus` calls.

## Talking to JavaScript

Four methods on `WebViewComponent` (and on the standalone `WebView`)
cover the JS-interop surface:

* **`eval(String js)`** — fire-and-forget.  Runs the snippet in the
  current document; the return value is discarded.  Use for side
  effects (`scrollTo`, `document.title = "..."`, click a hidden
  button).
* **`evalAsync(String js): CompletableFuture<String>`** — round-trips
  the snippet's result back to Java.  The future resolves with the
  `JSON.stringify`'d return value (`undefined` becomes `"null"`;
  returned `Promise`s are awaited).  JS-side failures
  (synchronous `throw`, Promise rejection, `JSON.stringify` `TypeError`)
  complete the future exceptionally with a
  `JavaScriptEvalException`.  The snippet runs inside an IIFE, so
  **use `return` to yield a value** — a bare expression is not the
  IIFE's return.
* **`addJavascriptCallback(String name, JavascriptCallback cb)`** —
  exposes a *fire-and-forget* Java callback at `window.<name>(arg)`
  for the page to call.  The callback returns nothing to JS.  Use when
  the page initiates the conversation, or when a long-lived JS
  subscription needs to push events to Java.
* **`addJavascriptFunction(String name, JavascriptFunction fn)`** —
  exposes a *value-returning* Java function at `window.<name>(arg)`.
  In the page it returns a Promise: `const r = await window.<name>(arg)`.
  No JavaScript glue — the Java side is just a lambda.  The library
  runs the (synchronous) handler on a background thread, so it can
  block safely without freezing the UI or deadlocking the engine UI
  thread against the EDT — the reason this exists instead of a
  synchronous, value-returning `addJavascriptCallback`.  A
  `CompletableFuture<String>`-returning overload
  (`AsyncJavascriptFunction`) covers inherently-async work.  Results
  are strings (return JSON text for structured data); a thrown
  exception rejects the page-side Promise.

```java
WebViewComponent wv = WebViewComponent.create();
wv.setUrl("https://example.com");
// ...add to JFrame and show...

// Ask the page for its current scroll position once it loads.
wv.evalAsync("return [window.scrollX, window.scrollY];")
  .thenAccept(json -> System.out.println("scroll = " + json));
// Prints e.g. "scroll = [0,240]"

// Await a Promise: the future resolves with the fetched body length.
wv.evalAsync(
    "return fetch('/health').then(r => r.text()).then(t => t.length);"
).thenAccept(json -> System.out.println("body length = " + json));

// JS error → future completes exceptionally.
wv.evalAsync("return missing.value;").exceptionally(t -> {
    Throwable cause = t.getCause();          // CompletionException wraps it
    if (cause instanceof JavaScriptEvalException) {
        System.err.println("page said no: " + cause.getMessage());
    }
    return null;
});

// Expose a value-returning Java function to the page — no JS glue.
wv.addJavascriptFunction("reverse", (String arg) ->
    new StringBuilder(arg).reverse().toString());
// in the page:  const r = await window.reverse("abc");   // "cba"
```

**Threading.**  On `WebViewComponent` (both heavyweight and lightweight)
future continuations land on the Swing EDT, so a `.thenAccept(...)` can
touch Swing state directly.  On the standalone `WebView` continuations
run inline on the WebView's native UI thread — there's no Swing in the
standalone path; wrap with
`.thenAcceptAsync(continuation, SwingUtilities::invokeLater)` if you
need EDT delivery there.

**Lifecycle.**  Calling `evalAsync` before the component is displayed
(or on the standalone `WebView` before `show()`, or after the window
closes) returns an already-failed future whose cause is an
`IllegalStateException` — no native call is made.  See
[`demos/WebViewAsyncEvalDemo/`](demos/WebViewAsyncEvalDemo/README.md)
for a runnable example.

## Browser-initiated dialogs

Pages can call `window.alert`, `window.confirm`, `window.prompt`, and
they can include `<input type="file">` elements whose click opens a
file picker.  `WebViewComponent.setDialogHandler` lets the host
application customise — or fully suppress — what shows up:

```java
wv.setDialogHandler(new WebViewDialogHandler() {
    @Override public boolean confirmOpened(WebViewConfirmEvent e) {
        return JOptionPane.showConfirmDialog(
            frame, e.message(), "Confirm",
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }
});
```

* **Default behaviour.**  When no handler is installed (the initial
  state), every dialog kind shows a Swing dialog — `JOptionPane` for
  alert / confirm / prompt, `JFileChooser` for file picker — modal to
  the host `JFrame` resolved via
  `SwingUtilities.getWindowAncestor(component)`.  Override individual
  methods to customise specific kinds; un-overridden methods fall
  through to the Swing defaults.
* **Drop mode for headless tests.**  Pass `null`:
  `wv.setDialogHandler(null)` installs an internal drop handler that
  returns the JS-spec cancel values synchronously without UI
  (`alert` no-op, `confirm` → `false`, `prompt` → `null`, file
  picker → empty list).  Required for unit tests in headless
  environments.  To reset to the framework default, pass
  `WebViewDialogHandler.DEFAULT` explicitly — `null` is NOT a reset.
* **Threading.**  Handler methods run on the Swing EDT, marshaled
  from whatever native thread fired the dialog.  Calling
  `wv.evalAsync(js).get()` from inside a handler **deadlocks** (both
  calls park on the EDT); use `.thenAccept(...)` instead, or
  pre-compute the value before the dialog opens.
* **Platform coverage (current).**  macOS heavyweight (WKWebView)
  routes all four dialog kinds through the handler (STORY-004-001).
  Linux WebKitGTK routes all four kinds through the handler in both
  heavyweight and lightweight modes via the `script-dialog` and
  `run-file-chooser` signals (STORY-004-002).  Windows WebView2
  routes alert / confirm / prompt (and before-unload) through the
  handler via the `ScriptDialogOpening` event combined with
  `put_AreDefaultScriptDialogsEnabled(FALSE)` (STORY-004-003).  On
  Windows, `<input type="file">` continues to use the OS-native
  Common Item Dialog — WebView2 exposes no public hook for the file
  picker, so `filePickerOpened` never fires on Windows.  On Windows,
  `frameUrl()` equals `pageUrl()` for now (top-level only) because
  the `ScriptDialogOpening` event args do not expose a separate
  frame URL.
* **Linux file-picker `accept`-extension limitation.**  On Linux, the
  `WebViewFilePickerEvent.acceptedExtensions` list is always empty
  even when the page wrote `<input accept=".png,.jpg">` — WebKitGTK
  exposes the extension filter as an opaque `GtkFileFilter` rather
  than the original extension strings.  The page's MIME-type hints
  (`accept="image/png"` etc.) are surfaced via `acceptedMimeTypes`;
  the page's own client-side `accept` validation continues to work.

See [`demos/WebViewDialogDemo/`](demos/WebViewDialogDemo/README.md)
for a runnable example that exercises all four dialog kinds in each
of the three handler modes (default, custom, drop).

## Demo

See [`demos/WebViewHeavyweightDemo/`](demos/WebViewHeavyweightDemo/README.md)
for a working example that exercises both heavyweight and lightweight
modes side-by-side, plus interaction with surrounding Swing widgets
(JComboBox dropdowns, tab switching).  One-shot launcher scripts
(`run-mac-demo.sh`, `run-linux-demo.sh`, `run-windows-demo.bat`) live
at the project root.

Additional demos:

* `demos/WebViewContextMenuDemo/` — exercises the right-click
  context-menu API: target descriptor, link / image / editable /
  selection cases, and the `setDefaultContextMenuEnabled` override.
* `demos/WebViewAsyncEvalDemo/` — exercises `evalAsync(String)`:
  primitive / object / Promise / `undefined` results, synchronous
  throws and Promise rejections surfacing as
  `JavaScriptEvalException`, concurrent in-flight calls, and EDT
  delivery of continuations.
* `demos/WebViewAsyncCallbackDemo/` — exercises
  `addJavascriptFunction(...)`: value-returning JS→Java functions
  (sync handlers run off-thread, async `CompletableFuture` handlers,
  errors rejecting the page Promise) with no JavaScript glue.
* `demos/WebViewDialogDemo/` — exercises the new
  `WebViewDialogHandler` API: default Swing dialogs
  (`alert` / `confirm` / `prompt` / file picker), a custom handler
  returning programmatic answers, and the
  `setDialogHandler(null)` drop mode for headless tests.

## Building from source

```
git clone https://github.com/webliteca/swingwebview
cd swingwebview
mvn -DskipTests package
```

This produces `target/webview-1.0-SNAPSHOT.jar`.  The build targets
Java 8 bytecode (`maven.compiler.source` / `maven.compiler.target` =
`1.8` in `pom.xml`); it works on JDK 8 and any newer LTS.  Pass
`-Dmaven.compiler.release=8` if you want strict Java 8 API checking
when building on JDK 9+.

### Rebuilding native libs

The native libraries are **not** checked into git. Locally, you build
them for your own platform and they get bundled into your
`target/*.jar`. For the Maven Central release, the
`.github/workflows/maven-release.yml` workflow builds all 6
platform+arch combinations (`linux_64`, `linux_arm64`, `osx_64`,
`osx_arm64`, `windows_64`, `windows_arm64`) on matching GitHub-hosted
runners and merges them into a single jar before publishing.

To build for your local platform:

1. Run `build-mac.sh` / `build-linux.sh` / `build-windows.sh` on the
   matching platform. These compile the native sources and drop the
   binaries into `natives/<platform>/`, which Maven then picks up as a
   resource during `mvn package`. The `natives/` directory is
   gitignored.
2. Mac and Linux native sources are under `src_c/`. Windows native
   sources are under `windows/`.
3. On Windows you need Visual Studio installed (VS 2019 works; earlier
   versions likely do too). The `build-windows.sh` script runs under
   git bash.

A locally-built jar will only contain the native lib for whichever
platform you ran the build on. The cross-platform fat jar comes only
from the CI release.

## License

MIT

## Credits

1. This library by [Steve Hannah](https://sjhannah.com)
2. Original webview library by [Serge Zaitsev](https://zserge.com)
