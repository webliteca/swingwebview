# WebViewAdoptPopupDemo

On-device demo for **popup adoption** (Canvas 18) and **custom User-Agent**
(Canvas 21). Lets you validate the native code for both features on a real
desktop without swingwebbrowser.

## Run

From the repo root:

```bash
./run-mac-adopt-popup-demo.sh
```

It rebuilds `libwebview.dylib` from `src_c/` (so your changes to the native
adoption / UA code are picked up), builds `dist/WebView.jar`, then compiles
and launches the demo.

> The POST/echo checks hit `https://httpbin.org`, so they need network. The
> adoption and opener mechanics work offline too.

## What to exercise

**Popup mode** (top-left combo):

- **Adopt into tab** — `popupDisposition` returns `ADOPT`. The engine keeps
  the opener-linked child and `popupAdoptable` hosts it in a **new tab** via
  `WebViewComponent.adoptPopup(popupId)`.
  - Click **POST → popup**: the new tab shows httpbin's JSON echo containing
    `"form": { "q": "hello" }` — proof the **POST body survived** into the
    adopted tab (not a GET). The echoed `"User-Agent"` header reflects any
    custom UA you set.
  - **window.open → popup** and the **target=_blank** link also open as tabs.
  - No native popup window appears at any point.
- **Native window** — the Canvas 15 behaviour: the popup opens in a separate
  native window (POST preserved, opener-linked).
- **Block** — `window.open` returns `null`; the POST form submit is blocked.

**User-Agent** (field + Set / Reset):

- Edit the field (prefilled with a Safari UA) and click **Set UA** — the
  opener reloads. The page's `navigator.userAgent` readout updates, and
  submitting the POST form shows the changed `User-Agent` header in the
  httpbin echo (proving the **HTTP header**, not just the JS value, changed).
- **Reset UA** restores the engine default.

## Notes

- The demo sets `JPopupMenu.setDefaultLightWeightPopupEnabled(false)` and the
  tooltip equivalent, required for heavyweight popups.
- Adoption's reference backend is macOS (WKWebView). Linux (Canvas 19) and
  Windows (Canvas 20) native adoption are follow-ups; on those the disposition
  falls back and popups are not yet adopted into tabs.
