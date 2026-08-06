# WebViewPopupDemo

Exercises the browser-initiated **popup** API (`window.open`) introduced by
[`spdd/prompt/15-20260802-1000-[Feat]-Browser-Initiated-Popups-Window-Open.md`](../../spdd/prompt/15-20260802-1000-%5BFeat%5D-Browser-Initiated-Popups-Window-Open.md)
(Canvas 15).

The embedded page has a `window.open(...)` button and a `<a target="_blank">`
link.  A combo box at the top switches among three handler modes:

| Mode | What happens |
|---|---|
| **Default handler** | Allow all popups.  The native engine opens each in a separate native window, linked to the opener. |
| **Custom handler** | Allow + record `popupOpened` / `popupClosed` to the log pane. |
| **Block handler** | `setPopupHandler(null)` — `window.open` returns `null` (the pre-feature behaviour). |

The bottom log pane shows every captured `console.log` line plus the
handler's `popupOpened` / `popupClosed` notices, so you can see whether the
popup opened (allowed) or `window.open` returned null (blocked).

## What to verify

| Check | Verification |
|---|---|
| Allow (default) | Mode = Default; click the button → a separate native window opens loading example.com. |
| Opener linkage | In the popup's dev console, `window.opener` is non-null (this is what makes OAuth `signInWithPopup` postMessage-to-opener work). |
| Notifications | Mode = Custom; open then close a popup → log shows `[opened] ...` then `[closed] ...`. |
| Requested size | Mode = Custom; `window.open(..., 'width=520,height=640')` → `[opened]` line reports `520x640`. |
| Block | Mode = Block; click the button → log shows `window.open returned null (blocked)`. |
| `getPopupHandler()` non-null | Implicit; the mode switcher always reads a real handler value. |
| `window.close()` | From the popup page call `window.close()` → the native window closes and `[closed]` is logged. |

## Build & run

```sh
ant run
```

Requires a built `dist/WebView.jar` at the project root (run any
`run-{linux,mac,windows}-demo.sh` once first to produce it).

## Platform status

All three platforms open a native, opener-linked popup window when a popup
is allowed:

- **macOS** — WKWebView `createWebViewWithConfiguration:` (Canvas 15).
- **Linux** — WebKitGTK `create` signal, heavyweight *and* lightweight
  (Canvas 16).
- **Windows** — WebView2 `NewWindowRequested` (Canvas 17).

`setPopupHandler(null)` blocks `window.open` (it returns `null`) on every
platform.
