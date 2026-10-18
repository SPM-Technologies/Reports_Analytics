package com.hospital.patientreg.shared.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** Writes a report as .xlsx using Apache POI's streaming workbook (only 200 rows kept in memory). */
@Component
public class ExcelExporter {

    private static final int EXCEL_MAX_ROWS = 1_048_000;

    public void export(ReportDefinition report, OutputStream out) throws IOException {
        SXSSFWorkbook wb = new SXSSFWorkbook(200);
        wb.setCompressTempFiles(true);
        try {
            SXSSFSheet sheet = wb.createSheet("Report");
            Styles st = new Styles(wb);
            int cols = report.headers().length;

            Row titleRow = sheet.createRow(0);
            setCell(titleRow, 0, report.fullTitle(), st.title);
            merge(sheet, 0, cols);

            Row subRow = sheet.createRow(1);
            setCell(subRow, 0, report.subtitle() + "  |  Generated: "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")), st.subtitle);
            merge(sheet, 1, cols);
            Branding b = report.branding();
            if (b.headerText() != null && !b.headerText().isBlank()) {
                setCell(sheet.createRow(2), 0, b.headerText(), st.subtitle);
                merge(sheet, 2, cols);
            }
            addLogo(wb, sheet, b.logo(), cols);

            Row header = sheet.createRow(3);
            for (int c = 0; c < cols; c++) {
                setCell(header, c, report.headers()[c], st.header);
                // widths are approximate character counts
                sheet.setColumnWidth(c, Math.min(255, Math.round(report.widths()[c] * 1.15f) + 2) * 256);
            }
            sheet.createFreezePane(0, 4);

            int[] rowNum = {4};
            report.rows().accept(values -> {
                if (rowNum[0] >= EXCEL_MAX_ROWS) return;
                writeRow(sheet.createRow(rowNum[0]++), values, st, false);
            });

            if (report.totalRow() != null) {
                writeRow(sheet.createRow(rowNum[0]++), report.totalRow(), st, true);
            }
            if (b.footerText() != null && !b.footerText().isBlank() && rowNum[0] + 1 < EXCEL_MAX_ROWS) {
                setCell(sheet.createRow(rowNum[0] + 1), 0, b.footerText(), st.subtitle);
            }
            wb.write(out);
        } finally {
            wb.close(); // also deletes the temp files
        }
    }

    private static void writeRow(Row row, Object[] values, Styles st, boolean total) {
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            Cell cell = row.createCell(c);
            if (v instanceof Number n) {
                cell.setCellValue(n.doubleValue());
                boolean decimal = v instanceof BigDecimal || v instanceof Double;
                cell.setCellStyle(total ? (decimal ? st.totalDecimal : st.totalNumber)
                        : (decimal ? st.decimal : st.number));
            } else if (v instanceof LocalDate d) {
                cell.setCellValue(d);
                cell.setCellStyle(st.date);
            } else {
                cell.setCellValue(v == null ? "" : v.toString());
                cell.setCellStyle(total ? st.totalText : st.text);
            }
        }
    }

    /** The hospital logo in the top-right corner (PNG or JPEG; anything else is left out). */
    private static void addLogo(SXSSFWorkbook wb, SXSSFSheet sheet, byte[] logo, int cols) {
        if (logo == null || logo.length < 4) {
            return;
        }
        int type = (logo[0] & 0xFF) == 0x89 ? org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG
                : org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_JPEG;
        int index = wb.addPicture(logo, type);
        org.apache.poi.ss.usermodel.ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
        anchor.setCol1(Math.max(0, cols - 1));
        anchor.setRow1(0);
        anchor.setCol2(Math.max(1, cols));
        anchor.setRow2(3);
        sheet.createDrawingPatriarch().createPicture(anchor, index);
    }

    private static void setCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** Cell styles are created once per workbook (Excel has a limit on style count). */
    private static final class Styles {
        final CellStyle title, subtitle, header, text, number, decimal, date;
        final CellStyle totalText, totalNumber, totalDecimal;

        Styles(SXSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            title = wb.createCellStyle();
            title.setFont(titleFont);
            subtitle = wb.createCellStyle();

            header = bordered(wb);
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            text = bordered(wb);
            number = bordered(wb);
            number.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            decimal = bordered(wb);
            decimal.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            date = bordered(wb);
            date.setDataFormat(wb.createDataFormat().getFormat("dd-mm-yyyy"));

            totalText = bordered(wb);
            totalText.setFont(bold);
            totalNumber = bordered(wb);
            totalNumber.cloneStyleFrom(number);
            totalNumber.setFont(bold);
            totalDecimal = bordered(wb);
            totalDecimal.cloneStyleFrom(decimal);
            totalDecimal.setFont(bold);
        }

        private static CellStyle bordered(SXSSFWorkbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }
    }

    /** Title lines span all columns; a one-column report needs no merge (POI refuses a one-cell region). */
    private static void merge(org.apache.poi.ss.usermodel.Sheet sheet, int row, int cols) {
        if (cols > 1) {
            sheet.addMergedRegion(new CellRangeAddress(row, row, 0, cols - 1));
        }
    }
}
