# WebViewSchemeDemo

Demo for **custom URL schemes** (Canvas 30, STORY-008-001).

Run it with `./run-mac-scheme-demo.sh` (heavyweight, the supported macOS mode)
or `./run-linux-scheme-demo.sh` (lightweight, the supported Linux mode;
`./run-linux-scheme-demo.sh heavyweight` also works), or
`run-windows-scheme-demo.bat` (heavyweight, the supported Windows mode).

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

## Automatic mode

`SCHEMEDEMO_AUTO=1` (or `-Dschemedemo.auto=true`) loads
`demo://app/index.html?auto=1`, and the page runs these checks by itself:

| Check | Passes when |
|---|---|
| `script` | `app.js` loaded from the scheme and ran (AC2) |
| `origin` | `location.origin` is `demo://app` (AC4) |
| `secure` | `window.isSecureContext` is true (AC4) |
| `storage` | a `localStorage` value reads back (AC4) |
| `post` | the POST body `{"n":3}` is echoed with status 200 (AC3); `post-body-unavailable` on engines that cannot supply request bodies also passes |
| `slow` | the 2 s answer arrives and the page's timers kept ticking meanwhile (AC5) |
| `broken` | the throwing handler gives status 500 (AC6) |
| `missing` | an unknown page gives the handler's 404 |

The page sends the results to `demo://app/api/report` (as a GET, in the `r`
query parameter, so it works on engines without request bodies), then starts one more slow
request and navigates away without waiting for it. The demo prints each result,
then `PASS` or `FAIL`, and exits 3 seconds later: 0 when every check passed, 1
when one failed, 2 when no report arrived within 60 seconds.

Without a display (Linux):

```
xvfb-run -a env SCHEMEDEMO_AUTO=1 ./run-linux-scheme-demo.sh
```

## Linux notes

- Request bodies need WebKitGTK 2.40 or newer. On older engines the handler
  gets an empty body and `bodyAvailable()` is false.
- Response status codes and headers need WebKitGTK 2.36 or newer. On older
  engines a 2xx answer keeps only its body and `Content-Type`, and any other
  status reaches the page as a network error.
- WebKitGTK does not report abandoned requests, so a request the page leaves
  behind stays open until its handler answers or the 30-second timeout ends it.

## Windows notes

- WebView2 does not report abandoned requests either, so a request the page
  leaves behind stays open until its handler answers or the 30-second timeout
  ends it.
- Custom schemes need a WebView2 Runtime that supports custom scheme
  registration; the evergreen Runtime on current Windows does. An older one
  cannot load pages from the scheme.
- Do not combine the standalone `WebView` window with registered schemes in the
  same process: WebView2 may refuse to create the second environment because
  its options differ.

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
