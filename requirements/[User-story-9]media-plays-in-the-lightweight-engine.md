# Story Decomposition: Media Plays In The Lightweight Engine

## INVEST Analysis

### Abstract Task: "A `<video>` In A Lightweight WebViewComponent On Linux Plays"

**Analysis Dimensions**:
- **Core Responsibility**: A page shown in the Linux lightweight engine (the default mode on Linux,
  Canvas 5) can play HTML media — `<video>` and `<audio>` — exactly as the same page does in a
  stock WebKitGTK browser.
- **Primary Operations**: the native engine hosts its `WebKitWebView` in a toplevel that WebKitGTK
  treats as an on-screen window, so WebKit allows media to start; everything else about the
  lightweight engine (pixel pump, synthesized input, sizing) is unchanged.
- **Key Constraints**:
  - Today no media element ever starts in the lightweight engine: no `loadstart`, `networkState`
    stays `NETWORK_NO_SOURCE`, and no `error` fires — from any source (`data:`, `http:`, `blob:`).
    The same page plays in WebKitGTK's `MiniBrowser` on the same machine.
  - Cause, from WebKitGTK 2.52.6's source: a page starts with `setCanStartMedia(false)` and only
    turns it on when the view is `IsInWindow` (`WebPage::updateIsInWindow`); and
    `widgetIsOnscreenToplevelWindow` returns false for a `GtkOffscreenWindow`
    (`UIProcess/gtk/GtkUtilities.cpp`), which is what the lightweight engine uses.
  - The engine must still never show a window to the user: the lightweight mode's whole point is
    that Swing paints the pixels.
- **Technical Complexity**: Low in code (the toplevel's type), with risk in the details — focus,
  input, sizing and pixel capture must keep working.
- **Business Complexity**: Low.

### INVEST Evaluation
- ✅ **Independent**: one engine, one platform.
- ✅ **Negotiable**: how the toplevel stays invisible.
- ✅ **Valuable**: video in chat cards, sites with video, audio notifications — none work today on Linux.
- ✅ **Estimable / Small**: one creation site in `src_c/webview_embed.cpp`, a smoke test.
- ✅ **Testable**: a page can report `loadedmetadata` through `evalAsync`; pixels can be sampled.

**Conclusion**: One story.

---

## [STORY-009-001] HTML media plays in the Linux lightweight engine

### Background
Found while adding inline video to agentic-app-framework's chat file cards: the player never loaded
on Linux. Reproduced in isolation with a 30-line PyGObject WebKit2 4.1 view: the same page inside a
`GtkOffscreenWindow` never starts its video; inside a `GTK_WINDOW_POPUP` moved to (-32000, -32000)
it reaches `canplay`.

### Business Value
- **Apps** embedding the lightweight component can show video and play audio on Linux.

### Scope In
- The Linux lightweight engine's toplevel (fresh engines and adopted popups).
- A smoke test that proves media starts, both from a `data:` URL and from a custom scheme via
  `fetch` → `blob:`, and that the page still reaches Swing as pixels.

### Scope Out
- A custom-scheme URL as a media `src` on Linux: WebKitGTK's GStreamer player only takes `blob`,
  `data`, `file`, `http`, `https` (`isProtocolAllowed`), and its source element only `http`, `https`,
  `blob` — not changeable from here. Pages fetch into a `blob:` instead.
- The heavyweight engine (a real window already), macOS, Windows.
- Windows WebView2 allowed origins for custom schemes (a separate security decision, Canvas 32 AC3).

### Acceptance Criteria

#### AC1: Media starts
**Given** a lightweight `WebViewComponent` on Linux showing a page with a `<video>` whose `src` is a
playable `data:video/webm` URL
**When** the page loads
**Then** the video fires `loadstart` and `loadedmetadata` with its real size and duration.

#### AC2: From the app's own scheme
**Given** a registered custom scheme that serves a WebM file
**When** the page `fetch`es it, makes a `blob:` URL and gives it to a `<video>`
**Then** the video reaches `loadedmetadata`.

#### AC3: Nothing else changes
**Given** the same component
**When** it renders, is resized, and receives synthesized mouse and key input
**Then** Swing paints the page as before, and no window appears on the user's screen.
