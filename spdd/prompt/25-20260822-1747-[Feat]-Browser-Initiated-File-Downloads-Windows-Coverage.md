---
generated_at: 2026-08-22T17:47:00-07:00
---

# REASONS Canvas: Browser-Initiated File Downloads — Windows WebView2 Coverage

## R · Requirements

- Extend the `WebViewDownloadHandler` contract established by
  [[browser-initiated-file-downloads]] (Canvas 23) to the Windows
  WebView2 backend. **No Java API changes.** Canvas 23's contract is
  final; this canvas fills in `webview_embed_set_download_callback` on
  the Windows side (`windows/webview_embed.cc`), which currently has no
  download wiring at all.

- Today WebView2 handles downloads itself: it shows its own **download
  flyout** rendered inside the embedded view and writes to a path under
  the user's Downloads folder. Two things are wrong with that in a
  Swing application — the host has no say in the destination and no
  report of the outcome, and the flyout is browser chrome appearing
  inside what is supposed to be a native desktop window. After this
  canvas, a handled download shows no flyout and lands where the host
  said.

- **Interception point**: `ICoreWebView2_4::add_DownloadStarting`.
  The event args carry the `ICoreWebView2DownloadOperation`, a
  `GetDeferral()` for asynchronous decisions, `put_ResultFilePath` for
  the destination, `put_Cancel` for refusal, and `put_Handled(TRUE)` to
  suppress the flyout. Progress and terminal outcome come from
  `add_BytesReceivedChanged` and `add_StateChanged` on the operation.

- **Graceful degradation is mandatory.** `add_DownloadStarting`
  requires `ICoreWebView2_4`; on an older WebView2 runtime the
  `QueryInterface` fails and the component must keep loading pages and
  running scripts, with downloads falling back to WebView2's built-in
  handling. Refusing to construct the engine because downloads are
  unavailable would break every application that never downloads
  anything. This matches how the engine already gates
  `ICoreWebView2_13` behind a `QueryInterface`
  (`windows/webview_embed.cc:2475`) rather than assuming the interface
  exists.

- **The decision must not block the WebView2 worker thread, and the
  deferral must be completed exactly once.** `Invoke` runs on the
  WebView2 worker thread, and every `ICoreWebView2*` method is
  apartment-bound to it — but the Java handler runs on the Swing EDT,
  and the stock one opens a modal `JFileChooser`. Blocking the worker
  waiting for that would deadlock, and `dispatch_to_thread` from inside
  the worker would self-deadlock. This canvas therefore uses the exact
  shape `ScriptDialogHandler` (Canvas 13) and `NewWindowRequested`
  (Canvas 17) already established: `GetDeferral`, `AddRef` the args,
  return `S_OK` immediately, run the JNI hop on a short-lived detached
  worker, then `dispatch_to_thread` back onto the WebView2 worker to
  set `ResultFilePath` / `Cancel`, subscribe the operation's events,
  `Complete()` the deferral, and `Release()` the args and deferral.
  That single marshalled-back lambda is the only completion site, which
  is what makes "exactly once" true — dropping the deferral hangs the
  download forever, and completing it twice is undefined.

- **Suggested filename.** WebView2 does not expose a bare suggested
  name; it exposes `get_ResultFilePath`, a full default path under the
  Downloads folder, and `get_ContentDisposition`. Take the leaf of
  `ResultFilePath` as the suggestion and let Canvas 23's dispatcher
  sanitise it — which it does unconditionally, so no Windows-specific
  sanitisation is added here.

- **Non-goals**: any Java API change; macOS (Canvas 23) or Linux
  (Canvas 24) wiring; WebView2's download-history UI; pause / resume /
  cancel-after-start (still deferred, as in Canvas 23).

## E · Entities

### Engine struct additions (`windows/webview_embed.cc`)

| Field | Type | Purpose |
|---|---|---|
| `download_callback` | `jobject` | Global ref to the Java `WebViewDownloadCallback`; `nullptr` when unset. Mirrors `dialog_callback` (`windows/webview_embed.cc:140`). |
| `download_starting_token` | `EventRegistrationToken` | For `remove_DownloadStarting` at teardown, matching `script_dialog_token`. |
| `next_download_id` | `long long` | Monotonic download identity, per engine. |
| `downloads_available` | `bool` | Result of the one-time `ICoreWebView2_4` `QueryInterface`. |

### `WinDownloadCtx` (new C++ struct)

One per in-flight `ICoreWebView2DownloadOperation`, held in a
`std::map<ICoreWebView2DownloadOperation *, std::unique_ptr<WinDownloadCtx>>`
on the engine so the tokens can be removed and the entry erased when the
download reaches a terminal state.

| Field | Type | Purpose |
|---|---|---|
| `engine` | `Engine *` | For the JNI fire helpers. |
| `id` | `long long` | Identity carried on every event. |
| `received` | `long long` | Last reported byte count. |
| `total` | `long long` | Declared size, or `-1` when unknown. |
| `bytes_token` | `EventRegistrationToken` | For `remove_BytesReceivedChanged`. |
| `state_token` | `EventRegistrationToken` | For `remove_StateChanged`. |
| `terminal` | `bool` | Native-side guard against a double terminal report. |

### Callback classes

Three new COM callback classes following the file's existing
single-interface `QueryInterface` pattern
(`windows/webview_embed.cc:402-413`):
`DownloadStartingHandler` (`ICoreWebView2DownloadStartingEventHandler`),
`DownloadBytesReceivedHandler`
(`ICoreWebView2BytesReceivedChangedEventHandler`), and
`DownloadStateChangedHandler`
(`ICoreWebView2StateChangedEventHandler`).

## A · Approach

1. **Gate on `ICoreWebView2_4` once, at wiring time.** Query the
   interface where the engine already wires
   `add_ScriptDialogOpening` (`windows/webview_embed.cc:1659`); record
   the result in `downloads_available`; register
   `add_DownloadStarting` only on success. Every later code path checks
   the flag rather than re-querying.

2. **Defer, decide on a worker, answer back on the WebView2 thread.**
   `GetDeferral` + `AddRef` on the args, return `S_OK`, do the JNI hop
   on a detached `std::thread`, then `dispatch_to_thread` back to apply
   the outcome and `Complete()`. One completion site inside that
   marshalled lambda, never at a return site. If `GetDeferral` itself
   fails, do not intercept at all: return `S_OK` without setting
   `Handled`, so WebView2's own flow takes over rather than the
   download hanging un-answered — the same fallback
   `ScriptDialogHandler` takes.

3. **Refusal is `put_Cancel(TRUE)`, not an empty path.** Leaving
   `ResultFilePath` unset lets WebView2 use its own default under
   Downloads, which is the behaviour being removed. `put_Handled(TRUE)`
   suppresses the flyout in both the accepted and refused cases — a
   refused download must not flash a flyout either.

4. **Subscribe to the operation's events before returning from
   `DownloadStarting`.** The operation can transition immediately for a
   tiny or already-failed download; subscribing after the event handler
   returns can miss the terminal transition entirely.

5. **Map `COREWEBVIEW2_DOWNLOAD_STATE` onto the contract.**
   `_COMPLETED` is success. `_INTERRUPTED` is failure, with the reason
   derived from `get_InterruptReason` (a readable string per
   `COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON`, falling back to the
   numeric value for reasons the mapping does not name).
   `_IN_PROGRESS` is not terminal and reports nothing.

6. **Report progress from `BytesReceivedChanged`.** Read
   `get_BytesReceived` on the operation; the total came from
   `get_TotalBytesToReceive` at start (`0` or negative ⇒ `-1`,
   unknown). WebView2 fires this on its own schedule, so no native
   throttling is added — the dispatcher's coalescing gate (Canvas 23,
   Operation 6.4) already bounds EDT pressure, and native throttling
   would make the last progress value lag the terminal event.

7. **Tear down tokens with the download, and with the engine.** Remove
   both per-operation tokens and erase the map entry when a terminal
   state arrives; remove `download_starting_token` and clear the JNI
   global ref in the engine's destroy path, before the controller is
   released, matching the ordering already used for
   `script_dialog_token` and `dialog_callback`.

8. **Reuse the existing JNI-fire idiom.** The Windows file already has
   the defensive attach/detach helper shape used by the dialog bridge;
   the three download fire helpers follow it exactly rather than
   introducing a second convention.

## S · Structure

### Dependencies
1. `ICoreWebView2_4` (QueryInterface) → `add_DownloadStarting` →
   `DownloadStartingHandler` → `WinDownloadCtx` + Java decision →
   `put_ResultFilePath` / `put_Cancel` + `put_Handled(TRUE)`.
2. `ICoreWebView2DownloadOperation` → `add_BytesReceivedChanged` /
   `add_StateChanged` → the two handler classes → the fire helpers.
3. Fire helpers → `Engine::download_callback` →
   `WebViewDownloadCallback` → `DownloadDispatcher.dispatch*` →
   `WebViewDownloadHandler`.
4. `win_set_download_callback` ← the `JNIEXPORT`
   `webview_embed_set_download_callback` bridge.

### Layered Architecture
Unchanged from Canvas 23. This canvas touches only layer 1 (native
engine) and the Windows half of the JNI bridge in layer 2.
`webview_offscreen_set_download_callback` has no Windows
implementation — there is no offscreen engine on this platform, exactly
as for the dialog and popup callbacks.

## O · Operations

### 1. Extend the engine struct
File: `windows/webview_embed.cc`
1. Add `download_callback`, `download_starting_token`,
   `next_download_id`, and `downloads_available` per Entities, beside
   the existing `dialog_callback` / `script_dialog_token` fields, with
   a comment matching their voice.
2. Add the `WinDownloadCtx` struct and the engine's
   `std::map<ICoreWebView2DownloadOperation *, std::unique_ptr<WinDownloadCtx>> downloads`.

### 2. Add the three fire helpers
File: `windows/webview_embed.cc`
1. `fire_win_download_requested(Engine *, long long id,
   const wchar_t *url, const wchar_t *suggested,
   const wchar_t *mime, long long total, const wchar_t *page_url,
   std::wstring *out_path)` → `CallObjectMethod` returning `jstring`;
   `false` when the ref is null, the method is missing, or Java
   returned `null`.
2. `fire_win_download_progress(Engine *, long long id,
   long long received, long long total)` → `CallVoidMethod`.
3. `fire_win_download_completed(Engine *, long long id, bool success,
   const wchar_t *reason, long long received)` → `CallVoidMethod`.
4. All three: defensive attach/detach, method id resolved per call,
   `ExceptionCheck` / `ExceptionClear` after every `Call*Method`,
   null-check the ref first. UTF-16 ↔ `jstring` conversion uses
   `NewString` / `GetStringChars` directly (no UTF-8 round trip), since
   `jchar` and `wchar_t` are both 16-bit on Windows.

### 3. Implement the interrupt-reason mapping
File: `windows/webview_embed.cc`
1. `static std::wstring win_download_interrupt_reason(
   COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON r)` — a `switch` naming the
   documented reasons (file failed, file access denied, file no space,
   file name too long, file too large, file virus infected, file
   transient error, file blocked, file security check failed, file too
   short, hash mismatch, network failed, network timeout,
   network disconnected, network server down, network invalid request,
   server failed, server no range, server bad content,
   server unauthorized, server certificate problem, server forbidden,
   server unexpected response, server cross origin redirect,
   user canceled, user shutdown, user paused, download process crashed).
2. `default:` returns `L"Download interrupted (reason " + number + L")"`
   so an unmapped future value still produces a usable message rather
   than an empty string.

### 4. Implement the state-changed handler
File: `windows/webview_embed.cc`
1. `DownloadStateChangedHandler::Invoke(
   ICoreWebView2DownloadOperation *op, IUnknown *)`:
   a. Recover the `WinDownloadCtx` from the engine's map; return
      `S_OK` when absent.
   b. `op->get_State(&state)`. Return `S_OK` for
      `COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS`.
   c. Claim `ctx->terminal`; return `S_OK` if already claimed.
   d. Refresh `ctx->received` from `get_BytesReceived`.
   e. `_COMPLETED` → `fire_win_download_completed(..., true, L"",
      ctx->received)`. `_INTERRUPTED` → read `get_InterruptReason`,
      map it with Operation 3, and fire with `success = false`.
   f. `remove_BytesReceivedChanged(ctx->bytes_token)` and
      `remove_StateChanged(ctx->state_token)`, then erase the map
      entry.

### 5. Implement the bytes-received handler
File: `windows/webview_embed.cc`
1. `DownloadBytesReceivedHandler::Invoke(
   ICoreWebView2DownloadOperation *op, IUnknown *)`:
   recover the ctx (return `S_OK` when absent or already terminal),
   `op->get_BytesReceived(&received)`, store it, and call
   `fire_win_download_progress(engine, ctx->id, ctx->received,
   ctx->total)`.

### 6. Implement the download-starting handler
File: `windows/webview_embed.cc`
1. `DownloadStartingHandler::Invoke(ICoreWebView2 *sender,
   ICoreWebView2DownloadStartingEventArgs *args)`, following
   `ScriptDialogHandler`'s shape exactly:
   a. `args->get_DownloadOperation(&op)`; `AddRef` it so it survives
      the hop.
   b. While still on the WebView2 worker, read `get_Uri`,
      `get_MimeType`, `get_TotalBytesToReceive` (`<= 0` ⇒ `-1`) from
      the operation and `get_ResultFilePath` from the args; take the
      leaf of the result path (after the last `\` or `/`) as the
      suggested name; page URL from `sender->get_Source`. Convert each
      to `std::string` with `wide_to_utf8` and `CoTaskMemFree` the
      originals.
   c. Mint `id = e->next_download_id++`.
   d. `args->GetDeferral(&deferral)`. On failure, log and return `S_OK`
      **without** setting `Handled` — WebView2's own flow takes over,
      which is far better than a download that hangs un-answered.
      `args->AddRef()`.
   e. Return `S_OK`; the JNI hop runs on a detached `std::thread`
      calling `fire_win_download_requested`.
   f. `dispatch_to_thread` back onto the WebView2 worker to apply the
      outcome:
      - `args->put_Handled(TRUE)` unconditionally — the flyout is
        suppressed whether or not the download is accepted.
      - Refused ⇒ `args->put_Cancel(TRUE)`.
      - Accepted ⇒ `args->put_ResultFilePath(wide.c_str())`, then
        create the `WinDownloadCtx`, subscribe
        `add_BytesReceivedChanged` and `add_StateChanged` on the
        operation, and insert the ctx into the engine's map keyed by
        the operation pointer. Subscribing here, before `Complete()`,
        is what stops a tiny or already-failed download transitioning
        before anyone is listening.
      - `deferral->Complete(); deferral->Release(); args->Release();`
        and, when refused, `op->Release()`.

### 7. Register and unregister the event
File: `windows/webview_embed.cc`
1. Where the engine wires `add_ScriptDialogOpening`
   (`windows/webview_embed.cc:1659`), `QueryInterface` for
   `ICoreWebView2_4`; on success set `e->downloads_available = true`
   and call `add_DownloadStarting`, storing
   `e->download_starting_token`. On failure leave the flag false and
   log nothing — an old runtime is a supported configuration, not an
   error.
2. In the engine teardown path that already removes
   `script_dialog_token` and frees `dialog_callback`, remove
   `download_starting_token` (when `downloads_available`), remove any
   surviving per-operation tokens, clear the `downloads` map, then
   delete and null `download_callback` — before the controller is
   released.

### 8. Implement the callback setter and JNI bridge
File: `windows/webview_embed.cc`
1. `win_set_download_callback(Engine *e, JNIEnv *env, jobject cb)` —
   delete any existing global ref, store `NewGlobalRef(cb)` or
   `nullptr`, mirroring the existing dialog-callback setter at
   `windows/webview_embed.cc:2376`.
2. Wire the `JNIEXPORT`
   `Java_ca_weblite_webview_WebViewNative_webview_1embed_1set_1download_1callback`
   to it.
3. `…_webview_1offscreen_1set_1download_1callback` stays a no-op with
   `(void)`-cast parameters — there is no offscreen engine on Windows,
   matching the dialog and popup callbacks.

### 9. Update the Java platform-coverage notes
Files: `src/ca/weblite/webview/swing/WebViewComponent.java`,
`src/ca/weblite/webview/WebViewDownloadHandler.java`,
`src/ca/weblite/webview/WebViewNative.java`, `README.md`
1. Amend the platform-coverage sentences so all three platforms are
   listed as wired, and add the note that Windows requires a WebView2
   runtime exposing `ICoreWebView2_4` and silently keeps its built-in
   download handling on older runtimes. Behaviour-bearing
   documentation, so it goes through the Canvas.

### 10. Extend the demo runner
File: `run-windows-download-demo.bat`
1. Confirm the demo from Canvas 23 Operation 14 runs against the
   Windows build; no demo source change is expected, only that the
   batch file exists alongside the other `run-windows-*.bat` scripts.

## N · Norms

1. **`QueryInterface`-gate every newer WebView2 interface.** Never
   assume `ICoreWebView2_N`; the runtime is user-installed and
   independently versioned.
2. **One COM callback class per interface**, following the file's
   existing single-interface `QueryInterface` pattern.
3. **Every `add_*` has a matching `remove_*`** on the path that
   destroys the thing it was added to.
4. **Deferrals complete exactly once**, in the single marshalled-back
   lambda, never at a return site — and never on the thread that made
   the Java call, since every `ICoreWebView2*` method is apartment-bound
   to the WebView2 worker.
5. **JNI refs are deleted before the controller is released**, and
   every fire helper null-checks first.
6. **No Java API change.** If an operation here seems to need one, the
   contract in Canvas 23 is wrong and that canvas must be amended
   instead.
7. **`-Wall`-clean / no unreferenced-parameter warnings**: `(void)`
   casts on unused COM handler parameters, matching the existing
   handlers.

## S · Safeguards

1. **Refusal falling back to the Downloads folder.** `put_Cancel(TRUE)`
   is mandatory on refusal; leaving `ResultFilePath` unset lets
   WebView2 write its own default, re-introducing exactly the behaviour
   this canvas removes.
2. **A flyout on a refused download.** `put_Handled(TRUE)` is set
   before the decision, so neither outcome shows browser chrome inside
   the Swing window.
3. **Deferral lifetime.** Never completing it hangs the download and
   leaks the operation; completing it twice is undefined. The single
   `dispatch_to_thread` lambda is the only completion site, and a
   failed `GetDeferral` means not intercepting at all rather than
   intercepting without the means to answer.
4. **Missing the terminal transition.** Subscribing to the operation's
   events after returning from `DownloadStarting` can miss an immediate
   `_INTERRUPTED` for a download that fails at once. Subscribe before
   returning.
5. **Double terminal report.** `ctx->terminal` guards natively; the
   dispatcher's latch guards Java-side. Both, because the second event
   can arrive during teardown.
6. **Old-runtime hang.** If `ICoreWebView2_4` is unavailable, no event
   is registered and WebView2 keeps its built-in handling; the
   component must still load pages and run scripts. A half-registered
   state — handled but never answered — would hang every download,
   which is worse than not intercepting at all.
7. **Dangling operation pointers.** The map is keyed by the raw
   operation pointer, so the entry must be erased on the terminal
   transition and the whole map cleared at engine teardown; a stale key
   reused by a later allocation would misattribute events.
8. **UTF-16 conversion.** `jchar` and `wchar_t` are both 16-bit on
   Windows; converting through UTF-8 would mangle names outside the
   BMP. Use `NewString` / `GetStringChars` directly.
