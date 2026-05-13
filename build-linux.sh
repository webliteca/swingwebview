#!/bin/bash
set -e
# Prefer webkit2gtk-4.1 (Ubuntu 24.04+) and fall back to 4.0 (older distros).
if pkg-config --exists webkit2gtk-4.1; then
    WEBKIT_PKG=webkit2gtk-4.1
else
    WEBKIT_PKG=webkit2gtk-4.0
fi
# Link against JAWT for the Swing embedding bridge.
JAWT_LIB="-L${JAVA_HOME}/lib -ljawt"
g++ -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux -fPIC -std=c++11 -Wall -Wextra -pedantic -I./src_c -DWEBVIEW_GTK=1 \
    `pkg-config --cflags gtk+-3.0 $WEBKIT_PKG` \
    src_c/webview.c src_c/webview_embed.cpp \
    $LDFLAGS \
    `pkg-config --libs gtk+-3.0 $WEBKIT_PKG` \
    $JAWT_LIB -lX11 \
    -shared -o libwebview.so
mv libwebview.so src/linux_64/
ant jar -Dplatforms.JDK_1.8.home=$JAVA_HOME