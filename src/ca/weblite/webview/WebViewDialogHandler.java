/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import java.awt.Window;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Handler interface for browser-initiated UI dialogs raised by the
 * embedded page: {@code window.alert}, {@code window.confirm},
 * {@code window.prompt}, and {@code <input type="file">} clicks.
 *
 * <p><strong>Default behaviour.</strong>  All four methods are
 * {@code default} and show standard Swing dialogs ({@link JOptionPane}
 * for alert / confirm / prompt; {@link JFileChooser} for file picker),
 * modal to the host {@code JFrame} resolved via
 * {@link SwingUtilities#getWindowAncestor}.  Callers that want stock
 * behaviour do nothing — the framework's {@link #DEFAULT} instance is
 * pre-installed on every {@link ca.weblite.webview.swing.WebViewComponent}.
 *
 * <p><strong>Custom behaviour.</strong>  Implement one or more methods
 * to override the default Swing dialog for that kind.  Un-overridden
 * methods fall through to the default Swing dialog.  Install with
 * {@link ca.weblite.webview.swing.WebViewComponent#setDialogHandler}.
 *
 * <p><strong>Suppression / headless tests.</strong>  Pass {@code null}
 * to {@code setDialogHandler} to install a drop handler that returns
 * the JS-spec cancel values synchronously without UI ({@code alert}
 * no-op; {@code confirm} false; {@code prompt} null; file picker empty
 * list).  Use {@link #DEFAULT} explicitly to reset to the stock Swing
 * defaults — note that {@code setDialogHandler(null)} and
 * {@code setDialogHandler(DEFAULT)} are NOT equivalent.
 *
 * <p><strong>Threading.</strong>  Every method runs on the Swing Event
 * Dispatch Thread, marshaled from whatever native thread fired the
 * dialog.  The native engine's JavaScript thread is suspended while
 * the handler runs — that is correct per the JS contract for
 * {@code alert} / {@code confirm} / {@code prompt}, which are
 * synchronous in the page.  The {@code <input type="file">} path is
 * asynchronous in JS but Java-side it still blocks the modal pump
 * until the user dismisses the picker.
 *
 * <p><strong>EDT-deadlock hazard.</strong>  Because the EDT is busy
 * running the handler, calling
 * {@code wv.evalAsync(js).get()} (or any other synchronous wait on an
 * EDT-scheduled task) from inside a handler DEADLOCKS — the
 * continuation can never run while the EDT is parked in the handler.
 * Handlers MAY freely call {@code wv.setUrl}, {@code wv.eval},
 * {@code wv.dispatch(r)} (those enqueue work on the native UI thread,
 * which is itself blocked waiting for the handler to return; the
 * enqueued work runs after the handler returns and the dialog closes).
 *
 * <p><strong>Exception isolation.</strong>  Exceptions thrown from a
 * handler method are caught by {@link DialogDispatcher} and forwarded
 * to {@link Thread#getDefaultUncaughtExceptionHandler()}; they do not
 * propagate to the native engine.  When a handler throws, the
 * dispatcher returns the safe fallback to the native side (alert:
 * void; confirm: false; prompt: null; file picker: empty list) so the
 * engine's JS thread is released cleanly.
 *
 * <p><strong>Platform coverage (this iteration).</strong>  macOS
 * heavyweight WKWebView is wired today (STORY-004-001).  Linux
 * WebKitGTK (STORY-004-002) and Windows WebView2 (STORY-004-003) wire
 * the same contract through their respective native engine callbacks
 * in subsequent stories — the Java API is final.  On platforms not yet
 * wired, the embedded engine continues to use its built-in dialogs and
 * the handler installed here is not invoked.
 */
public interface WebViewDialogHandler {

    /**
     * Stock handler instance whose methods invoke the {@code default}
     * implementations as-is (Swing dialogs anchored on the host
     * {@code JFrame}).  Stateless; safe to share across components and
     * threads.  Returned by
     * {@link ca.weblite.webview.swing.WebViewComponent#getDialogHandler}
     * when no caller has installed a custom handler.  Pass to
     * {@code setDialogHandler} to reset to defaults after a previous
     * custom or null installation.
     */
    WebViewDialogHandler DEFAULT = new WebViewDialogHandler() {};

    /**
     * Invoked when the page calls {@code window.alert(message)}.
     * Default: shows a modal {@link JOptionPane#showMessageDialog}.
     */
    default void alertOpened(WebViewAlertEvent event) {
        Window host = SwingUtilities.getWindowAncestor(event.source());
        JOptionPane.showMessageDialog(host, event.message(),
            "JavaScript Alert", JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Invoked when the page calls {@code window.confirm(message)}.
     * Default: shows a modal {@link JOptionPane#showConfirmDialog}
     * with OK/Cancel buttons and returns {@code true} when the user
     * clicks OK.
     */
    default boolean confirmOpened(WebViewConfirmEvent event) {
        Window host = SwingUtilities.getWindowAncestor(event.source());
        int r = JOptionPane.showConfirmDialog(host, event.message(),
            "JavaScript Confirm", JOptionPane.OK_CANCEL_OPTION);
        return r == JOptionPane.OK_OPTION;
    }

    /**
     * Invoked when the page calls {@code window.prompt(message, default)}.
     * Default: shows a modal {@link JOptionPane#showInputDialog} with
     * the page-supplied default value pre-populated.  Returns the
     * entered text on OK, or {@code null} on Cancel — the page sees
     * {@code null} as the {@code prompt} return value per the JS
     * contract.
     */
    default String promptOpened(WebViewPromptEvent event) {
        Window host = SwingUtilities.getWindowAncestor(event.source());
        Object r = JOptionPane.showInputDialog(host, event.message(),
            "JavaScript Prompt", JOptionPane.QUESTION_MESSAGE,
            null, null, event.defaultValue());
        return r == null ? null : r.toString();
    }

    /**
     * Invoked when the page user clicks an {@code <input type="file">}
     * element.  Default: shows a modal {@link JFileChooser} honouring
     * the page's {@code multiple} and {@code accept} attributes
     * (extensions only — Swing's {@link FileNameExtensionFilter} does
     * not filter by MIME type).  Returns the chosen files on OK, or
     * an empty list on Cancel; the page receives an empty
     * {@code FileList} in the latter case.
     */
    default List<File> filePickerOpened(WebViewFilePickerEvent event) {
        Window host = SwingUtilities.getWindowAncestor(event.source());
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(event.multiple());
        if (!event.acceptedExtensions().isEmpty()) {
            String[] exts = event.acceptedExtensions()
                .toArray(new String[0]);
            chooser.setFileFilter(
                new FileNameExtensionFilter("Accepted files", exts));
            chooser.setAcceptAllFileFilterUsed(false);
        }
        int r = chooser.showOpenDialog(host);
        if (r != JFileChooser.APPROVE_OPTION) {
            return Collections.emptyList();
        }
        if (event.multiple()) {
            File[] sel = chooser.getSelectedFiles();
            return sel == null
                ? Collections.<File>emptyList()
                : Arrays.asList(sel);
        }
        File f = chooser.getSelectedFile();
        return f == null
            ? Collections.<File>emptyList()
            : Collections.singletonList(f);
    }
}
