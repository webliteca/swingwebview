#!/bin/bash
set -e
# Build the macOS native library for the HOST architecture (Canvas 8, Op 8).
#
# Note: JAWT is deliberately NOT linked. The JAWT_* symbols are left undefined
# at link time (-Wl,-undefined,dynamic_lookup) and resolve at load time against
# the libjawt that WebViewNative pre-loads before this library (the two-phase
# preload). Linking -ljawt would make the build depend on a standalone libjawt
# that some JDKs do not ship -- exactly the case the loader is written to
# survive -- and fails with "ld: library 'jawt' not found" on a stock JDK.
# This mirrors the macOS `native` job in .github/workflows/build.yml, which
# produces the artifact that actually ships.
case "$(uname -m)" in
    arm64|aarch64) NATIVE_DIR=osx_arm64 ;;
    x86_64)        NATIVE_DIR=osx_64 ;;
    *)             NATIVE_DIR=osx_64 ;;
esac
echo "Building for host arch $(uname -m) -> natives/$NATIVE_DIR"
mkdir -p "natives/$NATIVE_DIR"
c++ -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/darwin" -dynamiclib \
    src_c/webview_embed.cpp \
    -o "natives/$NATIVE_DIR/libwebview.dylib" \
    -DWEBVIEW_COCOA=1 -DOBJC_OLD_DISPATCH_PROTOTYPES=1 -std=c++11 \
    -framework WebKit -framework Cocoa -framework QuartzCore -framework Security \
    -Wl,-undefined,dynamic_lookup
