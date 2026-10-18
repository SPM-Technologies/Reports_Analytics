package com.hospital.patientreg.registration.dao;

import com.hospital.patientreg.registration.model.ReportFilter;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportDao {

    /** Patient joined with contact, insurance and the visit created at registration. */
    private static final String REGISTER_FROM = """
            FROM patient p
            JOIN patient_contact c ON c.patient_id = p.patient_id
            LEFT JOIN patient_insurance i ON i.patient_id = p.patient_id
            JOIN patient_visit v ON v.patient_id = p.patient_id AND v.registration_visit
            JOIN department d ON d.dept_id = v.dept_id
            """;

    private static final String REGISTER_COLUMNS = """
            SELECT p.patient_id, p.registration_no, p.registration_date,
                   concat_ws(' ', p.title, p.first_name, p.middle_name, p.last_name) AS patient_name,
                   p.gender, p.age_years, c.mobile, c.city, c.state, d.dept_name, v.consulting_doctor,
                   v.visit_type, p.patient_category,
                   CASE WHEN i.has_insurance THEN 'Yes' ELSE 'No' END AS insured,
                   v.registration_fee
            """;

    private static final int REGISTER_FIELD_COUNT = 14; // columns after patient_id

    private final NamedParameterJdbcTemplate jdbc;

    public ReportDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- Report 1: Patient Registration Register ----------------

    public long registerCount(ReportFilter f, int hospitalId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT count(*) " + REGISTER_FROM + where(f, hospitalId, params);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** One page for the screen. Element 0 of each row is patient_id (for the "view" link). */
    public List<Object[]> registerPage(ReportFilter f, int hospitalId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("limit", f.getPageSize()).addValue("offset", f.getOffset());
        String sql = REGISTER_COLUMNS + REGISTER_FROM + where(f, hospitalId, params)
                + " ORDER BY p.registration_date DESC, p.patient_id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, i) -> registerRow(rs, true));
    }

    /**
     * Streams every matching row (without patient_id) to the sink. Uses a server-side cursor
     * (fetch size) so large exports do not load all rows into memory; must run inside a transaction.
     */
    public void registerStream(ReportFilter f, int hospitalId, Consumer<Object[]> sink) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = REGISTER_COLUMNS + REGISTER_FROM + where(f, hospitalId, params)
                + " ORDER BY p.registration_date DESC, p.patient_id DESC";
        JdbcTemplate streaming = new JdbcTemplate(jdbc.getJdbcTemplate().getDataSource());
        streaming.setFetchSize(2000);
        new NamedParameterJdbcTemplate(streaming).query(sql, params, rs -> {
            sink.accept(registerRow(rs, false));
        });
    }

    private static Object[] registerRow(ResultSet rs, boolean withId) throws SQLException {
        int start = withId ? 1 : 2;
        Object[] row = new Object[REGISTER_FIELD_COUNT + (withId ? 1 : 0)];
        for (int col = start, k = 0; col <= REGISTER_FIELD_COUNT + 1; col++, k++) {
            row[k] = col == 3 ? rs.getObject(col, java.time.LocalDate.class) : rs.getObject(col);
        }
        return row;
    }

    // ---------------- Report 2: Department-wise Summary ----------------

    /**
     * One row per active department:
     * name, total, male, female, other, new, follow-up, emergency, insured, avg age, total fee.
     */
    public List<Object[]> departmentSummary(ReportFilter f, int hospitalId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = """
                SELECT d.dept_name,
                       coalesce(s.total, 0), coalesce(s.male, 0), coalesce(s.female, 0), coalesce(s.other, 0),
                       coalesce(s.new_visits, 0), coalesce(s.follow_up, 0), coalesce(s.emergency, 0),
                       coalesce(s.insured, 0), coalesce(s.avg_age, 0), coalesce(s.total_fee, 0)
                FROM department d
                LEFT JOIN (
                    SELECT v.dept_id,
                           count(*) AS total,
                           count(*) FILTER (WHERE p.gender = 'Male')   AS male,
                           count(*) FILTER (WHERE p.gender = 'Female') AS female,
                           count(*) FILTER (WHERE p.gender = 'Other')  AS other,
                           count(*) FILTER (WHERE v.visit_type = 'NEW')       AS new_visits,
                           count(*) FILTER (WHERE v.visit_type = 'FOLLOW_UP') AS follow_up,
                           count(*) FILTER (WHERE v.visit_type = 'EMERGENCY') AS emergency,
                           count(*) FILTER (WHERE i.has_insurance) AS insured,
                           round(avg(p.age_years), 1) AS avg_age,
                           sum(v.registration_fee) AS total_fee
                """ + REGISTER_FROM + where(f, hospitalId, params) + """
                    GROUP BY v.dept_id
                ) s ON s.dept_id = d.dept_id
                WHERE d.active AND d.hospital_id = :hospitalId
                ORDER BY d.dept_name
                """;
        return jdbc.query(sql, params, (rs, i) -> new Object[] {
                rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getBigDecimal(10), rs.getBigDecimal(11)});
    }

    // ---------------- shared WHERE clause ----------------

    private static String where(ReportFilter f, int hospitalId, MapSqlParameterSource params) {
        // always one hospital (the active one); row-level security additionally limits rows to the user's hospitals
        StringBuilder sb = new StringBuilder(" WHERE p.hospital_id = :hospitalId");
        params.addValue("hospitalId", hospitalId);
        if (f.getFromDate() != null) {
            sb.append(" AND p.registration_date >= :fromDate");
            params.addValue("fromDate", f.getFromDate());
        }
        if (f.getToDate() != null) {
            sb.append(" AND p.registration_date <= :toDate");
            params.addValue("toDate", f.getToDate());
        }
        if (f.getDeptId() != null) {
            sb.append(" AND v.dept_id = :deptId");
            params.addValue("deptId", f.getDeptId());
        }
        if (ReportFilter.hasText(f.getGender())) {
            sb.append(" AND p.gender = :gender");
            params.addValue("gender", f.getGender());
        }
        if (ReportFilter.hasText(f.getPatientCategory())) {
            sb.append(" AND p.patient_category = :category");
            params.addValue("category", f.getPatientCategory());
        }
        if (ReportFilter.hasText(f.getKeyword())) {
            // searching by mobile number only for users who may see numbers: otherwise a masked number could be
            // worked out digit by digit by searching "98", "987", ...
            boolean mobileSearch = TenantContext.current().map(t -> t.canHere(Permission.VIEW_PATIENT)).orElse(false);
            sb.append(" AND (p.registration_no ILIKE :kw" + (mobileSearch ? " OR c.mobile LIKE :kw" : "")
                    + " OR (p.first_name || ' ' || p.last_name) ILIKE :kw)");
            // escape LIKE wildcards so "%" or "_" typed by the user match literally
            String kw = f.getKeyword().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            params.addValue("kw", "%" + kw + "%");
        }
        return sb.append(' ').toString();
    }
}
