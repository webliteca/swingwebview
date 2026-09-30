# Analysis: Decide Where A Page May Navigate (STORY-010-001)

> Story: `requirements/[User-story-10]decide-where-a-page-may-navigate.md`.

## 1. Business context

An application hosting a page it doesn't trust can confine what the page *loads* with a content security
policy, but not where the page *goes*. Every navigation is a request whose address the page chooses, so
it's a channel out. The Agentic App Framework needs this closed before agents may hand data to user-built
"creations" (its story 257). The library has to offer one portable, synchronous yes-or-no, asked before
any request leaves.

## 2. Domain concepts

| Concept | Meaning here |
|---|---|
| **Navigation** | The view, or one of its frames, starting to load a new document: a link, script (`location.*`), a form, a meta refresh, back/forward, reload, or a server redirect. |
| **Navigation decision** | Allow or refuse, made before the engine sends the request. A refusal leaves the page untouched. |
| **Cause** | What started it: link, form, back/forward, reload, redirect or other. What each engine can tell differs (§4). |
| **Application-initiated** | The application asked for it, through `setUrl`. A server redirect of such a navigation stays application-initiated. |
| **Popup** | A new window. It is already decided by `WebViewPopupHandler`, and is out of this story's scope. |

## 3. What the codebase already has

- **The popup decision (Canvases 15–20)** is the model: `WebViewPopupHandler` →
  `PopupDispatcher` (inline on the native UI thread, never the EDT, and a throw means block) →
  `WebViewPopupCallback` (the JNI-facing interface) → a per-engine global ref set through
  `webview_embed_set_popup_callback` / `webview_offscreen_set_popup_callback` at peer attach. Adopted
  popups receive the adopting component's callbacks at attach.
- **Linux (`src_c/webview_embed.cpp`, GTK)** has three engine kinds sharing one GTK thread:
  - `Engine` (heavyweight);
  - `OffEngine` (lightweight, offscreen);
  - `PopupEngine` (engine-owned popup windows), which becomes an `Engine` or `OffEngine` when adopted.

  WebKit is called through the runtime loader's X-macro list (`webkit_loader.h`), mirrored in
  `webkit_shim.h`. A new WebKit function must be added to both.
- **macOS**: one delegate class serves as the `WKUIDelegate`, the `WKNavigationDelegate` (for the
  download response policy, Canvas 23) and the `WKDownloadDelegate`. It doesn't implement
  `webView:decidePolicyForNavigationAction:decisionHandler:`, so WebKit's default runs.
- **Windows (`windows/webview_embed.cc`)**: WebView2. Per-engine handlers are registered once the
  controller exists (`add_NewWindowRequested`, `add_DownloadStarting`, …), and again on an adopted
  child.
- **Support probes**: an older native lacks new JNI symbols, and `isXxxSupported()` catches the
  `UnsatisfiedLinkError` (`webview_pdf_available`, `webview_scheme_available`).

## 4. The engines, verified or read

- **WebKitGTK (verified in a spike on 2.52, under Xvfb).** `decide-policy` with
  `WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION` fires before the request for:
  - script navigation, `location.*`;
  - a form with `target=_top`;
  - a meta refresh;
  - a `download` link;
  - frame navigations.

  `webkit_policy_decision_ignore` stops each, and a listener received nothing. Frame navigations are
  **indistinguishable** from the view's own: the frame name is null, and the current URI is the view's.
  `target=_blank` arrives as `NEW_WINDOW_ACTION`, which the popup path owns. Causes come from
  `webkit_navigation_action_get_navigation_type` (link, form, back/forward, reload, resubmit, other) and
  `webkit_navigation_action_is_redirect`.
- **WKWebView.** `decidePolicyForNavigationAction` covers every frame; `targetFrame == nil` means a new
  window. The causes are `navigationType` (link, form, back/forward, reload, resubmit, other), and
  redirects aren't flagged publicly. **Implementing the method replaces WebKit's default**, which:
  - allows a new-window action;
  - for a request WebKit can load (or a registered scheme), downloads when `shouldPerformDownload`, and
    otherwise allows;
  - otherwise opens a non-file URL with `NSWorkspace` and ignores it.

  Allowed navigations must reproduce this, or `download` links and `mailto:` would change behaviour.
- **WebView2.** `NavigationStarting` covers the main frame, and `FrameNavigationStarting` covers frames.
  Both give `put_Cancel`, `IsRedirected` and, through `ICoreWebView2NavigationStartingEventArgs3`,
  `NavigationKind` (reload, back/forward, new document). It can't tell a link from a form or script.

## 5. Strategic direction

1. **Mirror the popup decision exactly:**
   - a handler interface with a permissive `DEFAULT`;
   - a public-internal dispatcher that runs inline and fails closed;
   - a JNI callback interface;
   - global refs per engine, set at attach.
2. **Decide frames too, everywhere.** Linux can't exclude them, and a portable contract that differs
   by platform is worse than one that asks a little more. The safe side is to ask.
3. **Application-initiated is known in Java.** `setUrl` records the address it expects, and the first
   matching navigation (compared after light normalization, within a short window) is marked. A redirect
   inherits the mark of the navigation it continues. This fails closed: a missed match is simply
   unmarked.
4. **Reproduce each engine's default when allowing**, so an application without a handler, or with one
   that allows, sees no change.

## 6. Risks

| Risk | Mitigation |
|---|---|
| The macOS default isn't reproduced faithfully (downloads, `mailto:`) | Reproduce it step by step; `shouldPerformDownload` is probed with `respondsToSelector`. |
| A handler blocks the engine thread | Documented contract (fast, no Swing), as for popups. |
| Deadlock on macOS from an EDT round trip | The handler never marshals; it runs inline on the AppKit thread. |
| An application forgets `about:blank` / `about:srcdoc` for frames | Documented on the handler; the demo allows them. |
| Redirects unflagged on macOS | Documented: reported as "other". |
| macOS and Windows can't be run here | CI compiles all six natives; the Linux path is verified end to end. |

## 7. AC coverage

- **AC1–AC4 and AC6–AC10**: the native decision point with `ignore`/`Cancel`, plus dispatcher tests.
- **AC5**: the dispatcher's expectation and its redirect inheritance.
- **AC11**: `isNavigationHandlerSupported()` catching `UnsatisfiedLinkError`.
- **Causes**: WebView2 reports link, form and script navigations as "other", and macOS reports redirects
  as "other". Their ACs name the cause where the engine reports it.
