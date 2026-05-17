/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Editing commands that can be dispatched into an embedded WebView via
 * {@link EmbeddedWebView#executeEditingCommand(EditingCommand)}.
 *
 * <p>Each value carries a stable integer ID that is part of the JNI ABI
 * between Java and the native {@code webview_embed_execute_editing_command}
 * entry point.  The IDs are matched by the {@code switch} in the per-platform
 * native bodies (Cocoa, GTK, WebView2).  Renumbering or reusing an existing
 * ID is a breaking change across the native + Java boundary; new commands
 * MUST be appended with a fresh positive integer.
 */
public enum EditingCommand {

    CUT(1),
    COPY(2),
    PASTE(3),
    SELECT_ALL(4);

    private final int nativeId;

    EditingCommand(int nativeId) {
        this.nativeId = nativeId;
    }

    public int getNativeId() {
        return nativeId;
    }
}
