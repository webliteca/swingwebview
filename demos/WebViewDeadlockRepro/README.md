# WebView v1.0.5 deadlock repro

Provokes a deterministic-ish EDT ↔ AppKit main-thread mutual-wait
deadlock against the `cocoa_run_on_main` bridge introduced in v1.0.5
(PR #30, commit `dcda4cf`). See the top-of-file javadoc in
`src/ca/weblite/webview/demos/WebViewDeadlockRepro.java` for the full
timeline.

## What it does

- Loads a page that calls `window.poke()` on `setInterval(1ms)`. The
  bound Java callback synchronously rendezvous with the EDT via
  `SwingUtilities.invokeAndWait`, parking the AppKit main thread on a
  semaphore for the duration.
- A `javax.swing.Timer` fires synthetic Cmd-C `KeyEvent`s at 10ms
  cadence into the global `KeyboardFocusManager`. They are picked up by
  `WebViewHeavyweightComponent`'s editing-shortcut dispatcher, which
  evaluates `embedded.isNativeFirstResponder()` — the sync v1.0.5
  bridge call on the EDT side.
- A daemon watchdog pings the EDT every 100ms; if the EDT goes silent
  for >5s it dumps every thread's stack trace to stderr and forces
  `Runtime.halt(1)`.

## Expected behaviour

Within seconds of pressing **Start repro** the app freezes. The watchdog
fires shortly after and prints:

- The **EDT** parked inside
  `Java_ca_weblite_webview_WebViewNative_webview_1embed_1is_1native_1first_1responder`
  → `cocoa_run_on_main` → `performSelectorOnMainThread` semaphore wait,
  called from `handleEditingShortcut` on a `KeyEvent.KEY_PRESSED`.
- The **AppKit main thread** parked inside
  `EventQueue.invokeAndWait` (Java frames visible: the user's
  `JavascriptCallback.run` calling `invokeAndWait`, called from
  `engine_on_message` via JNI from the WKScriptMessageHandler).

That mutual-wait shape is the deadlock. The class of bug is broader
than this exact entry path: any sync EDT→main bridge meeting any sync
main→EDT callback (including AppKit accessibility, IME, focus events
on the main side) can close the same cycle.

## Running

```
ant run
```

Requires `dist/WebView.jar` and `natives/osx_arm64/libwebview.dylib`
to exist. Run `./run-mac-demo.sh` once from the repo root to produce
both, then come back here and `ant run`.

macOS only — the bridge being exercised is macOS-specific.
