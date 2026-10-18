package com.hospital.patientreg.shared.export;

import java.util.function.Consumer;

/**
 * Everything an exporter needs: titles, column headers, relative column widths,
 * a row source (streamed so huge reports never sit fully in memory), an optional totals row,
 * and the hospital's branding (title prefix, header and footer lines, logo).
 */
public record ReportDefinition(
        String title,
        String subtitle,
        String[] headers,
        float[] widths,
        Consumer<Consumer<Object[]>> rows,
        Object[] totalRow,
        Branding branding) {

    public ReportDefinition(String title, String subtitle, String[] headers, float[] widths,
                            Consumer<Consumer<Object[]>> rows, Object[] totalRow) {
        this(title, subtitle, headers, widths, rows, totalRow, Branding.NONE);
    }

    public ReportDefinition {
        branding = branding == null ? Branding.NONE : branding;
    }

    /** The title with the hospital's prefix. */
    public String fullTitle() {
        return branding.title(title);
    }
}
