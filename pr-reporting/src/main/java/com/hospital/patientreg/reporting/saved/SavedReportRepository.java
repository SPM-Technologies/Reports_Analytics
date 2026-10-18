package com.hospital.patientreg.reporting.saved;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Saved reports in PostgreSQL schema {@code reporting}, through the small reporting-metadata pool. */
@Repository
public class SavedReportRepository {

    private static final String COLUMNS = """
            id, name, description, template_key IS NOT NULL AS template, updated_at, hospital_id, created_by, created_by_name,
            visibility""";

    private final NamedParameterJdbcTemplate meta;
    private final ObjectMapper json;

    public SavedReportRepository(@Qualifier("reportingMetaJdbc") NamedParameterJdbcTemplate meta,
                                 ObjectMapper reportingObjectMapper) {
        this.meta = meta;
        this.json = reportingObjectMapper;
    }

    /** Templates first, then the reports of {@code hospitalIds} by name; definitions are left out of the list. */
    public List<SavedReport> list(Collection<Integer> hospitalIds) {
        return meta.query("SELECT " + COLUMNS + """
                 FROM reporting.saved_report
                WHERE template_key IS NOT NULL OR hospital_id IN (:h)
                ORDER BY template_key IS NULL, lower(name)""", Map.of("h", hospitalIds),
                (rs, i) -> row(rs, null));
    }

    public Optional<SavedReport> find(long id) {
        List<SavedReport> found = meta.query("SELECT " + COLUMNS + ", definition FROM reporting.saved_report WHERE id = :id",
                Map.of("id", id), this::full);
        return found.stream().findFirst();
    }

    public long insert(String name, String description, String definition, int hospitalId, long userId, String userName,
                       String visibility) {
        Long id = meta.queryForObject("""
                INSERT INTO reporting.saved_report (name, description, definition, hospital_id, created_by, created_by_name, visibility)
                VALUES (:name, :description, :definition, :hospital, :user, :userName, :visibility) RETURNING id""",
                new MapSqlParameterSource().addValue("name", name).addValue("description", description)
                        .addValue("definition", definition).addValue("hospital", hospitalId)
                        .addValue("user", userId).addValue("userName", userName).addValue("visibility", visibility), Long.class);
        return id == null ? 0 : id;
    }

    /** Updates a user report; templates are never touched. Returns false if no such user report exists. */
    public boolean update(long id, String name, String description, String definition, String visibility) {
        return meta.update("""
                UPDATE reporting.saved_report
                SET name = :name, description = :description, definition = :definition, visibility = :visibility, updated_at = now()
                WHERE id = :id AND template_key IS NULL""",
                new MapSqlParameterSource().addValue("id", id).addValue("name", name).addValue("description", description)
                        .addValue("definition", definition).addValue("visibility", visibility)) == 1;
    }

    public boolean delete(long id) {
        return meta.update("DELETE FROM reporting.saved_report WHERE id = :id AND template_key IS NULL",
                Map.of("id", id)) == 1;
    }

    private SavedReport full(ResultSet rs, int i) throws SQLException {
        try {
            return row(rs, json.readTree(rs.getString("definition")));
        } catch (JsonProcessingException e) {
            throw new SQLException("Saved report " + rs.getLong("id") + " has an unreadable definition", e);
        }
    }

    private static SavedReport row(ResultSet rs, com.fasterxml.jackson.databind.JsonNode definition) throws SQLException {
        int hospital = rs.getInt("hospital_id");
        Integer hospitalId = rs.wasNull() ? null : hospital;
        long createdBy = rs.getLong("created_by");
        Long author = rs.wasNull() ? null : createdBy;
        return new SavedReport(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("template"), rs.getTimestamp("updated_at").toLocalDateTime(), definition,
                null, rs.getString("created_by_name"), null, rs.getString("visibility"), hospitalId, author);
    }
}
