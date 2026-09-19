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
    <version>1.0.10</version>
</dependency>
```

The jar bundles the native libraries for macOS, Linux, and Windows — no
additional native install step is required beyond the platform's system
web engine:

* **Windows** requires the system-wide Microsoft Edge WebView2 Runtime,
  which ships with current Windows 11 / Edge.  On older Windows, install
  the Evergreen Runtime from
  <https://developer.microsoft.com/microsoft-edge/webview2/>.
* **Linux** requires a system WebKitGTK — either **4.1** (Ubuntu 22.04+)
  or **4.0** (Ubuntu 20.04).  The bundled `libwebview.so` resolves
  whichever is present at load time (no `webkit2gtk` SONAME is
  hard-linked), so a single jar runs on both.
* **macOS** needs nothing extra — WKWebView ships with the OS.

### Early access via GitHub Packages

Every tagged release is published to GitHub Packages *before* Maven
Central, and is resolvable there the moment the release build finishes —
useful when a downstream project needs to build against a new version
without waiting for Central to sync.

GitHub Packages requires an authenticated read even for public packages,
so this channel is only practical for first-party projects and CI that
already hold a token.  **Maven Central is the canonical channel; external
consumers should use the coordinates above.**

Add the repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github-webliteca</id>
        <url>https://maven.pkg.github.com/webliteca/swingwebview</url>
    </repository>
</repositories>
```

and a matching server entry in `~/.m2/settings.xml`, where the password is
a personal access token with the `read:packages` scope (in GitHub Actions,
use `${{ github.actor }}` and the built-in `GITHUB_TOKEN` instead):

```xml
<server>
    <id>github-webliteca</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_PAT_WITH_READ_PACKAGES</password>
</server>
```

The `<id>` values must match.  Once Maven Central has synced the same
version, the repository entry can be dropped again — the artifacts are
identical.

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

The native embedding layer (`src_c/webview_embed.cpp`) is **quiet by
default** — a normal embed/launch prints no `[webview-embed]` lines to
stderr.  Set `DEBUG_WEBVIEW_EMBED=1` on the way in — for example
`DEBUG_WEBVIEW_EMBED=1 java -jar your-app.jar` — to restore the full
verbose trace: JAWT resolution, the `JAWT_GetAWT` version-mask that
succeeded, GTK reparenting, the WebKit load lifecycle, click/focus
grabs, the repaint timer, navigation, the per-frame `draw#`/frame-clock
instrumentation, and (on macOS) host-`NSView` discovery and
`WKWebView` subview attachment.  Genuine error/failure conditions
(missing `JAWT_GetAWT`, `dlopen`/`dlsym` failures, a rejected JAWT
version mask, a `JAWT_LOCK_ERROR`, a non-X11 `GdkWindow`, a WebKit
`load-failed`, or the macOS layer-only-fallback warning) always print,
regardless of the flag.  The Windows port
(`windows/webview_embed.cc`) already logs only on failure, so it has
no default chatter to silence.

> This flag is read by the native library, so it only takes effect
> once you are running against a native build that includes it —
> the natives are produced by `build-{linux,mac,windows}.sh` / the CI
> release matrix rather than checked into the repo, so downstream
> consumers pick it up after the next native release.
>
> The macOS `ApplePersistenceIgnoreState: Existing state will not be
> touched …` line is emitted by AppKit itself, not by this library,
> so it is unaffected by `DEBUG_WEBVIEW_EMBED`.

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

## Browser-initiated downloads

When the page starts a file transfer — a click on `<a href="…" download>`,
a navigation whose response carries `Content-Disposition: attachment`, or
a navigation to a body the engine will not render inline —
`WebViewComponent.setDownloadHandler` lets the host decide where the bytes
land and watch the transfer through.

```java
wv.setDownloadHandler(new WebViewDownloadHandler() {
    @Override
    public File downloadRequested(WebViewDownloadEvent e) {
        // e.suggestedFileName() is already a safe leaf name.
        return new File(myDownloadsDir, e.suggestedFileName());
    }
    @Override
    public void downloadProgress(WebViewDownloadProgressEvent e) {
        bar.setValue(e.sizeKnown() ? (int) (e.fraction() * 100) : 0);
        bar.setIndeterminate(!e.sizeKnown());
    }
    @Override
    public void downloadCompleted(WebViewDownloadCompleteEvent e) {
        if (e.success()) notifyUser("Saved " + e.destination());
        else notifyUser("Download failed: " + e.failureReason());
    }
});
```

* **The engine writes; you choose where.**  The handler answers one
  question — *which file* — and then observes.  Java is never handed a
  stream: the engine already holds the connection, the cookie jar, and
  the authentication state needed to finish the transfer.
* **Default behaviour.**  With no handler installed, `downloadRequested`
  shows a `JFileChooser` save dialog anchored on the host `JFrame` and
  pre-filled with the server-suggested name, confirming before it
  replaces an existing file.  The two notification methods do nothing.
* **Returning `null` refuses the download.**  Nothing is written
  anywhere — including into the platform's own default downloads folder,
  which is the behaviour this channel exists to take over.
* **`setDownloadHandler(null)` is NOT a reset.**  It installs a drop
  handler that refuses every download with no UI — the headless-test and
  explicit-opt-out path.  Pass `WebViewDownloadHandler.DEFAULT`
  explicitly to restore the save dialog.  Reading `null` as "reset to the
  default" would ship an app that silently refuses every download.
* **Threading.**  All three methods run on the EDT, but
  `downloadRequested` is *synchronous* (`invokeAndWait`, with the
  engine's thread waiting) while progress and completion are
  fire-and-forget (`invokeLater`).  As with
  `WebViewDialogHandler`, calling `wv.evalAsync(js).get()` from inside
  `downloadRequested` **deadlocks**.
* **Progress is lossy; completion is not.**  Progress events are
  coalesced so at most one per download is queued on the EDT at a time —
  a 100 MB transfer reporting every 8 KB would otherwise queue ~12,800
  EDT tasks — so a handler sees the latest counts rather than every
  chunk, and must not do expensive work there.  `downloadCompleted` is
  delivered **exactly once** per download, whatever the backend emits.
* **Unknown size is `-1`, never `0`.**  Use `sizeKnown()` rather than
  testing for a magic value, so a progress bar can tell "no bytes yet,
  10 MB expected" from "some bytes, size unknown".
* **Filenames are sanitised for you.**
  `WebViewDownloadEvent.suggestedFileName()` arrives off the wire and is
  attacker-controlled, so it reaches the handler already reduced to a
  bare leaf name: no separators of either flavour, no `..`, no control
  characters, no characters illegal on Windows, no trailing dots or
  spaces, no reserved Windows device name (`CON`, `NUL`, `LPT1`…),
  bounded to 255 characters, and never empty.  A handler may join it
  onto a directory of its own choosing, but must not join it onto a
  parent path the page can influence.
* **Several downloads at once.**  Every event carries an `id()` that is
  stable from the destination decision through to the terminal report,
  so progress can be attributed to the download that produced it.  The
  destination `File` is not a usable key: it is unknown at request time
  and can repeat across sequential downloads.
* **Platform coverage (current).**  macOS routes downloads through the
  handler by adopting `WKDownloadDelegate` and answering
  `WKNavigationResponsePolicyDownload` (Canvas 23) — **requires macOS
  11.3+**; on older systems `WKDownload` does not exist, downloads
  behave as they did before, and the handler is not invoked.  Linux
  WebKitGTK routes them in both heavyweight and lightweight modes, and
  in popups, via the web context's `download-started` signal (Canvas
  24).  Windows WebView2 routes them via
  `ICoreWebView2_4::add_DownloadStarting` and suppresses the download
  flyout (Canvas 25) — **requires a runtime exposing
  `ICoreWebView2_4`**; on an older runtime WebView2 keeps its built-in
  handling and the handler is not invoked.
* **Not covered.**  Pausing, resuming, or cancelling a download after it
  has started; a built-in downloads list or history; resuming
  interrupted transfers across process restarts.

See [`demos/WebViewDownloadDemo/`](demos/WebViewDownloadDemo/README.md)
for a runnable example that serves five download shapes from a loopback
server and exercises all three handler modes.

## Password manager

The embedded engines (`WKWebView`, `WebKitGTK`, `WebView2`) do **not**
give an embedding app the browser "offer to save this password / autofill
it next time" experience — that is a browser-privileged feature the raw
engine withholds.  `WebViewComponent` provides its own password manager
instead: an injected script detects login-form submissions and the library
shows a Swing "Save password?" prompt; on approval the credential is
written to the **OS-native secret store**; on a later page load a stored
credential for the same origin is auto-filled.

```java
WebViewComponent wv = WebViewComponent.create();
// Enabled by default.  Turn it off with:
wv.setPasswordManagerEnabled(false);

// Programmatic access (works regardless of the enabled flag):
wv.saveCredential(new WebViewCredential("https://example.com", "alice", "s3cret"));
Optional<WebViewCredential> c = wv.getCredential("https://example.com");
List<WebViewCredential> everything = wv.getAllCredentials(); // all origins
wv.deleteCredential("https://example.com", "alice");

// Require a confirmation (or an OS biometric check) before autofill:
wv.setFillPasswordHandler(WebViewFillPasswordHandler.CONFIRM);
```

Key points:

* **Origin keying.** Credentials are keyed by page **origin** =
  scheme + host + port (the default port is implied by the scheme, so
  `https://example.com` and `https://example.com:443` are the same
  origin).  Autofill is **exact-origin only** — a credential for one
  origin is never offered on another (`http` vs `https`, a different
  port, or a different host are all distinct).
* **OS-native storage.**  Passwords live only in the OS secret store —
  macOS **Keychain**, Windows **Credential Manager**, and Linux
  **libsecret** / Secret Service (GNOME Keyring, KWallet, or any
  freedesktop Secret Service provider) are all wired.  On Windows the
  credentials are stored in the library's *own* Credential-Manager
  namespace (per-user, DPAPI-protected), not the Edge profile, and Edge's
  built-in password autosave is disabled so it does not compete with this
  manager.  On Linux libsecret is loaded at runtime (`libsecret-1.so.0`);
  where no Secret Service provider is available the store degrades to a
  no-op.  The library never writes a plaintext credential file and never
  logs a password.
* **Overridable seams.**  `setCredentialStore(WebViewCredentialStore)`
  swaps the backing store (e.g. `InMemoryCredentialStore` for tests);
  `setSavePasswordHandler(WebViewSavePasswordHandler)` replaces the
  "Save password?" policy (return a disposition programmatically for
  headless use).  Passing `null` to either restores the default.  Both
  getters never return `null`.
* **Autofill consent.**  By default a stored credential is filled
  silently on page load.  `setFillPasswordHandler(WebViewFillPasswordHandler)`
  gates that: install `WebViewFillPasswordHandler.CONFIRM` for a
  browser-style "Use the saved password for `<origin>`?" prompt, or your
  own handler that performs an OS biometric / re-authentication check
  (Touch ID, Windows Hello) and returns `DONT_FILL` to decline.  The
  event handed to the handler carries only the origin and username —
  never the password.  Passing `null` restores the silent-autofill
  default; the getter never returns `null`.  The consent handler gates
  the automatic page-load autofill only — the programmatic
  `getCredential` / `getCredentials` / `getAllCredentials` reads are
  trusted host calls and are never gated.
* **Managing saved passwords.**  `getAllCredentials()` enumerates every
  stored credential across all origins (most-recently-saved first) — the
  primitive you need to build a Chrome-style "manage saved passwords"
  screen on top of the OS-native store, combined with `saveCredential`
  (add/edit) and `deleteCredential` (remove).  Like the origin-scoped
  reads it returns credentials to host code only; page JavaScript has no
  path to any stored credential.
* **Security note.**  Once a credential is auto-filled it lives in the
  page DOM and is readable by any script running on that page — exactly
  the same exposure as a browser's autofill.  The library only ever fills
  the single origin-matched credential it chose to send.
* **Coverage: all three platforms.**  Automatic capture / autofill and the
  native secret store are wired on macOS (Keychain), Windows (Credential
  Manager), and Linux (libsecret, both heavyweight and lightweight).  Where
  a platform has no available secret store — notably a headless or
  keyring-less Linux session — the store degrades to a graceful no-op:
  page load and form submission still work, and the programmatic API
  simply reports nothing stored.

Known limitation: multi-step / identifier-first login flows (username and
password on separate pages, e.g. some Okta configurations) are captured
best-effort per page; cross-page correlation is not guaranteed.

See [`demos/WebViewPasswordDemo/`](demos/WebViewPasswordDemo/README.md)
for a runnable example exercising capture, autofill, and the programmatic
API in both the Keychain-backed and in-memory store modes.
[`demos/WebViewPasswordOptInDemo/`](demos/WebViewPasswordOptInDemo/README.md)
shows a **Chrome-style opt-in fill** — silent autofill suppressed, an
account chooser under the focused login field, and a simulated unlock
before the password is filled.

## Browser-initiated popups (`window.open`)

Pages can call `window.open(url, name, features)` or click a link / form
with `target="_blank"`.  `WebViewComponent.setPopupHandler` lets the host
application allow, observe, or block those popups:

```java
wv.setPopupHandler(new WebViewPopupHandler() {
    @Override public boolean popupRequested(WebViewPopupEvent e) {
        return e.targetUrl().startsWith("https://");   // allow only https
    }
    @Override public void popupOpened(WebViewPopupEvent e) {
        System.out.println("popup: " + e.targetUrl());
    }
});
```

* **Native-owned window.**  When a popup is allowed the *native engine*
  creates the child web view **linked to the opener** and hosts it in a
  fresh native top-level window that the engine sizes, shows, and
  destroys.  The opener linkage is what makes OAuth "sign-in with popup"
  flows work — the popup calls `window.opener.postMessage(...)` then
  `window.close()`, which only succeed when the popup is a real linked
  view rather than an independent tab.  The handler only decides *policy*
  (`popupRequested`) and *observes* the lifecycle (`popupOpened` /
  `popupClosed`); it does not open, host, or size the window.
* **Default behaviour.**  With no handler installed every popup is allowed.
* **Blocking popups.**  Pass `null`: `wv.setPopupHandler(null)` blocks all
  popups (`window.open` returns `null`) — the pre-feature behaviour,
  available as an explicit opt-out.  To reset to the framework default
  (allow), pass `WebViewPopupHandler.DEFAULT` explicitly — `null` is NOT a
  reset.
* **Threading.**  `popupRequested` runs on the **native UI thread**,
  synchronously and **off the EDT** (the platform popup callback must
  return the allow/deny decision before yielding to the browser engine);
  keep it fast, thread-safe, and free of Swing access.  `popupOpened` /
  `popupClosed` are asynchronous notifications delivered on the EDT.
* **Platform coverage.**  All three engines open native, opener-linked
  popup windows: macOS heavyweight (WKWebView) via the `WKUIDelegate
  createWebViewWithConfiguration:` / `webViewDidClose:` pair (Canvas 15);
  **Linux** heavyweight *and* lightweight (WebKitGTK `create` /
  `ready-to-show` / `close` signals, Canvas 16); and **Windows**
  (WebView2 `NewWindowRequested` + the child's `WindowCloseRequested`,
  Canvas 17).  `setPopupHandler(null)` blocks `window.open` on every
  platform.

See [`demos/WebViewPopupDemo/`](demos/WebViewPopupDemo/README.md) for a
runnable example that exercises the allow / custom / block modes.

### Adopting popups into a component (a tab)

By default an allowed popup opens in a **native window** the engine owns
(above).  A tabbed browser usually wants the popup to appear as a **new
tab** instead.  Blocking the native window and re-opening
`e.targetUrl()` with `setUrl(url)` does *not* work for that: `setUrl`
issues a **GET**, so a `<form method="post" target="…">` popup loses its
POST body and the re-opened page is no longer opener-linked.  The POST
body is not exposed to the popup channel on any engine, so the request
cannot be replayed from Java.

Instead, **adopt** the engine's own opener-linked child — the view
WebKit already drove the original request (POST verb + body) into — into
a `WebViewComponent` you supply:

```java
wv.setPopupHandler(new WebViewPopupHandler() {
    // 1. Decide ADOPT on the native UI thread (synchronous, off the EDT).
    @Override public PopupDisposition popupDisposition(WebViewPopupEvent e) {
        return PopupDisposition.ADOPT;   // not a native window
    }
    // 2. On the EDT, host the retained child in a new tab.
    @Override public void popupAdoptable(WebViewPopupEvent e, long popupId) {
        WebViewComponent tab = WebViewComponent.adoptPopup(popupId);
        myTabbedPane.addTab("Popup", tab);   // realizing it adopts the child
    }
});
```

* **POST + opener preserved.**  The adopted component reuses the engine's
  child, so `<form method="post">` popups keep their body and
  `window.opener` / `postMessage` keep working — the same guarantee the
  native-window path has, now in a tab.
* **Two-phase, no flash.**  `popupDisposition` returns `ADOPT`
  synchronously on the native UI thread (same rules as `popupRequested`:
  fast, thread-safe, no Swing).  The engine creates the child but shows
  **no window**; it fires `popupAdoptable` on the EDT, where you build the
  tab and call `WebViewComponent.adoptPopup(popupId)`.  Adoption happens
  when that component is realized.
* **Backward compatible.**  `popupDisposition` defaults to deriving from
  `popupRequested` (`true → NATIVE_WINDOW`, `false → BLOCK`), so existing
  handlers and `setPopupHandler(null)` are unchanged.
* **Adopt-once / reclaim.**  A `popupId` may be adopted once; an unknown
  or already-adopted id throws (`IllegalArgumentException` /
  `IllegalStateException`).  A child decided `ADOPT` but never adopted is
  reclaimed when the opener is disposed or after a bounded grace period.
* **Platform coverage.**  The reference backend is **macOS heavyweight**
  (WKWebView; the retained child is reparented into the tab's `NSView`).
  Linux (WebKitGTK) and Windows (WebView2) adoption, and lightweight /
  offscreen adoption, are follow-up work; on those the native adopt is
  not yet wired, so `adoptPopup` there fails fast rather than silently
  losing the popup.  The native adoption code ships pattern-faithful to
  the existing popup handlers but **must be validated on-device** (no
  native toolchain runs in the code-generation sandbox).

## Custom user agent

Some web apps gate on the `User-Agent`. WKWebView's default UA omits the
`Version/… Safari/…` tokens, so UA-sniffing sites can reject the embedded
WebView. Override it with `setUserAgent` — this changes the **actual HTTP
`User-Agent` request header** (not just the JS-visible
`navigator.userAgent`):

```java
WebViewComponent wv = WebViewComponent.create();
wv.setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15");
wv.setUrl("https://example.com/");   // first request carries the custom UA
```

* **Reset.** `setUserAgent(null)` or `setUserAgent("")` restores the engine
  default. `getUserAgent()` returns the override, or `null` when the default
  is in force.
* **Timing.** Called before display, it applies to the first request. Called
  after display, it applies to the **next** navigation (engines don't rewrite
  the in-flight request for the current page).
* **Popups inherit it.** A browser-initiated popup (`window.open`,
  `target="_blank"`, or a POST that targets a new window) — whether adopted
  into a `WebViewComponent` tab or opened in a native window — carries the
  opener's custom UA on its **first** request. When the opener has no override,
  the popup uses the engine default.
* **Platform coverage.** macOS `WKWebView.customUserAgent`, Linux WebKitGTK
  `webkit_settings_set_user_agent`, Windows WebView2
  `ICoreWebView2Settings2::UserAgent`. The native setters ship
  pattern-faithful but must be validated on-device (confirm the header via an
  echo endpoint).

## Per-host user agent

One static UA cannot satisfy two sites that want opposite things. Slack
rejects the engine's own UA as an unsupported browser and wants a mainstream
desktop Chrome string; Google's sign-in, presented that same Chrome UA by a
WebKit engine, scores it as a spoof — the claimed Chrome build has no
`navigator.userAgentData`, sends no `Sec-CH-UA` client hints, and carries a
WebKit TLS fingerprint — and answers with a CAPTCHA that cannot be passed at
any score.

`setUserAgentResolver` picks the UA per destination instead:

```java
WebViewComponent wv = WebViewComponent.create();
wv.setUserAgent(SAFARI_UA);                  // the default for everything
wv.setUserAgentResolver(url ->
    url.contains("://app.slack.com/") ? CHROME_UA : null);   // null = fall through
wv.setUrl("https://app.slack.com/client");   // first request carries CHROME_UA
```

* **Precedence.** The resolver's answer wins when it is non-null and
  non-blank; otherwise the static `setUserAgent` value applies; otherwise the
  engine default. A `null` or blank return means *fall through*, **not** "use
  the engine default" — a resolver can never force the engine default over a
  static override.
* **When it is consulted.** At three points, each a navigation that is about
  to start and that the library controls: before a view's initial navigation,
  before any `setUrl` on a live view, and before a browser-initiated pop-up
  child's first navigation — keyed on the **pop-up's own target URL**. That
  last one is the point of the feature: an OAuth sign-in popped out of a site
  that needs a spoofed UA lands on an identity provider that penalises exactly
  that spoof. When a resolver is installed it supersedes the opener-copy
  described under *Popups inherit it* above; with no resolver, opener-copy is
  unchanged.
* **Limitation.** This is not per-request switching. A server-side redirect
  that crosses hosts *during* a navigation keeps the UA that navigation
  started with. Intercepting every navigation was considered and rejected:
  WebKit does not reliably expose `NSURLRequest.HTTPBody` to
  `decidePolicyForNavigationAction` for form POSTs, so the
  cancel-set-UA-reissue pattern would silently drop OAuth form-post bodies.
* **Threading.** The resolver runs on the engine UI thread immediately before
  the navigation it governs, so keep it fast and non-blocking — a host-suffix
  check, not a network call. A resolver that throws is treated as a `null`
  return and can never break a navigation.
* **Platform coverage.** The pop-up path upcalls the resolver natively on
  macOS, Linux and Windows; the other two points resolve in Java. As with the
  UA setters, the native upcall ships pattern-faithful but must be validated
  on-device (`WebViewAdoptPopupDemo`'s **Resolver** toggle plus the
  `https://httpbin.org/user-agent` echo).

## Clear cache

When a site renders blank because a stale (or poisoned) cached resource is
replayed on every load — the classic symptom where the Web Inspector's
"Disable Caches" makes it work — purge the engine's **HTTP resource cache**
with `clearCache()`, then reload:

```java
WebViewComponent wv = WebViewComponent.create();
// … after the page has loaded blank from cache …
wv.clearCache();
wv.eval("location.reload()");   // refetch from the network
```

* **Resource cache only — login survives.** `clearCache()` drops the disk +
  memory HTTP cache and nothing else: cookies, local storage, IndexedDB, and
  service-worker registrations are left intact, so a session reached through a
  logged-in link stays logged in. It reaches a cache that page JavaScript
  (`caches.delete()` / unregistering a service worker) cannot.
* **Timing.** The native purge is asynchronous and runs on the engine's UI
  thread; trigger a navigation after it (re-`setUrl` or an
  `eval("location.reload()")`) to force the refetch. A no-op when no native
  peer is attached — safe to call headless.
* **Platform coverage.** macOS `WKWebsiteDataStore` disk+memory cache types,
  Linux WebKitGTK `webkit_web_context_clear_cache`, and Windows WebView2
  `ICoreWebView2Profile2::ClearBrowsingData(DISK_CACHE)` — all three
  implemented and compiled by CI's cross-platform native build. The purges
  should still be spot-checked on-device (confirm a previously-cached resource
  is re-requested, and cookies/login survive).

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
* `demos/WebViewPasswordDemo/` — exercises the built-in password
  manager: login-submission capture + "Save password?" prompt,
  autofill on reload, and the programmatic
  `saveCredential` / `getCredential` / `deleteCredential` API, in both
  the OS-Keychain and in-memory store modes.

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
