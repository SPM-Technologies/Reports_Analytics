package com.hospital.patientreg.reporting.query;

import java.util.List;
import java.util.Map;

/**
 * Validated, parameterised SQL for one report definition. {@code totalsSql} is null for detail lists.
 * All user values are in {@code params}; the SQL text contains only catalogue identifiers.
 */
public record QueryPlan(
        String mode,
        List<OutputColumn> columns,
        String dataSql,
        String countSql,
        String totalsSql,
        Map<String, Object> params,
        int pageSize,
        int offset) {

    /**
     * A column of the result: a field shown/grouped ("dimension"), a computed measure ("measure"), or the
     * subtotal level of a row ("meta", only with subtotals). {@code additive} measures (counts, sums) can be
     * added up across groups, e.g. into an "Others" row.
     */
    public record OutputColumn(String key, String label, String type, String role, boolean additive) {
        public OutputColumn(String key, String label, String type, String role) {
            this(key, label, type, role, false);
        }
    }
}
