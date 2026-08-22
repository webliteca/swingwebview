---
generated_at: 2026-08-22T17:46:00-07:00
---

# REASONS Canvas: Browser-Initiated File Downloads — Linux WebKitGTK Coverage

## R · Requirements

- Extend the `WebViewDownloadHandler` contract established by
  [[browser-initiated-file-downloads]] (Canvas 23) to the Linux
  WebKitGTK backend, for the heavyweight (X11-reparented) engine, the
  lightweight (offscreen) engine, and the popup engines that share an
  opener's web context. **No Java API changes.** Canvas 23's contract is
  final; this canvas only fills in `webview_embed_set_download_callback`
  and `webview_offscreen_set_download_callback` on the
  `#ifdef WEBVIEW_GTK` side, which Canvas 23 left as `(void)`-cast
  no-ops.

- Today WebKitGTK does not discard downloads the way `WKWebView` does —
  it handles them itself, choosing a destination under the user's
  download directory with no involvement from, and no report to, the
  embedding application. That is the behaviour being **removed**: after
  this canvas, a refused download must write nothing anywhere,
  including nothing in `~/Downloads`.

- **The interception point is the `WebKitWebContext`, not the web
  view.** WebKitGTK emits `download-started` on the context, which is
  shared by every view created against it. Every download must
  therefore be mapped back to the view that started it — via
  `webkit_download_get_web_view()` — before a dispatcher can be
  chosen. Getting this wrong sends one component's downloads to
  another component's handler, which is the sharpest correctness risk
  on this platform and has no analogue on macOS or Windows. A download
  whose view is unknown to us (null view, or a view with no registered
  sink) is left entirely alone, so WebKitGTK's built-in handling still
  applies to any view this library did not create.

- **`webkit_download_set_destination` changed meaning across the
  supported ABI range.** Before WebKitGTK 2.40 it takes a `file://`
  **URI**; from 2.40 it takes a plain filesystem **path**. The library
  ships one `libwebview.so` that must run on both 4.0 (Ubuntu 20.04)
  and 4.1 (Ubuntu 22.04+) hosts, so the correct form is chosen at
  runtime from `webkit_get_major_version()` /
  `webkit_get_minor_version()`. Passing the wrong form silently writes
  to a file literally named `file:` or fails the download outright.

- **Every new WebKit symbol resolves through the `dlopen` shim.**
  `libwebview.so` links no WebKit symbol; adding a direct call to
  `webkit_download_*` would compile locally and fail at runtime on the
  other ABI. Each new function is an entry in `WK_WEBKIT_SYMS`
  (`src_c/webkit_loader.h`) with a matching redirect in
  `src_c/webkit_shim.h`.

- **Popup coverage.** A popup engine inherits the opener's JNI global
  refs (the pattern at `src_c/webview_embed.cpp:1216` for
  `dialog_callback`); the download callback joins that inheritance, so
  a download started in a `window.open`ed child reaches the opening
  component's handler. A popup adopted into a tab (Canvas 18-19) keeps
  the sink it was created with, so its downloads continue to reach the
  handler that was already receiving them — the adopting component does
  not steal them mid-transfer.

- **Non-goals**: any Java API change; macOS (Canvas 23) or Windows
  (Canvas 25) wiring; WebKitGTK's own download-progress UI; pause /
  resume / cancel-after-start (still deferred, as in Canvas 23).

## E · Entities

### `DownloadSink` (new C struct, `src_c/webview_embed.cpp`, GTK section)

The per-view record that maps a `WebKitWebView` back to the Java
dispatcher that owns it. Attached to the view with
`g_object_set_data_full(G_OBJECT(view), "weblite-download-sink", sink,
free_download_sink)` when the callback is registered, so its lifetime is
the view's and no global registry is needed — a registry would have to
be kept in step with view destruction by hand, and getting that wrong is
the same use-after-free this design avoids.

| Field | Type | Purpose |
|---|---|---|
| `jvm` | `JavaVM *` | For the JNI attach in the fire helpers. |
| `download_callback` | `jobject` | Global ref to the Java `WebViewDownloadCallback`; `nullptr` when unset. |
| `next_id` | `long long` | Monotonic download identity, per view. |

### `GtkDownloadCtx` (new C struct)

One per in-flight `WebKitDownload`, attached to the download object with
`g_object_set_data_full` so it dies with the transfer.

| Field | Type | Purpose |
|---|---|---|
| `sink` | `DownloadSink *` | The view's sink; borrowed, never owned. |
| `id` | `long long` | Identity carried on every event. |
| `received` | `guint64` | Running byte count, for the terminal report. |
| `total` | `gint64` | Declared size, or `-1` when unknown. |
| `terminal` | `gboolean` | Native-side guard so `failed` + `finished` do not both fire a report. |

### Engine struct additions

`Engine` and `PopupEngine` each gain
`jobject download_callback = nullptr;`, mirroring `dialog_callback`
exactly: populated by the setter, inherited by popups at creation,
deleted and nulled in the destroy path **before** the widget is
destroyed.

### New symbols in `WK_WEBKIT_SYMS`

`webkit_web_view_get_context`, `webkit_download_get_web_view`,
`webkit_download_get_request`, `webkit_download_get_response`,
`webkit_download_set_destination`,
`webkit_download_set_allow_overwrite`, `webkit_download_cancel`,
`webkit_download_get_received_data_length`,
`webkit_uri_response_get_content_length`,
`webkit_uri_response_get_mime_type`, `webkit_uri_response_get_uri`,
`webkit_get_major_version`, `webkit_get_minor_version`.
(`webkit_uri_request_get_uri` is already in the list.)

## A · Approach

1. **Connect `download-started` once per context, not once per view.**
   The signal lives on the context; connecting it per view would fire
   the handler N times for one download. Connect it the first time any
   engine registers a download callback against that context, tracked
   with a `g_object_get_data` marker on the context itself so a second
   engine on the same context does not double-connect.

2. **Dispatch by view, refuse to guess.** In the `download-started`
   handler, resolve `webkit_download_get_web_view(download)`; if it is
   null, or carries no `weblite-download-sink`, return without touching
   the download — WebKitGTK's built-in handling stays in charge for
   anything this library did not create.

3. **Answer `decide-destination` synchronously.** The signal handler
   returns `gboolean`; returning `TRUE` means "handled, stop emission"
   and suppresses WebKitGTK's default destination. Inside it, call the
   Java bridge (which blocks on the EDT), then either
   `webkit_download_set_destination(...)` and return `TRUE`, or
   `webkit_download_cancel(download)` and return `TRUE`. **Refusal
   must call `cancel`** — merely returning `TRUE` without a destination
   leaves the transfer in a state where WebKitGTK may still fall back
   to its default directory, which is exactly the behaviour this canvas
   exists to remove.

4. **Choose the destination form at runtime.** A small helper
   `gtk_download_destination_arg(const char *abs_path)` returns a
   `file://`-prefixed URI on WebKitGTK < 2.40 and the bare path from
   2.40 onward, deciding from `webkit_get_major_version()` /
   `webkit_get_minor_version()`. Percent-encode the path when building
   the URI form so a filename containing `#`, `?`, or a space survives.

5. **`set_allow_overwrite(TRUE)`.** The destination decision has
   already been made — by the user through the stock save dialog's
   overwrite confirmation, or by an application handler that chose the
   path deliberately. Leaving overwrite disabled would make WebKitGTK
   silently rename or fail a download the host already approved.

6. **Report progress from `received-data`.** The signal carries the
   chunk length, not the total, so accumulate into `ctx->received` and
   report the running total. WebKitGTK fires this per chunk; the
   dispatcher's coalescing gate (Canvas 23, Operation 6.4) is what keeps
   that from flooding the EDT, so no additional native throttling is
   needed or wanted — native throttling would make the final progress
   value lag the terminal event.

7. **Guard the terminal report natively as well as in Java.**
   WebKitGTK can emit `failed` and then `finished` for the same
   abandoned transfer. The dispatcher's `AtomicBoolean` latch already
   makes this correct, but `ctx->terminal` stops the redundant JNI
   round-trip, which matters because the second one can arrive during
   teardown.

8. **Free refs before widget destruction.** `gtk_destroy_engine` and
   `on_close_popup` delete and null `download_callback` before the
   `WebKitWebView` is destroyed, matching the ordering already
   documented for `click_callback`. The `DownloadSink`'s destroy
   function is the view's own, so it cannot outlive the view.

## S · Structure

### Dependencies
1. `webkit_shim.h` redirects → `webkit_loader.h` X-macro entries →
   `webkit_loader.cpp` resolver (no new code, the X-macro generates it).
2. `download-started` (context) → `on_download_started` →
   `webkit_download_get_web_view` → `DownloadSink` →
   `GtkDownloadCtx` allocation + per-download signal connections.
3. `decide-destination` → `fire_download_requested` →
   `WebViewDownloadCallback.onDownloadRequested` →
   `DownloadDispatcher.dispatchDownloadRequested` (EDT, blocking) →
   `webkit_download_set_destination` or `webkit_download_cancel`.
4. `received-data` → `fire_download_progress`; `finished` / `failed` →
   `fire_download_completed`.
5. `gtk_set_download_callback` / `gtk_off_set_download_callback` ←
   the two `JNIEXPORT` bridges Canvas 23 stubbed.

### Layered Architecture
Unchanged from Canvas 23. This canvas touches only layer 1 (native
engine) and the Linux half of the JNI bridges in layer 2.

## O · Operations

### 1. Add the new WebKit symbols to the loader
Files: `src_c/webkit_loader.h`, `src_c/webkit_shim.h`
1. Add the thirteen symbols listed in Entities to `WK_WEBKIT_SYMS` in
   `webkit_loader.h`, in the list's existing alphabetical order.
2. Add the matching `#define` redirect for each in `webkit_shim.h`,
   in the same order, following the existing
   `#define webkit_x (::g_wk.fn_webkit_x)` form.
3. No change to `webkit_loader.cpp` — the X-macro generates the
   declaration, the storage, and the `dlsym` for each entry.

### 2. Add the sink and context structs
File: `src_c/webview_embed.cpp` (GTK section)
1. Define `DownloadSink` and `GtkDownloadCtx` per Entities, above the
   dialog bridge block.
2. `static void free_download_sink(gpointer p)` — delete the JNI
   global ref (attaching to the JVM if needed) and `g_free` the struct.
3. Add `jobject download_callback = nullptr;` to `Engine` and to
   `PopupEngine`, with a comment matching the `dialog_callback` one.

### 3. Add the three fire helpers
File: `src_c/webview_embed.cpp` (GTK section)
1. `fire_download_requested(DownloadSink *, long long id,
   const char *url, const char *suggested, const char *mime,
   gint64 total, const char *page_url, std::string *out_path)`
   → `CallObjectMethod` returning `jstring`; `false` when the ref is
   null, the method is missing, or Java returned `null`.
2. `fire_download_progress(DownloadSink *, long long id,
   guint64 received, gint64 total)` → `CallVoidMethod`.
3. `fire_download_completed(DownloadSink *, long long id,
   gboolean success, const char *reason, guint64 received)`
   → `CallVoidMethod`.
4. All three follow `fire_click_callback`'s shape exactly: defensive
   `GetEnv` / `AttachCurrentThread`, detach only if we attached,
   resolve the method id per call, `ExceptionCheck` / `ExceptionClear`
   after every `Call*Method`, null-check the ref first.

### 4. Implement the destination-form helper
File: `src_c/webview_embed.cpp` (GTK section)
1. `static gchar *gtk_download_destination_arg(const char *abs_path)`
   — returns newly-allocated memory the caller `g_free`s.
2. When `webkit_get_major_version() > 2 ||
   (webkit_get_major_version() == 2 && webkit_get_minor_version() >= 40)`,
   return `g_strdup(abs_path)`.
3. Otherwise build a `file://` URI with
   `g_filename_to_uri(abs_path, nullptr, nullptr)`; if that fails
   (a relative path — it should not happen, Java sends absolute), fall
   back to `g_strdup(abs_path)` rather than returning null.

### 5. Implement the per-download signal handlers
File: `src_c/webview_embed.cpp` (GTK section)
1. `on_download_decide_destination(WebKitDownload *d,
   const gchar *suggested, gpointer user_data)` → `gboolean`:
   a. Recover `GtkDownloadCtx`; return `FALSE` if absent.
   b. Read the response via `webkit_download_get_response(d)`:
      `webkit_uri_response_get_content_length` (0 ⇒ `-1`),
      `webkit_uri_response_get_mime_type`,
      `webkit_uri_response_get_uri`. When the response is null, fall
      back to `webkit_uri_request_get_uri(webkit_download_get_request(d))`
      for the URL and `-1` / `""` for size and MIME.
      Store the resolved total into `ctx->total`.
   c. Page URL: `webkit_web_view_get_uri` of the download's view, or
      `""`.
   d. Call `fire_download_requested`. On `false` (refusal):
      `webkit_download_cancel(d)` and return `TRUE`.
   e. On success: `webkit_download_set_allow_overwrite(d, TRUE)`;
      build the destination argument with the Operation 4 helper;
      `webkit_download_set_destination(d, arg)`; `g_free(arg)`;
      return `TRUE`.
2. `on_download_received_data(WebKitDownload *d, guint64 length,
   gpointer user_data)`: accumulate `ctx->received += length` and call
   `fire_download_progress(ctx->sink, ctx->id, ctx->received,
   ctx->total)`.
3. `on_download_finished(WebKitDownload *d, gpointer user_data)`:
   claim `ctx->terminal` (return if already set); refresh
   `ctx->received` from `webkit_download_get_received_data_length(d)`;
   `fire_download_completed(..., TRUE, "", ctx->received)`.
4. `on_download_failed(WebKitDownload *d, GError *error,
   gpointer user_data)`: claim `ctx->terminal` (return if already
   set); `fire_download_completed(..., FALSE,
   error && error->message ? error->message : "Download failed",
   ctx->received)`.
   A user-initiated cancel arrives here as
   `WEBKIT_DOWNLOAD_ERROR_CANCELLED_BY_USER`, which is the correct
   outcome for a refusal and needs no special case — the dispatcher has
   already reported the refusal and latched the id, so this second
   report is dropped Java-side.

### 6. Implement the context-level `download-started` handler
File: `src_c/webview_embed.cpp` (GTK section)
1. `on_context_download_started(WebKitWebContext *ctx_obj,
   WebKitDownload *d, gpointer user_data)`:
   a. `WebKitWebView *view = webkit_download_get_web_view(d)`; return
      immediately when null.
   b. `DownloadSink *sink = (DownloadSink *) g_object_get_data(
      G_OBJECT(view), "weblite-download-sink")`; return when null or
      when `sink->download_callback` is null — the view is not ours,
      or has no handler, so WebKitGTK keeps its built-in behaviour.
   c. Allocate a `GtkDownloadCtx` with `id = sink->next_id++`,
      `total = -1`, `received = 0`, `terminal = FALSE`, and attach it
      to the download with `g_object_set_data_full(G_OBJECT(d),
      "weblite-download-ctx", ctx, g_free)`.
   d. Connect `decide-destination`, `received-data`, `finished`, and
      `failed` to the Operation 5 handlers, passing `ctx`.

### 7. Implement the callback setters
File: `src_c/webview_embed.cpp` (GTK section)
1. `gtk_set_download_callback(Engine *e, JNIEnv *env, jobject cb)`:
   a. Delete any existing `e->download_callback` global ref; store
      `NewGlobalRef(cb)` or `nullptr`. Mirrors
      `gtk_set_dialog_callback`.
   b. Create (or update) the view's `DownloadSink` with the JVM and
      the new global ref, attached via `g_object_set_data_full` with
      `free_download_sink`.
   c. Ensure `download-started` is connected on
      `webkit_web_view_get_context(e->webview)` **exactly once per
      context**: check for a `"weblite-download-connected"` marker set
      with `g_object_set_data` on the context, connect and set the
      marker when absent.
2. `gtk_off_set_download_callback(...)` — identical against the
   offscreen engine struct, matching how
   `gtk_off_set_dialog_callback` mirrors its heavyweight twin.
3. Popup inheritance: where `on_create_popup` already copies
   `dialog_callback` into the child `PopupEngine`
   (`src_c/webview_embed.cpp:1216`), copy `download_callback` the same
   way and install a `DownloadSink` on the child view. Freeing follows
   the same path as `dialog_callback` in `on_close_popup`.

### 8. Fill in the JNI bridges
File: `src_c/webview_embed.cpp`
1. Replace the `(void)`-cast no-op bodies Canvas 23 left in the
   `#ifdef WEBVIEW_GTK` branches of
   `Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1download_1callback`
   and `…_webview_1offscreen_1set_1download_1callback` with calls to
   the Operation 7 setters.

### 9. Free the refs before teardown
File: `src_c/webview_embed.cpp`
1. In `gtk_destroy_engine`, delete and null `download_callback`
   **before** the widget is destroyed, in the same block that already
   handles `dialog_callback` and `click_callback`.
2. In `on_close_popup`, extend the existing
   `free_inherited_refs`-style cleanup to cover the popup's
   `download_callback`.

### 10. Update the Java platform-coverage notes
Files: `src/ca/weblite/webview/swing/WebViewComponent.java`,
`src/ca/weblite/webview/WebViewDownloadHandler.java`,
`src/ca/weblite/webview/WebViewNative.java`, `README.md`
1. Amend the platform-coverage sentences Canvas 23 wrote so Linux is
   listed as wired (heavyweight, lightweight, and popups) and only
   Windows remains outstanding. Behaviour-bearing documentation, so it
   goes through the Canvas rather than a hand edit.

### 11. Extend the demo runner
File: `run-linux-download-demo.sh`
1. Confirm the demo from Canvas 23 Operation 14 runs against the Linux
   build; no demo source change is expected, only that the script
   exists and is executable alongside the other `run-linux-*.sh`
   scripts.

## N · Norms

1. **Every WebKit symbol goes through the shim.** No direct
   `webkit_*` call may appear without a `WK_WEBKIT_SYMS` entry and a
   `webkit_shim.h` redirect. This is the single rule most likely to be
   broken by a well-meaning edit, because the code compiles either way
   and only fails on the other ABI at runtime.
2. **`-Wall -Wextra -pedantic` clean.** Unused parameters in signal
   handlers get `(void)` casts, matching the existing GTK handlers.
3. **`g_object_set_data_full`, never a global registry.** Lifetime
   follows the object; there is nothing to keep in step by hand.
4. **JNI refs are deleted before the widget is destroyed**, and every
   fire helper null-checks first.
5. **GTK signal handlers return the documented type.**
   `decide-destination` returns `gboolean`; returning `TRUE` is what
   suppresses the default destination, and a handler that forgets it
   silently restores the behaviour this canvas removes.
6. **No Java API change.** If an operation here seems to need one, the
   contract in Canvas 23 is wrong and that canvas must be amended
   instead.

## S · Safeguards

1. **Cross-component download leakage.** `download-started` is
   context-wide; resolving the view and refusing to act on an unknown
   one is what keeps component A's downloads out of component B's
   handler. This is the highest-severity Linux-specific risk.
2. **Refusal falling back to `~/Downloads`.** `webkit_download_cancel`
   is mandatory on refusal; returning `TRUE` from `decide-destination`
   without a destination is not sufficient and re-introduces exactly
   the behaviour being removed.
3. **ABI divergence in `set_destination`.** URI before 2.40, path from
   2.40. The runtime version check is not optional; the wrong form
   produces a file named `file:` or a failed download, depending on
   version.
4. **Percent-encoding in the URI form.** A path containing a space,
   `#`, or `?` must survive `g_filename_to_uri`; hand-concatenating
   `"file://"` would corrupt it.
5. **Double terminal report.** `ctx->terminal` guards the native side;
   the dispatcher's latch guards the Java side. Both, because the
   second event can arrive during teardown when the Java side may
   already be disposed.
6. **Use-after-free on the sink.** The sink is owned by the view via
   `g_object_set_data_full`, so it cannot outlive it; the download's
   ctx borrows the sink and dies with the download, which WebKitGTK
   guarantees ends before the view it belongs to.
7. **Double-connecting `download-started`.** Two engines on one
   context would otherwise both handle every download, allocating two
   contexts and firing two Java requests for one transfer. The
   one-time marker on the context prevents it.
8. **Adopted popups.** A popup adopted into a tab keeps the sink it
   was created with, so an in-flight download keeps reporting to the
   handler that approved it rather than silently changing owner
   mid-transfer.
