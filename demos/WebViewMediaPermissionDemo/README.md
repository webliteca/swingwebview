# WebViewMediaPermissionDemo

Camera, microphone and screen-capture requests decided by the application (Canvas 35).

```bash
./run-linux-media-permission-demo.sh          # Linux (lightweight)
MOCK=1 ./run-linux-media-permission-demo.sh   # Linux, WebKitGTK's mock devices (no camera needed)
./run-mac-media-permission-demo.sh            # macOS 12+ (heavyweight)
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

## Platform notes

- **macOS** needs 12 or newer. The process that uses the camera must declare
  `NSCameraUsageDescription` and `NSMicrophoneUsageDescription` in its Info.plist, or macOS terminates
  it on first access. This script runs a plain `java`, so macOS attributes the request to your terminal
  or JDK; a packaged application must declare both keys itself. macOS's own one-time consent prompt
  still appears on top of the handler's answer.
  The library turns on WebKit's private `_mediaDevicesEnabled` / `_mediaStreamEnabled` /
  `_screenCaptureEnabled` preferences (where the WebKit has them) so that `navigator.mediaDevices`
  exists in the embedded view; without them the page reports
  `TypeError … evaluating 'md.getUserMedia'`. A host missing the two Info.plist keys gets
  `NotAllowedError` from WebKit, or is terminated by macOS. These are private WebKit API: fine
  outside the Mac App Store, but App Store review may object.
- **Windows**: camera and microphone only. Windows' own privacy settings still apply.
- **Linux**: the lightweight component is the only Linux mode. Ubuntu 24.04's WebKitGTK is built
  without WebRTC, so `RTCPeerConnection` is missing there even though capture works.
