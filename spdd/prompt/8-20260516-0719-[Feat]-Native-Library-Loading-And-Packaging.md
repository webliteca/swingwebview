---
bootstrap: true
generated_at: 2026-05-16T07:19:13-07:00
---

# REASONS Canvas: Native Library Loading & Packaging

## R · Requirements
- Ship per-platform native shared libraries (`libwebview.so`,
  `libwebview.dylib`, `webview.dll`) inside the WebView JAR and
  load the right one at JVM startup automatically — no manual
  `System.loadLibrary` calls in caller code (`WebViewNative.java:19`,
  `pom.xml:55`).
- Resolve the current platform via `os.name` + `os.arch` and map
  to a directory inside the JAR. The Maven-Central artifact ships
  exactly six combinations: `linux_64`, `linux_arm64`, `osx_64`,
  `osx_arm64`, `windows_64`, `windows_arm64`
  (`NativeLibraryUtil.java:84`, `pom.xml:67-73`).
- Extract the native binary to a temp file and load it via
  `System.load(absolutePath)`. Fall back to
  `System.loadLibrary` first in case the OS dynamic linker can
  resolve the library on its own
  (`NativeLoader.java:131`).
- On macOS, also pre-load `libjawt` BEFORE the WebView library so
  the embed engine's `JAWT_GetAWT` references resolve. Silently
  ignore `UnsatisfiedLinkError` here — some JDK distributions
  don't ship `libjawt` as a standalone loadable library and AWT
  may have already pulled it in (`WebViewNative.java:30`).
- On Windows the `WebView2Loader.lib` is statically linked into
  `webview.dll` so no separate `WebView2Loader.dll` extraction is
  needed (`WebViewNative.java:23`,
  `.github/workflows/build.yml:171`). The system Microsoft Edge
  WebView2 Runtime provides the actual Chromium binaries
  (`README.md ("Platform support" section)`).
- Definition of Done: indirectly validated by every other feature
  in this repo — if the native loader is broken, nothing else
  works. No standalone unit tests; smoke tested by the demos
  (`run-mac-demo.sh`, `run-linux-demo.sh`,
  `run-windows-demo.bat`).

## E · Entities
- **NativeLoader** (`NativeLoader.java:79`) — entry point with
  static `loadLibrary(String, String...)` and `extractRegistered()`.
  Picks an extractor implementation at class-init based on the
  loading classloader:
  - `DefaultJniExtractor` when loaded by the system classloader
    (supports transitively-linked libs with shared globals).
  - `WebappJniExtractor` otherwise (multi-classloader-safe but
    no shared globals) (`NativeLoader.java:96`).
- **JniExtractor** (interface, `JniExtractor.java`) —
  contract for unpacking a native binary out of the JAR to a
  loadable temp path.
- **NativeLibraryUtil** (`NativeLibraryUtil.java:82`) — host
  detection (`Architecture` enum: LINUX_32, LINUX_64,
  LINUX_ARM, LINUX_ARM64, WINDOWS_32, WINDOWS_64,
  WINDOWS_ARM64, OSX_32, OSX_64, OSX_ARM64, OSX_PPC, AIX_32,
  AIX_64), library filename construction
  (`getPlatformLibraryName`: `lib<name>.so` / `<name>.dll` /
  `lib<name>.dylib`), and search-path traversal.
- **BaseJniExtractor** / **DefaultJniExtractor** /
  **WebappJniExtractor** (`BaseJniExtractor.java:58`,
  `DefaultJniExtractor.java`, `WebappJniExtractor.java`) — extract
  resources to `java.io.tmpdir` / `./tmplib`, with leftover
  cleanup older than 5 minutes (`BaseJniExtractor.java:66`).
- **MxSysInfo** (`MxSysInfo.java`) — optional richer
  platform descriptor (`mx.sysinfo` system property) layered on
  top of `os.name`/`os.arch`
  (`BaseJniExtractor.java:89`).
- **WebViewNative** (`WebViewNative.java:17`) — the JNI surface;
  its static initializer is the choke point that loads the
  `webview` shared library before any other class touches the
  JNI entry points.

## A · Approach
- **Static initializer in WebViewNative is the load point.** The
  first reference to any `WebViewNative.webview_*` method
  triggers class-init, which runs the loader block. This means
  every other class that calls into native code — `WebView`,
  `EmbeddedWebView`, `OffscreenWebView`, etc. — gets free
  lazy loading.
- **Two-phase JAWT preload on macOS.** Loading `libjawt`
  manually before the WebView dylib avoids a SIGSEGV at PC=0 on
  the first JAWT call. The comment at
  `WebViewNative.java:25` records the diagnosis.
- **Per-architecture directory inside the jar.** The Maven
  resource configuration explicitly packages native
  subdirectories (`linux_64/**`, `linux_arm64/**`, `osx_64/**`,
  `osx_arm64/**`, `windows_64/**`, `windows_arm64/**`) inside the
  JAR (`pom.xml:65-75`). The source directory for the resource
  is `natives/` at the repo root — **not** `src/`. `natives/` is
  gitignored; its contents are produced by `build-*.sh` locally or
  the per-platform CI matrix on release. At runtime, the extractor
  maps `Architecture.LINUX_64` → `linux_64/libwebview.so`, etc.
- **Leftover cleanup, not pinning.** Extracted native files live
  in the OS temp directory and are deleted if older than 5
  minutes when the next process starts
  (`BaseJniExtractor.java:66`,
  `BaseJniExtractor.deleteLeftoverFiles`). This avoids tmpdir
  accumulation across many runs without forcing
  `File.deleteOnExit`.

## S · Structure
- `src/ca/weblite/webview/WebViewNative.java` — JNI entry
  points and the `static { … loadLibrary("webview") … }`
  initializer (`WebViewNative.java:19`).
- `src/ca/weblite/webview/nativelib/NativeLoader.java` — public
  loader API (`loadLibrary`, `extractRegistered`).
- `src/ca/weblite/webview/nativelib/NativeLibraryUtil.java` —
  host detection + filename derivation.
- `src/ca/weblite/webview/nativelib/JniExtractor.java` and
  implementations (`BaseJniExtractor`, `DefaultJniExtractor`,
  `WebappJniExtractor`) — JAR → temp-file extraction.
- `src/ca/weblite/webview/nativelib/MxSysInfo.java` — optional
  fine-grained platform descriptor.
- `natives/<arch>/<libname>` — the build-output location for
  per-platform native binaries. **Gitignored**; not checked into
  the source tree. Populated by `build-*.sh` (local) or the
  per-platform CI matrix (release). The expected six combinations
  are `linux_64/libwebview.so`, `linux_arm64/libwebview.so`,
  `osx_64/libwebview.dylib`, `osx_arm64/libwebview.dylib`,
  `windows_64/webview.dll`, `windows_arm64/webview.dll`.
  `WebView2Loader.lib` is statically linked into `webview.dll`
  on Windows so no separate `WebView2Loader.dll` ships in the jar
  (`.github/workflows/build.yml:171`).
- `pom.xml:65-75` — Maven resource entries that copy each
  per-architecture directory from `natives/<arch>/` into
  `target/classes/<arch>/` at JAR-build time, so they land at the
  jar root where `NativeLibraryUtil.getPlatformLibraryPath`
  expects them.
- `build-linux.sh`, `build-mac.sh`, `build-windows.sh` —
  developer scripts that build a single-platform native lib for
  the host OS/arch and drop it under `natives/<arch>/`. A local
  `mvn package` after one of these produces a jar containing only
  that host's native lib (sufficient for local smoke testing).
- `.github/workflows/build.yml` and
  `.github/workflows/maven-release.yml` — the canonical six-way
  build pipeline. A `native` matrix job per platform produces and
  uploads one artifact; an assembly/deploy job downloads all six,
  lays them into `natives/<arch>/`, asserts all six are present,
  and runs `mvn package`/`deploy`. This is the only path that
  produces the cross-platform fat jar shipped to Maven Central.

## O · Operations

### 1. Detect Architecture — NativeLibraryUtil.getArchitecture
File: `src/ca/weblite/webview/nativelib/NativeLibraryUtil.java`

1. Responsibility: classify the running JVM into one of the
   `Architecture` enum values based on `os.name` and `os.arch`.
2. Methods:
   - `getArchitecture(): Architecture`
     - Logic: read `os.name` (lowercased) and route based on
       substring match (`nix`/`nux`, `aix`, `win`, `mac`)
       (`NativeLibraryUtil.java:109`). Within each, read
       `os.arch` via `getProcessor()` to disambiguate
       Intel_32 / Intel_64 / ARM / AARCH_64 / PPC / PPC_64
       (`NativeLibraryUtil.java:169`). Cache the result in the
       static `architecture` field
       (`NativeLibraryUtil.java:96`).
3. Constraints / Invariants:
   - The `os.name` check uses substring matching, so e.g.
     `"Darwin"` does not match — Apple JVMs report `"Mac OS X"`
     which does, but a more permissive check might miss future
     OS rebrands `[INFERRED]`.
   - The result is cached at class-init scope — changing
     `os.name` at runtime won't change the answer.

### 2. Derive Library Name — NativeLibraryUtil.getPlatformLibraryName
File: `src/ca/weblite/webview/nativelib/NativeLibraryUtil.java`

1. Responsibility: turn a logical library name (`"webview"`)
   into the per-platform filename
   (`libwebview.so`, `libwebview.dylib`, `webview.dll`).
2. Methods:
   - `getPlatformLibraryName(String libName): String`
     - Logic: switch on `getArchitecture()`. Linux/AIX → `lib<x>.so`;
       Windows → `<x>.dll`; macOS → `lib<x>.dylib`
       (`NativeLibraryUtil.java:227`).
3. Constraints / Invariants:
   - `WINDOWS_ARM64` and `LINUX_ARM`/`LINUX_ARM64` use the
     same suffixes as their Intel siblings.
   - The `default:` branch leaves `name = null` —
     `[INFERRED]` callers must handle a null return for
     unknown platforms (the `loadNativeLibrary` path checks
     `Architecture.UNKNOWN` upstream,
     `NativeLibraryUtil.java:326`).

### 3. Extract and Load Library — NativeLoader.loadLibrary
File: `src/ca/weblite/webview/nativelib/NativeLoader.java`

1. Responsibility: load a logical library, first via
   `System.loadLibrary` (in case of OS-installed lib), then by
   extracting from the JAR and `System.load`-ing the temp file.
2. Methods:
   - `loadLibrary(String libName, String... searchPaths): void`
     - Logic: try `System.loadLibrary(libName)` first
       (`NativeLoader.java:136`). On `UnsatisfiedLinkError`, call
       `NativeLibraryUtil.loadNativeLibrary(jniExtractor, libName,
       searchPaths)` (`NativeLoader.java:139`). If THAT returns
       false, rethrow as a new `IOException`
       (`NativeLoader.java:141`).
3. Constraints / Invariants:
   - The fallback path tries multiple search prefixes inside
     the JAR (`natives/`, ``, `META-INF/lib/`) before giving
     up (`NativeLibraryUtil.java:331`).
   - The choice between `DefaultJniExtractor` and
     `WebappJniExtractor` is made at class-init based on
     whether `NativeLoader.class.getClassLoader() ==
     ClassLoader.getSystemClassLoader()`
     (`NativeLoader.java:97`).

### 4. Load WebView Native Binary — WebViewNative static initializer
File: `src/ca/weblite/webview/WebViewNative.java`

1. Responsibility: pre-load `libjawt` (best-effort on macOS) and
   then load the `webview` shared library before any JNI entry
   point is referenced.
2. Methods:
   - `static { … }` block (`WebViewNative.java:19`)
     - Logic: try `System.loadLibrary("jawt")`, silently
       ignoring `UnsatisfiedLinkError`
       (`WebViewNative.java:30`). Call
       `NativeLoader.loadLibrary("webview")`
       (`WebViewNative.java:37`). On `IOException`, log to the
       class logger at SEVERE
       (`WebViewNative.java:38`).
3. Constraints / Invariants:
   - The `IOException` branch logs but does NOT rethrow —
     `[INFERRED]` subsequent JNI calls will SIGSEGV with
     unresolved symbols. The log is the only diagnostic.
   - The static block runs once per classloader on first
     reference to any `WebViewNative.*` method.

### 5. Pack Native Binaries — pom.xml resources
File: `pom.xml`

1. Responsibility: copy each per-architecture native directory
   from the (gitignored) `natives/` build-output root into
   `target/classes/<arch>/` so the JAR-packaging step bundles
   them at the jar root.
2. Logic: the `<resources>` section sets `<directory>natives</directory>`
   and explicitly enumerates `linux_64/**`, `linux_arm64/**`,
   `osx_64/**`, `osx_arm64/**`, `windows_64/**`, `windows_arm64/**`
   (`pom.xml:65-75`). `windows_32` is **not** included — it was
   dropped when the CI matrix was reduced to the six
   currently-supported combinations.
3. Constraints / Invariants:
   - Adding a new architecture requires three coordinated edits:
     a new `<include>` line here, a new matrix entry in **both**
     CI workflows (see Operation 6), and (typically) a new
     `Architecture` enum value plus `getPlatformLibraryName`
     branch in `NativeLibraryUtil.java`.
   - `natives/` is a **build output**, not a source location.
     `mvn clean` does not delete it (it lives outside `target/`),
     but it is not in version control. A clean checkout produces
     an empty-of-natives jar until either a `build-*.sh` script
     or the CI pipeline populates it. This is deliberate — it
     prevents the prior "stale checked-in binary" failure mode
     where consumers silently shipped outdated natives.

### 6. Cross-Platform Release Build — CI workflows
Files: `.github/workflows/build.yml`,
`.github/workflows/maven-release.yml`

1. Responsibility: produce a single Maven-Central-ready jar that
   contains all six platform+arch native libraries. Each native
   must be compiled on a runner of its matching OS/arch — there
   is no cross-compilation path.
2. Logic (two-phase, identical structure in both workflows):
   - **Phase 1 — `native` matrix job.** Six parallel runs, one
     per `(os, native_dir, lib_name)` tuple
     (`maven-release.yml:36-60`). Each builds the native lib
     into `natives/<native_dir>/<lib_name>` and uploads it as
     an artifact named `native-<native_dir>` with
     `if-no-files-found: error` so an empty build fails fast
     (`maven-release.yml:175-180`).
     `fail-fast: true` on the matrix aborts the release if any
     platform fails (`maven-release.yml:34`).
   - **Phase 2 — assembly/deploy job.** `needs: native` gates
     this on all six succeeding. Downloads every `native-*`
     artifact, copies each into `natives/<arch>/`, then runs a
     **6-platform-presence assertion** that fails the job if
     any of the six expected directories is empty
     (`maven-release.yml:234-249`). Only then does
     `mvn deploy` run.
3. Constraints / Invariants:
   - The presence assertion's `required=(...)` array is the
     authoritative list of platforms shipped. It must stay in
     sync with the matrix in Phase 1 and the `<include>` list
     in `pom.xml`. Drift between any two of those three lists
     either silently drops a platform from the jar or hangs
     the release.
   - macOS x86_64 is cross-compiled from an `arm64` runner via
     `clang -arch x86_64` because GitHub is phasing out Intel
     macOS runners (`maven-release.yml:37-43`). This relies on
     the macOS SDK being universal — it is, today.
   - Windows builds statically link `WebView2LoaderStatic.lib`
     so the published jar carries only `webview.dll`, no
     `WebView2Loader.dll` (`build.yml:171`).
   - `build.yml` runs on every push/PR to `master`, producing
     a downloadable jar artifact but not publishing. Only
     `maven-release.yml` publishes, gated on `v*` tag pushes.

## N · Norms
- Use `NativeLoader.loadLibrary` (or rely on the static
  initializer in `WebViewNative`) — do not call
  `System.loadLibrary` or `System.load` directly from feature
  code.
- New native dependencies must be listed in the
  `META-INF/lib/AUTOEXTRACT.LIST` classpath resource if they
  need to be available to the OS dynamic linker as transitive
  deps (`NativeLoader.java:67`). The current build does not
  use this mechanism.
- When adding a new architecture, update **all** of the
  following in a single commit, or the release will silently
  ship a degraded jar:
  - the `Architecture` enum (`NativeLibraryUtil.java:84`),
  - the `getPlatformLibraryName` switch
    (`NativeLibraryUtil.java:227`),
  - the `<include>` list in `pom.xml`,
  - a new matrix entry in **both**
    `.github/workflows/build.yml` and
    `.github/workflows/maven-release.yml`,
  - the `required=(...)` array in the "Verify all 6 platforms
    are present" step of both workflows,
  - one of the `build-*.sh` scripts (or a new one) for local
    builds on the matching OS/arch.

## S · Safeguards
- `WebViewNative` swallows `IOException` from the loader and
  only logs (`WebViewNative.java:39`). Operationally, any
  failed load means the next JNI call SIGSEGVs; treat the
  SEVERE log line as the early-warning signal.
- `loadLibrary("jawt")` is wrapped in its own try/catch and
  swallows `UnsatisfiedLinkError` — see explanation at
  `WebViewNative.java:31`. Do not change this to rethrow:
  some JDKs do not ship `libjawt` as a standalone loadable
  library.
- The extractor cleans up leftover libraries older than 5
  minutes (`BaseJniExtractor.java:66`), bounded by the
  `org.scijava.nativelib.leftoverMinAgeMs` system property.
  This bounds disk usage across many process launches.
- `Architecture.UNKNOWN` short-circuits the load path with a
  debug log instead of throwing
  (`NativeLibraryUtil.java:326`) — for an unrecognised
  platform you get a clean "no native library available"
  message rather than a stack trace.
- The extractor walks multiple resource path prefixes
  (`natives/`, ``, `META-INF/lib/`) so the JAR layout can
  evolve without breaking existing consumers
  (`NativeLibraryUtil.java:334`).
