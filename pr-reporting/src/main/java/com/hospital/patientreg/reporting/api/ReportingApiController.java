package com.hospital.patientreg.reporting.api;

import com.hospital.patientreg.reporting.export.ExportService;
import com.hospital.patientreg.reporting.query.InvalidQueryException;
import com.hospital.patientreg.reporting.query.QueryRequest;
import com.hospital.patientreg.shared.export.ExportLimiter;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.hospital.patientreg.reporting.query.QueryResult;
import com.hospital.patientreg.reporting.query.QueryService;
import com.hospital.patientreg.reporting.query.UserQueryLimiter;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.DatasetCatalog;
import com.hospital.patientreg.reporting.semantic.Field;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reporting JSON API, version 1 (see Docs/Solution-Architecture.html, section 12). */
@RestController
@RequestMapping("/api/v1")
public class ReportingApiController {

    private final DatasetCatalog catalog;
    private final QueryService queryService;
    private final ExportService exportService;
    private final ExportLimiter exportLimiter;
    private final UserQueryLimiter userLimiter;
    private final AuditTrail audit;

    public ReportingApiController(DatasetCatalog catalog, QueryService queryService, ExportService exportService,
                                  ExportLimiter exportLimiter, UserQueryLimiter userLimiter, AuditTrail audit) {
        this.userLimiter = userLimiter;
        this.audit = audit;
        this.catalog = catalog;
        this.queryService = queryService;
        this.exportService = exportService;
        this.exportLimiter = exportLimiter;
    }

    /** Datasets and the fields users may work with (hidden fields left out). */
    @GetMapping("/datasets")
    public Collection<Map<String, Object>> datasets() {
        boolean several = QueryService.analyticsScope().size() > 1;
        return catalog.all().stream().map(ds -> describe(queryService.dataset(ds.id()), several)).toList();
    }

    @GetMapping("/datasets/{id}")
    public Map<String, Object> dataset(@PathVariable String id) {
        return describe(queryService.dataset(id), QueryService.analyticsScope().size() > 1);
    }

    /** Run a report definition; the answer is rendered on the same screen. */
    @PostMapping("/query")
    public QueryResult query(@RequestBody QueryRequest request) {
        return userLimiter.run(() -> queryService.run(request));
    }

    /** What the UI sends to download: the on-screen definition, a format and an optional title. */
    public record ExportRequest(QueryRequest query, String format, String title) {
    }

    /**
     * Streams exactly what is on screen as Excel, PDF or CSV. Runs through the shared download limit;
     * validation and the row-count check happen before any header is written, so an error is a JSON
     * problem response rather than a broken file.
     */
    @PostMapping("/query/export")
    public void export(@RequestBody ExportRequest request, HttpServletResponse response) throws IOException {
        if (request == null || request.query() == null) {
            throw new InvalidQueryException("The report definition is missing");
        }
        exportLimiter.run(() -> {
            ExportService.PreparedExport prepared = exportService.prepare(request.query(), request.format(), request.title());
            response.setContentType(prepared.format().contentType);
            response.setHeader("Content-Disposition", "attachment; filename=\"" + prepared.fileName() + "\"");
            exportService.write(prepared, request.query(), response.getOutputStream());
            // one entry per hospital in the download, so each hospital's administrators see it in their audit log
            for (int hospital : prepared.hospitalIds()) {
                audit.record("REPORT_EXPORTED", hospital, "Analytics: " + prepared.title() + " (" + prepared.format().extension
                        + ", " + prepared.rowCount() + " rows; " + prepared.hospitals() + ")");
            }
        });
    }

    /** Top values of a field, for filter drop-downs. */
    @GetMapping("/datasets/{id}/fields/{field}/values")
    public List<Map<String, Object>> values(@PathVariable String id, @PathVariable String field,
                                            @RequestParam(required = false) String q) {
        return userLimiter.run(() -> queryService.suggestions(id, field, q));
    }

    /** How fresh the analytics copy is. */
    @GetMapping("/freshness")
    public Map<String, Object> freshness(@RequestParam(defaultValue = "patients") String dataset) {
        LocalDateTime asOf = queryService.dataAsOf(queryService.dataset(dataset), QueryService.analyticsScope());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", dataset);
        out.put("dataAsOf", asOf);
        return out;
    }

    /** The dataset as the user may work with it: hidden fields left out, hospital fields only for several hospitals. */
    private static Map<String, Object> describe(Dataset ds, boolean severalHospitals) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ds.id());
        out.put("label", ds.label());
        out.put("description", ds.description());
        out.put("fields", ds.fields().stream().filter(f -> !f.hidden() && (severalHospitals || !f.multiHospital()))
                .map(ReportingApiController::describe).toList());
        return out;
    }

    private static Map<String, Object> describe(Field f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", f.name());
        out.put("label", f.label());
        out.put("type", f.type());
        out.put("role", f.role());
        out.put("operators", f.isMeasure() ? List.of() : f.type().operators().stream().sorted().toList());
        out.put("aggregations", f.aggregations() == null ? List.of() : f.aggregations());
        out.put("suggest", f.suggest());
        out.put("origin", f.origin() == null ? "standard" : f.origin());
        if (f.formula() != null) {
            out.put("formula", f.formula());
        }
        if (f.description() != null) {
            out.put("description", f.description());
        }
        return out;
    }
}
