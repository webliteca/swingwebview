# Story Decomposition: Decide Camera, Microphone And Screen-Capture Requests

## INVEST Analysis

### Abstract Task: "The Application Answers When A Page Asks For The Camera"

**Analysis Dimensions**:
- **Core Responsibility**: Let an application decide, per request, whether a page it hosts may use the
  camera, the microphone or a screen capture, and make the engine's WebRTC support available where the
  engine can provide it.
  - Today no engine asks the application. WebKitGTK refuses every request when nobody answers it, so on
    Linux a page can never get the camera. WebView2 shows its own prompt. WKWebView shows its own prompt
    (and a host app that lacks the camera/microphone usage descriptions crashes on first access).
- **Primary Operations**:
  - install a permission handler on a component;
  - receive each media-capture request (the requesting origin, the page address, and which devices it
    wants: camera, microphone, screen) and answer allow, deny, or "do what the engine would do";
  - answer later, from any thread, after the application has asked its user or checked its policy;
  - find out whether the loaded native library routes these requests, and (from the page) whether the
    engine has WebRTC at all.
- **Key Constraints**:
  - All three engines: WebKitGTK (Linux, lightweight component), WebView2 (Windows), WKWebView (macOS 12+).
  - The answer may take a person's time, so it must not hold any engine or Swing thread while it waits.
  - Without a handler, each engine behaves exactly as it does today.
  - Older engines that lack a capability (WebKitGTK before 2.42 has no screen-capture request; macOS
    before 12 has no hook) keep working, with the gap documented.
- **Technical Complexity**: High. Three native implementations and an asynchronous answer from Java
  back into each engine.
- **Business Complexity**: Low. One handler, three answers.

### INVEST Evaluation
- ✅ **Independent**: builds on the existing engine wrappers only.
- ✅ **Negotiable**: which facts the request carries; whether WebRTC is switched on by default (it is
  switched on, since it only adds capability a page must still be granted devices for).
- ✅ **Valuable**: video chat and voice features in hosted pages, with the application in control.
- ✅ **Estimable**: one permission hook per engine, following the existing per-engine callback pattern.
- ⚠️ **Small**: about 4 days across three engines. It stays one story, like the navigation decision,
  because the value is one portable decision point.
- ✅ **Testable**: on Linux, a page can ask for the camera against WebKitGTK's mock devices and report
  whether it received a stream or an error.

**Conclusion**: **Ready as-is — one story.**

### Split Strategy
Not applicable — no split needed.

---

## [STORY-011-001] Decide camera, microphone and screen-capture requests

### Background

The Agentic App Framework hosts small multiplayer web apps ("creations") in this library's component.
Its next feature is video chat between the people in a room: each page asks for the camera and
microphone (`getUserMedia`), optionally shares its screen (`getDisplayMedia`), and connects peer to peer
with WebRTC.

None of that can work at the desk today:
- on Linux, WebKitGTK refuses every media request nobody answers, so the page always gets
  `NotAllowedError`;
- on Windows, WebView2 shows its own generic prompt that the application cannot style, pre-answer or
  remember;
- on macOS, WKWebView shows its own prompt, and the application cannot apply its own policy.

The framework needs one portable decision point: the page asks, the application decides (often after
asking its user in its own UI), and the page gets a stream or a clean refusal.

### Business Value
- Provide **application developers** with control over which hosted pages may use the camera,
  microphone and screen, on every platform.
- Support **video and voice features** in hosted pages on Linux, where they are impossible today.
- Enable **the application's own consent UI** and remembered choices instead of engine prompts.

### Dependencies and Assumptions
- **Prerequisites**: none beyond the existing component modes and custom schemes (a page served from a
  registered custom scheme is a secure context, which media capture requires).
- **Data assumptions**: the machine has the devices; tests on Linux use WebKitGTK's mock capture devices.
- **Integration points**: WebKitGTK, WebView2 and WKWebView permission APIs; the operating system's own
  privacy controls still apply on top of the application's answer (macOS camera/microphone consent;
  Windows privacy settings).
- **Business constraints**: macOS host apps must declare camera and microphone usage descriptions, or
  the OS ends the process when a page first uses the device. This must be documented.

### Scope In
- A permission handler on the component, told the origin, the page address and the requested kinds
  (camera, microphone, screen), answering allow, deny or default — immediately or later, from any thread.
- Linux: requests from the lightweight component reach the handler; WebRTC and media streams are
  switched on in the engine; how a page detects whether WebRTC is present is documented.
- Windows: camera and microphone requests reach the handler.
- macOS 12+: camera and microphone requests reach the handler.
- A demo per OS, a Linux smoke test, README documentation of each engine's default and gaps.

### Scope Out
- Geolocation, notifications, clipboard and other permission kinds (left to the engine).
- Choosing which camera or microphone, or which screen, is shared (the engine's picker, if any).
- Remembering answers between runs (the application can do that in its handler).
- Screen capture on Windows (WebView2 1.0.2592 has no permission kind for it) and on macOS (no public
  WKWebView hook); both keep the engine's own behaviour.
- Supplying WebRTC on a Linux distribution whose WebKitGTK was built without it.

### Acceptance Criteria

#### AC1: Allowed camera and microphone request gets a stream
**Given** a component on Linux showing `mediatest://app/` with a handler that allows every request
**When** the page asks for `{video: true, audio: true}`
**Then** the page receives a stream with one video and one audio track, and the handler was told the
origin `mediatest://app` and the kinds camera and microphone.

#### AC2: Denied request is refused cleanly
**Given** the same page with a handler that denies every request
**When** the page asks for the camera
**Then** the page's request fails with `NotAllowedError`, and the handler was told the kind camera only.

#### AC3: Answering later
**Given** a handler that answers allow two seconds after it is asked, from a background thread
**When** the page asks for the microphone
**Then** the page waits and then receives an audio stream; no window freezes while it waits.

#### AC4: No handler keeps today's behaviour
**Given** no handler is installed
**When** the page asks for the camera
**Then** Linux refuses it (`NotAllowedError`), Windows shows WebView2's own prompt, macOS shows WebKit's
own prompt — exactly as before this feature.

#### AC5: Default answer
**Given** a handler that answers "default"
**When** the page asks for the camera
**Then** the outcome is the same as with no handler (AC4).

#### AC6: A failing handler refuses
**Given** a handler that throws
**When** the page asks for the camera
**Then** the request is refused, and the error reaches the application's uncaught-exception handler.

#### AC7: An unanswered request does not hang forever
**Given** a handler that never answers
**When** the component is disposed
**Then** the pending request is refused and nothing crashes.

#### AC8: Only the first answer counts
**Given** a handler that answers allow and then deny
**When** the page asks for the camera
**Then** the page receives a stream; the second answer is ignored.

#### AC9: Feature detection
**Given** any platform
**When** the application asks whether permission handling is supported
**Then** it is told yes with a native built from this feature, no with an older one; and a page can
detect whether the engine provides WebRTC (on Ubuntu 24.04's WebKitGTK 2.52 build it does not, even with
WebRTC switched on), so it can fall back to an audio/video-free experience or an external browser.

#### AC10: Screen capture on Linux
**Given** WebKitGTK 2.42 or newer and a handler that allows
**When** the page calls `getDisplayMedia`
**Then** the handler is told the kind screen.

#### Non-Functional Expectations
- No engine thread or the Swing event thread is blocked while the application decides.
- An answer that arrives after the page or component is gone is ignored safely.
