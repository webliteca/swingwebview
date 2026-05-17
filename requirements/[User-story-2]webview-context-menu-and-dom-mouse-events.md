# Story Decomposition: WebView Right-Click Context Menu + DOM Mouse Event Channel

## INVEST Analysis

### Abstract Task: "Surface DOM-level right-click events to Swing and let callers show a Swing JPopupMenu anchored at the click"

**Analysis Dimensions**:
- **Core Responsibility**: Give Swing applications hosting `WebViewComponent` a structured channel for DOM mouse events originating inside the embedded page — currently scoped to the right-click / `contextmenu` event — plus a convenience helper that turns each such event into a Swing `JPopupMenu` anchored at the click point. Each event carries enough information about the DOM element under the cursor that the host can build a context-aware menu (e.g. different items for a link vs. an image vs. plain text).
- **Primary Operations**:
  1. Register / unregister a Java `WebViewMouseListener` that receives `contextMenuRequested(WebViewMouseEvent)` callbacks with a `DomTarget` descriptor of the right-clicked element.
  2. Suppress the browser's default context menu automatically while at least one listener is registered; allow callers to opt back into the platform default explicitly.
  3. Attach a `WebViewContextMenu` helper that turns each event into a caller-built `JPopupMenu` and shows it anchored at the click.
- **Key Constraints**:
  - Must work in both `HEAVYWEIGHT` and `LIGHTWEIGHT` modes (per canvas-6 / canvas-7) — the JS shim and the native bridge land identically on each.
  - Payload travels through a reserved `__webview_` JS binding consistent with the existing console-capture wiring (canvas-2 / STORY-001-002), so this story does not introduce new native APIs.
  - Listener callbacks must be dispatched on the Swing EDT so callers can touch Swing state directly (matches the `ConsoleListener` contract).
  - Coordinate-to-Swing translation: the JS side reports CSS pixels (viewport-, page-, and screen-relative); the event surface gives callers a helper that returns a component-relative `Point` suitable for `JPopupMenu.show(Component, int, int)` and another for screen-relative placement.
  - Default-menu suppression model is **auto-suppress when listener registered** — the JS-side flag flips automatically as listeners come and go, with an explicit `setDefaultContextMenuEnabled(true)` override for callers who want the OS-default menu back.
- **Technical Complexity**: Medium — single JS shim added at document-start that listens for `contextmenu`, builds a JSON payload of the target descriptor, and posts it through a reserved binding; symmetric wiring in `WebViewHeavyweightComponent` and `WebViewLightweightComponent`; one new helper class.
- **Business Complexity**: Low — small, well-bounded developer-facing API surface; the menu items themselves are entirely the caller's responsibility.

### INVEST Evaluation

- ✅ **Independent**: Sits on top of canvases 5 / 6 / 7 (mode selection + heavyweight + lightweight) and reuses the existing JS-binding bridge. No dependency on STORY-001-001 or STORY-001-002, though it follows the same reserved-binding convention.
- ✅ **Negotiable**: API shape agreed with user — `addWebViewMouseListener` / `removeWebViewMouseListener`, `setDefaultContextMenuEnabled`, `WebViewMouseEvent`, `DomTarget`, `WebViewContextMenu`. Default-suppression policy fixed at "auto-suppress when listener registered".
- ✅ **Valuable**: Right-click context menus are table stakes for a desktop app embedding web content; every non-trivial host application will use this.
- ✅ **Estimable**: One JS shim, one binding per native backend (already plumbed), a small POJO graph, and a JPopupMenu helper. Each piece is a known, bounded pattern in this codebase.
- ✅ **Small**: 3-5 days of work for the full surface (event channel + helper + both modes).
- ✅ **Testable**: Listener fan-out, descriptor fields, EDT marshaling, default-menu suppression, and helper behaviour are all observable from a small Swing harness.

**Conclusion**: Ready as-is — no split needed. Layer 1 (event channel) and Layer 2 (JPopupMenu helper) are tightly coupled (the helper exists only to remove boilerplate around the channel), share the same JS shim and native wiring, and were explicitly scoped together by the user. At most three functional points (listener registration, default-menu suppression, JPopupMenu helper) — within INVEST limits.

### Split Strategy

N/A — kept as a single story.

---

## [STORY-002-001] Right-Click Context Menu and DOM Mouse Event Channel for WebViewComponent

### Background

A Swing application that embeds a `WebViewComponent` today has no way to respond to right-clicks on DOM content. The native peer surfaces a coarse `WebViewClickCallback` that only fires "some mouse button was pressed inside the WebView" (used internally to dismiss open `JPopupMenu`s when the user clicks into the WebView region — see `WebViewClickCallback.java`), and nothing more: no button, no coordinates, no target element. The platform's own context menu (Inspect Element, browser Cut / Copy / Paste, etc.) appears on right-click when `debug=true`, but callers cannot replace it, supplement it, or react to which DOM element was right-clicked.

This story introduces a structured channel for DOM-level mouse events originating inside the embedded page, scoped initially to right-click / `contextmenu`. Each event carries the mouse details (button, modifiers, viewport / page / screen coordinates) and a `DomTarget` descriptor of the element under the cursor (tag, id, classes, a curated set of attributes, nearest-ancestor link href, image / media src if applicable, selection text, content-editability, page and frame URL). On top of the listener primitive, the story ships a `WebViewContextMenu` helper that takes a caller-supplied `JPopupMenu` builder and shows the menu at the click point in component coordinates.

The channel is designed so future event kinds — `mousedown`, `mouseup`, `dblclick`, `dragstart`, hover-driven tooltips — can be added by extending `WebViewMouseListener` and the JS shim without re-architecting the bridge, but only `contextMenuRequested` ships in this story.

Key points:
- Business value: every desktop app embedding a WebView needs a context menu wired to host-side Swing actions (Copy as plain text, Open in external browser, Save image, etc.). Without this, the only options are an awkward HTML-rendered popup or no menu at all.
- Relationship with other features: builds on the existing JS-binding bridge (canvas-2 / canvas-6 / canvas-7) and the reserved `__webview_` prefix convention introduced by STORY-001-002. Does not depend on `setDebug` and works in release builds.
- Why now: the heavyweight and lightweight components are shipped and usable, but the lack of a right-click hook is the single most common request when wiring the library into a real Swing application.

### Business Value

- Provide **structured right-click events** with rich DOM-target descriptors to application developers using `WebViewComponent`, in both HEAVYWEIGHT and LIGHTWEIGHT modes.
- Support **context-aware Swing context menus** anchored at the click — links, images, editable fields, and selections each surface enough information for the host to build different menu items per case.
- Enable **future DOM mouse-event integrations** (drag-and-drop, custom tooltips, double-click handlers) by establishing a single extensible event channel rather than ad-hoc per-feature plumbing.
- Eliminate **double-menu bugs** (browser-default menu + custom menu both appearing) by auto-suppressing the platform default once a listener is registered.

### Dependencies and Assumptions

- **Prerequisites**: Canvases 2, 5, 6, 7 already in place. The reserved-binding pattern (`__webview_…`) introduced by STORY-001-002 / canvas for console capture is available and works in both heavyweight and lightweight modes.
- **Data assumptions**: No persisted state. Listener registry and the default-menu-enabled flag are per-component-instance.
- **Integration points**: WKWebView's `WKScriptMessageHandler` + `WKUserScript` (macOS), WebKitGTK's `WebKitUserContentManager` (Linux heavyweight + offscreen / lightweight), WebView2's `AddScriptToExecuteOnDocumentCreated` + `WebMessageReceived` (Windows) — all already wired by the existing JS-binding bridge; this story adds one more named binding.
- **Business constraints**:
  - The default browser context menu cannot be partially suppressed; either the page calls `preventDefault()` on the `contextmenu` event or it doesn't. The JS shim therefore calls `preventDefault()` unconditionally when at least one listener is registered, and the Java side has no opportunity to "let the default fall through" after seeing the event.
  - The platform's "Inspect Element" item (when `debug=true`) is part of the platform-default menu and is therefore suppressed when a listener is registered. Re-injecting an equivalent item into a caller-built `JPopupMenu` is **out of scope** for this story; the caller may opt back into the platform default via `setDefaultContextMenuEnabled(true)`.
  - The JS shim runs in the page's JavaScript context and is subject to the page's CSP. The story assumes the existing console-capture shim already negotiates this constraint successfully.

### Scope In

- New public type `ca.weblite.webview.WebViewMouseEvent` with read-only accessors:
  - `String type()` — fixed to `"contextmenu"` in this story; reserved for future event kinds.
  - `int button()` — 1 = left, 2 = middle, 3 = right.
  - `boolean isShiftDown() / isCtrlDown() / isAltDown() / isMetaDown()`.
  - `int clientX() / clientY()` — CSS pixels relative to the viewport.
  - `int pageX() / pageY()` — CSS pixels relative to the document.
  - `int screenX() / screenY()` — CSS pixels relative to the screen.
  - `long timeStamp()` — page-side `event.timeStamp`, milliseconds since navigation start.
  - `DomTarget target()` — non-null.
  - `WebViewComponent source()` — the component the event came from.
  - `java.awt.Point toComponentPoint()` — convenience returning `clientX / clientY` translated into the source component's coordinate space, suitable for `JPopupMenu.show(source(), p.x, p.y)`.
  - `java.awt.Point toScreenPoint()` — convenience returning `screenX / screenY`, suitable for `JPopupMenu.show` against an anchor at screen origin.
- New public type `ca.weblite.webview.DomTarget` with read-only accessors:
  - `String tagName()` — uppercase, e.g. `"A"`, `"IMG"`, `"DIV"`, `"BODY"`.
  - `String id()` — empty string when the element has no id.
  - `java.util.List<String> classes()` — never null; empty when the element has no class attribute.
  - `java.util.Map<String,String> attributes()` — a curated subset relevant to context menus: `href`, `src`, `alt`, `title`, `name`, `type`, `value`, `role`, `data-*` keys present on the element. Never null; missing attributes are simply absent from the map.
  - `String linkHref()` — `href` of the nearest ancestor `<a>` element (including `target` itself), or `null` when none.
  - `String imageSrc()` — `src` of the target if it is an `<img>`, or `null` otherwise.
  - `String mediaSrc()` — `src` (or current `currentSrc`) of the target if it is `<audio>` or `<video>`, or `null` otherwise.
  - `boolean isContentEditable()` — `true` when `target.isContentEditable` is `true` or the target is a form text input / textarea.
  - `String selectionText()` — current `window.getSelection().toString()`, or empty string when nothing is selected.
  - `String pageUrl()` — `document.location.href` of the top-level document.
  - `String frameUrl()` — `document.location.href` of the immediate document the target is in; equals `pageUrl` when not inside an iframe.
- New public interface `ca.weblite.webview.WebViewMouseListener` with a single method `void contextMenuRequested(WebViewMouseEvent event)`. Reserved for future additions (`mousePressed`, `mouseReleased`, `mouseClicked`, etc.) — those will be added as `default` methods to preserve binary compatibility; this story exposes only `contextMenuRequested`.
- New public methods on `WebViewComponent` (abstract; implemented uniformly across both subclasses):
  - `WebViewComponent addWebViewMouseListener(WebViewMouseListener listener)`
  - `WebViewComponent removeWebViewMouseListener(WebViewMouseListener listener)`
  - `WebViewComponent setDefaultContextMenuEnabled(boolean enabled)` — explicit override; default behaviour is to auto-suppress when at least one listener is registered.
  - `boolean isDefaultContextMenuEnabled()` — returns the effective state.
- New public helper class `ca.weblite.webview.swing.WebViewContextMenu`:
  - Constructor `WebViewContextMenu(java.util.function.Function<WebViewMouseEvent, JPopupMenu> menuBuilder)`.
  - `WebViewContextMenu attachTo(WebViewComponent component)` — registers an internal `WebViewMouseListener`, may be called once per helper instance.
  - `void detach()` — removes the internal listener.
  - On each `contextMenuRequested`, invokes `menuBuilder.apply(event)`; if it returns non-null, calls `popup.show(event.source(), p.x, p.y)` with `p = event.toComponentPoint()`; if it returns `null`, no popup is shown (caller can suppress contextually).
- Implementation wires the channel through a reserved `__webview_dom_event` JS binding (following the `RESERVED_BINDING_PREFIX` convention already declared on `WebViewComponent`). A JS shim added via `addOnBeforeLoad` listens for `contextmenu` events on `document`, builds the descriptor payload, calls `event.preventDefault()` when the suppression flag is set, and posts the payload via the binding. Toggling the suppression flag at runtime (when the first / last listener is added / removed, or when `setDefaultContextMenuEnabled` is called) is performed via `eval()` on the live page and persists across navigations through the same `addOnBeforeLoad` mechanism.
- All listener callbacks are marshaled to the Swing EDT before invocation.
- Listeners added before the component is displayable are remembered and the shim is installed when the native peer is created; events that fire before any Java listener is registered are dropped.
- Reserved-binding prefix protection (`addJavascriptCallback` already rejects names starting with `__webview_`) is reused — no additional public reservation needed.

### Scope Out

- DOM mouse event kinds other than `contextmenu` — `mousedown`, `mouseup`, `mouseclick`, `dblclick`, `dragstart`, `mouseenter` / `mouseleave`, `mousemove`. The channel is designed to extend to these; no implementation in this story.
- Drag-and-drop initiation from the WebView (page-to-Swing or Swing-to-page).
- Custom tooltip placement driven by DOM hover state.
- Native OS context menus (`NSMenu` on macOS, `GtkMenu` on Linux, `HMENU` on Windows) — explicitly a non-goal; Swing `JPopupMenu` only.
- Re-injecting an equivalent "Inspect Element" item into caller-built `JPopupMenu`s when the platform default is suppressed. Callers that want the platform default back can call `setDefaultContextMenuEnabled(true)`; designing a cross-platform "Inspect Element" hook is a separate follow-up story if needed.
- Adding the listener API to the standalone in-process `WebView` class (the API is for embedded usage inside Swing only).
- Programmatic dispatch of synthetic right-click events into the page from Java.
- Cross-origin iframe descriptors — when the right-clicked element is inside a same-origin iframe, `frameUrl` reflects that iframe's URL and target attributes are captured; cross-origin iframes only surface what the top-level event handler can observe (the iframe element itself, not the document inside it).
- Coordinate translation accuracy under DPI scaling beyond what the existing heavyweight / lightweight components already provide — this story uses the same translation primitives those components use; it does not introduce new HiDPI handling.
- Built-in menu-item factories (e.g. "Copy Link", "Open in Browser", "Save Image As") — these are downstream user-built helpers this API enables; the helper in this story is purely a `Function<WebViewMouseEvent, JPopupMenu>` invoker.

### Acceptance Criteria

#### AC1: Listener receives contextmenu event with target descriptor on lightweight
**Given** a `WebViewLightweightComponent` on Linux with a registered `WebViewMouseListener` and loaded with a page containing `<div id="hello" class="a b">Hi</div>`,
**When** the user right-clicks the `<div>`,
**Then** the listener's `contextMenuRequested` is invoked exactly once with a `WebViewMouseEvent` whose `button()` is `3`, `type()` is `"contextmenu"`, and whose `target()` reports `tagName()` `"DIV"`, `id()` `"hello"`, and `classes()` containing `"a"` and `"b"` in that order.

#### AC2: Listener receives contextmenu event with target descriptor on heavyweight
**Given** a `WebViewHeavyweightComponent` on macOS or Windows with a registered `WebViewMouseListener` and loaded with the same `<div id="hello" class="a b">Hi</div>` page,
**When** the user right-clicks the `<div>`,
**Then** the listener's `contextMenuRequested` is invoked exactly once with the same field values as AC1.

#### AC3: Listener receives keyboard modifiers and button state
**Given** a `WebViewComponent` with a registered listener and a loaded page,
**When** the user right-clicks anywhere in the page while holding Shift and Ctrl,
**Then** the listener receives a `WebViewMouseEvent` with `isShiftDown()` `true`, `isCtrlDown()` `true`, `isAltDown()` `false`, and `isMetaDown()` `false`.

#### AC4: Coordinates are reported in viewport / page / screen frames and translate to component coordinates
**Given** a `WebViewComponent` with a registered listener, sized to 800x600 and positioned at component-relative origin (0,0) within its parent JFrame, and a loaded page whose scrollable content has been scrolled vertically by 100 CSS pixels,
**When** the user right-clicks at viewport position (50, 50),
**Then** the listener receives a `WebViewMouseEvent` with `clientX()` `50`, `clientY()` `50`, `pageY()` `150`, and `toComponentPoint()` returning a `Point` whose `x` and `y` are within one pixel of `(50, 50)` (HiDPI scaling notwithstanding).

#### AC5: Nearest-ancestor link href surfaces on inner-element right-click
**Given** a page containing `<a href="https://example.com/foo"><span>label</span></a>` and a registered listener,
**When** the user right-clicks the `<span>`,
**Then** the listener receives a `WebViewMouseEvent` whose `target().tagName()` is `"SPAN"` **and** whose `target().linkHref()` is `"https://example.com/foo"`.

#### AC6: Image source surfaces when right-clicking an `<img>`
**Given** a page containing `<img src="https://example.com/cat.png" alt="a cat">` and a registered listener,
**When** the user right-clicks the image,
**Then** the listener receives a `WebViewMouseEvent` whose `target().tagName()` is `"IMG"`, `target().imageSrc()` is `"https://example.com/cat.png"`, and `target().attributes()` contains `alt` -> `"a cat"`.

#### AC7: Current text selection is surfaced
**Given** a page with the text `"the quick brown fox"` where the user has selected the word `"quick"` and a registered listener,
**When** the user right-clicks anywhere in the selection,
**Then** the listener receives a `WebViewMouseEvent` whose `target().selectionText()` is `"quick"`.

#### AC8: Empty selection reports as empty string
**Given** a page where nothing is selected and a registered listener,
**When** the user right-clicks anywhere,
**Then** the listener receives a `WebViewMouseEvent` whose `target().selectionText()` is `""` (empty string, never null).

#### AC9: Content-editable and form-input editability is reported
**Given** a page containing `<input type="text">`, `<textarea></textarea>`, and `<div contenteditable="true">edit me</div>` and a registered listener,
**When** the user right-clicks each of the three elements in turn,
**Then** each callback's `target().isContentEditable()` is `true`.

#### AC10: Default browser context menu is suppressed when a listener is registered
**Given** a `WebViewComponent` with at least one `WebViewMouseListener` registered, loaded with any page,
**When** the user right-clicks anywhere in the page,
**Then** the platform's default context menu (Inspect Element, Cut / Copy / Paste, etc.) does not appear; only the Java listener fires.

#### AC11: Default browser context menu remains when no listener is registered
**Given** a `WebViewComponent` with no `WebViewMouseListener` registered and `debug=true`,
**When** the user right-clicks anywhere in the page,
**Then** the platform's default context menu appears as it does today (Linux WebKitGTK menu, macOS WKWebView menu, or Windows WebView2 menu).

#### AC12: Removing the last listener restores the default menu
**Given** a `WebViewComponent` with one `WebViewMouseListener` registered, after which `removeWebViewMouseListener` is called with that same listener,
**When** the user right-clicks anywhere in the page,
**Then** the platform's default context menu appears again and no Java callback fires.

#### AC13: setDefaultContextMenuEnabled(true) re-enables the platform menu while the listener still fires
**Given** a `WebViewComponent` with a registered listener and a subsequent call to `setDefaultContextMenuEnabled(true)`,
**When** the user right-clicks anywhere in the page,
**Then** the platform's default context menu appears **and** the Java listener also receives the event.

#### AC14: WebViewContextMenu shows the supplied JPopupMenu at the click point
**Given** a `WebViewContextMenu` attached to a `WebViewComponent` with a menu builder that returns a `JPopupMenu` containing two items "Open" and "Copy", and a loaded page,
**When** the user right-clicks at component-relative position (120, 80),
**Then** a Swing `JPopupMenu` appears anchored within one pixel of (120, 80) in the component's coordinate space, showing exactly the items "Open" and "Copy", and the platform default menu does not appear.

#### AC15: WebViewContextMenu builder returning null suppresses the popup
**Given** a `WebViewContextMenu` attached to a `WebViewComponent` with a menu builder that returns `null` for events whose target tag is `"BODY"`,
**When** the user right-clicks directly on the page background (so target tag is `"BODY"`),
**Then** no `JPopupMenu` is shown and no `NullPointerException` propagates to the caller.

#### AC16: WebViewContextMenu detach stops the popup from appearing on subsequent right-clicks
**Given** a `WebViewContextMenu` attached to a `WebViewComponent` for which `detach()` has subsequently been called,
**When** the user right-clicks in the page,
**Then** no caller-built popup appears; the underlying mouse listener has been removed and (per AC12) the platform default menu appears again unless another listener is still registered.

#### AC17: Listener callbacks fire on the Swing EDT
**Given** a `WebViewComponent` with a registered listener whose `contextMenuRequested` records `SwingUtilities.isEventDispatchThread()`,
**When** the user right-clicks anywhere in the page,
**Then** the recorded value is `true` regardless of which native thread originated the event.

#### AC18: Listeners registered before display still receive events
**Given** a `WebViewComponent` instance constructed and assigned a `WebViewMouseListener` before being added to a JFrame,
**When** the component is subsequently displayed, navigated to a page, and the user right-clicks,
**Then** the listener receives the `contextMenuRequested` callback exactly once with a fully-populated event.

#### AC19: Multiple listeners all receive the same event in registration order
**Given** a `WebViewComponent` with three `WebViewMouseListener`s registered via `addWebViewMouseListener` and a loaded page,
**When** the user right-clicks once,
**Then** all three listeners receive a `WebViewMouseEvent` with identical field values; the order in which listeners are invoked is the order they were added.

#### AC20: Listener exception does not break the pipeline
**Given** a `WebViewComponent` with two registered listeners where the first throws a `RuntimeException` inside `contextMenuRequested`,
**When** the user right-clicks,
**Then** the second listener still receives the event, and the exception from the first listener is surfaced via standard EDT uncaught-exception handling but does not crash the WebView or the host app.

#### AC21: Shim survives page navigation
**Given** a `WebViewComponent` with a registered listener, after the user has navigated from page A to page B,
**When** the user right-clicks anywhere in page B,
**Then** the listener receives the `contextMenuRequested` callback exactly once for that click; no re-registration was needed across the navigation.

#### AC22: Reserved binding name is protected from caller collision
**Given** a `WebViewComponent` on which the caller attempts to register a JavaScript callback under a name beginning with `__webview_` (the reserved prefix),
**When** the call is made,
**Then** the call is rejected with `IllegalArgumentException` (existing behaviour) so the reserved `__webview_dom_event` channel cannot be hijacked by application code.

### Non-Functional Expectations

- The added `contextmenu` listener must not produce a measurable input-latency regression on the lightweight component during ordinary scrolling / hovering on pages that never right-click — i.e. the shim only does work in response to a `contextmenu` event, not on every mouse movement.
- The injected JS shim must be idempotent across page navigations — each new page load installs the shim cleanly and listeners continue to receive events without re-registration.
- Toggling `setDefaultContextMenuEnabled(boolean)` must take effect on the very next right-click (no stale-flag invocations after the setter returns).
- The descriptor payload must never include sensitive form values verbatim from `<input type="password">` — `password`-typed inputs report their `value` as the empty string in `attributes()`, never the actual entered text.

---

## Quality Checks

**STORY-002-001 (WebView Right-Click Context Menu and DOM Mouse Event Channel)**:
- ✅ All required sections present (Background, Business Value, Dependencies and Assumptions, Scope In, Scope Out, Acceptance Criteria, Non-Functional Expectations).
- ✅ Each AC uses Given-When-Then with concrete page markup, observable outcomes, and specific field values.
- ✅ Business-language ACs — API names (`addWebViewMouseListener`, `contextMenuRequested`, `WebViewContextMenu`) appear because they are the user-visible contract, consistent with STORY-001-001 / STORY-001-002. No JNI signatures, no SDK method names inside AC bodies.
- ✅ Covers happy path (HW + LW, link / image / selection / editable variants), validation / business rules (auto-suppression, opt-out, listener-removal), error conditions (listener exception, null-builder return, reserved-name collision), and EDT / pre-display / multi-listener / navigation invariants.
- ✅ At most three core functional points (listener API, default-menu suppression, JPopupMenu helper) — within INVEST limits.
- ✅ 3-5 days of work (JS shim, two-mode wiring through the existing binding bridge, POJO graph, helper class, descriptor extraction for the curated attribute set).

## Final INVEST Re-validation

| Property | STORY-002-001 |
|---|---|
| Independent | ✅ (no story-level dependency on STORY-001-001 or STORY-001-002; reuses already-shipped bridge) |
| Complete | ✅ (full listener API + helper + suppression model + both modes) |
| Valuable | ✅ (context menus are table stakes for desktop apps embedding web content) |
| Estimable | ✅ (one JS shim + one binding + POJO graph + one helper class — all known patterns) |
| Right-sized | ✅ (3-5 days) |
| Testable | ✅ (listener callbacks, descriptor fields, EDT marshaling, suppression behaviour, helper popup placement — all observable from a small Swing harness) |

Story passes all six INVEST criteria as a single deliverable.
