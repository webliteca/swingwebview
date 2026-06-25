# WebViewDataUrlSmokeTest

Verifies the macOS `data:`-URL navigation fix (`cocoa_navigate` now loads
`data:text/html` via `WKWebView -loadHTMLString:baseURL:` instead of
`-loadRequest:`, which silently refuses `data:` URLs).

## Running

```
./run-mac-data-smoketest.sh        # macOS
./run-linux-data-smoketest.sh      # Linux
```

## What you should see

A WebView showing a colored heading **"data: URL OK — plain"**, plus four
buttons:

- **plain** — `data:text/html,<raw html>` (the case that rendered blank
  before the fix).
- **percent-encoded** — `data:text/html,<percent-encoded>` (exercises
  `stringByRemovingPercentEncoding`).
- **base64** — `data:text/html;base64,<...>` (exercises the `NSData`
  base64 decode path).
- **https (control)** — `https://example.com`, which always worked via
  `loadRequest:`, as a sanity control.

**Pass:** every button shows readable heading text (and the Unicode line
`café ✅ 😀` renders, confirming UTF-8 decoding).

**Fail:** a `data:` button shows a blank/white WebView while the **https**
control still renders — that means `data:` navigation is still broken.
