#!/bin/bash
# One-shot script: build the macOS native lib, build WebView.jar, compile
# and run the media permission demo (WebViewMediaPermissionDemo, Canvas 35).
#
# Serves a page from demo://app/ whose buttons ask for the camera and the
# microphone, in the heavyweight component (the supported macOS mode).  The
# application's WebViewPermissionHandler answers each request (by default
# after asking you in a Swing dialog).  Needs macOS 12+.
#
# Info.plist (Canvas 35 D13, D14): macOS terminates a process that opens the
# camera or microphone when its main bundle has no NSCameraUsageDescription /
# NSMicrophoneUsageDescription, and a plain `java` has neither.  So by default
# this script builds build/WebViewMediaPermissionDemo.app -- an Info.plist with
# both keys, and a tiny clang-built launcher (demos/WebViewMediaPermissionDemo/
# mac/launcher.c) that runs the JVM in its own process through the JDK's
# JLI_Launch -- and runs the demo from it.  --no-bundle runs a plain `java`
# instead: camera / microphone requests are then denied by the library, with a
# "swingwebview: ... access denied" line on stderr, rather than crashing.
# See demos/WebViewMediaPermissionDemo/README.md.
#
# Usage:    ./run-mac-media-permission-demo.sh [--no-bundle]
#           ./run-mac-media-permission-demo.sh --print-info-plist
# Override: JAVA_HOME=/path/to/jdk ./run-mac-media-permission-demo.sh
#
set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_DIR"

APP_NAME="WebViewMediaPermissionDemo"
BUNDLE_ID="ca.weblite.webview.demos.mediapermission"

usage() {
    echo "Usage: $0 [--no-bundle] | --print-info-plist" >&2
    echo "  (default)           run the demo from build/$APP_NAME.app, whose Info.plist" >&2
    echo "                      declares the camera and microphone usage descriptions" >&2
    echo "  --no-bundle         run a plain java (no usage descriptions): camera and" >&2
    echo "                      microphone are denied with a clear message, no crash" >&2
    echo "  --print-info-plist  print the bundle's Info.plist and exit (any OS)" >&2
}

# The bundle's Info.plist (Canvas 35 D14), on stdout.
info_plist() {
    cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key>
    <string>$BUNDLE_ID</string>
    <key>CFBundleName</key>
    <string>$APP_NAME</string>
    <key>CFBundleExecutable</key>
    <string>$APP_NAME</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>LSMinimumSystemVersion</key>
    <string>12.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSCameraUsageDescription</key>
    <string>The media permission demo shows your camera in a web page.</string>
    <key>NSMicrophoneUsageDescription</key>
    <string>The media permission demo records from your microphone in a web page.</string>
</dict>
</plist>
PLIST
}

USE_BUNDLE=1
for arg in "$@"; do
    case "$arg" in
        --no-bundle)        USE_BUNDLE=0 ;;
        --print-info-plist) info_plist; exit 0 ;;
        -h|--help)          usage; exit 0 ;;
        *)                  echo "Unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

if [ "$(uname -s)" != "Darwin" ]; then
    echo "This script runs the demo on macOS only (this is $(uname -s))." >&2
    echo "On Linux use ./run-linux-media-permission-demo.sh." >&2
    exit 1
fi

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
# 5. Compile and run the media permission demo
# ---------------------------------------------------------------------------
DEMO_DIR="$REPO_DIR/demos/WebViewMediaPermissionDemo"
DEMO_CLASSES="$BUILD_DIR/classes-media-permission-demo"
mkdir -p "$DEMO_CLASSES"
echo "Compiling demo ..."
"$JAVAC" -d "$DEMO_CLASSES" \
    -classpath "$WV_JAR" \
    "$DEMO_DIR/src/ca/weblite/webview/demos/WebViewMediaPermissionDemo.java"

if [ "$USE_BUNDLE" = "0" ]; then
    echo "Launching WebViewMediaPermissionDemo with a plain java (--no-bundle) ..."
    echo "  No Info.plist usage descriptions: camera / microphone will be denied."
    exec "$JAVA" \
        -cp "$DEMO_CLASSES:$WV_JAR" \
        ca.weblite.webview.demos.WebViewMediaPermissionDemo
fi

# ---------------------------------------------------------------------------
# 6. Build build/WebViewMediaPermissionDemo.app (Canvas 35 D14)
# ---------------------------------------------------------------------------
LAUNCHER_SRC="$DEMO_DIR/mac/launcher.c"
APP="$BUILD_DIR/$APP_NAME.app"
APP_EXE="$APP/Contents/MacOS/$APP_NAME"
if ! command -v clang >/dev/null 2>&1; then
    echo "clang not found: the demo bundle's launcher is built with it." >&2
    echo "Install the Xcode command line tools:  xcode-select --install" >&2
    echo "or run without the bundle:  $0 --no-bundle" >&2
    exit 1
fi
if [ ! -f "$LAUNCHER_SRC" ]; then
    echo "Missing launcher source: $LAUNCHER_SRC" >&2; exit 1
fi
JLI=""
for rel in lib/libjli.dylib lib/jli/libjli.dylib jre/lib/jli/libjli.dylib; do
    if [ -f "$JAVA_HOME/$rel" ]; then JLI="$JAVA_HOME/$rel"; break; fi
done
if [ -z "$JLI" ]; then
    echo "No libjli.dylib under JAVA_HOME=$JAVA_HOME -- is it a JDK?" >&2
    echo "Point JAVA_HOME at a full JDK, or run:  $0 --no-bundle" >&2
    exit 1
fi

NEED_APP=0
if [ ! -x "$APP_EXE" ] || [ ! -f "$APP/Contents/Info.plist" ]; then
    NEED_APP=1
else
    for src in "$LAUNCHER_SRC" "$0"; do
        if [ "$src" -nt "$APP_EXE" ]; then NEED_APP=1; break; fi
    done
fi
if [ "$NEED_APP" = "1" ]; then
    echo "Building $APP ..."
    rm -rf "$APP"
    mkdir -p "$APP/Contents/MacOS"
    info_plist > "$APP/Contents/Info.plist"
    if command -v plutil >/dev/null 2>&1; then
        plutil -lint "$APP/Contents/Info.plist" >/dev/null
    fi
    # The same Info.plist goes into the executable's __TEXT,__info_plist, so
    # the keys are there even for the bare executable.
    clang -O2 -Wall -o "$APP_EXE" "$LAUNCHER_SRC" \
        -sectcreate __TEXT __info_plist "$APP/Contents/Info.plist"
    if command -v codesign >/dev/null 2>&1; then
        codesign --force --sign - "$APP" >/dev/null 2>&1 \
            || echo "warning: ad-hoc codesign of $APP failed; continuing" >&2
    fi
    echo "Built $APP"
else
    echo "$APP up to date."
fi

# exec the launcher itself (not `open`), so this terminal keeps stdout/stderr
# and the environment; JAVA_HOME tells it which JDK's libjli to load.
echo "Launching WebViewMediaPermissionDemo from $APP ..."
exec "$APP_EXE" \
    -cp "$DEMO_CLASSES:$WV_JAR" \
    ca.weblite.webview.demos.WebViewMediaPermissionDemo
