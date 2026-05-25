# Story Decomposition: Browser-Initiated UI Dialogs (alert / confirm / prompt / file picker)

## INVEST Analysis

### Abstract Task: "Let JavaScript-driven UI dialogs originating inside the embedded page surface to the Swing host across every supported platform/mode, with consistent default behaviour and a single override hook"

**Analysis Dimensions**:
- **Core Responsibility**: A page running inside `WebViewComponent` can call `window.alert(msg)`, `window.confirm(msg)`, `window.prompt(msg, default)`, and can include `<input type="file">` elements whose click opens a file picker. Today the behaviour of these four dialog kinds is inconsistent across platforms and silently broken on macOS. This task gives `WebViewComponent` a single, uniform dialog channel so:
  - **By default**, every platform shows a Swing dialog (`JOptionPane` for alert / confirm / prompt, `JFileChooser` for file picker) anchored to the host `JFrame` and modal to it. Behaviour looks identical to the user regardless of which native backend is underneath.
  - **By override**, a Java caller can register a `WebViewDialogHandler` that takes over and decides what (if anything) to show — including returning a programmatic answer without showing UI, which is essential for headless tests.
- **Primary Operations**:
  1. Intercept the four dialog kinds at the native layer on each backend, defer the JS-side completion until the Java host has produced an answer, and pass the answer back without freezing the page beyond the dialog's intended modal lifetime.
  2. Marshal the dialog request onto the Swing EDT, invoke the active `WebViewDialogHandler` (default or caller-supplied), and pass the result back to the native layer.
  3. Expose a public Java API (`WebViewDialogHandler`, `setDialogHandler`, event POJOs) shared by all backends.
  4. Provide a default `WebViewDialogHandler` implementation that shows `JOptionPane` / `JFileChooser` modal to the host window.
- **Key Constraints**:
  - `alert` / `confirm` / `prompt` are **synchronous** in the JS contract — the page's microtask queue is blocked until the dialog returns. Every backend's native API provides a completion-handler / signal-handler model that supports this; Java just must not deadlock by trying to invoke the EDT from a thread that's holding back the UI thread the EDT depends on.
  - `<input type="file">` is **asynchronous** in the JS contract — the file picker fires after a user click and the page receives the chosen files via the `change` event.
  - The default Swing dialog must be modal to the **host `JFrame`** (`SwingUtilities.getWindowAncestor(component)`) so it doesn't get hidden behind other windows.
  - On `LIGHTWEIGHT` mode (Linux) the offscreen WebKit window has no `transient_for` — the native default GTK dialogs cannot position themselves and the right-click / `<select>` story already documents this constraint. Routing through Swing dialogs sidesteps it.
  - On Windows, WebView2's built-in dialogs already work; the only motivation to route through Swing there is **uniform look and feel and testability**. This is in scope — see STORY-004-003.
  - Behaviour must be observable from a small Swing harness; the handler-override path must be usable from a unit test (programmatic answer without showing UI) so headless test environments stay viable.
  - The reserved-binding convention (`__webview_` prefix) introduced by STORY-001-002 / STORY-002-001 is **not** the right channel for this — dialog requests originate from native browser-engine APIs, not from page-injected JS. No new reserved JS binding is introduced.
- **Technical Complexity**: Medium-High overall, split across three platform-shaped pieces:
  - macOS: implement a `WKUIDelegate` (currently nil — alerts/confirms/prompts are silently dropped, file pickers do nothing) and bridge each delegate method to the Java handler synchronously, releasing the native completion handler when Java returns.
  - Linux: connect handlers for `script-dialog` and `run-file-chooser` on `WebKitWebView`, suppress the default, and bridge them to Java. Identical wiring for heavyweight (X11-reparented) and lightweight (offscreen) modes — the GTK signal model is the same.
  - Windows: register `add_ScriptDialogOpening` and call `put_AreDefaultScriptDialogsEnabled(FALSE)` to take over alert/confirm/prompt. WebView2 exposes no public hook for `<input type="file">`; file picker keeps WebView2's built-in OS dialog (already works).
- **Business Complexity**: Low — the user-facing semantics of `alert / confirm / prompt / file picker` are already specified by the HTML spec. The work is bridging plumbing, not new business behaviour.

### INVEST Evaluation (whole feature)

- ✅ **Independent**: No story-level dependency on previously-shipped Canvases beyond the existing native-engine plumbing (canvases 6 / 7 / 2). Each per-platform sub-story is independently shippable; the Java API designed in STORY-004-001 is the contract the other two conform to.
- ✅ **Negotiable**: Specific defaults agreed with user — Swing dialogs anchored on the host JFrame; single `WebViewDialogHandler` per component replacing any default; null handler suppresses dialogs entirely.
- ✅ **Valuable**: Without this work, large classes of real-world web content (auth flows that call `confirm`, upload pages that use `<input type=file>`, third-party SDKs that show `alert`-based onboarding) silently fail on macOS and behave inconsistently elsewhere. With it, embedded pages "just work" the way callers expect.
- ✅ **Estimable**: Each backend has a well-documented native-API surface for these four interceptions. The Java side is one interface + four POJOs + a default implementation.
- ⚠️ **Small (as a single story)**: Combined, the work spans three native backends and the public Java API — realistically 7-10 days. **This exceeds the 1-5 day INVEST sizing target.** Splitting is required (see below).
- ✅ **Testable**: Default behaviour is observable from a Swing harness; override behaviour is observable from a unit test by registering a programmatic handler.

**Conclusion**: Needs splitting along platform-backend boundaries. The Java API contract is established in STORY-004-001 (which also delivers the macOS implementation, the platform that is currently fully broken). STORY-004-002 (Linux) and STORY-004-003 (Windows) extend coverage to the remaining backends while conforming to the same Java contract.

### Split Strategy

Split by **technical dependency / native backend**, because:

- Each native backend's interception API is distinct (ObjC delegate protocol on macOS, GTK signal handlers on Linux, COM event-source on Windows). The work to wire each one is independent of the others.
- The Java contract is shared and must be designed once — that's STORY-004-001's primary responsibility, co-delivered with macOS because macOS is the platform with zero current coverage.
- Each story delivers independent value: shipping STORY-004-001 alone fixes macOS (currently silent) without regressing Linux or Windows; STORY-004-002 alone fixes Linux lightweight (currently broken via the `transient_for` constraint already documented in README.md); STORY-004-003 alone gives Windows uniform Swing-style dialogs.
- Each story is 2-4 days, well within INVEST sizing.

Story dependency graph:

```
STORY-004-001 (Java API contract + macOS)
        │
        ├──► STORY-004-002 (Linux heavyweight + lightweight) — depends on the Java contract from 004-001
        │
        └──► STORY-004-003 (Windows) — depends on the Java contract from 004-001
```

STORY-004-002 and STORY-004-003 can be developed in parallel once STORY-004-001 has landed.

---

## [STORY-004-001] WebViewDialogHandler API and macOS WKWebView Coverage

### Background

JavaScript inside the embedded page can call `window.alert(msg)`, `window.confirm(msg)`, `window.prompt(msg, default)`, and the page can include `<input type="file">` elements whose click opens a file picker. Today, behaviour of these four dialog kinds is inconsistent across the platforms `WebViewComponent` supports, and **on macOS all four are silently broken** because no `WKUIDelegate` is attached to the `WKWebView`:

- `WKWebView` consults its `uiDelegate` to know what to do for `runJavaScriptAlertPanelWithMessage:` / `runJavaScriptConfirmPanel:` / `runJavaScriptTextInputPanel:` / `runOpenPanelWithParameters:`. If the delegate is `nil` — which is the case in this codebase — `WKWebView` simply discards the request. The JS-side `alert(...)` returns immediately, `confirm(...)` returns `false`, `prompt(...)` returns `null`, and `<input type="file">` clicks open no picker at all. Real-world pages relying on these (auth flows, file uploads, embedded SDKs that call `alert` for errors) silently misbehave.

This story does two things at once:

1. **Designs and exposes the cross-platform Java contract** for browser-initiated UI dialogs — one `WebViewDialogHandler` interface with default implementations that show Swing dialogs, four event POJOs, and a setter on `WebViewComponent`. The same contract is the one STORY-004-002 (Linux) and STORY-004-003 (Windows) will conform to.
2. **Implements that contract on macOS** by attaching a `WKUIDelegate` to the `WKWebView` that bridges each of the four delegate methods to the Java handler, marshals to the EDT, and releases the native completion handler when the handler returns. The default `WebViewDialogHandler` shows `JOptionPane` (alert / confirm / prompt) and `JFileChooser` (file picker), modal to the host `JFrame`.

The chosen design — handler override defaulting to Swing dialogs anchored on the host frame — was selected for these reasons:

- A Swing-dialog default is **portable**: the same `JOptionPane.showMessageDialog(host, msg)` call works identically on every platform's JVM. There is no per-platform "native dialog" code path to maintain on the Java side.
- A single-handler model (`setDialogHandler(handler)` replacing the default) keeps the call site simple. Callers don't need to compose listeners; they either accept the default or supply their own implementation. Tests register a programmatic handler that returns a fixed answer without showing UI.
- The handler is **per-component-instance**. Two `WebViewComponent`s in the same JVM can have different handlers (or share one).

Key points:
- Business value: every desktop app embedding a WebView needs basic dialog interop to host real-world content. macOS is the most broken today (silent failure), so this is the highest-leverage place to start.
- Relationship with other features: orthogonal to console capture (canvas-2 / STORY-001-002) and to right-click context menus (STORY-002-001). The reserved `__webview_` binding prefix is not used — these dialog requests come from native browser-engine callbacks, not page-injected JS.
- Why now: STORY-002-001 (right-click menus + DOM event channel) landed the precedent for "structured, EDT-marshaled events surfaced from the embedded page to Swing." This story extends that precedent to the second of the two most common Swing-Web interop needs.

### Business Value

- Provide **working `alert / confirm / prompt`** on macOS, restoring a baseline of HTML-spec behaviour that is currently silently broken.
- Provide a **working `<input type="file">`** picker on macOS, enabling upload flows that today do nothing when clicked.
- Provide a **single Java contract** (`WebViewDialogHandler`) that the Linux and Windows coverage stories will conform to, ensuring the embedded page sees the same behaviour regardless of platform.
- Provide a **default Swing dialog look-and-feel** out of the box, so callers who do nothing get a sensible cross-platform experience.
- Provide a **deterministic override hook** so unit tests and headless environments can answer dialogs programmatically without spawning UI.

### Dependencies and Assumptions

- **Prerequisites**: Canvases 5, 6, 7 (mode selection, heavyweight, lightweight) in place. The macOS heavyweight engine already creates a `WKWebView` and attaches a `WKScriptMessageHandler` — this story adds a `WKUIDelegate` to the same `WKWebView`. No dependency on STORY-001-x or STORY-002-x.
- **Data assumptions**: No persisted state. The active handler is a per-component-instance reference. Default handler is shared (stateless `JOptionPane` / `JFileChooser` calls).
- **Integration points**: `WKWebView` (macOS) — adds a `WKUIDelegate` to the existing webview. The `WKUIDelegate` selectors `webView:runJavaScriptAlertPanelWithMessage:initiatedByFrame:completionHandler:`, `webView:runJavaScriptConfirmPanelWithMessage:initiatedByFrame:completionHandler:`, `webView:runJavaScriptTextInputPanelWithPrompt:defaultText:initiatedByFrame:completionHandler:`, and `webView:runOpenPanelWithParameters:initiatedByFrame:completionHandler:` are the four bridging points.
- **Business constraints**:
  - `alert` / `confirm` / `prompt` are **synchronous** in JS — the JS thread is paused until the completion handler is invoked. The Swing dialog must therefore run modal on the EDT and the native completion handler must be invoked only after the EDT has produced an answer. The bridging must not deadlock the WebKit UI thread by waiting on the EDT while the EDT is waiting on the WebKit UI thread.
  - `<input type="file">` is **asynchronous** in JS — `webView:runOpenPanelWithParameters:initiatedByFrame:completionHandler:` completes when the picker is dismissed; the page receives the chosen files via the `change` event. The completion handler accepts `nil` or `NSArray<NSURL *> *`.
  - The default Swing dialog is anchored on `SwingUtilities.getWindowAncestor(component)` — the host `JFrame`. If the component is not yet attached to a window, the default falls back to anchoring on the component itself (which Swing will reparent to a hidden frame). Callers who construct a `WebViewComponent` without attaching it to a window before triggering a dialog get the same fallback behaviour `JOptionPane` already gives them.
  - The `runOpenPanelWithParameters:` parameters carry `allowsMultipleSelection` and `acceptedMIMETypes` / `acceptedFileExtensions` (via the page's `<input accept="..." multiple>` attributes). The default file-picker handler honours both — `JFileChooser.setMultiSelectionEnabled` and a `FileNameExtensionFilter` derived from the extensions / MIME-to-extension mapping.

### Scope In

- New public interface `ca.weblite.webview.WebViewDialogHandler` with four `default` methods, all invoked on the Swing EDT:
  - `default void alertOpened(WebViewAlertEvent event)` — default impl: `JOptionPane.showMessageDialog(host, event.message(), event.source().getName(), JOptionPane.PLAIN_MESSAGE)`.
  - `default boolean confirmOpened(WebViewConfirmEvent event)` — default impl: `JOptionPane.showConfirmDialog(host, event.message(), event.source().getName(), JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION`.
  - `default String promptOpened(WebViewPromptEvent event)` — default impl: `JOptionPane.showInputDialog(host, event.message(), event.defaultValue())`. Returns `null` on cancel.
  - `default java.util.List<java.io.File> filePickerOpened(WebViewFilePickerEvent event)` — default impl: a modal `JFileChooser` honouring `event.multiple()` and `event.acceptedExtensions()`. Returns empty list on cancel.
- New public POJOs in `ca.weblite.webview`, all immutable with public accessors:
  - `WebViewAlertEvent` — `WebViewComponent source()`, `String message()`, `String pageUrl()`, `String frameUrl()`.
  - `WebViewConfirmEvent` — `WebViewComponent source()`, `String message()`, `String pageUrl()`, `String frameUrl()`.
  - `WebViewPromptEvent` — `WebViewComponent source()`, `String message()`, `String defaultValue()`, `String pageUrl()`, `String frameUrl()`.
  - `WebViewFilePickerEvent` — `WebViewComponent source()`, `boolean multiple()`, `java.util.List<String> acceptedExtensions()` (lower-case, leading dot omitted, e.g. `["png","jpg","jpeg"]`), `java.util.List<String> acceptedMimeTypes()` (e.g. `["image/png","image/*"]`), `String pageUrl()`, `String frameUrl()`.
- New public methods on `WebViewComponent`:
  - `WebViewComponent setDialogHandler(WebViewDialogHandler handler)` — replaces the active handler. Passing `null` installs a "drop" handler whose methods return synchronously without showing UI (`alertOpened` returns immediately; `confirmOpened` returns `false`; `promptOpened` returns `null`; `filePickerOpened` returns empty list). Passing a non-null handler installs that handler; per-method overrides apply, un-overridden methods fall through to the interface defaults (Swing dialogs).
  - `WebViewDialogHandler getDialogHandler()` — returns the currently installed handler. Never returns `null`; the "default" instance is returned when no caller has set one.
- macOS heavyweight implementation:
  - Define an Objective-C delegate class implementing the four `WKUIDelegate` selectors (alert / confirm / prompt / open-panel). Register it as the `uiDelegate` of every `WKWebView` created by the engine.
  - Each selector marshals to the Swing EDT, invokes the active Java handler, captures the return value, and invokes the WebKit completion handler with the captured value. The marshal uses `SwingUtilities.invokeAndWait` from a non-EDT WebKit thread — never from the EDT itself (the WKWebView delegate selectors are not invoked on the EDT).
  - The `runOpenPanelWithParameters:` selector extracts `allowsMultipleSelection`, `acceptedMIMETypes`, and `acceptedFileExtensions` (where available on the WKOpenPanelParameters version present) and populates the `WebViewFilePickerEvent` accordingly.
  - Cleanup: the delegate is released when the engine is destroyed; no leaked reference from `WKWebView` to a freed Engine.
- All four event POJOs include `pageUrl()` and `frameUrl()` (the top document and the immediately-initiating frame's URL, respectively) so handlers can apply per-origin policy.

### Scope Out

- Linux WebKitGTK interception — STORY-004-002.
- Windows WebView2 `add_ScriptDialogOpening` interception — STORY-004-003.
- The Linux lightweight component on macOS (which is a documented dead end per the README) — not a target platform.
- The standalone in-process `WebView` class — this story is for embedded `WebViewComponent` only. Adding the same API to standalone `WebView` is a follow-up if a user asks for it.
- Custom-styled dialogs (HTML-rendered overlays, themed components, etc.) — callers who want a custom look set their own `WebViewDialogHandler`.
- `window.open(url, name, features)` popups and `beforeunload` confirmation prompts — these are different WKWebView delegate methods (`createWebViewWithConfiguration:` and the navigation delegate's `decidePolicyForNavigationAction:`) and are out of scope for this story.
- HTTP basic / digest authentication challenges (`webView:didReceiveAuthenticationChallenge:`) — a different delegate channel, not a JS-initiated dialog.
- Drag-and-drop file uploads — a different code path that doesn't go through `runOpenPanel`.
- Per-call cancellation from Java mid-dialog (e.g. a way to dismiss an open `confirm` from Java) — handler returns determine the outcome; mid-flight cancellation is out of scope.
- Telemetry / diagnostic logging of dialog events to stderr — handlers that want to log do so themselves.

### Acceptance Criteria

#### AC1: Default alert handler shows a Swing message dialog on macOS
**Given** a `WebViewHeavyweightComponent` running on macOS, attached to a visible `JFrame`, with no custom `WebViewDialogHandler` set,
**When** the loaded page calls `alert("hello world")`,
**Then** a Swing `JOptionPane` message dialog appears modal to the host `JFrame` containing the text `"hello world"`, and `alert(...)` returns to JS only after the user dismisses the dialog.

#### AC2: Default confirm handler returns true when user clicks OK on macOS
**Given** a `WebViewHeavyweightComponent` on macOS with no custom handler set, loaded with a page that runs `const r = confirm("proceed?"); document.title = String(r);`,
**When** the user clicks the OK button on the resulting Swing dialog,
**Then** the page's `document.title` becomes the string `"true"`.

#### AC3: Default confirm handler returns false when user clicks Cancel on macOS
**Given** the same setup as AC2,
**When** the user clicks the Cancel button on the resulting Swing dialog,
**Then** the page's `document.title` becomes the string `"false"`.

#### AC4: Default prompt handler returns the entered text on macOS
**Given** a `WebViewHeavyweightComponent` on macOS with no custom handler, loaded with a page that runs `const r = prompt("name?", "default"); document.title = String(r);`,
**When** the user types `"alice"` into the resulting Swing input dialog and clicks OK,
**Then** the page's `document.title` becomes `"alice"`.

#### AC5: Default prompt handler returns null on cancel on macOS
**Given** the same setup as AC4,
**When** the user clicks Cancel on the resulting Swing input dialog,
**Then** the page's `document.title` becomes the string `"null"` (the JS `null` value, stringified).

#### AC6: Default prompt handler pre-fills the default text on macOS
**Given** the same setup as AC4,
**When** the Swing input dialog appears,
**Then** the input field is pre-populated with the text `"default"` so the user can edit or accept it directly.

#### AC7: Default file picker honours single-file selection on macOS
**Given** a `WebViewHeavyweightComponent` on macOS, loaded with `<input type="file" id="f">` and no custom handler,
**When** the user clicks the file input and selects exactly one file `/tmp/a.png` in the resulting Swing `JFileChooser`,
**Then** the page's `document.getElementById('f').files.length` is `1` and `files[0].name` is `"a.png"`.

#### AC8: Default file picker honours multiple-file selection on macOS
**Given** the same setup as AC7 but with `<input type="file" id="f" multiple>`,
**When** the user selects two files `/tmp/a.png` and `/tmp/b.png`,
**Then** the page's `document.getElementById('f').files.length` is `2`, and `files[0].name` is `"a.png"` and `files[1].name` is `"b.png"`.

#### AC9: Default file picker honours accept attribute on macOS
**Given** a `WebViewHeavyweightComponent` on macOS, loaded with `<input type="file" accept=".png,.jpg">`,
**When** the user clicks the file input,
**Then** the resulting Swing `JFileChooser` filters to files with extensions `.png` or `.jpg`; files with other extensions are not selectable through the active filter.

#### AC10: Custom alert handler replaces the default
**Given** a `WebViewHeavyweightComponent` on macOS with `setDialogHandler(new WebViewDialogHandler() { @Override public void alertOpened(WebViewAlertEvent e) { recorded = e.message(); } })` set,
**When** the loaded page calls `alert("ping")`,
**Then** no Swing dialog appears, the variable `recorded` holds the string `"ping"`, and `alert(...)` returns to JS within 50 ms of being called.

#### AC11: Custom confirm handler returns a programmatic answer
**Given** a `WebViewHeavyweightComponent` on macOS with a `WebViewDialogHandler` whose `confirmOpened` returns `true` unconditionally,
**When** the page calls `const r = confirm("?"); document.title = String(r);`,
**Then** no Swing dialog appears and `document.title` becomes `"true"`.

#### AC12: Custom prompt handler returns a programmatic answer
**Given** a `WebViewHeavyweightComponent` on macOS with a `WebViewDialogHandler` whose `promptOpened` returns `"hardcoded"`,
**When** the page calls `const r = prompt("?", "x"); document.title = String(r);`,
**Then** no Swing dialog appears and `document.title` becomes `"hardcoded"`.

#### AC13: Custom file-picker handler returns a programmatic file list
**Given** a `WebViewHeavyweightComponent` on macOS with a `WebViewDialogHandler` whose `filePickerOpened` returns `Arrays.asList(new File("/tmp/preselected.txt"))`,
**When** the page contains `<input type="file" id="f">` and the user clicks it,
**Then** no Swing `JFileChooser` appears, `document.getElementById('f').files.length` is `1`, and `files[0].name` is `"preselected.txt"`.

#### AC14: setDialogHandler(null) suppresses dialogs without freezing the page
**Given** a `WebViewHeavyweightComponent` on macOS with `setDialogHandler(null)` set,
**When** the page calls `alert("x")`, then `const c = confirm("y")`, then `const p = prompt("z", "w")`,
**Then** no Swing dialog appears, the page's JS thread continues without hanging, `c` is `false`, and `p` is `null`.

#### AC15: WebViewFilePickerEvent surfaces multiple and accept hints
**Given** a `WebViewHeavyweightComponent` on macOS with a custom handler that records `event.multiple()`, `event.acceptedExtensions()`, and `event.acceptedMimeTypes()`,
**When** the page contains `<input type="file" accept="image/*,.pdf" multiple>` and the user clicks it,
**Then** the handler is invoked once with `multiple()` `true`, `acceptedExtensions()` containing `"pdf"`, and `acceptedMimeTypes()` containing `"image/*"`.

#### AC16: Handler callbacks fire on the Swing EDT
**Given** a `WebViewHeavyweightComponent` on macOS with a custom handler that records `SwingUtilities.isEventDispatchThread()` in each of its four methods,
**When** the page in turn triggers `alert`, `confirm`, `prompt`, and a click on `<input type=file>`,
**Then** every recorded value is `true`.

#### AC17: getDialogHandler never returns null
**Given** a freshly constructed `WebViewHeavyweightComponent` with no handler set,
**When** the caller invokes `getDialogHandler()`,
**Then** the return value is non-null and refers to the framework's default handler instance (the one that shows Swing dialogs).

#### AC18: pageUrl / frameUrl are populated on each event
**Given** a `WebViewHeavyweightComponent` on macOS loaded with `https://example.com/index.html` and a custom handler that records `event.pageUrl()` and `event.frameUrl()`,
**When** the top-level page calls `alert("x")`,
**Then** the handler records `pageUrl()` `"https://example.com/index.html"` and `frameUrl()` `"https://example.com/index.html"` (same value because the alert came from the top-level frame).

#### AC19: Dialog from a same-origin iframe surfaces the iframe URL as frameUrl
**Given** a `WebViewHeavyweightComponent` on macOS, loaded at `https://example.com/parent.html`, which embeds a same-origin iframe at `https://example.com/child.html` that calls `alert("hi from child")`, with a custom handler that records both URLs,
**When** the alert fires,
**Then** the handler records `pageUrl()` `"https://example.com/parent.html"` and `frameUrl()` `"https://example.com/child.html"`.

#### AC20: Handler exception does not crash WebKit
**Given** a `WebViewHeavyweightComponent` on macOS with a custom handler whose `alertOpened` throws a `RuntimeException`,
**When** the page calls `alert("x")`,
**Then** the exception is surfaced via standard EDT uncaught-exception handling, the JS-side `alert(...)` returns within a bounded time (no hang), the WebView remains responsive, and the host app does not crash.

### Non-Functional Expectations

- The bridging from the macOS WebKit thread to the Swing EDT must not deadlock. In particular, when the WebKit UI thread is suspended waiting for an `alert`/`confirm`/`prompt` answer, the EDT must remain free to run the modal `JOptionPane` (i.e. the synchronisation primitives used to wait for the EDT must not block the EDT itself).
- The handler-override path must be usable from a unit test in a headless environment — registering a programmatic handler that returns a fixed answer must not cause Swing to attempt to instantiate a `Window`.
- Default Swing dialogs must be modal to the host `JFrame` (not application-modal) so that other unrelated `JFrame`s in the host app remain interactive while a single WebView's dialog is open.
- `<input type="password">` is **not** invoked through `runOpenPanelWithParameters:` (it isn't a file input); this story imposes no new password-field behaviour. Password values continue to be the page's own concern.
- The `WebViewFilePickerEvent.acceptedExtensions()` list must be normalised (lower-case, leading dot stripped) so that handlers don't have to special-case `.PNG` vs `.png` vs `png`.

---

## [STORY-004-002] Linux WebKitGTK Coverage for WebViewDialogHandler (Heavyweight + Lightweight)

### Background

STORY-004-001 establishes the `WebViewDialogHandler` Java contract and ships the macOS implementation. This story extends coverage to Linux — both `WebViewHeavyweightComponent` (X11-reparented WebKitGTK) and `WebViewLightweightComponent` (offscreen WebKitGTK rendered into a `BufferedImage`). The two modes share the same WebKit engine and the same signal-handler model, so they share native code.

WebKitGTK exposes browser-initiated dialogs via two signals on `WebKitWebView`:

- `script-dialog` (`WebKitScriptDialog *`) — fired for `alert` / `confirm` / `prompt` / `before-unload`. Connecting a handler that returns `TRUE` suppresses the default GTK dialog and lets the application drive the response via `webkit_script_dialog_confirm_set_confirmed(dialog, ...)` / `webkit_script_dialog_prompt_set_text(dialog, ...)`. The dialog's lifetime is bound by the handler returning; for async response, `webkit_script_dialog_ref(dialog)` keeps it alive until the application calls `webkit_script_dialog_close(dialog)`.
- `run-file-chooser` (`WebKitFileChooserRequest *`) — fired for `<input type="file">` clicks. The request exposes `webkit_file_chooser_request_get_select_multiple`, `webkit_file_chooser_request_get_mime_types` / `..._get_mime_types_filter`. The application calls `webkit_file_chooser_request_select_files(request, paths)` or `webkit_file_chooser_request_cancel(request)`. Returning `TRUE` from the handler suppresses the default GTK file chooser.

In heavyweight mode the default GTK dialogs *do* appear today, but our GTK window is reparented under an X11 parent that GTK doesn't know about — the same `gdk_window_move_to_rect: 'window->transient_for'` constraint already documented in `README.md` as breaking right-click menus and `<select>` dropdowns. In lightweight mode the WebView lives in a `GtkOffscreenWindow` with no `transient_for` at all, and the default dialogs cannot position themselves.

This story sidesteps both Linux pain points by **intercepting both signals and routing them to the `WebViewDialogHandler` set on the Java component**. The default handler (from STORY-004-001) shows Swing dialogs anchored on the host `JFrame`, which avoids the GTK parenting problem entirely.

Key points:
- Business value: makes `alert / confirm / prompt` and `<input type="file">` actually work in lightweight mode (where they're broken today) and gives heavyweight a consistent, Swing-styled look that doesn't suffer from the orphan-dialog rendering glitches.
- Relationship with other features: builds on the JNI bridge already used for `WebViewClickCallback`, `WebViewMouseDispatcher`, and `ConsoleDispatcher`. No new JNI infrastructure is needed — same async-callback-into-Java-from-GTK-thread pattern.
- Why now: STORY-004-001 lands the Java contract; without this story Linux callers see `setDialogHandler(...)` doing nothing on their platform.

### Business Value

- Provide **working `alert / confirm / prompt`** in `WebViewLightweightComponent` on Linux, which is currently broken because the offscreen GTK window has no `transient_for`.
- Provide **working `<input type="file">`** in `WebViewLightweightComponent` on Linux for the same reason.
- Provide a **consistent Swing-styled experience** in heavyweight mode that avoids the documented `transient_for` rendering glitches the default GTK dialogs hit when WebKit is parented under an X11 window GTK doesn't own.
- Fulfil the **cross-platform contract** introduced by `WebViewDialogHandler`: callers writing `setDialogHandler(...)` get identical Java semantics regardless of which native backend is underneath.

### Dependencies and Assumptions

- **Prerequisites**: STORY-004-001 must be complete (the `WebViewDialogHandler` interface, the four POJOs, and the `setDialogHandler` / `getDialogHandler` methods on `WebViewComponent` must already be in place). Canvases 6 and 7 (heavyweight + lightweight engines) in place.
- **Data assumptions**: No persisted state; same per-component handler reference as on macOS.
- **Integration points**: WebKitGTK 2.x signals `script-dialog` and `run-file-chooser` on `WebKitWebView`. Functions used: `webkit_script_dialog_get_dialog_type`, `webkit_script_dialog_get_message`, `webkit_script_dialog_prompt_get_default_text`, `webkit_script_dialog_confirm_set_confirmed`, `webkit_script_dialog_prompt_set_text`, `webkit_script_dialog_ref`, `webkit_script_dialog_close`, `webkit_file_chooser_request_get_select_multiple`, `webkit_file_chooser_request_get_mime_types_filter`, `webkit_file_chooser_request_select_files`, `webkit_file_chooser_request_cancel`. Same signals on the same `WebKitWebView` whether it's living in a foreign X11 window (heavyweight) or a `GtkOffscreenWindow` (lightweight).
- **Business constraints**:
  - The signal handler must return `TRUE` to claim the dialog. If `TRUE` is returned, no further default behaviour fires; if `FALSE`, the default GTK dialog appears. The handler always returns `TRUE` once this story ships — there is no path where the Java handler is invoked and the GTK default also fires.
  - The `script-dialog` handler runs on the GTK main thread (the GTK pump thread per `README.md`). The Java handler must run on the EDT. The bridge must marshal from the GTK thread to the EDT, wait for the answer, and feed it back to WebKit. The current ConsoleDispatcher / MouseDispatcher pattern is to call back into Java from the GTK thread synchronously; this story adds a parallel callback for dialogs.
  - `before-unload` dialogs (`WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM`) reach the same `script-dialog` signal. **They are routed to the `confirmOpened` handler** because that's the closest semantic fit (a yes/no question) and the default `confirmOpened` will show a Swing OK/Cancel dialog. Future work can add a dedicated `beforeUnloadOpened` method to `WebViewDialogHandler` if needed.

### Scope In

- Native: connect `script-dialog` and `run-file-chooser` signals on every `WebKitWebView` created by the heavyweight and lightweight engines (single connection site per engine, both engines share it).
- Native: dispatch the dialog kind (alert / confirm / prompt / before-unload mapped to confirm / file-picker) to Java via the existing JNI callback bridge, populating the same four event POJOs from STORY-004-001.
- Native: receive the Java answer back (void / boolean / String / `List<File>`) and call the appropriate `webkit_script_dialog_*` / `webkit_file_chooser_request_*` function to complete the request.
- Java: no new public API — all public types come from STORY-004-001. Internal: a small dispatcher class (e.g. `DialogDispatcher`) lives in `ca.weblite.webview` and is invoked from JNI; it marshals to the EDT, calls the active `WebViewDialogHandler`, and returns the answer.
- Behaviour-symmetric across heavyweight and lightweight modes: the same JNI hooks, the same Java dispatcher, the same default Swing dialogs.

### Scope Out

- Windows WebView2 coverage — STORY-004-003.
- macOS — already covered by STORY-004-001.
- Adding a dedicated `beforeUnloadOpened` callback to `WebViewDialogHandler`. Before-unload is routed to `confirmOpened` in this story; a dedicated handler can be added later if a caller asks.
- `webView:permissionRequest:` / `webkit_permission_request_*` (geolocation, notifications, camera) — not a dialog in the alert/confirm/prompt sense; out of scope.
- Authentication dialogs (`authenticate` signal) — different signal, different shape, out of scope.
- Drag-and-drop file uploads — different code path, out of scope.
- HTTP/network proxy / TLS-error dialogs — different signal channel, out of scope.

### Acceptance Criteria

#### AC1: alert fires the dialog handler on Linux heavyweight
**Given** a `WebViewHeavyweightComponent` on Linux, attached to a visible `JFrame`, with no custom `WebViewDialogHandler` set,
**When** the loaded page calls `alert("hello")`,
**Then** a Swing `JOptionPane` message dialog appears modal to the host `JFrame` showing `"hello"`, and the default GTK dialog does not appear.

#### AC2: alert fires the dialog handler on Linux lightweight
**Given** a `WebViewLightweightComponent` on Linux, attached to a visible `JFrame`, with no custom handler set,
**When** the loaded page calls `alert("hello")`,
**Then** a Swing `JOptionPane` message dialog appears modal to the host `JFrame` showing `"hello"`. No `gdk_window_move_to_rect: 'window->transient_for'` warning appears in stderr from this dialog.

#### AC3: confirm round-trips OK on heavyweight and lightweight
**Given** a `WebViewComponent` on Linux (run this AC once in each mode) with no custom handler, loaded with `const r = confirm("?"); document.title = String(r);`,
**When** the user clicks OK on the Swing dialog,
**Then** the page's `document.title` becomes `"true"`.

#### AC4: confirm round-trips Cancel on heavyweight and lightweight
**Given** the same setup as AC3,
**When** the user clicks Cancel,
**Then** the page's `document.title` becomes `"false"`.

#### AC5: prompt round-trips the entered text on heavyweight and lightweight
**Given** a `WebViewComponent` on Linux (each mode in turn) with no custom handler, loaded with `const r = prompt("name?", "default"); document.title = String(r);`,
**When** the user types `"bob"` and clicks OK,
**Then** the page's `document.title` becomes `"bob"`.

#### AC6: prompt returns null on cancel
**Given** the same setup as AC5,
**When** the user clicks Cancel,
**Then** the page's `document.title` becomes `"null"`.

#### AC7: File picker honours multiple and accept on lightweight
**Given** a `WebViewLightweightComponent` on Linux with `<input type="file" accept=".png" multiple>` and no custom handler,
**When** the user clicks the input and selects two `.png` files in the resulting Swing `JFileChooser`,
**Then** `document.querySelector('input[type=file]').files.length` is `2`.

#### AC8: File picker on heavyweight
**Given** a `WebViewHeavyweightComponent` on Linux with `<input type="file">` and no custom handler,
**When** the user clicks the input and selects one file in the resulting Swing `JFileChooser`,
**Then** `document.querySelector('input[type=file]').files.length` is `1` and the default GTK file dialog does not appear.

#### AC9: Custom handler replaces the default on both modes
**Given** a `WebViewComponent` on Linux (each mode in turn) with `setDialogHandler(handler)` set, where `handler.confirmOpened` returns `true` unconditionally,
**When** the page calls `const r = confirm("?"); document.title = String(r);`,
**Then** no Swing dialog appears and `document.title` becomes `"true"`.

#### AC10: setDialogHandler(null) does not freeze the page on Linux
**Given** a `WebViewComponent` on Linux (each mode in turn) with `setDialogHandler(null)`,
**When** the page calls `alert("x")` then `confirm("y")` then `prompt("z", "w")`,
**Then** no Swing dialog appears, the page's JS thread continues without hanging, and the page subsequently logs three lines through the existing console-capture channel proving execution proceeded past each call.

#### AC11: Handler callbacks fire on the EDT
**Given** a `WebViewComponent` on Linux (each mode in turn) with a custom handler that records `SwingUtilities.isEventDispatchThread()` in all four methods,
**When** the page in turn triggers `alert`, `confirm`, `prompt`, and a file-input click,
**Then** every recorded value is `true`.

#### AC12: before-unload routes to confirmOpened
**Given** a `WebViewComponent` on Linux (each mode in turn) with a custom handler whose `confirmOpened` returns `true` and records the event message, loaded with a page that registers `window.onbeforeunload = () => "are you sure?";`,
**When** the page navigates away (e.g. by calling `location.href = '...'`),
**Then** `confirmOpened` is invoked exactly once, the recorded message contains `"are you sure"` (the browser may prepend / append boilerplate per WebKit version), and navigation proceeds because the handler returned `true`.

#### AC13: Default GTK dialogs are fully suppressed
**Given** a `WebViewComponent` on Linux (each mode in turn) with the default handler,
**When** the page calls `alert("x")`,
**Then** no GTK-styled native dialog appears anywhere on screen — only the Swing `JOptionPane`. No `gdk_window_move_to_rect` warning is logged to stderr that originates from this story's dialog handlers.

#### AC14: Heavyweight and lightweight share identical behaviour
**Given** the same page running in `WebViewHeavyweightComponent` and `WebViewLightweightComponent` on Linux, each with the same custom handler that returns the same answers,
**When** the page runs an identical sequence `alert("a"); const c = confirm("b"); const p = prompt("c", "d");`,
**Then** both modes invoke the handler in the same order with identical event field values (message strings, default values, pageUrl, frameUrl), and produce the same JS-visible return values.

#### AC15: WebViewFilePickerEvent surfaces accept hints on Linux
**Given** a `WebViewComponent` on Linux (each mode in turn) with a custom handler recording `event.acceptedExtensions()` and `event.acceptedMimeTypes()`, with the page containing `<input type="file" accept="image/*,.pdf">`,
**When** the user clicks the input,
**Then** the handler records `acceptedExtensions()` containing `"pdf"` and `acceptedMimeTypes()` containing `"image/*"`.

#### AC16: Handler returning empty list cancels the file picker without selection
**Given** a `WebViewComponent` on Linux (each mode in turn) with a custom handler whose `filePickerOpened` returns `Collections.emptyList()`, with the page containing `<input type="file" id="f">`,
**When** the user clicks the input,
**Then** the `change` event fires with `document.getElementById('f').files.length` of `0`, the page does not hang, and no native GTK file dialog appears.

### Non-Functional Expectations

- The bridge must not block the GTK main-loop thread for longer than the dialog stays open. While the Swing dialog is up, the WebKit JS thread is paused (correct per the JS contract), but the GTK event loop must remain free to repaint other GTK widgets in the offscreen pipeline (i.e. don't park the main loop with a synchronous wait that includes paint scheduling).
- The synchronous wait must be deadlock-free given the heavyweight model where the GTK pump runs on its own thread separate from AWT's X11 thread — invoking the EDT from the GTK pump thread and waiting must not require the GTK pump to service anything the EDT depends on.
- Identical Java field values must be produced for the same page-side input across heavyweight and lightweight modes — there must not be silent divergence (e.g. mime-types reordered, extensions case-different) between the two engines.

---

## [STORY-004-003] Windows WebView2 Coverage for WebViewDialogHandler

### Background

STORY-004-001 establishes the `WebViewDialogHandler` Java contract on macOS. STORY-004-002 extends to Linux. This story extends coverage to Windows, where `WebViewHeavyweightComponent` uses Microsoft WebView2 (an embedded Chromium / Edge).

WebView2's default behaviour today is that `alert / confirm / prompt` show built-in WebView2 dialogs (since `AreDefaultScriptDialogsEnabled` defaults to `TRUE` and the codebase does not change it), and `<input type="file">` opens the standard Windows file picker via WebView2's internal hosting. Both work out of the box.

The motivation for this story is **uniform behaviour with macOS and Linux**: a caller who writes `setDialogHandler(custom)` expecting their custom handler to fire would, today, find that on Windows the default WebView2 dialog appears instead and their handler is never invoked. To fulfil the cross-platform contract from STORY-004-001, Windows must intercept the script dialogs.

WebView2 exposes `ICoreWebView2::add_ScriptDialogOpening(handler, &token)` which fires for alert / confirm / prompt / before-unload (`WEBVIEW2_SCRIPT_DIALOG_KIND_*` in `windows/script/WebView2.h`). Returning a value asynchronously is supported via `ICoreWebView2ScriptDialogOpeningEventArgs::GetDeferral`. Combined with `ICoreWebView2Settings::put_AreDefaultScriptDialogsEnabled(FALSE)` this fully diverts JS dialogs to the application.

WebView2 exposes **no public hook for `<input type="file">`**. The file picker is internal to WebView2 and cannot be intercepted from the host. This story therefore leaves the file picker path alone on Windows — `<input type="file">` continues to open the standard Windows file dialog. The Java `WebViewFilePickerEvent` simply does not fire on Windows in this story. This is documented as a known platform limitation.

Key points:
- Business value: makes `setDialogHandler(...)` behave the same on Windows as on macOS and Linux for the three JS dialog kinds. Tests that register a programmatic handler to answer `confirm` will work on Windows just as they do on macOS.
- Relationship with other features: the WebView2 settings object is already obtained at engine creation (`windows/webview_embed.cc` lines around 422 and 876 — `ICoreWebView2Settings *settings = nullptr;`). This story adds a `put_AreDefaultScriptDialogsEnabled(FALSE)` call there and registers a `ScriptDialogOpening` event handler that bridges to the same Java dispatcher used by STORY-004-002.
- Why now: closes the last gap in the cross-platform `WebViewDialogHandler` contract.

### Business Value

- Provide **handler-override consistency on Windows** — `setDialogHandler(custom)` actually intercepts `alert / confirm / prompt` instead of being silently overridden by WebView2's built-in dialogs.
- Provide a **uniform Swing-styled look-and-feel** for `alert / confirm / prompt` across all three platforms when the default handler is in use, eliminating the today's discrepancy where Windows shows Chromium-styled dialogs.
- Make **programmatic handlers** (used in tests, in CI, and in headless-ish deployments) work correctly on Windows.

### Dependencies and Assumptions

- **Prerequisites**: STORY-004-001 (Java API contract + macOS) must be complete. STORY-004-002 (Linux) is not a strict prerequisite — its native code is independent of Windows's — but the internal `DialogDispatcher` Java class introduced by STORY-004-002 is reused here, so STORY-004-002 landing first reduces duplicated internal scaffolding.
- **Data assumptions**: No persisted state.
- **Integration points**: `ICoreWebView2`, `ICoreWebView2Settings`, `ICoreWebView2ScriptDialogOpeningEventArgs`, `ICoreWebView2ScriptDialogOpeningEventHandler`, `ICoreWebView2Deferral` from the WebView2 SDK headers already vendored under `windows/script/`.
- **Business constraints**:
  - `<input type="file">` is **not interceptable** in WebView2. This story does not attempt to bridge it; on Windows, `WebViewFilePickerEvent` does not fire and the OS-native file picker continues to appear as today. This is documented in `README.md` as a Windows-specific limitation. Callers who need custom file-picker behaviour can still use `<input type="file" webkitdirectory />` and similar HTML primitives — same constraint applies. **AC4 verifies the unchanged default behaviour; no AC requires a custom file handler to fire on Windows.**
  - `before-unload` dialogs reach the same `ScriptDialogOpening` event (kind = `WEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD` in some SDK versions, or simply confirm in others). They route to `confirmOpened` for parity with STORY-004-002.
  - The `ScriptDialogOpening` callback runs on the WebView2 worker thread (per the existing `README.md` description: "Each embedded WebView runs on its own worker thread that pumps a private message queue"). The bridge must marshal to the EDT and call back across worker→EDT boundaries without blocking the worker queue beyond the dialog's modal lifetime.
  - Calling `put_AreDefaultScriptDialogsEnabled(FALSE)` happens once, at engine creation, immediately after the `ICoreWebView2Settings` is obtained.

### Scope In

- Native: in the Windows engine creation path (`windows/webview_embed.cc`, around the existing `ICoreWebView2Settings *settings = nullptr;` site), call `settings->put_AreDefaultScriptDialogsEnabled(FALSE)` after acquiring the settings interface.
- Native: register a `ICoreWebView2ScriptDialogOpeningEventHandler` via `ICoreWebView2::add_ScriptDialogOpening`. The handler:
  1. Reads `Uri`, `Kind`, `Message`, `DefaultText` from the event args.
  2. Calls `GetDeferral` and stashes the deferral pointer.
  3. Routes to the existing `DialogDispatcher` (introduced by STORY-004-002 or by this story if 004-002 has not landed) which marshals to the EDT, invokes `WebViewDialogHandler.alertOpened` / `confirmOpened` / `promptOpened`, and returns the answer.
  4. On answer: calls `put_ResultText` (for prompt) or `Accept` (for confirm); for `alert` calls `Accept`. For "cancel" outcomes (confirm returns `false`, prompt returns `null`) it does **not** call `Accept` — leaving the deferral with no `Accept` causes WebView2 to cancel the dialog, which matches the JS contract (`confirm` returns `false`, `prompt` returns `null`). Then calls `Complete` on the deferral.
- Native: `before-unload` dialogs route to `confirmOpened`, same as Linux (STORY-004-002).
- Java: no public API changes. The internal `DialogDispatcher` from STORY-004-002 is reused. If STORY-004-002 has not yet landed, this story introduces `DialogDispatcher` in the same shape.
- README documentation: add a note in the platform-support section explaining that on Windows, `WebViewFilePickerEvent` does not fire (WebView2 limitation) and `<input type="file">` uses the OS-native file picker as before.

### Scope Out

- macOS — STORY-004-001.
- Linux — STORY-004-002.
- Bridging `<input type="file">` on Windows — WebView2 does not expose a hook for this. Out of scope for this story, and the limitation is documented in README. A future workaround would require injecting a JS shim that intercepts the file-input click and routes through a custom binding; that is a different design and a separate story.
- `NewWindowRequested`, `PermissionRequested`, `BasicAuthenticationRequested`, `WebResourceRequested` — different event channels, all out of scope.
- Migrating to a newer WebView2 SDK version. The vendored SDK headers under `windows/script/` are sufficient.

### Acceptance Criteria

#### AC1: Default alert handler shows a Swing message dialog on Windows
**Given** a `WebViewHeavyweightComponent` running on Windows 11 with the WebView2 Runtime installed, attached to a visible `JFrame`, with no custom `WebViewDialogHandler`,
**When** the loaded page calls `alert("hello world")`,
**Then** a Swing `JOptionPane` message dialog appears modal to the host `JFrame` showing `"hello world"`, and the WebView2 built-in alert dialog does not appear.

#### AC2: Default confirm round-trips OK and Cancel
**Given** a `WebViewHeavyweightComponent` on Windows with no custom handler, loaded with `const r = confirm("?"); document.title = String(r);`,
**When** the user clicks OK on the resulting Swing dialog and then reloads and clicks Cancel,
**Then** `document.title` is `"true"` in the first case and `"false"` in the second.

#### AC3: Default prompt round-trips entered text and cancel
**Given** a `WebViewHeavyweightComponent` on Windows with no custom handler, loaded with `const r = prompt("name?", "default"); document.title = String(r);`,
**When** the user types `"carol"` and clicks OK in one run, and clicks Cancel in another,
**Then** `document.title` is `"carol"` in the first case and `"null"` in the second; in both, the WebView2 built-in prompt does not appear.

#### AC4: <input type=file> continues to open the OS-native picker on Windows
**Given** a `WebViewHeavyweightComponent` on Windows with `<input type="file">` and no custom handler,
**When** the user clicks the file input,
**Then** the standard Windows file dialog (Common Item Dialog) appears as today, the user can select a file, and the page receives the selection. **`WebViewFilePickerEvent` does not fire on Windows in this story.**

#### AC5: Custom alertOpened replaces the default on Windows
**Given** a `WebViewHeavyweightComponent` on Windows with `setDialogHandler(new WebViewDialogHandler(){ @Override public void alertOpened(WebViewAlertEvent e){ recorded = e.message(); } })`,
**When** the loaded page calls `alert("ping")`,
**Then** no Swing dialog and no WebView2 dialog appear, and `recorded` equals `"ping"`.

#### AC6: Custom confirmOpened returns a programmatic answer
**Given** a `WebViewHeavyweightComponent` on Windows with a handler whose `confirmOpened` returns `true` unconditionally,
**When** the page calls `const r = confirm("?"); document.title = String(r);`,
**Then** no dialog appears and `document.title` becomes `"true"`.

#### AC7: Custom promptOpened returns a programmatic answer
**Given** a `WebViewHeavyweightComponent` on Windows with a handler whose `promptOpened` returns `"hardcoded"`,
**When** the page calls `const r = prompt("?", "x"); document.title = String(r);`,
**Then** no dialog appears and `document.title` becomes `"hardcoded"`.

#### AC8: setDialogHandler(null) suppresses dialogs without freezing the page
**Given** a `WebViewHeavyweightComponent` on Windows with `setDialogHandler(null)`,
**When** the page calls `alert("x")` then `confirm("y")` then `prompt("z", "w")`,
**Then** no dialog appears, the page's JS thread continues without hanging, and the three calls return synchronously with the documented null-handler semantics (void / false / null).

#### AC9: before-unload routes to confirmOpened on Windows
**Given** a `WebViewHeavyweightComponent` on Windows with a handler whose `confirmOpened` returns `true` and records the event,
**When** a page running `window.onbeforeunload = () => "really?"` is navigated away from,
**Then** `confirmOpened` is invoked exactly once, navigation proceeds, and the WebView2 built-in before-unload dialog does not appear.

#### AC10: Handler callback fires on the EDT
**Given** a `WebViewHeavyweightComponent` on Windows with a handler recording `SwingUtilities.isEventDispatchThread()` inside `alertOpened`, `confirmOpened`, and `promptOpened`,
**When** the page in turn triggers each of the three dialog kinds,
**Then** every recorded value is `true`.

#### AC11: pageUrl and frameUrl are populated on Windows
**Given** a `WebViewHeavyweightComponent` on Windows loaded with `https://example.com/index.html` and a handler recording `event.pageUrl()` and `event.frameUrl()`,
**When** the page calls `alert("x")`,
**Then** `pageUrl()` is `"https://example.com/index.html"` and `frameUrl()` is `"https://example.com/index.html"`.

#### AC12: Built-in WebView2 dialogs do not appear with default handler
**Given** a `WebViewHeavyweightComponent` on Windows with the default `WebViewDialogHandler` (no custom handler set),
**When** the page calls `alert("first")` then `confirm("second")` then `prompt("third", "x")`,
**Then** at no point does the WebView2 built-in dialog flash on screen before the Swing dialog appears (i.e. `AreDefaultScriptDialogsEnabled` is `FALSE` from engine creation, not toggled mid-flight).

#### AC13: Handler exception does not crash WebView2
**Given** a `WebViewHeavyweightComponent` on Windows with a handler whose `alertOpened` throws a `RuntimeException`,
**When** the page calls `alert("x")`,
**Then** the exception is surfaced via EDT uncaught-exception handling, the JS-side `alert(...)` returns within a bounded time (the deferral is completed even on exception), the WebView2 stays responsive, and the host app does not crash.

### Non-Functional Expectations

- The `ICoreWebView2Deferral` must always be completed, even when the Java handler throws. Failure to complete the deferral leaves the WebView2 JS engine hung.
- The marshal from the WebView2 worker thread to the EDT and back must not require WebView2's worker queue to service messages from the EDT (the worker thread is paused waiting for the answer); there must be no inverted-dependency between the WebView2 message pump and the EDT.
- The Windows-only limitation that `WebViewFilePickerEvent` does not fire must be documented in `README.md` (Platform support table or a dedicated note), so callers don't write code that silently no-ops on Windows.

---

## Quality Checks

**STORY-004-001 (Java API contract + macOS coverage)**:
- ✅ All required sections present (Background, Business Value, Dependencies and Assumptions, Scope In, Scope Out, Acceptance Criteria, Non-Functional Expectations).
- ✅ ACs use Given-When-Then with concrete inputs (`alert("hello world")`, `prompt("name?", "default")`, `<input type="file" accept=".png,.jpg" multiple>`) and observable outcomes (`document.title` equals specific string, `files.length` equals specific integer).
- ✅ Business-language ACs — public API names (`setDialogHandler`, `WebViewDialogHandler`, `confirmOpened`, `WebViewFilePickerEvent`) appear because they are the user-visible contract. No JNI signatures, no ObjC selector names inside AC bodies (selectors mentioned only in Dependencies / Scope-In context).
- ✅ Covers happy path (default Swing dialogs for all four kinds), validation / business rules (cancel returns null/false, accept-filter applied, multiple flag honoured), error conditions (handler exception, setDialogHandler(null) suppression), and EDT / pre-display / iframe-URL invariants.
- ✅ At most three core functional points (Java API, macOS native delegate, default Swing dialog implementation).
- ✅ 3-5 days of work (ObjC UIDelegate class, JNI bridge for synchronous return, four event POJOs, default Swing dialog impl, EDT marshaling).

**STORY-004-002 (Linux coverage)**:
- ✅ All required sections present.
- ✅ ACs run "in each mode in turn" where the behaviour is identical, explicitly checking both `WebViewHeavyweightComponent` and `WebViewLightweightComponent`.
- ✅ Business-language ACs, no GTK signal names inside AC bodies (signal names appear only in Background / Dependencies / Scope-In).
- ✅ Covers happy path (alert / confirm / prompt / file picker, both modes), validation (accept-filter, multiple, cancel-returns-empty), error/edge (`setDialogHandler(null)` semantics), and parity (AC14: heavyweight vs lightweight produce identical Java field values).
- ✅ At most two core functional points (GTK signal handlers for script-dialog and run-file-chooser).
- ✅ 2-4 days of work (two GTK signal connections, JNI bridging reuses STORY-004-001 pattern, no new Java API).

**STORY-004-003 (Windows coverage)**:
- ✅ All required sections present.
- ✅ ACs use concrete inputs / outputs; AC4 explicitly carves out the WebView2 file-picker limitation so the reader is not surprised.
- ✅ Business-language ACs; COM interface names appear only in Dependencies / Scope-In, never inside AC bodies.
- ✅ Covers happy path (alert / confirm / prompt default), edge (before-unload routes to confirm, AC9), business rules (no built-in WebView2 dialog flash, AC12), and error conditions (handler exception completes the deferral, AC13).
- ✅ Two core functional points (settings flag + `add_ScriptDialogOpening` handler).
- ✅ 2-3 days of work.

## Final INVEST Re-validation

| Property | STORY-004-001 | STORY-004-002 | STORY-004-003 |
|---|---|---|---|
| Independent | ✅ (no story-level dependency; designs the API) | ✅ (depends only on the API from 004-001, native code is wholly independent) | ✅ (depends only on the API from 004-001, native code is wholly independent of Linux's) |
| Complete | ✅ (full Java contract + working macOS) | ✅ (full Linux coverage, both modes) | ✅ (full Windows coverage for the three interceptable kinds + documented limitation) |
| Valuable | ✅ (fixes the silently-broken macOS platform; ships the contract) | ✅ (fixes broken Linux lightweight; fixes orphan-dialog glitch on heavyweight) | ✅ (makes setDialogHandler actually work on Windows) |
| Estimable | ✅ (one ObjC delegate + JNI sync-bridge + four POJOs + Swing default impl) | ✅ (two GTK signal handlers reusing existing JNI pattern) | ✅ (one settings put + one event-handler registration + deferral plumbing) |
| Right-sized | ✅ (3-5 days) | ✅ (2-4 days) | ✅ (2-3 days) |
| Testable | ✅ (default dialogs and handler override both observable from Swing harness; programmatic handler runs in headless unit test) | ✅ (parity AC14 + both-mode coverage AC1-AC11) | ✅ (built-in dialog suppression observable via AC12; file-picker carve-out tested via AC4) |

All three stories pass INVEST.
