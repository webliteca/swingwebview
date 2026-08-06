---
generated_at: 2026-08-02T16:00:00-07:00
---

# REASONS Canvas: Browser-Initiated Popups (`window.open`) — Linux WebKitGTK Coverage (Heavyweight + Lightweight)

## R · Requirements

- Wire the existing `WebViewPopupHandler` contract — designed and
  shipped for the Java layer plus macOS (WKWebView) in
  [[browser-initiated-popups-window-open]] (Canvas 15) — to the Linux
  WebKitGTK engines so `window.open(url, name, features)` and clicks on
  `<a target="_blank">` / `<form target="_blank">` open a native
  top-level popup window **linked to the opener** (so `window.opener`
  is non-null and `window.opener.postMessage(...)` works), and
  `window.close()` from the popup destroys that window. This closes
  the Linux half of the "Native coverage status" note Canvas 15 left
  open (Operation 10), exactly as Canvas 12 added Linux coverage for
  the dialog feature after Canvas 11 shipped the Java layer + macOS.
- Both Linux engines are in scope:
  - **Heavyweight** (`WebViewHeavyweightComponent` → `EmbeddedWebView`
    → `gtk_create_engine` in `src_c/webview_embed.cpp`).
  - **Lightweight** (`WebViewLightweightComponent` → `OffscreenWebView`
    → `gtk_off_create_engine`).
- Connect one new signal, `create`, on every `WebKitWebView` created by
  either engine, at the same sites the dialog signals (`script-dialog`,
  `run-file-chooser`) are connected (`gtk_create_engine` ~line 1021,
  `gtk_off_create_engine` ~line 1567):
  - `create` (`WebKitNavigationAction *`) — fires for `window.open` and
    `target="_blank"`. Returning a new `WebKitWebView*` tells WebKitGTK
    to drive that (linked) view for the popup; returning `NULL` blocks
    the popup (`window.open` returns `null`), which is the pre-feature
    behaviour and the behaviour when `popup_callback` is unset.
- The child (popup) `WebKitWebView` MUST be created with
  `webkit_web_view_new_with_related_view(opener)` so it shares the
  opener's network/session context and keeps the `window.opener`
  linkage — creating a bare `webkit_web_view_new()` loading the same
  URL is a defect (breaks OAuth `signInWithPopup`). It is hosted in an
  engine-owned `GTK_WINDOW_TOPLEVEL` the native layer creates, sizes,
  shows, and destroys — Java is never asked to host it in a Swing
  surface (same native-owned-window model Canvas 15 established for
  macOS).
- Two further signals are connected on the **child** popup web view:
  - `ready-to-show` — size the window from
    `webkit_web_view_get_window_properties` (fallback 500×650, a typical
    auth-popup size), `gtk_widget_show_all` + `gtk_window_present`, then
    fire `onPopupOpened` (async / EDT-marshalled on the Java side).
  - `close` — `gtk_widget_destroy` the window, fire `onPopupClosed`,
    tear the child popup engine down.
  The child also gets `create` / `script-dialog` / `run-file-chooser`
  connected so nested popups and dialogs raised from inside the popup
  work, inheriting the opener's `popup_callback` / `dialog_callback`.
- The `create` handler MUST be shared code (single inner C function)
  invoked from three thin per-engine wrappers keyed by the `user_data`
  pointer (`Engine*`, `OffEngine*`, and the new `PopupEngine*` for
  nested popups), exactly as `handle_script_dialog` /
  `handle_run_file_chooser` are shared across
  `on_script_dialog_engine` / `on_script_dialog_off_engine`.
- Each engine struct (`Engine`, `OffEngine`) already documents a
  `dialog_callback` field; add a parallel `jobject popup_callback`
  field to each, populated by the new `gtk_set_popup_callback` /
  `gtk_off_set_popup_callback` setters and cleared in
  `gtk_destroy_engine` / `gtk_off_destroy_engine`, mirroring the
  `dialog_callback` lifecycle exactly.
- The `WebViewNative.webview_embed_set_popup_callback` /
  `webview_offscreen_set_popup_callback` JNI bridges (already present
  from Canvas 15, currently a no-op on the non-Cocoa branch) gain a
  `WEBVIEW_GTK` branch dispatching to `gtk_set_popup_callback` /
  `gtk_off_set_popup_callback`. No Java-side change is required — the
  contract, dispatcher, adapter wiring, event POJO, and `EmbeddedWebView`
  / `OffscreenWebView` setters all shipped in Canvas 15.

### Threading

- The `create` signal handler runs on the **GTK main thread**. The
  allow/deny hop (`onPopupRequested`) is invoked **synchronously** on
  that thread with `CallBooleanMethod` — the handler must return the
  child web view (or `NULL`) before yielding back to WebKitGTK, exactly
  like the `script-dialog` confirm path calls `fire_dialog_confirm`
  synchronously. The `PopupDispatcher.dispatchPopupRequested` runs the
  application handler inline and returns its boolean; no EDT hop.
- `onPopupOpened` / `onPopupClosed` are fire-and-forget: the native
  side calls them with `CallVoidMethod` directly on the GTK main thread,
  and the Java `PopupDispatcher` marshals them to the EDT via
  `SwingUtilities.invokeLater` (non-blocking), so the GTK main thread is
  never blocked awaiting the EDT. This matches the Canvas 15 threading
  split; no detached worker thread is needed on GTK (unlike macOS, where
  AppKit main must not be blocked even briefly — GTK's `invokeLater`
  path already returns immediately).

### Definition of Done

- `window.open(url, ...)` from a page in a heavyweight OR lightweight
  Linux `WebViewComponent` opens a native GTK top-level window loading
  `url`, linked to the opener (`window.opener` non-null; `postMessage`
  to the opener works); `window.close()` from the popup closes it.
- `setPopupHandler(null)` blocks all popups on Linux (`window.open`
  returns `null`); the DEFAULT handler allows them.
- `onPopupOpened` / `onPopupClosed` reach the Java handler on the EDT;
  `popupClosed` receives the same correlated event stored at open (via
  the `openPopups` map keyed on `popupId`).
- No Java-side change beyond what Canvas 15 shipped; `PopupDispatcherTest`
  still passes unchanged. Native window creation is validated on-device
  by running `WebViewPopupDemo` on Linux (no-automated-GUI-tests policy).
- Canvas 15's "Native coverage status" note and the README popups
  caveat are updated in lockstep to mark Linux ✅.

### Out of scope (unchanged from Canvas 15)

- Hosting the popup in a Swing surface / as a tab; per-popup window
  chrome; async user interaction inside `popupRequested`; feature-string
  keys beyond `width`/`height`; Windows coverage (Canvas 17). The
  standalone in-process `WebView` class is untouched.

## E · Entities

- **`src_c/webview_embed.cpp`** (modified, GTK sections). Adds:
  - `jobject popup_callback = nullptr;` on `struct Engine` (~line 410,
    after `dialog_callback`) and `struct OffEngine` (~line 1455).
  - A new `struct PopupEngine` — the child popup's state: `GtkWidget
    *window` (the `GTK_WINDOW_TOPLEVEL`), `WebKitWebView *web`, `JavaVM
    *jvm`, `jobject popup_callback` (inherited global ref), `jobject
    dialog_callback` (inherited global ref, may be null), `jlong
    popup_id`.
  - `fire_popup_requested(JavaVM*, jobject cb, url, name, gesture, w, h,
    page)` → `bool` — synchronous `CallBooleanMethod` into
    `WebViewPopupCallback.onPopupRequested`, GTK-main-thread, exception
    sanitized, returns `false` on any error/attach-failure.
  - `fire_gtk_popup_opened(JavaVM*, jobject cb, popup_id, url, name,
    gesture, w, h, page)` and `fire_gtk_popup_closed(JavaVM*, jobject
    popup_cb, jobject dialog_cb, popup_id, url, page)` — `CallVoidMethod`
    into `onPopupOpened` / `onPopupClosed`. `fire_gtk_popup_closed` does
    NOT delete the inherited refs (the caller frees them after it
    returns — the call is synchronous on GTK, unlike the macOS detached
    worker which owns them).
  - Shared inner `handle_create_web_view(JavaVM*, jobject popup_cb,
    jobject dialog_cb, WebKitWebView *opener, WebKitNavigationAction*)`
    → `GtkWidget*`.
  - `on_ready_to_show_popup` / `on_close_popup` (keyed on `PopupEngine*`).
  - Per-engine `create` wrappers: `on_create_web_view_engine`
    (`Engine*`), `on_create_web_view_off_engine` (`OffEngine*`),
    `on_create_web_view_popup` (`PopupEngine*`); plus
    `on_script_dialog_popup` / `on_run_file_chooser_popup` reusing the
    shared dialog inner handlers with `PopupEngine::dialog_callback`.
  - `gtk_set_popup_callback` / `gtk_off_set_popup_callback` mirroring the
    dialog setters; `popup_callback` clears in the two destroy funcs.
  - Signal connects for `create` in `gtk_create_engine` and
    `gtk_off_create_engine`.
  - `WEBVIEW_GTK` branch added to the two popup JNI bridges (~line 4882).

## A · Approach

1. **Struct fields + setters + teardown (storage lifecycle first).**
   Add `popup_callback` to `Engine` and `OffEngine` verbatim-parallel to
   `dialog_callback`. Add `gtk_set_popup_callback` /
   `gtk_off_set_popup_callback` as delete-old-ref / new-global-ref
   setters copied from `gtk_set_dialog_callback` /
   `gtk_off_set_dialog_callback`. In `gtk_destroy_engine` /
   `gtk_off_destroy_engine`, clear `popup_callback` in the same
   attach/DeleteGlobalRef/detach block that already clears
   `dialog_callback`. Point the two popup JNI bridges at these setters
   under `#ifdef WEBVIEW_GTK`.

2. **Shared create handler.** `handle_create_web_view`:
   - If `popup_cb` is null → `return NULL` (blocked — inert until wired).
   - Read `uri` from `webkit_uri_request_get_uri(
     webkit_navigation_action_get_request(nav))` (empty if absent);
     `gesture` from `webkit_navigation_action_is_user_gesture(nav)`;
     `page` from `webkit_web_view_get_uri(opener)`.
   - `fire_popup_requested(...)` synchronously; if it returns false →
     `return NULL`.
   - `GtkWidget *childw = webkit_web_view_new_with_related_view(opener);`
     `WebKitWebView *child = WEBKIT_WEB_VIEW(childw);`
   - `GtkWidget *win = gtk_window_new(GTK_WINDOW_TOPLEVEL);`
     `gtk_window_set_title(GTK_WINDOW(win), "Popup");`
     `gtk_container_add(GTK_CONTAINER(win), childw);`
   - Build a `PopupEngine` (`window=win`, `web=child`, `jvm`,
     `popup_id = (jlong)(intptr_t)pe`), inheriting fresh global refs of
     `popup_cb` and (if non-null) `dialog_cb`.
   - Connect on `child`: `create` → `on_create_web_view_popup`,
     `script-dialog` → `on_script_dialog_popup`, `run-file-chooser` →
     `on_run_file_chooser_popup`, `ready-to-show` →
     `on_ready_to_show_popup`, `close` → `on_close_popup` — all with the
     `PopupEngine*` as `user_data`.
   - `return childw;` — WebKitGTK adopts the floating reference; do NOT
     `g_object_ref`/sink it ourselves.

3. **ready-to-show → size, show, notify-open.** Read
   `webkit_web_view_get_window_properties(child)` →
   `webkit_window_properties_get_geometry(&GdkRectangle)`; use
   `width`/`height` when > 0, else 500×650.
   `gtk_window_set_default_size`, `gtk_widget_show_all(win)`,
   `gtk_window_present(GTK_WINDOW(win))`. Then `fire_gtk_popup_opened`
   with the child's current URI and the resolved W×H.

4. **close → destroy, notify-closed, teardown.** Read the child URI,
   `fire_gtk_popup_closed(jvm, pe->popup_callback, pe->dialog_callback,
   pe->popup_id, uri, "")` (synchronous — `onPopupClosed` returns after
   `invokeLater` schedules the EDT run), `gtk_widget_destroy(pe->window)`,
   then delete the two inherited global refs and `delete pe`. Because
   the JNI call is synchronous and the Java side only reads the event it
   stored at open time (correlated by `popupId`), freeing the refs
   immediately after the call is safe — no ownership transfer to a
   detached worker is needed (the macOS divergence does not apply on
   GTK).

5. **Signal wiring at the two engine sites.** In `gtk_create_engine`,
   after the `run-file-chooser` connect (~line 1024):
   `g_signal_connect(WEBKIT_WEB_VIEW(e->web), "create",
   (GCallback)on_create_web_view_engine, e);`. Same in
   `gtk_off_create_engine` (~line 1570) with
   `on_create_web_view_off_engine`. The `create` signal is
   `G_SIGNAL_RUN_LAST` returning a `GtkWidget*`; the first non-NULL
   handler return wins.

## S · Structure

### Dependencies
- `on_create_web_view_engine` / `_off_engine` / `_popup` →
  `handle_create_web_view` → `fire_popup_requested`
  (`WebViewPopupCallback.onPopupRequested`, JNI) +
  `webkit_web_view_new_with_related_view` + GTK window.
- `on_ready_to_show_popup` → `fire_gtk_popup_opened`
  (`onPopupOpened`); `on_close_popup` → `fire_gtk_popup_closed`
  (`onPopupClosed`) + `gtk_widget_destroy` + ref frees + `delete pe`.
- `on_script_dialog_popup` / `on_run_file_chooser_popup` →
  existing `handle_script_dialog` / `handle_run_file_chooser` with
  `PopupEngine::dialog_callback`.
- JNI bridge (`WEBVIEW_GTK`) → `gtk_set_popup_callback` /
  `gtk_off_set_popup_callback` → `Engine`/`OffEngine::popup_callback`.

### Layered Architecture
- Native engine layer only (`src_c/webview_embed.cpp` GTK). No Java,
  JNI-surface, wrapper, dispatcher, component, contract, or demo change
  — all of those shipped in Canvas 15. The GTK bridge dispatch gains one
  `#ifdef` branch each.

## O · Operations

### 1. Add `popup_callback` to `Engine` and `OffEngine`
Parallel to `dialog_callback`, with an equivalent doc comment noting the
`create`-signal wiring is what invokes it.

### 2. `PopupEngine` struct + notify helpers
`struct PopupEngine { GtkWidget *window; WebKitWebView *web; JavaVM *jvm;
jobject popup_callback; jobject dialog_callback; jlong popup_id; };`
`fire_popup_requested` (bool, sync, `CallBooleanMethod`, exception
sanitize → false on error); `fire_gtk_popup_opened` /
`fire_gtk_popup_closed` (`CallVoidMethod`, exception sanitize). Method
signatures:
`onPopupRequested (Ljava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)Z`,
`onPopupOpened (JLjava/lang/String;Ljava/lang/String;ZIILjava/lang/String;)V`,
`onPopupClosed (JLjava/lang/String;Ljava/lang/String;)V`.

### 3. `handle_create_web_view` + `on_create_web_view_{engine,off_engine,popup}`
Per Approach §2. The three wrappers pull `jvm` + `popup_callback` +
`dialog_callback` from their respective struct and call the shared inner.

### 4. `on_ready_to_show_popup` + `on_close_popup` + `on_script_dialog_popup` + `on_run_file_chooser_popup`
Per Approach §3–4. The dialog wrappers reuse the shared inner dialog
handlers with `PopupEngine::dialog_callback`.

### 5. `gtk_set_popup_callback` / `gtk_off_set_popup_callback`
Delete-old / new-global-ref, copied from the dialog setters against
`popup_callback`.

### 6. Clear `popup_callback` in `gtk_destroy_engine` / `gtk_off_destroy_engine`
Same attach/`DeleteGlobalRef`/detach block as `dialog_callback`.

### 7. Connect `create` in `gtk_create_engine` + `gtk_off_create_engine`
After the `run-file-chooser` connect at each site.

### 8. `WEBVIEW_GTK` branch in the two popup JNI bridges
`webview_embed_set_popup_callback` → `gtk_set_popup_callback`;
`webview_offscreen_set_popup_callback` → `gtk_off_set_popup_callback`.

## N · Norms

- **Mirror the dialog Linux coverage (Canvas 12) exactly**: one shared
  inner handler, thin per-engine wrappers keyed by `user_data`, storage
  lifecycle in the setters + destroy funcs, JNI bridge `#ifdef` branch.
- **Opener linkage is mandatory** — `webkit_web_view_new_with_related_view`,
  never a bare new view. Documented on `handle_create_web_view`.
- **Return NULL on every non-allow path** — null `popup_callback`, JNI
  attach failure, `onPopupRequested == false`, or exception. Never return
  a half-built child.
- **Per-call `jmethodID` resolution** + **JNI exception sanitization**
  (`ExceptionCheck` → `Describe` → `Clear`) after every
  `CallBooleanMethod` / `CallVoidMethod`; a leaked exception into GTK
  crashes the process.
- **No JS shim, no `__webview_*` binding, no JSON parser** — `create` is
  a native-signal channel; payloads are primitives.
- **No Java-side change** — the Canvas 15 Java contract is frozen; this
  canvas only lights up the GTK native callback sites.

## S · Safeguards

- **Storage-first ordering** — fields + setters + teardown before the
  signal handlers, so the JNI bridge stays linkable at every commit.
- **Child-engine teardown ordering** — `close` destroys the GTK window
  and frees the inherited `popup_callback` / `dialog_callback` global
  refs after the synchronous `onPopupClosed` returns; `delete pe` last.
  A `close` without a prior `ready-to-show` (popup blocked before shown)
  still frees cleanly.
- **`popupId` correlation is best-effort** — the Java `dispatchPopupClosed`
  tolerates a missing map entry, so a close firing before open never NPEs.
- **Disabled-until-wired** — with no `popup_callback` set, `create`
  returns `NULL` and `window.open` stays blocked, identical to today.
- **No `setDebug` coupling** — the `create`/`ready-to-show`/`close`
  signals are connected unconditionally, so popups work in release builds.
- **On-device validation required** — the sandbox has no WebKitGTK
  toolchain; this GTK code is pattern-faithful to the shipped dialog
  signal handlers and the macOS popup selectors but MUST be built and
  exercised via `WebViewPopupDemo` on Linux before release. A maintainer
  who lands this MUST flip Canvas 15's coverage note and the README
  caveat to mark Linux done in the same commit.
