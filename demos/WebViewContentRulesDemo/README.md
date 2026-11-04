# WebViewContentRulesDemo (Canvas 37)

Serves `http://localhost:8124/`, a page that loads:

- `/ads/banner.png` and `/ads/tag.js` (blocked by `url-filter: /ads/`),
- `/ok/logo.png` (allowed),
- `/first/party.js` (allowed) and `http://127.0.0.1:8124/third/party.js` (blocked: third-party script),
- `fetch('/ads/beacon')` (blocked),
- three `.ad-slot` elements (hidden by `css-display-none`) and one `.content` element (shown).

The server logs every request it receives; **Show requests** prints them, and **Toggle rules and
reload** removes or re-applies the list.

| Check (auto mode) | AC |
|---|---|
| `/ads/` image and script never requested; `/ok/` image requested | AC1 |
| fetch beacon never requested, and the page sees it fail | AC2 |
| ad slots hidden, content shown | AC3 |
| first-party script requested, third-party script not | AC5 |
| look-up of the stored list finds it; an unknown name answers null | AC6 |
| an invalid list fails to compile with a reason | AC7 |
| after removing the list and reloading, the blocked requests arrive | AC8 |

Run: `./run-linux-content-rules-demo.sh` (lightweight, the only Linux mode),
`./run-mac-content-rules-demo.sh`, `run-windows-content-rules-demo.bat`. Set `CRDEMO_AUTO=1` to run
the checks and exit (0 = passed). Headless Linux: `xvfb-run -a env CRDEMO_AUTO=1 ./run-linux-content-rules-demo.sh`.
