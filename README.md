# WebView

This is a Java port of the fantastic, tiny, light-weight [WebView](https://github.com/zserge/webview) by [Serge Zaitsev](https://zserge.com).

It is packaged into an executable Jar file so that you can run it as a CLI self-contained process or as a Java library inside your current process.

## Synopsis

Cross-platform WebView that can be opened and controlled via CLI or as JavaAPI.

## Installation

Download [WebView.jar](bin/WebView.jar)

This jar can be run directly as an executable jar file (e.g. `java -jar WebView.jar [OPTIONS]`), or using the Java API by adding the jar to your classpath.

## Platform Support

Runs on Windows (32 or 64), Linux (64), and Mac.  Other platforms (e.g. Linux 32) can be supported.  Simply need to build the native libs for that platform.

## Running in Separate Process


You can run the WebView in a separate process by running:

~~~~
$ java -jar WebView.jar http://www.example.com
~~~~

This will open the web browser in its own window pointing to http://www.example.com.

**CLI Usage Instructions**

~~~~
Usage: java -jar WebView.jar [OPTIONS] <url>

  <url> - A URL to a webpage to show in the webview.  
Note: You can use a data url here.

Options:
  -title <Window Title>
  -w <window width px>
  -h <window height px>
  -onLoad <js to run on page load>
  -onLoadFile <path to js file to run on page load>
  -useMessageBoundaries    Use message boundaries for wrapping messages from the webview.   Makes it easier to parse.

Examples:

java -jar WebView.jar https://example.com
  Opens webview with starting page https://example.com

java -jar WebView.jar "data:text/html,%3Chtml%3Ehello%3C%2Fhtml%3E"
  Opens webview that says 'hello html'

java -jar WebView.jar https://google.com \
   -onLoad "window.addEventListener('load', function(){postMessageExt('loaded '+location.href)})"
  Opens a webview, and prints out URL of each page on window load event.
~~~~

### Interacting with the Browser Environment

You can interact with the browser environment by typing into the console while the browser is running.  The browser listens on STDIN, for any input, and it will evaluate any input as Javascript in the context of the current page.  E.g. Type "alert('foo')" then `[ENTER]` to open an alert popup.  

If you need to enter a multi-line Javascript command, then begin your input with `<<<SOME_BOUNDARY`, and end it with `SOME_BOUNDARY`.

For example:

~~~~
<<<END
var url = window.location.href;
alert('You are at '+url);
END
~~~~

NOTE:  If you give it an empty boundary, then it will simply use a blank line as your boundary.

### Getting Information From The Browser

There are two ways get the browser to communicate back to the outside world:

1. The onLoad callback.  Whenever the user nagivates to a new page, it will output `loaded [URL]` to STDOUT.  E.g. If you navigate to google.com, then it will output `loaded https://google.com` to STDOUT.
2. Call `window.postMessageExt("some message")`.  This will cause the browser print "some message" to STDOUT.  All messages of this kind are wrapped with beginning and ending boundaries to make the output easier to parse, in case you are writing a program to interact with the browser.

Here is an example of a session, where I load google.com, and then get its page title via `window.external.invoke()`:

~~~~
$ java -jar WebView-shaded.jar "https://www.google.com"

loaded https://www.google.com/
window.external.invoke(document.title)
<<<Boundary1575660241187
Google
Boundary1575660241187
~~~~

A few things to notice here:

1. When the page is loaded, it informed us with "loaded https://www.google.com" in STDOUT
2. I typed the "window.external.invoke(document.title)" command.
3. It responded to my command with an open boundary `<<<Boundary1575660241187` followed by the message ("Google"), followed by the closing boundary `Boundary1575660241187`

## Using Java API

If you want to use the webview directly in your Java app, you can do this also. 

A simple usage example:

~~~~
webview = new WebView()
    .size(width, height)
    .title(title)
    .resizable(resizable)
    .fullscreen(fullscreen)
    .url(u)
    .onLoad(()->{
       //.. Do something on page load.
	   // You can get the url of the page via webview.url()
    })
    .javascriptCallback(message->{
        // Handle a message sent via window.external.invoke(message)
        // message is a string.
    })
    .show();
~~~~

NOTE: The `show()` method will start a blocking event loop.

WARNING: Currently the WebView is picky about being started on the main application thread.  On Mac you may need to add the "-XstartOnFirstThread" flag in the JVM.

## Embedding WebView Directly in Swing

In addition to the out-of-process `WebViewCLIClient` approach, the library
includes an in-process Swing embedding API in the
`ca.weblite.webview.swing` package.  The recommended entry point is the
abstract `WebViewComponent` and its static factory, which picks the best
implementation for the current platform automatically:

```java
import ca.weblite.webview.swing.WebViewComponent;

WebViewComponent wv = WebViewComponent.create();
wv.setUrl("https://example.com");
wv.setPreferredSize(new Dimension(900, 600));
frame.add(wv, BorderLayout.CENTER);
```

`WebViewComponent.create()` returns a heavyweight implementation on
macOS / Windows and a lightweight one on Linux (where the heavyweight
path's visible text-input feedback is unreliable).  To force a specific
mode, set the `ca.weblite.webview.mode` system property to
`heavyweight` or `lightweight` (case-insensitive), or call
`WebViewComponent.create(Mode.HEAVYWEIGHT)` / `Mode.LIGHTWEIGHT`
explicitly.

Internally there are two concrete subclasses, both extending
`WebViewComponent`, which you can also instantiate directly if you
need to:

* **`WebViewHeavyweightComponent`** — embeds the native WebView as a child
  of the underlying heavyweight AWT peer.  Renders directly to screen
  pixels.  Native compositing means the highest fidelity and lowest
  overhead, but interacts with Swing Z-order the way every heavyweight
  AWT component does — it paints above any overlapping lightweight Swing
  components in the same window (see "Heavyweight popup notes" below).
* **`WebViewLightweightComponent`** — renders the WebView into an
  offscreen surface, ships the pixels to Java, and Swing paints them
  into a regular `JComponent`.  Composites cleanly with arbitrary Swing
  widgets and Z-order.  Higher per-frame cost than heavyweight and
  needs Swing-side input forwarding (which is wired for mouse and
  keyboard).

See the [Heavyweight Swing Demo](demos/WebViewHeavyweightDemo/README.md)
for a working example exercising both modes side-by-side, plus
interaction with surrounding Swing widgets (JComboBox dropdowns, tab
switching).

### Platform support matrix

| Platform | Heavyweight | Lightweight |
|---|---|---|
| **macOS** (Cocoa / WKWebView) | ✅ Full (rendering, input, resize, tab visibility) | ⚠️ Stub — falls back to default Swing background |
| **Linux** (WebKitGTK / X11) | ⚠️ Rendering, mouse, scroll, resize, tab switching work.  Visible text-input feedback (caret blink, characters appearing as typed) is **unreliable** because of how GTK frame-clock and focus interact with `XReparentWindow` under a foreign (non-GTK) parent. | ✅ **Full** — rendering + mouse (click, drag, scroll, hover) + keyboard (typing, Backspace, Delete, arrows, function keys, common modifiers) |
| **Windows** (WebView2) | ✅ Full (rendering, input, resize, tab visibility) on Windows 11.  Requires the system-wide Microsoft Edge WebView2 Runtime (ships with current Windows 11 / Edge; install Evergreen from https://developer.microsoft.com/microsoft-edge/webview2/ on older Windows). | ⚠️ Stub |

The `WebViewComponent.create()` factory already encodes these
defaults, so most callers don't need to think about it.  A future
cross-platform lightweight pass would make lightweight a sane default
everywhere.

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

### Lightweight notes

The lightweight component renders WebKit into a `GtkOffscreenWindow`,
snapshots `cairo_image_surface_t` pixels at ~30Hz into a
`BufferedImage`, and paints that into the `JComponent` via
`paintComponent`.  AWT `MouseEvent`s and `KeyEvent`s are translated to
`GdkEvent`s and injected via `gtk_main_do_event`.  Notes:

* The WebKitWebView's IM context is disabled because all input
  arrives already-decoded from AWT.  This means CJK / IME composition
  is **not** available in the lightweight component on Linux today.
  Dead keys and Compose key sequences (e.g. `é`, `ñ`) work for
  ASCII-Latin-1 layouts but not for IME-driven layouts.
* Right-click context menus and `<select>` dropdowns from inside the
  page log a `gdk_window_move_to_rect: assertion 'window->transient_for'`
  warning and don't visibly appear — WebKit tries to position them
  relative to a toplevel that doesn't exist in our offscreen model.
  Not fatal; just a missing piece of UI for those interactions.
* Heavyweight popup interop is *not* needed in lightweight mode —
  Swing components like `JComboBox` and tooltips composite over the
  WebView with their normal lightweight rendering.

### Heavyweight popup notes

When using `WebViewHeavyweightComponent`, native Swing popups
(`JComboBox` dropdowns, `JMenu`, tooltips) render *behind* the
WebView's heavyweight peer unless you opt into heavyweight popup
mode at app start:

```java
JPopupMenu.setDefaultLightWeightPopupEnabled(false);
ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
```

This makes popups appear as real OS windows that sit above heavyweight
peers.  See the heavyweight demo for the full pattern.  Lightweight
mode does not need this.

The embedded WebView does **not** call `webview_run()` and never takes
ownership of the host application's event loop.

## Using Java API from Swing, JavaFX, or other UI Toolkit (subprocess mode)

The original `WebView` class cannot be used directly from Swing, JavaFX, or
any other existing UI toolkit because it starts its own event loop.  If you
want to use that class from such an app, you can use the `WebViewCLIClient`
class, which provides an interface to create and manage a WebView running
in its own subprocess.

See the [Swing Demo](demos/WebViewSwingDemo/README.md) for a full example of this.

The basics are:

~~~~

// Opening the webview
WebViewCLIClient webview = (WebViewCLIClient)new WebViewCLIClient.Builder()
    .url("https://www.codenameone.com")
    .title("Codename One")
    .size(800, 600)
    .build();
    
// Adding a load listener (fired whenever a page loads)
webview.addLoadListener(evt->{
    System.out.println("Loaded "+evt.getURL());
});

// Adding a message listener (fired whenever any js calls window.postMessageExt(msg))
webview.addMessageListener(evt->{
    System.out.println(evt.getMessage());
});

// Evaluate javascript on the current page.  Implicit callback() method
// allows you to return result in CompetableFuture.
webview.eval("callback(window.location.href)")
    .thenAccept(str->{
        System.out.println("Current URL is "+str);
    });
    
    

    
// Closing the webview later
webview.close();
~~~~


    


#### Demos

1. [Swing Demo](demos/WebViewSwingDemo/README.md) - A simple demo showing how to create and control a WebView from a Swing App (subprocess mode).
2. [Heavyweight Swing Demo](demos/WebViewHeavyweightDemo/README.md) - In-process demo showing both heavyweight and lightweight embedding modes side-by-side, plus interop with surrounding Swing widgets (JComboBox dropdowns, tab visibility tracking).  Includes `run-mac-demo.sh`, `run-linux-demo.sh`, and `run-windows-demo.bat` one-shot launcher scripts at the project root.
3. [Minimal Demo](demos/WebViewMinimalDemo/README.md) - A simple demo that only launches a WebView on the main thread.

## Supported Platforms

This should work on Mac, Linux, and Windows.


## Building Sources

~~~
git clone https://github.com/shannah/webviewjar
cd webviewjar
ant jar
~~~

This will create dist/WebView.jar, which can be run as an executable jar.

### Troubleshooting

ANT requires that the `platforms.JDK_1.8.home` system property is set to your JAVA_HOME.  If it complains about this, you can fix the issue by changing the `ant jar` command, above, to `ant jar -Dplatforms.JDK_1.8.home="$JAVA_HOME"`.

### Rebuilding Native Libs

The repo comes with pre-built native libs in the src/windows_32, src/windows_64, src/osx_64, and src/linux_64.  If you want to make changes to these native libs, then the following information may be of use to you.

1. Use the `build-xxx.sh` (where xxx is your current platform) scripts to rebuild the native sources, and copy them into the appropriate place in the src directory.
2. Mac and linux native sources are located in the src_c directory.  Windows native sources are in the windows directory.
3. On Windows, you'll need to have Visual Studio installed (I use VS 2019, but earlier versions probably work).  Additionally, I use git bash on Windows, which is why the build-windows.sh is a bash script, and not a .bat script.



## License

MIT

## Credits

1. This library created by [Steve Hannah](https://sjhannah.com)
2. Original webview library by [Serge Zaitsev](https://zserge.com)
