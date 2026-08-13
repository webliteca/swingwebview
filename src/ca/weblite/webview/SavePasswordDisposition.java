/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * The decision a {@link WebViewSavePasswordHandler} returns for a
 * captured login submission.
 */
public enum SavePasswordDisposition {
    /** Store the captured credential in the active credential store. */
    SAVE,
    /** Discard the captured credential; store nothing. */
    DONT_SAVE
}
