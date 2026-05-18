/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JavaScriptEvalExceptionTest {

    @Test
    public void carriesMessageVerbatim() {
        JavaScriptEvalException ex = new JavaScriptEvalException("foo is not defined");
        assertEquals("foo is not defined", ex.getMessage());
    }

    @Test
    public void acceptsNullMessage() {
        // RuntimeException permits null; the dispatcher never passes null
        // but document the contract.
        JavaScriptEvalException ex = new JavaScriptEvalException(null);
        assertNull(ex.getMessage());
    }

    @Test
    public void isARuntimeException() {
        assertTrue(new JavaScriptEvalException("x") instanceof RuntimeException);
    }
}
