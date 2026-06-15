#!/usr/bin/env bash
#
# Reproduction script for issue #36: "SwingWebView cannot run on macOS"
# (SIGSEGV in objc_msgSend right after the WKWebView is added as a subview).
#
# Hypothesis being tested
# -----------------------
# The crash is an Objective-C struct-return ABI mismatch that only bites on
# Intel (x86_64) Macs.  In src_c/webview_embed.cpp, cocoa_set_bounds() does:
#
#       CGRect b = msg<CGRect>(e->host_view, sel("bounds"));   // line ~3888
#
# msg<>() always dispatches through plain objc_msgSend().  On x86_64 the SysV
# ABI returns a 32-byte struct (CGRect) via a hidden pointer in the first
# integer register, so a CGRect-returning selector MUST be dispatched through
# objc_msgSend_stret().  Using plain objc_msgSend() shifts self/cmd by one
# register; the dispatcher then reads the stack return-buffer pointer as the
# receiver and dereferences its isa -> SIGSEGV at a bogus/zero address.
#
# On Apple Silicon (arm64) there is no objc_msgSend_stret and large struct
# returns use the separate x8 register, so the very same code is correct --
# which is why the library works on arm64 Macs but crashes on Intel.
#
# This crash path is only reached when host_is_awt == false, i.e. when the
# native code attaches the WKWebView to NSWindow.contentView instead of a
# per-Canvas AWT NSView.  The JetBrains Runtime (JBR) used by IntelliJ does
# not expose an AWT-named NSView, so it takes that fallback path -- matching
# the reporter's environment (Intel mac, macOS 15.7.7, JBR 25.0.3).
#
# What this script does
# ---------------------
#   Phase A  (fast, no JDK/Maven needed): compiles a ~40-line Objective-C
#            program that calls a CGRect-returning selector two ways --
#            through objc_msgSend (the buggy dispatch) and through the
#            architecture-correct dispatch -- and shows the first crashes on
#            Intel while the second succeeds.  This isolates the root cause.
#
#   Phase B  (end-to-end): downloads ca.weblite:webview:1.0.7, writes the
#            exact demo from the issue, and runs it under JBR 25 to reproduce
#            the real crash (exit 134 / SIGABRT with an hs_err log naming
#            cocoa_set_bounds).
#
# Usage
# -----
#   ./repro-issue-36-macos-intel.sh            # run both phases
#   ./repro-issue-36-macos-intel.sh --phase-a  # ABI micro-repro only
#   ./repro-issue-36-macos-intel.sh --phase-b  # end-to-end only
#
# Environment overrides
#   JBR_HOME=/path/to/jbr/Contents/Home   use a specific JBR (skips autodetect)
#   JBR_URL=https://.../jbr-...-osx-x64-....tar.gz   download URL fallback
#   WEBVIEW_VERSION=1.0.7                  library version to test (default 1.0.7)
#   STOCK_JAVA=/path/to/stock/jdk/Home    optional: also run under a non-JBR JDK
#                                         to show it does NOT crash there
#
set -uo pipefail

# --------------------------------------------------------------------------
# Pretty output helpers
# --------------------------------------------------------------------------
bold=$(printf '\033[1m'); red=$(printf '\033[31m'); grn=$(printf '\033[32m')
ylw=$(printf '\033[33m'); rst=$(printf '\033[0m')
say()  { printf '%s\n' "$*"; }
hdr()  { printf '\n%s== %s ==%s\n' "$bold" "$*" "$rst"; }
ok()   { printf '%s[ OK ]%s %s\n'   "$grn" "$rst" "$*"; }
bad()  { printf '%s[FAIL]%s %s\n'   "$red" "$rst" "$*"; }
warn() { printf '%s[warn]%s %s\n'   "$ylw" "$rst" "$*"; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/swv-issue36.XXXXXX")"
CACHE="${HOME}/.cache/swv-issue36"
mkdir -p "$CACHE"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

WEBVIEW_VERSION="${WEBVIEW_VERSION:-1.0.7}"

PHASE="all"
case "${1:-}" in
  --phase-a) PHASE="a" ;;
  --phase-b) PHASE="b" ;;
  -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
  "") ;;
  *) bad "unknown argument: $1"; exit 2 ;;
esac

# --------------------------------------------------------------------------
# Platform sanity checks
# --------------------------------------------------------------------------
hdr "Platform"
OS="$(uname -s)"
ARCH="$(uname -m)"
say "OS:   $OS"
say "Arch: $ARCH"
say "macOS version: $(sw_vers -productVersion 2>/dev/null || echo '?') ($(sw_vers -buildVersion 2>/dev/null || echo '?'))"

if [[ "$OS" != "Darwin" ]]; then
  bad "This reproduction is macOS-specific. Detected: $OS"
  exit 2
fi
if [[ "$ARCH" != "x86_64" && "$ARCH" != "i386" ]]; then
  warn "This is an Apple Silicon Mac ($ARCH). The bug does NOT crash here --"
  warn "arm64 has no objc_msgSend_stret and uses the x8 register for struct"
  warn "returns, so the same code is correct. Run this on an Intel Mac to"
  warn "reproduce. (Phase A is still compiled to demonstrate the difference.)"
fi

# ==========================================================================
# Phase A -- Objective-C struct-return ABI micro-repro
# ==========================================================================
phase_a() {
  hdr "Phase A: objc_msgSend vs objc_msgSend_stret (CGRect return)"

  if ! command -v clang >/dev/null 2>&1; then
    warn "clang not found (install Xcode command line tools: xcode-select --install)"
    warn "skipping Phase A"
    return
  fi

  cat > "$WORK/abi_repro.m" <<'OBJC'
// Mirrors src_c/webview_embed.cpp:3888 -- msg<CGRect>(view, "bounds").
// argv[1] == "bad"  -> dispatch a CGRect-returning selector through plain
//                      objc_msgSend (what msg<>() does today). Wrong on x86_64.
// argv[1] == "good" -> dispatch through the architecture-correct function.
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <objc/message.h>
#include <string.h>
#include <stdio.h>

typedef CGRect (*RectMsg)(id, SEL);

int main(int argc, char **argv) {
  const char *mode = argc > 1 ? argv[1] : "good";
  @autoreleasepool {
    id view = ((id (*)(id, SEL))objc_msgSend)(
        (id)objc_getClass("NSView"), sel_registerName("alloc"));
    view = ((id (*)(id, SEL, CGRect))objc_msgSend)(
        view, sel_registerName("initWithFrame:"), CGRectMake(0, 0, 640, 480));
    SEL boundsSel = sel_registerName("bounds");

    CGRect r;
    if (strcmp(mode, "bad") == 0) {
      // Buggy dispatch: identical to msg<CGRect>(...) in the library.
      RectMsg fn = (RectMsg)objc_msgSend;
      r = fn(view, boundsSel);                 // <-- crashes on x86_64
    } else {
#if defined(__x86_64__)
      RectMsg fn = (RectMsg)objc_msgSend_stret; // correct on Intel
#else
      RectMsg fn = (RectMsg)objc_msgSend;       // correct on arm64
#endif
      r = fn(view, boundsSel);
    }
    printf("bounds = (%g, %g, %g, %g)\n",
           r.origin.x, r.origin.y, r.size.width, r.size.height);
  }
  return 0;
}
OBJC

  if ! clang -fobjc-arc -framework Foundation -framework AppKit \
        -o "$WORK/abi_repro" "$WORK/abi_repro.m" 2>"$WORK/clang.log"; then
    bad "Phase A failed to compile:"; cat "$WORK/clang.log"; return
  fi

  say "Running the BAD dispatch (plain objc_msgSend for a CGRect return)..."
  "$WORK/abi_repro" bad >"$WORK/bad.out" 2>&1
  bad_code=$?
  if [[ $bad_code -ge 128 ]]; then
    sig=$((bad_code - 128))
    ok "BAD dispatch crashed with signal $sig (exit $bad_code) -- reproduces the bug"
    [[ -s "$WORK/bad.out" ]] && sed 's/^/      /' "$WORK/bad.out"
  else
    warn "BAD dispatch did NOT crash (exit $bad_code). Output:"
    sed 's/^/      /' "$WORK/bad.out"
    if [[ "$ARCH" != "x86_64" && "$ARCH" != "i386" ]]; then
      warn "Expected on arm64 -- the bug is Intel-only."
    fi
  fi

  say "Running the GOOD dispatch (architecture-correct)..."
  "$WORK/abi_repro" good >"$WORK/good.out" 2>&1
  good_code=$?
  if [[ $good_code -eq 0 ]]; then
    ok "GOOD dispatch succeeded: $(cat "$WORK/good.out")"
  else
    bad "GOOD dispatch failed unexpectedly (exit $good_code):"
    sed 's/^/      /' "$WORK/good.out"
  fi

  if [[ ( "$ARCH" == "x86_64" || "$ARCH" == "i386" ) && $bad_code -ge 128 && $good_code -eq 0 ]]; then
    ok "Phase A confirms the root cause: the bounds call must use objc_msgSend_stret on Intel."
  fi
}

# ==========================================================================
# Phase B -- end-to-end repro of the actual library crash under JBR 25
# ==========================================================================

# Locate a JetBrains Runtime (JBR), preferring version 25.
find_jbr() {
  if [[ -n "${JBR_HOME:-}" ]]; then
    [[ -x "$JBR_HOME/bin/java" ]] && { echo "$JBR_HOME"; return 0; }
    warn "JBR_HOME set but $JBR_HOME/bin/java is not executable" >&2
  fi
  # IntelliJ / Toolbox bundled JBRs and standalone JBR installs all expose a
  # .../jbr/Contents/Home (or .../jbr-<ver>/Contents/Home) directory.
  local search=(/Applications "$HOME/Applications"
    "$HOME/Library/Application Support/JetBrains/Toolbox/apps"
    "$CACHE")
  local best="" home v
  while IFS= read -r home; do
    [[ -x "$home/bin/java" ]] || continue
    v="$("$home/bin/java" -version 2>&1 | head -1)"
    if printf '%s' "$v" | grep -q '"25'; then echo "$home"; return 0; fi
    [[ -z "$best" ]] && best="$home"
  done < <(/usr/bin/find "${search[@]}" -maxdepth 7 -type d -name Home \
             \( -path '*jbr*' -o -path '*JetBrainsRuntime*' \) 2>/dev/null)
  [[ -n "$best" ]] && { echo "$best"; return 0; }
  return 1
}

download_jbr() {
  local url="${JBR_URL:-https://cache-redirector.jetbrains.com/intellij-jbr/jbr-25.0.3-osx-x64-b480.61.tar.gz}"
  local tgz="$CACHE/$(basename "$url")"
  local dest="$CACHE/jbr-download"
  if [[ ! -d "$dest" ]]; then
    say "Downloading JBR 25 (osx-x64) from:" >&2
    say "  $url" >&2
    if ! curl -fL --retry 3 -o "$tgz" "$url"; then
      warn "JBR download failed. Set JBR_HOME to a local JBR 25, or JBR_URL to a" >&2
      warn "valid osx-x64 JBR 25 tarball from https://github.com/JetBrains/JetBrainsRuntime/releases" >&2
      return 1
    fi
    mkdir -p "$dest"
    tar -xzf "$tgz" -C "$dest" || { warn "extract failed" >&2; return 1; }
  fi
  /usr/bin/find "$dest" -maxdepth 4 -type d -name Home -path '*Contents*' 2>/dev/null | head -1
}

run_demo() {
  # $1 = JAVA_HOME, $2 = label, $3 = jar path
  local jhome="$1" label="$2" jar="$3"
  local cls="$WORK/classes-$label"
  mkdir -p "$cls"
  local errfile="$WORK/hs_err_${label}.log"

  say "Compiling demo with $label javac..."
  if ! "$jhome/bin/javac" -cp "$jar" -d "$cls" "$WORK/Main.java" 2>"$WORK/javac-$label.log"; then
    bad "compile failed under $label:"; sed 's/^/      /' "$WORK/javac-$label.log"
    return 2
  fi

  say "Launching demo under $label ($("$jhome/bin/java" -version 2>&1 | head -1))..."
  rm -f "$errfile"
  # The native [webview-embed] logs go to stderr; capture everything.
  "$jhome/bin/java" \
      -XX:ErrorFile="$errfile" \
      -Djava.awt.headless=false \
      -cp "$jar:$cls" Main >"$WORK/run-$label.out" 2>&1 &
  local pid=$!

  # Manual timeout (macOS has no `timeout`): a crash exits within a second or
  # two; if it survives, it would stay open forever, so kill after 30s.
  ( sleep 30; kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null ) &
  local watcher=$!
  wait "$pid"; local code=$?
  kill "$watcher" 2>/dev/null; wait "$watcher" 2>/dev/null

  say "---- native / JVM output (under $label) ----"
  sed 's/^/      /' "$WORK/run-$label.out"
  say "--------------------------------------------"

  if [[ -f "$errfile" ]] || [[ $code -eq 134 || $code -eq 139 ]]; then
    bad "$label CRASHED (exit $code) -- reproduced."
    if [[ -f "$errfile" ]]; then
      local saved="$CACHE/hs_err_${label}_$(date +%s).log"
      cp "$errfile" "$saved"
      say "Saved crash log: $saved"
      say "Relevant native frames:"
      grep -nE "objc_msgSend|cocoa_set_bounds|cocoa_run_on_main_async|libwebview" "$errfile" \
        | head -8 | sed 's/^/      /'
    fi
    return 1
  elif [[ $code -eq 143 || $code -eq 130 ]]; then
    ok "$label survived 30s without crashing (window was open; killed by timeout)."
    return 0
  else
    warn "$label exited $code without an hs_err log. Output above may explain why"
    warn "(e.g. no display / headless). Re-run in a normal desktop session."
    return 0
  fi
}

phase_b() {
  hdr "Phase B: end-to-end demo under JBR 25 (webview $WEBVIEW_VERSION)"

  # 1. Fetch the library jar (bundles the native dylib; no other runtime deps).
  local jar="$CACHE/webview-$WEBVIEW_VERSION.jar"
  if [[ ! -f "$jar" ]]; then
    local url="https://repo1.maven.org/maven2/ca/weblite/webview/$WEBVIEW_VERSION/webview-$WEBVIEW_VERSION.jar"
    say "Downloading $url"
    if ! curl -fL --retry 3 -o "$jar" "$url"; then
      bad "Could not download webview-$WEBVIEW_VERSION.jar"; return 2
    fi
  fi
  ok "Library jar: $jar"

  # 2. Write the exact demo from the issue.
  cat > "$WORK/Main.java" <<'JAVA'
import ca.weblite.webview.swing.WebViewComponent;
import javax.swing.*;
import java.awt.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WebViewComponent wv = WebViewComponent.create();
            wv.setUrl("https://example.com");
            wv.setPreferredSize(new Dimension(900, 600));

            JFrame frame = new JFrame("WebView Demo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(wv, BorderLayout.CENTER);
            frame.pack();
            frame.setVisible(true);
        });
    }
}
JAVA

  # 3. Find or download JBR 25.
  local jbr; jbr="$(find_jbr || true)"
  if [[ -z "$jbr" ]]; then
    warn "No JBR found in IntelliJ/Toolbox locations; attempting download..."
    jbr="$(download_jbr || true)"
  fi
  if [[ -z "$jbr" ]]; then
    bad "Could not locate or download a JetBrains Runtime."
    bad "Set JBR_HOME=/path/to/jbr/Contents/Home and re-run."
    return 2
  fi
  ok "JBR: $jbr"

  run_demo "$jbr" "JBR" "$jar"; local jbr_result=$?

  # 4. Optional A/B: show a stock (non-JBR) JDK does NOT crash.
  if [[ -n "${STOCK_JAVA:-}" && -x "$STOCK_JAVA/bin/java" ]]; then
    hdr "Control run: stock JDK (expected NOT to crash)"
    run_demo "$STOCK_JAVA" "stock" "$jar"
  else
    say
    say "Tip: set STOCK_JAVA=/path/to/Temurin-or-Corretto-21/Home to also run the"
    say "control case that should NOT crash (proving the bug is JBR/contentView-specific)."
  fi

  return $jbr_result
}

# ==========================================================================
# Drive
# ==========================================================================
rc=0
[[ "$PHASE" == "all" || "$PHASE" == "a" ]] && phase_a
[[ "$PHASE" == "all" || "$PHASE" == "b" ]] && { phase_b || rc=$?; }

hdr "Summary"
say "Root-cause hypothesis: src_c/webview_embed.cpp cocoa_set_bounds() dispatches"
say "a CGRect-returning selector (-[NSView bounds]) through plain objc_msgSend."
say "On Intel (x86_64) that selector must use objc_msgSend_stret; the wrong"
say "dispatch crashes in objc_msgSend. The path is only reached on the"
say "NSWindow.contentView fallback that JBR triggers, so it is Intel + JBR only."
say
say "Crash logs (if any) saved under: $CACHE"
exit $rc
