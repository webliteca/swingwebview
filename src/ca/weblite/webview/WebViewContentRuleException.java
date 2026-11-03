/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Why compiling, looking up or removing a content rule list failed
 * (Canvas 37 D2).  The message is the engine's own reason where the engine
 * gives one (WebKit's compiler error, for example), or the rule index and
 * problem the portable validator found.
 */
public class WebViewContentRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WebViewContentRuleException(String message) {
        super(message);
    }

    public WebViewContentRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
