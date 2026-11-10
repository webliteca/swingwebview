/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * How a print dialog ended (Canvas 38 D1).
 */
public enum PrintOutcome {
    /** The person confirmed: sent to a printer, saved as a PDF, or opened in a viewer. */
    PRINTED,
    /** The person closed the dialog without printing. */
    CANCELLED,
    /**
     * The dialog opened and the engine reports nothing more about it
     * (WebView2's print dialog, on Windows).
     */
    OPENED
}
