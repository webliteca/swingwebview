# Story Decomposition: Block Content With Rule Lists

## INVEST Analysis

### Abstract Task: "The Engine Refuses What A Rule List Says, Before It Is Fetched"

**Analysis Dimensions**:
- **Core Responsibility**: Let an application hand a component a list of content rules — written in the
  content-blocker format WebKit already reads — and have the engine refuse every matching request and
  hide every matching element, before the request leaves the machine.
  - An application can already refuse a *navigation* (story 10) and inject a script before a page loads.
    Neither stops the requests a page's own HTML makes: `<script src>`, `<img>`, `<iframe>`,
    stylesheets, beacons. The engine starts those as soon as it reads the HTML, before any injected
    script runs. An ad blocker that only hides what already loaded sends every tracker its hit.
  - Every engine can refuse those requests natively: WKWebView and WebKitGTK compile rule lists into the
    engine; WebView2 asks the host about each request.
- **Primary Operations**:
  - compile a rule list once, under a name, and keep the compiled form across launches;
  - apply compiled lists to a component, and remove them;
  - find out whether the loaded native library can apply rule lists.
- **Key Constraints**:
  - One rule format on all three platforms, so an application writes its rules once.
  - Lists are large (tens of thousands of rules) and compiling them is slow, so compiling is
    asynchronous and its result is kept.
  - A list applied before a component's first navigation governs that navigation.
  - A popup adopted into a component is governed by the same lists as its opener.
  - With no list applied, nothing changes.
- **Technical Complexity**: High — three native implementations, and on Windows a matcher for the
  format itself, fast enough to run on every request.
- **Business Complexity**: Medium — the rule format has triggers (address pattern, resource type,
  first/third party, page domain) and actions (block, hide, undo earlier rules).

### INVEST Evaluation
- ✅ **Independent**: builds on the existing engine wrappers only.
- ✅ **Negotiable**: which triggers and actions each engine honours where the native format differs.
- ✅ **Valuable**: an application can block ads and trackers the way a privacy browser does.
- ✅ **Estimable**: two engines take the format natively; one needs a matcher.
- ⚠️ **Small**: about 5 days across three engines. It stays one story, as custom schemes (story 8) and
  navigation decisions (story 10) did, because the value is one portable rule format.
- ✅ **Testable**: a page loads resources from a local listener; the listener shows whether blocked
  requests arrived, and the page shows whether hidden elements are visible.

**Conclusion**: **Ready as-is — one story.**

### Split Strategy
Not applicable — no split needed.

---

## [STORY-013-001] Block content with rule lists

### Background

The Agentic App Framework's built-in browser is used for everyday browsing as well as by agents. Its
owner wants it to block ads and trackers the way Brave does: at the network layer, so a tracker never
sees the request, not just cosmetically after the fact.

Ad blockers express their rules as filter lists (EasyList, EasyPrivacy). WebKit's content-blocker format
is the established way to hand such rules to an engine: Safari and Brave on iOS convert filter lists to
it. WKWebView and WebKitGTK compile it natively. WebView2 has no compiler, but it asks the host about
every request, so the library can match the same format itself.

This story adds **one** portable way to do it:
- the application compiles a rule list under a name (once; the compiled form is kept);
- it applies one or more compiled lists to a component;
- every request the component's pages make, and every element they show, is governed by those lists.

### Business Value
- Provide **application developers** with network-level content blocking on every platform, in one
  rule format.
- Support **privacy browsing**: ads and trackers are never requested, which also makes pages faster.
- Enable **per-site exceptions**: lists can be removed from a component, and the format's own
  domain conditions let rules skip a site.

### Dependencies and Assumptions
- **Prerequisites**: none.
- **Data assumptions**: the application supplies rule lists in the content-blocker format; converting
  filter lists into it is the application's job.
- **Integration points**:
  - WKWebView on macOS: content rule list store and the user content controller.
  - WebKitGTK on Linux: user content filter store and the user content manager.
  - WebView2 on Windows: the web-resource-requested event.
- **Business constraints**:
  - no new mandatory runtime dependency (Linux keeps loading WebKit at runtime);
  - no change for applications that apply no list.

### Scope In
- **Compiling** a named rule list, asynchronously, with the result reported once: a usable list, or a
  failure saying why (for example, invalid rules).
- **Keeping compiled lists** in a directory the application chooses, so the next launch reuses them
  without recompiling; looking up and removing a stored list by name.
- **Applying** compiled lists to a component, removing one, and removing all.
- **Actions**: block a request; hide elements matching a selector; cancel earlier rules for a matching
  request.
- **Triggers**: address pattern; resource type; first- or third-party; page domain conditions.
- **Adopted popups** keep their opener's lists.
- **Finding out** whether the loaded native library supports rule lists.
- A demo that blocks and hides content from a local listener.

### Scope Out
- Converting filter lists to the content-blocker format (application work).
- Reporting which requests were blocked (no engine reports it portably).
- Scriptlets and redirect resources (the application injects scripts before load already).
- Actions beyond block, hide and cancel-earlier-rules where an engine does not support them natively.

### Acceptance Criteria

#### AC1: A matching request is never sent
**Given** a list with one rule blocking addresses containing `127.0.0.1:8124/ads/`, applied to a
component before it navigates
**When** the page loads `<img src="http://127.0.0.1:8124/ads/banner.png">` and
`<script src="http://127.0.0.1:8124/ads/tag.js">`
**Then** the listener on port 8124 receives neither request, and the page's other image from
`http://127.0.0.1:8124/ok/logo.png` loads.

#### AC2: Requests from scripts are blocked too
**Given** the same list
**When** the page calls `fetch("http://127.0.0.1:8124/ads/beacon")`
**Then** the listener receives nothing and the fetch fails.

#### AC3: Matching elements are hidden
**Given** a list with a hide rule for `.ad-slot`
**When** a page with three `<div class="ad-slot">` elements and one `<div class="content">` loads
**Then** the three ad slots are not displayed and the content is.

#### AC4: Domain conditions and exceptions
**Given** a block rule for `/ads/` that does not apply on `127.0.0.1`'s pages via an `unless-domain`
condition, or a later rule that cancels earlier rules for that page
**When** a page on `127.0.0.1` loads an `/ads/` image
**Then** the request reaches the listener.

#### AC5: Third-party only
**Given** a rule blocking third-party scripts
**When** a page at `http://localhost:8124/` loads a script from `http://localhost:8124/` and one from
`http://127.0.0.1:8124/`
**Then** only the first-party script is requested.

#### AC6: Compiled once, reused next launch
**Given** a list compiled under the name `ads-v1` into a store directory
**When** the application starts again and looks up `ads-v1` in the same directory
**Then** it gets the list without compiling it again, and applying it blocks as in AC1.

#### AC7: Invalid rules are reported
**Given** text that is not a valid rule list (for example `[{"trigger":{}}]`)
**When** the application compiles it
**Then** the compile reports a failure with a reason, and no component is affected.

#### AC8: Removing a list restores loading
**Given** a component with the AC1 list applied
**When** the application removes the list and the page reloads
**Then** the listener receives the `/ads/` requests.

#### AC9: Adopted popups keep the lists
**Given** a component with the AC1 list applied that adopts a popup
**When** the popup loads an `/ads/` image
**Then** the listener receives nothing.

#### AC10: Nothing changes without a list
**Given** a component with no list applied
**When** any page loads
**Then** every request is made as before.

#### AC11: Support is discoverable
**Given** an older native library without this feature
**When** the application asks whether rule lists are supported
**Then** it is told no, and applying a list does nothing rather than failing.

#### Non-Functional Expectations
- A component with a list of 50,000 rules applied loads pages without a noticeable delay.
- Compiling a large list does not freeze the application's window.
