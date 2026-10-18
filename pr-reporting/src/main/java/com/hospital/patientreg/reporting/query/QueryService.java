package com.hospital.patientreg.reporting.query;

import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.custom.EffectiveCatalog;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Runs report definitions against the analytics store (never the live database). */
@Service
public class QueryService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final EffectiveCatalog catalog;
    private final QueryBuilder builder;
    private final NamedParameterJdbcTemplate analytics;

    public QueryService(EffectiveCatalog catalog, QueryBuilder builder,
                        @Qualifier("analyticsJdbc") NamedParameterJdbcTemplate analytics) {
        this.catalog = catalog;
        this.builder = builder;
        this.analytics = analytics;
    }

    /**
     * The dataset as the signed-in user may work with it: standard fields with their hospitals' settings,
     * custom fields and shared calculated fields (stage 5b).
     */
    public Dataset dataset(String id) {
        return catalog.forUser(id, analyticsScope());
    }

    /** Patient rows a user without the download permission may page through (screen use, not bulk copying). */
    public static final int BROWSE_LIMIT_WITHOUT_EXPORT = 5_000;

    /**
     * Without EXPORT in every hospital of the scope, a list of patients can be paged only to row 5,000: otherwise
     * paging would copy all patients past the download permission, its limits and its audit (review finding).
     */
    static void browseLimit(QueryRequest request, Set<Integer> scope) {
        boolean detail = request.measures() == null || request.measures().isEmpty();
        if (!detail || TenantContext.require().hospitalsWith(Permission.EXPORT).containsAll(scope)) {
            return;
        }
        long offset = request.offset() == null ? 0 : request.offset();
        long size = request.pageSize() == null ? 50 : request.pageSize();
        if (offset + size > BROWSE_LIMIT_WITHOUT_EXPORT) {
            throw new InvalidQueryException("Without the download permission a patient list can be paged to row "
                    + String.format(java.util.Locale.ROOT, "%,d", BROWSE_LIMIT_WITHOUT_EXPORT) + "; add filters to narrow it down");
        }
    }

    /** Hospitals whose analytics the signed-in user may see (403 if none). */
    public static Set<Integer> analyticsScope() {
        return TenantContext.require().requireAnywhere(Permission.ANALYTICS);
    }

    public QueryResult run(QueryRequest request) {
        long start = System.nanoTime();
        Set<Integer> scope = analyticsScope();
        browseLimit(request, scope);
        Dataset ds = dataset(request.dataset());
        QueryPlan plan = builder.build(request, ds, scope);
        int width = plan.columns().size();

        List<List<Object>> rows = analytics.query(plan.dataSql(), plan.params(), (rs, i) -> row(rs, width));
        Long total = analytics.queryForObject(plan.countSql(), plan.params(), Long.class);
        List<Object> totals = plan.totalsSql() == null ? null
                : analytics.queryForObject(plan.totalsSql(), plan.params(), (rs, i) -> row(rs, rs.getMetaData().getColumnCount()));
        LocalDateTime asOf = dataAsOf(ds, scope);

        long count = total == null ? 0 : total;
        List<Object> others = request.topN() != null && count > rows.size() && totals != null
                ? others(plan.columns(), rows, totals) : null;
        Boolean truncated = request.pivot() != null && count > rows.size() ? Boolean.TRUE : null;

        long ms = (System.nanoTime() - start) / 1_000_000;
        return new QueryResult(ds.id(), plan.mode(), plan.columns(), rows, totals, count,
                plan.pageSize(), plan.offset(), asOf, ms, others, truncated);
    }

    /** The groups after the top N, added up: totals minus the shown groups, for counts and sums. */
    public static List<Object> others(List<QueryPlan.OutputColumn> columns, List<List<Object>> rows, List<Object> totals) {
        List<Object> out = new ArrayList<>();
        int measure = 0;
        for (int c = 0; c < columns.size(); c++) {
            QueryPlan.OutputColumn col = columns.get(c);
            if (!"measure".equals(col.role())) {
                out.add(c == 0 ? "Others" : null);
                continue;
            }
            Object total = measure < totals.size() ? totals.get(measure) : null;
            measure++;
            if (!col.additive() || !(total instanceof Number t)) {
                out.add(null);
                continue;
            }
            java.math.BigDecimal rest = new java.math.BigDecimal(t.toString());
            for (List<Object> r : rows) {
                if (r.get(c) instanceof Number n) {
                    rest = rest.subtract(new java.math.BigDecimal(n.toString()));
                }
            }
            out.add(rest.stripTrailingZeros().scale() <= 0 ? (Object) rest.longValue() : rest);
        }
        return out;
    }

    public List<Map<String, Object>> suggestions(String datasetId, String field, String q) {
        Dataset ds = dataset(datasetId);
        QueryPlan plan = builder.suggestions(ds, field, q, 50, analyticsScope());
        return analytics.query(plan.dataSql(), plan.params(),
                (rs, i) -> Map.of("value", normalise(rs.getObject("v")), "count", rs.getLong("n")));
    }

    /**
     * How current the analytics copy is. With real-time sync (Debezium) a heartbeat arrives every few
     * seconds even when nobody registers, so its time is used; otherwise the commit time of the newest
     * change that has reached the analytics store.
     */
    public LocalDateTime dataAsOf(Dataset ds, Set<Integer> scope) {
        List<Integer> hospitals = scope.stream().sorted().toList();
        LocalDateTime newestChange = asTime(analytics.queryForObject(
                "SELECT max(`" + ds.freshnessColumn() + "`) FROM `" + ds.table() + "` WHERE `hospital_id` IN (:h)"
                        + QueryBuilder.settings(hospitals), Map.of("h", hospitals), (rs, i) -> rs.getObject(1)));
        // generated or imported rows can carry future timestamps; data cannot be newer than now
        LocalDateTime now = LocalDateTime.now(IST);
        if (newestChange != null && newestChange.isAfter(now)) {
            newestChange = now;
        }
        LocalDateTime heartbeat = null;
        try {
            heartbeat = asTime(analytics.getJdbcTemplate().queryForObject(
                    "SELECT max(ts) FROM pipeline_heartbeat", (rs, i) -> rs.getObject(1)));
        } catch (DataAccessException noRealtimePipeline) {
            // table exists only when real-time sync is set up (docker compose --profile cdc)
        }
        if (heartbeat == null || heartbeat.getYear() < 2000) {
            return newestChange;
        }
        return newestChange == null || heartbeat.isAfter(newestChange) ? heartbeat : newestChange;
    }

    private static LocalDateTime asTime(Object v) {
        return normalise(v) instanceof LocalDateTime t ? t : null;
    }

    private static List<Object> row(ResultSet rs, int width) throws SQLException {
        List<Object> row = new ArrayList<>(width);
        for (int c = 1; c <= width; c++) {
            row.add(normalise(rs.getObject(c)));
        }
        return row;
    }

    /** JDBC values to JSON-friendly Java types (dates as ISO, unsigned ints as numbers, times in IST). */
    public static Object normalise(Object v) {
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime();
        }
        if (v instanceof OffsetDateTime o) {
            return o.atZoneSameInstant(IST).toLocalDateTime();
        }
        if (v instanceof ZonedDateTime z) {
            return z.withZoneSameInstant(IST).toLocalDateTime();
        }
        if (v instanceof BigInteger b && b.bitLength() < 63) {
            return b.longValue();
        }
        // ClickHouse returns nan for an average over zero rows: show it as empty, not as the text "NaN"
        if ((v instanceof Double d && (d.isNaN() || d.isInfinite())) || (v instanceof Float f && (f.isNaN() || f.isInfinite()))) {
            return null;
        }
        // floating-point results (averages, formulas with decimals) to 4 decimals: 100.49999999999999 -> 100.5
        if (v instanceof Double || v instanceof Float) {
            java.math.BigDecimal b = java.math.BigDecimal.valueOf(((Number) v).doubleValue()).setScale(4, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            return b.scale() <= 0 ? (Object) b.setScale(0).doubleValue() : b;
        }
        return v;
    }
}
