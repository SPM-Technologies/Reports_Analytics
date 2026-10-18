package com.hospital.patientreg.shared.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CsvExporterTest {

    @Test
    void quotesSeparatorsQuotesAndLineBreaks() {
        assertThat(CsvExporter.cell("plain")).isEqualTo("plain");
        assertThat(CsvExporter.cell("Rao, Ravi")).isEqualTo("\"Rao, Ravi\"");
        assertThat(CsvExporter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvExporter.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvExporter.cell(null)).isEmpty();
    }

    @Test
    void neutralisesSpreadsheetFormulasButNotNumbers() {
        assertThat(CsvExporter.cell("=HYPERLINK(\"http://evil\")")).startsWith("\"'=HYPERLINK");
        assertThat(CsvExporter.cell("+91 98765")).isEqualTo("'+91 98765");
        assertThat(CsvExporter.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        // a leading tab or carriage return also starts a formula in some spreadsheets (final review finding)
        assertThat(CsvExporter.cell("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(CsvExporter.cell("\r=1+1")).startsWith("\"'\r=1+1");
        assertThat(CsvExporter.cell(-5)).isEqualTo("-5");
        assertThat(CsvExporter.cell(new BigDecimal("-12.50"))).isEqualTo("-12.50");
    }

    @Test
    void writesBomHeaderRowsAndTotalsInUtf8() throws Exception {
        ReportDefinition def = new ReportDefinition("t", "s", new String[] {"Name", "Date", "Fee"}, new float[] {1, 1, 1},
                sink -> sink.accept(new Object[] {"राम शर्मा", LocalDate.of(2026, 10, 15), new BigDecimal("100.00")}),
                new Object[] {"TOTAL", null, new BigDecimal("100.00")});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CsvExporter().export(def, out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("﻿Name,Date,Fee\r\nराम शर्मा,2026-10-15,100.00\r\nTOTAL,,100.00\r\n");
    }
}
