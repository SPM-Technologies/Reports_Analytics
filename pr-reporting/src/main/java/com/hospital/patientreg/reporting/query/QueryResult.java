package com.hospital.patientreg.reporting.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What the API returns for one Run: column metadata, one page of rows, totals and freshness.
 * {@code others}: with top N, the remaining groups added up (counts and sums only; other measures empty).
 * {@code truncated}: a pivot whose groups did not all fit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryResult(
        String dataset,
        String mode,
        List<QueryPlan.OutputColumn> columns,
        List<List<Object>> rows,
        List<Object> totals,
        long totalRows,
        int pageSize,
        int offset,
        LocalDateTime dataAsOf,
        long queryMs,
        List<Object> others,
        Boolean truncated) {
}
