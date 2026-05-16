---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: WebView CLI Application

## R · Requirements
- Provide an executable JAR (`java -jar WebView.jar [OPTIONS] <url>`)
  that opens a native cross-platform WebView window pointed at a URL,
  configurable via command-line flags. Manifest main-class is
  `ca.weblite.webview.WebViewCLI` (`pom.xml:80`).
- Supported flags: `-title`, `-w`/`-width`, `-h`/`-height`,
  `-resizable`, `-fullscreen`, `-onLoad <js>`, `-onLoadFile <path>`,
  `-useMessageBoundaries`, `-port <n>`, plus the OAuth-mode flags
  `-oauth`, `-client_id`, `-client_secret`, `-redirect_uri`, `-scope`,
  `-response_type` (`WebViewCLI.java:152`). Any unrecognised `-xxx val`
  pair becomes an entry in `additionalParams` for OAuth URL building
  (`WebViewCLI.java:233`).
- If no URL argument is given, print a help banner describing usage
  and exit with status 1 (`WebViewCLI.java:142`, `WebViewCLI.java:101`).
- Default window: 800x600, resizable, title "Login", URL provided as
  the final positional argument (`WebViewCLI.java:24`,
  `WebViewCLI.java:237`).
- On macOS, transparently relaunch the JVM with
  `-XstartOnFirstThread` if it wasn't already started that way — the
  zserge WebView library requires running on the AppKit main thread
  (`WebViewCLI.java:514`).
- When `-port <n>` is supplied, run a TCP socket server that accepts
  WebView control connections instead of reading commands from
  STDIN (`WebViewCLI.java:54`). Otherwise, hook STDIN/STDOUT up to a
  `WebViewServer` so JavaScript can be piped to the running browser
  (`WebViewCLI.java:62`).
- In OAuth mode (`-oauth`), construct an OAuth authorization URL from
  the flags, navigate the WebView to it, and require an output-file
  path as the second positional argument where captured credentials
  will be written (`WebViewCLI.java:71`, `WebViewCLI.java:238`).
- Required OAuth flags when `-oauth` is set: `client_id`,
  `redirect_uri`, `scope`, and the output file argument
  (`WebViewCLI.java:255`).
- Definition of Done: existing integration check is the README usage
  examples (`README.md:34`) and the demo command lines in
  `run-mac-demo.sh` / `run-linux-demo.sh` / `run-windows-demo.bat`.
  No automated CLI tests; behaviour is validated by demos.

## E · Entities
- **WebViewCLI** (`WebViewCLI.java:23`) — mutable parameter bag
  populated by `parseParams` then used by `init` to construct a
  `WebView`. Implements `AutoCloseable`; `close()` releases the
  optional `WebViewServer` (`WebViewCLI.java:439`). Invariants:
  - `url` is always set from `args[0]` after parsing
    (`WebViewCLI.java:237`).
  - In OAuth mode, `oauthOutputFile` is non-null, its parent
    directory exists, and the file does not yet exist
    (`WebViewCLI.java:241`–`WebViewCLI.java:253`).
  - `port` of `-1` means "use STDIO"; any `>= 0` selects socket mode
    (`WebViewCLI.java:36`, `WebViewCLI.java:54`).
- **OAuth URL** — built by `getFullUrl()` (`WebViewCLI.java:71`):
  `client_id`, `redirect_uri` (URL-encoded), optional `scope`,
  `response_type` defaulting to `code` when `client_secret` is set
  else `token` (`WebViewCLI.java:78`), and all `additionalParams`
  appended as encoded query pairs.

## A · Approach
- **Single process, blocking event loop.** `WebViewCLI.init()` builds
  a `WebView`, attaches a STDIN/STDOUT or socket bridge, then calls
  `webview.show()` which blocks until the window closes
  (`WebViewCLI.java:43`).
- **JVM self-restart for macOS.** Rather than asking users to launch
  with `-XstartOnFirstThread` themselves, the CLI checks the
  `JAVA_STARTED_ON_FIRST_THREAD_<pid>` env var; if unset, it spawns a
  child JVM with the flag and `inheritIO`, waits for it, and returns
  `true` so `main` exits cleanly (`WebViewCLI.java:514`).
- **Hand-rolled URL encoder.** The CLI ships its own
  `encodeUrl(String)` (`WebViewCLI.java:387`) and Latin-1/Unicode
  percent-encoder (`WebViewCLI.java:454`) instead of using
  `java.net.URLEncoder`, presumably to preserve Codename One
  compatibility (the encoder originally came from LWUIT —
  `WebViewCLI.java:489`).
- **Loose argument parser.** Flags and positionals are pulled out via
  `extractFlags` / `extractArgs` (`WebViewCLI.java:275`,
  `WebViewCLI.java:292`) so order between flags and positionals does
  not matter. Unknown flags are not errors; they become OAuth query
  parameters.

## S · Structure
- `src/ca/weblite/webview/WebViewCLI.java` — main class, argument
  parser, OAuth URL builder, percent-encoder, JVM relaunch helper.
- `src/ca/weblite/webview/OAuth2CLITest.java` — manual smoke
  harness that drives `WebViewCLI.main` with an Apple Sign-In URL
  (`OAuth2CLITest.java:19`).
- `pom.xml:80` — Maven jar plugin manifest entry naming
  `ca.weblite.webview.WebViewCLI` as the executable JAR main class.
- `README.md:34` — user-facing CLI usage documentation (kept in
  sync with the in-process help banner string in
  `WebViewCLI.help()`).

## O · Operations

### 1. Parse Argument Parser — WebViewCLI.parseParams
File: `src/ca/weblite/webview/WebViewCLI.java`

1. Responsibility: turn `String[] args` into a configured
   `WebViewCLI` instance, exiting with the help banner if the args
   are empty.
2. Methods:
   - `parseParams(String[] args): WebViewCLI`
     - Logic: if `args.length < 1`, call `help()` and `System.exit(1)`
       (`WebViewCLI.java:142`). Split `args` into flag pairs and
       positional URLs via `extractFlags`/`extractArgs`
       (`WebViewCLI.java:146`). Iterate flag pairs: each known
       `-name` writes into the matching `WebViewCLI` field (URL bits,
       window size, port, onLoad, etc., `WebViewCLI.java:159`–
       `WebViewCLI.java:232`). Unknown flags fall through to
       `additionalParams.put(arg.substring(1), val)`
       (`WebViewCLI.java:233`). Assign `oauth.url = args[0]`
       (`WebViewCLI.java:237`). When `-oauth` is set, validate
       required fields and the output-file path
       (`WebViewCLI.java:255`).
3. Constraints / Invariants:
   - OAuth mode requires `client_id`, `redirect_uri`, `scope`, and a
     non-existing output file under an existing parent directory
     (`WebViewCLI.java:255`–`WebViewCLI.java:268`).
   - `-onLoad` accumulates: multiple uses concatenate JS snippets
     separated by `\n` (`WebViewCLI.java:208`).
   - `-onLoadFile` reads the file as UTF-8 and appends to onLoad
     (`WebViewCLI.java:219`).

### 2. Run macOS JVM Relauncher — WebViewCLI.restartJVM
File: `src/ca/weblite/webview/WebViewCLI.java`

1. Responsibility: ensure the process is running with
   `-XstartOnFirstThread` on macOS by re-spawning the JVM if needed.
2. Methods:
   - `restartJVM(String[] args): boolean`
     - Logic: if `os.name` is not `Mac*`/`Darwin*` return `false`
       (`WebViewCLI.java:519`). Read PID via
       `ManagementFactory.getRuntimeMXBean().getName().split("@")[0]`
       (`WebViewCLI.java:524`). If env var
       `JAVA_STARTED_ON_FIRST_THREAD_<pid>` equals `"1"`, return
       `false` (`WebViewCLI.java:529`). Otherwise build a child JVM
       command (`jvmPath`, `-XstartOnFirstThread`, original input
       arguments, `-cp`, classpath, `mainClass`, args), spawn with
       `ProcessBuilder.inheritIO()`, `waitFor`, then return `true`
       (`WebViewCLI.java:534`–`WebViewCLI.java:562`).
3. Constraints / Invariants:
   - Returns `true` only on macOS when a child JVM was launched —
     callers use this signal to short-circuit `main` so the work runs
     only in the child (`WebViewCLI.java:309`).

### 3. Build OAuth Url — WebViewCLI.getFullUrl
File: `src/ca/weblite/webview/WebViewCLI.java`

1. Responsibility: assemble the OAuth authorization-endpoint URL from
   the parsed flags.
2. Methods:
   - `getFullUrl(): String`
     - Logic: start with `url + "?client_id=" + clientId +
       "&redirect_uri=" + encodeUrl(redirectURI)`
       (`WebViewCLI.java:72`). Append `&scope=...` if set
       (`WebViewCLI.java:74`). Append `&response_type=code` when
       `clientSecret != null` else `token`, unless `responseType` is
       set explicitly (`WebViewCLI.java:78`). Append every
       `additionalParams` entry as a percent-encoded `key=value`
       pair (`WebViewCLI.java:88`).
3. Constraints / Invariants:
   - Uses the in-class `encodeUrl` (`WebViewCLI.java:387`), not
     `java.net.URLEncoder` — non-ASCII characters get UTF-8
     percent-encoding via `appendHex` (`WebViewCLI.java:454`).

### 4. Initialize and Show WebView — WebViewCLI.init
File: `src/ca/weblite/webview/WebViewCLI.java`

1. Responsibility: build a `WebView`, wire up the STDIO or socket
   bridge, and start the (blocking) event loop.
2. Methods:
   - `init(): void`
     - Logic: choose the navigation URL — `getFullUrl()` for OAuth
       mode, otherwise the raw `url` (`WebViewCLI.java:44`). Build the
       `WebView` via its fluent setters with the parsed size, title,
       resizable, URL (`WebViewCLI.java:45`). Apply `onLoad` JS via
       `addOnBeforeLoad` if present (`WebViewCLI.java:51`). If
       `port >= 0`, spin up a `WebviewSocketServer` on that port and
       print the bound port to STDOUT on a worker thread
       (`WebViewCLI.java:54`); otherwise wrap STDIN/STDOUT in a
       `WebViewServer` (`WebViewCLI.java:62`). Finally call
       `webview.show()`, which blocks until the window closes
       (`WebViewCLI.java:66`).
3. Constraints / Invariants:
   - `useMessageBoundaries` propagates into the bridge so STDOUT
     messages can optionally be wrapped with `<<<boundary>>>`
     markers, easing parsing for downstream tools
     (`WebViewCLI.java:56`, `WebViewCLI.java:63`).

### 5. Capture OAuth Redirect — WebViewCLI.handleURL
File: `src/ca/weblite/webview/WebViewCLI.java`

1. Responsibility: when the embedded browser navigates to the
   configured redirect URI, dump the query parameters to the OAuth
   output file and exit.
2. Methods:
   - `handleURL(String url): void`
     - Logic: if the URL starts with `redirectURI` and contains `?`
       and `code=`, parse the query string via `getParamsFromURL`
       and write each `key=value` line to `oauthOutputFile`
       (`WebViewCLI.java:402`). On success `System.exit(0)`; if the
       URL was a redirect but no `code` was found, `System.exit(1)`
       (`WebViewCLI.java:414`).
3. Constraints / Invariants:
   - `[INFERRED]` This method is private and is not wired to any
     `WebView` load callback in `init()` — the OAuth capture flow
     appears partially implemented. Treat the OAuth mode as work in
     progress: arguments are parsed, the auth URL is built and
     opened, but the redirect is not currently caught from Java.

### 6. Run Main Entry Point — WebViewCLI.main
File: `src/ca/weblite/webview/WebViewCLI.java`

1. Responsibility: top-level entry point invoked by
   `java -jar WebView.jar`.
2. Methods:
   - `main(String[] args): void`
     - Logic: call `restartJVM(args)`; if it returns `true` the macOS
       child JVM has already been spawned and waited on, so return
       immediately (`WebViewCLI.java:309`). Otherwise call
       `parseParams(args)` inside a try-with-resources, run `init()`,
       and print `"Fin"` to STDOUT when the WebView closes
       (`WebViewCLI.java:313`).
3. Constraints / Invariants:
   - Uses try-with-resources so the optional `WebViewServer` is
     closed when the WebView returns (`WebViewCLI.java:439`).
   - Any thrown exception is printed to STDERR and the process exits
     normally — there is no non-zero exit on argument-parse errors
     after the initial `args.length < 1` check.

## N · Norms
- Logging uses raw `System.out` / `System.err` and stack traces —
  no `java.util.logging.Logger` here. `[DRIFT]` from a typical Java
  logging convention but consistent across the CLI.
- Errors during init are reported via `printStackTrace(System.err)`
  rather than re-thrown (`WebViewCLI.java:317`).
- The CLI uses Java 8 source/target (`pom.xml:41`); avoid
  Java-9+-only APIs here even when other tools allow them.
- Help banner text in `help()` (`WebViewCLI.java:101`) is the
  canonical CLI documentation alongside `README.md:34` — they need
  to stay in sync.

## S · Safeguards
- `args.length < 1` short-circuits to `help()` and `System.exit(1)`
  before any further parsing (`WebViewCLI.java:142`).
- OAuth-mode required fields are validated before the WebView is
  ever shown; failures throw `IllegalArgumentException` from
  `parseParams` with named missing fields (`WebViewCLI.java:255`–
  `WebViewCLI.java:268`).
- OAuth output file: rejects pre-existing files and missing parent
  directories (`WebViewCLI.java:248`, `WebViewCLI.java:251`) to
  avoid clobbering secrets or writing into nonexistent paths.
- `-onLoadFile` swallows `IOException` after printing a stack trace
  and exiting with status 1 (`WebViewCLI.java:228`) — failure to
  read the JS file is fatal.
- `extractFlags` / `extractArgs` assume every argument is at least
  one character. An empty string in `args` would NPE at
  `arg.charAt(0)` (`WebViewCLI.java:279`, `WebViewCLI.java:295`)
  `[INFERRED]`.
- No authentication or origin check on the optional `-port` socket
  server — anyone who can connect can eval arbitrary JavaScript in
  the running WebView. See [[webview-stdio-socket-bridge]] for
  details and ownership of that risk.
