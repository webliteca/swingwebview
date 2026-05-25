#!/bin/bash
# One-shot script: build the Linux native lib, build WebView.jar, compile
# and run the browser-initiated UI dialogs demo (WebViewDialogDemo).
#
# Exercises window.alert / window.confirm / window.prompt and
# <input type="file"> across three handler modes (Default Swing dialogs,
# Custom programmatic answers, Drop / null = suppress).  See
# demos/WebViewDialogDemo/README.md for the AC-mapped manual test
# checklist.
#
# Usage:    ./run-linux-dialog-demo.sh             # lightweight (default)
#           ./run-linux-dialog-demo.sh heavyweight # force heavyweight mode
#           ./run-linux-dialog-demo.sh lightweight # force lightweight mode
#           WEBVIEW_MODE=heavyweight ./run-linux-dialog-demo.sh
#
# STORY-004-002 requires AC verification in BOTH modes; run the script
# twice (once per mode) to cover the full acceptance matrix.
#
# Override JDK:  JAVA_HOME=/path/to/jdk ./run-linux-dialog-demo.sh
#
# Required packages:
#   - g++, pkg-config
#   - libgtk-3-dev
#   - libwebkit2gtk-4.1-dev (Ubuntu 24.04+) or libwebkit2gtk-4.0-dev (older)
#   - libx11-dev
#   - libxt-dev   <-- pulled in because JDK 8's jawt_md.h includes X11/Intrinsic.h
set -e
set -o pipefail

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_DIR"

# ---------------------------------------------------------------------------
# 0. Mode selection
# ---------------------------------------------------------------------------
MODE="${1:-${WEBVIEW_MODE:-lightweight}}"
case "$MODE" in
    heavyweight|lightweight) ;;
    *)
        echo "Unknown mode: $MODE" >&2
        echo "Usage: $0 [heavyweight|lightweight]" >&2
        exit 1
        ;;
esac
echo "Mode: $MODE"

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
    echo "  Debian/Ubuntu 24.04+:  sudo apt install libgtk-3-dev libwebkit2gtk-4.1-dev libx11-dev libxt-dev" >&2
    echo "  Debian/Ubuntu older:   sudo apt install libgtk-3-dev libwebkit2gtk-4.0-dev libx11-dev libxt-dev" >&2
    echo "  Fedora:                sudo dnf install gtk3-devel webkit2gtk4.1-devel libX11-devel libXt-devel" >&2
    exit 1
fi
if ! pkg-config --exists gtk+-3.0; then
    echo "gtk+-3.0 (libgtk-3-dev) is required." >&2
    exit 1
fi
echo "Using WebKit package: $WEBKIT_PKG"

if ! printf '#include <X11/Intrinsic.h>\nint main(){return 0;}\n' | \
        g++ -E -x c++ - >/dev/null 2>&1; then
    echo "" >&2
    echo "ERROR: <X11/Intrinsic.h> not found." >&2
    echo "" >&2
    echo "JDK 8 on Linux pulls X11 Intrinsics in via jawt_md.h.  Install:" >&2
    echo "  Debian/Ubuntu:  sudo apt install libxt-dev" >&2
    echo "  Fedora:         sudo dnf install libXt-devel" >&2
    echo "  Arch:           sudo pacman -S libxt" >&2
    echo "" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 3. Build libwebview.so for the current arch
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
# 5. Build WebView.jar -- classes + linux_*/libwebview.so at jar root
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
# 6. Compile and run the dialog demo
# ---------------------------------------------------------------------------
DEMO_DIR="$REPO_DIR/demos/WebViewDialogDemo"
DEMO_CLASSES="$BUILD_DIR/classes-dialog-demo"
mkdir -p "$DEMO_CLASSES"
echo "Compiling demo ..."
"$JAVAC" -d "$DEMO_CLASSES" -classpath "$WV_JAR" \
    "$DEMO_DIR/src/ca/weblite/webview/demos/WebViewDialogDemo.java"

echo "Launching WebViewDialogDemo (mode=$MODE) ..."
# Force the X11 GDK backend; embedding via XReparentWindow needs a real X11
# GdkDisplay on both sides.
export GDK_BACKEND=x11
export WEBKIT_DISABLE_DMABUF_RENDERER=1
export WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1
export WEBKIT_FORCE_SANDBOX=0

exec "$JAVA" \
    "-Dca.weblite.webview.mode=$MODE" \
    -cp "$DEMO_CLASSES:$WV_JAR" \
    ca.weblite.webview.demos.WebViewDialogDemo
