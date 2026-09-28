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
- Publish each tagged release of the assembled six-platform jar
  to **two** repositories: GitHub Packages first, then Maven
  Central. Both receive the same coordinates and the same
  tag-derived version. Maven Central's post-publish sync delay
  blocks downstream first-party projects from building against a
  freshly released version; GitHub Packages is readable the moment
  the deploy returns, so it serves as a fast lane while Central
  propagates. Publication remains gated on `v*` tags — there is no
  snapshot channel and no publication from `master`
  (`.github/workflows/maven-release.yml`, `pom.xml` profiles
  `github-deploy` and `central-deploy`).
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
- **The native libraries therefore do not LINK JAWT.** This is the
  direct consequence of the preload above, and it was never written
  down. JAWT symbols are left **undefined at link time** and resolve
  at load time against the `libjawt` the loader has already brought
  in — on macOS via `-Wl,-undefined,dynamic_lookup`, on Linux by
  simply not passing `-ljawt` (the symbols come from the already
  loaded JVM). Linking `-ljawt` contradicts the design twice over:
  the loader already tolerates JDKs that **do not ship `libjawt` as
  a standalone loadable library** (Safeguards), so a link-time
  dependency on it makes the build require a file the canvas
  explicitly says may not exist — which is exactly how
  `build-mac.sh` came to fail with `ld: library 'jawt' not found`
  on a stock JDK. Both CI workflows already build this way, and CI
  produces the shipped artifact, so the developer scripts were the
  outlier rather than CI.
- **Per-architecture directory inside the jar.** The Maven
  resource configuration explicitly packages native
  subdirectories (`linux_64/**`, `linux_arm64/**`, `osx_64/**`,
  `osx_arm64/**`, `windows_64/**`, `windows_arm64/**`) inside the
  JAR (`pom.xml:65-75`). The source directory for the resource
  is `natives/` at the repo root — **not** `src/`. `natives/` is
  gitignored; its contents are produced by `build-*.sh` locally or
  the per-platform CI matrix on release. At runtime, the extractor
  maps `Architecture.LINUX_64` → `linux_64/libwebview.so`, etc.
- **One deploy target per Maven profile, because the Central
  plugin owns the deploy lifecycle.**
  `central-publishing-maven-plugin` is declared with build
  extensions enabled, which *replaces* the standard deploy goal in
  the `jar` packaging lifecycle. While it is active unconditionally,
  `mvn deploy` can reach Maven Central and nothing else — adding a
  `<distributionManagement>` repository has no effect. The dual
  target is therefore expressed as two mutually exclusive profiles:
  `central-deploy` carries the Central publishing plugin,
  `github-deploy` carries a `<distributionManagement>` entry for the
  GitHub Packages endpoint and the ordinary deploy plugin. The
  release job runs `mvn deploy` twice over one already-assembled
  tree, once per profile. With neither profile active there is no
  deploy target at all, which is the intended outcome: a bare
  `mvn deploy` must not silently publish anywhere, while
  `mvn package` and `mvn install` are untouched for local
  developers.
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
- `src_c/webkit_loader.h`, `src_c/webkit_loader.cpp`,
  `src_c/webkit_shim.h` — the **Linux-only runtime WebKitGTK /
  JavaScriptCore loader** (Operation 7). After this,
  `libwebview.so` carries **no** `DT_NEEDED` on
  `libwebkit2gtk-4.{0,1}` or `libjavascriptcoregtk-4.{0,1}`; those
  are `dlopen`ed at `JNI_OnLoad` (4.1 preferred, 4.0 fallback) and
  every WebKit/JSC call site is routed through function pointers
  via `webkit_shim.h`. GTK3/GLib remain normally linked. macOS
  (WKWebView) and Windows (WebView2) builds are untouched — the
  loader compiles only under `WEBVIEW_GTK` / `__linux__`.
- `README.md` ("Installation" section) — the consumer-facing
  record of both channels: the Maven Central coordinates every
  adopter uses, plus the GitHub Packages repository and the
  authenticated `settings.xml` server entry a first-party
  consumer needs to resolve a release before Central has synced.
- `pom.xml` profiles `github-deploy` and `central-deploy` — the
  two release targets (Operation 9). Exactly one is activated per
  `mvn deploy` invocation; neither is active by default. The
  pre-existing `sign-artifacts` profile is orthogonal and supplies
  the sources jar, javadoc jar and GPG signatures required by
  Central.
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
  They must match the CI recipe for their platform in **linkage**
  and in **which translation units they compile** (Operation 8);
  a script that diverges produces a library the shipped one is not,
  which defeats the point of a local smoke test.
- `.github/workflows/build.yml` and
  `.github/workflows/maven-release.yml` — the canonical six-way
  build pipeline. A `native` matrix job per platform produces and
  uploads one artifact; an assembly/deploy job downloads all six,
  lays them into `natives/<arch>/`, asserts all six are present,
  and runs `mvn package`/`deploy`. This is the only path that
  produces the cross-platform fat jar shipped to GitHub Packages
  and Maven Central.

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

1. Responsibility: produce a single release-ready jar that
   contains all six platform+arch native libraries, and hand it to
   the two publication targets of Operation 9. Each native
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
     (`maven-release.yml:234-249`). Only then does either
     `mvn deploy` invocation run.
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
     a downloadable jar artifact but not publishing — to any
     repository, including GitHub Packages. Only
     `maven-release.yml` publishes, gated on `v*` tag pushes
     (plus manual `workflow_dispatch`). There is no snapshot
     channel.
   - The six-platform presence assertion gates **both** deploys.
     A release missing any platform publishes to neither target,
     so the two repositories can never disagree about what a
     given version contains.
   - The Linux `native` job asserts, via `readelf -d` on the
     built `libwebview.so`, that it has **no** `NEEDED` entry
     matching `webkit2gtk` or `javascriptcoregtk` (4.0 or 4.1)
     — a machine-checked guarantee of the single-portable-binary
     property (Operation 7). A hard WebKit/JSC link dependency
     fails the build.

### 7. Runtime WebKitGTK / JavaScriptCore Resolution (Linux)
Files: `src_c/webkit_loader.h`, `src_c/webkit_loader.cpp`,
`src_c/webkit_shim.h`, `src_c/webview.h`, `src_c/webview_embed.cpp`,
`build-linux.sh`, `.github/workflows/build.yml`,
`.github/workflows/maven-release.yml`, `run-linux-*.sh`

1. Responsibility: ship a **single** `libwebview.so` that loads and
   works on both WebKitGTK 4.1 hosts (Ubuntu 22.04+) and WebKitGTK
   4.0 hosts (Ubuntu 20.04) with no packaging matrix and no change
   to the public Java API or JNI signatures. Achieved by resolving
   WebKitGTK/JavaScriptCore at **runtime** instead of link time. The
   loader is Linux-only (guarded by `WEBVIEW_GTK` / `__linux__`);
   the macOS and Windows code paths and builds are not touched.

2. Loader module (`webkit_loader.h` / `webkit_loader.cpp`): one
   struct of typed function pointers, one member per WebKitGTK/JSC
   symbol the wrapper calls, each member's type taken via
   `decltype(&symbol)` against the real headers (unevaluated —
   creates no link dependency). A global instance `g_wk` plus a
   `pthread_once`-guarded initializer resolve the pointers once:
   - `dlopen` the WebKit library preferring
     `libwebkit2gtk-4.1.so.0`, falling back to
     `libwebkit2gtk-4.0.so.37`.
   - `dlopen` the **matching-generation** JavaScriptCore:
     `libjavascriptcoregtk-4.1.so.0` when 4.1 WebKit opened, else
     `libjavascriptcoregtk-4.0.so.18` — never mix generations, so
     only one libsoup is pulled transitively. Do not link libsoup
     directly.
   - Both handles opened `RTLD_NOW | RTLD_GLOBAL`.
   - Resolve every `webkit_*` symbol from the WebKit handle and
     every `jsc_*` / JavaScriptCore-C-API symbol from the JSC
     handle via `dlsym`.
   - The JS-result symbol subset mirrors `webview.h`'s
     `WEBKIT_MAJOR_VERSION >= 2 && WEBKIT_MINOR_VERSION >= 22`
     guard exactly, so the loader references only symbols the
     compiled call sites reference (the pre-2.22
     `webkit_javascript_result_get_global_context` / `_get_value`
     path is absent from modern headers and must stay behind the
     same `#else`).
   - On any failure — either library not `dlopen`able, or a
     required symbol missing — fail with a clear message naming
     **both** candidate SONAMEs of the library that could not be
     satisfied. Runs from `JNI_OnLoad`; returning `JNI_ERR` makes
     `System.load` throw with that message (same load-time failure
     point as before, now diagnosable on both 4.0 and 4.1 hosts).

3. Call-site routing (`webkit_shim.h`): included by `webview.h` and
   `webview_embed.cpp` immediately after the WebKit/JSC/GTK headers,
   inside the GTK-only regions. It `#define`s each resolved symbol
   name to the corresponding `g_wk` member, so existing call sites
   compile to function-pointer calls with **no textual edits** (the
   member token is not re-expanded — self-reference is safe). It
   also redefines the one GObject cast macro in use, `WEBKIT_WEB_VIEW`,
   to a plain pointer cast, dropping the implicit
   `webkit_web_view_get_type` symbol reference (no `WEBKIT_IS_*`
   checks exist, and all other `WEBKIT_*` tokens are compile-time
   enum/version constants). `webkit_loader.cpp` does **not** include
   the shim — it assigns the real struct members and `dlsym`s by
   string name. GTK/GLib cast macros are unchanged (GTK stays
   linked).

4. Build: compilation still sees the WebKit headers
   (`pkg-config --cflags gtk+-3.0 $WEBKIT_PKG`), but the link step
   no longer adds the WebKit/JSC libraries — link only
   `pkg-config --libs gtk+-3.0` (GTK/GLib), plus `-lX11 -ldl`, and
   add `src_c/webkit_loader.cpp` to the sources. Applied uniformly
   to `build-linux.sh`, the Linux `native` job in `build.yml` and
   `maven-release.yml`, and every `run-linux-*.sh` developer/demo
   script (so the demos exercise the same runtime-resolution path).

5. Verification gate: after the Linux native build, `build.yml`
   runs `readelf -d` on the produced `libwebview.so` and fails the
   job if any `NEEDED` entry matches `webkit2gtk` or
   `javascriptcoregtk` — the machine-checked proof the artifact
   carries no hard WebKit dependency.
6. README: the "Platform support" preamble documents the Linux
   runtime requirement as a single portable binary — the bundled
   `libwebview.so` loads on either WebKitGTK **4.1** (Ubuntu 22.04+)
   or **4.0** (Ubuntu 20.04) present at load time, with no separate
   per-version build, alongside the existing Windows WebView2-Runtime
   note.

### 8. Developer Build Scripts Match the CI Recipe
Files: `build-mac.sh`, `build-linux.sh`

1. Responsibility: produce, for the host platform only, a native
   library whose **linkage and translation units are identical to
   what the CI matrix produces for that same platform** — so a local
   `mvn package` smoke test exercises the library that actually
   ships, not a differently-linked lookalike.

2. **No `-ljawt`, anywhere.** Remove the JAWT link flag from both
   scripts. It contradicts the two-phase preload (Approach) and
   depends on a file the Safeguards say may not exist.
   - `build-mac.sh` links with `-Wl,-undefined,dynamic_lookup`,
     leaving the `JAWT_*` symbols to resolve at load time.
   - `build-linux.sh` passes no JAWT flag at all; the symbols come
     from the already-loaded JVM. Its `-lX11 -ldl` and the
     GTK-only `pkg-config --libs` from Operation 7.4 are unchanged.

3. **Translation units per platform, matching CI.**
   - macOS compiles **only** `src_c/webview_embed.cpp`. It does not
     compile `src_c/webview.c` (which is C built as C++ and only
     emits a deprecation warning there).
   - Linux compiles `src_c/webview.c`, `src_c/webview_embed.cpp`
     and `src_c/webkit_loader.cpp`, as Operation 7.4 already
     requires.

4. **Host architecture, not a hardcoded one.** The Entities section
   already says these scripts drop the library under
   `natives/<arch>/` for the **host** OS/arch, but `build-mac.sh`
   hardcodes `natives/osx_64`. On an Apple Silicon Mac that writes an
   arm64 dylib into the x86_64 directory, and the local jar then
   cannot load it — the failure surfaces far from its cause, as a
   missing native library at runtime. `build-mac.sh` must read
   `uname -m` and select `osx_arm64` for `arm64`/`aarch64` and
   `osx_64` for `x86_64`.

5. **Known remaining gap, deliberately not closed here:**
   `build-linux.sh` still hardcodes `natives/linux_64` and
   `build-windows.sh` still hardcodes `natives/windows_64`, so both
   mis-place the library on an arm64 host. Only the JAWT flag is
   corrected in `build-linux.sh`; `build-windows.sh` is untouched
   (it links no JAWT and delegates to `windows/script/build.bat`).

### 9. Dual-Target Release Publication
Files: `pom.xml`, `.github/workflows/maven-release.yml`

1. Responsibility: publish one tagged release to GitHub Packages
   and to Maven Central, in that order, from the single
   fully-assembled jar produced by Operation 6.
2. Logic:
   - `pom.xml` defines two deploy profiles, neither active by
     default. `github-deploy` declares a
     `<distributionManagement>` repository with id `github`
     pointing at the repository's GitHub Packages Maven endpoint,
     and relies on the standard deploy plugin. `central-deploy`
     declares `central-publishing-maven-plugin` with build
     extensions enabled and the pre-existing `central` server id
     and auto-publish setting. The Central plugin must live
     **inside** its profile — see the Approach note on lifecycle
     ownership.
   - The deploy job authenticates two servers in one Maven
     settings file: `github`, with the workflow's built-in
     repository token, and `central`, with the portal API token
     credentials. The job requests `packages: write` permission
     in addition to `contents: read`.
   - After the version is set from the tag, the job deploys twice
     over the same tree: first `github-deploy`, then
     `central-deploy` with the `sign-artifacts` profile and the
     GPG passphrase. GitHub Packages goes first so the fast lane
     opens before the slower Central publication begins.
3. Constraints / Invariants:
   - Ordering is load-bearing, not cosmetic: the whole point of
     the second target is to shorten the wait, so a failure in
     the Central leg must still leave the GitHub Packages
     artifact published and usable.
   - GPG signing is required for Central and unnecessary for
     GitHub Packages. The `github-deploy` leg therefore runs
     without `sign-artifacts`, which also keeps the private key
     out of the first deploy entirely.
   - Both legs publish identical coordinates and version. Never
     let the two diverge — a consumer resolving
     `ca.weblite:webview:<v>` must get the same artifact
     whichever repository answers first.
   - A given version can be published to each repository only
     once; neither target accepts a redeploy of an existing
     release version. Re-running a release therefore requires a
     new tag, not a retry of the old one.

## N · Norms
- The developer `build-*.sh` scripts must quote every shell
  expansion that carries a filesystem path — `"${JAVA_HOME}"`
  above all — in compiler include/library flags and anywhere
  else a path is interpolated. JDKs are routinely installed at
  paths containing spaces (e.g. a JetBrains Runtime bundled
  inside `/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home`);
  an unquoted `-I${JAVA_HOME}/include` word-splits on the space
  and the build fails with "no such file or directory: 'IDEA'".
  Quoting keeps the scripts usable with any JDK regardless of
  where it lives.
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
- **Maven Central is the canonical public distribution channel.**
  GitHub Packages requires authentication for *reads* even when
  the repository and package are public, so a consumer must hold a
  token with package-read scope to resolve from it. That is fine
  for first-party projects and for CI inside the owning
  organisation, which have such a token already, and unusable for
  external adopters. Document Central coordinates in `README.md`
  for external users; treat GitHub Packages as an internal fast
  lane and never as a replacement for the Central release.
- The Linux native resolves WebKitGTK/JavaScriptCore at **runtime**
  (Operation 7), never via a link-time `-l`. Any newly-used
  `webkit_*` / `jsc_*` symbol must be added to the `webkit_loader`
  pointer set **and** the `webkit_shim` redirects in the same
  change, and must belong to the API subset common to WebKitGTK 4.0
  and 4.1 (if a symbol is 4.1-only, find the 4.0-compatible
  equivalent or guard it behind the version `#if`). Never add a
  WebKit/JSC `-l` flag or `--libs` pkg-config module to any Linux
  build entry point.

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
- **Never link JAWT (never-relax).** Because the line above tolerates
  a JDK with no standalone `libjawt`, no build entry point — CI job,
  developer `build-*.sh`, or `run-*` demo script — may pass `-ljawt`
  or otherwise create a link-time dependency on it. JAWT resolves at
  load time only. A build that links it fails on exactly the JDKs the
  loader was written to survive.
- **CI is the source of truth for linkage (never-relax).** A
  developer `build-*.sh` must produce a library whose linkage matches
  what the CI matrix produces for the same platform. Where the two
  diverge, the script is wrong, not CI — CI builds the artifact that
  ships, so a local smoke test against a differently-linked library
  proves nothing about the release.
- **A release publishes to both targets or to neither.** The
  six-platform presence assertion runs before either deploy, so a
  jar missing a platform never reaches GitHub Packages or Maven
  Central. Do not move a deploy step ahead of that assertion, and
  do not add a deploy step to `build.yml` — publication belongs to
  tag-triggered releases only.
- **Deploy targets stay inside their profiles (never-relax).**
  Moving `central-publishing-maven-plugin` back into the top-level
  build silently re-breaks the GitHub Packages leg: with its
  extensions active unconditionally it replaces the deploy goal for
  every invocation, so the `github-deploy` profile deploys to
  Central instead, or not at all. A bare `mvn deploy` having no
  target is the intended behaviour, not an oversight to fix.
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
- The Linux WebKit loader fails **loudly**, not silently: if
  neither `libwebkit2gtk-4.1.so.0` nor `libwebkit2gtk-4.0.so.37`
  (respectively the two JavaScriptCore SONAMEs) can be `dlopen`ed,
  or a required symbol is missing, `JNI_OnLoad` returns `JNI_ERR`
  and `System.load` throws `UnsatisfiedLinkError` with a message
  naming both candidate SONAMEs. This replaces the old
  dynamic-linker error — which named only the 4.1 SONAME and made a
  4.0 host look broken — with a message that makes the
  missing-runtime case obvious on either host.
