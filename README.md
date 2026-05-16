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
    <version>1.0-SNAPSHOT</version>
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

## Demo

See [`demos/WebViewHeavyweightDemo/`](demos/WebViewHeavyweightDemo/README.md)
for a working example that exercises both heavyweight and lightweight
modes side-by-side, plus interaction with surrounding Swing widgets
(JComboBox dropdowns, tab switching).  One-shot launcher scripts
(`run-mac-demo.sh`, `run-linux-demo.sh`, `run-windows-demo.bat`) live
at the project root.

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

The repo ships pre-built native libs under `src/windows_32`,
`src/windows_64`, `src/osx_64`, `src/linux_64`, and `src/linux_arm64`.
To rebuild them:

1. Run `build-mac.sh` / `build-linux.sh` / `build-windows.sh` on the
   matching platform.  These rebuild the native sources and copy the
   binaries into the appropriate `src/<platform>` directory.
2. Mac and Linux native sources are under `src_c/`.  Windows native
   sources are under `windows/`.
3. On Windows you need Visual Studio installed (VS 2019 works; earlier
   versions likely do too).  The `build-windows.sh` script runs under
   git bash.

## License

MIT

## Credits

1. This library by [Steve Hannah](https://sjhannah.com)
2. Original webview library by [Serge Zaitsev](https://zserge.com)
