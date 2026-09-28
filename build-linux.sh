#!/bin/bash
set -e
# Prefer webkit2gtk-4.1 (Ubuntu 24.04+) and fall back to 4.0 (older distros).
if pkg-config --exists webkit2gtk-4.1; then
    WEBKIT_PKG=webkit2gtk-4.1
else
    WEBKIT_PKG=webkit2gtk-4.0
fi
# JAWT is deliberately NOT linked (Canvas 8, Op 8). The JAWT_* symbols resolve
# at load time from the already-loaded JVM, against the libjawt WebViewNative
# pre-loads before this library. Linking -ljawt would make the build depend on a
# standalone libjawt that some JDKs do not ship -- the very case the loader is
# written to survive. This mirrors the Linux `native` job in
# .github/workflows/build.yml, which produces the artifact that ships.
g++ -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/linux" -fPIC -std=c++11 -Wall -Wextra -pedantic -I./src_c -DWEBVIEW_GTK=1 \
    `pkg-config --cflags gtk+-3.0 $WEBKIT_PKG` \
    src_c/webview.c src_c/webview_embed.cpp src_c/webkit_loader.cpp \
    $LDFLAGS \
    `pkg-config --libs gtk+-3.0` \
    -lX11 -ldl \
    -shared -o libwebview.so
# NOTE: WebKitGTK/JavaScriptCore are intentionally NOT linked (only --cflags
# for the headers). They are dlopen'd at runtime (4.1 preferred, 4.0 fallback)
# by src_c/webkit_loader.cpp, so this single libwebview.so runs on both.
# The password-manager credential store (Canvas 27) likewise dlopen's
# libsecret-1.so.0 at first use rather than linking -lsecret -- matching the
# same runtime-load convention. Absence of a Secret Service provider degrades
# gracefully (the store becomes a no-op); it never blocks library load.
mkdir -p natives/linux_64
mv libwebview.so natives/linux_64/
mvn -DskipTests package