# WebViewCookieDemo

Demo for **`WebViewComponent.getCookies(String)`** (Canvas 6, Operation 16):
reading the native browser cookie store, including HttpOnly cookies.

Run it with `./run-mac-cookie-demo.sh` (heavyweight, the supported macOS mode),
`./run-linux-cookie-demo.sh` (lightweight, the only Linux mode), or
`run-windows-cookie-demo.bat` (heavyweight, the supported Windows mode).

The demo starts a loopback-only HTTP server on an ephemeral port and loads
`http://localhost:<port>/`. That page's response sets:

| Cookie | Attributes | Purpose |
|---|---|---|
| `swv_http` | `Path=/; HttpOnly` | invisible to `document.cookie`, must be returned natively |
| `swv_js` | `Path=/` | ordinary script-visible cookie |
| `swv_path` | `Path=/private` | only applies below `/private` |
| `swv_secure` | `Path=/; Secure` | never applies to an `http` URL |

When the page has loaded it reports its `document.cookie` to the server, and
the demo runs the checks below. Only cookie **names** are printed, never
values. The **Run getCookies checks again** button repeats the native checks.

## Checks

| Check | Passes when |
|---|---|
| document.cookie hides HttpOnly | the page cannot see `swv_http` (proves the cookie really is HttpOnly) |
| document.cookie sees swv_js | the page can see its ordinary cookie |
| completes on EDT | every future completes on the Swing event-dispatch thread |
| HttpOnly cookie returned | `getCookies(page)` contains `swv_http` |
| script cookie returned | `getCookies(page)` contains `swv_js` |
| Path=/private cookie excluded | `getCookies(page)` omits `swv_path` |
| Secure cookie excluded for http | `getCookies(page)` omits `swv_secure` |
| header syntax | the result is `name=value; name2=value2` |
| Path=/private cookie returned | `getCookies(.../private/area)` contains `swv_path` |
| longer-path cookie listed first | in that result `swv_path` precedes `swv_js` (RFC 6265 §5.4) |
| host-only cookies excluded | `getCookies(http://sub.localhost:<port>/)` omits `swv_http` and `swv_js` |
| unrelated host returns empty | `getCookies(http://unrelated.invalid/)` is `""` |

`INFO` lines are informational: whether an engine stores a `Secure` cookie set
over plain `http://localhost` varies, so the `https://localhost` query is only
reported.

## Automatic mode

`COOKIEDEMO_AUTO=1` (or `-Dcookiedemo.auto=true`) prints every check, then
`PASS` or `FAIL`, and exits 0 on pass or 1 on failure (2 if the page never
loaded within 60 seconds).

    COOKIEDEMO_AUTO=1 ./run-mac-cookie-demo.sh
    xvfb-run -a env COOKIEDEMO_AUTO=1 ./run-linux-cookie-demo.sh
    set COOKIEDEMO_AUTO=1 && run-windows-cookie-demo.bat

## Manual checklist

1. The window shows "getCookies demo" and the `document.cookie` box lists
   `swv_js` but **not** `swv_http`.
2. The console prints a line per check and ends with `PASS`.
3. Click **Run getCookies checks again**: the native checks print again and
   still pass (the `document.cookie` rows are omitted on re-runs).
4. Windows only: the WebView2 data lives under
   `%LOCALAPPDATA%\SwingWebView\java.exe-<hash>.WebView2` unless a
   `java.exe.WebView2` folder already exists, writable, beside `java.exe`, or
   `WEBVIEW2_USER_DATA_FOLDER` is set.
