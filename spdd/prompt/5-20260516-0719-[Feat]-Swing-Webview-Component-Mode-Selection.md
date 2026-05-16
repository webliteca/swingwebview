---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: Swing WebView Component & Mode Selection

## R · Requirements
- Provide an abstract Swing component (`WebViewComponent extends
  JComponent`) that exposes a uniform WebView embedding API
  regardless of whether the underlying implementation is a
  heavyweight peer or an offscreen-rendered lightweight component
  (`WebViewComponent.java:42`).
- Public abstract API: `setUrl`/`getUrl`, `setDebug`,
  `addOnBeforeLoad`, `eval`, `addJavascriptCallback`, `dispose`,
  `isHeavyweight` (`WebViewComponent.java:122`–
  `WebViewComponent.java:157`).
- `WebViewComponent.create()` chooses the right implementation for
  the platform automatically: heavyweight on macOS / Windows,
  lightweight on Linux (`WebViewComponent.java:71`,
  `WebViewComponent.java:90`).
- Callers may override the default by setting the system property
  `ca.weblite.webview.mode` to `"heavyweight"`/`"heavy"` or
  `"lightweight"`/`"light"` (case-insensitive); unrecognised values
  fall back to the platform default and emit a warning to STDERR
  (`WebViewComponent.java:93`).
- `WebViewComponent.create(Mode mode)` lets callers pick
  explicitly without going through the property
  (`WebViewComponent.java:76`).
- Definition of Done: documented in `README.md ("Choosing a mode" section)` ("Embedding
  WebView Directly in Swing") and exercised by the
  `WebViewHeavyweightDemo` (`demos/WebViewHeavyweightDemo/...`).
  No automated tests cover this factory.

## E · Entities
- **WebViewComponent.Mode** (`WebViewComponent.java:45`) — enum
  with two values:
  - `HEAVYWEIGHT` — native peer as a heavyweight AWT child. See
    [[swing-heavyweight-webview-embedding]] for the
    implementation invariants.
  - `LIGHTWEIGHT` — offscreen-rendered, painted into a regular
    Swing component. See
    [[swing-lightweight-webview-embedding]].
- **WebViewComponent** (`WebViewComponent.java:42`) — abstract
  base extending `JComponent`. Subclasses must implement every
  abstract method; `isHeavyweight()` defaults to `false` and is
  overridden to `true` only by
  `WebViewHeavyweightComponent.isHeavyweight()`
  (`WebViewHeavyweightComponent.java:69`).
- **MODE_PROPERTY** (`WebViewComponent.java:58`) — public
  string constant `"ca.weblite.webview.mode"` used as the system
  property name.

## A · Approach
- **Platform default has rationale baked in.** Linux is forced to
  lightweight because the heavyweight path on WebKitGTK reparented
  under a foreign-toolkit X11 parent has unreliable visible
  text-input feedback (caret blink, characters as typed). See
  `README.md ("Platform support" section)` and the inline comment at
  `WebViewComponent.java:106`. macOS/Windows get heavyweight
  because the lightweight path is currently a stub on those
  platforms (`README.md ("Platform support" section)`, `WebViewLightweightComponent.java:43`).
- **Override is a system property, not a constructor flag.** A
  property keeps the call sites in callers identical across all
  platforms — `WebViewComponent.create()` — while still letting
  ops/devs flip the toggle from launchers
  (`README.md ("Quick start" section)`).
- **Configuration replay.** The abstract API allows callers to
  call `setUrl`, `addOnBeforeLoad`, `addJavascriptCallback`
  BEFORE the component is displayable; each concrete subclass is
  responsible for buffering and replaying on attach. The factory
  itself stays free of any such state.

## S · Structure
- `src/ca/weblite/webview/swing/WebViewComponent.java` — abstract
  class, `Mode` enum, factory, platform/property resolution.
- `src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java`
  — heavyweight subclass; implementation details in
  [[swing-heavyweight-webview-embedding]].
- `src/ca/weblite/webview/swing/WebViewLightweightComponent.java`
  — lightweight subclass; implementation details in
  [[swing-lightweight-webview-embedding]].
- `README.md ("Choosing a mode" section)` — user-facing platform/mode matrix and override
  documentation.

## O · Operations

### 1. Define Mode Enum — WebViewComponent.Mode
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: enumerate the two embedding strategies.
2. Fields:
   - `HEAVYWEIGHT, LIGHTWEIGHT` (`WebViewComponent.java:49`).
3. Constraints / Invariants:
   - Order is API: `valueOf` and `values()` must keep returning
     these names for callers that persist the choice.

### 2. Resolve Default Platform Mode — WebViewComponent.resolveDefaultMode
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: pick a `Mode` based on the system property
   override and, failing that, the host OS.
2. Methods:
   - `resolveDefaultMode(): Mode`
     - Logic: read the system property `MODE_PROPERTY`, trim,
       lowercase. If `"heavyweight"`/`"heavy"` return
       `HEAVYWEIGHT`; if `"lightweight"`/`"light"` return
       `LIGHTWEIGHT`; otherwise if non-empty emit a STDERR
       warning and fall through (`WebViewComponent.java:93`).
       Read `os.name` and return `LIGHTWEIGHT` when it contains
       `"linux"`, `"nix"`, or `"nux"`
       (`WebViewComponent.java:109`); otherwise return
       `HEAVYWEIGHT`.
3. Constraints / Invariants:
   - Property check is case-insensitive and accepts both full
     names and the abbreviations `heavy`/`light`
     (`WebViewComponent.java:95`).
   - Unrecognised property values log a warning to STDERR but
     never throw — the platform default kicks in
     (`WebViewComponent.java:100`).
   - The Linux substring check covers `"linux"`, generic
     `"nix"`, and `"nux"` (Unix variants) — see comment for the
     reason (`WebViewComponent.java:106`).

### 3. Create WebViewComponent — WebViewComponent.create
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: instantiate the concrete subclass for a given
   mode (or the resolved default).
2. Methods:
   - `create(): WebViewComponent` — delegate to
     `create(resolveDefaultMode())` (`WebViewComponent.java:71`).
   - `create(Mode mode): WebViewComponent`
     - Logic: switch on `mode`. `HEAVYWEIGHT` →
       `new WebViewHeavyweightComponent()`; `LIGHTWEIGHT` →
       `new WebViewLightweightComponent()`; default throws
       `IllegalArgumentException` (`WebViewComponent.java:77`).
3. Constraints / Invariants:
   - The factory is the only blessed entry point; constructing
     either subclass directly is allowed (per
     `README.md ("Choosing a mode" section)`) but loses platform-aware defaulting.

### 4. Declare Embedding API Surface
File: `src/ca/weblite/webview/swing/WebViewComponent.java`

1. Responsibility: define the abstract contract every embedding
   implementation must honour.
2. Methods:
   - `setUrl(String url): WebViewComponent` — buffered before
     display, applied immediately when live
     (`WebViewComponent.java:123`).
   - `getUrl(): String` — returns the pending or current URL
     (`WebViewComponent.java:126`).
   - `setDebug(boolean debug): WebViewComponent` — must be
     called BEFORE display; subclasses throw
     `IllegalStateException` if called later
     (`WebViewComponent.java:129`).
   - `addOnBeforeLoad(String js): WebViewComponent` — buffered
     and replayed on attach (`WebViewComponent.java:135`).
   - `eval(String js): WebViewComponent` — no-op until
     displayable (`WebViewComponent.java:141`).
   - `addJavascriptCallback(String name, WebView.JavascriptCallback cb): WebViewComponent`
     — buffered and replayed on attach
     (`WebViewComponent.java:148`).
   - `dispose(): void` — release native resources; component is
     unusable afterward (`WebViewComponent.java:157`).
   - `isHeavyweight(): boolean` — defaults to `false`; only
     `WebViewHeavyweightComponent` overrides to `true`
     (`WebViewComponent.java:118`).
3. Constraints / Invariants:
   - "Pending configuration is applied as soon as the component
     becomes displayable" is contract, not implementation
     detail — every subclass must satisfy it
     (`WebViewComponent.java:38`).
   - `setDebug` is only valid before display because the native
     engines bake the debug flag into the peer at creation time
     (e.g. `EmbeddedWebView.attach(canvas, debug)` at
     `EmbeddedWebView.java:57`).

## N · Norms
- The system property name `ca.weblite.webview.mode` is part of
  the public surface — do not rename it. Document any new
  property values in `README.md ("Choosing a mode" section)`.
- The lightweight↔heavyweight platform matrix lives in
  `README.md ("Platform support" section)` and must be kept in sync with what
  `resolveDefaultMode` actually returns and what each subclass
  actually supports.

## S · Safeguards
- Unrecognised system property values are warned about but never
  fatal (`WebViewComponent.java:100`). This avoids breaking
  callers when typo'd or when a future value is unknown to an
  older library version.
- The Linux-default-to-lightweight decision is hardcoded with a
  written comment explaining the underlying GTK/X11 frame-clock
  reason (`WebViewComponent.java:106`) so future maintainers
  understand it isn't arbitrary.
- The default `isHeavyweight()` returning `false` means an
  unknown future subclass is treated as lightweight by callers
  that branch on this — safer assumption since it implies
  "behaves like a normal Swing component."
