package com.hospital.patientreg.reporting.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hospital.patientreg.reporting.query.QueryRequest.MeasureSpec;
import com.hospital.patientreg.reporting.query.QueryRequest.SortSpec;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.DatasetCatalog;
import com.hospital.patientreg.reporting.query.QueryPlan.OutputColumn;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QueryBuilderTest {

    private static Dataset patients;
    /** The signed-in user's hospitals: only hospital 1. */
    private static final List<Integer> H1 = List.of(1);
    /** Fixed "now": 15 Oct 2026, 10:00 IST. */
    private final QueryBuilder builder = new QueryBuilder(
            Clock.fixed(Instant.parse("2026-10-15T04:30:00Z"), ZoneId.of("Asia/Kolkata")), 500);

    @BeforeAll
    static void loadCatalogue() throws Exception {
        patients = new DatasetCatalog(JsonMapper.builder().addModule(new JavaTimeModule()).build())
                .find("patients").orElseThrow();
    }

    private static QueryRequest detail(List<String> columns, FilterNode filter) {
        return new QueryRequest("patients", columns, null, filter, null, null, null);
    }

    @Test
    void detailListReadsLatestVersionsWithDefaultOrderAndPaging() {
        QueryPlan plan = builder.build(detail(List.of("registration_no", "patient_name", "gender"), null), patients, H1);
        assertThat(plan.mode()).isEqualTo("detail");
        assertThat(plan.dataSql())
                .startsWith("SELECT `registration_no`, `patient_name`, `gender` FROM `patient_report` FINAL WHERE is_deleted = 0 AND `hospital_id` IN (:scope_hospitals)")
                .contains("ORDER BY `registration_date` DESC, `patient_id` DESC")
                .endsWith("LIMIT :limit OFFSET :offset SETTINGS SQL_hospitals = '1'");
        assertThat(plan.countSql()).isEqualTo(
                "SELECT count() FROM `patient_report` FINAL WHERE is_deleted = 0 AND `hospital_id` IN (:scope_hospitals)"
                + " SETTINGS SQL_hospitals = '1'");
        assertThat(plan.params()).containsEntry("scope_hospitals", List.of(1));
        assertThat(plan.totalsSql()).isNull();
        assertThat(plan.params()).containsEntry("limit", 50).containsEntry("offset", 0);
    }

    @Test
    void aggregateGroupsAndComputesTotals() {
        QueryRequest req = new QueryRequest("patients", List.of("gender"),
                List.of(new MeasureSpec("patients", null), new MeasureSpec("registration_fee", "sum"),
                        new MeasureSpec("age_years", "avg")),
                null, null, null, null);
        QueryPlan plan = builder.build(req, patients, H1);
        assertThat(plan.mode()).isEqualTo("aggregate");
        assertThat(plan.dataSql()).contains("count() AS `patients`", "sum(`registration_fee`) AS `sum_registration_fee`",
                "round(avg(`age_years`), 1) AS `avg_age_years`", "GROUP BY `gender`", "ORDER BY `patients` DESC");
        assertThat(plan.countSql()).startsWith("SELECT count() FROM (SELECT 1 FROM `patient_report` FINAL");
        assertThat(plan.totalsSql()).doesNotContain("GROUP BY").contains("count() AS `patients`");
        assertThat(plan.columns()).extracting("key").containsExactly("gender", "patients", "sum_registration_fee", "avg_age_years");
    }

    @Test
    void userValuesAreBoundNeverWrittenIntoSql() {
        String evil = "x'; DROP TABLE patient_report; --";
        QueryPlan plan = builder.build(detail(List.of("patient_name"),
                FilterNode.and(FilterNode.condition("city", "eq", evil),
                        FilterNode.condition("state", "in", List.of("Goa", evil)))), patients, H1);
        assertThat(plan.dataSql()).doesNotContain("DROP").contains("`city` = :p1", "`state` IN (:p2)");
        assertThat(plan.params()).containsEntry("p1", evil);
    }

    @Test
    void onlyCatalogueFieldsOperatorsAndAggregationsAreAccepted() {
        assertThatThrownBy(() -> builder.build(detail(List.of("aadhaar"), null), patients, H1))
                .isInstanceOf(InvalidQueryException.class).hasMessageContaining("Unknown field");
        assertThatThrownBy(() -> builder.build(detail(List.of("gender; DROP TABLE x"), null), patients, H1))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> builder.build(detail(List.of("gender"),
                FilterNode.condition("registration_date", "contains", "2026")), patients, H1))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> builder.build(new QueryRequest("patients", List.of("city"),
                List.of(new MeasureSpec("gender", "sum")), null, null, null, null), patients, H1))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> builder.build(detail(List.of("patients"), null), patients, H1))
                .hasMessageContaining("is a measure");
        assertThatThrownBy(() -> builder.build(new QueryRequest("patients", List.of("gender"), null, null,
                List.of(new SortSpec("gender; DROP", "asc")), null, null), patients, H1))
                .hasMessageContaining("Cannot sort");
    }

    @Test
    void containsIsCaseInsensitiveAndEscapesWildcards() {
        QueryPlan plan = builder.build(detail(List.of("patient_name"),
                FilterNode.condition("patient_name", "contains", "Rao_50%")), patients, H1);
        assertThat(plan.dataSql()).contains("lower(`patient_name`) LIKE :p1");
        assertThat(plan.params()).containsEntry("p1", "%rao\\_50\\%%");
    }

    @Test
    void relativeDatesAreComputedInIst() {
        QueryPlan plan = builder.build(detail(List.of("registration_no"),
                FilterNode.condition("registration_date", "relative", "LAST_30_DAYS")), patients, H1);
        assertThat(plan.params()).containsEntry("p1a", LocalDate.of(2026, 9, 16)).containsEntry("p1b", LocalDate.of(2026, 10, 15));
        assertThat(builder.relativeRange("LAST_MONTH")).containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void typedValuesAreValidated() {
        assertThatThrownBy(() -> builder.build(detail(List.of("gender"),
                FilterNode.condition("registration_date", "gte", "15-10-2026")), patients, H1))
                .hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> builder.build(detail(List.of("gender"),
                FilterNode.condition("age_years", "between", List.of(10))), patients, H1))
                .hasMessageContaining("exactly two");
        QueryPlan plan = builder.build(detail(List.of("gender"), FilterNode.condition("is_insured", "isTrue", null)), patients, H1);
        assertThat(plan.dataSql()).contains("`is_insured` = 1");
    }

    @Test
    void limitsAreEnforced() {
        assertThatThrownBy(() -> builder.build(new QueryRequest("patients", List.of("gender"), null, null, null, 5000, null), patients, H1))
                .hasMessageContaining("Page size");
        FilterNode deep = FilterNode.condition("gender", "eq", "Male");
        for (int i = 0; i < 5; i++) {
            deep = FilterNode.and(deep);
        }
        FilterNode tooDeep = deep;
        assertThatThrownBy(() -> builder.build(detail(List.of("gender"), tooDeep), patients, H1))
                .hasMessageContaining("nested too deeply");
    }

    @Test
    void suggestionsOnlyForSuggestFields() {
        QueryPlan plan = builder.suggestions(patients, "city", "pu", 50, H1);
        assertThat(plan.dataSql()).contains("lower(`city`) LIKE :q", "GROUP BY v ORDER BY n DESC");
        assertThatThrownBy(() -> builder.suggestions(patients, "patient_name", null, 50, H1))
                .hasMessageContaining("No value suggestions");
    }

    /** Multi-hospital isolation (architecture 10.1): every SQL statement is limited to the given hospitals. */
    @Test
    void everyStatementIsLimitedToTheUsersHospitals() {
        QueryRequest req = new QueryRequest("patients", List.of("gender"), List.of(new MeasureSpec("patients", null)),
                null, null, null, null);
        QueryPlan plan = builder.build(req, patients, List.of(3, 1));
        for (String sql : List.of(plan.dataSql(), plan.countSql(), plan.totalsSql())) {
            assertThat(sql).contains("`hospital_id` IN (:scope_hospitals)").endsWith("SETTINGS SQL_hospitals = '1,3'");
        }
        assertThat(plan.params()).containsEntry("scope_hospitals", List.of(1, 3));
        QueryPlan export = builder.buildExport(req, patients, 1000, List.of(2));
        assertThat(export.dataSql()).contains("SETTINGS SQL_hospitals = '2', max_result_rows = 1001");
        assertThat(builder.suggestions(patients, "city", null, 50, List.of(2)).dataSql())
                .contains("`hospital_id` IN (:scope_hospitals)").endsWith("SETTINGS SQL_hospitals = '2'");
    }

    @Test
    void noHospitalMeansNoQuery() {
        QueryRequest req = detail(List.of("gender"), null);
        assertThatThrownBy(() -> builder.build(req, patients, List.of())).hasMessageContaining("no hospital");
        assertThatThrownBy(() -> builder.build(req, patients, null)).hasMessageContaining("no hospital");
        assertThatThrownBy(() -> builder.suggestions(patients, "city", null, 50, List.of())).hasMessageContaining("no hospital");
    }

    /** A filter on the hospital field cannot widen the scope: it is ANDed with the forced condition. */
    @Test
    void hospitalFilterOnlyNarrows() {
        QueryPlan plan = builder.build(detail(List.of("hospital_name"),
                FilterNode.condition("hospital_code", "in", List.of("H001", "H002"))), patients, H1);
        assertThat(plan.dataSql()).contains("WHERE is_deleted = 0 AND `hospital_id` IN (:scope_hospitals) AND (");
    }

    // ---------------------------------------------------------------- stage 5b

    private static QueryRequest grouped(List<String> columns, List<MeasureSpec> measures, List<QueryRequest.CalculatedSpec> calc,
                                        Boolean subtotals, Integer topN, List<FilterNode> having, String pivot) {
        return new QueryRequest("patients", columns, measures, null, null, null, null, calc, subtotals, topN, having, pivot);
    }

    @Test
    void reportsOwnCalculatedFieldsCanBeShownGroupedFilteredAndTotalled() {
        List<QueryRequest.CalculatedSpec> calc = List.of(
                new QueryRequest.CalculatedSpec("calc_age_group", "Age group", "BUCKET([Age], 18, 60)"),
                new QueryRequest.CalculatedSpec("calc_insured_pct", "Insured %", "ROUND(COUNT_IF([Insured]) / COUNT() * 100, 1)"));
        QueryRequest req = new QueryRequest("patients", List.of("calc_age_group"),
                List.of(new MeasureSpec("patients", null), new MeasureSpec("calc_insured_pct", null)),
                FilterNode.condition("calc_age_group", "eq", "60 and above"), null, null, null, calc, null, null, null, null);
        QueryPlan plan = builder.build(req, patients, H1);
        assertThat(plan.dataSql()).contains("multiIf(`age_years` < 18, :rc0_1, `age_years` < 60, :rc0_2, :rc0_3) AS `calc_age_group`")
                .contains("GROUP BY multiIf(").contains("AS `calc_insured_pct`").contains("countIf(`is_insured`)");
        assertThat(plan.params()).containsEntry("rc0_1", "below 18").containsEntry("rc0_3", "60 and above");
        assertThat(plan.columns().stream().map(OutputColumn::label)).contains("Age group", "Insured %");
        assertThatThrownBy(() -> builder.build(grouped(List.of("gender"), List.of(new MeasureSpec("patients", null)),
                List.of(new QueryRequest.CalculatedSpec("bad name", "X", "1")), null, null, null, null), patients, H1))
                .hasMessageContaining("calc_");
        assertThatThrownBy(() -> builder.build(grouped(List.of("gender"), List.of(new MeasureSpec("patients", null)),
                List.of(new QueryRequest.CalculatedSpec("calc_x", "Gender", "1")), null, null, null, null), patients, H1))
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> builder.build(grouped(List.of("gender"), List.of(new MeasureSpec("patients", null)),
                List.of(new QueryRequest.CalculatedSpec("calc_x", "X", "[Aadhaar]")), null, null, null, null), patients, H1))
                .hasMessageContaining("Calculated field \"X\"").hasMessageContaining("Unknown field");
    }

    @Test
    void measuresCanBeShownAsShareRunningTotalRankAndChange() {
        QueryPlan plan = builder.build(grouped(List.of("dept_name", "registration_month"), List.of(
                new MeasureSpec("patients", null, "percentOfTotal"), new MeasureSpec("patients", null, "percentOfGroup"),
                new MeasureSpec("patients", null, "runningTotal"), new MeasureSpec("patients", null, "rank"),
                new MeasureSpec("registration_fee", "sum", "change"), new MeasureSpec("registration_fee", "sum", "percentChange")),
                null, null, null, null, null), patients, H1);
        assertThat(plan.dataSql()).contains("round(100 * (count()) / nullIf(sum(count()) OVER (), 0), 2) AS `percentOfTotal_patients`")
                .contains("OVER (PARTITION BY `dept_name`)")
                .contains("sum(count()) OVER (PARTITION BY `dept_name` ORDER BY toStartOfMonth(`registration_date`) ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)")
                .contains("rank() OVER (ORDER BY count() DESC)")
                .contains("lagInFrame(toNullable(sum(`registration_fee`)))");
        assertThat(plan.totalsSql()).contains("NULL AS `percentOfTotal_patients`");
        assertThatThrownBy(() -> builder.build(grouped(List.of("gender"), List.of(new MeasureSpec("patients", null, "percentOfGroup")),
                null, null, null, null, null), patients, H1)).hasMessageContaining("two group columns");
    }

    @Test
    void subtotalsRollUpEachGroup() {
        QueryPlan plan = builder.build(grouped(List.of("dept_name", "gender"), List.of(new MeasureSpec("patients", null)),
                null, true, null, null, null), patients, H1);
        assertThat(plan.dataSql()).contains("GROUP BY ROLLUP(`dept_name`, `gender`)").contains("grouping(`dept_name`, `gender`) AS `__level`")
                .contains("HAVING `__level` != 3").contains("ORDER BY `dept_name` ASC NULLS LAST, grouping(`gender`) ASC, `gender` ASC NULLS LAST")
                .endsWith("group_by_use_nulls = 1");
        assertThat(plan.columns().getLast().role()).isEqualTo("meta");
        assertThatThrownBy(() -> builder.build(grouped(List.of("gender"), List.of(new MeasureSpec("patients", null)),
                null, true, null, null, null), patients, H1)).hasMessageContaining("two group columns");
    }

    @Test
    void topNConditionsOnTotalsAndPivot() {
        QueryPlan top = builder.build(grouped(List.of("city"), List.of(new MeasureSpec("patients", null)), null, null, 5, null, null),
                patients, H1);
        assertThat(top.pageSize()).isEqualTo(5);
        assertThat(top.columns().get(1).additive()).isTrue();
        QueryPlan having = builder.build(grouped(List.of("dept_name"), List.of(new MeasureSpec("patients", null),
                new MeasureSpec("age_years", "avg")), null, null, null,
                List.of(FilterNode.condition("patients", "gt", 500)), null), patients, H1);
        assertThat(having.dataSql()).contains("HAVING `patients` > :h0");
        assertThat(having.params()).containsEntry("h0", new BigDecimal("500"));
        assertThat(having.countSql()).contains("HAVING `patients` > :h0");
        assertThat(having.totalsSql()).contains("sum(`patients`) AS `patients`").contains("NULL AS `avg_age_years`");
        assertThatThrownBy(() -> builder.build(grouped(List.of("dept_name"), List.of(new MeasureSpec("patients", null)), null, null,
                null, List.of(FilterNode.condition("gender", "gt", 1)), null), patients, H1)).hasMessageContaining("measure");
        QueryPlan pivot = builder.build(grouped(List.of("dept_name", "gender"), List.of(new MeasureSpec("patients", null)),
                null, null, null, null, "gender"), patients, H1);
        assertThat(pivot.pageSize()).isEqualTo(10_000);
        assertThatThrownBy(() -> builder.build(grouped(List.of("dept_name"), List.of(new MeasureSpec("patients", null)),
                null, null, null, null, "gender"), patients, H1)).hasMessageContaining("pivot");
    }
}
