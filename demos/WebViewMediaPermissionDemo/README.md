# WebViewMediaPermissionDemo

Camera, microphone and screen-capture requests decided by the application (Canvas 35).

```bash
./run-linux-media-permission-demo.sh          # Linux (lightweight)
MOCK=1 ./run-linux-media-permission-demo.sh   # Linux, WebKitGTK's mock devices (no camera needed)
./run-mac-media-permission-demo.sh            # macOS 12+ (heavyweight), from a minimal .app
./run-mac-media-permission-demo.sh --no-bundle   # macOS, plain java: camera/mic denied, no crash
run-windows-media-permission-demo.bat         # Windows (heavyweight, WebView2)
```

The page is served from `demo://app/`, a registered custom scheme, so it is a secure context (media
capture needs one). Its buttons call `getUserMedia` for the camera, the microphone or both, and
`getDisplayMedia` to share the screen. It shows the stream, or the error name on refusal, and whether
the engine has WebRTC (`typeof RTCPeerConnection === 'function'`).

The **Policy** box chooses what the `WebViewPermissionHandler` does:

| Policy | Result |
|---|---|
| Ask me (dialog) | a Swing dialog on the EDT asks you; the request is answered when you choose |
| Always allow / Always deny | answered at once |
| Engine default | `useDefault()`: Linux refuses; Windows and macOS show the engine's own prompt |
| No handler | `setPermissionHandler(null)`: the request never reaches the application |

The log shows each request's origin, kinds and address, and the thread the handler ran on (the EDT).

## What to check

1. **Allow** gives a live stream; **Deny** gives `NotAllowedError`.
2. Leaving the dialog open doesn't freeze the page or the window; the page's promise waits.
3. **Engine default** and **No handler** behave the same.
4. On Linux, **Share screen** reaches the handler as `[DISPLAY]` with WebKitGTK 2.42+. On Windows and
   macOS it does not reach the handler: WebView2's and WebKit's own pickers decide it.
5. On macOS with `--no-bundle`, **Camera** gives `NotAllowedError` and the stderr line
   `swingwebview: camera access denied — …`, without reaching the handler, and the app keeps running.

## Platform notes

- **macOS** needs 12 or newer. Host-app checklist:
  - [ ] **Info.plist keys.** The bundle whose executable runs the JVM must declare
    `NSCameraUsageDescription` and `NSMicrophoneUsageDescription`. With jpackage, pass an `Info.plist`
    holding both through `--resource-dir`; with jDeploy, make sure the generated bundle's Info.plist
    carries both keys (see the jDeploy documentation for how); a notarized app with the
    hardened runtime also needs the `com.apple.security.device.camera` and
    `com.apple.security.device.audio-input` entitlements.
  - [ ] **Without them: a clean deny, not a crash.** macOS kills a process that opens the camera or
    microphone without the key (`SIGABRT`, "Namespace TCC"). The library therefore denies such a
    request itself — handler or not, without asking the handler — the page gets `NotAllowedError`, and
    stderr shows `swingwebview: camera access denied — this app's Info.plist has no
    NSCameraUsageDescription (macOS would terminate the app)`. The demo also logs
    `WebViewComponent.missingMacUsageDescriptions()` at start.
  - [ ] **Screen sharing needs no key** (macOS's Screen Recording permission has none and doesn't kill
    the app); it is allowed in System Settings › Privacy & Security › Screen & System Audio Recording.
  - [ ] **Private preferences.** The library turns on WebKit's private `_mediaDevicesEnabled` /
    `_mediaStreamEnabled` / `_screenCaptureEnabled` preferences (where the WebKit has them) so that
    `navigator.mediaDevices` exists in the embedded view; without them the page reports
    `TypeError … evaluating 'md.getUserMedia'`. These are private WebKit API: fine outside the Mac App
    Store, but App Store review may object.

  **How the script runs it.** A plain `java` has no usage descriptions, and `NSBundle mainBundle`
  follows the executable, so by default the script builds `build/WebViewMediaPermissionDemo.app`: an
  `Info.plist` with both keys and a small launcher (`mac/launcher.c`, built with `clang`, the same
  plist embedded in its `__TEXT,__info_plist`) that runs the JVM *in its own process* through the
  JDK's `JLI_Launch`, as the `java` command does. It needs the Xcode command line tools
  (`xcode-select --install`) and a JDK in `JAVA_HOME`. `--no-bundle` runs a plain `java` to show the
  safeguard: Camera is denied with the stderr line above instead of crashing.
  `--print-info-plist` prints the bundle's Info.plist. macOS's own one-time consent prompt still
  appears on top of the handler's answer (it may name your terminal).
- **Windows**: camera and microphone only. Windows' own privacy settings still apply.
- **Linux**: the lightweight component is the only Linux mode. Ubuntu 24.04's WebKitGTK is built
  without WebRTC, so `RTCPeerConnection` is missing there even though capture works.
