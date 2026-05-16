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
