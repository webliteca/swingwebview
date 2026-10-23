# Story Decomposition: Add Application Items To The Native Context Menu

## INVEST Analysis

### Abstract Task: "The Application Adds Its Own Items To The Browser's Right-Click Menu"

**Analysis Dimensions**:
- **Core Responsibility**: Let an application add its own items to the browser engine's own right-click
  menu, next to every item the engine already shows (Copy, Look Up, spelling suggestions, Back, Reload,
  Inspect Element in debug mode, and so on), and tell the application which item the user chose and
  what was under the pointer.
  - Today an application can only *replace* that menu (story 2: a mouse listener hides the engine's
    menu and the application shows a Swing menu of its own). Replacing loses every engine item, which
    the application cannot rebuild: spelling suggestions, Look Up, Inspect Element, Services on macOS.
- **Primary Operations**:
  - register (and remove) a contributor on a component;
  - on each right-click, tell the contributor what was clicked — the selected text, the link, the
    image or media address, whether the spot is editable, and where the click was — and let it answer
    with zero or more items (a label, enabled or not);
  - when the user chooses one of those items, tell the application, on the Swing event thread, with
    the same description of what was clicked.
- **Key Constraints**:
  - All three engines: WebKitGTK (Linux, lightweight component — the only Linux mode), WKWebView
    (macOS), WebView2 (Windows).
  - Every item the engine shows today must still be there and still work.
  - Only a real choice in the menu the user was shown may reach the application; a page cannot
    trigger an application item by itself.
  - Item labels are whatever the application says; they are shown as plain text on every platform.
  - Without a contributor, nothing changes.
- **Technical Complexity**: High. Three native menus, plus the lightweight Linux component, whose
  engine menu today opens in the wrong place (the top-left corner of the screen) because the engine's
  window is parked off-screen.
- **Business Complexity**: Low. One contributor, one choice.

### INVEST Evaluation
- ✅ **Independent**: builds on the existing component modes and the story-2 right-click channel only.
- ✅ **Negotiable**: what the click description carries; where the application's items sit in the menu
  (they are appended after the engine's items, below a separator).
- ✅ **Valuable**: the Agentic App Framework's "Ask about this selection" item, and any other
  application action on a selection, link or image, without losing the engine's own items.
- ✅ **Estimable**: one menu hook per engine, following the per-engine callback pattern of the popup
  and navigation decisions.
- ⚠️ **Small**: about 4 days across three engines. It stays one story, like the navigation and media
  decisions, because the value is one portable hook.
- ✅ **Testable**: on Linux, a scripted right-click on a selection opens the real engine menu, which
  can be photographed and clicked.

**Conclusion**: **Ready as-is — one story.**

### Split Strategy
Not applicable — no split needed.

---

## [STORY-012-001] Add application items to the native context menu

### Background

The Agentic App Framework shows web pages inside this library's component. Its next feature is "Ask":
the user selects some text in a page, right-clicks, and chooses **Ask**, and the framework opens its
assistant with that text (anchored near the selection).

Story 2 gave applications the right-click event, but only as a *replacement* for the engine's menu:
once the application listens, the engine's menu disappears and the application must draw its own. For
"Ask" that is the wrong trade. The user expects the menu they know — Copy, Look Up, spelling, Inspect
Element while debugging — with one more item at the bottom.

There is also a defect on Linux today: in the lightweight component, which is the only component used
on Linux, the engine's own menu opens at the top-left corner of the screen instead of under the
pointer.

### Business Value
- Provide **application developers** with a way to add actions to the browser's own right-click menu
  on every platform, without losing any of the engine's items.
- Support **"Ask about the selection"** in the Agentic App Framework: the chosen item hands the
  selected text and the click position to the application.
- Enable **a correct right-click menu on Linux**, opening where the user clicked.

### Dependencies and Assumptions
- **Prerequisites**: the component modes (stories behind Canvases 5–7) and the story-2 right-click
  channel, which already describes the clicked element and the selection.
- **Data assumptions**: none; any page works.
- **Integration points**: WebKitGTK's context-menu signal; WKWebView's menu as it opens; WebView2's
  context-menu event (runtime 1.0.1185 or newer). Each engine decides which of its own items appear.
- **Business constraints**: the Agentic App Framework consumes this from the next library release; its
  pinned library version must be raised to that release.

### Scope In
- Registering and removing contributors on the component, before or after it is shown.
- On each right-click that opens the engine's menu, asking each contributor, in registration order,
  for items; appending them, below a separator, after every engine item.
- Telling the application which item was chosen, on the Swing event thread, with the description of
  what was clicked: selected text, link address, image address, media address, editable or not, the
  click point in the component, and the selection's rectangle when there is one.
- Linux lightweight component: the engine menu opens at the pointer (fixing today's defect), with the
  application's items in it.
- macOS and Windows heavyweight components.
- A demo per OS, an automatic Linux check, README documentation.

### Scope Out
- Removing, reordering or relabelling the engine's own items.
- Submenus, check marks, icons and keyboard shortcuts on application items.
- Contributing items when the application has replaced the menu (story 2's listener with the engine
  menu switched off) — there is no engine menu to add to.
- Menus inside cross-origin frames beyond what each engine reports natively.
- The standalone (non-Swing) `WebView` window.
- The Linux heavyweight component (never used on Linux).

### Acceptance Criteria

#### AC1: An item appears for a selection, with every engine item kept
**Given** a lightweight component on Linux showing a page whose paragraph "Hello selectable world" is
selected, and a contributor that adds **Ask** when the click has selected text
**When** the user right-clicks the selection
**Then** the engine's menu opens under the pointer with Copy (and Inspect Element when debugging is on)
followed by a separator and **Ask**.

#### AC2: Choosing the item tells the application
**Given** the menu of AC1
**When** the user chooses **Ask**
**Then** the application is told, once, on the Swing event thread, that **Ask** was chosen, with the
selected text "Hello selectable world", the click point in the component, and the selection's
rectangle.

#### AC3: No item where the contributor adds none
**Given** the same contributor
**When** the user right-clicks blank page space with nothing selected
**Then** the engine's usual menu (Back, Forward, Reload, …) opens with no separator and no **Ask**.

#### AC4: Link, image and editable clicks are described
**Given** a contributor that records what it is told
**When** the user right-clicks a link to `https://example.com/a`, an image `cat.png`, and a text field
**Then** it is told the link address, the image address, and "editable" respectively.

#### AC5: Disabled items
**Given** a contributor that adds **Ask** disabled
**When** the menu opens
**Then** **Ask** is shown greyed out and cannot be chosen.

#### AC6: Labels are shown as written
**Given** a contributor whose label is `Ask_me & <b>now</b>`
**When** the menu opens
**Then** the item reads exactly `Ask_me & <b>now</b>` on every platform (no mnemonic, no markup).

#### AC7: A page cannot choose an application item
**Given** a page that tries to call the library's internal channels with forged messages
**When** no menu is open, or a different menu is open
**Then** the application is never told an item was chosen.

#### AC8: A menu choice counts once, and only for the newest menu
**Given** a menu was shown and dismissed, and a second menu is shown
**When** an item of the second menu is chosen
**Then** the application is told once, with the second click's description.

#### AC9: Several contributors
**Given** two contributors adding **Ask** and **Translate**
**When** the menu opens
**Then** both appear, in registration order, below one separator; each choice reaches the item that
added it.

#### AC10: A failing contributor is skipped
**Given** a contributor that throws
**When** the menu opens
**Then** the engine's menu still opens, the other contributors' items still appear, and the error
reaches the application's uncaught-exception handler.

#### AC11: Removing a contributor
**Given** **Ask** was contributed
**When** the contributor is removed and the user right-clicks again
**Then** **Ask** no longer appears.

#### AC12: Replacing the menu still wins
**Given** a story-2 mouse listener that replaces the menu, and a contributor
**When** the user right-clicks
**Then** the application's own Swing menu appears as before and the engine menu (with its items) does
not.

#### AC13: Nothing changes without a contributor
**Given** no contributor
**When** the user right-clicks
**Then** each engine's menu is exactly as before this feature (on Linux, it now opens at the pointer).

#### AC14: Feature detection
**Given** any platform
**When** the application asks whether contributed items are supported
**Then** it is told yes with a native library built from this feature, and no with an older one.

#### Non-Functional Expectations
- Deciding the items must be fast: the menu is waiting for them.
- No engine thread or the Swing event thread is blocked by the other while the menu is built.
- A choice that arrives after the component is gone is ignored safely.
