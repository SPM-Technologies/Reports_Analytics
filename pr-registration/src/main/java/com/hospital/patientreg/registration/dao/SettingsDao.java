package com.hospital.patientreg.registration.dao;

import com.hospital.patientreg.registration.model.CustomFieldDef;
import com.hospital.patientreg.registration.model.Doctor;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-hospital form settings (stage 5b): dropdown values, hidden optional fields, departments and doctors,
 * custom field definitions and values. Row-level security limits every statement to the user's hospitals;
 * the hospital id is still passed explicitly so a user of several hospitals works on the active one only.
 */
@Repository
public class SettingsDao {

    /** One stored value of a hospital's list. */
    public record LookupRow(String listKey, String value, int sortOrder, boolean active) {
    }

    /** A department as shown on the settings screen. */
    public record DepartmentRow(int deptId, String code, String name, boolean active) {
        public int getDeptId() { return deptId; }
        public String getCode() { return code; }
        public String getName() { return name; }
        public boolean isActive() { return active; }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SettingsDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- lists

    public List<LookupRow> lookups(int hospitalId) {
        return jdbc.query("""
                SELECT list_key, value, sort_order, active FROM lookup_value WHERE hospital_id = :h
                ORDER BY list_key, sort_order, value""", Map.of("h", hospitalId),
                (rs, i) -> new LookupRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)));
    }

    /** The list becomes exactly {@code values} in this order; values no longer listed are kept but deactivated. */
    public void replaceList(int hospitalId, String listKey, List<String> values) {
        MapSqlParameterSource p = new MapSqlParameterSource("h", hospitalId).addValue("k", listKey);
        jdbc.update("UPDATE lookup_value SET active = FALSE WHERE hospital_id = :h AND list_key = :k", p);
        for (int i = 0; i < values.size(); i++) {
            jdbc.update("""
                    INSERT INTO lookup_value (hospital_id, list_key, value, sort_order, active) VALUES (:h, :k, :v, :o, TRUE)
                    ON CONFLICT (hospital_id, list_key, value) DO UPDATE SET sort_order = EXCLUDED.sort_order, active = TRUE""",
                    new MapSqlParameterSource("h", hospitalId).addValue("k", listKey).addValue("v", values.get(i)).addValue("o", i));
        }
    }

    // ---------------------------------------------------------------- hidden optional fields

    public Set<String> hiddenFields(int hospitalId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT field FROM form_field_setting WHERE hospital_id = :h AND hidden ORDER BY field", Map.of("h", hospitalId), String.class));
    }

    public void setHidden(int hospitalId, String field, boolean hidden) {
        jdbc.update("""
                INSERT INTO form_field_setting (hospital_id, field, hidden) VALUES (:h, :f, :x)
                ON CONFLICT (hospital_id, field) DO UPDATE SET hidden = EXCLUDED.hidden""",
                new MapSqlParameterSource("h", hospitalId).addValue("f", field).addValue("x", hidden));
    }

    // ---------------------------------------------------------------- departments and doctors

    public List<DepartmentRow> departments(int hospitalId) {
        return jdbc.query("SELECT dept_id, dept_code, dept_name, active FROM department WHERE hospital_id = :h ORDER BY dept_name",
                Map.of("h", hospitalId), (rs, i) -> new DepartmentRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
    }

    public boolean departmentCodeExists(int hospitalId, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM department WHERE hospital_id = :h AND dept_code = :c)",
                new MapSqlParameterSource("h", hospitalId).addValue("c", code), Boolean.class));
    }

    public void insertDepartment(int hospitalId, String code, String name) {
        jdbc.update("INSERT INTO department (hospital_id, dept_code, dept_name) VALUES (:h, :c, :n)",
                new MapSqlParameterSource("h", hospitalId).addValue("c", code).addValue("n", name));
    }

    /** Returns false if the department does not belong to the hospital. */
    public boolean updateDepartment(int hospitalId, int deptId, String name, boolean active) {
        return jdbc.update("UPDATE department SET dept_name = :n, active = :a WHERE hospital_id = :h AND dept_id = :d",
                new MapSqlParameterSource("h", hospitalId).addValue("d", deptId).addValue("n", name).addValue("a", active)) == 1;
    }

    public List<Doctor> doctors(int hospitalId) {
        return jdbc.query("SELECT doctor_id, dept_id, name, active FROM doctor WHERE hospital_id = :h ORDER BY name",
                Map.of("h", hospitalId), (rs, i) -> new Doctor(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4)));
    }

    /** The department's doctors become exactly {@code names}; others are kept but deactivated. */
    public void replaceDoctors(int hospitalId, int deptId, List<String> names) {
        MapSqlParameterSource p = new MapSqlParameterSource("h", hospitalId).addValue("d", deptId);
        jdbc.update("UPDATE doctor SET active = FALSE WHERE hospital_id = :h AND dept_id = :d", p);
        for (String name : names) {
            jdbc.update("""
                    INSERT INTO doctor (hospital_id, dept_id, name) VALUES (:h, :d, :n)
                    ON CONFLICT (hospital_id, dept_id, name) DO UPDATE SET active = TRUE""",
                    new MapSqlParameterSource("h", hospitalId).addValue("d", deptId).addValue("n", name));
        }
    }

    // ---------------------------------------------------------------- custom fields

    private static final String CF_COLUMNS = """
            hospital_id, field_key, label, data_type, section, sort_order, required, min_value, max_value, max_length,
            options, help_text, reportable, sensitivity, active, version""";

    /** Every custom field of the hospitals (active and inactive), by section and order. */
    public List<CustomFieldDef> customFields(List<Integer> hospitalIds) {
        if (hospitalIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + CF_COLUMNS + " FROM custom_field_def WHERE hospital_id IN (:h) ORDER BY hospital_id, section, sort_order, label",
                Map.of("h", hospitalIds), (rs, i) -> customField(rs));
    }

    public Optional<CustomFieldDef> customField(int hospitalId, String key) {
        return jdbc.query("SELECT " + CF_COLUMNS + " FROM custom_field_def WHERE hospital_id = :h AND field_key = :k",
                new MapSqlParameterSource("h", hospitalId).addValue("k", key), (rs, i) -> customField(rs)).stream().findFirst();
    }

    /** Inserts a new definition or updates an existing one (raising its version). */
    /**
     * Marks a hospital's patients holding a value of {@code key} as changed (new version, new change time), so
     * the reporting sync copies them again with the field's current report exposure. A rare administrator action;
     * row-level security keeps it inside the hospital.
     */
    public int republishPatientsWith(int hospitalId, String key) {
        return jdbc.update("UPDATE patient SET row_version = row_version + 1, updated_at = now() "
                + "WHERE hospital_id = :h AND custom_fields ->> :k IS NOT NULL", Map.of("h", hospitalId, "k", key));
    }

    /** The patients registered in a department get a new version, so the reporting sync copies its new name. */
    public int republishDepartment(int hospitalId, int deptId) {
        return jdbc.update("""
                UPDATE patient SET row_version = row_version + 1, updated_at = now()
                WHERE hospital_id = :h AND patient_id IN (
                    SELECT patient_id FROM patient_visit WHERE hospital_id = :h AND dept_id = :d AND registration_visit)""",
                Map.of("h", hospitalId, "d", deptId));
    }

    /** All patients of a hospital get a new version (e.g. after the hospital was renamed). */
    public int republishHospital(int hospitalId) {
        return jdbc.update("UPDATE patient SET row_version = row_version + 1, updated_at = now() WHERE hospital_id = :h",
                Map.of("h", hospitalId));
    }

    public void saveCustomField(CustomFieldDef f) {
        MapSqlParameterSource p = new MapSqlParameterSource("h", f.hospitalId()).addValue("k", f.key()).addValue("l", f.label())
                .addValue("t", f.type().name()).addValue("s", f.section()).addValue("o", f.sortOrder()).addValue("r", f.required())
                .addValue("min", f.minValue()).addValue("max", f.maxValue()).addValue("len", f.maxLength())
                .addValue("opts", String.join("\u001f", f.options())).addValue("help", f.helpText())
                .addValue("rep", f.reportable()).addValue("sens", f.sensitivity()).addValue("a", f.active());
        jdbc.update("""
                INSERT INTO custom_field_def (hospital_id, field_key, label, data_type, section, sort_order, required, min_value,
                    max_value, max_length, options, help_text, reportable, sensitivity, active)
                VALUES (:h, :k, :l, :t, :s, :o, :r, :min, :max, :len,
                    CASE WHEN :opts = '' THEN '{}'::VARCHAR(60)[] ELSE string_to_array(:opts, E'\\x1f')::VARCHAR(60)[] END,
                    :help, :rep, :sens, :a)
                ON CONFLICT (hospital_id, field_key) DO UPDATE SET label = EXCLUDED.label, data_type = EXCLUDED.data_type,
                    section = EXCLUDED.section, sort_order = EXCLUDED.sort_order, required = EXCLUDED.required,
                    min_value = EXCLUDED.min_value, max_value = EXCLUDED.max_value, max_length = EXCLUDED.max_length,
                    options = EXCLUDED.options, help_text = EXCLUDED.help_text, reportable = EXCLUDED.reportable,
                    sensitivity = EXCLUDED.sensitivity, active = EXCLUDED.active,
                    version = custom_field_def.version + 1, updated_at = now()""", p);
    }

    /** How many patients have a value for the field (shown before changing a field that is in use). */
    public long customFieldUsage(int hospitalId, String key) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM patient WHERE hospital_id = :h AND (custom_fields ->> :k) IS NOT NULL",
                new MapSqlParameterSource("h", hospitalId).addValue("k", key), Long.class);
        return n == null ? 0 : n;
    }

    /** A patient's custom values. */
    public Map<String, String> customValues(long patientId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT e.key, e.value FROM patient p, jsonb_each_text(p.custom_fields) e WHERE p.patient_id = :id ORDER BY e.key",
                Map.of("id", patientId), rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
    }

    private static CustomFieldDef customField(ResultSet rs) throws SQLException {
        Array opts = rs.getArray("options");
        List<String> options = opts == null ? List.of() : new ArrayList<>(Arrays.stream((Object[]) opts.getArray()).map(String::valueOf).toList());
        int len = rs.getInt("max_length");
        Integer maxLength = rs.wasNull() ? null : len;
        BigDecimal min = rs.getBigDecimal("min_value");
        BigDecimal max = rs.getBigDecimal("max_value");
        return new CustomFieldDef(rs.getInt("hospital_id"), rs.getString("field_key"), rs.getString("label"),
                CustomFieldDef.Type.valueOf(rs.getString("data_type")), rs.getString("section"), rs.getInt("sort_order"),
                rs.getBoolean("required"), min == null ? null : min.stripTrailingZeros(), max == null ? null : max.stripTrailingZeros(),
                maxLength, options, rs.getString("help_text"), rs.getBoolean("reportable"), rs.getString("sensitivity"),
                rs.getBoolean("active"), rs.getInt("version"));
    }
}
