#!/bin/bash
set -e
# Note: on macOS, JAWT lives next to libjli inside the JDK; -ljawt links it.
c++ -I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin -dynamiclib \
    src_c/webview.c src_c/webview_embed.cpp \
    -o libwebview.dylib \
    -DWEBVIEW_COCOA=1 -DOBJC_OLD_DISPATCH_PROTOTYPES=1 -std=c++11 \
    -framework WebKit -framework Cocoa -framework QuartzCore \
    -L${JAVA_HOME}/lib -ljawt
mv libwebview.dylib src/osx_64/