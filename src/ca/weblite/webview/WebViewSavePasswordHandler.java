/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.awt.Window;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Policy that decides whether a captured login submission should be
 * saved.  Install one via
 * {@link ca.weblite.webview.swing.WebViewComponent#setSavePasswordHandler}.
 *
 * <p>{@link #onLoginSubmitted} is invoked on the Swing Event Dispatch
 * Thread.  The {@link #DEFAULT} handler shows a browser-style
 * "Save password?" confirm dialog modal to the host window; a custom
 * handler may decide programmatically (return a disposition without
 * showing UI) — this is what unit tests and headless environments use.
 *
 * <p>The method should return promptly and MUST NOT block on
 * {@code evalAsync(js).get()} or any other EDT-scheduled task — the EDT
 * is busy running the handler.  Exceptions thrown by the handler are
 * caught by {@link PasswordDispatcher} and forwarded to
 * {@link Thread#getDefaultUncaughtExceptionHandler()}; nothing is stored
 * and the WebView stays responsive.
 *
 * <p>This iteration wires the capture on macOS; Linux and Windows
 * coverage arrive in the follow-up canvases.
 */
@FunctionalInterface
public interface WebViewSavePasswordHandler {

    /**
     * Decide whether {@code event}'s captured credential should be saved.
     * Invoked on the EDT.
     */
    SavePasswordDisposition onLoginSubmitted(WebViewSavePasswordEvent event);

    /**
     * The default handler: a Swing "Save password?" confirm dialog,
     * modal to the host window, showing the origin and username (never
     * the password).  OK returns {@link SavePasswordDisposition#SAVE};
     * Cancel/close returns {@link SavePasswordDisposition#DONT_SAVE}.
     */
    WebViewSavePasswordHandler DEFAULT = new WebViewSavePasswordHandler() {
        @Override
        public SavePasswordDisposition onLoginSubmitted(
                WebViewSavePasswordEvent event) {
            Window host = SwingUtilities.getWindowAncestor(event.source());
            String msg = "Save the password for " + event.origin() + "?\n\n"
                + "Username: " + event.username();
            int r = JOptionPane.showConfirmDialog(host, msg, "Save password?",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            return r == JOptionPane.OK_OPTION
                ? SavePasswordDisposition.SAVE
                : SavePasswordDisposition.DONT_SAVE;
        }
    };
}
