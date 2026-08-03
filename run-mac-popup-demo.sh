#!/bin/bash
# One-shot script: build the macOS native lib, build WebView.jar, compile
# and run the browser-initiated popup demo (WebViewPopupDemo).
#
# Exercises window.open(url, name, features) and <a target="_blank"> across
# three handler modes (Default = allow, Custom = allow + log
# popupOpened/popupClosed, Block = setPopupHandler(null) => window.open
# returns null).  See demos/WebViewPopupDemo/README.md for the AC-mapped
# manual test checklist (allow, opener linkage, notifications, requested
# size, block, window.close()).
#
# The popup feature adds a NEW native symbol (cocoa_set_popup_callback plus
# the createWebViewWithConfiguration: / webViewDidClose: selectors), so the
# dylib MUST be recompiled on this Mac -- a stale prebuilt lib will not have
# it.  This script rebuilds libwebview.dylib whenever src_c/* is newer.
#
# Usage:    ./run-mac-popup-demo.sh
# Override: JAVA_HOME=/path/to/jdk ./run-mac-popup-demo.sh
#
set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_DIR"

# ---------------------------------------------------------------------------
# 1. Resolve JAVA_HOME
# ---------------------------------------------------------------------------
if [ -z "$JAVA_HOME" ]; then
    if [ -x /usr/libexec/java_home ]; then
        JAVA_HOME="$(/usr/libexec/java_home)"
    fi
fi
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "JAVA_HOME is not set and could not be resolved automatically." >&2
    echo "Install a JDK and export JAVA_HOME, e.g.:" >&2
    echo "  brew install --cask temurin" >&2
    echo "  export JAVA_HOME=\"\$(/usr/libexec/java_home)\"" >&2
    exit 1
fi
export JAVA_HOME
echo "Using JAVA_HOME=$JAVA_HOME"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
JAR="$JAVA_HOME/bin/jar"
for tool in "$JAVAC" "$JAVA" "$JAR"; do
    if [ ! -x "$tool" ]; then
        echo "Missing tool: $tool" >&2; exit 1
    fi
done

# ---------------------------------------------------------------------------
# 2. Build libwebview.dylib if missing or source is newer
# ---------------------------------------------------------------------------
case "$(uname -m)" in
    arm64|aarch64) NATIVE_DIR=osx_arm64 ;;
    x86_64)        NATIVE_DIR=osx_64 ;;
    *)             NATIVE_DIR=osx_64 ;;
esac
echo "Native dir: $NATIVE_DIR"
mkdir -p "$REPO_DIR/src/$NATIVE_DIR"
DYLIB="$REPO_DIR/src/$NATIVE_DIR/libwebview.dylib"
NEED_DYLIB=0
if [ ! -f "$DYLIB" ]; then
    NEED_DYLIB=1
else
    for src in src_c/webview.c src_c/webview.h src_c/webview_embed.cpp; do
        if [ "$src" -nt "$DYLIB" ]; then NEED_DYLIB=1; break; fi
    done
fi

if [ "$NEED_DYLIB" = "1" ]; then
    echo "Building libwebview.dylib (arch: $(uname -m)) ..."
    c++ \
        -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
        -dynamiclib \
        src_c/webview_embed.cpp \
        -o "$DYLIB" \
        -DWEBVIEW_COCOA=1 \
        -std=c++11 \
        -framework WebKit -framework Cocoa -framework QuartzCore \
        -Wl,-undefined,dynamic_lookup
    echo "Built $DYLIB"
else
    echo "libwebview.dylib up to date."
fi

# ---------------------------------------------------------------------------
# 3. Compile WebView Java sources
# ---------------------------------------------------------------------------
BUILD_DIR="$REPO_DIR/build"
WV_CLASSES="$BUILD_DIR/classes-webview"
WV_JAR="$REPO_DIR/dist/WebView.jar"
mkdir -p "$WV_CLASSES" "$REPO_DIR/dist"
echo "Compiling WebView Java sources ..."
find src -name '*.java' > "$BUILD_DIR/wv-sources.txt"
"$JAVAC" -d "$WV_CLASSES" \
    -classpath "lib/*" @"$BUILD_DIR/wv-sources.txt"

# ---------------------------------------------------------------------------
# 4. Build WebView.jar -- classes + osx_*/libwebview.dylib at the jar root
# ---------------------------------------------------------------------------
echo "Building WebView.jar ..."
STAGE="$BUILD_DIR/stage-webview"
rm -rf "$STAGE"
mkdir -p "$STAGE/$NATIVE_DIR"
cp -R "$WV_CLASSES"/. "$STAGE"/
cp "$DYLIB" "$STAGE/$NATIVE_DIR/libwebview.dylib"
( cd "$STAGE" && "$JAR" cf "$WV_JAR" . )
echo "Built $WV_JAR"

# ---------------------------------------------------------------------------
# 5. Compile and run the popup demo
# ---------------------------------------------------------------------------
DEMO_DIR="$REPO_DIR/demos/WebViewPopupDemo"
DEMO_CLASSES="$BUILD_DIR/classes-popup-demo"
mkdir -p "$DEMO_CLASSES"
echo "Compiling demo ..."
"$JAVAC" -d "$DEMO_CLASSES" \
    -classpath "$WV_JAR" \
    "$DEMO_DIR/src/ca/weblite/webview/demos/WebViewPopupDemo.java"

echo "Launching WebViewPopupDemo ..."
exec "$JAVA" \
    -cp "$DEMO_CLASSES:$WV_JAR" \
    ca.weblite.webview.demos.WebViewPopupDemo
