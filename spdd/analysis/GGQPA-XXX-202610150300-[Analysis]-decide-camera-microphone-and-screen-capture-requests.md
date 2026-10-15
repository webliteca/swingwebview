# Analysis: Decide Camera, Microphone And Screen-Capture Requests (STORY-011-001)

> Story: `requirements/[User-story-11]decide-camera-microphone-and-screen-capture-requests.md`
> (the original requirement, unmodified, is that file).
> Evidence: a WebKitGTK 2.52.6 spike (Python/GI probes: a secure custom scheme is a secure context; getUserMedia is refused with no `permission-request` handler and succeeds with one; `RTCPeerConnection` stays undefined with `enable-webrtc` on) and the framework's
> video-chat brief (Part 6, "swingwebview").

## 1. Business context

The Agentic App Framework is adding video chat to its hosted "creations". A creation is served from a
registered custom scheme (a secure context), calls `getUserMedia` / `getDisplayMedia`, and talks
WebRTC to its peers. At the desk this fails or is out of the application's hands on every engine: Linux
always refuses, Windows and macOS show engine prompts the application cannot control. The library has
to offer one portable decision point, answerable after the application has asked its user, and switch
WebRTC on where the engine has it.

## 2. Domain concepts

| Concept | Meaning here |
|---|---|
| **Media-capture request** | A page asking for devices: camera, microphone, or a screen/window capture. One request may ask for several kinds (WebKit: camera+microphone together; WebView2: one kind per request). |
| **Kind** | `CAMERA`, `MICROPHONE`, `DISPLAY`. |
| **Origin** | The security origin asking (`scheme://host[:port]`). WKWebView reports it; WebView2 reports the requesting frame's URL; WebKitGTK reports neither, so the top page's address stands in. |
| **Decision** | `ALLOW`, `DENY`, or `DEFAULT` — "do what this engine does with nobody listening". |
| **Pending request** | A request the application has not answered yet. The engine holds the page's promise open; the application may answer from any thread, once. |
| **WebRTC availability** | Whether the engine build has `RTCPeerConnection` at all. A property of the engine build, observable only from a page. |

**Existing concepts reused:**
- **Per-component dispatcher + JNI callback + global ref set at attach** (popups, navigation, downloads:
  `NavigationDispatcher`, `WebViewNavigationCallback`, `webview_*_set_navigation_callback`).
- **Asynchronous answer by id from any thread** (custom schemes, Canvas 30–32: `SchemeDispatcher` keeps
  a pending map; native keeps the engine's request object by id; `webview_scheme_respond(id, …)`
  marshals the answer to the engine thread; first answer wins).
- **Deferral patterns** already used per engine: WebView2 `GetDeferral` + `post_to_worker_thread`
  (scheme, dialogs, downloads); WKWebView block `copy` + `dispatch_async` main (dialogs); GTK
  `g_object_ref` + `GtkPump::run_async`.
- **Runtime loader** (`webkit_loader.h` X-macros, optional groups gated by `WEBKIT_CHECK_VERSION` and
  `WK_HAS`, Canvas 31 D5) for any new WebKitGTK symbol.
- **Origin canonicalisation** (`Origins.canonical`).

**New concepts:** the permission handler, the request carrier with its one-shot answer, the kind and
decision enums, the JNI-facing permission callback, and a process-wide pending-request table per native
backend.

**Key business rules:**
- No handler → no change in behaviour, on every engine.
- `DEFAULT` → the same outcome as no handler.
- First answer wins; later answers are ignored.
- A handler that throws refuses; a disposed component refuses its pending requests.
- Nothing blocks an engine thread or the EDT while the application decides.

## 3. Strategic approach

### Solution direction
Native permission hook (per engine) → JNI callback on the engine thread → Java dispatcher. The
dispatcher answers `DEFAULT` synchronously when no handler is installed (native then leaves the engine
untouched — the exact pre-feature path); otherwise it records the request as pending, returns "pending"
to native at once, and runs the handler on the EDT with `invokeLater` (the library's convention for
asynchronous, user-facing callbacks: `evalAsync` continuations, download notifications). The handler
answers through the request object, now or later, from any thread; the answer goes to a static native
`respond(id, decision)` which marshals to the engine thread and completes the engine's request.

### Key design decisions
- **Handler shape: a request object with `allow()/deny()/useDefault()`**, like
  `WebViewSchemeResponder`, rather than a returned future → one answering idiom across the library;
  works equally for "answer now" and "answer after a dialog". A `CompletionStage` adapter is easy for
  callers to write and isn't needed in the API.
- **Handler runs on the EDT (invokeLater)** → applications typically show a Swing consent dialog. Unlike
  the synchronous dialog/popup/navigation hooks, nothing waits on it, so there's no deadlock hazard.
- **Synchronous fast path for "no handler"** → keeps today's behaviour byte-for-byte; in particular
  WKWebView's and WebView2's own prompts and WebKitGTK's own denial.
- **`DEFAULT` after pending** → GTK: deny (what WebKitGTK's default handler does once the signal is
  claimed); WebView2: leave `State` at `DEFAULT` and complete the deferral (WebView2 then applies its own
  default — its prompt); WKWebView: `WKPermissionDecisionPrompt`.
- **Linux settings**: switch on `enable-media-stream` and `enable-webrtc` on every GTK view, by GObject
  property lookup (`g_object_class_find_property`) so older WebKitGTK without a property simply skips
  it. WebRTC can't be detected from settings (the spike shows `enable-webrtc` reads back TRUE on a build
  without WebRTC), so detection is documented as a page check (`typeof RTCPeerConnection`).
- **Mock capture devices for tests**: `enable-mock-capture-devices`, switched on only when the
  environment variable `WEBVIEW_MOCK_CAPTURE_DEVICES=1` is set — a test hook, not API.
- **Screen capture**: Linux through `webkit_user_media_permission_is_for_display_device` (2.42+,
  optional). Windows (SDK 1.0.2592.51 has no permission kind for it) and macOS (no public hook) keep the
  engine's own behaviour; documented.
- **Release version**: no Canvas or norm ties a feature to a version bump (`pom.xml` stays
  `1.0-SNAPSHOT`); none is made.

### Alternatives considered
- **Synchronous boolean on the engine thread** (the navigation pattern) — rejected: a consent prompt
  needs a person; blocking the GTK/AppKit/WebView2 thread for that freezes every view, and on macOS
  deadlocks with the EDT.
- **Handler returns `CompletableFuture<Decision>`** — workable, but a null/never-completed future is
  easier to get wrong than a responder object, and it diverges from the scheme responder.
- **A native "is WebRTC available" probe** — no cheap, reliable native signal exists (WebKitGTK exports
  nothing WebRTC-specific; the setting reads back TRUE regardless). Rejected for a documented page check.

## 4. Risk & gap analysis

### Requirement ambiguities
- **Origin on Linux** is the top page's origin, not the requesting frame's — WebKitGTK's
  `WebKitUserMediaPermissionRequest` exposes no origin. Documented.
- **Display + audio**: `getDisplayMedia({audio:true})` asks for system audio, not the microphone; the
  request is reported as `DISPLAY` only.

### Edge cases
- The page navigates away while a request is pending → the engine cancels its own request; a late answer
  must be harmless (GTK: answering a stale request is a no-op in WebKit; WebView2/WKWebView: the
  deferral/block is still valid to complete).
- Component disposed while pending → Java refuses every pending request; native drains its table for
  that engine before the view is destroyed.
- An adopted popup → served by the adopting engine's callback (GTK adoption reuses the engine creation
  function; Windows adoption must register the handler on the child too).
- A handler answering twice, or answering `null` → first wins; `null` is treated as `DENY`.

### Technical risks
- **Lightweight engine on Linux** renders into a hidden `GTK_WINDOW_POPUP` (Canvas 33). The spike saw
  `getUserMedia` hang in a `GtkOffscreenWindow`; the popup window counts as "in a window", which should
  let capture start. Must be verified by the smoke test.
- **WebKitGTK type checks**: `WEBKIT_IS_USER_MEDIA_PERMISSION_REQUEST` references
  `webkit_user_media_permission_request_get_type`, which must come through the loader (optional), never
  as a link-time reference.
- **Windows and macOS are untestable here**: written against SDK headers and Apple docs; only compiled
  by CI. WebView2's `put_State(DEFAULT)` after a deferral is documented to fall back to the default
  behaviour (a prompt) — needs on-device confirmation.
- **macOS Info.plist**: a host app without `NSCameraUsageDescription` / `NSMicrophoneUsageDescription`
  is killed by TCC on first access. Documentation only; the library cannot prevent it.
- **CI Linux headers are WebKitGTK 2.44 (Debian 11 container)**: display capture (2.42) compiles in;
  older headers compile it out through the version gate.

### Acceptance-criteria coverage
| AC# | Description | Addressable? | Notes |
|---|---|---|---|
| 1 | Allowed → stream, kinds + origin reported | Yes | Linux smoke test with mock devices. |
| 2 | Denied → `NotAllowedError` | Yes | Smoke test. |
| 3 | Answer later from another thread | Yes | Smoke test answers after a delay from a timer thread. |
| 4 | No handler → engine default | Yes | Linux verified (denial); Windows/macOS by construction, untested. |
| 5 | `DEFAULT` = no handler | Yes | Linux verified. |
| 6 | Throwing handler refuses | Yes | Unit test of the dispatcher. |
| 7 | Dispose refuses pending | Yes | Unit test + native drain. |
| 8 | First answer wins | Yes | Unit test. |
| 9 | Feature detection | Yes | `isPermissionHandlerSupported()`; WebRTC by page check. |
| 10 | Display capture on Linux | Partial | Routed when WebKitGTK ≥ 2.42 raises it; whether Xvfb + mock devices raise a display request is environment-dependent, checked best-effort by the smoke test. |
