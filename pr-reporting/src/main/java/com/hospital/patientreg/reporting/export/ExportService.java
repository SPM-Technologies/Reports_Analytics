package com.hospital.patientreg.reporting.export;

import com.hospital.patientreg.reporting.query.FilterNode;
import com.hospital.patientreg.reporting.query.InvalidQueryException;
import com.hospital.patientreg.reporting.query.QueryBuilder;
import com.hospital.patientreg.reporting.query.QueryPlan;
import com.hospital.patientreg.reporting.query.QueryPlan.OutputColumn;
import com.hospital.patientreg.reporting.query.QueryRequest;
import com.hospital.patientreg.reporting.query.QueryService;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.Field;
import com.hospital.patientreg.shared.export.BrandingProvider;
import com.hospital.patientreg.shared.export.CsvExporter;
import com.hospital.patientreg.shared.export.ExcelExporter;
import com.hospital.patientreg.shared.export.PdfExporter;
import com.hospital.patientreg.shared.export.ReportDefinition;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Downloads of exactly what the user sees on screen (same definition, all matching rows).
 * Rows stream from ClickHouse into the shared Excel / PDF / CSV writers.
 */
@Service
public class ExportService {

    public enum Format {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 1_000_000),
        PDF("pdf", "application/pdf", 50_000),
        CSV("csv", "text/csv; charset=UTF-8", 2_000_000);

        public final String extension;
        public final String contentType;
        public final int maxRows;

        Format(String extension, String contentType, int maxRows) {
            this.extension = extension;
            this.contentType = contentType;
            this.maxRows = maxRows;
        }

        public static Format of(String value) {
            for (Format f : values()) {
                if (f.extension.equalsIgnoreCase(value) || ("excel".equalsIgnoreCase(value) && f == XLSX)) {
                    return f;
                }
            }
            throw new InvalidQueryException("Unknown download format: " + value);
        }
    }

    /** A validated download, ready to stream; built before any response header is written. */
    public record PreparedExport(Format format, Dataset dataset, QueryPlan plan, String title, long rowCount, String hospitals,
                                 Set<Integer> hospitalIds) {
        public String fileName() {
            String base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
            base = base.replaceAll("^_+", "").replaceAll("_+$", "");
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
            return (base.isEmpty() ? "report" : base) + "_" + stamp + "." + format.extension;
        }
    }

    private final QueryService queryService;
    private final QueryBuilder builder;
    private final NamedParameterJdbcTemplate analytics;
    private final ExcelExporter excel;
    private final PdfExporter pdf;
    private final CsvExporter csv;
    private final BrandingProvider branding;

    public ExportService(QueryService queryService, QueryBuilder builder,
                         @Qualifier("analyticsJdbc") NamedParameterJdbcTemplate analytics,
                         ExcelExporter excel, PdfExporter pdf, CsvExporter csv, BrandingProvider branding) {
        this.branding = branding;
        this.queryService = queryService;
        this.builder = builder;
        this.analytics = analytics;
        this.excel = excel;
        this.pdf = pdf;
        this.csv = csv;
    }

    /** Hospitals the user may both analyse and download (403 if none). */
    static Set<Integer> exportScope() {
        Set<Integer> scope = new LinkedHashSet<>(QueryService.analyticsScope());
        scope.retainAll(TenantContext.require().hospitalsWith(Permission.EXPORT));
        if (scope.isEmpty()) {
            throw Tenant.forbidden("You do not have permission to download reports");
        }
        return scope;
    }

    /** Validates the definition and checks the row count against the format's limit. */
    public PreparedExport prepare(QueryRequest request, String formatName, String title) {
        Format format = Format.of(formatName);
        Tenant t = TenantContext.require();
        // each hospital may limit the formats of users without its settings permission; a download holds the rows
        // of every hospital in scope, so every one of them must allow the format (review finding)
        for (int h : exportScope()) {
            if (!t.can(Permission.MANAGE_SETTINGS, h) && !branding.forHospital(h).allows(format.extension)) {
                String code = t.hospital(h).map(x -> x.code()).orElse("H" + h);
                throw Tenant.forbidden(format.extension.toUpperCase(Locale.ROOT) + " downloads are not enabled for " + code);
            }
        }
        Dataset ds = queryService.dataset(request.dataset());
        Set<Integer> scope = exportScope();
        QueryPlan plan = builder.buildExport(request, ds, format.maxRows, scope);
        String hospitals = TenantContext.require().hospitals().stream().filter(h -> scope.contains(h.hospitalId()))
                .map(h -> h.code() + " " + h.name()).collect(Collectors.joining(", "));
        Long count = analytics.queryForObject(plan.countSql(), plan.params(), Long.class);
        long groups = count == null ? 0 : count;
        // top N: the file has N groups plus one "Others" row, however many groups there are
        long rows = request.topN() != null && groups > request.topN() ? request.topN() + 1 : groups;
        if (rows > format.maxRows) {
            throw new InvalidQueryException(String.format(Locale.ROOT,
                    "This report has %,d rows; %s downloads are limited to %,d rows. Add filters, or choose %s.",
                    rows, format.extension.toUpperCase(Locale.ROOT), format.maxRows,
                    format == Format.CSV ? "fewer rows" : "CSV"));
        }
        String cleanTitle = title == null || title.isBlank() ? ds.label() + " report" : title.trim();
        if (cleanTitle.length() > 120) {
            cleanTitle = cleanTitle.substring(0, 120);
        }
        return new PreparedExport(format, ds, plan, cleanTitle, rows, hospitals, scope);
    }

    public void write(PreparedExport export, QueryRequest request, OutputStream out) throws IOException {
        QueryPlan plan = export.plan();
        List<OutputColumn> all = plan.columns();
        // meta columns (the subtotal level) are not printed
        List<OutputColumn> cols = all.stream().filter(c -> !"meta".equals(c.role())).toList();
        int levelIndex = -1;
        for (int i = 0; i < all.size(); i++) {
            if ("__level".equals(all.get(i).key())) {
                levelIndex = i;
            }
        }
        int dims = (int) cols.stream().filter(c -> "dimension".equals(c.role())).count();
        boolean[] yesNo = new boolean[cols.size()];
        String[] headers = new String[cols.size()];
        float[] widths = new float[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            OutputColumn c = cols.get(i);
            headers[i] = c.label();
            yesNo[i] = "boolean".equals(c.type());
            widths[i] = switch (c.type()) {
                case "date" -> 11;
                case "number", "boolean" -> 9;
                case "money" -> 12;
                default -> Math.max(10, Math.min(28, c.label().length() + 6));
            };
        }

        List<Object> totalValues = plan.totalsSql() == null ? null : totalsValues(plan);
        Object[] totals = totalValues == null ? null : totalsRow(totalValues, cols.size(), dims);
        boolean topN = request.topN() != null && totalValues != null;
        int level = levelIndex;

        Consumer<Consumer<Object[]>> rows = sink -> {
            List<List<Object>> kept = new ArrayList<>();
            analytics.query(plan.dataSql(), plan.params(), rs -> {
                Object[] row = new Object[cols.size()];
                for (int c = 0; c < cols.size(); c++) {
                    Object v = QueryService.normalise(rs.getObject(c + 1));
                    row[c] = yesNo[c] && v instanceof Number n ? (n.intValue() == 1 ? "Yes" : "No") : v;
                }
                if (level >= 0) {
                    labelSubtotal(row, rs.getInt(level + 1), dims);
                }
                if (topN) {
                    kept.add(java.util.Arrays.asList(row.clone()));
                }
                sink.accept(row);
            });
            // top N: the remaining groups added up, as on screen
            if (topN && export.rowCount() > kept.size() && kept.size() == request.topN()) {
                sink.accept(QueryService.others(cols, kept, totalValues).toArray());
            }
        };

        String subtitle = export.hospitals() + "  |  " + describeFilters(request.filter(), export.dataset())
                + "  |  " + String.format(Locale.ROOT, "%,d rows", export.rowCount());
        Tenant t = TenantContext.require();
        int brandHospital = export.hospitalIds().contains(t.activeHospitalId()) ? t.activeHospitalId() : export.hospitalIds().iterator().next();
        ReportDefinition def = new ReportDefinition(export.title(), subtitle, headers, widths, rows, totals,
                branding.forHospital(brandHospital));
        switch (export.format()) {
            case XLSX -> excel.export(def, out);
            case PDF -> pdf.export(def, out);
            case CSV -> csv.export(def, out);
        }
    }

    private List<Object> totalsValues(QueryPlan plan) {
        return analytics.queryForObject(plan.totalsSql(), plan.params(), (rs, i) -> {
            List<Object> v = new ArrayList<>();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                v.add(QueryService.normalise(rs.getObject(c)));
            }
            return v;
        });
    }

    private static Object[] totalsRow(List<Object> t, int width, int dims) {
        Object[] totals = new Object[width];
        totals[0] = "TOTAL";
        for (int i = 0; i < t.size() && dims + i < width; i++) {
            totals[dims + i] = t.get(i);
        }
        return totals;
    }

    /**
     * A ROLLUP subtotal row: {@code grouping()} sets one bit per rolled-up group column (the last column is the
     * lowest bit). The first rolled-up column reads "Subtotal", the others are left empty.
     */
    static void labelSubtotal(Object[] row, int level, int dims) {
        if (level <= 0) {
            return;
        }
        boolean first = true;
        for (int i = 0; i < dims; i++) {
            if (((level >> (dims - 1 - i)) & 1) == 1) {
                row[i] = first ? "Subtotal" : null;
                first = false;
            }
        }
    }

    /** Short human-readable filter summary printed under the title. */
    static String describeFilters(FilterNode node, Dataset ds) {
        return node == null ? "No filters" : "Filters: " + describe(node, ds);
    }

    private static String describe(FilterNode n, Dataset ds) {
        if (n.and() != null && !n.and().isEmpty()) {
            return join(n.and(), " AND ", ds);
        }
        if (n.or() != null && !n.or().isEmpty()) {
            return "(" + join(n.or(), " OR ", ds) + ")";
        }
        String label = ds.field(n.field()).map(Field::label).orElse(n.field());
        String value = n.value() == null ? "" : " " + n.value();
        return label + " " + n.op() + value;
    }

    private static String join(List<FilterNode> nodes, String sep, Dataset ds) {
        List<String> parts = new ArrayList<>();
        nodes.forEach(c -> parts.add(describe(c, ds)));
        return String.join(sep, parts);
    }
}
