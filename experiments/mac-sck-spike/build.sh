#!/usr/bin/env bash
# Build the ScreenCaptureKit spike binary.
#
# Requires:
#   - macOS 12.3 or newer (SCStream + initWithDesktopIndependentWindow)
#   - Xcode Command Line Tools (`xcode-select --install`)
#
# Output: experiments/mac-sck-spike/sck_spike
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/sck_spike"
SRC="$HERE/sck_spike.mm"

clang++ -std=c++17 -fobjc-arc -O2 -Wall -Wextra -Wno-unused-parameter \
    -mmacosx-version-min=12.3 \
    -framework Cocoa \
    -framework WebKit \
    -framework ScreenCaptureKit \
    -framework CoreMedia \
    -framework CoreVideo \
    -framework CoreGraphics \
    -framework ImageIO \
    -framework UniformTypeIdentifiers \
    -o "$OUT" "$SRC"

echo "Built: $OUT"
echo
echo "Usage:"
echo "  $OUT                          # uses https://example.com"
echo "  $OUT https://your.url/here    # custom URL"
echo
echo "First run will trigger the macOS Screen Recording permission"
echo "prompt; approve it for the 'sck_spike' binary, then re-run."
