# WebViewDialogDemo

Exercises the browser-initiated UI dialog API introduced by
[`requirements/[User-story-4]browser-initiated-ui-dialogs.md`](../../requirements/%5BUser-story-4%5Dbrowser-initiated-ui-dialogs.md).

Buttons in the embedded page raise `alert` / `confirm` / `prompt`, and
two `<input type="file">` elements (single-select with `accept=".png,.jpg"`
and multi-select with `accept="image/*,.pdf"`) raise the file picker.
A combo box at the top switches among three handler modes:

| Mode | What happens |
|---|---|
| **Default handler** | Standard Swing `JOptionPane` / `JFileChooser` dialogs anchored on the host JFrame. |
| **Custom handler** | Each method records the event to the log pane and returns a fixed value (alert: no-op; confirm: `true`; prompt: `"hardcoded"`; file picker: `[/tmp/preselected.txt]`).  No UI. |
| **Drop handler** | `setDialogHandler(null)` — every dispatch returns the JS-spec cancel value without UI (alert: void; confirm: `false`; prompt: `null`; file picker: empty list). |

The bottom log pane shows every captured `console.log` line, so the
JS-side return value of each dialog (e.g. `confirm = true`, `prompt =
"hardcoded"`, `multi files = 2`) is visible without external tooling.

## Acceptance Criteria coverage

| AC | Verification |
|---|---|
| AC1 (default alert) | Mode = Default; click Alert → Swing message dialog appears with the message. |
| AC2 / AC3 (default confirm OK / Cancel) | Mode = Default; click Confirm → Swing OK/Cancel dialog.  Log shows `confirm = true` or `confirm = false`. |
| AC4–AC6 (default prompt entered text / null / default pre-fill) | Mode = Default; click Prompt → Swing input dialog pre-filled with `default`.  Log shows the typed value or `null`. |
| AC7 (default file picker single) | Mode = Default; click the single-file picker, choose one `.png` → log shows `single files = 1`. |
| AC8 (default file picker multi) | Mode = Default; click the multi picker, choose two files → log shows `multi files = 2`. |
| AC9 (default file picker accept filter) | Mode = Default; click the single-file picker → JFileChooser filter is restricted to `.png`/`.jpg`. |
| AC10 (custom alert) | Mode = Custom; click Alert → no UI, log shows `[custom] alertOpened: hello world`. |
| AC11 (custom confirm) | Mode = Custom; click Confirm → no UI, log shows `[custom] confirmOpened: proceed?` and `confirm = true`. |
| AC12 (custom prompt) | Mode = Custom; click Prompt → no UI, log shows `[custom] promptOpened: name? (default=default)` and `prompt = "hardcoded"`. |
| AC13 (custom file picker) | Mode = Custom; click either picker → no UI, log shows `[custom] filePickerOpened: ...` and the `change` event reports `1` file. |
| AC14 (null handler) | Mode = Drop; click any button → no UI, log shows the JS-spec cancel return (alert succeeds silently, confirm → `false`, prompt → `null`, file pickers → `0` files). |
| AC15 (filePicker hints) | Mode = Custom; click multi picker → log shows `multiple=true extensions=[pdf] mimeTypes=[image/*]`. |
| AC16 (EDT) | Implicit; the demo's log appends from the handler call sites must show up on the EDT.  No deadlock under heavy clicking. |
| AC17 (getDialogHandler non-null) | Implicit; `wv.getDialogHandler()` is never null — the mode switcher always reads a real value. |
| AC18 / AC19 (pageUrl / frameUrl) | Mode = Custom on a real `https://` page (not the inline demo's data: URL) — out of demo scope; verify via custom harness or browser. |
| AC20 (handler exception) | Manual; modify the demo's custom handler to throw — exception goes to stderr via uncaughtExceptionHandler, app keeps running, JS returns the safe fallback. |

## Build & run

```sh
ant run
```

Requires a built `dist/WebView.jar` at the project root (run any
`run-{linux,mac,windows}-demo.sh` once first to produce it).

## Platform status (this iteration)

macOS heavyweight (WKWebView) is fully wired in STORY-004-001 — every
mode listed above behaves as described.  On Linux and Windows the
embedded WebView continues to use its built-in dialogs until
STORY-004-002 (WebKitGTK signals) and STORY-004-003 (WebView2
ScriptDialogOpening) land; on those platforms the demo's mode switcher
still works for the JFrame and log pane but the embedded engine
ignores the custom handler and continues to show its own dialogs.
