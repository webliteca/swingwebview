/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * The decision a {@link WebViewFillPasswordHandler} returns when the
 * password manager is about to autofill a stored credential.
 */
public enum FillPasswordDisposition {
    /** Inject the stored credential into the detected login fields. */
    FILL,
    /** Suppress the autofill; leave the page's fields untouched. */
    DONT_FILL
}
