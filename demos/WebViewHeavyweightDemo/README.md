# WebView Demo

A Swing app that embeds a native WebView via the
`WebViewComponent.create()` factory and exercises every JS-interaction
method on the abstract surface, plus the console-capture pipeline.

The factory picks the right implementation for the host platform
(heavyweight on macOS / Windows, lightweight on Linux).  Override with
the system property `-Dca.weblite.webview.mode=heavyweight|lightweight`.

## Run

From this directory:

```
ant run
```

Make sure you have built `WebView.jar` first by running `ant jar` at the
project root.  Or use the one-shot launcher scripts at the repo root
(`run-linux-demo.sh`, `run-mac-demo.sh`, `run-windows-demo.bat`) which
build everything and launch this demo.

## What it shows

* **WebView tab** — a `WebViewComponent` created via the factory; URL
  bar and Go button drive it.  `setDebug(true)` is enabled so the
  platform's native DevTools work (Open DevTools button in the JS Bridge
  panel below) and, on Linux debug builds, `console.*` also routes to
  stdout.
* **Log tab** — every `console.log / info / warn / error / debug` call
  from the page appears here, formatted as `[LEVEL] source:line text`.
  Listeners fire on the EDT; the shim is installed at document-start so
  early console calls are captured.  A "Mirror to System.out" checkbox
  forwards the same messages to the host process's stdout.
* **Swing only tab** — pure Swing.  Switch to it and back to verify the
  WebView re-appears.  On heavyweight (macOS / Windows) a
  HierarchyListener on the Canvas hides the native peer when the tab is
  inactive and restores it on the way back; lightweight (Linux) has
  nothing to hide and just stops being painted.
* **Bookmarks dropdown** — verifies heavyweight popup interop: the
  dropdown should appear above the WebView area (`JPopupMenu`
  default-lightweight-enabled is set to `false` at startup).  Toggle
  the "Lightweight popups" checkbox to flip and watch a heavyweight
  WebView swallow the dropdown.
* **JS Bridge panel** at the bottom exercises every JS-interaction
  method on the abstract surface — `eval`, `addOnBeforeLoad`,
  `addJavascriptCallback`, `dispatch`, and `openDevTools`:
  - **Eval** — runs ad-hoc JS against the current document.  Result is
    fire-and-forget; round-trip values back via a binding.
  - **Add onBeforeLoad** — register an init script that runs at the
    start of every new document.  Takes effect on the next navigation.
  - **Register window.swingLog / window.swingPing** — expose Java
    callbacks in the page.  Args arrive as a JSON-array string.
  - **Dispatch hello** — posts a `Runnable` onto the engine's native UI
    thread; it reports back via the panel's log.
  - **Open DevTools** — opens the platform's native Web Inspector /
    Chromium DevTools in a separate OS window.  Returns `false` on
    macOS (no public API; use right-click → Inspect Element).
  Pick the `(JS bridge test page)` entry from the Bookmarks dropdown
  for an inline page that wires `window.swingLog` / `window.swingPing`
  and the five `console.*` levels to buttons.

## Platform notes

This demo exercises both the `webview_embed_*` and
`webview_offscreen_*` native entry points, depending on which mode the
factory chose.  See the project README for the current status of each
platform.  In short:

* **Linux (GTK + X11)** — defaults to lightweight.  WebKit renders into
  a `GtkOffscreenWindow`, Java snapshots pixels into a `BufferedImage`
  and composites them in `paintComponent`.  Force heavyweight with
  `-Dca.weblite.webview.mode=heavyweight` to reparent the GTK window
  under the AWT canvas via `XReparentWindow` — caret blink is
  unreliable but rendering and mouse work end-to-end.
* **macOS (Cocoa + WKWebView)** — defaults to heavyweight.  The
  `WKWebView` is installed as a subview of the JAWT-resolved
  `NSWindow.contentView`.  See the project README for input forwarding
  notes.
* **Windows (WebView2)** — defaults to heavyweight.  A child `HWND` is
  created under the AWT canvas HWND and an `ICoreWebView2Controller` is
  hosted inside it.  WebView2 runs on a dedicated worker thread per
  component.
