/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Translation between AWT input-event constants and GDK input constants.
 * Used by the lightweight ({@code WebViewLightweightComponent}) path to
 * feed AWT mouse/keyboard events into the offscreen WebKitGTK engine.
 */
public final class GdkInput {

    private GdkInput() {}

    // GDK modifier mask bits (see gdk/gdkkeysyms.h / gdktypes.h).
    public static final int GDK_SHIFT_MASK   = 1 << 0;   // 1
    public static final int GDK_LOCK_MASK    = 1 << 1;   // 2  (caps lock)
    public static final int GDK_CONTROL_MASK = 1 << 2;   // 4
    public static final int GDK_MOD1_MASK    = 1 << 3;   // 8  (alt)
    public static final int GDK_MOD2_MASK    = 1 << 4;   // 16
    public static final int GDK_BUTTON1_MASK = 1 << 8;   // 256
    public static final int GDK_BUTTON2_MASK = 1 << 9;   // 512
    public static final int GDK_BUTTON3_MASK = 1 << 10;  // 1024
    public static final int GDK_SUPER_MASK   = 1 << 26;
    public static final int GDK_META_MASK    = 1 << 28;

    /** Translate AWT modifiers (from getModifiersEx) into GDK bits. */
    public static int translateModifiers(int awtMods) {
        int g = 0;
        if ((awtMods & InputEvent.SHIFT_DOWN_MASK)   != 0) g |= GDK_SHIFT_MASK;
        if ((awtMods & InputEvent.CTRL_DOWN_MASK)    != 0) g |= GDK_CONTROL_MASK;
        if ((awtMods & InputEvent.ALT_DOWN_MASK)     != 0) g |= GDK_MOD1_MASK;
        if ((awtMods & InputEvent.META_DOWN_MASK)    != 0) g |= GDK_META_MASK;
        if ((awtMods & InputEvent.BUTTON1_DOWN_MASK) != 0) g |= GDK_BUTTON1_MASK;
        if ((awtMods & InputEvent.BUTTON2_DOWN_MASK) != 0) g |= GDK_BUTTON2_MASK;
        if ((awtMods & InputEvent.BUTTON3_DOWN_MASK) != 0) g |= GDK_BUTTON3_MASK;
        return g;
    }

    /** Translate AWT MouseEvent button number into the GDK 1/2/3. */
    public static int translateButton(int awtButton) {
        switch (awtButton) {
            case MouseEvent.BUTTON1: return 1;
            case MouseEvent.BUTTON2: return 2;
            case MouseEvent.BUTTON3: return 3;
            default: return awtButton;
        }
    }

    // ----- Keyboard ------------------------------------------------------
    //
    // Translation between AWT VK codes / chars and GDK keysyms.  Values
    // taken from gdk/gdkkeysyms.h.  Latin-1 printable characters use the
    // char value directly (GDK_KEY_a == 'a' == 0x61 etc.); only non-
    // character keys need an explicit table entry.

    /**
     * Map an AWT KeyEvent to a GDK keysym.  Returns 0 when the event has
     * neither a known special-key mapping nor a usable character.
     */
    public static int translateKeyCode(int vkCode, char keyChar) {
        int special = vkToGdkKeysym(vkCode);
        if (special != 0) return special;
        if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar != 0xFFFF
            && keyChar > 0) {
            // Latin-1 / Unicode-BMP printable -- the char value matches
            // the corresponding GDK_KEY_xxx for ASCII; for higher
            // codepoints WebKit's input pipeline can usually still
            // recover the character via gdk_keyval_to_unicode.
            return keyChar;
        }
        return 0;
    }

    /** True when the AWT VK code represents a modifier key. */
    public static boolean isModifierKey(int vkCode) {
        switch (vkCode) {
            case KeyEvent.VK_SHIFT:
            case KeyEvent.VK_CONTROL:
            case KeyEvent.VK_ALT:
            case KeyEvent.VK_ALT_GRAPH:
            case KeyEvent.VK_META:
            case KeyEvent.VK_CAPS_LOCK:
            case KeyEvent.VK_NUM_LOCK:
            case KeyEvent.VK_SCROLL_LOCK:
                return true;
            default:
                return false;
        }
    }

    private static int vkToGdkKeysym(int vk) {
        switch (vk) {
            case KeyEvent.VK_BACK_SPACE:  return 0xff08;
            case KeyEvent.VK_TAB:         return 0xff09;
            case KeyEvent.VK_ENTER:       return 0xff0d;
            case KeyEvent.VK_ESCAPE:      return 0xff1b;
            case KeyEvent.VK_DELETE:      return 0xffff;
            case KeyEvent.VK_INSERT:      return 0xff63;

            case KeyEvent.VK_HOME:        return 0xff50;
            case KeyEvent.VK_LEFT:        return 0xff51;
            case KeyEvent.VK_UP:          return 0xff52;
            case KeyEvent.VK_RIGHT:       return 0xff53;
            case KeyEvent.VK_DOWN:        return 0xff54;
            case KeyEvent.VK_PAGE_UP:     return 0xff55;
            case KeyEvent.VK_PAGE_DOWN:   return 0xff56;
            case KeyEvent.VK_END:         return 0xff57;

            case KeyEvent.VK_F1:          return 0xffbe;
            case KeyEvent.VK_F2:          return 0xffbf;
            case KeyEvent.VK_F3:          return 0xffc0;
            case KeyEvent.VK_F4:          return 0xffc1;
            case KeyEvent.VK_F5:          return 0xffc2;
            case KeyEvent.VK_F6:          return 0xffc3;
            case KeyEvent.VK_F7:          return 0xffc4;
            case KeyEvent.VK_F8:          return 0xffc5;
            case KeyEvent.VK_F9:          return 0xffc6;
            case KeyEvent.VK_F10:         return 0xffc7;
            case KeyEvent.VK_F11:         return 0xffc8;
            case KeyEvent.VK_F12:         return 0xffc9;

            case KeyEvent.VK_SHIFT:       return 0xffe1; // Shift_L
            case KeyEvent.VK_CONTROL:     return 0xffe3; // Control_L
            case KeyEvent.VK_CAPS_LOCK:   return 0xffe5; // Caps_Lock
            case KeyEvent.VK_ALT:         return 0xffe9; // Alt_L
            case KeyEvent.VK_ALT_GRAPH:   return 0xfe03; // ISO_Level3_Shift
            case KeyEvent.VK_META:        return 0xffeb; // Super_L
            case KeyEvent.VK_NUM_LOCK:    return 0xff7f;
            case KeyEvent.VK_SCROLL_LOCK: return 0xff14;

            // Common punctuation / special chars -- in most cases
            // KeyEvent.getKeyChar already gives the character we want,
            // so no entry is needed here.

            default: return 0;
        }
    }
}
