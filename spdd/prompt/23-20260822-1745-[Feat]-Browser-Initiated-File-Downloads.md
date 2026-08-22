---
generated_at: 2026-08-22T17:45:00-07:00
---

# REASONS Canvas: Browser-Initiated File Downloads — Java API + macOS WKWebView Coverage

## R · Requirements

- Establish the cross-platform Java contract for **file downloads**
  started inside the embedded page — a click on `<a href="…" download>`,
  a navigation whose response carries `Content-Disposition: attachment`,
  and a navigation to a body the engine will not render inline — and
  ship the macOS (WKWebView) implementation of that contract. Linux
  WebKitGTK coverage is Canvas 24; Windows WebView2 coverage is Canvas
  25. Today `WebViewComponent` has no download channel at all: on macOS
  no `WKDownloadDelegate` is adopted and the navigation delegate never
  answers `WKNavigationResponsePolicyDownload`, so `WKWebView` discards
  every transfer silently — nothing lands on disk and nothing is
  reported. This canvas closes the last of the three browser-initiated
  channels; dialogs are Canvas 11-13 and popups are Canvas 15-17.

- **Ownership model: the native engine writes the bytes; Java chooses
  the destination.** The handler answers one question — *which file* —
  and then observes. Java is never handed a stream: moving the transfer
  onto a Java thread would lose the engine's connection reuse, cookie
  jar, and authentication state, and would turn a native-driven stream
  into a backpressure problem across JNI. The engine already holds the
  right context to complete the download; this canvas only redirects
  where it puts the result.

- Expose a single public interface
  `ca.weblite.webview.WebViewDownloadHandler` with three `default`
  methods:
  - `File downloadRequested(WebViewDownloadEvent event)` — the
    destination decision. Return the `File` the bytes must be written
    to, or `null` to **refuse** the download (nothing is written
    anywhere, including the platform's own default download folder).
    **Runs on the Swing EDT**, marshalled with `invokeAndWait`, with
    the native engine's thread blocked awaiting the answer — the same
    shape `DialogDispatcher` already uses for `alert` / `confirm` /
    `prompt` / file picker. The default shows a `JFileChooser` save
    dialog anchored on the host window, pre-filled with
    `event.suggestedFileName()`, and confirms before overwriting an
    existing file.
  - `void downloadProgress(WebViewDownloadProgressEvent event)` —
    notification that more bytes have arrived. Runs on the EDT
    (`invokeLater`). Default is a no-op. **Lossy but monotonic**: the
    dispatcher coalesces bursts so at most one progress event per
    download is queued at a time, so a handler sees the latest counts
    rather than every chunk. A handler MUST NOT do expensive work here.
  - `void downloadCompleted(WebViewDownloadCompleteEvent event)` —
    the single terminal outcome. Runs on the EDT (`invokeLater`).
    Default is a no-op. **Delivered exactly once per download**,
    enforced Java-side by a latch, because no backend guarantees it
    natively and WebKitGTK in particular can emit both `failed` and
    `finished` for one abandoned transfer.
  - `WebViewDownloadHandler DEFAULT` — the stock instance, as
    `WebViewDialogHandler.DEFAULT` and `WebViewPopupHandler.DEFAULT`.

- `WebViewComponent` gains `setDownloadHandler(WebViewDownloadHandler)`
  and `getDownloadHandler()`, both `final`, reproducing the established
  null-versus-DEFAULT asymmetry **exactly**:
  - `setDownloadHandler(null)` installs an internal **drop** handler
    that refuses every download with no UI and no file. This is the
    headless-test and explicit-opt-out path.
  - `setDownloadHandler(WebViewDownloadHandler.DEFAULT)` resets to the
    stock save dialog.
  - These are NOT equivalent, and the Javadoc must say so, because a
    caller who reads `null` as "reset to default" would ship an
    application that silently refuses every download.
  - `getDownloadHandler()` never returns `null`.

- **Download identity.** Several downloads may be in flight on one
  component at once. Every event carries a `long id`, minted by the
  native layer when the transfer starts and stable through the terminal
  event, so a host can attribute progress to the download that produced
  it. Identity is NOT the destination `File`: the destination is unknown
  at request time and can legitimately repeat across sequential
  downloads, which would make two concurrent transfers indistinguishable
  exactly when distinguishing them matters. This mirrors the `popupId`
  the popup channel already threads through `dispatchPopupOpened` /
  `dispatchPopupClosed`.

- **Untrusted filenames.** `suggestedFileName` arrives from the wire
  (`Content-Disposition`, or the URL's last path segment) and is
  attacker-controlled. It reaches the handler **already sanitised** to a
  bare leaf name: no directory separators of either flavour, no
  parent-directory segments, no control characters, no characters
  illegal on Windows, no trailing dots or spaces, no reserved Windows
  device name, bounded in length, and never empty. Sanitisation happens
  once, in `DownloadDispatcher`, not three times in three native
  languages — `DialogDispatcher.normaliseExtensions` /
  `normaliseMimeTypes` set this precedent, and the dispatcher is the
  only place a unit test can reach without a live engine.

- **Unknown size is `-1`, never `0`.** `totalBytes()` returns `-1` when
  the server declared no `Content-Length`, matching
  `WebViewPopupEvent`'s `-1` for unspecified width/height. A host
  rendering a progress bar must be able to tell "no bytes yet, 10 MB
  expected" from "some bytes, size unknown".

- **Refusal is a terminal outcome.** When `downloadRequested` returns
  `null` (or throws, or the dispatcher is disposed), the dispatcher
  itself emits exactly one unsuccessful `downloadCompleted` and latches
  the id, so a subsequent native failure event for the same download is
  swallowed rather than double-reported.

- **Disposal is authoritative.** After `disposeAll()`, every dispatch
  returns its safe fallback (refuse / drop) without touching the
  handler, so a download that outlives its component cannot fire into a
  torn-down Swing tree or a freed JNI global ref.

- macOS coverage: adopt `WKNavigationDelegate` and `WKDownloadDelegate`
  on the existing per-engine delegate object, implement
  `webView:navigationResponse:didBecomeDownload:` and
  `webView:navigationAction:didBecomeDownload:`, answer
  `WKNavigationResponsePolicyDownload` from
  `webView:decidePolicyForNavigationResponse:decisionHandler:` when the
  response is not renderable or carries an attachment disposition, and
  bridge `download:decideDestinationUsingResponse:suggestedFilename:completionHandler:`,
  `downloadDidFinish:` and `download:didFailWithError:resumeData:` to
  the Java callback. All of it gated behind an availability check —
  `WKDownload` is macOS 11.3+, and on older systems the component must
  keep loading pages and running scripts, degrading to today's discard
  behaviour rather than failing to construct.

- **macOS progress comes from KVO, not from a delegate method.**
  `WKDownloadDelegate` has no byte-count callback — unlike WebKitGTK's
  `received-data` and WebView2's `BytesReceivedChanged`, the only
  progress `WKDownload` exposes is its `progress` property, an
  `NSProgress`. macOS progress is therefore observed by registering a
  KVO observer on that `NSProgress`'s `completedUnitCount` key path and
  reading `completedUnitCount` / `totalUnitCount` in the callback, using
  the same `objc_allocateClassPair` + associated-object observer pattern
  the engine already uses for `NSWindow.firstResponder`. The observer is
  removed when the download reaches a terminal state, and again at
  engine teardown for any download still in flight — an NSProgress
  outliving its observer is an AppKit hard error, not a warning.

- **The destination decision must not block AppKit main.** The handler
  runs on the Swing EDT, and the stock handler opens a modal
  `JFileChooser`, which itself needs AppKit main. Blocking AppKit main
  while waiting for it would deadlock instantly. macOS therefore uses
  the deferral shape the dialog channel already established in
  `impl_run_alert`: copy the completion-handler block to the heap,
  return from the selector immediately, run the JNI hop on a detached
  worker thread, and `dispatch_async` back onto the main queue to invoke
  the completion handler with the answer. This is the macOS expression
  of "the engine's thread is blocked awaiting the decision" — the
  transfer is deferred, AppKit main is not.

- **Non-goals for this canvas** (deliberately deferred, as Canvas 11
  deferred `window.open`):
  - Pausing, resuming, or cancelling a download after it has started.
    Neither WebKitGTK nor WebView2 exposes the same control set, and a
    live per-download native handle on the Java side brings its own
    disposal rules. Revisit once the one-way channel is proven.
  - A built-in downloads list, download history, or any UI beyond the
    default save dialog.
  - Resuming interrupted transfers across process restarts
    (`resumeData` is accepted from the native side and discarded).
  - Downloads the host application starts itself — an application that
    wants to fetch a URL uses its own HTTP client.
  - Linux (Canvas 24) and Windows (Canvas 25) native wiring. The Java
    contract in this canvas is final; those canvases add no Java API.

## E · Entities

### `WebViewDownloadEvent` (public final class)

Immutable carrier of one download request, handed to
`downloadRequested`. Package-private constructor. Mirrors
`WebViewFilePickerEvent`: null `source` throws NPE named `"source"`,
null strings coerce to `""`, numeric "unspecified" is `-1`.

| Accessor | Type | Contract |
|---|---|---|
| `source()` | `WebViewComponent` | The component the download started in. Never null. |
| `id()` | `long` | Native download identity, stable across every event for this download. |
| `url()` | `String` | The URL the bytes come from. Never null, may be `""`. |
| `suggestedFileName()` | `String` | **Already sanitised** leaf name. Never null, never empty. |
| `mimeType()` | `String` | Server-declared content type, lower-cased. Never null, may be `""`. |
| `totalBytes()` | `long` | Expected size, or `-1` when the server declared none. |
| `sizeKnown()` | `boolean` | `totalBytes() >= 0`. |
| `pageUrl()` | `String` | The page that initiated the download. Never null, may be `""`. |
| `toString()` | `String` | Single-line summary; `url` truncated to 80 chars. No `equals` / `hashCode`. |

### `WebViewDownloadProgressEvent` (public final class)

| Accessor | Type | Contract |
|---|---|---|
| `source()` | `WebViewComponent` | Never null. |
| `id()` | `long` | Matches the originating `WebViewDownloadEvent.id()`. |
| `destination()` | `File` | The file the handler chose. Never null. |
| `receivedBytes()` | `long` | Bytes written so far. Monotonically non-decreasing across events for one id. |
| `totalBytes()` | `long` | Expected size, or `-1` when unknown. Never less than `receivedBytes()` when known. |
| `sizeKnown()` | `boolean` | `totalBytes() >= 0`. |
| `fraction()` | `double` | `receivedBytes / totalBytes` clamped to `[0.0, 1.0]`, or `-1.0` when the size is unknown. Convenience for progress bars. |
| `toString()` | `String` | Single-line summary. |

### `WebViewDownloadCompleteEvent` (public final class)

| Accessor | Type | Contract |
|---|---|---|
| `source()` | `WebViewComponent` | Never null. |
| `id()` | `long` | Matches the originating `WebViewDownloadEvent.id()`. |
| `destination()` | `File` | The file the handler chose, or `null` when the download was refused before a destination existed. |
| `success()` | `boolean` | `true` only when the complete body reached `destination()`. |
| `failureReason()` | `String` | Human-readable description. Never null; `""` when `success()`. |
| `receivedBytes()` | `long` | Bytes written before the outcome; `0` for a refusal. |
| `toString()` | `String` | Single-line summary. |

### `WebViewDownloadHandler` (public interface)

Three `default` methods plus the `DEFAULT` constant, per Requirements.
Class Javadoc documents: the engine-writes / Java-chooses model; the
EDT threading split (decision via `invokeAndWait`, notifications via
`invokeLater`); the `evalAsync(js).get()` self-deadlock hazard inside
`downloadRequested`, carried over verbatim in spirit from
`WebViewDialogHandler`; the `setDownloadHandler(null)` drop shortcut and
its non-equivalence to `DEFAULT`; that `suggestedFileName` is already
sanitised and MUST still not be joined onto an attacker-influenced
parent path by the handler; that progress is coalesced and lossy while
completion is exactly-once; and that exceptions thrown from any method
are isolated and treated as a refusal (for `downloadRequested`) or
ignored (for the notifications).

### `WebViewDownloadCallback` (public interface — internal JNI bridge)

`public` only so native code can resolve methods on it, exactly as
`WebViewDialogCallback` and `WebViewPopupCallback` are. Javadoc must say
plainly it is not application API.

```java
String onDownloadRequested(long id, String url, String suggestedFileName,
                           String mimeType, long totalBytes, String pageUrl);
void   onDownloadProgress(long id, long receivedBytes, long totalBytes);
void   onDownloadCompleted(long id, boolean success, String failureReason,
                           long receivedBytes);
```

`onDownloadRequested` returns the **absolute destination path**, or
`null` to refuse; the native side blocks on it and MUST NOT marshal it
itself. The other two are fire-and-forget. Note the progress and
completion signatures do NOT carry the destination — the dispatcher
already knows it from the request and correlates by `id`, which keeps
the native side from having to retain the path.

### `DownloadDispatcher` (public final class)

Per-component fan-out hub. `public` for the same cross-package reason as
`DialogDispatcher` / `PopupDispatcher`.

Fields:
- `private final WebViewComponent source`
- `private volatile WebViewDownloadHandler handler = WebViewDownloadHandler.DEFAULT`
- `private volatile boolean disposed = false`
- `private final ConcurrentHashMap<Long, Active> active`
- `static final WebViewDownloadHandler DROP` — refuses everything:
  `downloadRequested` returns `null`, both notifications are no-ops.

`Active` (private static final nested class) holds one in-flight
download: `final File destination`, `final AtomicBoolean terminal`,
`final AtomicLong received`, `final AtomicLong total`, and
`final AtomicBoolean progressQueued` (the coalescing gate).

Public surface: `setHandler`, `getHandler`, `disposeAll`, `isDisposed`,
`dispatchDownloadRequested`, `dispatchDownloadProgress`,
`dispatchDownloadCompleted`, plus the package-private
`static String sanitiseFileName(String, String)` so tests can reach it.

### macOS native entities (`src_c/webview_embed.cpp`)

- `Engine::download_callback` — JNI global ref, `nullptr` when unset.
  Cleared before view teardown, as `click_callback` already is.
- `Engine::next_download_id` — `long long`, monotonic per engine.
- A `DownloadCtx` heap struct per in-flight `WKDownload`, associated
  with the download object, holding the engine pointer, the id, and the
  bytes-written counter.
- New selectors on the existing per-engine delegate object (the class
  that already carries the `WKUIDelegate` selectors and the `"eng"`
  associated object): the response-policy method, the two
  `didBecomeDownload:` navigation-delegate methods, and the three
  `WKDownloadDelegate` methods (`decideDestinationUsingResponse:`,
  `downloadDidFinish:`, `didFailWithError:resumeData:`).  The same
  object is installed as both `uiDelegate` and `navigationDelegate`;
  it adopts `WKNavigationDelegate` and `WKDownloadDelegate` alongside
  `WKUIDelegate`.
- `WebviewEmbedDownloadObserver` — a KVO observer class for the
  `NSProgress` behind each in-flight `WKDownload`, since
  `WKDownloadDelegate` reports no byte counts.  Registered on
  `completedUnitCount`; removed on the terminal event and at engine
  teardown.
- `g_download_map` — a process-global `std::map<id, DownloadCtx *>`
  from `WKDownload` to its context, guarded by its own mutex, matching
  the existing `g_webview_map` / `g_retained_popups` idiom.  A map,
  rather than an `OBJC_ASSOCIATION_ASSIGN` associated object, because
  the context must be *freed* at a definite point and an ASSIGN
  association frees nothing.

## A · Approach

1. **Reuse the established channel shape rather than inventing one.**
   Handler interface with `default` methods and a `DEFAULT` constant;
   immutable event POJOs; an internal JNI callback interface; a
   per-component dispatcher holding one volatile handler, marshalling to
   the EDT, isolating exceptions, and gating on a `disposed` flag. Three
   channels with three different shapes would be a maintenance tax for
   no benefit, and the null-versus-DEFAULT asymmetry in particular is
   already documented behaviour callers rely on.

2. **The decision blocks; the notifications do not.** `invokeAndWait`
   for `downloadRequested` — the native side genuinely cannot proceed
   without a path, exactly like a `confirm` dialog. `invokeLater` for
   progress and completion — a 100 MB download firing one EDT task per
   8 KB chunk would queue ~12,800 tasks and starve the UI.

3. **Coalesce progress in the dispatcher, not natively.** Each `Active`
   carries a `progressQueued` flag. `dispatchDownloadProgress` always
   stores the latest counters into the `Active`'s atomics, then
   `compareAndSet(false, true)` on the gate; only the thread that wins
   enqueues an EDT task, and that task clears the gate and reads
   whatever the latest counters are at the moment it runs. The result is
   at most one queued progress event per download, always carrying
   current numbers, with no lock and no unbounded queue. Doing this
   natively would mean writing it three times and getting the memory
   model wrong at least once.

4. **Latch the terminal event.** `Active.terminal` is an
   `AtomicBoolean`; every path that would report a terminal outcome —
   native finish, native failure, refusal, throwing handler, disposal —
   goes through `compareAndSet(false, true)` and gives up if it loses.
   This is what makes AC7 and AC8's "exactly once" true regardless of
   what the backend emits, and it is the reason the dispatcher, not the
   native layer, owns the guarantee.

5. **Sanitise once, in Java, before the event is built.** One
   implementation, unit-testable with no engine, applying the union of
   all three platforms' rules (POSIX and Windows separators, Windows
   reserved characters and device names, control characters, trailing
   dots and spaces, length bound) so a name that is safe on the
   developer's machine is safe on the user's.

6. **Correlate by id, hold state only for live downloads.** The
   `Active` map is populated when a destination is chosen and removed
   when the terminal event fires. A refused download never enters the
   map but its id is latched via a short-lived entry so a late native
   failure is swallowed. Progress for an unknown id is dropped
   silently — that is the normal shape of a race between a terminal
   event and an in-flight chunk notification, not an error.

7. **macOS: answer the policy question, then adopt the delegate.**
   `WKWebView` only produces a `WKDownload` if the navigation-response
   policy says so, so
   `webView:decidePolicyForNavigationResponse:decisionHandler:` must
   answer `WKNavigationResponsePolicyDownload` when
   `-[WKNavigationResponse canShowMIMEType]` is false or the response's
   `Content-Disposition` starts with `attachment`; otherwise `Allow`.
   Both `didBecomeDownload:` callbacks then set the engine's delegate
   object as the download's `delegate`.

8. **Gate every macOS download selector behind availability.** The
   selectors are added to the delegate class unconditionally (an unused
   selector costs nothing), but the response-policy method only answers
   `Download` when `WKDownload` is actually available at runtime,
   checked once per process with `NSClassFromString(@"WKDownload") != nil`.
   On macOS 11.2 and older the policy method always answers `Allow`,
   which is exactly today's behaviour. This mirrors how the Windows
   engine already gates `ICoreWebView2_13` behind a `QueryInterface`
   rather than assuming the interface exists.

9. **Free the JNI global ref before the view is destroyed.** A download
   can outlive the page, the navigation, and plausibly the component, so
   a late event firing into a freed ref is a SIGSEGV rather than an
   exception. `cocoa_destroy_engine` clears `download_callback` (and
   nulls the field) *before* tearing down the web view, matching the
   ordering already documented for `click_callback` in
   `gtk_destroy_engine`, and every `fire_download_*` helper null-checks
   the ref first.

10. **Alternatives rejected**: handing Java an `InputStream` (loses the
    engine's session state, creates a JNI backpressure problem); a
    `WebViewDownload` object with `cancel()`/`pause()`/`resume()` (no
    common control set across backends — deferred, not dismissed);
    adding a fifth method to `WebViewDialogHandler` (changes a shipped
    interface and conflates a synchronous Q&A channel with a streaming
    one); a `setDownloadDirectory(File)` property instead of a handler
    (cannot express refusal, per-download naming, or progress).

## S · Structure

### Inheritance Relationships
1. `WebViewDownloadHandler` — public interface, three `default`
   methods, `DEFAULT` constant. No required abstract method.
2. `WebViewDownloadEvent`, `WebViewDownloadProgressEvent`,
   `WebViewDownloadCompleteEvent` — public final classes, no
   inheritance (matches `WebViewAlertEvent` / `WebViewPopupEvent`).
3. `WebViewDownloadCallback` — public interface, three methods, not
   `@FunctionalInterface`.
4. `DownloadDispatcher` — public final class (matches
   `DialogDispatcher`), with a private static final nested `Active`.
5. `WebViewComponent` (abstract) gains one field + two `final`
   methods; no change to the abstract surface.
6. `EmbeddedWebView` / `OffscreenWebView` each gain one
   `setDownloadCallback` method.

### Dependencies
1. `WebViewDownloadHandler` → `WebViewDownloadEvent` and friends,
   `java.io.File`, `javax.swing.JFileChooser` / `JOptionPane` /
   `SwingUtilities` (the default save dialog), `java.awt.Window`.
2. `DownloadDispatcher` → `javax.swing.SwingUtilities`,
   `java.util.concurrent.ConcurrentHashMap`,
   `java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}`,
   `java.io.File`, the three event types, `WebViewDownloadHandler`,
   `WebViewComponent`.
3. `WebViewComponent` → `DownloadDispatcher`, `WebViewDownloadHandler`.
4. `WebViewHeavyweightComponent.createPeer()` /
   `WebViewLightweightComponent.addNotify()` → `EmbeddedWebView` /
   `OffscreenWebView`, `DownloadDispatcher`, `WebViewDownloadCallback`.
5. `EmbeddedWebView.setDownloadCallback` →
   `WebViewNative.webview_embed_set_download_callback`.
6. Native selectors → `Engine::download_callback` jobject → JNI
   `CallObjectMethod` / `CallVoidMethod` → Java
   `WebViewDownloadCallback` → `DownloadDispatcher.dispatch*` →
   (`downloadRequested` via `invokeAndWait`; progress and completion
   via `invokeLater`) → `WebViewDownloadHandler`.

### Layered Architecture
1. **Native engine layer** (`src_c/webview_embed.cpp`): response-policy
   and `didBecomeDownload:` selectors, the four `WKDownloadDelegate`
   selectors, `DownloadCtx` lifecycle, `cocoa_set_download_callback`,
   JNI bridges.
2. **JNI surface** (`WebViewNative`): two `native static` decls.
3. **Engine wrapper layer** (`EmbeddedWebView`, `OffscreenWebView`):
   `setDownloadCallback`.
4. **Dispatcher layer** (`DownloadDispatcher`): per-component hub;
   synchronous destination decision, coalesced EDT progress,
   exactly-once terminal latch, sanitisation, exception isolation,
   disposal gating.
5. **Component API layer** (`WebViewComponent`): `setDownloadHandler` /
   `getDownloadHandler`.
6. **Public contract layer** (`WebViewDownloadHandler`, the three event
   types, `WebViewDownloadCallback`).
7. **Wiring layer** (`WebViewHeavyweightComponent.createPeer()`,
   `WebViewLightweightComponent.addNotify()`).
8. **Demo layer** (`demos/WebViewDownloadDemo/`).

## O · Operations

### 1. Create Value Object — WebViewDownloadEvent
File: `src/ca/weblite/webview/WebViewDownloadEvent.java`
1. Package-private constructor
   `WebViewDownloadEvent(WebViewComponent source, long id, String url,
   String suggestedFileName, String mimeType, long totalBytes,
   String pageUrl)`. Null-check `source` (NPE named `"source"`); coerce
   null `url` / `mimeType` / `pageUrl` to `""`; store `id`,
   `totalBytes` verbatim (callers pass `-1` for unknown); store
   `suggestedFileName` verbatim — the dispatcher has already sanitised
   it and guarantees non-empty. All fields `final`.
2. Accessors per Entities, including `sizeKnown()` returning
   `totalBytes >= 0`.
3. `toString()` — single-line, `url` truncated to 80 chars. No
   `equals` / `hashCode`.

### 2. Create Value Object — WebViewDownloadProgressEvent
File: `src/ca/weblite/webview/WebViewDownloadProgressEvent.java`
1. Package-private constructor
   `(WebViewComponent source, long id, File destination,
   long receivedBytes, long totalBytes)`. Null-check `source` and
   `destination`. Clamp `receivedBytes` to `>= 0`.
2. Accessors per Entities. `fraction()` returns `-1.0` when
   `totalBytes < 0` or `totalBytes == 0`; otherwise
   `Math.min(1.0, (double) receivedBytes / (double) totalBytes)`.
3. `toString()`.

### 3. Create Value Object — WebViewDownloadCompleteEvent
File: `src/ca/weblite/webview/WebViewDownloadCompleteEvent.java`
1. Package-private constructor
   `(WebViewComponent source, long id, File destination,
   boolean success, String failureReason, long receivedBytes)`.
   Null-check `source`; `destination` MAY be null (refused before a
   destination existed); coerce null `failureReason` to `""`; when
   `success` is true force `failureReason` to `""`.
2. Accessors per Entities.
3. `toString()`.

### 4. Create Handler Interface — WebViewDownloadHandler
File: `src/ca/weblite/webview/WebViewDownloadHandler.java`
1. Public interface with `WebViewDownloadHandler DEFAULT =
   new WebViewDownloadHandler() {};`.
2. `default File downloadRequested(WebViewDownloadEvent event)`:
   resolve `Window host = SwingUtilities.getWindowAncestor(event.source())`;
   create a `JFileChooser`; `setDialogTitle("Save Download")`;
   `setSelectedFile(new File(chooser.getCurrentDirectory(), event.suggestedFileName()))`;
   `showSaveDialog(host)`. Return `null` unless the result is
   `JFileChooser.APPROVE_OPTION` and `getSelectedFile()` is non-null.
   When the chosen file already exists, ask with
   `JOptionPane.showConfirmDialog(host, …, JOptionPane.YES_NO_OPTION)`
   and return `null` on anything but `YES_OPTION` — silently
   overwriting a user's file is data loss, so the stock handler must
   not do it.
3. `default void downloadProgress(WebViewDownloadProgressEvent event) { }`.
4. `default void downloadCompleted(WebViewDownloadCompleteEvent event) { }`.
5. Class and method Javadoc per Entities — in particular the
   null-versus-`DEFAULT` asymmetry, the EDT threading split, the
   `evalAsync(...).get()` deadlock hazard inside `downloadRequested`,
   the coalesced-progress / exactly-once-completion contract, and the
   note that `suggestedFileName()` is pre-sanitised but must still not
   be joined onto an attacker-influenced parent directory.

### 5. Create JNI Callback Interface — WebViewDownloadCallback
File: `src/ca/weblite/webview/WebViewDownloadCallback.java`
1. Public interface, three methods with the signatures in Entities.
2. Javadoc mirroring `WebViewPopupCallback`: not application API;
   `public` only for the JNI bridge; `onDownloadRequested` returns the
   answer synchronously and MUST NOT be EDT-marshalled by the native
   side; the other two are fire-and-forget notifications; the native
   engine invokes all three from its own UI thread.

### 6. Create Dispatcher — DownloadDispatcher
File: `src/ca/weblite/webview/DownloadDispatcher.java`
1. `public final class`, fields and nested `Active` per Entities.
   `DROP` singleton refusing everything.
2. `setHandler(h)` → `handler = (h == null) ? DROP : h`.
   `getHandler()` returns the field. `disposeAll()` sets `disposed`
   and clears `active`. `isDisposed()`.
3. `public String dispatchDownloadRequested(long id, String url,
   String suggestedFileName, String mimeType, long totalBytes,
   String pageUrl)`:
   a. If `disposed`, return `null` — no handler call, no terminal
      event (the component is gone; there is nobody to tell).
   b. `String safe = sanitiseFileName(suggestedFileName, url)`.
   c. Build the `WebViewDownloadEvent`.
   d. Run `handler.downloadRequested(event)` on the EDT via the same
      `invokeAndWait` + exception-isolation helper shape
      `DialogDispatcher.runOnEdtVoid` uses (already-on-EDT guard;
      `InvocationTargetException` and `InterruptedException` caught;
      handler exceptions forwarded to
      `Thread.getDefaultUncaughtExceptionHandler()`).
   e. On `null` (refusal), a thrown handler, or `totalBytes` state
      irrelevant: emit exactly one unsuccessful completion —
      `reportTerminal(id, null, false, "Download refused by handler", 0)` —
      and return `null`.
   f. Otherwise put a fresh `Active` into `active` keyed by `id` with
      the chosen destination and `total` seeded from `totalBytes`, and
      return `destination.getAbsolutePath()`.
4. `public void dispatchDownloadProgress(long id, long receivedBytes,
   long totalBytes)`:
   a. If `disposed`, return. Look up `Active`; if absent, return
      (terminated or refused — a normal race, not an error).
   b. Store the counters into the `Active`'s atomics, taking the max
      of the stored and incoming `received` so the sequence stays
      monotonic even if events arrive out of order.
   c. `if (a.progressQueued.compareAndSet(false, true))` enqueue one
      `SwingUtilities.invokeLater` task that clears the gate **first**,
      re-reads the atomics, re-checks `disposed` and that the `Active`
      is still current, builds the event, and calls
      `handler.downloadProgress(event)` inside the shared
      exception-isolation wrapper.
5. `public void dispatchDownloadCompleted(long id, boolean success,
   String failureReason, long receivedBytes)`:
   a. If `disposed`, return.
   b. `Active a = active.get(id)`. Resolve the destination from `a`
      (null when absent).
   c. `reportTerminal(id, destination, success, failureReason,
      receivedBytes)`.
6. `private void reportTerminal(long id, File destination,
   boolean success, String reason, long received)`:
   a. Obtain (or create, for a refusal) the `Active` for `id`; win the
      `terminal.compareAndSet(false, true)` race or return without
      reporting — this is the exactly-once guarantee.
   b. `active.remove(id)`.
   c. Build the `WebViewDownloadCompleteEvent` and deliver it on the
      EDT via `invokeLater`, wrapped in the exception isolator.
   d. Refusal ids are latched by inserting an already-terminal
      `Active` under the id and scheduling its removal on the next
      terminal dispatch, so a late native `failed` for a refused
      download finds a taken latch and is dropped.
7. `static String sanitiseFileName(String suggested, String url)` —
   package-private for tests:
   a. Start from `suggested`; when null or empty, fall back to the
      last path segment of `url` (everything after the final `/`,
      query and fragment stripped, percent-decoded best-effort).
   b. Cut everything up to and including the last `/` or `\` — a
      suggested name is a leaf, never a path.
   c. Remove characters `< 0x20`, `0x7F`, and the Windows-illegal set
      `< > : " | ? *`.
   d. Trim leading/trailing whitespace and trailing `.` characters
      (Windows strips them silently, which would otherwise let
      `"evil.exe."` and `"evil.exe"` collide).
   e. If the result is empty, `"."`, `".."`, or consists only of dots,
      use `"download"`.
   f. If the base name (up to the last `.`), upper-cased, is a
      reserved Windows device name — `CON`, `PRN`, `AUX`, `NUL`,
      `COM0`-`COM9`, `LPT0`-`LPT9` — prefix the whole name with `_`.
   g. Bound the result to 255 characters, preserving the extension:
      when longer, keep the last `.`-suffix (itself capped at 32
      chars) and truncate the base so the total is 255.
   h. Never returns null or empty.
8. Reuse (do not duplicate) the exception-isolation and EDT-hop
   idioms from `DialogDispatcher`; extract private helpers with the
   same names and semantics.

### 7. Extend WebViewComponent Base
File: `src/ca/weblite/webview/swing/WebViewComponent.java`
1. Add `protected final DownloadDispatcher downloadDispatcher =
   new DownloadDispatcher(this);` beside the existing
   `dialogDispatcher` / `popupDispatcher` fields.
2. Add `public final WebViewComponent setDownloadHandler(
   WebViewDownloadHandler handler)` delegating to
   `downloadDispatcher.setHandler(handler)` and returning `this`.
3. Add `public final WebViewDownloadHandler getDownloadHandler()`
   delegating to `downloadDispatcher.getHandler()`.
4. Javadoc in the same voice as the dialog and popup blocks above it,
   including a platform-coverage note: macOS wired in this canvas;
   Linux (Canvas 24) and Windows (Canvas 25) store the handler but do
   not yet bridge the native callback, so on those platforms the
   engine keeps its built-in download behaviour until those canvases
   land.

### 8. Extend EmbeddedWebView and OffscreenWebView
Files: `src/ca/weblite/webview/EmbeddedWebView.java`,
`src/ca/weblite/webview/OffscreenWebView.java`
1. Add `public void setDownloadCallback(WebViewDownloadCallback cb)`
   to each, guarded by the existing `checkAlive()`, delegating to the
   matching `WebViewNative` method.
2. **Anchor the callback in the `heap` field** per `spdd/norms.md` —
   the native side keeps a global ref but the Java object must also be
   strongly reachable for the life of the peer, exactly as the dialog
   and popup callbacks are.

### 9. Extend WebViewNative
File: `src/ca/weblite/webview/WebViewNative.java`
1. `native static void webview_embed_set_download_callback(long w,
   WebViewDownloadCallback cb);`
2. `native static void webview_offscreen_set_download_callback(long w,
   WebViewDownloadCallback cb);`
3. Comment block above them matching the `set_dialog_callback` /
   `set_popup_callback` prose: threading, which methods block, and
   which platforms are wired.

### 10. JNI header — deliberately NOT regenerated
File: `src_c/ca_weblite_webview_WebViewNative.h`
1. **No change.** The checked-in header has been stale since the dialog
   canvas: it carries no declaration for
   `…_set_1dialog_1callback`, `…_set_1popup_1callback`,
   `…_set_1click_1callback`, `…_set_1focus_1callback`, or
   `…_set_1attach_1callback` either. Those `JNIEXPORT` functions are
   defined directly in `src_c/webview_embed.cpp` and
   `windows/webview_embed.cc`, which is sufficient — `javah` output is
   a convenience, not a compile-time requirement, and JNI resolves by
   mangled symbol name at runtime.
2. Adding only the two download declarations would leave the header
   *more* inconsistent than it is now (five callback setters missing,
   two present). Regenerating it wholesale is a separate,
   non-behavioural cleanup and does not belong in this canvas.
3. The two download bridges therefore follow the established precedent
   and are defined only in the two native translation units, in
   Operation 11 below and in Canvases 24 and 25.

### 11. Implement macOS download support
File: `src_c/webview_embed.cpp` (`WEBVIEW_COCOA` section)
1. Add `jobject download_callback = nullptr;` and
   `long long next_download_id = 1;` to the cocoa engine struct.
2. Add three `fire_download_*` helpers mirroring
   `fire_click_callback`'s shape exactly — defensive
   `GetEnv`/`AttachCurrentThread`, detach only if we attached,
   resolve the method id per call, `ExceptionCheck`/`ExceptionClear`
   after every `Call*Method`, null-check the global ref first:
   - `fire_download_requested` → `CallObjectMethod` returning
     `jstring`; copy to a `std::string` and return whether a path was
     produced.
   - `fire_download_progress` → `CallVoidMethod`.
   - `fire_download_completed` → `CallVoidMethod`.
3. Add `webView:decidePolicyForNavigationResponse:decisionHandler:`
   to the embedded navigation-delegate class: answer
   `WKNavigationResponsePolicyDownload` (value `2`) when downloads are
   available at runtime AND (`-[WKNavigationResponse canShowMIMEType]`
   is `NO` OR the response's `Content-Disposition` header, lower-cased
   and trimmed, starts with `attachment`); otherwise answer
   `WKNavigationResponsePolicyAllow` (value `1`).
4. Add `webView:navigationAction:didBecomeDownload:` and
   `webView:navigationResponse:didBecomeDownload:`; each sets the
   delegate object as the `WKDownload`'s `delegate` and allocates a
   `DownloadCtx { Engine *e; long long id; long long written; }` with
   `id = e->next_download_id++`, associated with the download via
   `objc_setAssociatedObject` so it is released with the download.
5. Add `download:decideDestinationUsingResponse:suggestedFilename:completionHandler:`,
   following `impl_run_alert`'s deferral shape exactly:
   a. Copy the completion-handler block with `-copy` so it survives the
      selector's return; balance with `-release` after invoking it.
   b. While still on AppKit main, capture everything needed as
      `std::string` / `long long`: `expectedContentLength` from the
      response (`-1` when `NSURLResponseUnknownLength`), the response
      `MIMEType`, the download's
      `originalRequest.URL.absoluteString`, the suggested filename, and
      the download's `webView.URL.absoluteString` as the page URL.
   c. Return from the selector immediately, running the JNI hop on a
      detached `std::thread`.  **Do not block AppKit main** — the Java
      handler runs on the EDT and the stock one opens a modal
      `JFileChooser`, which needs AppKit main itself.
   d. `dispatch_async` back onto the main queue to invoke the
      completion handler with `[NSURL fileURLWithPath:]` of the
      returned path, or with `nil` to cancel when Java refused.
   e. If the JVM attach fails, still `dispatch_async` and invoke the
      completion handler with `nil`, so the engine never hangs on an
      un-answered deferral.
6. Register the KVO progress observer when the destination is accepted:
   `[[download progress] addObserver:observer
   forKeyPath:@"completedUnitCount" options:0 context:&ctx]`, with the
   observer allocated from `WebviewEmbedDownloadObserver` and the
   `DownloadCtx *` recovered through the associated object.  The
   callback reads `completedUnitCount` and `totalUnitCount`
   (`totalUnitCount <= 0` ⇒ `-1`, unknown), stores the running count in
   `ctx->written`, and calls `fire_download_progress`.
   `WKDownloadDelegate` exposes no byte-count method, so this is the
   only source of progress on this platform.
7. Add `downloadDidFinish:` → claim `ctx->terminal`, remove the KVO
   observer, `fire_download_completed(ctx->id, true, "", ctx->written)`,
   then erase and free the context.  Add
   `download:didFailWithError:resumeData:` → claim `ctx->terminal`,
   remove the observer,
   `fire_download_completed(ctx->id, false,
   [[error localizedDescription] UTF8String], ctx->written)`, then erase
   and free; `resumeData` is accepted and discarded (out of scope).
   Removing the observer before the `NSProgress` is released is
   mandatory — AppKit treats an over-released observed object as a hard
   error, not a warning.
8. Runtime availability: a file-scope
   `static bool cocoa_downloads_available()` computing
   `NSClassFromString(@"WKDownload") != nil` once into a static, used
   only by step 3's policy answer. Selectors are always added; they
   simply never fire on an older system.
9. `cocoa_set_download_callback(Engine *, JNIEnv *, jobject)` —
   delete any existing global ref, store `NewGlobalRef(cb)` or
   `nullptr`, mirroring `gtk_set_dialog_callback`.
10. In `cocoa_destroy_engine`, delete and null `download_callback`
    **before** the web view is torn down, matching the ordering
    documented for `click_callback`.
11. Two `JNIEXPORT` bridges at the bottom of the file, following the
    `set_dialog_callback` / `set_popup_callback` pattern, with the
    `#ifdef WEBVIEW_GTK` branch left as a no-op **plus a `(void)`
    cast of each parameter** so the Linux build stays warning-clean
    under `-Wall -Wextra -pedantic` until Canvas 24 fills it in.

### 12. Wire the Download Bridge — WebViewHeavyweightComponent
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
1. In `createPeer()`, beside the existing dialog and popup callback
   installation, install an anonymous `WebViewDownloadCallback` whose
   three methods delegate to `downloadDispatcher.dispatchDownload*`.
2. In the disposal path, call `downloadDispatcher.disposeAll()`
   alongside `dialogDispatcher.disposeAll()` and
   `popupDispatcher.disposeAll()`.

### 13. Wire the Download Bridge — WebViewLightweightComponent
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`
1. Same wiring as Operation 12, against
   `webview_offscreen_set_download_callback`, in `addNotify()`.
2. Same `disposeAll()` addition in the disposal path.

### 14. Create Interactive Demo — WebViewDownloadDemo
File: `demos/WebViewDownloadDemo/`
1. A small Swing frame hosting a `WebViewComponent` pointed at a page
   with three links: an `<a download>` to a small text file, a link to
   a URL served with `Content-Disposition: attachment`, and a link to
   a `.zip`. A "Use silent handler" checkbox swaps between the stock
   save dialog and a handler that writes into a temp folder and
   appends progress and completion lines to a text area.
2. Follow the existing demo layout and the `run-{linux,mac}-*.sh`
   script convention at the repo root; add
   `run-mac-download-demo.sh`, `run-linux-download-demo.sh`, and
   `run-windows-download-demo.bat` alongside the existing ones.

### 15. Update README
File: `README.md`
1. Add a `## Browser-initiated downloads` section after the existing
   `## Browser-initiated dialogs` section, in the same voice: a short
   code sample installing a handler, the null-versus-`DEFAULT`
   note, the EDT threading split, the coalesced-progress and
   exactly-once-completion guarantees, the sanitisation guarantee, and
   the platform-coverage table (macOS wired here; Linux and Windows in
   Canvases 24 and 25).

### 16. Unit Tests — DownloadDispatcherTest
File: `test/ca/weblite/webview/DownloadDispatcherTest.java`
1. Reuse the `StubComponent` pattern from `DialogDispatcherTest`.
2. Cover, with no native engine:
   - Default handler is `WebViewDownloadHandler.DEFAULT`;
     `setHandler(null)` installs the drop singleton and is NOT the
     same instance as `DEFAULT`; `setHandler(DEFAULT)` restores it.
   - A custom handler's returned `File` comes back as an absolute
     path from `dispatchDownloadRequested` (AC2).
   - A handler returning `null` yields a `null` path AND exactly one
     unsuccessful completion (AC3).
   - A throwing handler yields a `null` path, exactly one
     unsuccessful completion, and does not propagate (AC11).
   - Progress: counters are monotonic, `totalBytes` of `-1` survives
     as "unknown" and `fraction()` is `-1.0` (AC5, AC6); a burst of
     rapid dispatches delivers fewer events than were dispatched and
     the last one carries the latest counts (the coalescing rule).
   - Completion: exactly one event even when native fires
     `completed` twice, or `failed` then `finished` (AC7, AC8).
   - `disposeAll()` makes `dispatchDownloadRequested` return `null`
     without calling the handler, and silences later progress and
     completion (AC12, AC13).
   - `sanitiseFileName`: `"../../../../etc/passwd"` → `"passwd"`;
     `"..\\..\\evil.exe"` → `"evil.exe"`; `""` and `null` fall back
     to the URL's last segment, then to `"download"` (AC9, AC10);
     `"..."` → `"download"`; `"CON.txt"` → `"_CON.txt"`;
     control characters and `<>:"|?*` are stripped; trailing dots and
     spaces are trimmed; a 400-character name is bounded to 255 with
     its extension intact.

## N · Norms

1. **Java 8 source level.** No `var`, no `List.of`, no
   `String.isBlank`, no `Map.of`. `ConcurrentHashMap`,
   `AtomicBoolean`, `AtomicLong` are all Java 5+ and fine. Use
   `Long.valueOf(id)` keys explicitly rather than relying on autoboxing
   in `remove()` calls, where an `int`/`long` mix-up silently misses.
2. **Anonymous inner classes for JNI callbacks**, not lambdas, matching
   `WebView.java:181` and `EmbeddedWebView.java:143`.
3. **Anchor JNI callbacks in `heap`.** Both new
   `setDownloadCallback` methods must retain the callback object.
4. **`checkAlive()` before every native call** on the wrapper classes;
   peer `0` means disposed.
5. **Event POJOs are immutable**, all fields `final`, never-null
   string accessors coercing `null` to `""`, `-1` for "unspecified"
   numerics, `toString()` for logging, no `equals` / `hashCode` —
   matching `WebViewFilePickerEvent` and `WebViewPopupEvent`.
6. **Handler exceptions never reach native code.** Catch, forward to
   `Thread.getDefaultUncaughtExceptionHandler()`, return the safe
   fallback (refuse for the decision; ignore for notifications).
7. **`-Wall -Wextra -pedantic` clean** on the Linux build: every
   parameter of a stubbed-out `#ifdef WEBVIEW_GTK` bridge gets a
   `(void)` cast.
8. **Every new WebKit symbol goes through the `dlopen` shim.** Not
   applicable to this canvas (macOS only) but binding for Canvas 24;
   noted here so the rule travels with the feature.
9. **Javadoc carries the contract.** Threading, nullability, the
   null-versus-`DEFAULT` asymmetry, and the deadlock hazard are
   documented on the interface, not left to the canvas.
10. **No new reserved JS binding.** Downloads originate from native
    engine callbacks; the `__webview_` prefix convention is not
    involved.

## S · Safeguards

1. **Path traversal via `Content-Disposition`.** A hostile server
   suggesting `../../../../etc/passwd`, `..\\..\\Windows\\System32\\x.dll`,
   or an absolute path must not be able to steer the write. Mitigation:
   `sanitiseFileName` reduces the suggestion to a leaf name before it
   is ever shown to a handler, and the contract states that the name is
   a *suggestion for a leaf*, never a path. Directly covered by
   `DownloadDispatcherTest`.
2. **Filename collision by Windows normalisation.** `"evil.exe."` and
   `"evil.exe"` name the same file on Windows; trailing dots and spaces
   are stripped so a handler's de-duplication logic sees the same string
   the filesystem will.
3. **Reserved device names.** A download named `CON`, `NUL`, or `LPT1`
   on Windows opens a device rather than a file. Prefixed with `_`.
4. **Silent overwrite.** The stock handler confirms before replacing an
   existing file; overwriting a user's document because a page named its
   download the same thing is data loss.
5. **EDT starvation from progress events.** A 100 MB download at 8 KB
   per chunk would queue ~12,800 EDT tasks. The coalescing gate bounds
   the queue to one pending event per download, which is what makes the
   story's throughput expectation achievable.
6. **EDT deadlock inside `downloadRequested`.** The EDT is parked
   running the handler while a native thread waits; `evalAsync(js).get()`
   from inside the handler can never complete. Documented on the
   interface, carrying over the identical hazard note from
   `WebViewDialogHandler`.
7. **`invokeAndWait` from the EDT throws.** The shared EDT-hop helper
   must run the body inline when `SwingUtilities.isEventDispatchThread()`
   is already true, exactly as `DialogDispatcher.runOnEdtVoid` does.
8. **Use-after-free of the JNI global ref.** A download can outlive its
   page and its component. The ref is cleared and nulled before view
   teardown, every fire helper null-checks it, and the Java-side
   `disposed` flag is authoritative — three independent gates, because
   this failure mode is a SIGSEGV, not an exception.
9. **Double terminal reporting.** `Active.terminal` is an
   `AtomicBoolean` won by exactly one reporter, so a backend that emits
   both `failed` and `finished` produces one event.
10. **Refusal must actually cancel.** Returning `null` is not merely
    "no path chosen" — the native side must call the completion handler
    with `nil` so `WKDownload` cancels, rather than falling back to a
    default destination. Nothing may be written, including into the
    platform's own downloads folder.
11. **Unbounded `active` map.** Entries are removed on the terminal
    event, and `disposeAll()` clears the map, so a component that
    downloads for hours does not accumulate state.
12. **Availability gating on macOS.** `WKDownload` is 11.3+. The policy
    method answers `Allow` when the class is absent, so an older system
    behaves exactly as it does today rather than crashing on a missing
    symbol.
13. **Blocking AppKit main on the destination decision.** The Java
    handler runs on the EDT and the stock one opens a modal
    `JFileChooser`, which needs AppKit main; waiting for it from AppKit
    main deadlocks on the first download. The worker-thread deferral in
    Operation 11.5 is not an optimisation, it is the correctness
    requirement, and it is the same rule the repo already states as the
    "macOS sync EDT→AppKit-main bridge prohibition".
14. **KVO observer outliving its NSProgress.** An `NSProgress` released
    while still observed is an AppKit hard error. The observer is
    removed on the terminal event, and again at engine teardown for any
    download still in flight, before anything is released.
