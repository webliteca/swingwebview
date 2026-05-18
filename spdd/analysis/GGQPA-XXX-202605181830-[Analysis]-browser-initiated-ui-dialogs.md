# SPDD Analysis: Browser-Initiated UI Dialogs (alert / confirm / prompt / file picker)

## Original Business Requirement

(Excerpt — full text preserved verbatim in `requirements/[User-story-4]browser-initiated-ui-dialogs.md`.)

Three sister stories sharing one Java contract:

- **STORY-004-001** — `WebViewDialogHandler` API + macOS WKWebView coverage. Designs the cross-platform Java contract (`WebViewDialogHandler` interface with four `default` methods showing Swing dialogs; four immutable event POJOs: `WebViewAlertEvent`, `WebViewConfirmEvent`, `WebViewPromptEvent`, `WebViewFilePickerEvent`; `setDialogHandler` / `getDialogHandler` on `WebViewComponent`) and ships the macOS implementation via a `WKUIDelegate`. Currently macOS is silently broken: no `uiDelegate` is set on the `WKWebView`, so `alert` / `confirm` / `prompt` / `<input type=file>` all silently no-op. 20 ACs + 5 non-functional expectations.

- **STORY-004-002** — Linux WebKitGTK coverage (heavyweight + lightweight). Connects `script-dialog` and `run-file-chooser` signal handlers on `WebKitWebView`, suppresses the GTK default (returns `TRUE`), and bridges to the same `WebViewDialogHandler`. Same JNI / dispatcher pattern in both modes. Fixes the documented `transient_for` problem in lightweight (offscreen `GtkOffscreenWindow` has no transient parent → orphan dialogs cannot position) and replaces the heavyweight default which suffers the same `gdk_window_move_to_rect` glitch on a foreign-toolkit X11 parent. 16 ACs + 3 non-functional expectations.

- **STORY-004-003** — Windows WebView2 coverage. Adds `put_AreDefaultScriptDialogsEnabled(FALSE)` at engine creation and registers `add_ScriptDialogOpening` to bridge alert/confirm/prompt to the same Java handler. WebView2 exposes **no public hook** for `<input type=file>` so the OS-native file dialog continues to appear on Windows; `WebViewFilePickerEvent` does not fire on Windows in this story — documented as a known platform limitation. 13 ACs + 3 non-functional expectations.

Cross-cutting invariants the contract requires:

- **EDT-only callbacks.** Every `*Opened` method runs on the Swing EDT.
- **Synchronous JS-contract semantics.** `alert` / `confirm` / `prompt` are synchronous in JS; the native completion handler / deferral must not be invoked until the Java handler returns. `<input type=file>` is async — completion fires on dismissal, page receives selection via the `change` event.
- **Default behaviour = Swing dialogs.** No-handler / handler-using-defaults shows `JOptionPane` (alert/confirm/prompt) and `JFileChooser` (file picker), modal to the host `JFrame` resolved via `SwingUtilities.getWindowAncestor(component)`.
- **Override = one handler per component.** `setDialogHandler(custom)` replaces. `setDialogHandler(null)` installs a "drop" handler that returns void / `false` / `null` / empty list without showing UI — required for headless tests.
- **No new reserved JS binding** (dialog requests are native callbacks, not page-injected JS). The existing `__webview_` prefix convention is unused for this work.
- **Story dependency graph.** STORY-004-001 designs the API and is the prerequisite for the other two; STORY-004-002 and STORY-004-003 can ship in parallel after it.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **`WebViewComponent`** (`src/ca/weblite/webview/swing/WebViewComponent.java`) — abstract base for both Swing variants. Already exposes `consoleDispatcher`, `mouseDispatcher` (both `protected final`, initialised at construction so they survive peer create/destroy), and the constant `RESERVED_BINDING_PREFIX = "__webview_"`. **The new `setDialogHandler` / `getDialogHandler` API attaches here as concrete `final` methods (no abstract overload needed)** — same shape as the existing `addWebViewMouseListener` / `setDefaultContextMenuEnabled` / `addConsoleListener` family at lines 282-406. The component is the natural owner of the per-instance handler reference; both subclasses see the same dispatcher.

- **`WebViewHeavyweightComponent`** (`src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`) — wraps an `EmbeddedWebView`; peer created lazily in `createPeer()` (line 409). The `createPeer()` site at lines 410-501 is **the canonical install site** for new bridges: console (425-432), DOM mouse events (438-459), native focus (473-483), native click (491-501). The new dialog bridge attaches at the same site, immediately after the existing setFocusCallback / setClickCallback registrations.

- **`WebViewLightweightComponent`** (`src/ca/weblite/webview/swing/WebViewLightweightComponent.java`) — wraps an `OffscreenWebView`; peer created lazily in `addNotify()` (around line 209). Mirrors the heavyweight install site for the console + mouse bridges. The same dialog-callback installation pattern lands here for STORY-004-002's lightweight path.

- **`EmbeddedWebView`** (referenced from heavyweight component) — JNI wrapper over the heavyweight native peer. Currently exposes `setFocusCallback(WebViewFocusCallback)` (used in `createPeer` at line 473) and `setClickCallback(WebViewClickCallback)` (line 491). **These are the two existing precedents for fire-and-forget Java callbacks the native code invokes.** The new dialog callback needs a similar setter (`setDialogCallback(WebViewDialogCallback)`) but with a critical difference: it must return values synchronously (alert: void; confirm: boolean; prompt: String-or-null; file picker: String[]). Neither existing callback returns anything.

- **`OffscreenWebView`** — JNI wrapper over the offscreen peer. Already exposes `addOnBeforeLoad`, `addJavascriptCallback`, `eval`, `dispatch`. **Does not currently have a focus or click callback setter** — those are heavyweight-only because the lightweight engine sees mouse / focus events through AWT, not from the native side. The new dialog callback must be added to both engines because the native browser-engine callbacks for dialogs originate inside WebKit/WebView2 regardless of the embedding model.

- **`WebViewNative`** (`src/ca/weblite/webview/WebViewNative.java`) — the JNI surface. Current callback-setter entry points: `webview_embed_set_focus_callback(long w, WebViewFocusCallback cb)` (line 228), `webview_embed_set_click_callback(long w, WebViewClickCallback cb)` (line 243). **The new dialog dispatcher introduces two new native entry points**: `webview_embed_set_dialog_callback(long w, WebViewDialogCallback cb)` for heavyweight and `webview_offscreen_set_dialog_callback(long peer, WebViewDialogCallback cb)` for offscreen.

- **`WebViewFocusCallback`** / **`WebViewClickCallback`** (`src/ca/weblite/webview/WebViewFocusCallback.java`, `WebViewClickCallback.java`) — single-method functional interfaces. **Both explicitly document "callback invoked from a native thread (AppKit main thread on macOS / GTK main on Linux / WebView2 worker on Windows); implementations MUST marshal to the EDT themselves before touching any Swing state."** This story's `WebViewDialogCallback` follows the same JNI contract but the implementation lives inside the new `DialogDispatcher` rather than each Swing subclass, so individual users never see the native-thread invocation — they see only the EDT-marshalled `WebViewDialogHandler` callback.

- **`ConsoleDispatcher`** (`src/ca/weblite/webview/ConsoleDispatcher.java`) — canonical fan-out hub: `CopyOnWriteArrayList<Listener>` registry, `dispatch(rawJson)` decoding entry point, `deliverOnEdt(msg)` EDT-marshal helper, per-listener `try/catch` isolation forwarding to `Thread.getDefaultUncaughtExceptionHandler()` (lines 235-241). **The new `DialogDispatcher` mirrors this class structurally** for the listener registry + EDT marshaling pattern, but diverges in two critical ways: (a) it uses `invokeAndWait`, not `invokeLater`, because the native side waits for the answer, and (b) it holds a single handler reference (`setHandler`), not a listener list, because dialog requests need a single resolver not fan-out.

- **`WebViewMouseDispatcher`** (`src/ca/weblite/webview/WebViewMouseDispatcher.java`) — second canonical fan-out hub. Adds the `FlagSink` pattern for pushing JS-side state changes (the `__webview_dom_event_suppress` flag) to the live peer. **The dialog work does not need a FlagSink** — there is no JS-side state to mirror; the suppression is enforced at the native delegate / signal level, not in JavaScript.

- **`EvalDispatcher`** (`src/ca/weblite/webview/EvalDispatcher.java`) — third dispatcher precedent. Introduces the `marshalToEdt` constructor flag (line 137) distinguishing standalone-`WebView` (no Swing in the picture) from `WebViewComponent` (EDT marshaling required). **For dialog handling we only care about the embedded surface** — STORY-004-001 explicitly scopes out the standalone `WebView` class (line 134 of the requirements) — so the dispatcher hard-codes EDT marshaling and skips the `marshalToEdt` flag.

- **JS-binding bridge** — every engine routes binding calls through a `{name, seq, args}` JSON envelope. **Not relevant to this story** — dialog requests come from native browser-engine callbacks (`WKUIDelegate` methods / `WebKitWebView` signals / `ICoreWebView2_add_ScriptDialogOpening`), not from page-injected JS. The reserved `__webview_*` prefix and `addJavascriptCallback` are untouched.

- **Native engine creation paths** —
  - macOS: `cocoa_create_engine` in `src_c/webview_embed.cpp` (line 1951). The `WKWebView` is alloc'd at line 1989 against a `WKWebViewConfiguration` (line 1987). **The `uiDelegate` is never assigned** — confirmed by grep. The new ObjC `WKUIDelegate` class is registered alongside the existing `WKScriptMessageHandler` registration (line 2083) by adding `msg<void,id>(e->webview, sel("setUIDelegate:"), ui_delegate);` after the delegate instance is constructed. The class can be installed via the same once-per-JVM cached-Class pattern `get_webview_embed_delegate_cls()` uses for the script-message delegate (line 1684).
  - Linux: `gtk_create_engine` (heavyweight, around line 529) and `gtk_create_offscreen_engine` (lightweight, around line 1023) — both create the `WebKitWebView` via `webkit_web_view_new()`. Existing `g_signal_connect` calls hook `load-changed`, `load-failed`, GTK gesture-pressed, and the GdkFrameClock paint signals. **No `script-dialog` or `run-file-chooser` signal handlers exist** — this story adds two `g_signal_connect` calls per engine. Returning `TRUE` from each handler suppresses the default GTK behaviour (story Constraint).
  - Windows: `windows/webview_embed.cc` — settings acquired around line 422 (`ICoreWebView2Settings *settings`), where this story adds `settings->put_AreDefaultScriptDialogsEnabled(FALSE)`. Event handler registration follows the existing `FocusHandler` precedent (lines 449-454) — a small C++ class implementing `ICoreWebView2ScriptDialogOpeningEventHandler`, instantiated and registered via `webview->add_ScriptDialogOpening`.

- **`SwingUtilities.getWindowAncestor`** — the existing pattern used implicitly anywhere a modal anchor is needed. The default `WebViewDialogHandler` uses this to find the host `JFrame`; the four `JOptionPane` / `JFileChooser` calls take it as the first argument.

- **JUnit 4** (`pom.xml:43-49`) — the only test dependency. Existing test files (`EvalDispatcherTest.java`, `JavaScriptEvalExceptionTest.java`) follow a unit-test style without spinning up a real engine — they exercise the dispatcher Java surface directly. The new `DialogDispatcher` test will follow the same shape.

### New Concepts Required

- **`ca.weblite.webview.WebViewDialogHandler`** — public interface with four `default` methods, all EDT-invoked: `alertOpened(WebViewAlertEvent)` returning void; `confirmOpened(WebViewConfirmEvent)` returning `boolean`; `promptOpened(WebViewPromptEvent)` returning `String` (or `null` for cancel); `filePickerOpened(WebViewFilePickerEvent)` returning `java.util.List<java.io.File>`. The default implementations call `JOptionPane.showMessageDialog`, `showConfirmDialog`, `showInputDialog`, and a configured `JFileChooser` respectively — all anchored on `SwingUtilities.getWindowAncestor(event.source())`.

- **`ca.weblite.webview.WebViewAlertEvent`** — immutable POJO: `source` (`WebViewComponent`), `message` (`String`), `pageUrl` (`String`), `frameUrl` (`String`). Constructed by `DialogDispatcher` from the native payload.

- **`ca.weblite.webview.WebViewConfirmEvent`** — same fields as `WebViewAlertEvent`. (Could be unified into a single base class but the requirements list them as four distinct types for caller clarity; keep them separate per the requirements.)

- **`ca.weblite.webview.WebViewPromptEvent`** — `source`, `message`, `defaultValue`, `pageUrl`, `frameUrl`.

- **`ca.weblite.webview.WebViewFilePickerEvent`** — `source`, `multiple` (boolean), `acceptedExtensions` (`List<String>` — lower-case, leading dot omitted), `acceptedMimeTypes` (`List<String>`), `pageUrl`, `frameUrl`.

- **`ca.weblite.webview.DialogDispatcher`** — per-component fan-out hub holding the single `WebViewDialogHandler` reference plus the four dispatch entry points called from JNI. Public-because-cross-package (matches `ConsoleDispatcher` / `WebViewMouseDispatcher`). Owns the `WebViewComponent source` reference for event-construction (matches `WebViewMouseDispatcher` constructor).
  - `setHandler(WebViewDialogHandler handler)` — replacing setter; `null` installs a `DROP` handler whose methods return void / false / null / empty without UI.
  - `getHandler()` — never returns null; returns the framework default when no caller has set one.
  - Native-facing dispatch entry points (one per dialog kind) returning the answer synchronously:
    - `void dispatchAlert(String message, String pageUrl, String frameUrl)`
    - `boolean dispatchConfirm(String message, String pageUrl, String frameUrl)`
    - `String dispatchPrompt(String message, String defaultValue, String pageUrl, String frameUrl)`
    - `String[] dispatchFilePicker(boolean multiple, String[] mimeTypes, String[] extensions, String pageUrl, String frameUrl)`
  - Each `dispatch*` method uses `SwingUtilities.invokeAndWait` to hop to the EDT, invokes the handler, and returns the captured value. Handler exceptions are caught, forwarded to the default uncaught-exception handler, and the dispatch returns a safe fallback (void / false / null / empty) so the native completion handler / deferral is never left dangling.

- **`ca.weblite.webview.WebViewDialogCallback`** — internal-ish single-method functional interface called from JNI. Likely simplest as one method per dialog kind on a single interface, all of which return the answer synchronously. Matches the `WebViewFocusCallback` / `WebViewClickCallback` pattern but with return values. The Java implementation in `WebViewComponent`'s `createPeer` / `addNotify` site is a one-liner delegating to the per-component `DialogDispatcher`.

- **`ca.weblite.webview.WebViewDialogHandler.DEFAULT`** — shared stateless default instance whose four methods invoke the interface defaults (which in turn show Swing dialogs). Returned by `getDialogHandler()` when no caller has installed a custom handler.

- **New JNI native methods on `WebViewNative`** —
  - `native static void webview_embed_set_dialog_callback(long w, WebViewDialogCallback cb)` — for the heavyweight engines (macOS, Linux heavyweight, Windows).
  - `native static void webview_offscreen_set_dialog_callback(long peer, WebViewDialogCallback cb)` — for the Linux lightweight offscreen engine.

- **Native delegate / signal-handler / event-handler classes** —
  - macOS: an ObjC class implementing the four `WKUIDelegate` selectors (`runJavaScriptAlertPanelWithMessage:`, `runJavaScriptConfirmPanelWithMessage:`, `runJavaScriptTextInputPanelWithPrompt:`, `runOpenPanelWithParameters:`). Lives in `src_c/webview_embed.cpp` alongside `get_webview_embed_delegate_cls()` (the existing `WKScriptMessageHandler` class).
  - Linux: two static C functions registered via `g_signal_connect(WEBKIT_WEB_VIEW(e->web), "script-dialog", ...)` and `g_signal_connect(WEBKIT_WEB_VIEW(e->web), "run-file-chooser", ...)`. Both in `src_c/webview_embed.cpp`, hooked from inside both `gtk_create_engine` and `gtk_create_offscreen_engine`.
  - Windows: a C++ class `ScriptDialogHandler : public ICoreWebView2ScriptDialogOpeningEventHandler` in `windows/webview_embed.cc`, registered via `webview->add_ScriptDialogOpening` next to the existing `add_WebMessageReceived` site.

- **Reserved binding name `__webview_dialog`** — **not introduced.** Dialogs do not flow through a JS binding; the engines emit native callbacks. The existing `RESERVED_BINDING_PREFIX` discipline is untouched by this story.

### Conceptual Relationships

- `WebViewComponent` owns a `DialogDispatcher` for its entire lifetime — same lifecycle as `consoleDispatcher` and `mouseDispatcher`. The `protected final DialogDispatcher dialogDispatcher = new DialogDispatcher(this)` field is initialised at construction so a caller can call `setDialogHandler` before the component is displayed.

- The dispatcher owns the single `WebViewDialogHandler` reference. Setting `null` installs an internal `DROP` handler that suppresses dialogs without UI; setting a non-null handler replaces. There is **no listener-list semantic** (deliberate divergence from `ConsoleDispatcher` / `WebViewMouseDispatcher`) because a dialog request needs exactly one answer.

- Pre-display handler registration is buffered trivially: the handler reference lives in the Java-side dispatcher; the JNI callback isn't wired until peer-attach. If a dialog somehow fires before the peer attaches (impossible in practice — no page loaded yet means no `alert` call), the native side has no callback to invoke and the engine's own default fires. There is no "pre-attach dialog event queue" because dialogs are synchronous and there's no JS in flight before the page loads.

- The native callback (`WebViewDialogCallback`) is invoked synchronously from the native UI thread — AppKit main on macOS, GTK pump on Linux, WebView2 worker on Windows. The Java implementation in the Swing subclass's peer-attach site is a one-line `dialogDispatcher.dispatch*(...)` call. The dispatcher does the EDT hop via `SwingUtilities.invokeAndWait` and returns the answer. The native side then feeds the answer back to the platform's deferral / completion handler.

- The default `WebViewDialogHandler.DEFAULT` is **stateless** — it just calls `JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(event.source()), event.message())` and friends. No per-instance state; safe to share across components and across threads (though it only runs on the EDT regardless).

- The dispatcher is **independent of `ConsoleDispatcher` and `WebViewMouseDispatcher`** — they share class structure but not state, and operate on entirely separate transport channels (the others on JS bindings, this one on native engine callbacks).

- The `WebViewContextMenu` helper (STORY-002-001) is **orthogonal** — it consumes the mouse-event channel; this story consumes a different channel. They coexist without interaction.

- `openDevTools()` and `evalAsync()` are **orthogonal** — neither interacts with dialog dispatch.

### Key Business Rules

- **Single handler per component.** `setDialogHandler(handler)` replaces the active handler verbatim. There is no add/remove listener model. Stories AC10-AC14 (STORY-004-001), AC9 / AC10 (STORY-004-002), AC5-AC8 (STORY-004-003) all derive from this rule.

- **`null` handler = drop semantics.** `setDialogHandler(null)` does not throw and does not restore a "default" — it installs a handler that suppresses all dialog UI and returns the JS-spec cancel values synchronously (void / false / null / empty list). Required for headless tests. Stories STORY-004-001 AC14, STORY-004-002 AC10, STORY-004-003 AC8.

- **`getDialogHandler()` never returns null.** Returns `WebViewDialogHandler.DEFAULT` when no caller has installed one. Story STORY-004-001 AC17.

- **EDT-only handler callbacks.** Every method on the handler runs on the Swing EDT, marshaled from whatever native thread fired the dialog. Stories AC16 (004-001), AC11 (004-002), AC10 (004-003).

- **Modal-to-host-JFrame.** Default Swing dialogs anchor on `SwingUtilities.getWindowAncestor(component)`. If the component is not yet in a window, fall back to anchoring on the component itself (matches `JOptionPane`'s existing fallback). Story STORY-004-001 Constraint + AC1.

- **`before-unload` routes to `confirmOpened`.** Linux's `WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM` and Windows's `WEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD` both map to `confirmOpened`. No dedicated `beforeUnloadOpened` method is introduced. Stories STORY-004-002 AC12, STORY-004-003 AC9.

- **Default GTK / WebView2 / WKWebView dialogs are fully suppressed once the bridge is installed.** No path exists where the Java handler is invoked AND the native default also shows. Stories STORY-004-002 AC1 / AC2 / AC13 + STORY-004-003 AC1 / AC12.

- **Default `WebViewFilePickerEvent.acceptedExtensions` normalisation.** Lower-case, leading dot stripped, deduplicated. Stories STORY-004-001 AC15, STORY-004-002 AC15.

- **Windows file picker is OS-native, `WebViewFilePickerEvent` does not fire.** Documented limitation; AC4 of STORY-004-003 verifies that the OS dialog still appears for `<input type=file>`.

- **Handler exceptions never leave the dispatcher.** Caught, forwarded to `Thread.getDefaultUncaughtExceptionHandler()` (same pattern as `ConsoleDispatcher` line 235-241), and a safe fallback is returned so the native completion handler / deferral always completes. Stories STORY-004-001 AC20, STORY-004-003 AC13.

## Strategic Approach

### Solution Direction

**Introduce a fourth dispatcher (`DialogDispatcher`) following the existing per-component-fan-out-hub pattern, but specialised for synchronous single-resolver semantics. Hook it to a new JNI callback (`WebViewDialogCallback`) installed per peer-attach. Implement the platform-specific callback sites natively in each engine: a `WKUIDelegate` on macOS, two `g_signal_connect` handlers on Linux (both modes), and a `ICoreWebView2ScriptDialogOpeningEventHandler` plus `put_AreDefaultScriptDialogsEnabled(FALSE)` on Windows.**

Cross-cutting Java side (STORY-004-001):

- Add immutable event POJOs (`WebViewAlertEvent`, `WebViewConfirmEvent`, `WebViewPromptEvent`, `WebViewFilePickerEvent`) to `ca.weblite.webview`.
- Add `WebViewDialogHandler` interface to `ca.weblite.webview` with four `default` methods showing Swing dialogs. Each default uses `SwingUtilities.getWindowAncestor(event.source())` to anchor on the host JFrame.
- Add `WebViewDialogCallback` interface to `ca.weblite.webview` (internal-ish single-method functional interface; one method per dialog kind, each returning the answer synchronously). Documented as "callback invoked from a native thread; implementations must marshal to EDT" — same wording as `WebViewFocusCallback`.
- Add `DialogDispatcher` to `ca.weblite.webview`. Holds the single `WebViewDialogHandler` reference (volatile; defaulting to `WebViewDialogHandler.DEFAULT`). Exposes `setHandler`, `getHandler`, and four `dispatch*` methods called from the JNI callback. Each `dispatch*` does the `SwingUtilities.invokeAndWait` EDT hop, captures the handler return value, and returns it. Internal helpers `runOnEdtAndCapture(...)` factor out the boilerplate.
- Add `setDialogHandler` / `getDialogHandler` to `WebViewComponent` as concrete `final` methods delegating to the per-instance `DialogDispatcher`. Add a `protected final DialogDispatcher dialogDispatcher = new DialogDispatcher(this)` field initialised at construction.
- Add `webview_embed_set_dialog_callback(long, WebViewDialogCallback)` and `webview_offscreen_set_dialog_callback(long, WebViewDialogCallback)` to `WebViewNative` plus their `EmbeddedWebView` / `OffscreenWebView` wrapper methods.

macOS native side (STORY-004-001):

- Add a `WKUIDelegate` ObjC class to `src_c/webview_embed.cpp` following the `get_webview_embed_delegate_cls()` cached-Class pattern at line 1684. Class implements four selectors:
  - `webView:runJavaScriptAlertPanelWithMessage:initiatedByFrame:completionHandler:` — calls `dialogDispatcher.dispatchAlert` via JNI; invokes the completion handler (no args) on AppKit main thread after Java returns.
  - `webView:runJavaScriptConfirmPanelWithMessage:initiatedByFrame:completionHandler:` — calls `dispatchConfirm`; invokes completion handler with `BOOL`.
  - `webView:runJavaScriptTextInputPanelWithPrompt:defaultText:initiatedByFrame:completionHandler:` — calls `dispatchPrompt`; invokes completion handler with `NSString*` or `nil`.
  - `webView:runOpenPanelWithParameters:initiatedByFrame:completionHandler:` — extracts `allowsMultipleSelection`, `_acceptedMIMETypes`, `_acceptedFileExtensions` (KVC; these are private accessors but documented stable), calls `dispatchFilePicker`; invokes completion handler with `NSArray<NSURL*>*` or `nil`.
- Assign the delegate to `e->webview` via `msg<void,id>(e->webview, sel("setUIDelegate:"), ui_delegate)` immediately after `WKWebView` alloc/init (line 1989-1992).
- The delegate stashes the `Engine*` via `objc_setAssociatedObject` so it can find the right Java dispatcher (mirrors the existing pattern at line 2088 for the script-message delegate).
- Add `cocoa_set_dialog_callback(Engine*, JNIEnv*, jobject)` mirroring `cocoa_set_focus_callback` (line 1923) and `cocoa_set_click_callback` (line 1940).
- **Critical**: `WKUIDelegate` selectors are invoked on the AppKit main thread, NOT the EDT. Calling `SwingUtilities.invokeAndWait` from the AppKit main thread blocks AppKit; while blocked, AppKit doesn't service its own runloop. The EDT in turn does NOT depend on AppKit servicing its runloop for `JOptionPane.showMessageDialog` to complete (Swing dialogs are pure-Java modal pumps on the EDT — they don't dispatch to AppKit). So invokeAndWait does not deadlock — but the AppKit runloop is paused while the Swing dialog is up, which is exactly the desired behaviour: the WebKit JS thread is also paused, and the page does not get to repaint or run any timers while waiting for the answer. This is correct per the JS contract.

Linux native side (STORY-004-002):

- Add two static C functions to `src_c/webview_embed.cpp`:
  - `on_script_dialog(WebKitWebView*, WebKitScriptDialog*, gpointer user_data)` — reads dialog type via `webkit_script_dialog_get_dialog_type`, extracts message / default text, calls the appropriate Java `dispatchAlert` / `dispatchConfirm` / `dispatchPrompt` / `dispatchConfirm` (for before-unload, which is also `WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM`). For alert: no need to set anything, just return TRUE. For confirm / before-unload: `webkit_script_dialog_confirm_set_confirmed(dialog, java_result)`. For prompt: `webkit_script_dialog_prompt_set_text(dialog, java_text)` or leave unset for null/cancel. Returns `TRUE` to suppress GTK default.
  - `on_run_file_chooser(WebKitWebView*, WebKitFileChooserRequest*, gpointer user_data)` — reads `webkit_file_chooser_request_get_select_multiple`, `webkit_file_chooser_request_get_mime_types_filter`, calls `dispatchFilePicker`. If non-empty result, calls `webkit_file_chooser_request_select_files(request, paths)`; if empty (cancel), calls `webkit_file_chooser_request_cancel(request)`. Returns `TRUE`.
- Both `g_signal_connect`'d in `gtk_create_engine` (heavyweight, around line 583) AND `gtk_create_offscreen_engine` (lightweight, around line 1045) — same two function pointers, separate registration sites. The `gpointer user_data` carries the `Engine*` pointer so the handler can find its Java callback.
- Add `gtk_set_dialog_callback(Engine*, JNIEnv*, jobject)` mirroring the macOS one.
- **Critical**: the signal handler runs on the GTK main thread (the GTK pump thread per the README). EDT marshaling via `SwingUtilities.invokeAndWait` from a non-EDT thread is straightforward. The GTK pump and EDT are independent threads with no mutual blocking dependency — same shape as `WebViewFocusCallback` invocation, which already works.

Windows native side (STORY-004-003):

- In `windows/webview_embed.cc`, at the `ICoreWebView2Settings` site (line 422-428):
  - Add `settings->put_AreDefaultScriptDialogsEnabled(FALSE);` after the existing settings calls.
- After `add_WebMessageReceived` registration (line 440), add:
  - `auto *sh = new ScriptDialogHandler(e); webview->add_ScriptDialogOpening(sh, &e->script_dialog_token); sh->Release();`
  - Where `ScriptDialogHandler` implements `ICoreWebView2ScriptDialogOpeningEventHandler::Invoke` by: reading `Uri`, `Kind`, `Message`, `DefaultText` from the args; calling `GetDeferral`; spawning a Java dispatch on a worker thread (so the WebView2 worker thread isn't blocked while waiting for EDT — see Risk section); on completion, invoke `Accept` / `put_ResultText` as appropriate; call `Complete` on the deferral. Returns `S_OK`.
- Store the dialog token in the `Engine` struct alongside the existing `message_token`, `got_focus_token`, `lost_focus_token`.
- Add `win_set_dialog_callback(Engine*, JNIEnv*, jobject)` for the Java-side hook.
- **File picker is NOT intercepted** — WebView2 has no public hook. README is updated to document the limitation.

### Key Design Decisions

- **Single handler vs. listener-list.** Trade-off: a listener list (`addDialogListener` / `removeDialogListener`) matches `WebViewMouseListener` and `ConsoleListener`; a single handler is asymmetric with the rest of the codebase. **Recommendation: single handler, accepting the asymmetry.** A dialog request needs ONE answer; multiple listeners would have to merge answers somehow (first wins? last wins? boolean AND/OR?) and every option is surprising. The story explicitly chose this in the requirements (line 80 of the doc) and the API is cleaner. The user's tests treat the dispatch as "answer this question," not "broadcast this event."

- **`SwingUtilities.invokeAndWait` vs. async with `CompletableFuture`-style completion.** Trade-off: `invokeAndWait` is simple and matches the synchronous JS contract directly; `CompletableFuture` round-tripping is more flexible (could be cancelled, could be tested without an EDT) but requires the native code to call back into Java again with the answer. **Recommendation: `invokeAndWait` from the native UI thread.** The native UI thread is *expected* to block while the dialog is open (that's the whole point of a modal dialog), and the JS thread is suspended either way. `invokeAndWait` keeps the dispatcher's API surface tiny. The standalone `WebView` is out of scope for this story (requirements line 134) so we don't need the standalone-no-EDT branch that `EvalDispatcher` has.

- **`invokeAndWait` from inside a `WKUIDelegate` selector — deadlock risk?** The AppKit main thread invokes the delegate; we then `invokeAndWait` to the EDT. The EDT runs `JOptionPane.showMessageDialog(host, ...)` which spins a secondary modal pump on the EDT (Swing's modal-dialog implementation is pure Java — the EDT internally pumps a nested event queue while the dialog is up). The EDT never re-enters AppKit synchronously while pumping; Swing repaints go through Java2D which on macOS does call AppKit asynchronously, but those calls don't block. **Recommendation: `invokeAndWait` is safe.** The AppKit main thread is paused while waiting, which means AppKit doesn't repaint the WKWebView until Java returns — that's the desired behaviour (the page is frozen during the modal dialog). Verified by analogy with `WebViewFocusCallback`: that also fires from AppKit and the receiving Java code already runs on a non-EDT thread, then `invokeLater`s to EDT. The new path adds the wait, which is the only difference, and the EDT does not depend on AppKit.

- **`invokeAndWait` from Linux GTK pump thread — deadlock risk?** The GTK pump runs on its own thread separate from AWT's X11 thread (per README: "A dedicated GTK pump thread drives the WebKitGTK main loop independently of AWT's X11 event loop"). The EDT runs on AWT's main thread. The two are decoupled. EDT shows `JOptionPane`, which under X11 uses AWT's X11 surface — AWT's X11 surface uses AWT's X11 thread, not GTK's pump. **Recommendation: safe.** Lightweight mode is even more decoupled — the offscreen `GtkOffscreenWindow` doesn't talk to X11 at all from the GTK pump's perspective.

- **`invokeAndWait` from Windows WebView2 worker thread — deadlock risk?** Each WebView2 engine has its own worker thread (per README). The EDT is AWT's thread. WebView2's `Accept` / `put_ResultText` / `Complete` calls must be made on the WebView2 worker thread — calling them from the EDT is incorrect. **Recommendation: in the Windows handler, do NOT use `invokeAndWait` synchronously**; instead use `GetDeferral` + spawn the EDT marshal on a separate Java thread that calls `invokeAndWait` to the EDT, captures the result, then dispatches a Runnable back onto the WebView2 worker thread via `webview_embed_dispatch` to complete the deferral. This is the same shape `WebView2` examples use for any deferral that needs UI confirmation. The Java `DialogDispatcher.dispatchAlertAsyncWindows(...)` returns nothing; the native handler holds a `Microsoft::WRL::ComPtr<ICoreWebView2Deferral>` for the duration. (Alternative: block the WebView2 worker thread and rely on no other WebView2 ops running on it during the dialog — risky because the worker also services repaint and JS execution, which we want frozen during the modal *anyway*. Probably **safe**, and simpler than the deferral dance.) **Decision: prefer the synchronous-block approach** unless STORY-004-003 implementation reveals a deadlock; the deferral pattern is the fallback.

- **POJO design: one event class per dialog kind vs. one unified `WebViewDialogEvent`.** Trade-off: four classes keep accessors honest (no `getDefaultValue()` on an `AlertEvent`); one unified class with a `type` discriminator is more compact but invites accessor-on-wrong-event-kind bugs. **Recommendation: four classes per the requirements.** Matches what STORY-002-001 chose with `WebViewMouseEvent` (single class) versus splitting per event kind — the story explicitly asked for four POJOs.

- **Event source: pass `WebViewComponent` reference vs. opaque token.** Trade-off: passing the component reference is convenient (handler can call `SwingUtilities.getWindowAncestor(event.source())`) but couples the event POJO to the Swing API; an opaque token would let the same event type ship in a hypothetical future standalone-`WebView` path. **Recommendation: pass `WebViewComponent` reference.** The story explicitly scopes out the standalone `WebView` path, and the convenience is the whole reason the default handler can show modal dialogs without callers wiring the anchor.

- **`acceptedMimeTypes` / `acceptedExtensions` normalisation.** Trade-off: ship the raw `accept` attribute strings to Java (more flexibility) vs. normalise in the native layer (consistent across platforms). **Recommendation: normalise in the dispatcher (Java side).** The native layer reports what each platform makes available (`acceptedMIMETypes` + `acceptedFileExtensions` on WKOpenPanelParameters; `webkit_file_chooser_request_get_mime_types`/`..._mime_types_filter` on Linux; not available on Windows). The Java dispatcher canonicalises: lowercase, strip leading dot, dedupe, split on `,`. Story STORY-004-001 NF-expectation explicitly requires this normalisation.

- **STORY-004-002 wiring: separate functions per mode vs. shared.** Trade-off: heavyweight (`gtk_create_engine`) and lightweight (`gtk_create_offscreen_engine`) both create a `WebKitWebView`; the `script-dialog` / `run-file-chooser` signals are identical. Sharing is straightforward (two static C functions, two `g_signal_connect` sites). **Recommendation: shared functions, separate registration sites.** Mirrors the existing `gtk_create_engine` / `gtk_create_offscreen_engine` split for the rest of the engine setup.

- **STORY-004-003: full-functionality or partial?** The WebView2 file picker can't be intercepted, but the three script dialogs can. Trade-off: ship STORY-004-003 with the documented limitation (today's plan) vs. defer the entire Windows story until the file picker can also be intercepted somehow. **Recommendation: ship with documented limitation.** Three of four dialog kinds covered on Windows is materially more value than zero, and the file picker continues to work via WebView2's OS-native dialog — it just doesn't fire `filePickerOpened` on Windows. README documents the limitation. AC4 of STORY-004-003 explicitly verifies the unchanged file-picker behaviour.

### Alternatives Considered

- **JavaScript-shim approach** (intercept `window.alert` etc. via an `addOnBeforeLoad` shim, route through a reserved `__webview_dialog` binding) — **rejected.** Three reasons: (a) it can't intercept `<input type=file>` clicks because that's not a JS API the shim can override; (b) `before-unload` is a special browser event, not driven by `window.confirm()`, so the shim wouldn't see it; (c) every platform already provides a clean native-side hook (`WKUIDelegate`, `script-dialog` signal, `add_ScriptDialogOpening`) so using the lower-level path costs us nothing and gives us complete coverage. The shim approach would also be vulnerable to page code re-assigning `window.alert` after our shim runs.

- **Single async `WebViewDialogHandler` interface returning `CompletableFuture<...>` from each method** — **rejected.** Synchronous `JOptionPane.show*` calls are the natural Swing idiom; forcing handlers to return futures pushes complexity onto every caller and gains nothing because the underlying contract is synchronous JS-side. STORY-004-001 explicitly defines synchronous handler signatures (interface declared `default void alertOpened(event)` etc. in the requirements).

- **Per-engine `WKUIDelegate` instance vs. shared singleton.** Could reuse a single ObjC delegate object across all engines by reading `objc_getAssociatedObject(self, "eng")`. **Decision: per-engine.** Matches the existing per-engine pattern used by the script-message delegate (line 2085: `delegate = msg(delegate_cls, sel("new"))` is called once per engine). Adds negligible cost.

- **For Windows: synchronously block worker thread vs. deferral pattern.** Discussed above under Design Decisions.

- **Routing `before-unload` to a dedicated `beforeUnloadOpened` callback** — **rejected for this iteration.** The semantic difference (user has typed something, can lose data on navigation) deserves its own method, but the requirements explicitly defer this. Adding one default method is cheap and could happen as a follow-up; doing it now would expand the API surface for two platforms (Linux + Windows) that the user can already handle via `confirmOpened`. Followup work referenced in STORY-004-002 Scope-Out.

## Risk & Gap Analysis

### Requirement Ambiguities

- **`SwingUtilities.invokeAndWait` from EDT.** What happens if a caller invokes `WebViewComponent` operations from a handler that itself was invoked from `invokeAndWait`? The WKUIDelegate selectors are *not* on the EDT, so the dispatcher calls `invokeAndWait` to hop ONTO the EDT. Inside the handler the EDT IS the current thread. If the handler then calls `wv.dispatch(r)`, no problem — that hops to the native thread. But if the handler calls `wv.evalAsync(js)` and chains a `.thenAccept` continuation on the EDT, and then synchronously waits for it via `.get()`, **the handler deadlocks itself** (EDT waiting for an EDT task). **Resolution**: document in the handler interface Javadoc: "Methods run on the EDT; do not block on `evalAsync(...).get()` or any other EDT-scheduled task from inside the handler." STORY-004-001 should add this to the interface Javadoc and a corresponding NF expectation.

- **Default `WebViewFilePickerEvent.acceptedMimeTypes` empty vs. null vs. wildcard.** When the page's `<input type="file">` has no `accept` attribute, the WebKit / WebView2 API surfaces an empty list or null for accepted MIME types. The requirements say `acceptedMimeTypes` returns a `List<String>` but don't specify the empty-list contract precisely. **Resolution**: the list is never null; it's empty when no `accept` attribute is set. Document this on the POJO Javadoc. AC15 of STORY-004-001 doesn't check the empty case but the default `JFileChooser` impl needs to handle it.

- **What does `frameUrl` report when the dialog originates from a worker or a `srcdoc` iframe?** Workers can't call `alert` (no DOM), but `srcdoc` iframes can. WKWebView reports `initiatedByFrame.request.URL` which is `about:srcdoc` for srcdoc iframes; WebKitGTK reports the same; WebView2's `Uri` property gives the iframe URL. **Resolution**: `frameUrl` carries whatever the platform reports — `about:srcdoc` for srcdoc, the iframe URL for normal iframes, equals `pageUrl` for top-level. Document on the POJO Javadoc.

- **Multi-select file picker file order.** AC8 of STORY-004-001 says `files[0].name = "a.png"` and `files[1].name = "b.png"` — implies an ordering. Native order depends on `JFileChooser.getSelectedFiles()` (which returns whatever order the user selected them in, OS-dependent). **Resolution**: pass the order verbatim from `JFileChooser.getSelectedFiles()` to the platform completion handler; the platforms preserve order. AC8 should be read as "the two selected files appear in the page's `files` collection" (order matching JFileChooser order); not a strict alphabetical guarantee. Tighten the AC wording, or accept "in selection order" as the implicit contract.

- **`getDialogHandler()` returning `DEFAULT` when caller installed a custom handler.** AC17 of STORY-004-001 says `getDialogHandler()` returns "the framework's default handler instance" when no caller has set one. But what if a caller has called `setDialogHandler(custom)` and then `setDialogHandler(null)`? Returns the DROP handler? The DEFAULT? **Resolution**: `setDialogHandler(null)` installs the DROP handler, NOT a reset to DEFAULT. `getDialogHandler()` then returns the DROP handler. To reset to DEFAULT, callers explicitly pass `WebViewDialogHandler.DEFAULT`. Document on the setter.

- **AC4 of STORY-004-003** says the standard Windows file dialog "appears as today" — but does the test verify the WebView2's built-in file picker (which is what `<input type=file>` actually opens, not the Common Item Dialog directly)? **Resolution**: WebView2 opens the OS-native `IFileOpenDialog` (Common Item Dialog) under the hood; the test simply confirms that a system file dialog appears and a selection round-trips into the page's `files` collection. AC wording is acceptable; treat "Common Item Dialog" as descriptive, not prescriptive.

### Edge Cases

- **Page calls `alert` from `unload` or `pagehide`.** WKWebView refuses to show alerts from `pagehide` (engine-enforced). The delegate may still be called for legitimate `beforeunload` confirmations. **Behaviour**: as long as the delegate is invoked, the dispatcher fires; if the engine drops the request before reaching the delegate, the page sees the JS-spec default (alert returns undefined, confirm returns false). No code change needed.

- **Page calls multiple `alert`s in rapid succession.** Each `alert` blocks the JS thread until the previous returns. The native delegate is called once per alert; the dispatcher serially handles each. **Behaviour**: queue-of-one — the second `alert` doesn't fire its delegate until the first completes. No code change needed; just verify in AC.

- **Multiple `WebViewComponent`s in the same JFrame, both showing alerts simultaneously.** Each component has its own `DialogDispatcher`. Each Swing dialog anchors on the same host JFrame and is `Dialog.ModalityType.DOCUMENT_MODAL` to that frame. JOptionPane's modal pump on the EDT processes one at a time. **Behaviour**: the second `JOptionPane.show*` call queues until the first dismisses. The native JS thread of the second component remains blocked. No deadlock — the EDT eventually pumps both. NF expectation should call this out.

- **Handler returns null from `promptOpened`** — that's the cancel semantic, but what if the handler is implemented buggy-ly and throws NullPointerException? The dispatcher's try/catch catches it; returns null fallback. AC20 of STORY-004-001 covers this.

- **Native callback fires after `WebViewComponent.dispose()` has been called.** Java side has released the global ref; native side calls back into a stale JNI handle. **Behaviour**: native code must check for null-callback before invoking. The existing `cocoa_set_focus_callback` / `cocoa_set_click_callback` patterns already null-check (`if (!e->focus_callback) return`); the new `cocoa_set_dialog_callback` must do the same. On disposal, the native completion handler is still invoked with a safe fallback (alert: no-op; confirm: NO; prompt: nil; open-panel: nil) so the engine doesn't hang.

- **Handler shows a Swing dialog that triggers an internal `WebViewComponent` operation.** E.g. the user clicks a button in `confirmOpened`'s rendered dialog that calls `wv.setUrl(...)`. The handler is on the EDT; `setUrl` is allowed from the EDT (it dispatches to the native UI thread). **Behaviour**: works. But note: the native UI thread is currently blocked in the `WKUIDelegate` selector waiting for our return. So the new `setUrl` enqueues a navigation request that won't be serviced until the delegate returns. Caller may observe a delay. Document as a "don't do this" note in the Javadoc, but not a hard error.

- **Page calls `prompt(null, undefined)`** — the JS spec coerces both args to strings; `prompt(null)` shows a dialog with message "null". The native delegate sees this verbatim. **Behaviour**: the dispatcher carries the coerced strings; the Swing dialog displays them as text. No special handling.

- **Handler returns a non-existent file path from `filePickerOpened`.** The native side passes the file path to the platform completion handler (`NSArray<NSURL*>*` on macOS, `webkit_file_chooser_request_select_files` on Linux); the engines accept the path and the page's `files` collection contains a `File` object with that name. Whether the file is actually readable is a separate concern (the page's subsequent `FileReader` may fail). **Behaviour**: caller's responsibility. Document on the handler Javadoc.

- **Page is destroyed mid-dialog.** The handler's modal `JOptionPane` is still showing on the EDT, but `WebViewComponent.dispose()` was called from another thread. The native completion handler is gone; calling back into it would crash. **Behaviour**: the dispatcher's `dispatch*` methods must check `disposed` flag before invoking the handler AND before returning. If disposed mid-flight, dismiss the Swing dialog programmatically (call `dialog.dispose()` on the EDT), return the safe fallback, and the native side must check `disposed` before invoking the completion handler. This is a small extra plumbing concern. Map to a NF expectation: "Disposal during an open dialog dismisses the dialog and does not crash."

- **Page calls `alert` from a `MessageChannel`-posted message handler.** The handler can run synchronously inside the engine's microtask queue. The native delegate is still invoked; the dispatcher behaves identically. No special handling.

### Technical Risks

- **macOS `WKOpenPanelParameters._acceptedMIMETypes` / `_acceptedFileExtensions` are private API.** These accessors are not documented public; using them via KVC (`[parameters valueForKey:@"_acceptedMIMETypes"]`) works on macOS 10.12+ but could break in a future macOS release. **Mitigation**: wrap the KVC read in `@try`/`@catch`; fall back to empty list if missing. Document the limitation. Alternative: probe `parameters.respondsToSelector(@selector(...))` for a public alternative if any future macOS exposes one.

- **JNI synchronous round-trip with `SwingUtilities.invokeAndWait` from non-EDT.** Established pattern in Swing apps for decades — not novel. Risk is low.

- **Windows WebView2 worker thread blocking.** Synchronously blocking the worker thread inside the `ScriptDialogOpening` event is unusual; the WebView2 SDK examples use `GetDeferral` for any wait. **Mitigation**: implement the deferral pattern from the start. The native handler stashes the deferral, dispatches the Java call onto a worker thread (or directly invokes `dispatchAlertWindowsAsync` which does `invokeAndWait` to EDT), and on completion the answer is written back to the deferral via `webview_embed_dispatch` to hop back onto the WebView2 worker. Slightly more code than blocking-synchronous; safer.

- **Linux `webkit_script_dialog_*` API version differences.** WebKitGTK 2.x has stable signal names but the dialog's accessor functions may differ between 2.0 and 2.1 (the codebase supports both — README mentions `libwebkit2gtk-4.0-dev` and `libwebkit2gtk-4.1-dev`). **Mitigation**: use only the long-stable accessors (`webkit_script_dialog_get_dialog_type`, `..._get_message`, `..._prompt_get_default_text`, `..._confirm_set_confirmed`, `..._prompt_set_text`). All these have been in WebKitGTK since 2.0. No `webkit_script_dialog_ref` / `webkit_script_dialog_close` needed because the synchronous return model is sufficient — the dialog object stays alive while the signal handler runs.

- **`<input type=file>` on Linux returning a single-element list when `multiple` is true.** WebKitGTK's `webkit_file_chooser_request_select_files` always takes a list; multiplicity is up to the page. No issue.

- **Default `JFileChooser` modality.** `JFileChooser` shown via `showOpenDialog(component)` is automatically modal to the component's window. Same as `JOptionPane`. No special handling.

- **Coordinate / DPI scaling.** Not relevant for this story — Swing dialogs use platform-native rendering and don't need viewport coordinate translation. The mouse-event story had to handle this; dialogs don't.

- **`SwingUtilities.invokeAndWait` throwing `InterruptedException` / `InvocationTargetException`.** The dispatcher must catch both; on `InterruptedException`, restore the interrupt flag and return safe fallback; on `InvocationTargetException`, surface the wrapped exception via `Thread.getDefaultUncaughtExceptionHandler()` and return safe fallback. NF expectation: handler exceptions never leave the dispatcher.

- **The `WebViewDialogCallback` Java interface design (one method per dialog kind vs. one variadic method).** One method per kind keeps types tidy but means the JNI signature is split across four `CallVoidMethod` / `CallBooleanMethod` / `CallObjectMethod` / `CallObjectMethod` invocations from C. **Mitigation**: define the interface with four explicit methods; the native side caches the `jmethodID` for each at setup time. Adds ~50 lines of native code vs. a variadic single method, but is straightforward and the existing native code already caches method IDs (used by `fire_click_callback` line 166).

### Acceptance Criteria Coverage

**STORY-004-001 (Java API contract + macOS coverage) — 20 ACs:**

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Default alert handler shows Swing dialog | Yes | Standard Swing `JOptionPane.showMessageDialog(host, ...)`; modal-to-frame. |
| 2 | Default confirm OK returns true | Yes | `JOptionPane.OK_OPTION` mapping. |
| 3 | Default confirm Cancel returns false | Yes | Same path; `CANCEL_OPTION` returns false. |
| 4 | Default prompt entered text | Yes | `JOptionPane.showInputDialog`. |
| 5 | Default prompt null on cancel | Yes | `showInputDialog` returns null on cancel. |
| 6 | Default prompt default value pre-filled | Yes | Third arg of `showInputDialog`. |
| 7 | Default file picker single-file | Yes | `JFileChooser` without multi-selection. |
| 8 | Default file picker multi-file | Yes | `setMultiSelectionEnabled(true)` + `getSelectedFiles()`. |
| 9 | Default file picker accept filter | Yes | `FileNameExtensionFilter` from `acceptedExtensions`. |
| 10 | Custom alertOpened replaces default | Yes | Handler is single-resolver; replacement is direct. |
| 11 | Custom confirmOpened programmatic | Yes | Handler returns boolean. |
| 12 | Custom promptOpened programmatic | Yes | Handler returns String. |
| 13 | Custom filePickerOpened programmatic | Yes | Handler returns `List<File>`. |
| 14 | setDialogHandler(null) suppresses | Yes | DROP handler returns safe defaults; no UI. |
| 15 | WebViewFilePickerEvent multiple + accept hints | Yes | POJO accessors. |
| 16 | Handler callbacks on EDT | Yes | Dispatcher uses `invokeAndWait`. |
| 17 | getDialogHandler never returns null | Yes | DEFAULT is the no-op initial state. |
| 18 | pageUrl / frameUrl populated | Yes | Native delegate extracts `initiatedByFrame.request.URL`. |
| 19 | Iframe URL surfaces as frameUrl | Yes | Same path; iframe's `initiatedByFrame.request.URL` differs from top-level. |
| 20 | Handler exception does not crash WebKit | Yes | Dispatcher catches and forwards to uncaught-exception handler. |

All 20 addressable; no gaps.

**STORY-004-002 (Linux coverage) — 16 ACs:**

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | alert fires on heavyweight | Yes | Signal handler installed in `gtk_create_engine`. |
| 2 | alert fires on lightweight | Yes | Signal handler installed in `gtk_create_offscreen_engine`. |
| 3 | confirm OK both modes | Yes | Same path. |
| 4 | confirm Cancel both modes | Yes | Same path. |
| 5 | prompt entered text both modes | Yes | Same path. |
| 6 | prompt null on cancel | Yes | `webkit_script_dialog_prompt_set_text` not called for cancel; engine spec returns null. |
| 7 | File picker multi + accept on lightweight | Yes | `run-file-chooser` signal handler. |
| 8 | File picker on heavyweight | Yes | Same handler, separate registration. |
| 9 | Custom handler replaces default | Yes | Same dispatcher path as macOS. |
| 10 | setDialogHandler(null) does not freeze | Yes | DROP handler returns safe defaults. AC asserts "logs three lines through console-capture channel" — verifiable via existing console plumbing. |
| 11 | Handler callbacks on EDT | Yes | Dispatcher uses `invokeAndWait`. |
| 12 | before-unload routes to confirmOpened | Yes | `script-dialog` signal covers BEFORE_UNLOAD_CONFIRM; dispatcher maps it to `dispatchConfirm`. |
| 13 | Default GTK dialogs suppressed | Yes | Handlers return TRUE. |
| 14 | Heavyweight + lightweight identical behaviour | Yes | Same dispatcher, same JNI surface, same event POJOs. |
| 15 | WebViewFilePickerEvent accept hints | Yes | `webkit_file_chooser_request_get_mime_types_filter` extraction. |
| 16 | Empty list cancels file picker | Yes | `webkit_file_chooser_request_cancel` for empty result. |

All 16 addressable; AC10's "no `gdk_window_move_to_rect` warning" check requires careful stderr inspection during testing — flag as a "verify in dev environment" note, not a code-level concern.

**STORY-004-003 (Windows coverage) — 13 ACs:**

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Default alert handler shows Swing dialog | Yes | `add_ScriptDialogOpening` + EDT marshal. |
| 2 | Default confirm OK / Cancel | Yes | `Accept` for OK, leave un-accepted for Cancel (engine cancels). |
| 3 | Default prompt entered text / cancel | Yes | `put_ResultText` then `Accept`. |
| 4 | <input type=file> opens OS-native | Yes | We do nothing for file picker; default behaviour persists. |
| 5 | Custom alertOpened replaces default | Yes | Same dispatcher path. |
| 6 | Custom confirmOpened programmatic | Yes | Same. |
| 7 | Custom promptOpened programmatic | Yes | Same. |
| 8 | setDialogHandler(null) suppresses | Yes | DROP handler returns safe defaults; deferral completed without `Accept`. |
| 9 | before-unload routes to confirmOpened | Yes | `Kind == BEFOREUNLOAD` mapped to `dispatchConfirm`. |
| 10 | Handler callback on EDT | Yes | Dispatcher uses `invokeAndWait` from worker thread. |
| 11 | pageUrl / frameUrl populated | Yes | `ICoreWebView2ScriptDialogOpeningEventArgs::get_Uri`. |
| 12 | Built-in WebView2 dialogs do not appear | Yes | `put_AreDefaultScriptDialogsEnabled(FALSE)` at engine creation. |
| 13 | Handler exception completes deferral | Yes | Dispatcher's try/catch; native handler always calls `Complete` on the deferral. |

All 13 addressable. **Gap on AC11**: `Uri` corresponds to the top-level page URL; WebView2 does not expose a separate frame URL on the dialog event args. **Resolution**: on Windows, `frameUrl()` equals `pageUrl()` for now (top-level only). Document on the POJO Javadoc / README. AC11 only verifies the top-level case so the resolution is consistent with the AC wording.

### Open Questions

1. **The `WebViewDialogCallback` Java interface shape**: one method per dialog kind, vs. a single method dispatching on a discriminator? Both work; the former is more type-safe but adds 4 JNI signatures to cache. **Recommendation: one method per kind**, matching the four `dispatch*` methods on `DialogDispatcher`. To be confirmed during canvas authoring.

2. **`WebViewDialogHandler` interface placement**: in `ca.weblite.webview` (next to `ConsoleListener`, `WebViewMouseListener`) vs. `ca.weblite.webview.swing` (closer to where it's used and depends on Swing). **Recommendation: `ca.weblite.webview`**, matching the other handler/listener interfaces. The fact that its `default` methods use `JOptionPane` makes the package depend on Swing, but the existing dispatchers already do (`SwingUtilities.invokeLater`).

3. **Standalone `WebView` integration**: the requirements scope it out (line 134), but the standalone `WebView` also embeds a native engine that's subject to the same `alert` / `confirm` / `prompt` issue on macOS. **Decision: out of scope per requirements.** A follow-up story can mirror the API onto standalone `WebView` if asked.

4. **README documentation updates**: STORY-004-003 explicitly requires a README note about the Windows file-picker limitation. STORY-004-001 and STORY-004-002 should also describe the new API in the existing "Talking to JavaScript" / "Quick start" sections. **Decision: include README updates in each story's canvas Operations.** This is consistent with previous canvases (STORY-002-001 updated README via Operation 10).

5. **Demo program**: STORY-002-001 shipped `WebViewContextMenuDemo`. Should this story ship a `WebViewDialogDemo`? **Recommendation: yes** — a single small demo that exercises all four dialog kinds in both default-handler and custom-handler modes. Cheap to write, exercise-of-the-feature value. Each platform-specific story Operations should add or extend the demo as appropriate.

6. **Test strategy**: existing tests are unit-tests-without-real-engine (`EvalDispatcherTest`, `JavaScriptEvalExceptionTest`). The dispatcher can be unit-tested by simulating the four native `dispatch*` calls and verifying handler invocation + EDT-thread assertion. The native delegate / signal-handler / event-handler code is integration-tested via the demo and ACs run on actual hardware. **Decision: unit-test `DialogDispatcher` extensively; AC-test the rest on real engines.**
