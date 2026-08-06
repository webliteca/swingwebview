---
generated_at: 2026-08-02T16:15:00-07:00
---

# REASONS Canvas: Browser-Initiated Popups (`window.open`) — Windows WebView2 Coverage

## R · Requirements

- Wire the existing `WebViewPopupHandler` contract — designed and
  shipped for the Java layer + macOS (WKWebView) in
  [[browser-initiated-popups-window-open]] (Canvas 15) and extended to
  Linux (WebKitGTK) in [[browser-initiated-popups-linux-coverage]]
  (Canvas 16) — to the Windows WebView2 engine so `window.open(url,
  name, features)` and clicks on `<a target="_blank">` /
  `<form target="_blank">` open a native top-level popup window
  **linked to the opener** (`window.opener` non-null;
  `window.opener.postMessage(...)` works), and `window.close()` from the
  popup destroys that window. This closes the Windows half of the
  Canvas 15 "Native coverage status" note (Operation 11), exactly as
  Canvas 13 added Windows coverage for the dialog feature after
  Canvas 11 / 12.
- The Windows engine is `windows/webview_embed.cc`, which hosts an
  `ICoreWebView2` controller created lazily in the controller-ready
  callback inside `engine_thread` (the per-engine WebView2 worker
  thread with its own message pump). The new wiring lives in that
  callback plus a new `NewWindowRequestedHandler` class defined
  alongside the existing `FocusHandler` / `MsgHandler` /
  `ScriptDialogHandler`.
- The `Engine` struct already carries a `jobject popup_callback`
  field and its `set_popup_callback` JNI bridge (both shipped in
  Canvas 15). Add:
  - `EventRegistrationToken new_window_token{}` — for the
    `add_NewWindowRequested` registration.
  - `ICoreWebView2Environment *environment = nullptr` — AddRef'd at
    environment-ready, so the `NewWindowRequested` handler can create
    the child controller from the **same** environment (this is what
    makes the popup a linked view — `put_NewWindow` with a controller
    from a different environment does NOT link the opener).
- Register a `NewWindowRequestedHandler` (implementing
  `ICoreWebView2NewWindowRequestedEventHandler`) via
  `webview->add_NewWindowRequested(handler, &e->new_window_token)` in
  the controller-ready callback, right after the existing
  `add_ScriptDialogOpening` / `add_GotFocus` / `add_LostFocus` sites.
  Follow the `CallbackBase<Iface>` template used by the other handlers.
- The handler's `Invoke` runs on the WebView2 worker thread. It MUST
  use the **deferral** pattern (the worker thread must keep pumping
  while Java decides, so `Invoke` cannot block synchronously):
  1. Read `args->get_Uri`, `args->get_IsUserInitiated`, and
     `args->get_WindowFeatures` → `get_HasSize` / `get_Width` /
     `get_Height`.
  2. `args->GetDeferral(&deferral)`; on failure log and `return S_OK`
     (WebView2 opens its own default window — the pre-feature fallback).
  3. `args->AddRef()`, `deferral` held; spawn a short-lived
     `std::thread` for the allow/deny JNI hop
     (`onPopupRequested` via `popup_callback` — off the EDT,
     synchronous relative to the deferred `window.open`).
  4. `return S_OK` immediately.
  5. On the worker JNI thread, once Java answers, marshal back onto the
     WebView2 worker via `dispatch_to_thread(e, …)` (COM objects are
     apartment-bound to the worker):
     - **Deny**: `args->put_Handled(TRUE)` with no `NewWindow` (blocks;
       `window.open` returns `null`), `deferral->Complete()`, release
       `deferral` + `args`.
     - **Allow**: create a native top-level popup `HWND`, then
       `environment->CreateCoreWebView2Controller(hwnd,
       ControllerHandler)` (same environment → linked). In that
       completion: `get_CoreWebView2(&child)`,
       `args->put_NewWindow(child)`, `args->put_Handled(TRUE)`,
       `deferral->Complete()`, size + `ShowWindow` the popup, connect
       the child's `add_WindowCloseRequested` → `DestroyWindow` +
       `onPopupClosed`, fire `onPopupOpened`, release `deferral` +
       `args`.
- The child popup web view MUST be created via `put_NewWindow` with a
  controller from the SAME `ICoreWebView2Environment` — never a fresh
  environment / independent window loading the same URL (breaks OAuth
  `signInWithPopup`). The engine owns the popup `HWND`, sizes it
  (window features, fallback 500×650), shows it, and destroys it.
- The child popup web view gets its own `add_NewWindowRequested`
  (nested popups) and `add_ScriptDialogOpening` +
  `put_AreDefaultScriptDialogsEnabled(FALSE)` (dialogs inside the
  popup), both bound to the **opener** `Engine` so they reuse the
  opener's `popup_callback` / `dialog_callback` — mirroring the
  inherited-callbacks model on macOS / Linux.
- No Java-side change is required — the contract, dispatcher, adapter
  wiring, event POJO, and `EmbeddedWebView.setPopupCallback` all shipped
  in Canvas 15, and the `webview_embed_set_popup_callback` JNI bridge
  already stores the ref on Windows.

### Threading

- `Invoke` runs on the WebView2 worker thread and must not block it
  (the completion-side `dispatch_to_thread` would self-deadlock, and the
  worker also services rendering / JS / dispatch messages). The deferral
  + worker-thread-JNI-hop + `dispatch_to_thread`-back pattern is the
  same one `ScriptDialogHandler` uses and is the SDK-supported approach.
- `onPopupRequested` runs on the short-lived JNI worker thread (off the
  EDT), synchronous relative to the deferred `window.open`.
  `onPopupOpened` / `onPopupClosed` are fired via `CallVoidMethod`; the
  Java `PopupDispatcher` marshals them to the EDT via `invokeLater`.

### Definition of Done

- `window.open(url, ...)` from a page in a Windows `WebViewComponent`
  opens a native top-level window loading `url`, linked to the opener
  (`window.opener` non-null; `postMessage` to the opener works);
  `window.close()` from the popup closes it.
- `setPopupHandler(null)` blocks all popups on Windows (`window.open`
  returns `null`); the DEFAULT handler allows them.
- `onPopupOpened` / `onPopupClosed` reach the Java handler on the EDT;
  `popupClosed` receives the correlated event stored at open.
- No Java-side change; `PopupDispatcherTest` still passes unchanged.
  Native window creation is validated on-device by running
  `WebViewPopupDemo` on Windows (no-automated-GUI-tests policy).
- Canvas 15's "Native coverage status" note and the README popups
  caveat are updated in lockstep to mark Windows ✅ (completing all
  three platforms).

### Out of scope (unchanged from Canvas 15)

- Hosting the popup in a Swing surface / as a tab; per-popup window
  chrome; async user interaction inside `popupRequested`; feature-string
  keys beyond `width`/`height`. The standalone in-process `WebView`
  class is untouched. Windows has no offscreen engine, so the
  `webview_offscreen_set_popup_callback` bridge stays a stub.

## E · Entities

- **`windows/webview_embed.cc`** (modified). Adds:
  - `Engine::new_window_token` (`EventRegistrationToken`) and
    `Engine::environment` (`ICoreWebView2Environment*`, AddRef'd at
    env-ready, Release'd in `destroy_engine`).
  - `struct PopupWindow` — the child popup's native state: `HWND hwnd`,
    `ICoreWebView2Controller *controller`, `ICoreWebView2 *webview`,
    `Engine *opener` (for `jvm` + `popup_callback`), `jlong popup_id`,
    `std::string url`, `EventRegistrationToken close_token`.
  - `PopupWndProc` + `ensure_popup_class_registered` (window class
    `"WebViewEmbedPopup"`): `WM_SIZE` → `controller->put_Bounds`;
    `WM_DESTROY` → release controller/webview + `delete` the
    `PopupWindow`.
  - JNI helpers `fire_popup_requested_win(Engine*, url, gesture, w, h,
    page) -> bool`, `fire_popup_opened_win(Engine*, popup_id, url,
    gesture, w, h, page)`, `fire_popup_closed_win(Engine*, popup_id,
    url, page)` — the same method IDs / signatures as macOS / Linux,
    exception-sanitized.
  - `NewWindowRequestedHandler : CallbackBase<
    ICoreWebView2NewWindowRequestedEventHandler>` (Approach §), and
    `PopupCloseHandler : CallbackBase<
    ICoreWebView2WindowCloseRequestedEventHandler>` for the child's
    `WindowCloseRequested`.
  - Registration of `add_NewWindowRequested` in the controller-ready
    callback; `e->environment` AddRef at env-ready; `popup_callback`
    global-ref clear + `environment` Release in `destroy_engine`.

## A · Approach

1. **Struct fields + env capture + teardown first.** Add
   `new_window_token` and `environment` to `Engine`. In the
   environment-ready lambda, `e->environment = env; env->AddRef();`
   before creating the controller. In `destroy_engine`, clear
   `popup_callback` (attach / `DeleteGlobalRef` / detach, same block as
   `dialog_callback`) and `if (e->environment) { e->environment->Release();
   e->environment = nullptr; }`.

2. **Popup window class.** `ensure_popup_class_registered` registers
   `"WebViewEmbedPopup"` with `PopupWndProc`. `PopupWndProc` stores the
   `PopupWindow*` via `GWLP_USERDATA`; `WM_SIZE` resizes the controller;
   `WM_DESTROY` releases the controller + webview and deletes the
   `PopupWindow`.

3. **NewWindowRequestedHandler::Invoke** (worker thread) — deferral
   pattern per Requirements. Copy `uri` (UTF-8), read
   `IsUserInitiated` and window features (`HasSize` → `Width`/`Height`,
   else −1). `GetDeferral`; on failure `return S_OK`. `args->AddRef()`.
   Spawn `std::thread`:
   - `bool allow = fire_popup_requested_win(e, uri, gesture, w, h,
     page)` (page = opener document URL via `webview->get_Source`, best
     effort empty).
   - `dispatch_to_thread(e, [...] { ... })` onto the worker:
     - Deny → `args->put_Handled(TRUE); deferral->Complete();
       deferral->Release(); args->Release();`.
     - Allow → `HWND popup = CreateWindowEx(WS_OVERLAPPEDWINDOW …)`
       (size from features / 500×650), build a `PopupWindow` (`opener =
       e`, `popup_id = (jlong)(intptr_t)pw`), `SetWindowLongPtr(popup,
       GWLP_USERDATA, pw)`. `e->environment->CreateCoreWebView2Controller(
       popup, new ControllerHandler([...] {`
       - `ctrl->get_CoreWebView2(&child)`; store on `pw`.
       - `args->put_NewWindow(child); args->put_Handled(TRUE);
         deferral->Complete();`.
       - Size the controller bounds to the client rect, `put_IsVisible(
         TRUE)`, `ShowWindow(popup, SW_SHOW)`.
       - On the child: `add_NewWindowRequested(new
         NewWindowRequestedHandler(e), …)` (nested), settings
         `put_AreDefaultScriptDialogsEnabled(FALSE)` +
         `add_ScriptDialogOpening(new ScriptDialogHandler(e), …)`
         (dialogs), and `add_WindowCloseRequested(new
         PopupCloseHandler(pw), &pw->close_token)`.
       - `fire_popup_opened_win(e, pw->popup_id, uri, gesture, W, H,
         page)`.
       - `deferral->Release(); args->Release();`.
       `}))`. On `CreateCoreWebView2Controller` failure: destroy the
       window, `args->put_Handled(TRUE)` (blocked), `Complete` +
       release.

4. **PopupCloseHandler::Invoke** (`WindowCloseRequested`, worker
   thread) — `fire_popup_closed_win(pw->opener, pw->popup_id, pw->url,
   "")`, then `DestroyWindow(pw->hwnd)` (which triggers `WM_DESTROY` →
   `PopupWndProc` releases the controller/webview and deletes `pw`).

5. **Registration site.** In the controller-ready callback, after
   `add_LostFocus`, add:
   `auto *nwh = new NewWindowRequestedHandler(e);
   e->webview->add_NewWindowRequested(nwh, &e->new_window_token);
   nwh->Release();`.

## S · Structure

### Dependencies
- Controller-ready → `add_NewWindowRequested(NewWindowRequestedHandler)`.
- `NewWindowRequestedHandler::Invoke` → `GetDeferral` + JNI worker
  (`fire_popup_requested_win` → `onPopupRequested`) + `dispatch_to_thread`
  → `environment->CreateCoreWebView2Controller` → `put_NewWindow` +
  child event registrations + `fire_popup_opened_win`.
- `PopupCloseHandler::Invoke` → `fire_popup_closed_win` +
  `DestroyWindow` → `PopupWndProc` `WM_DESTROY` → controller/webview
  release + `delete PopupWindow`.
- `set_popup_callback` JNI bridge (already present) →
  `Engine::popup_callback`.

### Layered Architecture
- Native engine layer only (`windows/webview_embed.cc`). No Java,
  JNI-surface, wrapper, dispatcher, component, contract, or demo change
  — all shipped in Canvas 15.

## O · Operations

### 1. `Engine::new_window_token` + `Engine::environment`; env capture + teardown
Add the two fields. `env->AddRef()` at env-ready. In `destroy_engine`,
clear `popup_callback` (same attach/DeleteGlobalRef block as
`dialog_callback`) and `environment->Release()`.

### 2. `PopupWindow` + `PopupWndProc` + `ensure_popup_class_registered`
Per Approach §2.

### 3. JNI helpers `fire_popup_requested_win` / `fire_popup_opened_win` / `fire_popup_closed_win`
Method signatures identical to macOS / Linux
(`onPopupRequested (…)Z`, `onPopupOpened (J…)V`, `onPopupClosed
(JLjava/lang/String;Ljava/lang/String;)V`). `fire_popup_requested_win`
returns false on null callback / attach failure / exception.

### 4. `NewWindowRequestedHandler` + `PopupCloseHandler`
Per Approach §3–4, using the `CallbackBase<Iface>` template.

### 5. Register `add_NewWindowRequested` in the controller-ready callback
Per Approach §5.

## N · Norms

- **Mirror the dialog Windows coverage (Canvas 13)**: deferral pattern,
  `CallbackBase<Iface>` handlers, JNI hop on a worker thread,
  `dispatch_to_thread` back to the WebView2 worker for COM calls.
- **Opener linkage is mandatory** — `put_NewWindow` with a controller
  created from the SAME `ICoreWebView2Environment`.
- **`put_Handled(TRUE)` on every decided path** (allow and deny) so the
  default WebView2 window is suppressed; only the un-deferrable failure
  path (`GetDeferral` failed) falls through to the default window.
- **AddRef `args` + hold `deferral` across the async hop; Release both
  in the completion** — exactly as `ScriptDialogHandler`.
- **Per-call `jmethodID` resolution** + **JNI exception sanitization**
  after every `CallBooleanMethod` / `CallVoidMethod`.
- **No JS shim, no `__webview_*` binding, no JSON parser.**
- **No Java-side change** — the Canvas 15 contract is frozen.

## S · Safeguards

- **Worker thread never blocks** — the deferral pattern keeps the pump
  alive; blocking `Invoke` is rejected (self-deadlock with
  `dispatch_to_thread`), matching the dialog canvas.
- **Popup window teardown ordering** — `WindowCloseRequested` fires
  `onPopupClosed` first, then `DestroyWindow`; `WM_DESTROY` in
  `PopupWndProc` releases the controller + webview and deletes the
  `PopupWindow`. A destroy without a prior show still releases cleanly.
- **`popupId` correlation is best-effort** — the Java
  `dispatchPopupClosed` tolerates a missing map entry.
- **Disabled-until-set** — with no `popup_callback`,
  `fire_popup_requested_win` returns false → deny path → `window.open`
  returns `null`, identical to today.
- **Environment lifetime** — `environment` is AddRef'd at env-ready and
  Release'd in `destroy_engine`; a popup created just before disposal
  holds its own controller ref, so worker-thread teardown does not
  free a live child out from under it.
- **No `setDebug` coupling** — `add_NewWindowRequested` is registered
  unconditionally, so popups work in release builds.
- **On-device validation required** — the sandbox has no MSVC /
  WebView2 SDK; this code is pattern-faithful to the shipped
  `ScriptDialogHandler` / `FocusHandler` and the macOS / Linux popup
  paths but MUST be built and exercised via `WebViewPopupDemo` on
  Windows before release. The maintainer who lands this MUST flip
  Canvas 15's coverage note and the README caveat to mark Windows done
  (all three platforms complete) in the same commit.
