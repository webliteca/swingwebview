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

/** {@link PdfOptions} defaults, copies and validation (Canvas 29 op 1). */
public class PdfOptionsTest {

    private static final double EPS = 1e-9;

    @Test
    public void testLetterIsTheDefaultShape() {
        PdfOptions o = PdfOptions.letter();
        assertEquals(8.5, o.getPageWidth(), EPS);
        assertEquals(11, o.getPageHeight(), EPS);
        assertEquals(0, o.getMarginTop(), EPS);
        assertEquals(0, o.getMarginRight(), EPS);
        assertEquals(0, o.getMarginBottom(), EPS);
        assertEquals(0, o.getMarginLeft(), EPS);
        assertTrue(o.isPrintBackgrounds());
        assertNull(o.validate());
    }

    @Test
    public void testA4() {
        PdfOptions o = PdfOptions.a4();
        assertEquals(8.27, o.getPageWidth(), EPS);
        assertEquals(11.69, o.getPageHeight(), EPS);
        assertNull(o.validate());
    }

    @Test
    public void testWithCopiesLeaveTheOriginalAlone() {
        PdfOptions base = PdfOptions.letter();
        PdfOptions m = base.withMargins(0.5).withBackgrounds(false)
                .withPageSize(5, 7);
        assertEquals(0, base.getMarginTop(), EPS);
        assertTrue(base.isPrintBackgrounds());
        assertEquals(0.5, m.getMarginTop(), EPS);
        assertEquals(0.5, m.getMarginRight(), EPS);
        assertEquals(0.5, m.getMarginBottom(), EPS);
        assertEquals(0.5, m.getMarginLeft(), EPS);
        assertEquals(5, m.getPageWidth(), EPS);
        assertEquals(7, m.getPageHeight(), EPS);
        assertEquals(false, m.isPrintBackgrounds());
        PdfOptions four = base.withMargins(1, 2, 3, 4);
        assertEquals(1, four.getMarginTop(), EPS);
        assertEquals(2, four.getMarginRight(), EPS);
        assertEquals(3, four.getMarginBottom(), EPS);
        assertEquals(4, four.getMarginLeft(), EPS);
    }

    @Test
    public void testValidationSentences() {
        assertEquals("Page width and height must be positive.",
                PdfOptions.letter().withPageSize(0, 11).validate());
        assertEquals("Page width and height must be positive.",
                PdfOptions.letter().withPageSize(8.5, Double.NaN).validate());
        assertEquals("Margins must not be negative.",
                PdfOptions.letter().withMargins(0, -0.1, 0, 0).validate());
        assertEquals("The margins leave no room to print on the page.",
                PdfOptions.letter().withMargins(0, 4.25, 0, 4.25).validate());
        assertEquals("The margins leave no room to print on the page.",
                PdfOptions.letter().withMargins(6, 0, 5, 0).validate());
    }
}
