/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A device a page asks to capture from (Canvas 35 D2).  Each kind has a
 * bit in the native wire contract; never change the bits.
 */
public enum MediaCaptureKind {
    /** A camera: {@code getUserMedia({video: ...})}. */
    CAMERA(1),
    /** A microphone: {@code getUserMedia({audio: ...})}. */
    MICROPHONE(2),
    /** A screen, window or tab capture: {@code getDisplayMedia(...)}. */
    DISPLAY(4);

    private final int bit;

    MediaCaptureKind(int bit) {
        this.bit = bit;
    }

    /** @return this kind's bit in the native wire contract. */
    public int bit() {
        return bit;
    }

    /**
     * The kinds whose bits are set in {@code bits}; unknown bits are
     * ignored.
     *
     * @return an unmodifiable set, possibly empty
     */
    public static Set<MediaCaptureKind> fromBits(int bits) {
        EnumSet<MediaCaptureKind> out = EnumSet.noneOf(MediaCaptureKind.class);
        for (MediaCaptureKind k : values()) {
            if ((bits & k.bit) != 0) out.add(k);
        }
        return Collections.unmodifiableSet(out);
    }
}
