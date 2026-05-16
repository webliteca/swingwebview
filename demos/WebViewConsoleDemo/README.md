# WebViewConsoleDemo

Interactive Swing demo that exercises the developer-visibility API on
`WebViewComponent`:

- `openDevTools()` — opens the platform's native Web Inspector / DevTools
  in a separate OS window.
- `addConsoleListener(ConsoleListener)` — receives every `console.*`
  call from the page as a structured `ConsoleMessage` on the EDT.
- `setConsoleOutput(PrintStream)` — convenience sink that writes each
  message as a formatted line.

The demo is designed for **manual verification** against the acceptance
criteria in
`requirements/[User-story-1]swing-webview-devtools-and-console-api.md`.

## Running

From the repository root:

```
./run-linux-console-demo.sh          # Linux
./run-mac-console-demo.sh            # macOS
run-windows-console-demo.bat         # Windows (cmd.exe / Git Bash)
```

These scripts build the native library, build `dist/WebView.jar`, compile
this demo, and launch it.  No additional setup beyond what
`run-*-demo.sh` already requires (see top-level `README.md`).

You can also run it via Ant after building the jar:

```
cd demos/WebViewConsoleDemo
ant run
```

## What you should see

A single window with:

- A `WebViewComponent` (top) loading an inline HTML page with eight
  buttons, one per console scenario.
- A monospaced `JTextArea` (bottom) showing each captured
  `ConsoleMessage.toString()` line.
- A "Tools" menu with three actions: **Open DevTools**, **Mirror console
  to System.out**, **Try reserved binding (should throw)**.

When the page loads, the area should immediately contain one line like:

```
[LOG] data:text/html,...:1 hello from page load
```

Clicking each button adds a corresponding line.  The exact `source:line`
varies by engine (V8/Blink stack format on Windows vs JavaScriptCore on
macOS/Linux); `<unknown>` is a valid fallback for synthetic / `eval`
scripts.

## AC mapping (STORY-001-001 — DevTools)

| AC# | How to verify in this demo |
|-----|----------------------------|
| AC1 | On Linux heavyweight (force via `-Dca.weblite.webview.mode=heavyweight`): Tools → Open DevTools → expect the WebKitGTK Web Inspector window to appear; capture pane shows `openDevTools() returned: true`. |
| AC2 | On Windows: Tools → Open DevTools → expect the Chromium DevTools window to appear; capture pane shows `true`. |
| AC3 | On macOS: Tools → Open DevTools → expect capture pane to show `false` and **no** inspector window.  Then right-click in the WebView → "Inspect Element" → expect the Safari Web Inspector to open. |
| AC4 | On Linux (default lightweight): same as AC1 — Tools → Open DevTools opens the WebKitGTK inspector. |
| AC5 | Edit `WebViewConsoleDemo.java`: comment out the `wv.setDebug(true)` line, rebuild, rerun.  Tools → Open DevTools → capture pane shows `false`, no inspector. |
| AC6 | Add a `JButton` that calls `wv.openDevTools()` BEFORE the frame is shown (run before `frame.setVisible(true)`) and assert it returns `false`.  Or just observe: the menu item is enabled before page load but openDevTools returns `false` until the native peer attaches (first paint / `addNotify`). |
| AC7 | Click Tools → Open DevTools twice in a row.  Linux/Windows should focus the existing inspector window (no duplicate window) and return `true` both times. |
| AC8 | The menu action runs on the EDT.  The fact that the menu doesn't lock up after clicking confirms the call doesn't block the EDT.  Stderr will also show the call returning quickly. |

## AC mapping (STORY-001-002 — Console capture)

| AC# | How to verify in this demo |
|-----|----------------------------|
| AC1  | Click `console.log` button.  Capture pane gets one line `[LOG] <source>:<line> log() with 42`. |
| AC2  | Click each of the five buttons (`log`, `info`, `warn`, `error`, `debug`).  Capture pane shows exactly five lines with levels `LOG`, `INFO`, `WARN`, `ERROR`, `DEBUG` in that order. |
| AC3  | Edit the demo to register a second `ConsoleListener` (e.g. one that prints to `System.err`).  Click any button — both listeners receive the same `ConsoleMessage`. |
| AC4  | Edit the demo to register two listeners then call `wv.removeConsoleListener(listenerA)` after a few clicks; subsequent clicks should only hit listenerB. |
| AC5  | Tools → "Mirror console to System.out" (check it).  Click any button.  The terminal that launched the demo should print a line matching `[LEVEL] source:line text\n`. |
| AC6  | Toggle the same menu item OFF.  Subsequent button clicks do NOT print to the terminal (only to the in-app capture pane).  Stderr from the demo confirms `setConsoleOutput(null) cleared`. |
| AC7  | Watch the capture pane.  Every line should NOT carry the `[NOT-EDT!]` suffix.  That suffix only appears if `SwingUtilities.isEventDispatchThread()` returned false inside `onMessage`. |
| AC8  | The listener is registered BEFORE the frame is shown (see `wv.addConsoleListener(listener)` in `run()`, called before `setVisible(true)`).  The page-load `console.log('hello from page load')` should still appear in the capture pane on first launch. |
| AC9  | Edit the demo to call `wv.setDebug(false)` instead of `true`.  Click `console.error` → capture pane still receives the message even though debug is off.  (DevTools menu item now returns `false` — that's AC5 of the other story.) |
| AC10 | On Linux with `debug=true` the demo's stderr (launching terminal) should ALSO show the page's `console.*` output because the native engine prints to stdout when `enable_write_console_messages_to_stdout` is on.  In-app capture pane gets the same messages. |
| AC11 | Click "log(non-string args)" → capture pane shows a single line with text like `[object Object] mixed 4.5`. |
| AC12 | Edit the demo to register a listener that throws `new RuntimeException("boom")` inside `onMessage`, plus a normal one after it.  Click any button: the second listener still receives the message; an exception trace appears via `Thread.getDefaultUncaughtExceptionHandler()` (stderr) but the demo continues working. |

## Reserved-name reject (canvas-5 norm)

Tools → "Try reserved binding (should throw)" calls
`wv.addJavascriptCallback("__webview_console__", ...)`.  Expected:
capture pane shows `reserved-name reject OK: name is reserved for
internal use: ...`.  No callback is registered.

## Forcing a specific mode

By default `WebViewComponent.create()` picks lightweight on Linux and
heavyweight on macOS/Windows.  Override with the system property:

```
java -Dca.weblite.webview.mode=heavyweight ...    # heavyweight everywhere
java -Dca.weblite.webview.mode=lightweight ...    # lightweight (Linux only)
```

The demo's stderr always logs the resolved mode at startup so you can
confirm which path you're exercising.

## Limitations

- Source URL + line number from `console.*` is best-effort.  Inline
  `<script>` content can produce `<unknown>` or `:-1` on some engines.
- macOS `openDevTools()` always returns `false` (no public API to
  programmatically pop the inspector); use right-click → Inspect
  Element.  On macOS 13.3+ the demo also enables `isInspectable`, so
  the inspector is reachable from the Safari Develop menu.
- The lightweight component is Linux-only.  On macOS/Windows the
  factory always returns heavyweight regardless of the system property.
