package com.hospital.patientreg.shared.export;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Writes a report as PDF (A4 landscape) using OpenPDF. Rows are flushed to the
 * document in chunks so a large table does not have to be held in memory.
 */
@Component
public class PdfExporter {

    private static final int FLUSH_EVERY = 500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final Color HEADER_BG = new Color(0, 105, 120);
    private static final Color TOTAL_BG = new Color(225, 235, 238);

    private final Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
    private final Font subFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
    private final Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
    private final Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 7.5f);
    private final Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f);
    private final Font footerFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);

    public void export(ReportDefinition report, OutputStream out) {
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 28, 32);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        Branding b = report.branding();
        writer.setPageEvent(new PageFooter(report.fullTitle(), b.footerText()));
        doc.open();

        if (b.logo() != null) {
            try {
                com.lowagie.text.Image logo = com.lowagie.text.Image.getInstance(b.logo());
                logo.scaleToFit(160, 40);
                logo.setAlignment(Element.ALIGN_LEFT);
                doc.add(logo);
            } catch (Exception e) {
                // an unreadable logo is left out rather than failing the download
            }
        }
        Paragraph title = new Paragraph(report.fullTitle(), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);
        if (b.headerText() != null && !b.headerText().isBlank()) {
            Paragraph header = new Paragraph(b.headerText(), subFont);
            header.setAlignment(Element.ALIGN_CENTER);
            doc.add(header);
        }
        Paragraph sub = new Paragraph(report.subtitle() + "   |   Generated: "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")), subFont);
        sub.setAlignment(Element.ALIGN_CENTER);
        sub.setSpacingAfter(8);
        doc.add(sub);

        PdfPTable table = new PdfPTable(report.widths());
        table.setWidthPercentage(100);
        table.setHeaderRows(1); // repeat header on every page
        table.setComplete(false);
        for (String h : report.headers()) {
            PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
            cell.setBackgroundColor(HEADER_BG);
            cell.setPadding(4);
            table.addCell(cell);
        }

        int[] count = {0};
        report.rows().accept(values -> {
            addRow(table, values, cellFont, null);
            if (++count[0] % FLUSH_EVERY == 0) {
                doc.add(table); // writes the completed rows and frees them
            }
        });
        if (report.totalRow() != null) {
            addRow(table, report.totalRow(), totalFont, TOTAL_BG);
        }
        table.setComplete(true);
        doc.add(table);

        if (count[0] == 0) {
            doc.add(new Paragraph("No records found for the selected filters.", subFont));
        }
        doc.close();
    }

    private static void addRow(PdfPTable table, Object[] values, Font font, Color bg) {
        for (Object v : values) {
            PdfPCell cell = new PdfPCell(new Phrase(format(v), font));
            cell.setPadding(3);
            if (v instanceof Number) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            if (bg != null) cell.setBackgroundColor(bg);
            table.addCell(cell);
        }
    }

    private static String format(Object v) {
        if (v == null) return "";
        if (v instanceof LocalDate d) return d.format(DATE);
        if (v instanceof BigDecimal b) return new DecimalFormat("#,##0.00").format(b);
        if (v instanceof Long || v instanceof Integer) return new DecimalFormat("#,##0").format(v);
        return v.toString();
    }

    /** "Report title ... hospital footer ... Page n" on each page. */
    private final class PageFooter extends PdfPageEventHelper {
        private final String title;
        private final String footer;

        PageFooter(String title, String footer) {
            this.title = title;
            this.footer = footer;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float y = document.bottom() - 18;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(title, footerFont), document.left(), y, 0);
            if (footer != null && !footer.isBlank()) {
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                        new Phrase(footer, footerFont), (document.left() + document.right()) / 2, y, 0);
            }
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), footerFont), document.right(), y, 0);
        }
    }
}
