# Analysis: Add Application Items To The Native Context Menu (STORY-012-001)

> Story: `requirements/[User-story-12]add-application-items-to-the-native-context-menu.md`
> (the original requirement, unmodified, is that file).
> Evidence: a WebKitGTK 2.52.6 spike under Xvfb (a C probe that parks a `WebKitWebView` in a popup
> window at −32000,−32000 exactly as the lightweight engine does, synthesizes a right-click, appends a
> `GAction` item from the `context-menu` signal, and repositions the menu from a `popped-up` emission
> hook), plus a run of today's lightweight component under Xvfb with a scripted right-click.

## 1. Business context

The Agentic App Framework wants an **Ask** item when the user right-clicks a text selection in a hosted
page, handing the selected text (and where it is) to the framework's assistant. The library today
offers only the Canvas 9 *replace* model: a `WebViewMouseListener` suppresses the engine's menu and the
application shows a Swing `JPopupMenu`. That loses every engine item (Copy, Look Up, spelling
suggestions, Services, Inspect Element), which an application cannot rebuild. The new capability is an
*augment* model: the engine's own menu still appears, with the application's items appended.

The spike also exposed a defect in the only Linux mode: the lightweight engine's view lives in a
`GTK_WINDOW_POPUP` parked at −32000,−32000 (Canvas 33 D2), so WebKitGTK anchors its `GtkMenu` there and
GTK slides it into the nearest monitor — the menu opens at the **top-left corner of the screen**,
not under the pointer. The menu itself works (it is a real override-redirect X window with a grab), so
fixing its position, not replacing it, is the right fix.

## 2. Domain concepts

| Concept | Meaning here |
|---|---|
| **Contributor** | Application code registered on a component, asked on every engine menu for zero or more items. |
| **Contributed item** | A caller-chosen id, a plain-text label, enabled or not, and the action to run when chosen. |
| **Menu context** | What the right-click hit: selected text, link URL, image URL, media URL, editable or not, the click point in component coordinates, the selection's rectangle, and (when available) the Canvas 9 `DomTarget`. |
| **Shown menu** | One engine menu instance. Identified by a token; only items of the newest shown menu can fire, each at most once. |
| **Engine menu** | The engine's own menu: `WebKitContextMenu` (GTK), the `NSMenu` WKWebView opens, WebView2's `ContextMenuRequested` item collection. |

**Existing concepts reused:**
- **Canvas 9 right-click channel**: the `__webview_dom_event` shim fires on every DOM `contextmenu`
  (including keyboard-initiated ones) before the engine builds its menu, and posts a record with the
  selection text, link, image, media, editable flag and client point. It is posted on the same
  web-process → UI-process connection as the menu request, so it reaches Java first. It is the only
  source of the selection text on WebKitGTK and of almost everything on WKWebView.
- **Synchronous native-thread decision** (popups D1, navigation Canvas 34 D1): a Java callback asked on
  the engine's UI thread, off the EDT, which must be fast and must not touch Swing.
- **Per-component dispatcher + JNI callback + global ref installed at attach** (`NavigationDispatcher`,
  `WebViewNavigationCallback`, `webview_{embed,offscreen}_set_navigation_callback`,
  `webview_navigation_available` as the support probe).
- **Runtime WebKit loader** (`webkit_loader.h` X-macro groups, optional groups behind `WK_HAS`).
- **Class-wide WKWebView swizzles keyed by `g_webview_map`** (click and focus swizzles).
- **WebView2 `CallbackBase` handlers registered beside `add_NavigationStarting`.**

## 3. Strategic approach

### Solution direction
- One public contributor API on `WebViewComponent`, one internal per-component dispatcher, one JNI
  callback interface (asked for items; told which item was chosen).
- Each engine appends a separator and the contributed items to **its own** menu object, so every native
  item, submenu and behaviour stays the engine's.
- The click description combines what the engine reports natively (authoritative where it exists:
  WebKitGTK's hit test, WebView2's `ContextMenuTarget`) with the freshest Canvas 9 shim record (selection
  text on GTK, everything on WKWebView, the click point and the selection rectangle everywhere).
- Activation travels only native-menu → JNI → dispatcher, never through a JavaScript channel, and is
  matched against the token of the newest shown menu.
- Linux lightweight: keep WebKitGTK's own `GtkMenu` and move it under the pointer once GTK has placed
  it, using the component's screen origin reported from Swing.

### Key design decisions
- **Augment the engine menu rather than mirror it in Swing.** Mirroring native items in a `JPopupMenu`
  would need every item's label, state and activation, and WebKitGTK only exposes those through
  deprecated `GtkAction`s whose lifetime ends with the native menu proxy; spelling and input-method
  submenus would be lost. → Augment, on every engine.
- **Contributor runs synchronously on the engine thread.** Each engine needs the items before its menu
  opens; a hop to the EDT and back risks the EDT ↔ AppKit deadlock the repo has already had to remove,
  and needs a timeout policy. → Synchronous, off the EDT, fast, no Swing (the popup/navigation
  contract). The chosen item's action runs on the EDT, asynchronously.
- **Correlate with the Canvas 9 record by recency, consuming it.** The record is parsed on the native
  thread before the menu request arrives (same IPC connection, ordered). Keep the newest record with
  its arrival time; a menu request takes it if it is younger than a short window, and clears it so it
  is never used twice. → Native data first, record fills the gaps.
- **Precedence with Canvas 9.** A replace-model listener (with the engine menu suppressed) means there is
  no engine menu, so contributors are simply not asked. With `setDefaultContextMenuEnabled(true)` both
  work: the listener is told and the engine menu (with contributed items) opens.
- **Labels are plain text.** GTK menus built by WebKit treat `_` as a mnemonic (verified: `Ask_me`
  rendered as `Askme`), so it is doubled; Win32/WebView2 labels treat `&` as an access key, so it is
  doubled; `NSMenuItem` titles are literal. No platform parses markup (verified on GTK).
- **Linux menu placement.** GTK emits `GtkMenu::popped-up` with the pre-slide ("flipped") rectangle in
  root coordinates; subtracting the parked view's root origin gives the intended position inside the
  view, which is re-based on the Swing component's screen origin and clamped to the monitor's work
  area. Verified to place the menu and keep activation working.

### Alternatives considered
- **Suppress the engine menu and show a Swing menu with copies of a few native items** (Copy, Paste,
  Inspect): loses Look Up, spelling, Services and future engine items. Rejected.
- **An `evaluate_javascript` round trip for the selection inside the GTK `context-menu` signal**: the
  signal is synchronous and an async result cannot be waited for on the GTK thread without re-entering
  the main loop. Rejected in favour of the Canvas 9 record.
- **Pre-registered static items with "show when" predicates evaluated natively**: avoids a JNI upcall
  but cannot express arbitrary application logic. Rejected.
- **Moving the parked window on-screen while the menu is up** (so GTK anchors correctly): flashes a live
  native surface over the Swing component and steals input. Rejected.

## 4. Risk & gap analysis

### Requirement ambiguities
- "The selection's on-screen rect": the shim can report the selection's client rectangle (component
  coordinates); converting to screen is the application's job on the EDT (a helper can do it).
- Separators between different contributors' groups: not required; one separator before the block.

### Edge cases
- Keyboard-opened menus (Menu key, Shift+F10): the DOM event still fires, so the record exists; the
  Linux placement uses GTK's own anchor rect, re-based.
- Right-click inside a cross-origin iframe: the top-frame shim sees nothing; native data still applies
  (GTK hit test, WebView2 target); WKWebView gets an empty context.
- Selection inside `<input>`/`<textarea>`: `getSelection()` is empty there; the shim must read the
  control's own selection (never for passwords).
- A page that forges `__webview_dom_event` records: it can only change the *description* (which it can
  equally change by changing its selection); it can never trigger an item. On GTK and WebView2, the
  presence of a selection, link or image comes from the engine.
- Menu dismissed without a choice, then a new menu: the old token is dead.
- Component disposed while a menu is open: activation is ignored.

### Technical risks
- **WKWebView `willOpenMenu:withEvent:`** is the documented NSView hook and the standard way to extend
  WKWebView's menu, but whether `WKWebView` itself overrides it varies by OS; the swizzle must add the
  method when absent (calling `NSView`'s) and replace it only when WKWebView owns it. Not runnable
  here; CI compiles it.
- **WebView2 labels and `&`**: believed to be access-key markers, so doubled; to be confirmed on a
  device. Runtime older than 1.0.1185 lacks `ICoreWebView2_11` → no items, engine menu unchanged.
- **HiDPI on Linux**: the placement assumes GTK logical pixels equal Java logical pixels (true with
  matching `GDK_SCALE` and `sun.java2d.uiScale`); otherwise the menu is offset. Documented.
- **Contributor speed**: a slow contributor delays the menu; documented contract.
- **Activation item lifetime**: each native item owns what activation needs (a callback reference, the
  token, the index), so activation never touches an engine that may be gone.

### Acceptance-criteria coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Item appears for a selection, engine items kept | Yes | Linux verified in lightweight; macOS/Windows by construction + CI compile |
| 2 | Choice reaches the app on the EDT with text, point, rect | Yes | Rect from the shim record |
| 3 | No item where none contributed | Yes | No separator when no items |
| 4 | Link / image / editable described | Yes | Native on GTK and WebView2; shim on WKWebView |
| 5 | Disabled items | Yes | GAction enabled / `NSMenuItem` / `put_IsEnabled` |
| 6 | Labels shown as written | Yes | Per-platform escaping |
| 7 | Page cannot choose an item | Yes | No JS path to activation |
| 8 | Once, newest menu only | Yes | Token check + compare-and-set |
| 9 | Several contributors in order | Yes | |
| 10 | Failing contributor skipped | Yes | Uncaught handler |
| 11 | Removal | Yes | |
| 12 | Replace model still wins | Yes | No engine menu → not asked |
| 13 | Nothing changes without contributors | Yes | Plus the Linux placement fix |
| 14 | Feature detection | Yes | `webview_context_menu_available` probe |
