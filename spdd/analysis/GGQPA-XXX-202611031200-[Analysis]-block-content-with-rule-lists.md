# SPDD Analysis: Block Content With Rule Lists

## Original Business Requirement

The complete story is `requirements/[User-story-13]block-content-with-rule-lists.md` (STORY-013-001,
"Block content with rule lists"), read in full for this analysis. Its scope and acceptance criteria are
reproduced verbatim below.

> ### Scope In
> - **Compiling** a named rule list, asynchronously, with the result reported once: a usable list, or a
>   failure saying why (for example, invalid rules).
> - **Keeping compiled lists** in a directory the application chooses, so the next launch reuses them
>   without recompiling; looking up and removing a stored list by name.
> - **Applying** compiled lists to a component, removing one, and removing all.
> - **Actions**: block a request; hide elements matching a selector; cancel earlier rules for a matching
>   request.
> - **Triggers**: address pattern; resource type; first- or third-party; page domain conditions.
> - **Adopted popups** keep their opener's lists.
> - **Finding out** whether the loaded native library supports rule lists.
> - A demo that blocks and hides content from a local listener.
>
> ### Scope Out
> - Converting filter lists to the content-blocker format (application work).
> - Reporting which requests were blocked (no engine reports it portably).
> - Scriptlets and redirect resources (the application injects scripts before load already).
> - Actions beyond block, hide and cancel-earlier-rules where an engine does not support them natively.
>
> AC1 A matching request is never sent · AC2 Requests from scripts are blocked too · AC3 Matching
> elements are hidden · AC4 Domain conditions and exceptions · AC5 Third-party only · AC6 Compiled once,
> reused next launch · AC7 Invalid rules are reported · AC8 Removing a list restores loading · AC9
> Adopted popups keep the lists · AC10 Nothing changes without a list · AC11 Support is discoverable ·
> NFR: 50,000 rules without a noticeable delay; compiling never freezes the window.

## Domain Concept Identification

### Existing Concepts (from codebase)
- **`WebViewComponent` and its two modes**: the Swing component an application holds. Heavyweight wraps
  `EmbeddedWebView` (macOS, Windows; the GTK `Engine` exists but is never verified on Linux);
  lightweight wraps `OffscreenWebView` (Linux `OffEngine` only). Every per-view capability is a
  setter on the base class, buffered until the peer attaches and replayed in `createPeer` before the
  first navigate (user agent, navigation callback, permission callback).
- **Per-component dispatchers** (`NavigationDispatcher`, `PopupDispatcher`, `DownloadDispatcher`…):
  the Java side of a native upcall. They never throw to native and fail closed.
- **Process-global native state**: custom schemes (`WebViewSchemes`) are registered once per process
  and applied to every engine; the GTK default web context is shared.
- **User content manager / controller**: every engine already owns one (`e->manager` on GTK and Cocoa)
  for the `external` bridge and init scripts. On GTK, a popup child made with
  `webkit_web_view_new_with_related_view` shares its opener's manager; on Cocoa an ADOPT child is given
  its own empty controller (1.3.2), so nothing on it is inherited.
- **Popup inheritance on the native side**: a Cocoa/Windows child engine copies the opener's
  `download_callback` and `ua_resolver` global refs at creation, before its first request.
- **WebView2 `WebResourceRequested`**: already used twice (custom schemes with a per-scheme filter; the
  popup UA hook with a `*`/document filter). WebView2 hands each filtered request to every handler, so
  each handler must ignore what is not its own.
- **Optional WebKitGTK symbols**: `WK_WEBKIT_OPT_SYMS` resolves symbols that a runtime may lack without
  failing the load; call sites check `WK_HAS`.

### New Concepts Required
- **Content rule list**: a named, compiled set of rules in WebKit's content-blocker JSON format, owned by a
  store directory. Process-global, like schemes; applied per component.
- **Rule list store**: a directory where compiled lists persist across launches; compile, look up and
  remove are its operations, all asynchronous.
- **Applied lists of a component**: the ordered set of rule lists governing one component's view, applied
  before its first navigation, inherited by its popups.
- **Portable rule matcher** (Windows only at run time, but platform-independent code): parses and
  evaluates the JSON format for a request (URL, page URL, resource type) and produces the cosmetic
  stylesheet script for a page.

### Key Business Rules
- **One format everywhere**: the same JSON is accepted on all three engines; an input WebKit rejects is
  rejected on Windows too (at least for the structural errors).
- **Before the request**: a matching request is refused by the engine before it is sent.
- **Rules apply in order**; `ignore-previous-rules` cancels earlier matching actions.
- **No list, no change**: with nothing applied, no hook is installed and no request is inspected.
- **Popups inherit**: a popup's first request is governed by its opener's lists.

## Strategic Approach

### Solution Direction
- **Native compilation where the engine has it** (macOS `WKContentRuleListStore`, Linux
  `WebKitUserContentFilterStore`), keyed by store directory and identifier in a native registry; Java
  holds only (directory, identifier) handles, never native pointers.
- **A Java matcher on Windows**, driven by a synchronous upcall from a `WebResourceRequested` handler,
  plus a replaceable document-created script for the hide rules.
- **Java owns the per-component list set**, replays it at attach like the other buffered settings, and
  seeds an adopted popup's component from its opener's set; the native layer makes each popup child
  inherit its opener's lists before its first request.

### Key Design Decisions
- **Handles by identifier vs native pointers**: pointers cross JNI and need explicit release; identifiers
  in a registry are safe and match how both WebKit stores already key lists → identifiers.
- **Windows matcher in Java vs C++**: C++ avoids a JNI call per request but duplicates a regex engine
  and is untestable headless; Java is testable with JUnit and a JNI upcall per request is microseconds →
  Java, with a token index so a request is checked against few rules.
- **Hide rules on Windows**: evaluate in Java per document (needs a native place to put the result before
  the document's scripts run) vs ship the rules to an injected script that evaluates them itself →
  injected script, replaced as one unit whenever the list set changes.
- **Compile API shape**: `CompletableFuture` (Java 8, already used by `evalAsync` and `printToPdf`) →
  futures, completed off the EDT.

### Alternatives Considered
- **Per-request Java decision on every platform**: WKWebView and WebKitGTK expose no per-request hook
  short of a WebProcess extension (GTK) or none at all (macOS).
- **Converting filter lists inside the library**: application policy (which lists, which exceptions); out
  of scope.
- **Script-only blocking**: cannot stop parser-initiated requests (the story's reason to exist).

## Risk & Gap Analysis

### Requirement Ambiguities
- **Third-party** needs a registrable domain. WebKit uses the public suffix list; the Windows matcher
  approximates it (documented).
- **Frames on Windows**: `WebResourceRequested` covers iframe subresources in the same process; out-of-
  process iframes are not guaranteed by the pinned WebView2 SDK header. `load-context` cannot tell the
  frame of a subresource on Windows.
- **Shared manager on Linux**: an adopted popup shares its opener's user content manager, so removing a
  list from one removes it from the other.

### Edge Cases
- **Compiling the same identifier twice** replaces the stored list; components that applied the old one
  keep it until re-applied.
- **Applying a list never compiled in this process** (looked up instead) must work the same.
- **Engine destroyed during an async compile**: compilation is not tied to an engine.
- **Custom-scheme and `about:` loads** must never be inspected by the Windows matcher.

### Technical Risks
- **WebKit's compiler is strict** (unsupported regex features, too many rules: WebKit caps a list at
  150,000 rules) — surfaced as a compile failure with WebKit's message.
- **Request latency on Windows** — mitigated by the token index; only installed when a list is applied.
- **macOS block ABI** — completion blocks with two object arguments; the file already uses clang blocks.
- **Older WebKitGTK (< 2.24)** lacks the filter store — optional symbols; support reported false.

### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Matching request never sent | Yes | Native on macOS/Linux; matcher on Windows |
| 2 | Script requests blocked | Yes | Same mechanism (resource type `raw`/`fetch`) |
| 3 | Elements hidden | Yes | Native `css-display-none`; injected stylesheet on Windows |
| 4 | Domain conditions, exceptions | Yes | `unless-domain`, `ignore-previous-rules` |
| 5 | Third-party only | Yes | Windows approximates registrable domain |
| 6 | Reused next launch | Yes | Native stores persist; Windows stores the JSON and reparses |
| 7 | Invalid rules reported | Yes | Native error message; Windows structural validation |
| 8 | Removing restores loading | Yes | Takes effect on the next load |
| 9 | Adopted popups keep lists | Yes | Native inheritance + Java seeding |
| 10 | No change without a list | Yes | No hook installed until a list is applied |
| 11 | Support discoverable | Yes | Native probe; `UnsatisfiedLinkError` → false |
