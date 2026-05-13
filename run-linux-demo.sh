#!/bin/bash
# One-shot script: build the Linux native lib, build WebView.jar, compile
# and run the heavyweight Swing demo.  No Ant required.
#
# Usage:    ./run-linux-demo.sh
# Override: JAVA_HOME=/path/to/jdk ./run-linux-demo.sh
#
# Requires: g++, pkg-config, libgtk-3-dev, and either libwebkit2gtk-4.1-dev
# (Ubuntu 24.04+) or libwebkit2gtk-4.0-dev (older distros).
set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_DIR"

# ---------------------------------------------------------------------------
# 1. Resolve JAVA_HOME
# ---------------------------------------------------------------------------
if [ -z "$JAVA_HOME" ]; then
    if command -v javac >/dev/null 2>&1; then
        JAVAC_PATH="$(readlink -f "$(command -v javac)" 2>/dev/null || command -v javac)"
        JAVA_HOME="$(dirname "$(dirname "$JAVAC_PATH")")"
    fi
fi
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "JAVA_HOME is not set and could not be resolved automatically." >&2
    echo "Install a JDK and export JAVA_HOME, e.g. via mise / asdf:" >&2
    echo "  mise install   # picks up .tool-versions" >&2
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
# 2. Pick a WebKit pkg-config name
# ---------------------------------------------------------------------------
if pkg-config --exists webkit2gtk-4.1; then
    WEBKIT_PKG=webkit2gtk-4.1
elif pkg-config --exists webkit2gtk-4.0; then
    WEBKIT_PKG=webkit2gtk-4.0
else
    echo "Neither webkit2gtk-4.1 nor webkit2gtk-4.0 was found via pkg-config." >&2
    echo "Install one of the following with your package manager:" >&2
    echo "  Debian/Ubuntu 24.04+:  sudo apt install libgtk-3-dev libwebkit2gtk-4.1-dev libx11-dev" >&2
    echo "  Debian/Ubuntu older:   sudo apt install libgtk-3-dev libwebkit2gtk-4.0-dev libx11-dev" >&2
    echo "  Fedora:                sudo dnf install gtk3-devel webkit2gtk4.1-devel libX11-devel" >&2
    exit 1
fi
if ! pkg-config --exists gtk+-3.0; then
    echo "gtk+-3.0 (libgtk-3-dev) is required." >&2
    exit 1
fi
echo "Using WebKit package: $WEBKIT_PKG"

# ---------------------------------------------------------------------------
# 3. Build libwebview.so for the current arch.
#    The directory name has to match NativeLibraryUtil.Architecture lowercased
#    (linux_64 on x86_64, linux_arm64 on aarch64); the JVM os.arch decides
#    which folder the runtime extractor looks in.
# ---------------------------------------------------------------------------
case "$(uname -m)" in
    x86_64|amd64)   NATIVE_DIR=linux_64 ;;
    aarch64|arm64)  NATIVE_DIR=linux_arm64 ;;
    armv7l|armv7)   NATIVE_DIR=linux_arm ;;
    i?86)           NATIVE_DIR=linux_32 ;;
    *)              NATIVE_DIR=linux_64 ;;
esac
echo "Native dir: $NATIVE_DIR (arch: $(uname -m))"
mkdir -p "$REPO_DIR/src/$NATIVE_DIR"
SO="$REPO_DIR/src/$NATIVE_DIR/libwebview.so"

NEED_SO=0
if [ ! -f "$SO" ]; then
    NEED_SO=1
else
    for src in src_c/webview.c src_c/webview.h src_c/webview_embed.cpp; do
        if [ "$src" -nt "$SO" ]; then NEED_SO=1; break; fi
    done
fi

if [ "$NEED_SO" = "1" ]; then
    echo "Building libwebview.so ..."
    # libjawt is intentionally NOT linked at build time -- the Linux dynamic
    # linker's default behaviour for shared objects is to permit undefined
    # symbols, and webview_embed.cpp's resolve_jawt_get_awt() looks libjawt
    # up via dlopen at first call.  WebViewNative also does an explicit
    # System.loadLibrary("jawt") before loading this lib, so the symbol is
    # already in-process either way.
    g++ -fPIC -std=c++11 -Wall \
        -DWEBVIEW_GTK=1 \
        -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
        -I./src_c \
        $(pkg-config --cflags gtk+-3.0 "$WEBKIT_PKG") \
        src_c/webview.c src_c/webview_embed.cpp \
        $(pkg-config --libs gtk+-3.0 "$WEBKIT_PKG") \
        -lX11 -ldl \
        -shared -o "$SO"
    echo "Built $SO"
else
    echo "libwebview.so up to date."
fi

# ---------------------------------------------------------------------------
# 4. Compile WebView Java sources
# ---------------------------------------------------------------------------
BUILD_DIR="$REPO_DIR/build"
WV_CLASSES="$BUILD_DIR/classes-webview"
WV_JAR="$REPO_DIR/dist/WebView.jar"
mkdir -p "$WV_CLASSES" "$REPO_DIR/dist"
echo "Compiling WebView Java sources ..."
find src -name '*.java' > "$BUILD_DIR/wv-sources.txt"
"$JAVAC" -d "$WV_CLASSES" -classpath "lib/*" @"$BUILD_DIR/wv-sources.txt"

# ---------------------------------------------------------------------------
# 5. Build WebView.jar -- classes + <arch>/libwebview.so at jar root.
# ---------------------------------------------------------------------------
echo "Building WebView.jar ..."
STAGE="$BUILD_DIR/stage-webview"
rm -rf "$STAGE"
mkdir -p "$STAGE/$NATIVE_DIR"
cp -R "$WV_CLASSES"/. "$STAGE"/
cp "$SO" "$STAGE/$NATIVE_DIR/libwebview.so"
( cd "$STAGE" && "$JAR" cf "$WV_JAR" . )
echo "Built $WV_JAR"

# ---------------------------------------------------------------------------
# 6. Compile and run the heavyweight demo
# ---------------------------------------------------------------------------
DEMO_DIR="$REPO_DIR/demos/WebViewHeavyweightDemo"
DEMO_CLASSES="$BUILD_DIR/classes-demo"
mkdir -p "$DEMO_CLASSES"
echo "Compiling demo ..."
"$JAVAC" -d "$DEMO_CLASSES" -classpath "$WV_JAR" \
    "$DEMO_DIR/src/ca/weblite/webview/demos/WebViewHeavyweightDemo.java"

echo "Launching demo ..."
exec "$JAVA" \
    -cp "$DEMO_CLASSES:$WV_JAR" \
    ca.weblite.webview.demos.WebViewHeavyweightDemo
