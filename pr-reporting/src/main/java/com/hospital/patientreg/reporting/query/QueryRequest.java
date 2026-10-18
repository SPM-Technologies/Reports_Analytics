package com.hospital.patientreg.reporting.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A report definition sent by the UI (never SQL). Without measures it is a detail list of
 * {@code columns}; with measures, {@code columns} are the group-by dimensions.
 * <p>
 * Stage 5b: {@code calculated} fields of this report (formulas, see reporting.formula), {@code subtotals}
 * per group, {@code topN} groups (plus "Others"), {@code having} conditions on totals, and {@code pivot}
 * (one of the columns spread across; the result then holds all groups up to the group limit).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRequest(
        String dataset,
        List<String> columns,
        List<MeasureSpec> measures,
        FilterNode filter,
        List<SortSpec> sort,
        Integer pageSize,
        Integer offset,
        List<CalculatedSpec> calculated,
        Boolean subtotals,
        Integer topN,
        List<FilterNode> having,
        String pivot) {

    public QueryRequest(String dataset, List<String> columns, List<MeasureSpec> measures, FilterNode filter,
                        List<SortSpec> sort, Integer pageSize, Integer offset) {
        this(dataset, columns, measures, filter, sort, pageSize, offset, null, null, null, null, null);
    }

    /**
     * {@code agg} is required for dimension fields (sum, avg, min, max, countDistinct); measures have a fixed one.
     * {@code display}: shown as is (null), or percentOfTotal, percentOfGroup, runningTotal, rank, change, percentChange.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeasureSpec(String field, String agg, String display) {
        public MeasureSpec(String field, String agg) {
            this(field, agg, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SortSpec(String field, String dir) {
    }

    /** A calculated field of this report: name {@code calc_...}, label, formula. */
    @JsonIgnoreProperties(ignoreUnknown = true)   // the UI also keeps the kind and type it was told
    public record CalculatedSpec(String name, String label, String formula) {
    }

    public boolean wantsSubtotals() {
        return Boolean.TRUE.equals(subtotals);
    }
}
