# WebViewSchemeDemo

Demo for **custom URL schemes** (Canvas 30, STORY-008-001).

Run it with `./run-mac-scheme-demo.sh` (heavyweight, the supported macOS mode).
`./run-linux-scheme-demo.sh` and `run-windows-scheme-demo.bat` build and run
the same demo, which currently prints "Custom URL schemes are not available in
this version of the native library" and exits: Linux and Windows follow in
Canvases 31 and 32.

The demo registers `demo` before any WebView exists and answers every request
from Java:

| Address | Answer |
|---|---|
| `demo://app/index.html` | a page with a heading, buttons and `<script src="app.js">` |
| `demo://app/app.js` | the page's script |
| `demo://app/api/echo` | the POST body, echoed as JSON |
| `demo://app/slow` | "done", 2 seconds later, from its own thread |
| `demo://app/broken` | the handler throws, so the page gets a 500 |
| `demo://app/detail.html` | the popup's page |

Every request is printed to the console.

## Manual acceptance checklist

- [ ] **AC1**: the window shows "Hello", and the page was requested as `demo://app/index.html`.
- [ ] **AC2**: the console shows a request for `demo://app/app.js`, and the page shows "script ran: yes".
- [ ] **AC3**: **POST {"n":3}** shows `POST 200 {"method":"POST","received":{"n":3}}`.
- [ ] **AC4**: the page shows `isSecureContext: true`, `localStorage: kept` and `location.origin: demo://app`.
- [ ] **AC5**: **Slow** answers after about 2 s, and the window stays responsive meanwhile.
- [ ] **AC6**: **Broken** shows `broken: status 500`, and the demo keeps running.
- [ ] **AC11**: **Open popup** shows the Detail page.

AC7 (the 30-second timeout), AC8–AC10 and AC12 are covered by the unit tests
(`SchemeDispatcherTest`, `WebViewSchemesTest`).
