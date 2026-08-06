# Story Decomposition: Adopt Browser-Initiated Popups Into a Caller-Provided WebViewComponent (Swing Tab)

## INVEST Analysis

### Abstract Task: "Popup Adoption Into a Swing Tab"

**Analysis Dimensions**:
- **Core Responsibility**: Let an application host a browser-initiated
  popup (the engine-created, opener-linked child web view raised by
  `window.open`, `<a target="_blank">`, or `<form method="post"
  target="…">`) inside a `WebViewComponent` it supplies — i.e. a new
  tab — instead of the native top-level window the engine currently
  owns (Canvas 15). The adopted child must keep the exact navigation
  WebKit started: the POST verb and body, and opener linkage
  (`window.opener`, `postMessage`).
- **Primary Operations**: (1) decide popup disposition on the native
  UI thread — block / native-window / adopt; (2) on adopt, create the
  linked child but host it in a hidden native holder (no visible
  window); (3) notify the application asynchronously on the EDT; (4)
  construct a `WebViewComponent` that wraps the pre-existing child
  engine and reparent the native child into that component's realized
  surface; (5) observe close and dispose.
- **Key Constraints**: `popupRequested` is synchronous on the native
  UI thread and MUST NOT round-trip to the EDT (deadlock) — so the
  target component cannot be created inside it; adoption is therefore
  two-phase and asynchronous. POST body is NOT reliably exposed to the
  delegate on any engine, so replaying the request from Java is not an
  option — the engine's own child must be reused. Backward compatible
  with Canvas 15 (existing boolean handlers keep working;
  `setPopupHandler(null)` still blocks all).
- **Technical Complexity**: High — a new `WebViewComponent`
  construction mode that adopts a pre-existing native engine, native
  reparenting of an already-created child view across three engines,
  and a strict off-EDT/on-EDT threading split.
- **Business Complexity**: Medium — one primary scenario (popup →
  tab) with disposition and lifecycle variants.

### INVEST Evaluation

- ✅ **Independent**: Builds only on Canvas 15's shipped popup
  plumbing (`WebViewPopupHandler`, `PopupDispatcher`, the native
  `create`/`NewWindowRequested` sites). Each per-platform sub-story is
  independently shippable; the Java contract is designed once in
  STORY-005-001 and the other stories conform to it. The consumer
  story (STORY-005-004) is separately valuable.
- ✅ **Negotiable**: The disposition surface (enum vs. richer handler
  return), the adoption entry point, and the native reparenting
  strategy are all open to team design in `/spdd-analysis` and
  `/spdd-reasons-canvas`.
- ✅ **Valuable**: Fixes a concrete, currently-broken user outcome —
  POST-form and OAuth popups opened as tabs in a tabbed browser.
- ✅ **Estimable**: Each sub-story mirrors the dialog (Canvas 11–13)
  and popup (Canvas 15–17) staging the team has already executed
  twice.
- ✅ **Small**: Each sub-story is 2-5 days.
- ✅ **Testable**: The Java contract has unit-testable behavior
  (disposition routing, adopt-construction validation, dispatcher
  correlation); native paths are validated on-device via the demo,
  consistent with the repo's no-automated-GUI-tests policy.

**Conclusion**: Needs splitting along the same platform-backend
boundaries the dialog and popup features used, plus a consumer story.
The Java contract is established in STORY-005-001 (co-delivered with
macOS, the reference engine). STORY-005-002 (Linux) and STORY-005-003
(Windows) extend native coverage. STORY-005-004 wires swingwebbrowser
to open popups as real tabs through the adopt path.

### Split Strategy

```
STORY-005-001 (Java adopt contract + macOS WKWebView)
        │
        ├──► STORY-005-002 (Linux WebKitGTK: heavyweight + offscreen) — conforms to the 005-001 contract
        │
        ├──► STORY-005-003 (Windows WebView2) — conforms to the 005-001 contract
        │
        └──► STORY-005-004 (swingwebbrowser consumer: popups → tabs, POST preserved) — depends on the 005-001 contract; on-device needs the matching platform story
```

STORY-005-002 and STORY-005-003 can proceed in parallel once
STORY-005-001 has landed. STORY-005-004 can be developed against the
Java contract from STORY-005-001 and lights up per platform as
002/003 land.

---

## [STORY-005-001] Popup-Adoption Java Contract and macOS WKWebView Coverage

### Background

Canvas 15 ("Browser-Initiated Popups") gave the embedded page a way
to raise popups — `window.open`, `<a target="_blank">`, `<form
target="…">` — and the native engine hosts each popup's opener-linked
child web view in a **native top-level window it owns** (NSWindow /
GtkWindow / HWND). The Java `WebViewPopupHandler` only decides
allow/deny and observes open/close; it never gets to host the popup in
a Swing surface. Canvas 15 named this exact follow-up as anticipated
future work: *"A future canvas may add an 'adopt into a provided
`WebViewComponent`' path if a caller needs Swing integration."*

A tabbed browser (swingwebbrowser) built on the library wants popups
to appear as **new tabs**. With only the Canvas 15 API it must block
the native window (`popupRequested` → `false`) and re-open the popup's
URL in a fresh tab with `WebViewComponent.setUrl(url)`. That is a
plain GET, so a `<form method="post" target="…">` popup loses its POST
body entirely, and the re-opened page is no longer opener-linked
(`window.opener` is null, `postMessage` to the opener fails, OAuth
"sign-in with popup" breaks). The POST body is not reliably exposed to
the popup-create delegate on any engine, so the request cannot be
reconstructed and replayed from Java. The only faithful path is to
reuse the engine's own child — the view WebKit already drove the
original request into.

This story designs the cross-platform Java contract for **adopting**
that child into a caller-supplied `WebViewComponent`, and ships the
macOS (WKWebView) implementation as the reference backend.

### Business Value

- Provide application developers a way to host a browser-initiated
  popup inside their own Swing surface (a tab), not only in a native
  window.
- Preserve the popup's real navigation — POST method and body — and
  its opener linkage, so form-POST popups and OAuth popup flows work
  when surfaced as tabs.
- Establish the single Java contract that the Linux and Windows
  stories conform to, avoiding per-platform API drift.

### Dependencies and Assumptions

- **Prerequisites**: Canvas 15 (popup `window.open` support:
  `WebViewPopupHandler`, `WebViewPopupEvent`, `PopupDispatcher`,
  `WebViewPopupCallback`, and the native popup-create sites) is in
  place on macOS. Canvases 5/6/7 (mode selection, heavyweight,
  lightweight embedding) provide the `WebViewComponent` peer model.
- **Data assumptions**: A popup request already reaches the native
  popup-create delegate (macOS
  `createWebViewWithConfiguration:forNavigationAction:windowFeatures:`),
  which today constructs the linked child and its own NSWindow.
- **Integration points**: The consuming application
  (swingwebbrowser, STORY-005-004) installs the handler and creates
  the target tab component.
- **Business constraints**: Backward compatibility with Canvas 15 is
  mandatory — existing `boolean popupRequested` handlers and
  `setPopupHandler(null)` semantics must be unchanged for callers who
  do not opt into adoption.

### Scope In

- A popup **disposition** decision surfaced on the native UI thread:
  block, host in a native window (today's behavior), or adopt into a
  Swing component. Adoption is requested here but performed later.
- A hidden-holder path: on an adopt decision the macOS engine creates
  the opener-linked child WKWebView but does NOT create or show its
  own NSWindow (no visible flash), retaining the child under an
  offscreen holder keyed by the popup id.
- An asynchronous EDT notification that carries enough to build the
  tab and request adoption (the popup id + event fields).
- A `WebViewComponent` construction/attach mode that wraps the
  pre-existing macOS child engine identified by the popup id and
  reparents the child WKWebView into the component's realized surface,
  instead of creating a new engine.
- Backward-compatible handler API: existing boolean handlers keep
  working (mapped to the native-window disposition);
  `setPopupHandler(null)` still blocks all popups.
- macOS (WKWebView) native implementation of the above.
- `PopupDispatcher` / handler unit tests for the new disposition
  routing, adopt-request plumbing, correlation, and dispose behavior.
- README + demo coverage of the adopt path on macOS.

### Scope Out

- Linux (STORY-005-002) and Windows (STORY-005-003) native adoption.
- swingwebbrowser consumer wiring (STORY-005-004).
- Reparenting an *arbitrary* running `WebViewComponent` between
  windows — this story only adopts a **freshly engine-created popup
  child**, once, at popup time.
- Exposing the raw HTTP method/body to Java — adoption reuses the
  engine's child precisely because the request cannot be surfaced;
  no `postUrl`/request-object API is added.
- Per-popup window chrome, feature strings beyond width/height, HTTP
  auth / download / permission channels (unchanged from Canvas 15).

### Acceptance Criteria

#### AC1: A POST-form popup adopted into a tab keeps its POST body
**Given** an embedded page with `<form method="post" action="/echo"
target="win"><input name="q" value="hello"></form>` and an installed
handler that requests **adopt** disposition
**When** the form is submitted and the application adopts the popup
into a new tab's `WebViewComponent`
**Then** the adopted tab shows the server's response to the **POST**
(the echoed body contains `q=hello`), not a GET of `/echo`.

#### AC2: An adopted popup remains opener-linked
**Given** a page that opens a popup via
`window.open('child.html','win')` and the popup calls
`window.opener.postMessage('ready','*')` then `window.close()`
**When** the popup is adopted into a tab
**Then** `window.opener` in the popup is non-null, the opener page
receives the `'ready'` message, and `window.close()` closes the
adopted tab's popup.

#### AC3: Adopt disposition shows no native popup window
**Given** a handler that requests adopt disposition
**When** a popup is raised
**Then** no separate native top-level popup window appears at any
point; the popup content becomes visible only once it is adopted into
the caller's component.

#### AC4: Backward compatibility — existing allow handler still opens a native window
**Given** a handler that uses only the Canvas 15 API and allows the
popup (the default, or `popupRequested` returning `true`)
**When** a popup is raised
**Then** the popup opens in a native top-level window exactly as
before this story — no adoption occurs and no behavior changes.

#### AC5: Backward compatibility — blocking still blocks
**Given** `setPopupHandler(null)` (or a handler that denies the popup)
**When** a popup is raised
**Then** the popup is blocked (`window.open` returns `null`) and no
child is created or retained — identical to Canvas 15.

#### AC6: A requested adoption that is never claimed does not leak
**Given** a handler that requests adopt disposition but the
application never constructs a component to adopt the popup
**When** a bounded grace period elapses (or the opener is disposed)
**Then** the retained child engine and its hidden holder are torn down
and the popup is treated as closed — no orphaned native view or engine
remains.

#### AC7: Adopting an unknown or already-adopted popup id fails cleanly
**Given** an application that attempts to adopt a popup id that was
never issued, or one it has already adopted
**When** it constructs the adopting `WebViewComponent`
**Then** the attempt is rejected with a clear, documented error and no
partially-constructed component or native view is left behind.

#### AC8: Closing an adopted popup notifies the application
**Given** a popup adopted into a tab
**When** the popup page calls `window.close()` or the user closes the
tab
**Then** the application receives the close notification correlated to
the same popup, and the adopted component and child engine are
disposed without dereferencing freed native state.

#### Non-Functional Expectations
- The disposition decision on the native UI thread must remain fast
  and must not access Swing or block on the EDT (the deadlock
  constraint from Canvas 15 is preserved).
- Constructing the adopting `WebViewComponent`, and all handler
  notifications, must be safe on the EDT and must not throw
  `HeadlessException` in headless construction paths that do not
  realize a peer.

---

## [STORY-005-002] Linux WebKitGTK Coverage for Popup Adoption (Heavyweight + Offscreen)

### Background

STORY-005-001 establishes the Java adoption contract and ships the
macOS backend. Canvas 16 already wired the Linux WebKitGTK `create`
signal so `window.open` opens a native, opener-linked popup on Linux
for both the heavyweight (X11-reparented) and offscreen/lightweight
engines. This story extends those same sites so an adopt disposition
retains the `webkit_web_view_new_with_related_view` child under a
hidden holder and lets the application reparent it into a
`WebViewComponent`'s realized GTK/X11 surface, instead of hosting it
in the engine-owned `GtkWindow`.

### Business Value

- Bring the popup-adoption capability to Linux users of the library
  and of swingwebbrowser, at parity with macOS.
- Cover both embedding modes (heavyweight and offscreen) with the
  shared WebKitGTK engine, as the popup and dialog features did.

### Dependencies and Assumptions

- **Prerequisites**: STORY-005-001 (Java contract) merged; Canvas 16
  (Linux popup `create`-signal support) in place.
- **Data assumptions**: The Linux `create` signal already produces an
  opener-linked child that today is placed in a `GtkWindow`.
- **Integration points**: Same handler/component API as STORY-005-001;
  no Java API changes.
- **Business constraints**: No regression to the Canvas 16
  native-window popup path for callers who do not adopt.

### Scope In

- On adopt disposition, retain the WebKitGTK child under a hidden
  holder (unrealized/unshown top-level or offscreen container) keyed
  by popup id, without showing the engine-owned window.
- Reparent the retained child widget into the adopting
  `WebViewComponent`'s realized surface for both heavyweight and
  offscreen engines.
- Close/teardown wiring for the adopted child on both modes.

### Scope Out

- Java contract changes (owned by STORY-005-001).
- macOS / Windows backends.
- swingwebbrowser wiring (STORY-005-004).

### Acceptance Criteria

#### AC1: POST-form popup adopted into a tab keeps its POST body (Linux, heavyweight)
**Given** a heavyweight `WebViewComponent` on Linux and a handler
requesting adopt disposition
**When** a `<form method="post" target="win">` popup is submitted and
adopted into a new tab
**Then** the adopted tab shows the POST response with the submitted
body intact.

#### AC2: POST-form popup adopted into a tab keeps its POST body (Linux, offscreen)
**Given** an offscreen/lightweight `WebViewComponent` on Linux and a
handler requesting adopt disposition
**When** a `<form method="post" target="win">` popup is submitted and
adopted into a new tab
**Then** the adopted tab shows the POST response with the submitted
body intact.

#### AC3: Opener linkage preserved on Linux
**Given** an adopted popup on Linux
**When** the popup calls `window.opener.postMessage(...)`
**Then** the opener page receives the message and `window.close()`
closes the adopted tab's popup.

#### AC4: No engine-owned popup window on adopt
**Given** an adopt disposition on Linux
**When** a popup is raised
**Then** no `GtkWindow` popup appears; content is visible only after
adoption.

#### AC5: Backward compatibility on Linux
**Given** a Canvas 16 allow handler (native window) or
`setPopupHandler(null)`
**When** a popup is raised
**Then** the native-window popup opens (allow) or is blocked (null),
exactly as before this story.

---

## [STORY-005-003] Windows WebView2 Coverage for Popup Adoption

### Background

STORY-005-001 establishes the Java adoption contract and ships macOS.
Canvas 17 wired the Windows WebView2 `NewWindowRequested` event so
`window.open` opens a native, opener-linked popup on Windows via a
child controller from the same environment (`put_NewWindow`). This
story extends that handler so an adopt disposition creates the child
controller against a hidden holder window and lets the application
reparent/attach the child WebView2 into a `WebViewComponent`'s
realized `HWND`, instead of showing the engine-owned popup window.

### Business Value

- Bring popup adoption to Windows users at parity with macOS and
  Linux.
- Complete the three-platform matrix so swingwebbrowser's tabbed
  popups work everywhere.

### Dependencies and Assumptions

- **Prerequisites**: STORY-005-001 (Java contract) merged; Canvas 17
  (Windows popup `NewWindowRequested` support) in place.
- **Data assumptions**: The WebView2 `NewWindowRequested` deferral
  path already creates a child controller from the same environment.
- **Integration points**: Same handler/component API; no Java API
  changes.
- **Business constraints**: No regression to the Canvas 17
  native-window popup path for non-adopting callers.

### Scope In

- On adopt disposition, create the WebView2 child controller against a
  hidden holder `HWND` (not shown), retained by popup id, completing
  the `NewWindowRequested` deferral with the child assigned.
- Reparent/attach the child controller into the adopting
  `WebViewComponent`'s realized `HWND` and track bounds.
- Close/teardown wiring (`WindowCloseRequested`) for the adopted
  child.

### Scope Out

- Java contract changes (owned by STORY-005-001).
- macOS / Linux backends.
- swingwebbrowser wiring (STORY-005-004).

### Acceptance Criteria

#### AC1: POST-form popup adopted into a tab keeps its POST body (Windows)
**Given** a `WebViewComponent` on Windows and a handler requesting
adopt disposition
**When** a `<form method="post" target="win">` popup is submitted and
adopted into a new tab
**Then** the adopted tab shows the POST response with the submitted
body intact.

#### AC2: Opener linkage preserved on Windows
**Given** an adopted popup on Windows
**When** the popup calls `window.opener.postMessage(...)` then
`window.close()`
**Then** the opener receives the message and the adopted tab's popup
closes.

#### AC3: No engine-owned popup window on adopt
**Given** an adopt disposition on Windows
**When** a popup is raised
**Then** no separate WebView2 popup window appears; content is visible
only after adoption.

#### AC4: Backward compatibility on Windows
**Given** a Canvas 17 allow handler (native window) or
`setPopupHandler(null)`
**When** a popup is raised
**Then** the native-window popup opens (allow) or is blocked (null),
exactly as before this story.

---

## [STORY-005-004] swingwebbrowser: Open Browser Popups as Real Tabs via Adoption

### Background

swingwebbrowser currently opens browser-initiated popups as new tabs
by blocking the native popup window and calling `newTab(targetUrl)`,
which issues a GET (`BrowserTab.java` popup handler →
`BrowserFrame.onPopupRequested` → `newTab` → `setUrl`). This drops the
POST body of `<form method="post" target="…">` popups and breaks
opener linkage. This story rewires swingwebbrowser onto the popup
adoption contract from STORY-005-001 so popups become tabs that host
the engine's real opener-linked child, preserving POST and
`window.opener`.

### Business Value

- Deliver the actual user-visible outcome: form-POST popups and OAuth
  "sign-in with popup" flows open correctly as tabs in the browser.
- Remove the lossy GET-reopen workaround.

### Dependencies and Assumptions

- **Prerequisites**: STORY-005-001 (Java adopt contract) available in
  the swingwebview dependency; on-device correctness on a given OS
  additionally requires the matching platform story (005-002 /
  005-003).
- **Data assumptions**: swingwebbrowser already manages tabs and
  `WebViewComponent`s per tab.
- **Integration points**: The swingwebview popup-adoption API.
- **Business constraints**: The tab UX (title, history, close) must
  work for adopted popup tabs as for normal tabs.

### Scope In

- Replace the block-and-GET-reopen popup handler with an
  adopt-disposition handler: request adoption on the native thread,
  and on the EDT notification create a new tab whose
  `WebViewComponent` adopts the popup child.
- Ensure adopted popup tabs participate in normal tab behavior
  (selection, title updates, close, dev console retargeting).
- Handle the not-adopted / failed-adopt cases gracefully (no orphan
  tabs; the popup is dropped cleanly).

### Scope Out

- Any change to the swingwebview library contract (owned by
  STORY-005-001).
- Native engine changes.

### Acceptance Criteria

#### AC1: POST-form popup opens as a tab with POST preserved
**Given** the browser is on a page with `<form method="post"
action="/echo" target="popup"><input name="q" value="hi"></form>`
**When** the user submits the form
**Then** a new tab opens showing the server's POST response echoing
`q=hi` — not a GET of `/echo`.

#### AC2: window.open popup opens as an opener-linked tab
**Given** a page that calls
`window.open('child.html','popup','width=520,height=640')`
**When** the popup is created
**Then** a new tab opens loading `child.html`, `window.opener` is
non-null in that tab, and `window.opener.postMessage(...)` reaches the
originating tab.

#### AC3: target="_blank" link opens as a tab
**Given** a page with `<a href="page2.html" target="_blank">open</a>`
**When** the user clicks the link
**Then** a new tab opens loading `page2.html`.

#### AC4: Popup that calls window.close() closes its tab
**Given** a popup tab whose page calls `window.close()`
**When** that happens
**Then** the corresponding tab closes and browser state stays
consistent (active tab, tab list).

#### AC5: No lost popups or orphan tabs
**Given** any allowed popup
**When** it is created and adopted
**Then** exactly one tab is created for it; a popup that fails to
adopt produces no tab and no error dialog surfaced to the user beyond
a logged diagnostic.

#### Non-Functional Expectations
- Popup-to-tab creation must feel immediate to the user (no visible
  intermediate native window, no perceptible flash).
