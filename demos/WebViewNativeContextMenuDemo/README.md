# WebViewNativeContextMenuDemo

Demo for **application items in the engine's own context menu** (Canvas 36,
STORY-012-001).

Run it with `./run-linux-native-context-menu-demo.sh` (lightweight, the only
Linux mode), `./run-mac-native-context-menu-demo.sh` (heavyweight, the macOS
mode) or `run-windows-native-context-menu-demo.bat` (heavyweight, the Windows
mode).

Right-click the page. The engine's own menu opens — on Linux, at the pointer —
with every item it shows anyway, then a separator and the demo's items:

| Where you right-click | Items added |
|---|---|
| selected text (paragraph, text field, textarea) | **Ask** |
| the link | **Open Link Externally…** |
| anywhere | **Disabled sample** (greyed) and `Literal: snake_case & <b>no markup</b> 😀`, which must read exactly so |

Each contribution and each choice is printed with what the click hit: the
selected text, link, image, editable flag, the click point and the selection's
rectangle in component coordinates.

## Automatic mode

`NCMDEMO_AUTO=1` (or `-Dncmdemo.auto=true`) selects the first paragraph,
right-clicks it with a `java.awt.Robot`, saves a screenshot of the open menu to
`build/ncmdemo-menu.png` (or `-Dncmdemo.shot=<file>`), chooses **Ask** from the
keyboard (Up, Enter: Ask is the last item in this mode), checks that the action
ran on the EDT with the selected text, the selection rectangle and the click
point, prints `PASS` or `FAIL` and exits 0 or 1. It fails at once when
`isContextMenuContributionSupported()` is false.

On Linux, without a display:

```
xvfb-run -a env NCMDEMO_AUTO=1 ./run-linux-native-context-menu-demo.sh
```
