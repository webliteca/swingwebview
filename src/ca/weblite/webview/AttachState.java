/*
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction.
 */
package ca.weblite.webview;

/**
 * Lifecycle state of an {@link EmbeddedWebView}'s native peer attachment.
 *
 * <p>State transitions are confined to the Swing Event Dispatch Thread. The
 * only legal transitions are {@code PENDING → ATTACHED} and
 * {@code PENDING → FAILED}; {@code ATTACHED} and {@code FAILED} are terminal.
 *
 * <p>On Windows and Linux, the engine enters {@link #ATTACHED} synchronously
 * during the {@code EmbeddedWebView} constructor — its native side completes
 * before {@code webview_embed_create} returns. On macOS, the engine enters
 * {@link #ATTACHED} or {@link #FAILED} asynchronously after the AppKit-side
 * setup (WKWebView allocation, host-NSView discovery, {@code addSubview:},
 * configuration) completes; a JNI callback marshals onto the EDT to drive
 * the transition.
 *
 * <p>Callers that need an explicit signal MUST use
 * {@link EmbeddedWebView#addOnAttachComplete}; pre-attach method calls
 * on a {@code PENDING} engine ({@code setUrl}, {@code addOnBeforeLoad},
 * {@code addJavascriptCallback}, {@code eval}, etc.) are buffered by the
 * native dispatch queue and replay automatically once the engine reaches
 * {@code ATTACHED}.
 */
public enum AttachState {
    /** AppKit-side setup is in flight; only macOS observes this state. */
    PENDING,
    /** Native peer is fully attached and ready for use. */
    ATTACHED,
    /** AppKit-side setup failed; failure cause available via the listener. */
    FAILED
}
