package com.hospital.patientreg.reporting.query;

import com.hospital.patientreg.reporting.query.QueryPlan.OutputColumn;
import com.hospital.patientreg.reporting.query.QueryRequest.MeasureSpec;
import com.hospital.patientreg.reporting.query.QueryRequest.SortSpec;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.Field;
import com.hospital.patientreg.reporting.semantic.FieldType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.hospital.patientreg.reporting.formula.CalculatedFields;
import com.hospital.patientreg.reporting.query.QueryRequest.CalculatedSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a report definition into validated, parameterised ClickHouse SQL.
 * <ul>
 *   <li>Only identifiers from the (validated) catalogue are written into SQL; every user value is a bound parameter.</li>
 *   <li>Every query reads {@code FINAL} (latest version per patient) and skips deleted rows.</li>
 *   <li>Relative dates are computed in the application's time zone (IST).</li>
 * </ul>
 */
@Component
public class QueryBuilder {

    private static final int MAX_COLUMNS = 40;
    private static final int MAX_GROUP_BY = 6;
    private static final int MAX_CONDITIONS = 50;
    private static final int MAX_FILTER_DEPTH = 4;
    private static final int MAX_IN_VALUES = 1000;
    private static final int MAX_OFFSET = 1_000_000;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int EXPORT_TIMEOUT_SECONDS = 300;
    private static final Set<String> AGGREGATIONS = Set.of("sum", "avg", "min", "max", "countDistinct");
    private static final int MAX_PIVOT_GROUPS = 10_000;
    private static final int MAX_CALCULATED = 20;
    private static final int MAX_TOP_N = 1000;
    private static final Pattern CALC_NAME = Pattern.compile("calc_[a-z0-9_]{1,40}");

    private final Clock clock;
    private final int maxPageSize;

    public QueryBuilder(Clock reportingClock, @Value("${app.reporting.query.maxPageSize}") int maxPageSize) {
        this.clock = reportingClock;
        this.maxPageSize = maxPageSize;
    }

    /**
     * On-screen query: one page of at most {@code maxPageSize} rows, limited to {@code hospitals}
     * (the signed-in user's hospitals, from the server, never from the request; architecture 10.1).
     */
    public QueryPlan build(QueryRequest req, Dataset ds, Collection<Integer> hospitals) {
        return build(req, ds, null, hospitals);
    }

    /** Download: the same definition, all matching rows up to {@code maxRows}, from the first row. */
    public QueryPlan buildExport(QueryRequest req, Dataset ds, int maxRows, Collection<Integer> hospitals) {
        return build(req, ds, maxRows, hospitals);
    }

    private QueryPlan build(QueryRequest req, Dataset base, Integer exportRows, Collection<Integer> hospitals) {
        List<Integer> scope = scope(hospitals);
        Dataset ds = withCalculated(req, base);
        List<String> columns = req.columns() == null ? List.of() : req.columns();
        List<MeasureSpec> measures = req.measures() == null ? List.of() : req.measures();
        if (columns.isEmpty() && measures.isEmpty()) {
            throw new InvalidQueryException("Choose at least one column or measure");
        }
        if (columns.size() > MAX_COLUMNS) {
            throw new InvalidQueryException("At most " + MAX_COLUMNS + " columns");
        }
        if (new LinkedHashSet<>(columns).size() != columns.size()) {
            throw new InvalidQueryException("A column is listed twice");
        }

        boolean aggregate = !measures.isEmpty();
        if (aggregate && columns.size() > MAX_GROUP_BY) {
            throw new InvalidQueryException("At most " + MAX_GROUP_BY + " group-by columns");
        }
        boolean subtotals = req.wantsSubtotals();
        if (subtotals && (!aggregate || columns.size() < 2)) {
            throw new InvalidQueryException("Subtotals need at least two group columns and a measure");
        }
        Integer topN = req.topN();
        if (topN != null && (!aggregate || columns.isEmpty() || topN < 1 || topN > MAX_TOP_N || subtotals)) {
            throw new InvalidQueryException("Top N needs a group column and a measure, N from 1 to " + MAX_TOP_N + ", and no subtotals");
        }
        String pivot = req.pivot();
        if (pivot != null && (!aggregate || !columns.contains(pivot) || columns.size() < 2 || subtotals || topN != null)) {
            throw new InvalidQueryException("A pivot needs a measure, at least two group columns (one of them spread across) and no subtotals or top N");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        ds.fields().forEach(f -> params.putAll(f.params()));   // bound text values of calculated fields

        List<OutputColumn> out = new ArrayList<>();
        List<String> selectExprs = new ArrayList<>();
        List<String> dimensionExprs = new ArrayList<>();
        for (String name : columns) {
            Field f = dimension(ds, name);
            out.add(new OutputColumn(f.name(), f.label(), f.type().json(), "dimension"));
            selectExprs.add(f.expression() == null ? ident(f.name()) : f.expression() + " AS " + ident(f.name()));
            dimensionExprs.add(colExpr(f));
        }

        List<String> measureExprs = new ArrayList<>();       // everything selected, windows included
        List<String> plainMeasureExprs = new ArrayList<>();  // without window functions (totals, conditions on totals)
        List<String> totalsExprs = new ArrayList<>();
        Set<String> windowed = new LinkedHashSet<>();
        for (MeasureSpec m : measures) {
            Field f = ds.field(m.field()).orElseThrow(() -> new InvalidQueryException("Unknown field: " + m.field()));
            String key;
            String expr;
            String label;
            String type;
            boolean additive;
            if (f.isMeasure()) {
                key = f.name();
                expr = f.expression();
                label = f.label();
                type = f.type().json();
                additive = "count()".equals(expr) || expr.startsWith("countIf(");
            } else {
                String agg = m.agg();
                if (agg == null || !AGGREGATIONS.contains(agg) || !f.allowsAggregation(agg)) {
                    throw new InvalidQueryException("Aggregation '" + agg + "' is not allowed on " + f.label());
                }
                key = agg + "_" + f.name();
                expr = aggregateExpr(agg, f);
                label = aggLabel(agg) + " " + f.label();
                type = "countDistinct".equals(agg) || f.type() == FieldType.BOOLEAN ? "number" : f.type().json();
                additive = "sum".equals(agg);
            }
            if (m.display() != null) {
                String display = m.display();
                if (!DISPLAYS.containsKey(display)) {
                    throw new InvalidQueryException("Unknown way of showing a measure: " + display);
                }
                if (columns.isEmpty() || subtotals) {
                    throw new InvalidQueryException(DISPLAYS.get(display) + " needs a group column (and no subtotals)");
                }
                if ("percentOfGroup".equals(display) && columns.size() < 2) {
                    throw new InvalidQueryException("% of group needs at least two group columns");
                }
                expr = window(display, expr, dimensionExprs);
                key = display + "_" + key;
                label = DISPLAYS.get(display) + " " + label;
                type = "number";
                additive = false;
                windowed.add(key);
            }
            String k = key;
            if (out.stream().anyMatch(c -> c.key().equals(k))) {
                throw new InvalidQueryException("Measure listed twice: " + label);
            }
            out.add(new OutputColumn(key, label, type, "measure", additive));
            measureExprs.add(expr + " AS " + ident(key));
            if (windowed.contains(key)) {
                totalsExprs.add("NULL AS " + ident(key));
            } else {
                plainMeasureExprs.add(expr + " AS " + ident(key));
                totalsExprs.add(expr + " AS " + ident(key));
            }
        }
        selectExprs.addAll(measureExprs);
        String level = "grouping(" + String.join(", ", dimensionExprs) + ")";
        if (subtotals) {
            selectExprs.add(level + " AS `__level`");
            out.add(new OutputColumn("__level", "Subtotal level", "number", "meta"));
        }

        String where = hospitalCondition(scope, params);
        if (req.filter() != null) {
            int[] counter = {0};
            where += " AND (" + filterSql(req.filter(), ds, params, counter, 1) + ")";
        }
        String having = havingSql(req.having(), out, windowed, aggregate && !columns.isEmpty(), params);
        if (subtotals && !having.isEmpty()) {
            throw new InvalidQueryException("Conditions on totals cannot be combined with subtotals");
        }
        if (subtotals) {
            // ROLLUP also produces the grand total, which the totals row already shows
            having = " HAVING `__level` != " + ((1 << columns.size()) - 1);
        }

        String from = " FROM " + ident(ds.table()) + " FINAL WHERE " + where;
        String orderBy = subtotals ? subtotalOrder(req.sort(), ds, columns) : orderBy(req.sort(), ds, out, aggregate);
        int pageSize;
        int offset;
        if (exportRows != null) {
            pageSize = topN != null ? Math.min(topN, exportRows) : exportRows;   // a top-N download has its "Others" row added
            offset = 0;
        } else if (topN != null) {
            pageSize = topN;
            offset = 0;
        } else if (pivot != null) {
            pageSize = MAX_PIVOT_GROUPS;   // every group, spread across by the browser
            offset = 0;
        } else {
            pageSize = req.pageSize() == null ? DEFAULT_PAGE_SIZE : req.pageSize();
            offset = req.offset() == null ? 0 : req.offset();
            if (pageSize < 1 || pageSize > maxPageSize) {
                throw new InvalidQueryException("Page size must be between 1 and " + maxPageSize);
            }
            if (offset < 0 || offset > MAX_OFFSET) {
                throw new InvalidQueryException("Offset must be between 0 and " + MAX_OFFSET);
            }
        }
        params.put("limit", pageSize);
        params.put("offset", offset);

        String groupBy = aggregate && !dimensionExprs.isEmpty()
                ? (subtotals ? " GROUP BY ROLLUP(" : " GROUP BY ") + String.join(", ", dimensionExprs) + (subtotals ? ")" : "")
                : "";
        String extra = subtotals ? ", group_by_use_nulls = 1" : "";
        String dataSql = "SELECT " + String.join(", ", selectExprs) + from + groupBy + having + orderBy
                + " LIMIT :limit OFFSET :offset" + settings(scope) + extra;
        if (exportRows != null) {
            // downloads need more than the interactive profile allows; values come from code, never from the request
            dataSql += ", max_result_rows = " + (exportRows + 1) + ", max_execution_time = " + EXPORT_TIMEOUT_SECONDS;
        }
        String countSql;
        if (!aggregate) {
            countSql = "SELECT count()" + from + settings(scope);
        } else if (groupBy.isEmpty()) {
            countSql = "SELECT 1" + settings(scope);
        } else {
            String inner = subtotals ? level + " AS `__level`" : having.isEmpty() ? "1" : String.join(", ", plainMeasureExprs);
            countSql = "SELECT count() FROM (SELECT " + inner + from + groupBy + having + ")" + settings(scope) + extra;
        }
        String totalsSql = null;
        if (aggregate && having.isEmpty() || subtotals) {
            totalsSql = "SELECT " + String.join(", ", totalsExprs) + from + settings(scope);
        } else if (aggregate) {
            // with conditions on totals: add up the groups that passed (counts and sums only)
            List<String> outer = new ArrayList<>();
            for (OutputColumn c : out) {
                if ("measure".equals(c.role())) {
                    outer.add((c.additive() ? "sum(" + ident(c.key()) + ")" : "NULL") + " AS " + ident(c.key()));
                }
            }
            totalsSql = "SELECT " + String.join(", ", outer) + " FROM (SELECT " + String.join(", ", plainMeasureExprs) + from
                    + groupBy + having + ")" + settings(scope);
        }

        return new QueryPlan(aggregate ? "aggregate" : "detail", out, dataSql, countSql, totalsSql, params, pageSize, offset);
    }

    /** The dataset plus this report's own calculated fields, compiled in order (later ones may use earlier ones). */
    private Dataset withCalculated(QueryRequest req, Dataset base) {
        List<CalculatedSpec> calculated = req.calculated() == null ? List.of() : req.calculated();
        if (calculated.size() > MAX_CALCULATED) {
            throw new InvalidQueryException("At most " + MAX_CALCULATED + " calculated fields per report");
        }
        Dataset ds = base;
        int i = 0;
        for (CalculatedSpec c : calculated) {
            if (c == null || c.name() == null || !CALC_NAME.matcher(c.name()).matches()) {
                throw new InvalidQueryException("Calculated field names look like calc_total_fee");
            }
            if (ds.field(c.name()).isPresent()) {
                throw new InvalidQueryException("Calculated field " + c.name() + " is defined twice");
            }
            String label = c.label() == null ? "" : c.label().trim();
            if (label.isEmpty() || label.length() > 60) {
                throw new InvalidQueryException("Give the calculated field a name of 1-60 characters");
            }
            if (ds.fields().stream().anyMatch(f -> f.label().equalsIgnoreCase(label))) {
                throw new InvalidQueryException("A field called \"" + label + "\" already exists; choose another name");
            }
            try {
                ds = CalculatedFields.plus(ds, CalculatedFields.compile(c.name(), label, c.formula(), "report", null, ds,
                        "rc" + (i++), () -> LocalDate.now(clock)));
            } catch (InvalidQueryException e) {
                throw new InvalidQueryException("Calculated field \"" + label + "\": " + e.getMessage());
            }
        }
        return ds;
    }

    /** Ways of showing a measure (window functions over the grouped result), with their label prefix. */
    private static final Map<String, String> DISPLAYS = Map.of(
            "percentOfTotal", "% of total", "percentOfGroup", "% of group", "runningTotal", "Running total",
            "rank", "Rank by", "change", "Change in", "percentChange", "% change in");

    /** SQL of a measure shown another way; groups are ordered by the last group column (e.g. the month). */
    private static String window(String display, String e, List<String> dims) {
        String partitionAll = dims.size() > 1 ? "PARTITION BY " + String.join(", ", dims.subList(0, dims.size() - 1)) + " " : "";
        String order = "ORDER BY " + dims.get(dims.size() - 1) + " ASC";
        String previous = "lagInFrame(toNullable(" + e + ")) OVER (" + partitionAll + order
                + " ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)";
        return switch (display) {
            case "percentOfTotal" -> "round(100 * (" + e + ") / nullIf(sum(" + e + ") OVER (), 0), 2)";
            case "percentOfGroup" -> "round(100 * (" + e + ") / nullIf(sum(" + e + ") OVER (PARTITION BY " + dims.get(0) + "), 0), 2)";
            case "runningTotal" -> "sum(" + e + ") OVER (" + partitionAll + order + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)";
            case "rank" -> "rank() OVER (ORDER BY " + e + " DESC)";
            case "change" -> "(" + e + ") - " + previous;
            default -> "round(100 * ((" + e + ") - " + previous + ") / nullIf(" + previous + ", 0), 2)";
        };
    }

    /** Conditions on totals, e.g. departments with more than 500 patients (numbers only, values bound). */
    private static String havingSql(List<FilterNode> having, List<OutputColumn> out, Set<String> windowed, boolean grouped,
                                    Map<String, Object> params) {
        if (having == null || having.isEmpty()) {
            return "";
        }
        if (!grouped) {
            throw new InvalidQueryException("Conditions on totals need a group column and a measure");
        }
        if (having.size() > 10) {
            throw new InvalidQueryException("At most 10 conditions on totals");
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (FilterNode h : having) {
            OutputColumn c = out.stream().filter(o -> "measure".equals(o.role()) && o.key().equals(h.field())).findFirst()
                    .orElseThrow(() -> new InvalidQueryException("Conditions on totals must use a measure of the report"));
            if (windowed.contains(c.key())) {
                throw new InvalidQueryException(c.label() + " cannot be used in a condition");
            }
            String p = "h" + (i++);
            Object v = h.value();
            switch (h.op() == null ? "" : h.op()) {
                case "eq", "neq", "gt", "gte", "lt", "lte" -> {
                    params.put(p, havingNumber(v, c));
                    parts.add(ident(c.key()) + " " + comparator(h.op()) + " :" + p);
                }
                case "between" -> {
                    if (!(v instanceof List<?> range) || range.size() != 2) {
                        throw new InvalidQueryException("'between' needs exactly two values");
                    }
                    params.put(p + "a", havingNumber(range.get(0), c));
                    params.put(p + "b", havingNumber(range.get(1), c));
                    parts.add(ident(c.key()) + " BETWEEN :" + p + "a AND :" + p + "b");
                }
                default -> throw new InvalidQueryException("Conditions on totals compare numbers (=, <, >, between)");
            }
        }
        return " HAVING " + String.join(" AND ", parts);
    }

    private static BigDecimal havingNumber(Object v, OutputColumn c) {
        try {
            return v instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new InvalidQueryException("Enter a number for " + c.label());
        }
    }

    /** With subtotals rows are ordered by the group columns, each group's subtotal right after it. */
    private String subtotalOrder(List<SortSpec> sort, Dataset ds, List<String> columns) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String c = columns.get(i);
            String dir = " ASC";
            if (sort != null) {
                for (SortSpec s : sort) {
                    if (s.field().equals(c)) {
                        dir = direction(s.dir());
                    }
                }
            }
            // a subtotal comes after the detail rows of its group, whatever the direction: rolled-up columns come
            // back as '' (not NULL) from ClickHouse, so NULLS LAST alone would put the subtotal first
            if (i > 0) {
                parts.add("grouping(" + sortExpr(c, ds) + ") ASC");
            }
            parts.add(sortExpr(c, ds) + dir + " NULLS LAST");
        }
        if (sort != null && sort.stream().anyMatch(s -> !columns.contains(s.field()))) {
            throw new InvalidQueryException("With subtotals, sort by the group columns only");
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    /** Top values of a field for filter drop-downs, optionally matching a typed prefix/fragment. */
    public QueryPlan suggestions(Dataset ds, String fieldName, String q, int limit, Collection<Integer> hospitals) {
        List<Integer> scope = scope(hospitals);
        Field f = dimension(ds, fieldName);
        if (!f.suggest()) {
            throw new InvalidQueryException("No value suggestions for " + f.label());
        }
        Map<String, Object> params = new LinkedHashMap<>(f.params());
        String col = colExpr(f);   // masked and hidden values stay masked and hidden in suggestions too
        String where = hospitalCondition(scope, params);
        if (q != null && !q.isBlank()) {
            where += " AND lower(" + col + ") LIKE :q";
            params.put("q", "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%");
        }
        params.put("limit", limit);
        String sql = "SELECT " + col + " AS v, count() AS n FROM " + ident(ds.table()) + " FINAL WHERE " + where
                + " GROUP BY v ORDER BY n DESC, v LIMIT :limit" + settings(scope);
        return new QueryPlan("suggest", List.of(new OutputColumn("v", f.label(), f.type().json(), "dimension")),
                sql, null, null, params, limit, 0);
    }
    // ---------------------------------------------------------------- hospital scope

    /** The hospitals a query may read; an empty scope is refused rather than meaning "all". */
    private static List<Integer> scope(Collection<Integer> hospitals) {
        if (hospitals == null || hospitals.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You have no hospital whose reports you may see");
        }
        return hospitals.stream().sorted().toList();
    }

    /** Every query: latest version, not deleted, and only the user's hospitals (bound parameter). */
    private static String hospitalCondition(List<Integer> scope, Map<String, Object> params) {
        params.put("scope_hospitals", scope);
        return "is_deleted = 0 AND `hospital_id` IN (:scope_hospitals)";
    }

    /**
     * Second line of defence: the same hospital list as a query setting, checked by the ClickHouse row
     * filter of the reporting account (infra/clickhouse/users.d). Integers only, built here, never user text.
     */
    static String settings(List<Integer> scope) {
        return " SETTINGS SQL_hospitals = '" + scope.stream().map(String::valueOf).collect(Collectors.joining(",")) + "'";
    }

    // ---------------------------------------------------------------- helpers

    private static Field dimension(Dataset ds, String name) {
        Field f = ds.field(name).filter(x -> !x.hidden())   // internal fields (patient_id) only inside totals
                .orElseThrow(() -> new InvalidQueryException("Unknown field: " + name));
        if (f.isMeasure()) {
            throw new InvalidQueryException(f.label() + " is a measure, not a column");
        }
        return f;
    }

    private static String aggregateExpr(String agg, Field f) {
        String col = colExpr(f);   // calculated, custom, masked or hidden fields aggregate their expression
        int scale = f.scale() == null ? 2 : f.scale();
        return switch (agg) {
            case "sum" -> "sum(" + col + ")";
            case "avg" -> "round(avg(" + col + "), " + scale + ")";
            case "min" -> "min(" + col + ")";
            case "max" -> "max(" + col + ")";
            case "countDistinct" -> "uniqExact(" + col + ")";
            default -> throw new InvalidQueryException("Unknown aggregation " + agg);
        };
    }

    private static String aggLabel(String agg) {
        return switch (agg) {
            case "sum" -> "Total";
            case "avg" -> "Average";
            case "min" -> "Lowest";
            case "max" -> "Highest";
            default -> "Distinct";
        };
    }

    private String orderBy(List<SortSpec> sort, Dataset ds, List<OutputColumn> out, boolean aggregate) {
        List<String> parts = new ArrayList<>();
        if (sort != null && !sort.isEmpty()) {
            for (SortSpec s : sort) {
                boolean known = out.stream().anyMatch(c -> c.key().equals(s.field()))
                        || (!aggregate && ds.field(s.field()).filter(f -> !f.isMeasure() && !f.hidden()).isPresent());
                if (!known) {
                    throw new InvalidQueryException("Cannot sort by " + s.field());
                }
                parts.add(sortExpr(s.field(), ds) + direction(s.dir()));
            }
        } else if (aggregate) {
            out.stream().filter(c -> "measure".equals(c.role())).findFirst()
                    .ifPresent(c -> parts.add(ident(c.key()) + " DESC"));
        } else if (ds.defaultSort() != null) {
            // only fields this user has: sorting by a field hidden from them would reveal its order
            ds.defaultSort().stream().filter(s -> ds.field(s.field()).isPresent())
                    .forEach(s -> parts.add(sortExpr(s.field(), ds) + direction(s.dir())));
        }
        // a unique tie-breaker, so rows with equal sort values keep their place from page to page
        if (!parts.isEmpty() || aggregate) {
            List<String> keys = aggregate
                    ? out.stream().filter(c -> "dimension".equals(c.role())).map(c -> sortExpr(c.key(), ds)).toList()
                    : ds.field("patient_id").isPresent() ? List.of(ident("patient_id")) : List.of();
            for (String k : keys) {
                if (parts.stream().noneMatch(p -> p.startsWith(k + " "))) {
                    parts.add(k + " ASC");
                }
            }
        }
        return parts.isEmpty() ? "" : " ORDER BY " + String.join(", ", parts);
    }

    private static String direction(String dir) {
        if (dir == null || dir.equalsIgnoreCase("asc")) {
            return " ASC";
        }
        if (dir.equalsIgnoreCase("desc")) {
            return " DESC";
        }
        throw new InvalidQueryException("Sort direction must be asc or desc");
    }

    private String filterSql(FilterNode node, Dataset ds, Map<String, Object> params, int[] counter, int depth) {
        if (depth > MAX_FILTER_DEPTH) {
            throw new InvalidQueryException("Filters are nested too deeply");
        }
        boolean isAnd = node.and() != null && !node.and().isEmpty();
        boolean isOr = node.or() != null && !node.or().isEmpty();
        if (isAnd || isOr) {
            if (node.field() != null || (isAnd && isOr)) {
                throw new InvalidQueryException("A filter group must contain only 'and' or only 'or'");
            }
            List<String> parts = new ArrayList<>();
            for (FilterNode child : isAnd ? node.and() : node.or()) {
                parts.add("(" + filterSql(child, ds, params, counter, depth + 1) + ")");
            }
            return String.join(isAnd ? " AND " : " OR ", parts);
        }
        if (++counter[0] > MAX_CONDITIONS) {
            throw new InvalidQueryException("At most " + MAX_CONDITIONS + " filter conditions");
        }
        Field f = dimension(ds, node.field());
        String op = node.op();
        if (op == null || !f.type().allows(op)) {
            throw new InvalidQueryException("Operator '" + op + "' is not allowed on " + f.label());
        }
        String col = colExpr(f);
        String p = "p" + counter[0];
        Object v = node.value();
        switch (op) {
            case "eq", "neq", "gt", "gte", "lt", "lte" -> {
                params.put(p, scalar(f, v));
                return col + " " + comparator(op) + " :" + p;
            }
            case "in", "notIn" -> {
                List<Object> values = list(f, v);
                if (values.isEmpty() || values.size() > MAX_IN_VALUES) {
                    throw new InvalidQueryException("'" + op + "' needs 1 to " + MAX_IN_VALUES + " values");
                }
                params.put(p, values);
                return col + ("in".equals(op) ? " IN (:" : " NOT IN (:") + p + ")";
            }
            case "between" -> {
                List<Object> range = list(f, v);
                if (range.size() != 2) {
                    throw new InvalidQueryException("'between' needs exactly two values");
                }
                params.put(p + "a", range.get(0));
                params.put(p + "b", range.get(1));
                return col + " BETWEEN :" + p + "a AND :" + p + "b";
            }
            case "contains", "startsWith" -> {
                String text = text(v).toLowerCase(Locale.ROOT);
                if (text.isEmpty()) {
                    throw new InvalidQueryException("Enter text to search for in " + f.label());
                }
                params.put(p, ("contains".equals(op) ? "%" : "") + escapeLike(text) + "%");
                return "lower(" + col + ") LIKE :" + p;
            }
            case "relative" -> {
                LocalDate[] range = relativeRange(text(v));
                params.put(p + "a", range[0]);
                params.put(p + "b", range[1]);
                return col + " BETWEEN :" + p + "a AND :" + p + "b";
            }
            case "isTrue" -> {
                return col + " = 1";
            }
            case "isFalse" -> {
                return col + " = 0";
            }
            default -> throw new InvalidQueryException("Unknown operator " + op);
        }
    }

    private static String comparator(String op) {
        return switch (op) {
            case "eq" -> "=";
            case "neq" -> "!=";
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            default -> "<=";
        };
    }

    private static Object scalar(Field f, Object v) {
        if (v == null || v instanceof List<?> || v instanceof Map<?, ?>) {
            throw new InvalidQueryException("Enter a single value for " + f.label());
        }
        return switch (f.type()) {
            case STRING -> text(v);
            case NUMBER, MONEY -> number(f, v);
            case DATE -> date(f, v);
            case BOOLEAN -> throw new InvalidQueryException("Use isTrue/isFalse for " + f.label());
        };
    }

    private static List<Object> list(Field f, Object v) {
        if (!(v instanceof List<?> raw)) {
            throw new InvalidQueryException("Enter a list of values for " + f.label());
        }
        List<Object> out = new ArrayList<>();
        for (Object item : raw) {
            out.add(scalar(f, item));
        }
        return out;
    }

    private static String text(Object v) {
        if (!(v instanceof String s)) {
            throw new InvalidQueryException("Expected text but got " + (v == null ? "nothing" : v));
        }
        if (s.length() > 200) {
            throw new InvalidQueryException("Filter text is too long");
        }
        return s.trim();
    }

    private static BigDecimal number(Field f, Object v) {
        try {
            return v instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(text(v));
        } catch (NumberFormatException e) {
            throw new InvalidQueryException("Enter a valid number for " + f.label());
        }
    }

    private static LocalDate date(Field f, Object v) {
        try {
            return LocalDate.parse(text(v));
        } catch (DateTimeParseException e) {
            throw new InvalidQueryException("Enter a date as YYYY-MM-DD for " + f.label());
        }
    }

    /** Inclusive [from, to] dates for a relative period, in the application time zone. */
    LocalDate[] relativeRange(String period) {
        LocalDate today = LocalDate.now(clock);
        return switch (period) {
            case "TODAY" -> new LocalDate[] {today, today};
            case "YESTERDAY" -> new LocalDate[] {today.minusDays(1), today.minusDays(1)};
            case "LAST_7_DAYS" -> new LocalDate[] {today.minusDays(6), today};
            case "LAST_30_DAYS" -> new LocalDate[] {today.minusDays(29), today};
            case "THIS_MONTH" -> new LocalDate[] {today.withDayOfMonth(1), today};
            case "LAST_MONTH" -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                yield new LocalDate[] {first, first.withDayOfMonth(first.lengthOfMonth())};
            }
            case "THIS_YEAR" -> new LocalDate[] {today.withDayOfYear(1), today};
            case "LAST_YEAR" -> {
                LocalDate first = today.withDayOfYear(1).minusYears(1);
                yield new LocalDate[] {first, first.withDayOfYear(first.lengthOfYear())};
            }
            default -> throw new InvalidQueryException("Unknown relative period " + period);
        };
    }

    /** LIKE wildcards typed by users match literally. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** SQL for a dimension: its catalogue expression (e.g. toStartOfMonth(...)) or the plain column. */
    private static String colExpr(Field f) {
        return f.expression() != null ? f.expression() : ident(f.name());
    }

    /** Sort keys: dataset fields by their expression, measure outputs by their alias. */
    private static String sortExpr(String key, Dataset ds) {
        return ds.field(key).filter(f -> !f.isMeasure()).map(QueryBuilder::colExpr).orElse(ident(key));
    }

    /** Identifiers come only from the validated catalogue; backticks guard against keyword clashes. */
    private static String ident(String name) {
        return "`" + name + "`";
    }
}
