---
generated_at: 2026-05-17T15:30:00-07:00
---

# REASONS Canvas: Swing WebView Right-Click Context Menu and DOM Mouse Event Channel

## R · Requirements
- Surface page-side DOM `contextmenu` events (right-clicks inside the
  embedded WebView) to Java callers as structured, EDT-delivered
  `WebViewMouseEvent` callbacks carrying a `DomTarget` descriptor of
  the element under the cursor. The same primitive must be designed
  to extend to other DOM mouse-event kinds later (`mousedown`,
  `mouseup`, `dblclick`, `dragstart`) without breaking binary
  compatibility, but this canvas ships only `contextMenuRequested`.
- Listener registration / unregistration must be exposed on
  `WebViewComponent` as `addWebViewMouseListener(WebViewMouseListener)`
  / `removeWebViewMouseListener(WebViewMouseListener)`, available to
  both subclasses ([[swing-heavyweight-webview-embedding]] and
  [[swing-lightweight-webview-embedding]]). Listeners added before
  the native peer attaches are remembered; they start receiving
  events when the peer becomes live and the JS shim is installed.
- The browser's platform-default context menu (Inspect Element,
  Copy / Cut / Paste, etc.) MUST be auto-suppressed for as long as
  any `WebViewMouseListener` is registered, and MUST automatically
  reappear once the last listener is removed. Callers may opt back
  into the platform default explicitly via
  `setDefaultContextMenuEnabled(true)` on `WebViewComponent`; this
  setting persists across page navigations within the same
  component lifetime. The matching `isDefaultContextMenuEnabled()`
  accessor returns the effective state (default `true`).
- Provide a Swing-side convenience helper
  `ca.weblite.webview.swing.WebViewContextMenu` that takes a
  caller-supplied `Function<WebViewMouseEvent, JPopupMenu>` builder
  and handles wiring (`attachTo(WebViewComponent)` / `detach()`),
  null-builder-return suppression, and `JPopupMenu.show` placement
  at `WebViewMouseEvent.toComponentPoint()`.
- All listener callbacks MUST run on the Swing Event Dispatch
  Thread (matches the `ConsoleListener` contract in
  [[swing-webview-component-mode-selection]]).
- The reserved JS binding name `__webview_dom_event` MUST be
  protected from caller collision by the existing
  `RESERVED_BINDING_PREFIX = "__webview_"` check on
  `WebViewComponent.addJavascriptCallback`
  (`WebViewComponent.java:51`, enforced at
  `WebViewHeavyweightComponent.java:152-157` and
  `WebViewLightweightComponent.java:390-395`). No new guard is
  required — the existing prefix check is the AC22 mechanism.
- The implementation MUST NOT introduce a JSON-parsing dependency
  (the project has none — `pom.xml:43-50` declares only JUnit-test
  dependency). Wire format follows the precedent set by
  `ConsoleDispatcher` (`ConsoleDispatcher.java:64-145`):
  base64-encoded UTF-8 of a pipe-separated record posted as the
  single string argument of a JS-binding call.
- The implementation MUST NOT require new JNI entry points. Both
  `EmbeddedWebView` (`EmbeddedWebView.java:121-155`) and
  `OffscreenWebView` (`OffscreenWebView.java:126-163`) already
  expose `addOnBeforeLoad`, `addJavascriptCallback`, and `eval` —
  these three operations are sufficient to install the JS shim,
  bind the reserved channel, and toggle the runtime suppression
  flag.
- The JS shim MUST be idempotent across page navigations: each new
  document load installs the shim cleanly and listeners continue
  to receive events without re-registration (re-entry guarded by
  `window.__webview_dom_event_installed__`, mirroring
  `ConsoleDispatcher.SHIM_JS` at `ConsoleDispatcher.java:67-68`).
- Definition of Done: a `WebViewContextMenuDemo` Swing app under
  `demos/` exercises right-click on plain content, links, images,
  text-input fields, and a text selection, with the demo
  populating a Swing `JPopupMenu` whose contents depend on the
  `DomTarget`. README under "Demos" lists it alongside the
  existing heavyweight / console demos. No automated tests — this
  is GUI integration code, consistent with
  [[swing-heavyweight-webview-embedding]] and
  [[swing-webview-component-mode-selection]].
- Out of scope (explicit non-goals):
  - DOM mouse event kinds other than `contextmenu`
    (`mousedown`/`mouseup`/`dblclick`/`dragstart`,
    `mouseenter`/`mouseleave`/`mousemove`).
  - Drag-and-drop initiation (page-to-Swing or Swing-to-page).
  - Custom tooltip placement driven by DOM hover state.
  - Native OS context menus (`NSMenu` / `GtkMenu` / `HMENU`).
  - Re-injection of an "Inspect Element" item into caller-built
    JPopupMenus when the platform default is suppressed.
  - Adding the listener API to the standalone in-process
    `WebView` (this is for embedded usage only, matching the
    Scope-Out in [[swing-webview-component-mode-selection]]).
  - Programmatic dispatch of synthetic right-click events into the
    page from Java.
  - Cross-origin iframe descriptors — the top-level `contextmenu`
    listener cannot observe events that originate inside
    cross-origin iframes (the browser does not propagate them
    across origin boundaries).
  - HiDPI / DPI scaling handling beyond what the existing
    heavyweight / lightweight components already provide.

## E · Entities

- **WebViewMouseEvent** (new public class,
  `src/ca/weblite/webview/WebViewMouseEvent.java`). Immutable
  value type. Invariants:
  - `type: String` — the DOM event type. Fixed to `"contextmenu"`
    in this canvas (`WebViewMouseEvent.EVENT_CONTEXT_MENU`). The
    field is a string rather than an enum so adding event kinds
    later does not require a binary-compatibility-breaking
    enum change.
  - `button: int` — the DOM `MouseEvent.button` value plus 1, so
    1 = primary (left), 2 = middle, 3 = secondary (right). For a
    `contextmenu` event this is always `3`.
  - `shiftDown / ctrlDown / altDown / metaDown: boolean` — DOM
    `MouseEvent.shiftKey / ctrlKey / altKey / metaKey`.
  - `clientX / clientY: int` — viewport-relative CSS pixels.
  - `pageX / pageY: int` — document-relative CSS pixels.
  - `screenX / screenY: int` — screen-relative CSS pixels (see
    Safeguards for the macOS WKWebView reliability caveat).
  - `timeStamp: long` — DOM `Event.timeStamp` truncated to a
    whole millisecond; monotonic, milliseconds since the
    top-level document's time origin (NOT wall-clock).
  - `target: DomTarget` — non-null; constructed by the dispatcher
    from the payload before the event is published.
  - `source: WebViewComponent` — non-null; the component the
    event came from.
  - All getters use the canonical `accessor()` style (no `get`
    prefix) matching `ConsoleMessage` / `WebView` accessors
    elsewhere in the codebase (`WebView.java:151`,
    `ConsoleMessage.java`).
  - Convenience helpers:
    - `toComponentPoint(): Point` — returns
      `new Point(clientX, clientY)`. The WebView component's
      content area corresponds 1:1 with the page viewport, so
      viewport CSS pixels = component logical pixels on all
      platforms the component supports today.
    - `toScreenPoint(): Point` — returns
      `new Point(screenX, screenY)`. Subject to the macOS
      WKWebView screenX/Y caveat in Safeguards.
  - Constructor is package-private; the only legitimate
    construction site is `WebViewMouseDispatcher.dispatch`.

- **DomTarget** (new public class,
  `src/ca/weblite/webview/DomTarget.java`). Immutable descriptor of
  the right-clicked element. Invariants:
  - `tagName: String` — uppercase ASCII (e.g. `"DIV"`, `"A"`,
    `"IMG"`, `"BODY"`). Never null.
  - `id: String` — the element's `id` attribute or `""` (never
    null) when absent.
  - `classes: List<String>` — read-only (`Collections.unmodifiableList`);
    empty list (never null) when the element has no `class`
    attribute. Order preserves the source order from
    `Element.classList`.
  - `attributes: Map<String, String>` — read-only
    (`Collections.unmodifiableMap`); never null. Contents are a
    *curated* subset, not a complete enumeration:
    - Always-present-if-set: `href`, `src`, `alt`, `title`,
      `name`, `type`, `value`, `role`.
    - All `data-*` keys present on the element, subject to the
      8 KiB total cap (see Safeguards).
    - `<input type="password">` reports `value` as `""`
      regardless of actual content (story NF expectation).
  - `linkHref: String` — `href` of the nearest ancestor `<a>`
    element (including `target` itself), or `null` when none.
  - `imageSrc: String` — `src` of the target if `tagName` is
    `"IMG"`, otherwise `null`.
  - `mediaSrc: String` — `src` (or `currentSrc` when populated)
    of the target if `tagName` is `"AUDIO"` or `"VIDEO"`,
    otherwise `null`.
  - `contentEditable: boolean` — `true` when the JS-side
    `target.isContentEditable` is `true`, OR `tagName` is
    `"INPUT"` AND `type` is in `{"text","search","email","url",
    "tel","password","number"}`, OR `tagName` is `"TEXTAREA"`.
    Accessor: `isContentEditable()` (boolean accessors keep the
    `is` prefix per Java convention).
  - `selectionText: String` — `window.getSelection().toString()`
    truncated to 64 KiB UTF-8 (see Safeguards); empty string
    (never null) when there is no selection.
  - `pageUrl: String` — `document.location.href` of the top-level
    document.
  - `frameUrl: String` — `document.location.href` of the
    immediate document the target is in; equals `pageUrl` when
    not inside an iframe.
  - Constructor is package-private; constructed only by the
    payload-parsing path on `WebViewMouseDispatcher`.

- **WebViewMouseListener** (new public functional interface,
  `src/ca/weblite/webview/WebViewMouseListener.java`). Invariants:
  - Single abstract method `void contextMenuRequested(WebViewMouseEvent event)`.
  - Annotated `@FunctionalInterface` so lambdas compile against
    it.
  - Forward-extension axis: future event kinds (e.g.
    `mousePressed`, `mouseClicked`, `dragStarted`) MUST be added
    as `default` methods with empty bodies, preserving binary
    compatibility for callers compiled against today's interface.
    Adding new abstract methods is a breaking change and MUST
    NOT be done.
  - Invoked on the Swing EDT exclusively; the contract is
    documented in the Javadoc.

- **WebViewMouseDispatcher** (new public class,
  `src/ca/weblite/webview/WebViewMouseDispatcher.java`). Mirrors
  `ConsoleDispatcher` (`ConsoleDispatcher.java:34-145`) line-for-line
  except for payload shape. Internal-only — the class is `public`
  for cross-package access from `ca.weblite.webview.swing`, NOT
  part of the supported API (Javadoc says so prominently, matching
  the existing wording on `ConsoleDispatcher.java:15-23`).
  Invariants:
  - `SHIM_JS: public static final String` — the canonical JS
    shim, installed via `addOnBeforeLoad` at peer-attach time.
    See Operation 4 for the full text.
  - `CHANNEL_NAME: public static final String` — `"__webview_dom_event"`.
    Used by both subclasses when binding the reserved callback.
  - `listeners: CopyOnWriteArrayList<WebViewMouseListener>` —
    iteration captures a stable snapshot, so add/remove during
    fan-out only takes effect for the NEXT event (matches
    `ConsoleDispatcher.java:225-229`).
  - `defaultOverride: volatile Boolean` — explicit override state.
    `null` means "no override has been set; follow the auto-suppress
    policy" (the initial state).  Non-null means the caller has used
    `setDefaultContextMenuEnabled(boolean)` and the dispatcher honors
    that value verbatim regardless of listener count.  The field is
    `Boolean` (not `boolean`) precisely so the dispatcher can tell
    "auto" apart from "explicit `true`" — a plain boolean cannot
    represent both an initial-default state AND an explicit `true`
    override at the same time.
  - `source: final WebViewComponent` — non-null; supplied at
    construction. Used to populate `WebViewMouseEvent.source()`.
  - `flagSink: volatile FlagSink` — the path through which the
    dispatcher mutates the JS-side suppress flag. Lazily set by
    the owning component on peer-attach; null until then. When
    null, `eval`-side updates are no-ops but
    `addOnBeforeLoad`-side updates accumulate into a pending
    list. See Operation 4.
  - `pendingPreloads: List<String>` — initialized empty;
    populated only when `flagSink == null`. Each entry is a
    `window.__webview_dom_event_suppress=…;` statement that will
    be replayed via `addOnBeforeLoad` once the sink is attached.
  - Public methods (visible to the swing-package components only,
    by convention):
    - `addListener(WebViewMouseListener)` — null-check; append
      to `listeners`; if `flagSink != null`, re-evaluate the
      suppress flag (Operation 4).
    - `removeListener(WebViewMouseListener)` — null-tolerant
      (silently return on null, matching `ConsoleDispatcher.java:158-161`);
      remove from `listeners`; if `flagSink != null`,
      re-evaluate the suppress flag.
    - `hasListeners(): boolean` — returns `!listeners.isEmpty()`.
    - `isDefaultEnabled(): boolean` — returns the **effective**
      default-menu-enabled state:
      `(defaultOverride != null) ? defaultOverride : !hasListeners()`.
      Callers asking "if a right-click happens now, will the
      platform default menu appear?" get the answer.
    - `setDefaultEnabled(boolean): void` — set
      `defaultOverride = Boolean.valueOf(enabled)`; re-evaluate
      the suppress flag.
    - `attachFlagSink(FlagSink): void` — set the sink; replay
      `pendingPreloads` into the sink (one `addOnBeforeLoad` per
      entry); then re-evaluate the suppress flag once. Called
      from `createPeer` / `addNotify` after the binding +
      shim install.
    - `dispatch(String rawJson)` — parse the
      `{"name":…,"seq":…,"args":["<b64>"]}` wrapper using a
      verbatim copy of `ConsoleDispatcher.extractFirstArg` (or
      a shared helper — see Norms); base64-decode the outer
      payload to UTF-8; parse the pipe-separated record into a
      `WebViewMouseEvent` (Operation 4 details the format);
      drop the event silently if parsing fails for any reason;
      hop to the EDT via the same
      `SwingUtilities.isEventDispatchThread()` short-circuit +
      `invokeLater` pattern as `ConsoleDispatcher.java:217-225`;
      iterate `listeners` and call each listener's
      `contextMenuRequested(event)` wrapped in
      `try { … } catch (Throwable t) { … }` that forwards `t` to
      `Thread.getDefaultUncaughtExceptionHandler()` exactly as
      `ConsoleDispatcher.java:231-244` does.
  - Inner type `FlagSink` (package-private interface):
    - `void eval(String js)` — evaluate the JS now (current
      document only).
    - `void addOnBeforeLoad(String js)` — register the JS as a
      document-start init script that runs on every navigation.
    - Implemented inline by `WebViewHeavyweightComponent` and
      `WebViewLightweightComponent` against their respective
      peers (`EmbeddedWebView` / `OffscreenWebView`).
  - Suppress-flag computation (3-state, see Approach):
    ```
    enabled  = (defaultOverride != null) ? defaultOverride : !hasListeners();
    suppress = !enabled;
    ```
    Every time the dispatcher re-evaluates the flag, it MUST
    issue BOTH a synchronous `eval` (current document) AND a
    fresh `addOnBeforeLoad` (next navigation). The
    addOnBeforeLoad payload IS the `eval` payload — the same
    statement, registered as an init script. Accumulation across
    repeated toggles is accepted (see Safeguards).

- **WebViewContextMenu** (new public class,
  `src/ca/weblite/webview/swing/WebViewContextMenu.java`). Pure
  composition over the Layer-1 API; no privileged access to
  anything internal. Invariants:
  - `menuBuilder: final Function<WebViewMouseEvent, JPopupMenu>`
    — non-null; supplied at construction. Throw
    `NullPointerException` with message `"menuBuilder"` from
    the constructor.
  - `attached: WebViewComponent` — null until `attachTo` runs;
    cleared by `detach()`. A second `attachTo` call against the
    same instance MUST throw
    `IllegalStateException("WebViewContextMenu is already attached.")`
    rather than silently re-wiring; matches the "attach once"
    contract callers see in `JPopupMenu.show` semantics.
  - `listener: WebViewMouseListener` — the helper's internal
    listener instance, retained so `detach()` can pass it back
    to `removeWebViewMouseListener`.
  - On `contextMenuRequested(event)`:
    1. Call `menu = menuBuilder.apply(event)`.
    2. If `menu == null`, return (caller-driven suppression).
    3. Compute `p = event.toComponentPoint()`.
    4. Call `menu.show(event.source(), p.x, p.y)`.
    Any exception thrown by `menuBuilder.apply` or by `menu.show`
    propagates to the dispatcher's per-listener try/catch — no
    swallowing in the helper.

- **WebViewComponent** (modified;
  `src/ca/weblite/webview/swing/WebViewComponent.java`). New
  members on the abstract base:
  - `protected final WebViewMouseDispatcher mouseDispatcher` —
    constructed in the no-arg constructor with `this` as the
    source. Initialized analogously to the existing
    `consoleDispatcher` field (currently
    `WebViewComponent.java:57`).
  - Four new abstract methods:
    - `addWebViewMouseListener(WebViewMouseListener listener): WebViewComponent`
    - `removeWebViewMouseListener(WebViewMouseListener listener): WebViewComponent`
    - `setDefaultContextMenuEnabled(boolean enabled): WebViewComponent`
    - `isDefaultContextMenuEnabled(): boolean`
  - Identical implementations in both subclasses (each just
    delegates to `mouseDispatcher`) — abstract on the base to
    match the existing surface convention; alternatively kept
    `final` on the base if a future maintainer prefers DRY.
    See Norms for the recommendation (keep them concrete-final
    on the base since they don't depend on subclass state).

- **WebViewHeavyweightComponent** (modified,
  `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`).
  New behaviour layered onto the existing
  [[swing-heavyweight-webview-embedding]] entity:
  - Inside `createPeer()` (currently `WebViewHeavyweightComponent.java:394`),
    AFTER the existing console-bridge install
    (`WebViewHeavyweightComponent.java:410-417`) and BEFORE the
    pending-init-scripts replay
    (`WebViewHeavyweightComponent.java:418-420`), install the
    mouse bridge:
    1. Build a `FlagSink` that calls
       `embedded.eval(js)` / `embedded.addOnBeforeLoad(js)`.
    2. `embedded.addOnBeforeLoad(WebViewMouseDispatcher.SHIM_JS)`.
    3. `embedded.addJavascriptCallback(
            WebViewMouseDispatcher.CHANNEL_NAME,
            arg -> mouseDispatcher.dispatch(arg))`.
       Bypasses the public `addJavascriptCallback` so the
       reserved-prefix check does not reject the channel — same
       trick the existing console bridge uses
       (`WebViewHeavyweightComponent.java:411-417`).
    4. `mouseDispatcher.attachFlagSink(sink)` — this replays any
       pending suppression statements into the peer and emits
       one fresh re-evaluation.
  - The four new public methods all delegate to
    `mouseDispatcher` and return `this`.

- **WebViewLightweightComponent** (modified,
  `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`).
  Symmetric new behaviour on the [[swing-lightweight-webview-embedding]]
  side:
  - Inside `addNotify()` (currently
    `WebViewLightweightComponent.java:189`), AFTER the existing
    console-bridge install (`WebViewLightweightComponent.java:209-216`)
    and BEFORE the pending-init-scripts replay
    (`WebViewLightweightComponent.java:217-219`), install the
    mouse bridge using `engine` (the `OffscreenWebView`) instead
    of `embedded`. Same four steps as in the heavyweight path.
  - If `engine == null` (non-Linux platform short-circuit at
    `WebViewLightweightComponent.java:195-204`), skip the install
    entirely — there is no peer to bind against. The dispatcher's
    `flagSink` stays `null`, listener registration still works,
    but no events ever fire and the suppression flag has no
    effect. This is the same degradation mode the lightweight
    component already exhibits for `addJavascriptCallback`,
    `eval`, etc.
  - The four new public methods all delegate to
    `mouseDispatcher` and return `this`.

- **WebViewContextMenuDemo** (new directory,
  `demos/WebViewContextMenuDemo/`). Mirrors the existing
  `demos/WebViewConsoleDemo/` and
  `demos/WebViewHeavyweightDemo/` layout (source root +
  per-platform run shim if needed). Exercises:
  - A small inline HTML page (data URL) containing a link, an
    image, an `<input type="text">`, an `<input type="password">`,
    a `<textarea>`, and a paragraph of plain text.
  - A `WebViewContextMenu` whose builder inspects the
    `WebViewMouseEvent` and constructs different menu items
    based on `target.linkHref()`, `target.imageSrc()`,
    `target.isContentEditable()`, and `target.selectionText()`.
  - Sets `JPopupMenu.setDefaultLightWeightPopupEnabled(false)`
    and `ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false)`
    at startup (matches
    `demos/WebViewHeavyweightDemo/WebViewHeavyweightDemo.java:83-84`
    and `demos/WebViewConsoleDemo/WebViewConsoleDemo.java:41-42`).

## A · Approach

- **Replicate the `ConsoleDispatcher` pattern for a second,
  parallel channel.** The existing console-capture wiring
  (`ConsoleDispatcher.java`, plus the
  `embedded.addOnBeforeLoad(ConsoleDispatcher.SHIM_JS)` +
  `embedded.addJavascriptCallback("__webview_console__", …)` pair
  in both subclasses' peer-bring-up paths) is the line-for-line
  template for this story. The same patterns are reused without
  generalization: separate dispatcher class, separate `SHIM_JS`
  constant, separate `CHANNEL_NAME` constant, separate
  `CopyOnWriteArrayList`, separate EDT-marshal + per-listener
  try/catch. Extracting a shared base class would eliminate
  ~50 lines of duplication but would require type-parameterizing
  the payload parser; the codebase's existing precedent (deliberate
  parallelism between `EmbeddedWebView` and `OffscreenWebView`)
  argues against the abstraction.

- **No new JNI.** Both peers already expose `addOnBeforeLoad`,
  `addJavascriptCallback`, and `eval`. The story lands as
  Java + injected JavaScript only — no `WebViewNative` changes,
  no `src_c/` changes, no `windows/` changes.

- **Eager shim install, gated suppression.** The JS shim is
  installed unconditionally in both subclasses' peer-bring-up
  paths, regardless of whether any Java listener is currently
  registered. The shim's `contextmenu` handler always builds the
  payload and posts via the binding; whether or not it calls
  `preventDefault()` is gated by `window.__webview_dom_event_suppress`,
  which the dispatcher initially leaves `false`.  With zero
  listeners AND no override (`defaultOverride == null`), the
  auto rule gives `enabled = !hasListeners() = true`, so
  `suppress = false`, the shim does no preventDefault, and the
  platform default menu fires as before — AC11 holds.  Going
  through the shim every right-click costs roughly one small JS
  function invocation; the alternative (lazy install on first
  listener add) would require coordinating shim-install with
  binding-bind and re-evaluating the flag on every state
  change.  Eager install is simpler and matches the
  console-bridge pattern.

- **Effective suppression follows an auto-vs-override rule.**
  When the caller has NOT used `setDefaultContextMenuEnabled`
  (i.e. the dispatcher's `defaultOverride` field is `null`), the
  platform menu is suppressed iff at least one listener is
  registered — the story's "auto-suppress when listener
  registered" policy.  When the caller HAS used
  `setDefaultContextMenuEnabled(b)`, the override wins verbatim:
  `b == true` means platform menu shows (listener still fires),
  `b == false` means platform menu is suppressed regardless of
  listener count.  Concretely:
  ```
  enabled  = (override != null) ? override : !hasListeners();
  suppress = !enabled;
  ```
  A plain `boolean defaultEnabled` (default `true`) does NOT
  work: with a single field there is no way to distinguish "no
  override has been set; auto policy applies" from "caller
  explicitly set override = true".  The first listener add must
  produce `suppress = true` (auto policy), but if `defaultEnabled`
  reads as `true` because that is the initial value, the formula
  `!defaultEnabled && hasListeners()` short-circuits and the
  platform menu is never suppressed.  This was a real bug found
  during macOS manual testing; the 3-state
  `Boolean defaultOverride` (null / true / false) is the fix.
  The dispatcher computes the effective suppression on every
  listener add/remove and on every `setDefaultContextMenuEnabled`
  call, then mirrors the result into
  `window.__webview_dom_event_suppress` via BOTH `eval` (apply
  to the current document) AND `addOnBeforeLoad` (apply on every
  subsequent navigation).  The eval-only alternative would reset
  the flag every navigation; the addOnBeforeLoad-only alternative
  would not take effect on the current document.  Both are
  required.

- **Cross-navigation persistence via append-only init scripts.**
  Each engine's `addOnBeforeLoad` is append-only — there is no
  removal API. Each suppression-state transition appends one
  short statement (e.g. `"window.__webview_dom_event_suppress=true;"`).
  At document-start of the next navigation, all the appended
  statements run in registration order, last-assignment-wins.
  Accumulation cost is negligible in practice (most callers
  flip 2-3 times per session). See Safeguards for the hard
  limit and Norms for the canonicalization rule.

- **Single binding channel `__webview_dom_event` covers all
  future event kinds.** The wire payload's first field is the
  event type string, so adding `mousedown`/`dblclick`/etc.
  later is a JS-shim extension plus a switch on the parsed type
  on the Java side — no new bindings, no new RESERVED_BINDING
  entries. Matches the design choice in
  [[swing-webview-component-mode-selection]] where one channel
  carries all five console levels.

- **Java 8 `default` methods for forward extension on the
  listener.** Adding `mousePressed`/`dblclick`/etc. as `default`
  no-op methods on `WebViewMouseListener` preserves binary
  compatibility with callers compiled against today's interface.
  Project targets Java 8 (`pom.xml:39-41`); the language feature
  is fully available. Multiple sub-interfaces would balloon the
  API surface for no observable benefit.

- **Base64-encoded pipe-separated wire format.** The payload is
  built JS-side as
  `<schema-version>|<eventType>|<button>|<modifierBits>|`
  `<clientX>|<clientY>|<pageX>|<pageY>|<screenX>|<screenY>|<timeStamp>|`
  `<tagName>|<idB64>|<classesB64>|<attrsB64>|<linkHrefB64>|`
  `<imageSrcB64>|<mediaSrcB64>|<isContentEditable>|<selectionB64>|`
  `<pageUrlB64>|<frameUrlB64>` where:
  - `schema-version` is the literal `"1"`. Future incompatible
    payload changes bump this; parsers reject mismatching
    versions silently.
  - `eventType` is `"contextmenu"` for this canvas.
  - `button` is `event.button + 1` (so right = 3).
  - `modifierBits` is `(shift?1:0) | (ctrl?2:0) | (alt?4:0) | (meta?8:0)` as a base-10 string.
  - All `*B64` fields are base64-encoded UTF-8 byte sequences
    of the raw text. Empty/absent fields are encoded as the
    empty string (a `||` in the record), NOT as the base64 of
    an empty string, to keep the parser simple.
  - `classesB64` is the base64 of a space-separated list (no
    inner encoding needed because class names are restricted
    by HTML spec to characters that don't include space or pipe
    in practice).
  - `attrsB64` is the base64 of a comma-separated `key=valueB64`
    pair list, where each `valueB64` is itself the base64 of the
    attribute value. Keys are restricted to attribute names from
    the curated set (all ASCII identifier characters with `-`
    for `data-*`) so they need no inner encoding.
  - `isContentEditable` is `"1"` or `"0"`.
  The outer record is then base64-encoded as one UTF-8 string
  and posted via `window.__webview_dom_event(b64)`. Same
  precedent as `ConsoleDispatcher.SHIM_JS`
  (`ConsoleDispatcher.java:74-79`).

- **Coordinate translation is identity.** Both subclasses host
  the WebView in a region whose AWT coordinate space matches
  the page's CSS viewport coordinate space 1:1 in logical
  pixels. `toComponentPoint` returns `new Point(clientX, clientY)`
  with no DPI math; `toScreenPoint` returns
  `new Point(screenX, screenY)` directly. The story's "no new
  HiDPI handling" Scope-Out is naturally satisfied. The macOS
  WKWebView 12.x and earlier reliability caveat on `screenX/Y`
  (see Safeguards) is the only known wrinkle; the documented
  workaround for affected callers is to fall back via
  `SwingUtilities.convertPointToScreen(toComponentPoint(), source())`.

- **Defer JPopupMenu painting-over-WebView concerns to the
  caller.** Heavyweight native peers paint above lightweight
  Swing popups. Both existing demos
  (`demos/WebViewHeavyweightDemo.java:83-84`,
  `demos/WebViewConsoleDemo.java:41-42`) set
  `JPopupMenu.setDefaultLightWeightPopupEnabled(false)` and
  `ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false)`
  globally at startup. `WebViewContextMenuDemo` does the same.
  `WebViewContextMenu` does NOT mutate this global state in
  `attachTo` — that would be a surprising side-effect from a
  constructor-level call. Instead, the helper's class Javadoc
  documents the prerequisite prominently, the demo demonstrates
  the correct pattern, and a caller who skips the setup gets
  a popup that paints behind the WebView on heavyweight (a
  visible bug, not a silent failure).

- **Auto-suppression policy lock-in.** The story's
  "auto-suppress when listener registered" decision is final.
  Future maintainers tempted to "fix" this by making
  suppression opt-in would silently introduce double-menu
  bugs in every caller's first iteration. The Safeguards
  section documents the rule explicitly as a deliberate API
  choice.

## S · Structure

- `src/ca/weblite/webview/WebViewMouseEvent.java` (new) — the
  public value object surfaced to listeners.
- `src/ca/weblite/webview/DomTarget.java` (new) — the public
  descriptor value object that `WebViewMouseEvent` carries.
- `src/ca/weblite/webview/WebViewMouseListener.java` (new) —
  the public `@FunctionalInterface` defining today's
  `contextMenuRequested` method; reserved for forward extension
  via `default` methods.
- `src/ca/weblite/webview/WebViewMouseDispatcher.java` (new) —
  the per-component fan-out hub. Public for cross-package
  access, NOT part of the supported API.
- `src/ca/weblite/webview/swing/WebViewContextMenu.java` (new)
  — the Swing-side JPopupMenu helper. Public; supported API.
- `src/ca/weblite/webview/swing/WebViewComponent.java` (modified)
  — adds the `mouseDispatcher` field (mirroring the existing
  `consoleDispatcher` at `WebViewComponent.java:57`) and the
  four new public methods. No interaction with the existing
  console / DevTools / binding plumbing.
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  (modified) — wires the mouse bridge inside `createPeer()`
  immediately after the existing console-bridge install at
  `WebViewHeavyweightComponent.java:410-417`.
- `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`
  (modified) — wires the mouse bridge inside `addNotify()`
  immediately after the existing console-bridge install at
  `WebViewLightweightComponent.java:209-216`. Skipped when
  `engine == null` (non-Linux short-circuit).
- `demos/WebViewContextMenuDemo/src/ca/weblite/webview/demos/WebViewContextMenuDemo.java`
  (new) — interactive Swing demo. Layout mirrors
  `demos/WebViewConsoleDemo/`.
- `README.md` (modified) — under the existing "Demos" section,
  add a one-line bullet for the new demo. No other prose changes.

## O · Operations

### 1. Create Value Object — WebViewMouseEvent
File: `src/ca/weblite/webview/WebViewMouseEvent.java`

1. Responsibility: immutable carrier of one DOM mouse event's
   data, presented to Java listeners.
2. Package-private constructor:
   - `WebViewMouseEvent(String type, int button, int modifierBits,
        int clientX, int clientY, int pageX, int pageY,
        int screenX, int screenY, long timeStamp,
        DomTarget target, WebViewComponent source)`
   - Logic: null-check `type`, `target`, `source` (each throws
     `NullPointerException` with the parameter name);
     range-check `button` to `[1,3]` (throw
     `IllegalArgumentException` otherwise); store all fields
     in `final` instance variables. The `modifierBits` int is
     stored verbatim; the four boolean accessors decode it.
3. Public accessors (all no-`get` style):
   - `type(): String`
   - `button(): int`
   - `isShiftDown(): boolean` — returns `(modifierBits & 1) != 0`
   - `isCtrlDown(): boolean` — `(modifierBits & 2) != 0`
   - `isAltDown(): boolean` — `(modifierBits & 4) != 0`
   - `isMetaDown(): boolean` — `(modifierBits & 8) != 0`
   - `clientX(): int`, `clientY(): int`
   - `pageX(): int`, `pageY(): int`
   - `screenX(): int`, `screenY(): int`
   - `timeStamp(): long`
   - `target(): DomTarget`
   - `source(): WebViewComponent`
   - `toComponentPoint(): Point` — returns `new Point(clientX, clientY)`
   - `toScreenPoint(): Point` — returns `new Point(screenX, screenY)`
4. Public constant:
   - `public static final String EVENT_CONTEXT_MENU = "contextmenu";`
5. `toString()` returns a stable single-line format suitable
   for debug logging (e.g.
   `"WebViewMouseEvent[contextmenu button=3 at (120,80) target=DIV#id]"`).
6. No `equals` / `hashCode` overrides — events are not
   compared for equality and inheriting from `Object` matches
   the existing house style on `WebViewClickCallback` /
   `WebViewFocusCallback`.

### 2. Create Value Object — DomTarget
File: `src/ca/weblite/webview/DomTarget.java`

1. Responsibility: immutable descriptor of the element the user
   right-clicked.
2. Package-private constructor:
   - `DomTarget(String tagName, String id, List<String> classes,
        Map<String,String> attributes, String linkHref,
        String imageSrc, String mediaSrc, boolean contentEditable,
        String selectionText, String pageUrl, String frameUrl)`
   - Logic: null-check `tagName`, `id`, `selectionText`,
     `pageUrl`, `frameUrl`; convert `classes` and `attributes`
     to immutable views via
     `Collections.unmodifiableList(new ArrayList<>(...))` /
     `Collections.unmodifiableMap(new LinkedHashMap<>(...))`
     (null arguments become empty immutable list/map);
     `linkHref`, `imageSrc`, `mediaSrc` are nullable and stored
     verbatim. Store `contentEditable` and the other fields
     in `final` ivars.
3. Public accessors:
   - `tagName(): String`
   - `id(): String`
   - `classes(): List<String>` — already-unmodifiable view
   - `attributes(): Map<String,String>` — already-unmodifiable view
   - `linkHref(): String` (nullable)
   - `imageSrc(): String` (nullable)
   - `mediaSrc(): String` (nullable)
   - `isContentEditable(): boolean`
   - `selectionText(): String`
   - `pageUrl(): String`
   - `frameUrl(): String`
4. `toString()` returns a compact human-readable summary
   (tag, id, classes count, attribute count, link/image/media
   presence). Used for debug logging only.

### 3. Create Listener Interface — WebViewMouseListener
File: `src/ca/weblite/webview/WebViewMouseListener.java`

1. Annotated `@FunctionalInterface`.
2. Single abstract method:
   - `void contextMenuRequested(WebViewMouseEvent event)`
3. Javadoc states explicitly:
   - Invoked on the Swing EDT.
   - Implementations may touch Swing state directly.
   - Exceptions thrown from the listener are caught by the
     dispatcher and forwarded to the JVM's default uncaught
     exception handler; they do not propagate to other
     listeners or to the native engine.
   - Future event kinds will be added as `default` methods;
     callers MUST NOT add their own `default` methods to the
     interface.

### 4. Create Dispatcher — WebViewMouseDispatcher
File: `src/ca/weblite/webview/WebViewMouseDispatcher.java`

1. Responsibility: per-component fan-out hub for `contextmenu`
   payloads. Owns the listener registry, the explicit
   default-menu-enabled override flag, and the `FlagSink`
   indirection through which JS-side state changes are pushed
   to the live peer.
2. Public constants:
   - `public static final String CHANNEL_NAME = "__webview_dom_event";`
   - `public static final String SHIM_JS = …;` (see step 8 below
     for the full text)
   - `public static final String SCHEMA_VERSION = "1";`
3. Fields:
   - `private final WebViewComponent source;` — non-null;
     supplied at construction.
   - `private final CopyOnWriteArrayList<WebViewMouseListener> listeners = new CopyOnWriteArrayList<>();`
   - `private volatile Boolean defaultOverride;` — null initially.
     Non-null after the caller has used
     `setDefaultContextMenuEnabled(boolean)`.  See Approach for
     why this is `Boolean` (3-state) rather than a plain
     `boolean`.
   - `private volatile FlagSink flagSink;` — null until
     `attachFlagSink` runs.
   - `private final List<String> pendingPreloads = new java.util.ArrayList<>();`
     — guarded by `synchronized(this)` during mutation; the
     volatile `flagSink` field is the synchronization point for
     the post-attach drain.
4. Inner type (package-private):
   - `interface FlagSink { void eval(String js); void addOnBeforeLoad(String js); }`
5. Methods:
   - `WebViewMouseDispatcher(WebViewComponent source)`:
     - Logic: null-check `source`, store.
   - `void addListener(WebViewMouseListener l)`:
     - Logic: null-check (throw NPE with name `"listener"`);
       `listeners.add(l)`; call `reevaluateSuppression()`.
   - `void removeListener(WebViewMouseListener l)`:
     - Logic: null-tolerant (return on null); `listeners.remove(l)`;
       call `reevaluateSuppression()`.
   - `boolean hasListeners()`: returns `!listeners.isEmpty()`.
   - `boolean isDefaultEnabled()`: returns the **effective**
     default-menu-enabled state — when `defaultOverride != null`,
     returns `defaultOverride.booleanValue()`; otherwise returns
     `!hasListeners()`.  That is, "what would happen on the next
     right-click given current state": no listener and no
     override → default menu shows → returns `true`; listener
     registered and no override → default menu is suppressed →
     returns `false`; explicit override always wins.
   - `void setDefaultEnabled(boolean enabled)`:
     - Logic: `defaultOverride = Boolean.valueOf(enabled);` call
       `reevaluateSuppression()`.
   - `void attachFlagSink(FlagSink sink)`:
     - Logic: null-check; assign to `flagSink`; under
       `synchronized(this)`, drain `pendingPreloads` by calling
       `sink.addOnBeforeLoad(s)` for each entry then
       `pendingPreloads.clear()`; finally call
       `reevaluateSuppression()` once.
   - `private void reevaluateSuppression()`:
     - Logic: snapshot `Boolean o = defaultOverride;` then
       compute
       `boolean enabled = (o != null) ? o.booleanValue() : !hasListeners();`
       and `boolean suppress = !enabled;`.  Build
       `String stmt = "window.__webview_dom_event_suppress=" + suppress + ";"`.
       Capture `FlagSink sink = flagSink`; if `sink == null`,
       under `synchronized(this)` append `stmt` to
       `pendingPreloads` and return; otherwise call
       `sink.eval(stmt)` (apply to current document) and
       `sink.addOnBeforeLoad(stmt)` (apply on every subsequent
       navigation).
   - `void dispatch(String rawJson)`:
     - Logic: if `rawJson == null` return; extract the first
       arg via the `extractFirstArg` helper (verbatim copy
       from `ConsoleDispatcher.java:255-263`, or via a shared
       package-private helper — see Norms); if `null` return;
       base64-decode the outer payload to a UTF-8 string; if
       decoding throws, return silently; parse the
       pipe-separated record into a `WebViewMouseEvent` (see
       step 6 below); if parsing fails, return silently;
       hop to the EDT via the same
       `SwingUtilities.isEventDispatchThread()` short-circuit
       + `invokeLater` pattern as
       `ConsoleDispatcher.java:217-225`; deliver the event by
       iterating `listeners` and calling
       `contextMenuRequested(event)` on each, wrapped in
       `try { … } catch (Throwable t) { … }` that forwards to
       `Thread.getDefaultUncaughtExceptionHandler()` exactly
       as `ConsoleDispatcher.java:231-244`.
6. Payload parser (private static helper, e.g.
   `parsePayload(String, WebViewComponent)`):
   - Split the decoded UTF-8 string on `|` into exactly the
     fields enumerated in the wire-format spec (Approach
     section). Reject any record with the wrong field count
     OR mismatched `schema-version` (silent return).
   - Parse integers / longs with `Integer.parseInt` /
     `Long.parseLong`, caught individually to handle non-numeric
     fields by failing the whole parse.
   - For each `*B64` field, base64-decode to a UTF-8 string;
     empty input remains empty. `attrsB64` decodes to a
     comma-separated `key=valueB64` list, which is then
     re-split on `,` and each `=`-delimited pair has its
     value base64-decoded.
   - Build `DomTarget` from the parsed fields. Null
     `linkHref` / `imageSrc` / `mediaSrc` when the
     corresponding field is empty AND the tag does not indicate
     them; otherwise pass-through.
   - Build `WebViewMouseEvent` with the parsed numeric / boolean
     fields, the constructed `DomTarget`, and `source`.
7. `extractFirstArg(String json)` helper — verbatim copy from
   `ConsoleDispatcher.java:255-263`, OR factored into a
   `package-private` helper in a new `BindingWireFormat`
   utility class shared by both dispatchers. The shared-helper
   path is preferred for DRY but adds one more file; if it's
   added, also factor out the EDT marshaling boilerplate the
   same way. See Norms for the disposition.
8. `SHIM_JS` JavaScript source. Single-line JS string, no
   external newlines. Pseudocode (commentary brackets are
   removed in the actual literal):
   ```
   (function(){
     if(window.__webview_dom_event_installed__)return;
     window.__webview_dom_event_installed__=true;
     window.__webview_dom_event_suppress=false;
     var enc=function(s){
       try{return btoa(unescape(encodeURIComponent(String(s||""))));}
       catch(e){return "";}
     };
     var topUrl=function(){
       try{var w=window;while(w!==w.parent){w=w.parent;}return w.location.href;}
       catch(e){return document.location.href;}
     };
     document.addEventListener("contextmenu",function(ev){
       try{
         if(window.__webview_dom_event_suppress){ev.preventDefault();}
         var t=ev.target;
         if(!t || t.nodeType!==1){t=document.body || document.documentElement;}
         var tag=(t.tagName||"").toUpperCase();
         var id=t.id||"";
         var classes=t.classList?Array.prototype.slice.call(t.classList).join(" "):"";
         var keys=["href","src","alt","title","name","type","value","role"];
         var attrs=[];
         for(var i=0;i<keys.length;i++){
           if(t.hasAttribute && t.hasAttribute(keys[i])){
             var v=t.getAttribute(keys[i])||"";
             if(keys[i]==="value" && tag==="INPUT" && (t.type||"").toLowerCase()==="password"){v="";}
             attrs.push(keys[i]+"="+enc(v));
           }
         }
         if(t.attributes){
           var dataBudget=8192;
           for(var j=0;j<t.attributes.length;j++){
             var a=t.attributes[j];
             if(a.name.indexOf("data-")===0){
               var av=a.value||"";
               if(av.length>dataBudget){av=av.substring(0,dataBudget)+"...";}
               attrs.push(a.name+"="+enc(av));
               dataBudget-=av.length;
               if(dataBudget<=0)break;
             }
           }
         }
         var anc=t;
         var linkHref=null;
         while(anc && anc!==document.documentElement){
           if(anc.tagName==="A" && anc.getAttribute("href")){linkHref=anc.getAttribute("href");break;}
           anc=anc.parentElement;
         }
         var imageSrc=(tag==="IMG"?t.getAttribute("src")||"":null);
         var mediaSrc=null;
         if(tag==="AUDIO"||tag==="VIDEO"){mediaSrc=t.currentSrc||t.getAttribute("src")||"";}
         var ce = (t.isContentEditable===true) ||
                  (tag==="TEXTAREA") ||
                  (tag==="INPUT" && /^(text|search|email|url|tel|password|number)$/i.test(t.type||""));
         var sel="";
         try{
           var s=window.getSelection?window.getSelection().toString():"";
           sel = (s.length>65536)?(s.substring(0,65536)+"..."):s;
         }catch(e){}
         var modBits=(ev.shiftKey?1:0)|(ev.ctrlKey?2:0)|(ev.altKey?4:0)|(ev.metaKey?8:0);
         var record="1|contextmenu|"+(ev.button+1)+"|"+modBits+"|"+
                    (ev.clientX|0)+"|"+(ev.clientY|0)+"|"+
                    (ev.pageX|0)+"|"+(ev.pageY|0)+"|"+
                    (ev.screenX|0)+"|"+(ev.screenY|0)+"|"+
                    ((ev.timeStamp|0))+"|"+
                    tag+"|"+enc(id)+"|"+enc(classes)+"|"+enc(attrs.join(","))+"|"+
                    enc(linkHref||"")+"|"+enc(imageSrc||"")+"|"+enc(mediaSrc||"")+"|"+
                    (ce?"1":"0")+"|"+enc(sel)+"|"+enc(topUrl())+"|"+enc(document.location.href);
         var b64=enc(record);
         if(window.__webview_dom_event){window.__webview_dom_event(b64);}
       }catch(e){}
     }, true);
   })();
   ```
   - The capture-phase `true` argument is REQUIRED so the
     library handler runs before any page-side
     `addEventListener('contextmenu', e=>e.preventDefault())`.
   - `null` link/image/media positions encode as the empty
     string in the record; the parser uses tag/presence rules
     to decide whether to surface them as `null` vs.
     empty-string in `DomTarget`.
9. Constraints / Invariants:
   - `dispatch` MUST NEVER throw. Any parse failure or listener
     exception is silently swallowed / forwarded to the
     uncaught-exception handler.
   - `pendingPreloads` MUST NOT accumulate unboundedly. In
     practice each caller flips suppression 2-3 times per
     session; the pending list size is dominated by transitions
     before `attachFlagSink` runs (typically zero or one).
   - The dispatcher MUST be callable from any thread (the
     native peer's callback thread on Linux GTK / macOS
     AppKit / Windows WebView2 worker). EDT hop is internal.

### 5. Extend WebViewComponent Base
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Add field at class level (next to the existing
   `consoleDispatcher` declaration,
   `WebViewComponent.java:57`):
   - `protected final WebViewMouseDispatcher mouseDispatcher = new WebViewMouseDispatcher(this);`
   - Note: `this` is escaping the constructor here, exactly as
     it does for the existing `consoleDispatcher` — acceptable
     because the dispatcher does not call back into the
     component during construction.
2. Add four `public final` methods on the base class (NOT
   abstract — they don't depend on subclass state, so deferring
   to subclass implementations would be pure duplication):
   - `public final WebViewComponent addWebViewMouseListener(WebViewMouseListener listener)`
     - Logic: `mouseDispatcher.addListener(listener); return this;`
   - `public final WebViewComponent removeWebViewMouseListener(WebViewMouseListener listener)`
     - Logic: `mouseDispatcher.removeListener(listener); return this;`
   - `public final WebViewComponent setDefaultContextMenuEnabled(boolean enabled)`
     - Logic: `mouseDispatcher.setDefaultEnabled(enabled); return this;`
   - `public final boolean isDefaultContextMenuEnabled()`
     - Logic: `return mouseDispatcher.isDefaultEnabled();`
3. Constraints / Invariants:
   - The four new methods MUST be safe to call before the
     component is displayed (pre-peer-attach). The dispatcher
     handles the deferred-state case via `pendingPreloads`.
   - Calling the four methods after `dispose()` is benign:
     listener add/remove still mutates the dispatcher state,
     but the `flagSink` reference is stale and `eval` calls
     against a disposed peer no-op silently inside the peer's
     `checkAlive` path (`EmbeddedWebView.java:204` and
     analogous on `OffscreenWebView`).

### 6. Wire the Mouse Bridge — WebViewHeavyweightComponent
File: `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`

1. Inside `createPeer()` (currently
   `WebViewHeavyweightComponent.java:394`), AFTER the existing
   console-bridge install at `:410-417` and BEFORE the pending
   init scripts replay at `:418-420`, insert:
   ```
   embedded.addOnBeforeLoad(WebViewMouseDispatcher.SHIM_JS);
   embedded.addJavascriptCallback(
       WebViewMouseDispatcher.CHANNEL_NAME,
       new WebView.JavascriptCallback() {
           @Override public void run(String arg) {
               mouseDispatcher.dispatch(arg);
           }
       });
   mouseDispatcher.attachFlagSink(new WebViewMouseDispatcher.FlagSink() {
       @Override public void eval(String js) {
           embedded.eval(js);
       }
       @Override public void addOnBeforeLoad(String js) {
           embedded.addOnBeforeLoad(js);
       }
   });
   ```
2. Logic / sequencing rationale:
   - The `addJavascriptCallback` call goes through
     `embedded` rather than `this.addJavascriptCallback` so the
     reserved-prefix check does not reject the channel — same
     pattern as the console bridge at
     `WebViewHeavyweightComponent.java:411-417`.
   - `attachFlagSink` happens AFTER the binding is registered
     so the sink's first `addOnBeforeLoad` (replaying any
     pending preloads) registers behind the shim
     `addOnBeforeLoad` in the engine's init-script list. The
     init scripts run in registration order at document-start,
     so the shim runs first and initializes
     `window.__webview_dom_event_installed__` and
     `window.__webview_dom_event_suppress=false` BEFORE any
     accumulated suppression-override statement runs and writes
     to `window.__webview_dom_event_suppress`. Last-assignment-wins
     in JS execution order is the mechanism by which
     accumulated state correctly resolves on every navigation.
3. Constraints / Invariants:
   - No changes to any existing line in `createPeer()` —
     the new block is purely additive.
   - No changes to `dispose()` — the
     `EmbeddedWebView.dispose()` path already clears bindings
     and the heap-anchored callbacks, so the mouse channel
     tears down automatically when the peer is destroyed.
   - The new field accesses (`mouseDispatcher`) all go through
     the base class — no new fields are added on the
     subclass.

### 7. Wire the Mouse Bridge — WebViewLightweightComponent
File: `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`

1. Inside `addNotify()` (currently
   `WebViewLightweightComponent.java:189`), AFTER the existing
   console-bridge install at `:209-216` and BEFORE the
   pending init scripts replay at `:217-219`, insert the
   exact-same four-statement block as in Operation 6, but
   targeting `engine` (the `OffscreenWebView`) instead of
   `embedded`:
   ```
   engine.addOnBeforeLoad(WebViewMouseDispatcher.SHIM_JS);
   engine.addJavascriptCallback(
       WebViewMouseDispatcher.CHANNEL_NAME,
       new WebView.JavascriptCallback() {
           @Override public void run(String arg) {
               mouseDispatcher.dispatch(arg);
           }
       });
   mouseDispatcher.attachFlagSink(new WebViewMouseDispatcher.FlagSink() {
       @Override public void eval(String js) { engine.eval(js); }
       @Override public void addOnBeforeLoad(String js) { engine.addOnBeforeLoad(js); }
   });
   ```
2. Logic / sequencing rationale: identical to Operation 6.
3. Constraints / Invariants:
   - The insert is INSIDE the `engine != null` branch
     (after `WebViewLightweightComponent.java:204`'s null-check
     short-circuit). When `engine == null` (non-Linux), the
     mouse bridge is not installed; listener registration on
     the dispatcher still works but no events ever fire and
     the suppression flag has no effect. This matches the
     existing degradation mode for the lightweight component
     on macOS / Windows.
   - No changes to `removeNotify()` — the
     `OffscreenWebView.dispose()` path already clears
     bindings and the heap-anchored callbacks.

### 8. Create Convenience Helper — WebViewContextMenu
File: `src/ca/weblite/webview/swing/WebViewContextMenu.java`

1. Responsibility: turn each `contextMenuRequested` event into a
   Swing `JPopupMenu` shown at the click point, with no menu
   shown when the builder returns null.
2. Public API:
   - `public WebViewContextMenu(Function<WebViewMouseEvent, JPopupMenu> menuBuilder)`
     - Logic: null-check `menuBuilder` (throw NPE with name
       `"menuBuilder"`); store in `final` field.
   - `public WebViewContextMenu attachTo(WebViewComponent component)`
     - Logic: null-check `component`; if `attached != null`
       throw `IllegalStateException("WebViewContextMenu is already attached.")`;
       construct an internal `WebViewMouseListener` (lambda or
       anonymous class — see step 4); store it in the
       `listener` field; call
       `component.addWebViewMouseListener(listener)`;
       record `attached = component`; return `this`.
   - `public void detach()`
     - Logic: if `attached == null` return; call
       `attached.removeWebViewMouseListener(listener)`; clear
       `attached` and `listener`.
   - `public boolean isAttached()`
     - Logic: `return attached != null;`
3. Fields:
   - `private final Function<WebViewMouseEvent, JPopupMenu> menuBuilder;`
   - `private WebViewComponent attached;` (initially `null`)
   - `private WebViewMouseListener listener;`
4. Internal listener implementation:
   - On `contextMenuRequested(event)`:
     1. `JPopupMenu menu = menuBuilder.apply(event);`
     2. If `menu == null` return.
     3. `Point p = event.toComponentPoint();`
     4. `menu.show(event.source(), p.x, p.y);`
   - The listener does NOT swallow exceptions — they propagate
     to `WebViewMouseDispatcher.dispatch`'s per-listener
     `try/catch` and ultimately to the uncaught-exception
     handler.
5. Class-level Javadoc:
   - Documents the auto-suppress contract: attaching the helper
     suppresses the platform default menu for as long as the
     helper is attached (via the underlying mouse listener
     register). Detaching restores the default unless the
     caller has additional listeners registered separately.
   - Documents the heavyweight JPopupMenu prerequisite:
     callers using `WebViewHeavyweightComponent` MUST
     call `JPopupMenu.setDefaultLightWeightPopupEnabled(false)`
     and (recommended)
     `ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false)`
     at application startup, otherwise the popup paints
     behind the heavyweight native peer. Reference
     `WebViewContextMenuDemo` as the canonical example.
6. Constraints / Invariants:
   - The helper has no privileged access to the dispatcher —
     it goes through the public `addWebViewMouseListener` /
     `removeWebViewMouseListener` API.
   - A helper instance is single-use in the sense that a second
     `attachTo` call against the same instance throws. Callers
     wanting to re-attach must construct a new helper.

### 9. Create Interactive Demo — WebViewContextMenuDemo
Files:
- `demos/WebViewContextMenuDemo/src/ca/weblite/webview/demos/WebViewContextMenuDemo.java`

1. Responsibility: exercise every `DomTarget` field path against a
   small inline page, so AC verification is possible without an
   external website.
2. JFrame layout: a single `WebViewComponent.create()` filling
   the content pane. Demo loads a `data:` URL or `about:blank` +
   `addOnBeforeLoad` containing the inline test markup:
   - A heading with `id="title"` and `class="hero big"`.
   - An anchor `<a href="https://example.com/foo">link text in <span>nested span</span></a>`.
   - An image `<img src="https://placekitten.com/200/200" alt="kitten" title="placeholder">`.
   - A text input `<input type="text" value="some text">`.
   - A password input `<input type="password" value="secret">`.
   - A `<textarea>typing here</textarea>`.
   - A paragraph of selectable plain text.
3. Global Swing setup before `JFrame` show:
   - `JPopupMenu.setDefaultLightWeightPopupEnabled(false);`
   - `ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);`
4. `WebViewContextMenu` builder logic:
   - Always-present items: a header `JMenuItem` showing
     `event.target().tagName() + " @ (" + event.clientX() + "," + event.clientY() + ")"`
     (disabled, for visual confirmation).
   - When `target.linkHref() != null`: add "Open Link in Browser"
     (action: print to stdout `"open: " + linkHref`).
   - When `target.imageSrc() != null`: add "Copy Image URL" and
     show the URL in the item's text.
   - When `target.isContentEditable()`: add "Cut" / "Copy" /
     "Paste" / "Select All" items (no-op stubs — the demo is
     for AC verification, not full editor support).
   - When `target.selectionText().length() > 0`: add "Copy
     Selection" with the truncated selection in the item text.
   - Otherwise: a single "Inspect…" item (no-op stub, since
     "Inspect Element" re-injection is out of scope for this
     story).
   - Plus a "Disable Suppression" / "Re-enable Suppression"
     toggle that flips
     `webview.setDefaultContextMenuEnabled(!webview.isDefaultContextMenuEnabled())`,
     exercising AC13.
5. Demo wires a console listener that prints every captured
   `console.*` line to stdout, mirroring the existing
   `WebViewConsoleDemo` pattern (`WebViewConsoleDemo.java:41-42`
   for the lightweight-popups setup, then a `addConsoleListener`
   call).

### 10. Update README Demo Index
File: `README.md`

1. Locate the existing "Demos" section.
2. Append a single bullet line of the form:
   - `* WebViewContextMenuDemo — exercises the right-click
     context-menu API: target descriptor, link / image /
     editable / selection cases, and the
     `setDefaultContextMenuEnabled` override.`
3. No other prose changes.

## N · Norms

- **Mirror the `ConsoleDispatcher` pattern exactly.** When in
  doubt about a structural choice in
  `WebViewMouseDispatcher`, copy the corresponding code from
  `ConsoleDispatcher` verbatim. This includes: the public
  `SHIM_JS` / `CHANNEL_NAME` constant style, the
  `CopyOnWriteArrayList` field declaration style, the
  `extractFirstArg` JSON-without-a-parser approach
  (`ConsoleDispatcher.java:255-263`), the EDT marshal
  short-circuit (`ConsoleDispatcher.java:217-225`), and the
  per-listener `Throwable`-catching loop
  (`ConsoleDispatcher.java:231-244`). The duplication is
  deliberate — see Approach.
- **Wire-format helper sharing.** If a second dispatcher
  beyond `ConsoleDispatcher` would benefit from shared parsing
  helpers (`extractFirstArg`, `decodeBase64Utf8`, EDT marshal
  loop), introduce a package-private
  `ca.weblite.webview.BindingWireFormat` static-utility class
  and have BOTH dispatchers consume it. Do NOT introduce a
  Dispatcher abstract base class — the payload shapes differ
  enough that the abstraction is more complex than the
  duplication it would eliminate.
- **Accessor naming.** Value-object accessors use the no-`get`
  style (`event.button()`, `target.tagName()`) to match
  `ConsoleMessage` and the existing `WebView` fluent setters.
  Boolean accessors keep the `is` prefix
  (`isContentEditable()`, `isShiftDown()`) to match Java
  convention.
- **Null discipline.** Strings that have a "no value"
  semantic distinguish empty (`""`) from absent (`null`)
  per-field — `id` is `""` for no-id, `linkHref` is `null`
  for no-link, `selectionText` is `""` for empty selection.
  Document the per-field convention in Javadoc; do NOT
  uniformly nullify or empty-string everything.
- **Reserved-binding bypass.** The internal `__webview_dom_event`
  binding is registered by going through the peer wrapper
  (`embedded.addJavascriptCallback` /
  `engine.addJavascriptCallback`) rather than the component's
  public `addJavascriptCallback`, which would reject the
  reserved prefix. This is the same pattern as the existing
  `__webview_console__` install
  (`WebViewHeavyweightComponent.java:411-417`,
  `WebViewLightweightComponent.java:210-216`).
- **JS shim is one-shot per peer-attach.** Both subclasses
  install the shim exactly once in their peer-bring-up path.
  Repeated calls to `addOnBeforeLoad(SHIM_JS)` would still be
  safe because of the shim's own install guard
  (`window.__webview_dom_event_installed__`), but doubling up
  is wasteful and obscures intent. Keep the install site
  singular per peer-bring-up path.
- **Suppression-flag statement format.** Always
  `"window.__webview_dom_event_suppress=" + value + ";"`
  (boolean stringified by `Boolean.toString` / Java
  string-concat). Do NOT use any other syntax — the JS shim
  reads the exact name and a future change in field naming
  must be a coordinated rename across the shim and the
  dispatcher.
- **No JSON dependency.** The dispatcher decodes the
  binding-wrapper JSON via `extractFirstArg`'s
  no-parser-needed string scan (because the inner value is
  base64-encoded by the shim and therefore contains no
  JSON-special characters). Adding a JSON parser is a
  cross-cutting decision that requires modifying
  `pom.xml` and is out of scope for any individual story.
- **`pom.xml` Java 8 target stays in force.** No language
  features beyond Java 8 are used. `default` methods,
  `Function<T, R>`, `CopyOnWriteArrayList`, and `Base64` are
  all Java 8 features (`pom.xml:39-41`).
- **Demo style.** New demos follow the layout of the existing
  `demos/WebViewConsoleDemo` and `demos/WebViewHeavyweightDemo`
  (single Java source under `demos/<DemoName>/src/...`, no
  Maven config, runnable via the existing `run-*` scripts after
  a simple compile-and-run pattern). Demos are NOT shipped as
  Maven artifacts; they are reference applications.
- **Heavyweight popup prerequisite.** Both
  `WebViewContextMenu` Javadoc and `WebViewContextMenuDemo`
  document the
  `JPopupMenu.setDefaultLightWeightPopupEnabled(false)` +
  `ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false)`
  requirement. The helper does NOT mutate these flags itself
  because it is not appropriate for a library helper to
  globally alter Swing painting policy without explicit
  caller opt-in.
- **Wire-format version is `"1"`.** Future incompatible
  changes to the wire format MUST bump the version and the
  Java side MUST silently drop any record whose version does
  not match. Adding new fields at the END of the record is
  NOT a breaking change if the parser tolerates extra
  trailing fields; document this leniency.
- **EDT contract is non-negotiable.** Every dispatcher
  `contextMenuRequested` invocation runs on the EDT, full
  stop. Listeners that need to do heavy work should push to
  their own executor. This matches the contract of
  `ConsoleListener` and is the same advice that applies to
  every Swing listener.
- **No automated tests for GUI integration.** Consistent with
  [[swing-heavyweight-webview-embedding]],
  [[swing-lightweight-webview-embedding]], and
  [[swing-webview-component-mode-selection]]. ACs are verified
  by running `WebViewContextMenuDemo` and exercising each
  branch manually.

## S · Safeguards

- **Constructor null-checks.** `WebViewMouseEvent`, `DomTarget`,
  `WebViewMouseDispatcher`, and `WebViewContextMenu`
  constructors all reject null arguments with
  `NullPointerException` whose message names the offending
  parameter. Callers reading the exception message learn the
  cause without having to read source.
- **`button` range validation.** `WebViewMouseEvent`'s
  constructor throws `IllegalArgumentException` for
  `button < 1 || button > 3`. The dispatcher's parser maps
  `event.button` (DOM values 0/1/2) to 1/2/3 before
  constructing the event; an out-of-range parsed value
  represents wire corruption and the parser silently drops
  the event.
- **`WebViewMouseListener.contextMenuRequested` exception
  isolation.** Per Operation 4, each listener invocation is
  wrapped in `try { … } catch (Throwable t) { … }` that
  forwards the exception to
  `Thread.getDefaultUncaughtExceptionHandler()`. A misbehaving
  listener cannot break the dispatcher pipeline or prevent
  subsequent listeners from receiving the same event.
  Matches story AC20.
- **Listener mutation during fan-out.** Adding or removing a
  listener from inside a `contextMenuRequested` callback
  takes effect for the NEXT event, not the current one.
  Enforced by the snapshot-at-iteration semantics of
  `CopyOnWriteArrayList` — identical to
  `ConsoleDispatcher.java:225-229`. Document in Javadoc.
- **Pre-display listener registration.** Listeners registered
  before the native peer attaches are remembered in the
  dispatcher's `listeners` list. They start receiving events
  on the first navigation after peer-attach. No special
  "pending listeners" path is needed because the listener list
  is the same data structure pre- and post-attach. Matches
  story AC18.
- **Auto-suppression policy is a deliberate, locked-in API
  choice.** Once any `WebViewMouseListener` is registered, the
  platform default context menu is suppressed (subject to the
  explicit `setDefaultContextMenuEnabled` override). A future
  maintainer changing this to "opt-in suppression" would
  silently re-introduce double-menu bugs in every caller's
  first iteration — this rule MUST NOT be relaxed without an
  explicit canvas amendment and migration plan.
- **Cross-navigation override persistence.** When the caller
  has set `setDefaultContextMenuEnabled` (either value), the
  setting survives page navigations within the same component
  lifetime. Enforced by the dual `eval` + `addOnBeforeLoad`
  publish in `WebViewMouseDispatcher.reevaluateSuppression`.
  Resetting on navigation would silently surprise callers.
- **Append-only `addOnBeforeLoad` accumulation is bounded in
  practice.** Each suppression-state transition appends one
  ~50-byte JS statement to the engine's init-script list.
  Typical caller flips the state 2-3 times per session, so
  the accumulation cost is negligible. A 24-hour kiosk
  session that flips every minute accumulates ~75 KB total,
  which is also fine. If accumulation ever becomes a
  performance issue, the canvas may introduce a "consolidate"
  path that compacts the override on significant transitions
  (e.g. peer dispose / re-attach); not required for this
  story.
- **Async `eval` race.** `eval` is asynchronous on all three
  engines. A right-click that arrives within the sub-ms
  window between `setDefaultContextMenuEnabled` returning and
  the engine applying the flag uses the old value. User
  gesture latency is two-three orders of magnitude slower
  than `eval` propagation, so this is theoretical; document
  in `setDefaultContextMenuEnabled` Javadoc as an honest
  limitation.
- **Concurrent `setDefaultContextMenuEnabled` from non-EDT
  threads.** `defaultOverride` is `volatile`; the underlying
  `eval` / `addOnBeforeLoad` calls go through the engine's
  thread-safe message bridge. No additional synchronization
  is required.
- **Dispatcher behaviour after dispose.** Calling any of the
  four public methods on a disposed component is benign.
  Listener add/remove still mutates `listeners`. `eval` calls
  routed through the `FlagSink` reach the peer's `checkAlive`
  guard (`EmbeddedWebView.java:204`,
  `OffscreenWebView.java:227`) and throw
  `IllegalStateException` — the `FlagSink` implementation in
  the subclass MUST swallow that exception (wrap each
  `eval` and `addOnBeforeLoad` call in `try { … } catch
  (IllegalStateException ignored) { }`) so calls during
  teardown / shutdown do not propagate to the caller. Add
  this `try`/`catch` block inline at the `FlagSink`
  construction site in Operations 6 and 7.
- **Reserved-prefix protection is the AC22 mechanism.** No
  new guard, no new check — the existing reserved-prefix
  enforcement at `WebViewHeavyweightComponent.java:152-157` /
  `WebViewLightweightComponent.java:390-395` covers
  `__webview_dom_event` automatically. If a future change to
  `RESERVED_BINDING_PREFIX` is contemplated, the new prefix
  MUST still cover this channel name.
- **`<input type="password">` value scrubbing.** The JS shim
  drops the `value` attribute (replaces it with `""`) when
  the target is an `<input type="password">`. Selection text
  on password inputs is opaque to the DOM and
  `window.getSelection().toString()` returns the empty
  string in that case, so no separate selection-text
  scrubbing is needed. Match story NF expectation.
- **Selection text size cap.** The JS shim truncates
  `window.getSelection().toString()` at 64 KiB UTF-16 (the
  shim measures `.length`, which is UTF-16 code units, so
  the on-wire size after base64 of a UTF-8 re-encoding is
  roughly 87 KiB worst case). Append `"..."` to the truncated
  string so the caller can detect truncation. Lock this limit
  in Javadoc.
- **`data-*` attribute payload cap.** The JS shim caps the
  total payload of `data-*` values at 8 KiB. Values that
  overflow are truncated with a trailing `"..."`; once the
  budget is exhausted, no further `data-*` attributes are
  enumerated. Lock this limit in `DomTarget.attributes()`
  Javadoc.
- **macOS WKWebView 12.x `screenX/Y` reliability.** Some
  older WKWebView versions report `screenX/Y` equal to
  `clientX/Y`. The library reports the page's values
  verbatim; affected callers can compute screen coordinates
  via `SwingUtilities.convertPointToScreen(event.toComponentPoint(), event.source())`.
  Document the fallback in `WebViewMouseEvent.toScreenPoint`
  Javadoc.
- **JPopupMenu painting under heavyweight WebView.** Callers
  using `WebViewHeavyweightComponent` MUST set
  `JPopupMenu.setDefaultLightWeightPopupEnabled(false)` (and
  ideally
  `ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false)`)
  at application startup. The library does NOT enforce this
  for them — a missing setup produces a visible bug (popup
  paints behind the WebView), not a silent failure. Document
  in `WebViewContextMenu` and `WebViewMouseEvent.toComponentPoint`
  Javadoc. AC14 holds only when the prerequisite is met; this
  is a caller responsibility, not a library defect.
- **`WebViewLightweightComponent` non-Linux degradation.**
  When `engine == null` (macOS / Windows), the mouse bridge
  is not installed. Listener registration on the dispatcher
  still works and `setDefaultContextMenuEnabled` still
  mutates the dispatcher state, but no events ever fire.
  This matches the existing degradation mode for the
  lightweight component on those platforms and is
  consistent with the `WebViewLightweightComponent.java:62-68`
  Javadoc.
- **`WebViewContextMenu.attachTo` is idempotency-rejecting.**
  A second `attachTo` call on the same helper instance
  throws `IllegalStateException` rather than silently
  re-wiring. Callers wanting to swap the menu builder must
  construct a new helper.
- **`menuBuilder` exceptions surface via the dispatcher.**
  If `menuBuilder.apply(event)` throws, the exception flows
  out of `WebViewContextMenu`'s internal listener,
  is caught by the dispatcher's per-listener try/catch, and
  is forwarded to the JVM default uncaught-exception
  handler. The helper does NOT swallow the exception or
  show a fallback menu. Matches the dispatcher's general
  policy.
- **Same-origin iframe support relies on engine defaults.**
  All three engines' "execute script on document creation"
  APIs inject into every same-origin document by default;
  combined with the shim's capture-phase listener at
  `document` level, contextmenu events bubbled from
  same-origin children are captured. Cross-origin iframes
  cannot propagate `contextmenu` events to the top-level
  document — these events are not visible to the library
  on any platform. Document as expected behaviour in
  `DomTarget.frameUrl` Javadoc.
- **CSP and Trusted Types compatibility.** The shim is
  injected via the engine's content-script API, which is
  exempt from page-level CSP. Same protection as
  `ConsoleDispatcher.SHIM_JS`. No new CSP-related risk.
