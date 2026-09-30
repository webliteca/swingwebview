# WebViewMediaSmokeTest

A pass/fail check that HTML media plays in the Linux **lightweight** engine (Canvas 33).

```bash
xvfb-run -a ./run-linux-media-smoketest.sh     # or run it on a desktop
```

It opens a lightweight `WebViewComponent` on a page served from a registered custom scheme
(`mediatest://app/`) and prints one line per check, then exits `0` if all pass, `1` otherwise:

| Check | What it proves |
|---|---|
| **A** a `<video>` with a `data:video/webm` source reaches `loadedmetadata 16x16` | media starts in the engine at all |
| **B** a `<video>` playing a `blob:` URL made by `fetch('mediatest://app/clip.webm')` reaches `loadedmetadata 16x16` | an app can play media it serves from its own scheme |
| **P** the component, painted into an image, shows the page's red background | the pixel pump still delivers the page |

Why **B** goes through a blob: on Linux, WebKitGTK's media player only accepts `blob`, `data`, `file`,
`http` and `https` sources, so a custom-scheme URL can never be a media `src` there. `fetch` of the
scheme works, so pages fetch into a blob and play that.

Before Canvas 33 the engine hosted its view in a `GtkOffscreenWindow`, which WebKitGTK never treats as
"in a window" — and a page only starts media once it is. Checks A and B failed (no `loadstart`, no
error); P passed.

Needs the GStreamer VP8 decoder (`gstreamer1.0-plugins-good`, installed with WebKitGTK on most
distributions).
