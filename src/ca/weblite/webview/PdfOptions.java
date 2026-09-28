/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

/**
 * Page size, margins and background printing for
 * {@link ca.weblite.webview.swing.WebViewComponent#printToPdf(java.io.File, PdfOptions)}.
 *
 * <p>All lengths are in inches.  The defaults suit paged HTML that lays out
 * its own margins and running heads: US Letter, zero margins, backgrounds
 * on.  The engine's own headers and footers are always off and the scale is
 * always 1 (Canvas 29 D3).  Instances are immutable; the {@code with…}
 * methods return copies.
 */
public final class PdfOptions {

    private final double pageWidth;
    private final double pageHeight;
    private final double marginTop;
    private final double marginRight;
    private final double marginBottom;
    private final double marginLeft;
    private final boolean printBackgrounds;

    private PdfOptions(double pageWidth, double pageHeight, double marginTop,
                       double marginRight, double marginBottom,
                       double marginLeft, boolean printBackgrounds) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.marginTop = marginTop;
        this.marginRight = marginRight;
        this.marginBottom = marginBottom;
        this.marginLeft = marginLeft;
        this.printBackgrounds = printBackgrounds;
    }

    /** US Letter (8.5 × 11 in), zero margins, backgrounds on. */
    public static PdfOptions letter() {
        return new PdfOptions(8.5, 11, 0, 0, 0, 0, true);
    }

    /** A4 (8.27 × 11.69 in), zero margins, backgrounds on. */
    public static PdfOptions a4() {
        return new PdfOptions(8.27, 11.69, 0, 0, 0, 0, true);
    }

    /** A copy with the given page size, in inches. */
    public PdfOptions withPageSize(double width, double height) {
        return new PdfOptions(width, height, marginTop, marginRight,
                marginBottom, marginLeft, printBackgrounds);
    }

    /** A copy with the same margin, in inches, on every side. */
    public PdfOptions withMargins(double all) {
        return withMargins(all, all, all, all);
    }

    /** A copy with the given margins, in inches. */
    public PdfOptions withMargins(double top, double right, double bottom,
                                  double left) {
        return new PdfOptions(pageWidth, pageHeight, top, right, bottom, left,
                printBackgrounds);
    }

    /** A copy that does or does not print backgrounds. */
    public PdfOptions withBackgrounds(boolean on) {
        return new PdfOptions(pageWidth, pageHeight, marginTop, marginRight,
                marginBottom, marginLeft, on);
    }

    public double getPageWidth() {
        return pageWidth;
    }

    public double getPageHeight() {
        return pageHeight;
    }

    public double getMarginTop() {
        return marginTop;
    }

    public double getMarginRight() {
        return marginRight;
    }

    public double getMarginBottom() {
        return marginBottom;
    }

    public double getMarginLeft() {
        return marginLeft;
    }

    public boolean isPrintBackgrounds() {
        return printBackgrounds;
    }

    /**
     * Why these options cannot be printed, or {@code null} when they can.
     */
    public String validate() {
        if (!(pageWidth > 0) || !(pageHeight > 0)) {
            return "Page width and height must be positive.";
        }
        if (!(marginTop >= 0) || !(marginRight >= 0)
                || !(marginBottom >= 0) || !(marginLeft >= 0)) {
            return "Margins must not be negative.";
        }
        if (marginLeft + marginRight >= pageWidth
                || marginTop + marginBottom >= pageHeight) {
            return "The margins leave no room to print on the page.";
        }
        return null;
    }
}
