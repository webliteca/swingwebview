---
generated_at: 2026-08-13T15:45:00-07:00
---

# REASONS Canvas: Password Manager — Linux WebKitGTK + libsecret Coverage

## R · Requirements

- Extend the password manager shipped by canvas 23 (STORY-006-001) to
  Linux, for both `WebViewHeavyweightComponent` (X11-reparented
  WebKitGTK) and `WebViewLightweightComponent` (offscreen WebKitGTK).
  This canvas adds Linux native code only — **no Java or JavaScript
  changes**: the public API (`WebViewCredential`, `WebViewCredentialStore`,
  `WebViewSavePasswordHandler`, `PasswordDispatcher`, the `WebViewComponent`
  methods) and the shared `PasswordDispatcher.SHIM_JS` are reused verbatim
  from canvas 23.
- Two native pieces, both living in `src_c/webview_embed.cpp` under the
  Linux compilation (the same file canvas 23 compiled with non-Apple stub
  bodies):
  1. **Password message channel + capture:** register a WebKitGTK
     script-message handler for name `__webview_pw__` on the engine's
     `WebKitUserContentManager` (`e->manager`) — the same manager the
     `external` console/dialog channel uses — for **both** the heavyweight
     (`gtk_create_engine`) and offscreen (`gtk_off_create_engine`)
     engines. Its `script-message-received::__webview_pw__` handler reads
     the message string body, reads the **committed frame URL natively**
     via `webkit_web_view_get_uri(WEBKIT_WEB_VIEW(e->web))`, parses the
     `S|<b64user>|<b64pass>` / `F` tag, and invokes the Java
     `WebViewPasswordCallback` (`onLoginSubmitted` / `onFillRequested`)
     stored on the engine. Also implement the two callback setters —
     `webview_embed_set_password_callback` (heavyweight) and
     `webview_offscreen_set_password_callback` (offscreen) — replacing the
     canvas-23 Linux stubs, following `gtk_set_dialog_callback`.
  2. **libsecret credential store:** replace the canvas-23 non-Apple stub
     bodies of the four `webview_cred_store_*` primitives with a libsecret
     implementation, matching the macOS Keychain semantics exactly (same
     `service` namespace, same `savedAtMillis + "\n" + password` value
     encoding, same origin/username keying, same most-recent-first
     ordering contract — Java re-sorts regardless).
- **libsecret is loaded at runtime via `dlopen`**, matching the existing
  WebKitGTK runtime-load convention (`webkit_loader.cpp`) rather than
  hard-linking. If `libsecret-1.so.0` is absent, or no Secret Service
  provider answers, the store degrades gracefully:
  `webview_cred_store_available()` returns `false`, `save`/`delete` return
  `false`, `find` returns an empty array — the app never crashes and page
  load / submission still work (STORY-006-002 AC12).
- The `SecretSchema` is defined once (file-static), with attributes
  `service` (string), `origin` (string), `username` (string), all
  `SECRET_SCHEMA_ATTRIBUTE_STRING`, schema name
  `"ca.weblite.webview.passwords"`, flags `SECRET_SCHEMA_NONE`. The secret
  value is the `millis + "\n" + password` string. `find` uses
  `secret_password_searchv_sync` / `secret_service_search_sync` (or the
  simpler `secret_password_lookupv_sync` per-account) to enumerate all
  items whose `service` + `origin` match; the Canvas specifies the
  enumeration approach in Operations.
- All libsecret `*_sync` calls run on whatever thread the JNI primitive is
  invoked on (the Java `NativeCredentialStore` already keeps automatic
  paths off the EDT / engine UI thread via `PasswordDispatcher`'s
  executor; the programmatic API runs on the caller's thread by contract).
  The Secret Service D-Bus round-trip therefore never parks the GTK
  main-loop thread.
- The `__webview_pw__` handler runs on the GTK main thread; it calls the
  Java callback synchronously (void, non-blocking — the callback just
  hands off to `PasswordDispatcher`, which `invokeLater`s the prompt and
  offloads store I/O), so the GTK main loop is not parked.
- Heavyweight and lightweight share the identical handler + store code
  (one registration site per engine, same callback, same store), so the
  two modes produce byte-identical captured field values and identical
  stored/filled values (STORY-006-002 AC11/AC14).
- Definition of Done:
  - All 13 STORY-006-002 ACs pass on Linux (a Secret Service provider
    available for AC1–AC11/AC13; AC12 verified by disabling/removing the
    provider).
  - `WebViewPasswordDemo` (from canvas 23) works unchanged on Linux in
    both modes.
  - README's "Password manager" subsection updates the coverage note:
    Linux now supported (heavyweight + lightweight) via libsecret; still
    pending Windows.
  - `build-linux.sh` gains no hard link to libsecret (runtime `dlopen`);
    a build comment documents the `dlopen("libsecret-1.so.0")` dependency.
- Out of scope (explicit non-goals):
  - Windows coverage (canvas 25).
  - macOS — canvas 23.
  - Any Java or `SHIM_JS` change — the contract is fixed by canvas 23.
  - Shipping or requiring a specific keyring daemon (GNOME Keyring vs
    KWallet). The library targets the freedesktop Secret Service via
    libsecret and works with whichever provider implements it.
  - A library-provided keyring-unlock UI — if the login keyring is locked,
    libsecret / the Secret Service prompts per its own policy.
  - The multi-step-login and heap-zeroisation limitations already
    documented in canvas 23.

## E · Entities

- **`src_c/webview_embed.cpp`** (modified, Linux compilation). Gains:
  - `Engine::password_callback` `jobject` field is already declared by
    canvas 23 (shared struct); Linux now uses it.
  - A file-static `SecretSchema WEBVIEW_PW_SCHEMA` (Linux-guarded).
  - `dlopen`'d libsecret function pointers (file-static), resolved once
    lazily: `secret_password_store_sync`, `secret_password_lookup_sync`,
    `secret_password_clear_sync`, `secret_password_free`, and the search
    API used for enumeration (`secret_service_get_sync` +
    `secret_service_search_sync` + item accessors, OR
    `secret_password_search_sync`/`secret_password_searchv_sync` per the
    available libsecret version) — plus `g_free` / GLib helpers already
    available via the linked GTK stack.
  - `gtk_set_password_callback(Engine*, JNIEnv*, jobject)` — mirrors
    `gtk_set_dialog_callback`.
  - `on_password_message_engine(WebKitUserContentManager*, WebKitJavascriptResult*, gpointer engine)`
    — the `script-message-received::__webview_pw__` handler.
  - `fire_password_submitted` / `fire_password_fill_requested` — the
    JNI fire helpers (shared with macOS in shape; may be defined
    platform-neutrally once and reused).
  - libsecret bodies for `webview_cred_store_save/find/delete/available`
    (replacing the canvas-23 non-Apple stubs).
  - JNI bridges for `webview_embed_set_password_callback` (→
    `gtk_set_password_callback` on the heavyweight engine) and
    `webview_offscreen_set_password_callback` (→ the offscreen engine).
- **`build-linux.sh`** (modified): a comment documenting the runtime
  `dlopen` of `libsecret-1.so.0`; no `-lsecret` hard link.
- **README.md** (modified): coverage-note update.

No new classes; no mermaid diagram change (the class model is canvas 23's).

## A · Approach

1. **Message channel registration (both engines).** In `gtk_create_engine`
   and `gtk_off_create_engine`, next to the existing
   `webkit_user_content_manager_register_script_message_handler(e->manager, "external")`
   and its `script-message-received::external` connect, add:
   - `g_signal_connect(e->manager, "script-message-received::__webview_pw__", G_CALLBACK(on_password_message_engine), e);`
   - `webkit_user_content_manager_register_script_message_handler(e->manager, "__webview_pw__");`
   The shared `SHIM_JS` (injected via `addOnBeforeLoad`) posts to
   `window.webkit.messageHandlers.__webview_pw__.postMessage(...)`, which
   this handler receives.

2. **Reading the message + trusted origin.** `on_password_message_engine`:
   - Extract the JS string from `WebKitJavascriptResult` via
     `webkit_javascript_result_get_js_value` +
     `jsc_value_to_string` (the pattern the console/dialog handlers
     already use for `external`). Free with `g_free`.
   - Read the committed URI: `const gchar* uri = webkit_web_view_get_uri(WEBKIT_WEB_VIEW(e->web));`
     — this is the top document's committed URL, the trusted origin
     source (never the JS payload).
   - Parse: first char `'S'` → split remainder on the first two `'|'`
     into `b64user`, `b64pass` → `fire_password_submitted(e, uri, b64user, b64pass)`;
     first char `'F'` → `fire_password_fill_requested(e, uri)`.

3. **JNI fire helpers** copy the macOS/dialog mechanics: defensive attach,
   per-call `GetObjectClass` + `GetMethodID`, `CallVoidMethod`,
   `ExceptionCheck/Describe/Clear`, symmetric detach; no-op if
   `password_callback` is null. These may be shared with the macOS code
   (defined without Cocoa dependencies) so both platforms call the same
   functions; only the message-handler plumbing differs.

4. **libsecret store — lazy dlopen.** A file-static `bool ensure_secret()`
   `dlopen`s `libsecret-1.so.0` once (`dlopen(..., RTLD_NOW|RTLD_GLOBAL)`),
   resolves the needed symbols with `dlsym`, and caches a
   success/failure flag. Every store primitive calls `ensure_secret()`
   first; on failure it returns the graceful-degradation value.
   `webview_cred_store_available` returns the cached flag AND (optionally)
   a cheap probe that the Secret Service answers.

5. **Store semantics (identical to macOS).**
   - **save**: build the attribute set `{service, origin, username}`;
     `secret_password_store_sync(&WEBVIEW_PW_SCHEMA, SECRET_COLLECTION_DEFAULT,
     label, secretValue, NULL, &error, "service", service, "origin",
     origin, "username", username, NULL)` where `secretValue = millis +
     "\n" + password` and `label = "WebView password for " + origin`.
     `store_sync` upserts on matching attributes, so it satisfies the
     overwrite contract (AC7 of STORY-006-002). Return `error == NULL`.
   - **find**: enumerate all items with `{service, origin}` (username
     unbound) via the search API, read each item's secret + `username`
     attribute, split the secret on the first `\n` into millis + password,
     emit flat `[username, millis, password]` triples. Java re-sorts.
     On no match → empty array.
   - **delete**: `secret_password_clear_sync(&WEBVIEW_PW_SCHEMA, NULL,
     &error, "service", service, "origin", origin, "username", username,
     NULL)` → returns whether a secret was removed.
   - Free every returned secret with `secret_password_free` /
     `g_error_free` / `g_free`; never log the password.

6. **Threading.** The primitives run on the JNI caller thread. For
   automatic paths that is `PasswordDispatcher`'s `webview-password-io`
   executor (off the GTK main loop and off the EDT); for the programmatic
   API it is the caller's thread. The `*_sync` D-Bus round-trip therefore
   never blocks the GTK main-loop thread. The `on_password_message_engine`
   handler itself does no store I/O — it only fires the Java callback,
   which hands off immediately.

7. **Heavyweight/lightweight parity.** Both engines register the same
   handler and share the same store code and the same `password_callback`
   field, so field values and stored/filled values are identical across
   modes (AC11/AC14).

## S · Structure

### Dependencies
1. `on_password_message_engine` → `webkit_web_view_get_uri`,
   `webkit_javascript_result_get_js_value`, `jsc_value_to_string`,
   `fire_password_submitted` / `fire_password_fill_requested`.
2. `webview_cred_store_*` (Linux bodies) → the dlopen'd libsecret symbols
   via `ensure_secret()`.
3. JNI `webview_embed_set_password_callback` / `_offscreen_...` →
   `gtk_set_password_callback` on the respective engine.
4. `gtk_create_engine` / `gtk_off_create_engine` → the new
   `register_script_message_handler("__webview_pw__")` + signal connect.

### Layered Architecture (Linux additions only)
1. **Native engine layer** (`webview_embed.cpp`, Linux): the
   `__webview_pw__` GTK message handler, the callback setter, the fire
   helpers, and the libsecret store bodies.
2. All higher layers (JNI surface, wrappers, dispatcher, component API,
   public contract, wiring, demo) are unchanged from canvas 23 — the Linux
   wiring in `WebViewLightweightComponent.addNotify()` /
   `WebViewHeavyweightComponent.createPeer()` (which already call
   `addOnBeforeLoad(SHIM_JS)` + `setPasswordCallback(...)` from canvas 23)
   now reaches live native code instead of a stub.

## O · Operations

### 1. Define the SecretSchema and dlopen shim
File: `src_c/webview_embed.cpp` (Linux-guarded)

1. File-static `const SecretSchema WEBVIEW_PW_SCHEMA` with name
   `"ca.weblite.webview.passwords"`, `SECRET_SCHEMA_NONE`, attributes
   `"service"`, `"origin"`, `"username"` each
   `SECRET_SCHEMA_ATTRIBUTE_STRING`, terminated by `SECRET_SCHEMA_ATTRIBUTE`
   `NULL`.
2. File-static function pointers for the libsecret symbols used; a
   `static bool g_secret_ok = false; static bool g_secret_tried = false;`
   pair.
3. `static bool ensure_secret()`: if `g_secret_tried` return `g_secret_ok`;
   set tried; `void* h = dlopen("libsecret-1.so.0", RTLD_NOW|RTLD_GLOBAL);`
   if null → `g_secret_ok=false`; else `dlsym` each needed symbol; if any
   missing → false; else true. Cache and return.

### 2. Implement webview_cred_store_available
1. Body: `return ensure_secret() ? JNI_TRUE : JNI_FALSE;` (Linux). Callable
   with no engine.

### 3. Implement webview_cred_store_save
1. `if (!ensure_secret()) return JNI_FALSE;`
2. Convert jstrings (service/origin/username/password) to UTF-8 (`GetStringUTFChars`).
3. Build `std::string value = std::to_string(savedAtMillis) + "\n" + password;`
4. `GError* err = NULL; gboolean ok = secret_password_store_sync(&WEBVIEW_PW_SCHEMA, SECRET_COLLECTION_DEFAULT, label, value.c_str(), NULL, &err, "service", service, "origin", origin, "username", username, NULL);`
5. Release jstrings; if `err` → `g_error_free(err)`, return `JNI_FALSE`;
   return `ok ? JNI_TRUE : JNI_FALSE`. Never log `value`/`password`.

### 4. Implement webview_cred_store_find
1. `if (!ensure_secret()) return empty jobjectArray;`
2. Enumerate items matching `{service, origin}` (username unbound) using
   the resolved search API; for each: read the `username` attribute and
   the secret string; split the secret on the first `\n` → `millis`,
   `password`; append `username`, `millis`, `password` to a
   `std::vector<std::string>`.
3. Build a `jobjectArray` (`java/lang/String`) of the flat triples; free
   all libsecret allocations (`secret_password_free`, list frees).
   Return the array (empty on no match / error). Java sorts + builds
   credentials.

### 5. Implement webview_cred_store_delete
1. `if (!ensure_secret()) return JNI_FALSE;`
2. `gboolean removed = secret_password_clear_sync(&WEBVIEW_PW_SCHEMA, NULL, &err, "service", service, "origin", origin, "username", username, NULL);`
3. Free jstrings; on `err` → free + `JNI_FALSE`; return
   `removed ? JNI_TRUE : JNI_FALSE`.

### 6. Implement the password message handler + callback setter
File: `src_c/webview_embed.cpp` (Linux)

1. `on_password_message_engine(WebKitUserContentManager* m, WebKitJavascriptResult* r, gpointer data)`:
   - `Engine* e = (Engine*)data;`
   - Extract JS string (as the `external` handler does); if null → return.
   - `const gchar* uri = webkit_web_view_get_uri(WEBKIT_WEB_VIEW(e->web));`
     (may be null → treat as empty; Java's `Origins.canonical` drops it).
   - Parse tag `S`/`F`; call `fire_password_submitted(e, uri, b64user, b64pass)`
     or `fire_password_fill_requested(e, uri)`.
   - `g_free` the extracted string.
2. `gtk_set_password_callback(Engine* e, JNIEnv* env, jobject cb)`: delete
   prior global ref; `e->password_callback = cb ? env->NewGlobalRef(cb) : NULL;`.
3. In `gtk_create_engine` and `gtk_off_create_engine`, after the existing
   `external` handler registration: connect + register the
   `__webview_pw__` handler as in Approach §1.
4. JNI bridges: `Java_..._webview_1embed_1set_1password_1callback` →
   `gtk_set_password_callback(engine_from_long(w), env, cb)`;
   `Java_..._webview_1offscreen_1set_1password_1callback` → the offscreen
   engine variant. Replace the canvas-23 Linux stubs.
5. Teardown: on engine destroy, `DeleteGlobalRef(password_callback)`
   (add to the existing Linux destroy path next to the dialog callback
   cleanup).

### 7. fire helpers (shared)
1. If canvas 23 defined `fire_password_submitted` /
   `fire_password_fill_requested` without Cocoa dependencies, reuse them
   directly. Otherwise define the Linux copies with identical JNI
   mechanics (per-call `GetMethodID` for
   `onLoginSubmitted(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V`
   / `onFillRequested(Ljava/lang/String;)V`, `ExceptionCheck/Clear`,
   attach/detach symmetry, null-callback short-circuit).

### 8. build-linux.sh comment
1. Add a comment noting the runtime `dlopen("libsecret-1.so.0")`
   dependency and that no `-lsecret` link is added (matching the WebKitGTK
   runtime-load convention). No functional flag change.

### 9. README coverage note
1. Update the "Password manager" subsection: Linux supported in both
   heavyweight and lightweight via libsecret (freedesktop Secret Service);
   graceful no-op when no provider is available; Windows still pending.

## N · Norms

- **No Java / `SHIM_JS` change.** This canvas is native-only; the contract
  and script are canvas 23's. If a change here would require touching
  Java, stop — it belongs in a canvas-23 amendment, not here.
- **Origin from `webkit_web_view_get_uri` only** — never from the JS
  payload. Same invariant as macOS.
- **Identical store semantics to macOS**: same `service` namespace, same
  `millis + "\n" + password` value encoding, same keying, same
  most-recent-first contract (Java re-sorts). A Linux-stored credential
  and a macOS-stored credential differ only in the backing store, never in
  observed Java behaviour.
- **Runtime `dlopen` for libsecret**, matching `webkit_loader.cpp`; no
  hard link. Absence degrades gracefully, never fails library load.
- **`*_sync` calls off the GTK main loop.** They run on the JNI caller
  thread (the dispatcher's executor for automatic paths). The GTK message
  handler does no store I/O.
- **GLib/libsecret memory hygiene**: free every `GError`, secret string,
  and search-result list; use `secret_password_free` for secrets. Never
  log a secret.
- **JNI mechanics mirror the dialog/macOS helpers** (per-call
  `GetMethodID`, `ExceptionCheck/Clear`, attach/detach symmetry,
  global-ref lifecycle).
- **Heavyweight and lightweight share one code path** — one handler, one
  store, one callback field; no mode-specific divergence.

## S · Safeguards

- **Graceful degradation (STORY-006-002 AC12).** No libsecret / no Secret
  Service ⇒ `available()` false, `save`/`delete` false, `find` empty; page
  load and form submission continue to work; no crash, no thrown JNI
  exception.
- **Trusted origin** stamped from `webkit_web_view_get_uri`; JS-supplied
  origin ignored — the anti-cross-origin invariant holds on Linux too.
- **Password never logged / filed.** libsecret is the only persistence;
  no `g_print` / stderr of secrets; the value blob is freed after use.
- **No GTK-main-loop parking.** The `__webview_pw__` handler only fires
  the Java callback (which hands off); D-Bus `*_sync` store I/O runs on
  the dispatcher's worker, not the GTK thread.
- **Parity (AC11/AC14).** Heavyweight and lightweight use identical native
  code and therefore produce identical captured/stored/filled values.
- **Memory safety.** Every libsecret / GLib allocation is freed on every
  path; jstring `GetStringUTFChars` are released; the `jobjectArray` local
  refs are managed within the JNI frame.
- **JNI exception sanitisation** after every `CallVoidMethod`; symmetric
  attach/detach; null-callback short-circuit — identical to the dialog
  path.
- **Callback global-ref lifecycle** set in `gtk_set_password_callback`,
  deleted on engine destroy; the Java callback anchored in the wrapper's
  `heap` (canvas 23).
- **No behavioural drift from macOS** — because the Java layer, the shared
  JS, and the value encoding are shared, the only Linux-specific surface
  is the store backend and the message-handler plumbing; both conform to
  the canvas-23 contract.
