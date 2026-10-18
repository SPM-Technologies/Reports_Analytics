package com.hospital.patientreg.reporting.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Filter tree: either a group ({@code and} or {@code or} list of nodes) or a condition
 * ({@code field}, {@code op}, {@code value}). Example:
 * {"and":[{"field":"gender","op":"eq","value":"Female"},{"field":"registration_date","op":"relative","value":"LAST_30_DAYS"}]}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FilterNode(List<FilterNode> and, List<FilterNode> or, String field, String op, Object value) {

    public static FilterNode condition(String field, String op, Object value) {
        return new FilterNode(null, null, field, op, value);
    }

    public static FilterNode and(FilterNode... nodes) {
        return new FilterNode(List.of(nodes), null, null, null, null);
    }

    public static FilterNode or(FilterNode... nodes) {
        return new FilterNode(null, List.of(nodes), null, null, null);
    }
}
