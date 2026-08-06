# SPDD Analysis: Adopt Browser-Initiated Popups Into a Caller-Provided WebViewComponent (STORY-005-001 + macOS)

## Original Business Requirement

> Focused on STORY-005-001 of
> `requirements/[User-story-5]adopt-popups-into-webviewcomponent-tab.md`
> (the popup-adoption Java contract + macOS WKWebView reference
> backend). STORY-005-002 (Linux) / STORY-005-003 (Windows) conform to
> this contract; STORY-005-004 (swingwebbrowser) consumes it. The full
> story text is preserved verbatim in that requirements file.

**STORY-005-001 — Popup-Adoption Java Contract and macOS WKWebView Coverage.**

Canvas 15 gave the embedded page a way to raise popups (`window.open`,
`<a target="_blank">`, `<form method="post" target="…">`) and the
native engine hosts each popup's opener-linked child web view in a
**native top-level window it owns** (NSWindow / GtkWindow / HWND). The
Java `WebViewPopupHandler` only decides allow/deny (`boolean
popupRequested`) and observes open/close. A tabbed browser
(swingwebbrowser) wanting popups as **tabs** must block the native
window and re-open `WebViewPopupEvent.targetUrl()` with
`WebViewComponent.setUrl(url)` — a GET — which drops the POST body of
form-POST popups and breaks opener linkage (`window.opener`,
`postMessage`). The POST body is not reliably exposed to the
popup-create delegate on any engine, so the request cannot be replayed
from Java. The only faithful path is to **reuse the engine's own
opener-linked child** — the view WebKit already drove the original
request into.

This story designs the cross-platform Java contract for **adopting**
that child into a caller-supplied `WebViewComponent` (a tab) and ships
the macOS (WKWebView) reference backend. Backward compatibility with
Canvas 15 is mandatory. The acceptance criteria (AC1–AC8) are recorded
verbatim in the requirements file: POST preserved (AC1), opener
linkage preserved (AC2), no native popup window on adopt (AC3),
allow/block backward compatibility (AC4/AC5), unclaimed-adoption
cleanup (AC6), unknown/already-adopted id rejection (AC7), close
notification + disposal (AC8), plus off-EDT/headless non-functional
expectations.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **WebViewPopupHandler** (`src/ca/weblite/webview/WebViewPopupHandler.java`):
  the policy + observation contract. Three `default` methods —
  `boolean popupRequested` (allow/deny gate, runs synchronously on the
  native UI thread, off the EDT), `popupOpened` / `popupClosed`
  (EDT notifications). `DEFAULT` allows all. This is the surface that
  must grow an "adopt" disposition without breaking the boolean gate.
- **WebViewPopupEvent** (`WebViewPopupEvent.java`): immutable carrier
  — `source`, `targetUrl`, `targetName`, `userGesture`,
  `width`/`height`, `pageUrl`. No HTTP method/body (by design — not
  available). Reused as-is for adoption; the popup **id** becomes the
  correlation key.
- **PopupDispatcher** (`PopupDispatcher.java`): per-component fan-out
  hub. `dispatchPopupRequested` runs the handler **inline on the
  native UI thread** and returns its boolean synchronously;
  `dispatchPopupOpened`/`dispatchPopupClosed` marshal to the EDT via
  `invokeLater`. Holds `openPopups: ConcurrentHashMap<Long,
  WebViewPopupEvent>` correlating open→close by engine-assigned
  `popupId`. This correlation map is the natural home for
  adopt-pending bookkeeping.
- **WebViewPopupCallback** (`WebViewPopupCallback.java`): internal JNI
  bridge — `onPopupRequested` (sync, returns the decision),
  `onPopupOpened`/`onPopupClosed` (notifications). Delivered from the
  native `impl_create_web_view` / `on_create_web_view_popup` sites.
- **WebViewComponent** (`swing/WebViewComponent.java`): abstract Swing
  surface. Owns `popupDispatcher` (and dialog/console/mouse
  dispatchers) for the component's whole lifetime, surviving peer
  create/destroy. Peer created lazily. `setPopupHandler` /
  `getPopupHandler` delegate to the dispatcher.
- **EmbeddedWebView** (`EmbeddedWebView.java`): heavyweight engine
  wrapper. Constructed via `EmbeddedWebView.attach(canvas, debug)` —
  binds a **freshly created** native engine to a live AWT canvas
  surface. Anchors native callbacks in a `heap` list;
  `setPopupCallback` / `setDialogCallback` register global refs;
  `dispose()` clears callbacks then destroys the peer.
- **OffscreenWebView** (`OffscreenWebView.java`): lightweight/offscreen
  engine wrapper; analogous `setPopupCallback` and offscreen peer.
- **WebViewHeavyweightComponent.createPeer()** /
  **WebViewLightweightComponent.addNotify()**: the peer-attach sites.
  Today they call `EmbeddedWebView.attach(...)` /
  `engine = <create offscreen>`, then install the console / mouse /
  focus / click / dialog / popup callbacks that delegate to the
  per-component dispatchers. This is where an **adopt** construction
  path must branch.
- **Native popup-create sites** (`src_c/webview_embed.cpp`): macOS
  `impl_create_web_view` (builds the child `WKWebView` from the passed
  `WKWebViewConfiguration`, creates an `NSWindow`, sets the child as
  `contentView`, `makeKeyAndOrderFront:`, registers a child `Engine`
  in `g_webview_map` keyed by the child view, fires `onPopupOpened`);
  `impl_web_view_did_close` (tears the child engine down, fires
  `onPopupClosed`). Linux `on_create_web_view_popup` /
  `handle_create_web_view` (creates the related-view child, a
  `GtkWindow`, registers a `PopupEngine`). These already produce the
  opener-linked child — the adoption change is about **where the child
  is hosted** (engine-owned window vs. caller's surface) and **when it
  is shown**.
- **g_webview_map** / child `Engine` (macOS) & `PopupEngine` (Linux):
  the native registries that already key a child engine by an opaque
  handle equal to the `popupId`. Adoption reuses this registry to look
  up the retained child at reparent time.

### New Concepts Required

- **Popup disposition (BLOCK / NATIVE_WINDOW / ADOPT)**: the enriched
  outcome of the synchronous native-thread decision. BLOCK and
  NATIVE_WINDOW reproduce Canvas 15's `false` / `true`. ADOPT means
  "create the linked child but do not create/show an engine-owned
  window; retain it under a hidden holder keyed by `popupId` and
  notify the app to adopt it." Introduced additively so legacy boolean
  handlers keep mapping to BLOCK/NATIVE_WINDOW.
- **Adopt notification / adopt request**: the EDT-side signal carrying
  the `popupId` (and the `WebViewPopupEvent`) that lets the
  application build a tab and request adoption. Conceptually a new
  handler observation (e.g. an EDT `popupAdoptable(event)` /
  `popupOpened` variant carrying an adopt token) — exact surface is a
  REASONS-Canvas design decision.
- **Adopting WebViewComponent construction mode**: a `WebViewComponent`
  whose peer, at attach time, **adopts a pre-existing native child
  engine** identified by `popupId` (reparenting the child view into
  this component's realized surface) instead of creating its own
  engine. Realized at the wrapper layer as a new
  `EmbeddedWebView.adopt(canvas, popupId, debug)` /
  `OffscreenWebView.adopt(...)` seam paralleling `attach(...)`.
- **Hidden holder + retained child + adopt token lifecycle**: native
  state that keeps the engine-created child alive and unshown between
  the synchronous ADOPT decision and the asynchronous reparent, plus
  the bookkeeping (in `PopupDispatcher` and native registry) that
  tracks pending adoptions, enforces adopt-once, and reclaims
  unclaimed children (AC6/AC7).
- **Adopt/reparent native operation**: move an already-created child
  view from the hidden holder into a JAWT-realized surface — macOS
  `addSubview:` into the canvas's `NSView`; (later) Linux
  reparent into the X11/GTK surface; Windows `SetParent` /
  `put_ParentWindow`. A new native entry point
  (`webview_embed_adopt_popup(canvasHandle, popupId)`-style).

### Key Business Rules

- **POST + opener linkage are preserved only by reusing the engine's
  child** — never by re-navigating from Java. Governs the entire
  design: the adopting component must wrap the *existing* child engine,
  not create a new one and `setUrl`.
- **The synchronous native-thread decision must never touch the EDT or
  Swing** (Canvas 15 deadlock invariant). Governs disposition:
  ADOPT is *decided* synchronously but *performed* asynchronously.
- **Backward compatibility**: legacy boolean `popupRequested` and
  `setPopupHandler(null)` semantics are unchanged (AC4/AC5). Governs
  the additive shape of the disposition surface.
- **Adopt-once, no-leak**: a `popupId` may be adopted at most once;
  unknown/duplicate ids are rejected cleanly (AC7); an ADOPT that is
  never claimed is reclaimed (AC6). Governs the retained-child
  lifecycle.
- **No visible native window on ADOPT** (AC3) and **no perceptible
  flash** (STORY-005-004 NFR). Governs the hidden-holder requirement.
- **`getPopupHandler()` never null; disposed dispatcher inert** —
  existing PopupDispatcher invariants carry forward to the new paths.

## Strategic Approach

### Solution Direction

Extend Canvas 15's existing popup channel rather than build a parallel
one. The data flow becomes:

1. **Native popup-create site** (macOS `impl_create_web_view`) →
   synchronous JNI hop → `PopupDispatcher.dispatchPopupRequested` →
   handler decision. Enrich the decision to carry disposition
   (BLOCK / NATIVE_WINDOW / ADOPT) while keeping the boolean bridge
   for legacy handlers.
2. On **NATIVE_WINDOW / BLOCK**: exactly today's behavior (create +
   show NSWindow, or return `nil`).
3. On **ADOPT**: create the linked child (as today, from the passed
   configuration — this is what preserves POST + opener), but **do not
   create/show the NSWindow**; retain the child engine in the native
   registry under a hidden holder keyed by `popupId`; return the child
   to WebKit so it drives the original request into it; then fire an
   **EDT adopt notification** carrying the `popupId`.
4. **Application (EDT)** creates a new tab with an **adopting
   WebViewComponent**; its peer-attach path calls
   `EmbeddedWebView.adopt(canvas, popupId, debug)`, which invokes a new
   native `adopt_popup` that looks up the retained child by `popupId`
   and **reparents** its view into the canvas surface, transferring
   engine ownership to this component and installing the standard
   per-component callbacks (console/mouse/focus/click/dialog/popup) on
   the adopted engine.
5. **Close** (`window.close()` / user closes tab) tears down the
   adopted engine and fires `popupClosed` correlated by `popupId`
   (AC8).

Leverage existing conventions throughout: the `heap`-anchored callback
pattern, the `g_webview_map` child-engine registry, the
`openPopups` correlation map, the dialog/popup EDT-marshaling
precedent, and the `attach(...)` peer-construction seam (adding a
sibling `adopt(...)`).

### Key Design Decisions

- **Disposition surface: additive enum vs. new handler method vs.
  overloaded return.**
  - *Trade-offs*: Changing `popupRequested`'s return type from
    `boolean` breaks Canvas 15 callers. A parallel
    `PopupDisposition popupDisposition(event)` default-method (default
    derived from the legacy boolean) keeps compatibility and reads
    cleanly.
  - *Recommendation*: Add a new default method returning a
    `PopupDisposition` enum, whose default implementation delegates to
    the existing boolean (`popupRequested` → true ⇒ NATIVE_WINDOW,
    false ⇒ BLOCK). Legacy handlers are untouched; adopters override
    the new method. Final shape (enum vs. richer token) is fixed in
    the REASONS Canvas.

- **When to notify the app to adopt: reuse `popupOpened` vs. new
  `popupAdoptable`.**
  - *Trade-offs*: Overloading `popupOpened` conflates "native window
    shown" with "ready to adopt". A dedicated EDT notification
    (carrying an adopt token/`popupId`) is unambiguous and lets the
    app decide tab creation.
  - *Recommendation*: A dedicated EDT adopt notification carrying the
    `popupId` + event, fired only on the ADOPT path.

- **Adopting-component construction: new `adopt(...)` seam vs. mutate
  `attach(...)`.**
  - *Trade-offs*: `attach(canvas, debug)` hard-codes "create a new
    engine". Bolting an adopt-mode flag onto it muddies the common
    path. A sibling `EmbeddedWebView.adopt(canvas, popupId, debug)`
    (and `OffscreenWebView.adopt`) that reparents an existing engine
    is explicit and testable.
  - *Recommendation*: New `adopt(...)` seam; the Swing components pick
    `attach` vs. `adopt` based on whether they were constructed with a
    pending `popupId`. A public factory
    (`WebViewComponent.adoptPopup(popupId)` or a constructor variant)
    exposes this to the consumer.

- **Retained-child lifecycle owner: native registry vs. Java
  dispatcher.**
  - *Trade-offs*: The child engine lives natively; but adopt-once,
    unknown-id rejection, and unclaimed-reclaim (AC6/AC7) need
    Java-visible state for clean errors and a grace timer.
  - *Recommendation*: Native registry owns the child object;
    `PopupDispatcher` tracks pending-adopt `popupId`s (extending the
    `openPopups` map) to enforce adopt-once, produce clean
    `IllegalStateException`/`IllegalArgumentException` on unknown or
    already-adopted ids, and drive reclaim on opener dispose /
    grace-period expiry.

- **Reclaim policy for unclaimed adoptions (AC6).**
  - *Trade-offs*: A time-based grace risks tearing down a slow app; no
    reclaim leaks. Tie reclaim to opener-`WebViewComponent` disposal
    (deterministic) plus a generous bounded grace as a backstop.
  - *Recommendation*: Reclaim on opener dispose; add a documented
    bounded grace as a safety net, logged, not silent.

- **Scope of reparenting.** Only a freshly engine-created popup child
  is adopted, once, at popup time — not arbitrary running components.
  Keeps the native reparent tractable and sidesteps the Canvas 15
  "engines can't be created detached and reparented" concern (the
  child is already created and realized; moving an existing native
  view between parents is what heavyweight embedding already does).

### Alternatives Considered

- **Surface HTTP method/body to Java and replay via a new
  `postUrl`**: rejected — the POST body is not reliably exposed at the
  popup-create delegate on any engine (WKWebView `HTTPBody` is
  effectively always nil there; WebKitGTK's `WebKitURIRequest` lacks
  it), so replay cannot be made correct or cross-engine. Also loses
  opener linkage. This is the core reason adoption reuses the child.
- **Synchronously return a caller component from `popupRequested`**:
  rejected — the decision runs off-EDT on the native UI thread and
  cannot create/realize a Swing peer without an EDT round-trip
  (deadlock). Forces the two-phase async model.
- **Reparent the already-shown native popup window into the tab**:
  rejected — causes a visible flash (window appears then yanks) and
  fights the OS window manager; the hidden-holder approach avoids
  both.
- **Adopt by creating a new engine and transferring the DOM/session**:
  rejected — no such transfer primitive exists; opener linkage and the
  in-flight POST navigation live in the engine's own child.

## Risk & Gap Analysis

### Requirement Ambiguities

- **Exact disposition API shape** (enum returned from a new default
  method vs. a richer decision object) is left to the REASONS Canvas;
  the story only requires backward-compatible additivity.
- **The adopt-notification surface** (dedicated method name, whether
  it also carries the child's requested size) is unspecified at story
  level.
- **Grace-period duration for unclaimed adoptions (AC6)** is not
  numerically specified — must be chosen in the Canvas (Safeguards)
  and documented.
- **Public consumer entry point** for constructing the adopting
  component (`WebViewComponent.adoptPopup(popupId)` factory vs. a
  builder) is not fixed.

### Edge Cases

- **Popup closes before it is adopted**: `window.close()` fires on a
  retained-but-unadopted child — must reclaim and not deliver a stale
  adopt notification (or deliver a "already closed" signal).
- **App requests adoption for a `popupId` that was reclaimed** (grace
  expired / opener disposed): must reject cleanly (AC7 path).
- **Opener component disposed while an adopt is pending**: retained
  child must be torn down (AC6).
- **Nested popups from an adopted popup**: the adopted engine inherits
  the popup callback, so a popup from within an adopted tab must route
  to the adopting component's handler (as Canvas 15 already inherits
  callbacks to child engines).
- **Lightweight/offscreen adoption** (Linux, STORY-005-002): adopting
  into an offscreen engine differs from reparenting a heavyweight view
  — flagged for the platform story but the Java contract must not
  assume heavyweight.
- **Two rapid popups** before either is adopted: two retained children,
  two distinct `popupId`s — the correlation map must not collide.
- **Headless/unit context**: constructing the handler and dispatcher,
  and the disposition routing, must be testable without a native peer
  (AC-style unit tests, per repo policy).

### Technical Risks

- **Threading correctness** (highest): the synchronous ADOPT decision
  runs off-EDT and must not block on the EDT; the reparent runs on the
  EDT. A regression here reintroduces the AppKit/EDT deadlock Canvas 15
  and the deadlock-elimination analysis specifically guard against.
  Mitigation: keep the native-thread path decision-only; do all Swing
  work in the EDT adopt path; mirror the existing dispatcher split and
  cover it in `PopupDispatcher` unit tests.
- **Native reparenting of a live child view** (macOS `addSubview:`
  moving a `WKWebView` mid-navigation): retain/release balance and
  first-responder/focus handoff are delicate; the existing Canvas 15
  retain/release note already flags on-device zombie/Instruments
  validation. Mitigation: pattern-faithful native code, marked for
  on-device validation; no automated GUI tests (repo policy).
- **Untestable native code in this sandbox**: no native toolchain —
  macOS (and later Linux/Windows) code is written pattern-faithfully
  and MUST be built + exercised on-device before release, exactly as
  Canvases 15/16/17.
- **Lifecycle leaks**: retained children between decision and adopt are
  a new leak surface (AC6/AC7). Mitigation: Java-side pending-adopt
  bookkeeping in `PopupDispatcher`, deterministic reclaim on opener
  dispose, bounded grace backstop, disposed-dispatcher inertness.
- **Ownership transfer of engine callbacks**: the adopted engine's
  inherited (opener) popup/dialog global refs must be re-pointed to the
  adopting component's dispatchers without double-free. Mitigation:
  follow the Canvas 15 delete-old / new-global-ref lifecycle at the
  adopt seam.
- **Backward-compat regression**: the additive disposition must leave
  the exact `nil`/`NULL`/native-window behavior for non-adopting
  callers. Mitigation: derive disposition default from the legacy
  boolean; keep the `dispatchPopupRequested(...) -> boolean` bridge for
  existing native callback signatures, adding the disposition channel
  alongside rather than replacing it.

### Acceptance Criteria Coverage

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| AC1 | POST-form popup adopted keeps POST body | Yes | Reusing the engine child preserves the in-flight POST; verified on-device via demo/echo server. |
| AC2 | Adopted popup remains opener-linked | Yes | Child built from passed configuration = related view; unchanged from Canvas 15. |
| AC3 | Adopt shows no native popup window | Yes | Hidden-holder path; native code must not create/show NSWindow on ADOPT. |
| AC4 | Legacy allow → native window unchanged | Yes | Disposition default derives from boolean; NATIVE_WINDOW path is today's code. |
| AC5 | Blocking still blocks | Yes | BLOCK reproduces `nil`/`false`; no child created. |
| AC6 | Unclaimed adoption reclaimed, no leak | Partial | Needs a chosen grace-period value + opener-dispose reclaim; specify in Canvas Safeguards. |
| AC7 | Unknown/already-adopted id rejected cleanly | Yes | Java pending-adopt map enforces adopt-once + clean exceptions. |
| AC8 | Close notifies app; disposes safely | Yes | Reuse `popupClosed` correlation by `popupId`; teardown ordering per Canvas 15 safeguards. |
| NFR | Off-EDT decision, headless-safe construction | Yes | Mirrors Canvas 15 threading + headless invariants; covered by unit tests. |

**Coverage summary**: 7/8 ACs fully addressable with the proposed
approach; AC6 is partial pending a Canvas-level decision on the
reclaim grace-period value and the opener-dispose reclaim trigger. No
AC is unaddressable. macOS is the reference backend here; Linux/Windows
native adoption and the swingwebbrowser consumer are the sibling
stories (005-002/003/004).
