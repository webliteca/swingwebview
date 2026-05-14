/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

import java.awt.event.InputEvent;
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
}
