# WebView Heavyweight Demo

A minimal Swing app that embeds a native WebView as a heavyweight component
inside a `JFrame`, using `WebViewHeavyweightComponent`.

## Run

From this directory:

```
ant run
```

Make sure you have built `WebView.jar` first by running `ant jar` at the
project root.

## What it shows

* `WebViewHeavyweightComponent` placed in the center of a `BorderLayout`.
* A URL bar and Go button at the top that re-navigates the embedded WebView.
* The embedded WebView is configured up front (`wv.setUrl(...)`) and the
  native peer is created automatically when the surrounding frame becomes
  visible.
* A `WebViewLightweightComponent` in a second tab so you can flip between
  the two implementations and compare them side-by-side.
* A **JS Bridge** panel at the bottom that drives BOTH webviews and
  exercises every JS-interaction method on the abstract surface --
  `eval`, `addOnBeforeLoad`, `addJavascriptCallback`, and `dispatch`:
  - **Eval**: runs ad-hoc JS against the current document.  Result is
    fire-and-forget; round-trip values back via a binding.
  - **Add onBeforeLoad**: register an init script that runs at the start
    of every new document.  Takes effect on the next navigation.
  - **Register window.swingLog / window.swingPing**: expose Java callbacks
    in the page.  Args arrive as a JSON-array string.
  - **Dispatch hello**: posts a `Runnable` onto each engine's native UI
    thread; it reports back via the log.
  Output from each engine is tagged `[hw]` (heavyweight) or `[lw]`
  (lightweight) so parity is observable at a glance.  Pick the
  `(JS bridge test page)` entry from the Bookmarks dropdown for an
  inline page that wires `window.swingLog`/`window.swingPing` to
  buttons.

## Platform notes

This demo exercises the `webview_embed_*` native entry points added for
Swing/AWT embedding.  See the project README for the current status of each
platform.  In short:

* **Linux (GTK + X11)**: the WebView is reparented under the AWT canvas via
  `XReparentWindow`.  A dedicated GTK pump thread drives the WebKitGTK event
  loop.
* **macOS (Cocoa + WKWebView)**: the WKWebView's `CALayer` is installed as
  the layer of the JAWT-provided surface layers.  See the platform notes in
  the main README about input forwarding.
* **Windows (WebView2)**: a child `HWND` is created under the AWT canvas
  HWND, and a `IWebView2WebView` controller is hosted inside it.  WebView2
  runs on a dedicated worker thread per component.
