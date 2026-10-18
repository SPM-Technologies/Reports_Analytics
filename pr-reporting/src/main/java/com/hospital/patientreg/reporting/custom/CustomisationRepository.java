package com.hospital.patientreg.reporting.custom;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Report customisation per hospital in schema {@code reporting} (stage 5b): field settings and shared
 * calculated fields. Row-level security also limits every statement to the user's hospitals.
 */
@Repository
public class CustomisationRepository {

    private final NamedParameterJdbcTemplate meta;

    public CustomisationRepository(@Qualifier("reportingMetaJdbc") NamedParameterJdbcTemplate meta) {
        this.meta = meta;
    }

    // ---------------------------------------------------------------- field settings

    public List<FieldSetting> fieldSettings(String datasetId, Collection<Integer> hospitalIds) {
        if (hospitalIds.isEmpty()) {
            return List.of();
        }
        return meta.query("""
                SELECT hospital_id, field, label, visibility FROM reporting.field_setting
                WHERE dataset_id = :d AND hospital_id IN (:h)""",
                new MapSqlParameterSource("d", datasetId).addValue("h", hospitalIds),
                (rs, i) -> new FieldSetting(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    public void saveFieldSetting(int hospitalId, String datasetId, String field, String label, String visibility) {
        meta.update("""
                INSERT INTO reporting.field_setting (hospital_id, dataset_id, field, label, visibility, updated_at)
                VALUES (:h, :d, :f, :l, :v, now())
                ON CONFLICT (hospital_id, dataset_id, field) DO UPDATE
                    SET label = EXCLUDED.label, visibility = EXCLUDED.visibility, updated_at = now()""",
                new MapSqlParameterSource("h", hospitalId).addValue("d", datasetId).addValue("f", field)
                        .addValue("l", label).addValue("v", visibility));
    }

    /** Several field settings ({field, label, visibility}) in one transaction: all saved, or none. */
    public void saveFieldSettings(int hospitalId, String datasetId, List<String[]> rows) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(meta.getJdbcTemplate().getDataSource()));
        tx.executeWithoutResult(status -> rows.forEach(r -> saveFieldSetting(hospitalId, datasetId, r[0], r[1], r[2])));
    }

    // ---------------------------------------------------------------- shared calculated fields

    private static final String CALC_COLUMNS = """
            id, hospital_id, dataset_id, label, formula, description, result_type, aggregate, active, version,
            created_by, created_by_name, updated_at""";

    /** Calculated fields of the hospitals ({@code activeOnly}: those offered in the report builder). */
    public List<SharedCalculatedField> calculatedFields(String datasetId, Collection<Integer> hospitalIds, boolean activeOnly) {
        if (hospitalIds.isEmpty()) {
            return List.of();
        }
        return meta.query("SELECT " + CALC_COLUMNS + " FROM reporting.calculated_field WHERE dataset_id = :d AND hospital_id IN (:h)"
                        + (activeOnly ? " AND active" : "") + " ORDER BY hospital_id, id",
                new MapSqlParameterSource("d", datasetId).addValue("h", hospitalIds), (rs, i) -> calc(rs));
    }

    public Optional<SharedCalculatedField> calculatedField(long id) {
        return meta.query("SELECT " + CALC_COLUMNS + " FROM reporting.calculated_field WHERE id = :id", Map.of("id", id),
                (rs, i) -> calc(rs)).stream().findFirst();
    }

    public boolean calculatedLabelExists(int hospitalId, String datasetId, String label, Long exceptId) {
        return Boolean.TRUE.equals(meta.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM reporting.calculated_field
                               WHERE hospital_id = :h AND dataset_id = :d AND lower(label) = lower(:l) AND id <> :x)""",
                new MapSqlParameterSource("h", hospitalId).addValue("d", datasetId).addValue("l", label)
                        .addValue("x", exceptId == null ? -1 : exceptId), Boolean.class));
    }

    public long insertCalculated(int hospitalId, String datasetId, String label, String formula, String description,
                                 String resultType, boolean aggregate, long userId, String userName) {
        Long id = meta.queryForObject("""
                INSERT INTO reporting.calculated_field (hospital_id, dataset_id, label, formula, description, result_type,
                    aggregate, created_by, created_by_name)
                VALUES (:h, :d, :l, :f, :desc, :t, :a, :u, :un) RETURNING id""",
                new MapSqlParameterSource("h", hospitalId).addValue("d", datasetId).addValue("l", label).addValue("f", formula)
                        .addValue("desc", description).addValue("t", resultType).addValue("a", aggregate)
                        .addValue("u", userId).addValue("un", userName), Long.class);
        return id == null ? 0 : id;
    }

    public void updateCalculated(long id, String label, String formula, String description, String resultType,
                                 boolean aggregate, boolean active) {
        meta.update("""
                UPDATE reporting.calculated_field SET label = :l, formula = :f, description = :desc, result_type = :t,
                    aggregate = :a, active = :act, version = version + 1, updated_at = now()
                WHERE id = :id""",
                new MapSqlParameterSource("id", id).addValue("l", label).addValue("f", formula).addValue("desc", description)
                        .addValue("t", resultType).addValue("a", aggregate).addValue("act", active));
    }

    /** Names of saved reports (visible to the user) that use the shared field {@code key}. */
    public List<String> reportsUsing(String key) {
        return meta.queryForList("SELECT name FROM reporting.saved_report WHERE definition LIKE :k ORDER BY name",
                Map.of("k", "%\"" + key.replace("_", "\\_") + "\"%"), String.class);   // _ is a LIKE wildcard
    }

    /** All saved reports using shared field {@code id}, including colleagues' private ones (a count only). */
    public long reportCountUsing(long id) {
        Long n = meta.queryForObject("SELECT reporting.reports_using_shared_field(:id)", Map.of("id", id), Long.class);
        return n == null ? 0 : n;
    }

    private static SharedCalculatedField calc(ResultSet rs) throws SQLException {
        long by = rs.getLong("created_by");
        Long createdBy = rs.wasNull() ? null : by;
        return new SharedCalculatedField(rs.getLong("id"), rs.getInt("hospital_id"), rs.getString("dataset_id"),
                rs.getString("label"), rs.getString("formula"), rs.getString("description"), rs.getString("result_type"),
                rs.getBoolean("aggregate"), rs.getBoolean("active"), rs.getInt("version"), createdBy,
                rs.getString("created_by_name"), rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
