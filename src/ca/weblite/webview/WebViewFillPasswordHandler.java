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
 * Policy that decides whether the password manager should autofill a
 * stored credential on page load.  Install one via
 * {@link ca.weblite.webview.swing.WebViewComponent#setFillPasswordHandler}.
 *
 * <p>{@link #onAutofillRequested} is invoked on the Swing Event Dispatch
 * Thread, after an origin-matched credential has been found (the store
 * read already happened off the EDT).  It is consulted <strong>only</strong>
 * on the automatic page-load autofill path — the programmatic read API
 * ({@code getCredential} / {@code getCredentials} / {@code getAllCredentials})
 * is trusted host code and is never gated by this handler.
 *
 * <p>The {@link #DEFAULT} handler returns {@link FillPasswordDisposition#FILL}
 * unconditionally, preserving the library's automatic-autofill behaviour;
 * this is what a host gets if it installs nothing.  The {@link #CONFIRM}
 * handler shows a browser-style confirmation before filling.  A host that
 * needs an OS biometric / re-authentication gate (Touch ID, Windows Hello)
 * installs its own handler that performs the check and returns
 * {@link FillPasswordDisposition#DONT_FILL} to decline.
 *
 * <p>The method should return promptly and MUST NOT block on
 * {@code evalAsync(js).get()} or any other EDT-scheduled task — the EDT
 * is busy running the handler.  Exceptions thrown by the handler are
 * caught by {@link PasswordDispatcher} and forwarded to
 * {@link Thread#getDefaultUncaughtExceptionHandler()}; nothing is filled
 * and the WebView stays responsive.
 *
 * <p>The event handed to the handler carries only the origin and username
 * — never the password.
 */
@FunctionalInterface
public interface WebViewFillPasswordHandler {

    /**
     * Decide whether {@code event}'s origin-matched credential should be
     * injected into the page.  Invoked on the EDT.
     */
    FillPasswordDisposition onAutofillRequested(WebViewFillPasswordEvent event);

    /**
     * The default handler: autofill unconditionally
     * ({@link FillPasswordDisposition#FILL}).  Preserves the library's
     * shipped silent-autofill behaviour and is the initial handler on
     * every component.
     */
    WebViewFillPasswordHandler DEFAULT = new WebViewFillPasswordHandler() {
        @Override
        public FillPasswordDisposition onAutofillRequested(
                WebViewFillPasswordEvent event) {
            return FillPasswordDisposition.FILL;
        }
    };

    /**
     * An opt-in handler that shows a Swing "Use saved password?" confirm
     * dialog, modal to the host window, showing the origin and username
     * (never the password).  OK returns {@link FillPasswordDisposition#FILL};
     * Cancel/close returns {@link FillPasswordDisposition#DONT_FILL}.
     */
    WebViewFillPasswordHandler CONFIRM = new WebViewFillPasswordHandler() {
        @Override
        public FillPasswordDisposition onAutofillRequested(
                WebViewFillPasswordEvent event) {
            Window host = SwingUtilities.getWindowAncestor(event.source());
            String msg = "Use the saved password for " + event.origin() + "?\n\n"
                + "Username: " + event.username();
            int r = JOptionPane.showConfirmDialog(host, msg,
                "Use saved password?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
            return r == JOptionPane.OK_OPTION
                ? FillPasswordDisposition.FILL
                : FillPasswordDisposition.DONT_FILL;
        }
    };
}
