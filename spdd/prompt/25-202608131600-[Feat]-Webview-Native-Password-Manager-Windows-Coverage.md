---
generated_at: 2026-08-13T16:00:00-07:00
---

# REASONS Canvas: Password Manager — Windows WebView2 + Credential Manager Coverage

## R · Requirements

- Extend the password manager shipped by canvas 23 (STORY-006-001) to
  Windows, where `WebViewHeavyweightComponent` uses Microsoft WebView2
  (embedded Edge/Chromium). This canvas adds Windows native code only —
  **no Java or JavaScript changes**: the public API and the shared
  `PasswordDispatcher.SHIM_JS` are reused verbatim from canvas 23. Windows
  uses the **library's own** password manager (not Edge's profile store),
  so behaviour is uniform with macOS and Linux.
- Three native pieces, all in `windows/webview_embed.cc` (replacing the
  canvas-23 Windows stub bodies):
  1. **Password message routing + capture:** the shared `SHIM_JS` posts
     `window.chrome.webview.postMessage('__webview_pw__:' + payload)`.
     Extend the existing `MsgHandler` (`ICoreWebView2WebMessageReceivedEventHandler`,
     already registered via `add_WebMessageReceived`) to recognise
     messages prefixed `__webview_pw__:`, read the **committed source URL
     natively** via `ICoreWebView2WebMessageReceivedEventArgs::get_Source`
     (fallback `ICoreWebView2::get_Source`), parse the
     `S|<b64user>|<b64pass>` / `F` tag, and invoke the Java
     `WebViewPasswordCallback`. Implement the callback setter
     `webview_embed_set_password_callback` (and the offscreen setter as a
     no-op — Windows has no offscreen engine), following the existing
     `webview_embed_set_dialog_callback` JNI export.
  2. **Credential Manager store:** replace the canvas-23 Windows stub
     bodies of the four `webview_cred_store_*` primitives with a Win32
     Credential Manager implementation (`CredWriteW` / `CredReadW` /
     `CredEnumerateW` / `CredDeleteW`, `CRED_TYPE_GENERIC`), matching the
     macOS/Linux semantics exactly (same value encoding `millis + "\n" +
     password`, same origin/username keying, same most-recent-first
     contract — Java re-sorts).
  3. **Suppress Edge's built-in password autosave** so it never competes
     with the library's Swing prompt: at engine creation, after acquiring
     the settings object, `QueryInterface` for `ICoreWebView2Settings4`
     and call `put_IsPasswordAutosaveEnabled(FALSE)` (it defaults FALSE,
     but set it explicitly and defensively) and leave
     `put_IsGeneralAutofillEnabled` untouched.
- **Target-name encoding for Credential Manager.** Each credential is one
  `CRED_TYPE_GENERIC` entry whose `TargetName` is
  `L"ca.weblite.webview.passwords:" + base64url(origin) + "|" + base64url(username)`
  (the same namespace prefix used as the `service` on the other platforms,
  followed by the same account encoding). `CredEnumerateW` with filter
  `L"ca.weblite.webview.passwords:*"` backs `find`. The
  `CredentialBlob` holds the UTF-8 (or UTF-16) `millis + "\n" + password`
  bytes; `Persist = CRED_PERSIST_LOCAL_MACHINE` (per-user local; DPAPI
  protects the blob).
- The `WebMessageReceived` callback runs on the WebView2 worker thread; it
  invokes the Java callback synchronously (void, non-blocking — the
  callback hands off to `PasswordDispatcher`, which `invokeLater`s the
  prompt and offloads store I/O), so the worker message pump is not parked
  beyond the immediate hand-off.
- `<input type="file">`-style limitations do not apply here; the password
  channel is a `postMessage`, fully available on WebView2. There is **no**
  WebView2 limitation for this feature (contrast the dialogs feature's
  file-picker carve-out) — all 13 STORY-006-003 ACs are achievable.
- Definition of Done:
  - All 13 STORY-006-003 ACs pass on Windows 11 with the WebView2 Runtime.
  - `WebViewPasswordDemo` (canvas 23) works unchanged on Windows.
  - README's "Password manager" subsection updates coverage to
    all-three-platforms, with the Windows note: the library's own manager
    is used (not Edge's profile), credentials live in the Windows
    Credential Manager (DPAPI-protected, per-user), and Edge autosave is
    disabled.
  - `windows/script/build.bat` (or the Windows build) links `Advapi32`
    for the `Cred*` APIs if not already linked.
- Out of scope (explicit non-goals):
  - macOS — canvas 23. Linux — canvas 24.
  - Any Java or `SHIM_JS` change.
  - Reading / importing Edge's existing saved passwords from the user's
    Edge profile — the library uses its own Credential-Manager namespace;
    it neither reads nor writes Edge's store.
  - Roaming / enterprise credential sync beyond choosing the per-user
    `CRED_PERSIST_LOCAL_MACHINE` flag.
  - The multi-step-login and heap-zeroisation limitations documented in
    canvas 23.

## E · Entities

- **`windows/webview_embed.cc`** (modified). Gains:
  - `Engine::password_callback` `jobject` field (Windows struct) — mirrors
    the dialog callback field; global ref set/cleared like the dialog one
    (`:2376-2381`).
  - `MsgHandler::Invoke` extension: a `__webview_pw__:` prefix branch that
    reads `get_Source` and fires the Java callback.
  - `fire_password_submitted` / `fire_password_fill_requested` (Windows
    JNI fire helpers; mirror the dialog `onAlert`/`onConfirm` GetMethodID
    helpers at `:276`/`:318`).
  - Win32 Credential Manager bodies for the four `webview_cred_store_*`
    primitives (replacing the canvas-23 Windows stubs).
  - JNI bridges for `webview_embed_set_password_callback` (→ store the
    global ref, mirroring the dialog setter at `:2365`) and
    `webview_offscreen_set_password_callback` (no-op — no offscreen engine
    on Windows).
  - At engine creation, the `ICoreWebView2Settings4::put_IsPasswordAutosaveEnabled(FALSE)`
    call, added next to the existing settings block
    (`put_AreDefaultScriptDialogsEnabled(FALSE)`, ~`:1633`).
- **`windows/script/build.bat`** (modified if needed): link `Advapi32.lib`.
- **README.md** (modified): coverage-note update to all three platforms.

No new classes; the class model is canvas 23's.

## A · Approach

1. **Message routing.** The existing `MsgHandler::Invoke` receives every
   `postMessage`. The shared `SHIM_JS` prefixes password messages with
   `__webview_pw__:`. Branch: if the received string begins with
   `__webview_pw__:`, strip the prefix and treat the remainder as the
   `S|...` / `F` payload; otherwise fall through to the existing
   `window.external.invoke` bind dispatch unchanged. (This keeps a single
   `WebMessageReceived` registration; the codebase already demuxes the
   single Windows channel by content.)

2. **Trusted origin.** In the password branch, read the source URL from
   `ICoreWebView2WebMessageReceivedEventArgs::get_Source(&uri)` (a
   `LPWSTR` of the committed document that sent the message), fallback
   `webview->get_Source(&uri)`. Convert to UTF-8 and pass as `frameUrl`
   to the Java callback. Never trust an origin from the payload.

3. **JNI fire helpers** mirror the dialog `onAlert`/`onConfirm` path:
   attach the worker thread to the JVM if needed, per-call `GetMethodID`
   for `onLoginSubmitted(...)V` / `onFillRequested(...)V`, `CallVoidMethod`,
   `ExceptionCheck/Clear`, detach symmetry; no-op if `password_callback`
   is null.

4. **Credential Manager store.**
   - **save**: build `TargetName` = namespace prefix + `base64url(origin)`
     + `L"|"` + `base64url(username)`. Fill a `CREDENTIALW`:
     `Type=CRED_TYPE_GENERIC`, `TargetName`, `CredentialBlob` = bytes of
     `millis + "\n" + password`, `CredentialBlobSize`, `Persist =
     CRED_PERSIST_LOCAL_MACHINE`, `UserName` = the username (informational).
     `CredWriteW(&cred, 0)` upserts (overwrites an existing target),
     satisfying the overwrite contract. Return `CredWriteW` success.
   - **find**: `CredEnumerateW(L"ca.weblite.webview.passwords:*", 0, &count,
     &creds)`; for each, decode `TargetName` after the prefix into
     `base64url(origin)|base64url(username)`, base64url-decode both; if
     origin equals the (canonical) target origin, read the blob, split on
     the first `\n` → millis, password; emit `[username, millis, password]`.
     `CredFree(creds)`. Java sorts. Empty on none.
   - **delete**: reconstruct the exact `TargetName`; `CredDeleteW(target,
     CRED_TYPE_GENERIC, 0)`; return success (`true` only when actually
     deleted).
   - **available**: return `true` (Credential Manager is always present on
     supported Windows).
   All wide-string / UTF-8 conversions via `MultiByteToWideChar` /
   `WideCharToMultiByte`; zero and free the blob buffer after use; never
   `OutputDebugString` a password.

5. **Edge autosave suppression.** At engine creation, after
   `get_Settings`, `QueryInterface(IID_PPV_ARGS(&settings4))`; if it
   succeeds, `settings4->put_IsPasswordAutosaveEnabled(FALSE);
   settings4->Release();`. Guarded so an older Runtime lacking
   `ICoreWebView2Settings4` degrades to "no explicit call" (the default is
   already FALSE). This makes AC10 (no Edge bar) deterministic.

6. **Threading.** The store primitives run on the JNI caller thread — the
   dispatcher's `webview-password-io` executor for automatic paths, the
   caller's thread for the programmatic API. `CredRead`/`CredWrite` are
   fast local calls; they never run on the WebView2 message-pump path (the
   message handler only fires the Java callback, which hands off).

## S · Structure

### Dependencies
1. `MsgHandler::Invoke` (password branch) → `get_Source` →
   `fire_password_submitted` / `fire_password_fill_requested`.
2. `webview_cred_store_*` (Windows bodies) → `Advapi32` `Cred*` APIs.
3. JNI `webview_embed_set_password_callback` → the `Engine::password_callback`
   global ref (the offscreen setter is a no-op).
4. Engine creation → `ICoreWebView2Settings4::put_IsPasswordAutosaveEnabled(FALSE)`.

### Layered Architecture (Windows additions only)
1. **Native engine layer** (`windows/webview_embed.cc`): the password
   branch in `MsgHandler`, the callback setter + fire helpers, the
   Credential-Manager store bodies, and the Edge-autosave-off setting.
2. All higher layers are canvas 23's, unchanged. The Windows wiring in
   `WebViewHeavyweightComponent.createPeer()` (which already injects
   `SHIM_JS` and calls `setPasswordCallback`) now reaches live native code
   instead of a stub.

## O · Operations

### 1. Add the Engine password_callback field + setter
File: `windows/webview_embed.cc`

1. Add `jobject password_callback = nullptr;` to the Windows `Engine`
   struct (next to the dialog callback field).
2. JNI export `Java_..._webview_1embed_1set_1password_1callback(JNIEnv* env,
   jclass, jlong w, jobject cb)`: resolve the engine; delete any prior
   global ref; `e->password_callback = cb ? env->NewGlobalRef(cb) : nullptr;`
   (mirror the dialog setter at `:2365-2381`). Marshal onto the engine's
   worker thread if the codebase requires COM-thread affinity for field
   writes (follow the dialog setter's threading exactly).
3. JNI export `Java_..._webview_1offscreen_1set_1password_1callback`:
   no-op (no offscreen engine on Windows).
4. In the engine destroy path, `DeleteGlobalRef(password_callback)`.

### 2. Extend MsgHandler with the password branch
File: `windows/webview_embed.cc` (`MsgHandler::Invoke`, ~`:489-530`)

1. Obtain the message string (`get_WebMessageAsJson` /
   `TryGetWebMessageAsString` — use whichever the existing handler uses).
2. If it begins with `L"__webview_pw__:"`: strip the prefix; read the
   source URL via `args->get_Source(&sourceUri)` (fallback
   `e->webview->get_Source(&sourceUri)`); convert both payload and URI to
   UTF-8; parse the `S`/`F` tag; call `fire_password_submitted(e, uri,
   b64user, b64pass)` or `fire_password_fill_requested(e, uri)`;
   `CoTaskMemFree(sourceUri)`; return.
3. Otherwise, fall through to the existing bind dispatch unchanged.

### 3. fire helpers
File: `windows/webview_embed.cc`

1. `fire_password_submitted(Engine* e, const char* frameUrl, const char* b64user, const char* b64pass)` and
   `fire_password_fill_requested(Engine* e, const char* frameUrl)`:
   attach the calling thread to the JVM (defensive), per-call
   `GetObjectClass` + `GetMethodID`
   (`onLoginSubmitted(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V`
   / `onFillRequested(Ljava/lang/String;)V`), build UTF-8 jstrings,
   `CallVoidMethod`, `ExceptionCheck`→`Describe`→`Clear`, detach if
   attached; no-op if `password_callback` null. Mirror the dialog
   `onAlert`/`onConfirm` helpers (`:276`, `:318`).

### 4. Implement webview_cred_store_save
File: `windows/webview_embed.cc`

1. Build `TargetName` (wide): `L"ca.weblite.webview.passwords:" + b64url(origin) + L"|" + b64url(username)`.
2. `std::string blob = to_string(savedAtMillis) + "\n" + password;` (UTF-8).
3. `CREDENTIALW cred = {}; cred.Type = CRED_TYPE_GENERIC; cred.TargetName = target; cred.CredentialBlobSize = (DWORD)blob.size(); cred.CredentialBlob = (LPBYTE)blob.data(); cred.Persist = CRED_PERSIST_LOCAL_MACHINE; cred.UserName = usernameW;`
4. `BOOL ok = CredWriteW(&cred, 0);` SecureZero the blob buffer; return
   `ok ? JNI_TRUE : JNI_FALSE`. Never log the blob.

### 5. Implement webview_cred_store_find
File: `windows/webview_embed.cc`

1. `PCREDENTIALW* creds = nullptr; DWORD count = 0; if (!CredEnumerateW(L"ca.weblite.webview.passwords:*", 0, &count, &creds)) return empty jobjectArray;`
2. For each `creds[i]`: parse `TargetName` after the prefix into
   `b64origin|b64username`; base64url-decode → origin, username; if origin
   equals the (canonicalised) target origin, read the blob
   (`CredentialBlob`/`CredentialBlobSize`) as UTF-8, split on first `\n` →
   millis, password; append `username`, `millis`, `password`.
3. `CredFree(creds);` build + return the flat `jobjectArray` (empty on
   none). Java sorts.

### 6. Implement webview_cred_store_delete
File: `windows/webview_embed.cc`

1. Reconstruct the exact wide `TargetName`; `BOOL ok = CredDeleteW(target,
   CRED_TYPE_GENERIC, 0);` return `ok ? JNI_TRUE : JNI_FALSE` (false when
   the target did not exist).

### 7. Implement webview_cred_store_available
1. `return JNI_TRUE;` (Credential Manager always present).

### 8. Disable Edge password autosave
File: `windows/webview_embed.cc` (engine creation settings block, ~`:1623-1635`)

1. After the existing `ICoreWebView2Settings* settings` block, obtain
   `ICoreWebView2Settings4* s4` via `settings->QueryInterface(...)`; if
   `SUCCEEDED`, `s4->put_IsPasswordAutosaveEnabled(FALSE); s4->Release();`.
   Guarded so a Runtime without `ICoreWebView2Settings4` is a no-op.

### 9. Link Advapi32
File: `windows/script/build.bat` (or the Windows link step)

1. Ensure `Advapi32.lib` is linked (provides `CredWriteW` / `CredReadW` /
   `CredEnumerateW` / `CredDeleteW` / `CredFree`). Add if absent.

### 10. README coverage note
1. Update the "Password manager" subsection to all-three-platforms;
   Windows note: library's own manager (not Edge's profile), credentials
   in the Windows Credential Manager (DPAPI, per-user), Edge autosave
   disabled.

## N · Norms

- **No Java / `SHIM_JS` change.** Native-only; the contract is canvas 23's.
- **Single `WebMessageReceived` registration, demuxed by content** — the
  password branch is added inside the existing `MsgHandler`, not a second
  handler, matching the codebase's single-Windows-channel convention.
- **Origin from `get_Source` only** — never the payload. Same invariant as
  the other platforms.
- **Identical store semantics** to macOS/Linux: same namespace, same
  `millis + "\n" + password` encoding, same keying, same most-recent-first
  contract (Java re-sorts).
- **JNI mechanics mirror the dialog helpers** (per-call `GetMethodID`,
  `ExceptionCheck/Clear`, attach/detach symmetry, worker-thread affinity
  for callback-field writes, global-ref lifecycle).
- **COM/Win32 hygiene**: `CredFree` every enumerate result;
  `CoTaskMemFree` every `get_Source` URI; `Release` every QI'd interface;
  SecureZero the blob buffer after use. Never log a password.
- **Edge autosave stays off** from engine creation (deterministic AC10);
  do not toggle it mid-session.

## S · Safeguards

- **Edge's built-in save-password bar never appears** — autosave is set
  FALSE at creation (AC10). The library's Swing prompt is the only save UI.
- **Trusted origin** from `get_Source`; JS-supplied origin ignored.
- **Password never logged / filed.** The Credential Manager (DPAPI) is the
  only persistence; the blob buffer is zeroed after use; no
  `OutputDebugString` of secrets.
- **No worker-pump parking.** The password branch only fires the Java
  callback (hand-off); `Cred*` store I/O runs on the dispatcher's worker.
- **Credential Manager hygiene**: `CRED_TYPE_GENERIC`, namespaced target,
  `CRED_PERSIST_LOCAL_MACHINE`; `CredFree` on every enumerate; exact
  target reconstruction for delete.
- **JNI exception sanitisation** after every `CallVoidMethod`; symmetric
  attach/detach; null-callback short-circuit.
- **Callback global-ref lifecycle** set in the setter, deleted on engine
  destroy; the Java callback anchored in the wrapper `heap` (canvas 23).
- **No behavioural drift.** Java layer, shared JS, and value encoding are
  shared; only the store backend, the message demux branch, and the
  Edge-autosave setting are Windows-specific — all conforming to the
  canvas-23 contract.
- **Graceful QI failure.** A WebView2 Runtime lacking
  `ICoreWebView2Settings4` simply skips the explicit autosave-off call
  (default is already FALSE); no crash, no hard dependency on a Runtime
  version.
