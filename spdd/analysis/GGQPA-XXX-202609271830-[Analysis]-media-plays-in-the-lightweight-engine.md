# SPDD Analysis: Media Plays In The Lightweight Engine

## Original Business Requirement

See `requirements/[User-story-9]media-plays-in-the-lightweight-engine.md` → **[STORY-009-001]**.

## Domain Concept Identification

### Existing Concepts (from codebase)
- **Lightweight engine** (`OffEngine`, `gtk_off_create_engine` in `src_c/webview_embed.cpp`, Canvas 7):
  `e->window = gtk_offscreen_window_new()`, the `WebKitWebView` added to it, `gtk_widget_show_all`,
  a synthetic `GDK_FOCUS_CHANGE` so WebKit thinks it has focus. Pixels are taken with
  `gtk_widget_draw(e->window, cr)` into an image surface we own (`gtk_off_snapshot_into`) — it does
  not use `gtk_offscreen_window_get_surface`. Input is synthesized with `gtk_main_do_event`. Resizes
  call `gtk_window_resize`. Popup adoption (Canvas 19) reuses the same creation path.
- **GTK backend**: the pump thread calls `gdk_set_allowed_backends("x11")` before `gtk_init`, so the
  engine always runs on an X11 display (XWayland under Wayland).
- **Custom schemes on Linux** (Canvas 31): registered on the default context, marked secure and
  CORS-enabled, so `fetch` of a scheme URL works.

### New Concepts Required
- **Hidden on-screen toplevel**: a `GTK_WINDOW_POPUP` (override-redirect on X11, so no window
  manager decoration, taskbar entry or focus) placed far outside every monitor, which WebKitGTK counts
  as an on-screen window.

### Key Business Rules
- The user never sees the engine's window.
- Media is allowed to start exactly as in a browser.

## Strategic Approach

### Solution Direction
- Replace `gtk_offscreen_window_new()` with `gtk_window_new(GTK_WINDOW_POPUP)`, then before it is
  shown: `gtk_window_move(-32000, -32000)`, `gtk_window_set_accept_focus(FALSE)`,
  `gtk_window_set_focus_on_map(FALSE)`, `gtk_window_set_skip_taskbar_hint(TRUE)`,
  `gtk_window_set_skip_pager_hint(TRUE)`, `gtk_window_set_decorated(FALSE)`. Everything after
  (show, synthetic focus, snapshot, input, resize) is unchanged.
- Verified in isolation before this change: a WebKit2 4.1 view in such a popup plays a `blob:` video;
  in a `GtkOffscreenWindow` it never loads.

### Key Design Decisions
- **Popup, not a normal toplevel**: override-redirect bypasses the window manager — no decoration,
  no taskbar, no focus stealing, and the position is honoured exactly.
- **Off-screen by position**: X11 accepts negative coordinates for override-redirect windows; -32000
  is outside any realistic monitor layout (X11 coordinates are 16-bit signed).
- **No change to pixel capture**: `gtk_widget_draw` draws the widget tree into our surface for any
  GtkWindow; a mapped window's own on-screen contents are irrelevant.

### Alternatives Considered
- **Keep the offscreen window and force `canStartMedia`**: no public WebKitGTK API; a private one would
  break across versions.
- **`WEBKIT_GST_ALLOWED_URI_PROTOCOLS`**: only relaxes the first URL check; the media still never
  starts in an offscreen window, and custom schemes still fail at the source element.

## Risk & Gap Analysis

### Technical Risks
- **Throttling**: WebKit may treat a mapped but uncovered window as visible and render at full rate;
  the engine already drives rendering on demand, so this is expected to be neutral.
- **Compositors / XWayland**: an override-redirect window at negative coordinates is off every
  output; verified under Xvfb only.
- **Input and focus**: unchanged code paths (`gtk_main_do_event`, synthetic focus), but not
  exercised by the smoke test; run the existing interactive demos (`run-linux-demo.sh`) to check
  typing, clicking and scrolling by hand.

### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Media starts | Yes | smoke test |
| 2 | From the app's scheme | Yes | smoke test |
| 3 | Nothing else changes | Partly | smoke test samples pixels; input by hand with the demos |
