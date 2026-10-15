/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */

/*
 * In-process Java launcher for the macOS media-permission demo bundle
 * (Canvas 35 D14).
 *
 * macOS asks the *main bundle* of the process that opens the camera or
 * microphone for NSCameraUsageDescription / NSMicrophoneUsageDescription, and
 * [NSBundle mainBundle] follows the executable.  A shell-script .app that execs
 * the system `java` still gets java's (empty) bundle, and a launcher that
 * spawns `java` makes a separate process.  So this executable lives in
 * WebViewMediaPermissionDemo.app/Contents/MacOS/ (and carries the same
 * Info.plist in its own __TEXT,__info_plist section), and runs the JVM in its
 * own process through the JDK's JLI_Launch -- the entry point of the `java`
 * command itself, which also does the macOS-specific start-up AWT needs (the
 * JVM on a second thread, the first thread parked in the Cocoa run loop).
 *
 * libjli re-enters this executable's `main` on that second thread; `main` is
 * re-entrant (the same lookups again, and libjli's own guard stops the
 * recursion).
 *
 * Usage (as `java`):  <launcher> -cp <classpath> <main class> [args...]
 * Needs JAVA_HOME pointing at the JDK whose libjli is to be loaded.
 */

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef unsigned char jboolean_t;
typedef int jint_t;

typedef int (*JLI_Launch_t)(int argc, char **argv,
                            int jargc, const char **jargv,
                            int appclassc, const char **appclassv,
                            const char *fullversion, const char *dotversion,
                            const char *pname, const char *lname,
                            jboolean_t javaargs, jboolean_t cpwildcard,
                            jboolean_t javaw, jint_t ergo);

static void fail(const char *what, const char *detail) {
    fprintf(stderr, "swingwebview-launcher: %s%s%s\n", what,
            detail ? ": " : "", detail ? detail : "");
}

int main(int argc, char **argv) {
    const char *home = getenv("JAVA_HOME");
    if (!home || !*home) {
        fail("JAVA_HOME is not set (it must name the JDK to run)", NULL);
        return 1;
    }
    /* JDK 9+, then JDK 8's two layouts. */
    static const char *const kRel[] = {
        "/lib/libjli.dylib",
        "/lib/jli/libjli.dylib",
        "/jre/lib/jli/libjli.dylib",
    };
    char path[4096];
    void *jli = NULL;
    size_t i;
    for (i = 0; i < sizeof(kRel) / sizeof(kRel[0]) && !jli; i++) {
        snprintf(path, sizeof(path), "%s%s", home, kRel[i]);
        if (access(path, R_OK) == 0) jli = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    }
    if (!jli) {
        fail("no loadable libjli.dylib under JAVA_HOME", home);
        return 1;
    }
    JLI_Launch_t launch = (JLI_Launch_t)dlsym(jli, "JLI_Launch");
    if (!launch) {
        fail("JLI_Launch not found in", path);
        return 1;
    }
    /* Exactly what the `java` command passes: no extra JVM args or app
     * class, wildcard classpath expansion on, not javaw, default ergonomics. */
    return launch(argc, argv,
                  0, NULL,
                  0, NULL,
                  "swingwebview-launcher", "swingwebview-launcher",
                  "java", "java",
                  0, 1, 0, 0);
}
