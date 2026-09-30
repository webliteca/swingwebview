# WebViewNavigationDemo

Demo for **navigation decisions** (Canvas 34, STORY-010-001).

Run it with `./run-linux-navigation-demo.sh` (lightweight, the only Linux mode),
`./run-mac-navigation-demo.sh` (heavyweight, the macOS mode) or
`run-windows-navigation-demo.bat` (heavyweight, the Windows mode).

The demo serves `demo://app/` from a custom scheme and starts a local listener
on a free port, which stands for "the internet". Its navigation handler allows
`demo://app/…`, `about:blank`, `about:srcdoc` and the application's own
navigations, and refuses everything else. The page's buttons try every way a
page can leave, each carrying `d=secret` to the listener; none should reach it.
Every decision and every listener hit is printed to the console. The right-hand
panel holds a popup the page opens, adopted into its own component with the
same handler.

## Automatic mode

`NAVDEMO_AUTO=1` (or `-Dnavdemo.auto=true`) drives every check itself, prints
each one, then `PASS` or `FAIL`, and exits 0 or 1.

| Check | Passes when |
|---|---|
| `AC1 link`, `AC1 download link` | the handler was asked with cause `LINK`, the page stayed and the listener got nothing |
| `AC2 location.href` / `assign` / `replace`, `AC2 meta refresh` | the same, with cause `OTHER` |
| `AC3 form` | the same for a form posted to `_top`, with cause `FORM` |
| `AC10 frame` | a frame navigating itself was refused likewise |
| `AC7 throwing handler` | a handler that throws refused, and nothing was sent |
| `AC4 allowed link` | a link to `demo://app/next.html` loaded |
| `AC4/AC6 back`, `AC6 forward`, `AC6 … is BACK_FORWARD` / `RELOAD` | history and reload work and report their cause |
| `AC5 a page navigation is not marked` | the page's own navigation to the listener was refused, unmarked |
| `AC5 setUrl is marked and loads` | the application's `setUrl` to the same address was marked and loaded |
| `AC9 adopted popup refused` | the adopted popup's navigation was refused by its own component's handler |
| `AC8 no handler navigates` | with the handler removed, the page navigated as before |

On Windows, `LINK` and `FORM` are expected as `OTHER`, since WebView2 can't tell
them apart. The demo prints `isNavigationHandlerSupported` first (AC11), and in
automatic mode fails at once when it is false.
