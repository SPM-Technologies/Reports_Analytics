package com.hospital.patientreg.shared.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Writes a report as CSV (RFC 4180): UTF-8 with a byte-order mark so Excel shows Indian-script
 * names correctly, a header row, then streamed rows. Unlike Excel there is no row limit.
 */
@Component
public class CsvExporter {

    public void export(ReportDefinition report, OutputStream out) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
        w.write('﻿');
        writeLine(w, report.headers());
        try {
            report.rows().accept(values -> {
                try {
                    writeLine(w, values);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (report.totalRow() != null) {
                writeLine(w, report.totalRow());
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        w.flush();
    }

    private static void writeLine(Writer w, Object[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(cell(values[i]));
        }
        w.write("\r\n");
    }

    /** Quotes values containing separators, quotes or line breaks; neutralises spreadsheet formulas. */
    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = v instanceof BigDecimal b ? b.toPlainString() : v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) {
            s = "'" + s;   // stop "=HYPERLINK(...)" style formula injection when the file is opened in Excel
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
