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
  lambdas would work (e.g. `WebView.java:181`,
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
  the owning Java wrapper. See `WebView.java:167`,
  `EmbeddedWebView.java:33`. Short-lived dispatches should remove
  themselves from `heap` once their `run()` returns to avoid
  leaks (`WebView.java:215`, `EmbeddedWebView.java:160`).
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

- The in-process `WebView` (zserge backend) wants the host's
  main thread and runs its own event loop. Cross-thread work
  must go through `WebView.dispatch(Runnable)`
  (`WebView.java:213`).
- The embedded Swing components (`EmbeddedWebView`,
  `OffscreenWebView`) do **not** take ownership of the host
  application's event loop; they run alongside the AWT event
  dispatch thread and the platform native event loops described
  in each canvas.

## Logging and error handling

- Logging style is mixed across the codebase:
  - The library code (`WebViewNative.java:39`,
    `NativeLibraryUtil.java:386`) uses
    `java.util.logging.Logger`.
  - Do not introduce a third logging facade (SLF4J, Log4j, etc.)
    without a discussion — keeping the dependency footprint at
    "Java 8 stdlib + JUnit" is intentional (`pom.xml:43`).
- Errors during init are typically printed (stack trace) and
  the process continues or exits non-zero; they are not
  rethrown (`WebViewNative.java:38`). Where the documented
  behaviour is to swallow an exception — for example, the
  `libjawt` preload — keep the catch silent and document the
  reason inline.

## Resource lifecycle

- Long-lived objects that own native or external resources
  implement `AutoCloseable` / `dispose()` — e.g.
  `EmbeddedWebView` and `OffscreenWebView`. Callers should use
  try-with-resources where possible.

## Public surface

- The system property `ca.weblite.webview.mode` is part of the
  documented public surface (`WebViewComponent.java:58`,
  `README.md ("Choosing a mode" section)`). Do not rename it;
  document any new accepted values in both places.
- The platform-support matrix in
  `README.md ("Platform support" section)` is contract with
  users — keep it consistent with what
  `WebViewComponent.resolveDefaultMode()` actually picks
  (`WebViewComponent.java:90`) and what each subclass actually
  supports.

## Tests

- Tests live under `test/ca/weblite/webview/...`
  (`pom.xml:54`). The project uses JUnit 4 (`pom.xml:46`).
- There are no automated integration tests for the native
  embedding components today; verification is done via the
  Swing demo (`demos/WebViewHeavyweightDemo/`) on each target
  platform. New unit-level tests for pure logic (translators
  like `GdkInput`, encoders, etc.) are welcome and should not
  need a display.

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
