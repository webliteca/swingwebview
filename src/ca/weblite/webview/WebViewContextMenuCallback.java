/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * JNI-facing bridge from a native engine's context menu to the
 * per-component {@link ContextMenuDispatcher} (Canvas 36 D6).  Internal:
 * public only so the natives and the Swing components can reach it; not
 * part of the supported API.
 *
 * <p>{@link #onContextMenuRequested} is called synchronously on the
 * engine's UI thread just before the engine opens its menu;
 * {@link #onContextMenuItemSelected} is called on that thread when the user
 * chooses an item the application added.
 */
public interface WebViewContextMenuCallback {

    /**
     * The engine is about to open its menu.
     *
     * @param flags         {@link ContextMenuDispatcher} {@code FLAG_*} bits
     * @param linkUrl       the link under the pointer, or null / empty
     * @param imageUrl      the image under the pointer, or null / empty
     * @param mediaUrl      the audio / video under the pointer, or null / empty
     * @param selectionText the selected text when the engine knows it, else null
     * @param x             the click x in view coordinates, or -1
     * @param y             the click y in view coordinates, or -1
     * @return null, or the token in decimal followed by one element per item:
     *         {@code '1'} (enabled) or {@code '0'} (disabled) and the label
     */
    String[] onContextMenuRequested(int flags, String linkUrl, String imageUrl,
                                    String mediaUrl, String selectionText,
                                    int x, int y);

    /**
     * The user chose item {@code index} of the menu identified by
     * {@code token}.
     */
    void onContextMenuItemSelected(long token, int index);
}
