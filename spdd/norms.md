# Norms

Cross-cutting engineering norms that apply to every Canvas
in this repository. Add invariants here that don't belong
inside any single feature's REASONS Canvas (e.g. "all
monetary values are in cents", logging conventions,
security baselines).

## Language level

- Java 8 source and target — `maven.compiler.source` /
  `maven.compiler.target` are both `1.8` (`pom.xml:41`). Do not use
  Java-9+-only APIs in production code (`var`, `List.of`,
  `String.isBlank`, modules, etc.). The build will compile on
  newer JDKs but the bytecode targets Java 8.
- Anonymous-inner-class style is used for JNI callbacks even where
  lambdas would work (e.g. `WebView.java:183`,
  `EmbeddedWebView.java:143`) to stay debuggable and stack-trace-
  readable on older JDKs. Lambdas are fine elsewhere.

## JNI lifecycle

- **Anchor every JNI callback in a Java field.** Any object passed
  across the JNI boundary that may be invoked later
  (`WebViewNativeCallback`, dispatch `Runnable`, etc.) MUST be
  stored in a long-lived Java reference, otherwise the JVM
  garbage-collects it while the native side still holds a
  function pointer and you SIGSEGV on the next invocation.
  Existing pattern: a `heap: List<Object>` / `ArrayList` field on
  the owning Java wrapper. See `WebView.java:169`,
  `EmbeddedWebView.java:33`. Short-lived dispatches should remove
  themselves from `heap` once their `run()` returns to avoid
  leaks (`WebView.java:217`, `EmbeddedWebView.java:160`).
- **Native peers are opaque `long` handles.** A peer of `0`
  always means "disposed / never created." Every public method
  on a native wrapper must guard against this — the convention
  is a `checkAlive()` private method that throws
  `IllegalStateException` when `peer == 0`
  (`EmbeddedWebView.java:204`, `OffscreenWebView.java:116`).
- **`dispose()` is idempotent.** Implementations test `peer != 0`,
  zero the field, then call the native destroy. See
  `EmbeddedWebView.java:194`, `OffscreenWebView.java:108`.
- **Never call `System.loadLibrary` / `System.load` outside
  `NativeLoader`.** The static initializer in
  `WebViewNative.java:19` is the single entry point that loads
  the `webview` shared library; everyone else gets it for free
  on first reference. The macOS `libjawt` preload at
  `WebViewNative.java:30` is the documented exception and must
  stay in that one place.

## Threading

- The single-threaded `dispatchService` in `WebViewClient`
  (`WebViewClient.java:32`) is authoritative for listener
  mutation and listener fire. ALL listener add/remove
  AND all `fireOnLoad`/`fireMessage` calls must hop onto the
  dispatch thread before mutating or invoking
  (`WebViewClient.java:110`, `WebViewClient.java:252`). Listener
  callbacks therefore do NOT need their own locking.
- The in-process `WebView` (zserge backend) wants the host's
  main thread and runs its own event loop. Cross-thread work
  must go through `WebView.dispatch(Runnable)`
  (`WebView.java:215`).
- The STDIO/socket bridge serialises STDOUT writes through a
  single-threaded `ExecutorService` (`WebViewServer.java:30`).
  Never write to the output stream directly from a listener.

## Logging and error handling

- Logging style is mixed across the codebase:
  - The CLI surface (`WebViewCLI.java`) uses raw
    `System.out` / `System.err` and `printStackTrace`.
  - The library code (`WebViewNative.java:39`,
    `WebviewSocketServer.java:37`, `NativeLibraryUtil.java:386`)
    uses `java.util.logging.Logger`.
  - Do not introduce a third logging facade (SLF4J, Log4j, etc.)
    without a discussion — keeping the dependency footprint at
    "Java 8 stdlib + JUnit" is intentional (`pom.xml:43`).
- Errors during init are typically printed (stack trace) and
  the process continues or exits non-zero; they are not
  rethrown (`WebViewCLI.java:317`, `WebViewNative.java:38`).
  Where the documented behaviour is to swallow an exception —
  for example, the `libjawt` preload and the
  `WebViewSocket.close` cleanup — keep the catch silent and
  document the reason inline.
- `Throwable` printouts inside reader threads are suppressed
  when a `closed` flag is set so intentional shutdown doesn't
  produce noisy stacks (`WebViewClient.java:341`,
  `WebViewServer.java:46`). Apply the same pattern to any new
  background-thread loops you add.

## JSON

- Use the in-tree `ca.weblite.webview.nanojson` package for JSON
  parsing and writing (`WebViewClient.java:8`). Do not pull in
  Jackson, Gson, or `org.json` — keeping the runtime classpath
  free of extra JSON libraries is part of why nanojson was
  vendored in.

## Resource lifecycle

- Long-lived objects that own native or external resources
  (subprocess, socket, server) implement `AutoCloseable` —
  e.g. `WebViewCLI`, `WebViewClient`, `WebViewServer`,
  `WebViewSocket`, `WebviewSocketServer`. Callers should use
  try-with-resources where possible
  (`WebViewCLI.java:313`).
- Shutdown hooks are appropriate for processes whose lifetime
  is bounded by the JVM (e.g. the child JVM in
  `WebViewCLIClient.java:49`). Do not register one without
  thinking about idempotency — `destroyForcibly` is safe to
  call twice but heavier operations may not be.

## Public surface

- The Maven `mainClass` for the executable JAR is
  `ca.weblite.webview.WebViewCLI` (`pom.xml:80`). Do not move
  this — `java -jar WebView.jar` consumers rely on it.
- The system property `ca.weblite.webview.mode` is part of the
  documented public surface (`WebViewComponent.java:58`,
  `README.md:148`). Do not rename it; document any new accepted
  values in both places.
- The platform-support matrix in `README.md:178` is contract
  with users — keep it consistent with what
  `WebViewComponent.resolveDefaultMode()` actually picks
  (`WebViewComponent.java:90`) and what each subclass actually
  supports.
- The CLI help banner string in `WebViewCLI.help()`
  (`WebViewCLI.java:101`) is also user-facing; keep it in sync
  with the README usage docs at `README.md:34`.

## Tests

- Tests live under `test/ca/weblite/webview/...`
  (`pom.xml:54`). The project uses JUnit 4 (`pom.xml:46`).
- The only integration test today is
  `WebViewCLIClientTest.testEval`
  (`test/.../WebViewCLIClientTest.java:50`), and it actually
  launches a child JVM with a real WebView pointed at a public
  URL. It is therefore *not* hermetic. Treat it as a smoke
  test, not a unit test. New unit-level tests for pure logic
  (parsers, encoders, translators like
  `GdkInput`) are welcome and should not need a display.

## Native binary packaging

- Native binaries live under `src/<arch>/` and are bundled into
  the JAR via explicit `<include>` lines in `pom.xml`
  (`pom.xml:54`). Adding a new architecture requires:
  1. A fresh native build (one of the `build-*.sh` scripts).
  2. A new value in `NativeLibraryUtil.Architecture`
     (`NativeLibraryUtil.java:84`).
  3. A switch arm in
     `NativeLibraryUtil.getPlatformLibraryName`
     (`NativeLibraryUtil.java:227`).
  4. A new `<include>` line in `pom.xml`.
- Windows ships only `webview.dll` —
  `WebView2Loader.lib` is statically linked in (see comment at
  `WebViewNative.java:23`). Do not re-introduce the standalone
  `WebView2Loader.dll` extraction path.

## Security

- The optional TCP socket bridge (`-port` flag) accepts arbitrary
  JavaScript and binds to all interfaces by default
  (`WebviewSocketServer.java:28`,
  `WebViewServer.java:111`). It has no authentication. Document
  this in any operational guidance and prefer SSH tunnels over
  exposing the port directly. See
  `[[webview-stdio-socket-bridge]]` Safeguards section.
- OAuth-mode output files are refused if the parent directory
  is missing or the file already exists
  (`WebViewCLI.java:248`, `WebViewCLI.java:251`) — keep this
  guard; it prevents silently overwriting captured tokens.
