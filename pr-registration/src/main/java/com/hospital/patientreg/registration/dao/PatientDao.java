package com.hospital.patientreg.registration.dao;

import com.hospital.patientreg.registration.model.Department;
import com.hospital.patientreg.registration.model.PatientForm;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class PatientDao {

    /** Next number in the hospital's series for the year; the row lock serialises concurrent registrations. */
    private static final String NEXT_REG_NO = """
            INSERT INTO hospital_reg_counter (hospital_id, reg_year, last_no) VALUES (:hospitalId, :year, 1)
            ON CONFLICT (hospital_id, reg_year) DO UPDATE SET last_no = hospital_reg_counter.last_no + 1
            RETURNING last_no
            """;

    private static final String INSERT_PATIENT = """
            INSERT INTO patient (hospital_id, custom_fields, registration_no, registration_date, title, first_name, middle_name, last_name,
                gender, date_of_birth, age_years, blood_group, marital_status, nationality, religion, occupation,
                national_id, preferred_language, patient_category, status)
            VALUES (:hospitalId, CAST(:customValuesJson AS jsonb), :registrationNo,
                :registrationDate, :title, :firstName, :middleName, :lastName, :gender, :dateOfBirth, :ageYears,
                :bloodGroup, :maritalStatus, :nationality, :religion, :occupation, :nationalId, :preferredLanguage,
                :patientCategory, :status)
            RETURNING patient_id
            """;

    private static final String INSERT_CONTACT = """
            INSERT INTO patient_contact (hospital_id, patient_id, mobile, alternate_phone, email, address_line1, address_line2,
                city, district, state, pincode, country, perm_same_as_current, permanent_address)
            VALUES (:hospitalId, :patientId, :mobile, :alternatePhone, :email, :addressLine1, :addressLine2, :city, :district,
                :state, :pincode, :country, :permSameAsCurrent, :permanentAddress)
            """;

    private static final String INSERT_EMERGENCY = """
            INSERT INTO patient_emergency_contact (hospital_id, patient_id, contact_name, relationship, phone, alternate_phone, address)
            VALUES (:hospitalId, :patientId, :emergencyName, :emergencyRelationship, :emergencyPhone, :emergencyAltPhone,
                :emergencyAddress)
            """;

    private static final String INSERT_INSURANCE = """
            INSERT INTO patient_insurance (hospital_id, patient_id, has_insurance, provider_name, policy_number, group_number,
                policy_holder_name, holder_relationship, valid_from, valid_to, coverage_type, sum_insured)
            VALUES (:hospitalId, :patientId, :hasInsurance, :insuranceProvider, :policyNumber, :groupNumber, :policyHolderName,
                :holderRelationship, :policyValidFrom, :policyValidTo, :coverageType, :sumInsured)
            """;

    private static final String INSERT_MEDICAL = """
            INSERT INTO patient_medical_info (hospital_id, patient_id, height_cm, weight_kg, bmi, known_allergies,
                chronic_conditions, current_medications, past_surgeries, family_history, smoking_status,
                alcohol_use, disability, vaccination_status)
            VALUES (:hospitalId, :patientId, :heightCm, :weightKg, :bmi, :knownAllergies, :chronicConditions,
                :currentMedications, :pastSurgeries, :familyHistory, :smokingStatus, :alcoholUse, :disability,
                :vaccinationStatus)
            """;

    private static final String INSERT_VISIT = """
            INSERT INTO patient_visit (hospital_id, patient_id, dept_id, consulting_doctor, visit_type, visit_date, referred_by,
                referral_source, chief_complaint, symptom_duration, priority, payment_mode, registration_fee, remarks,
                registration_visit)
            VALUES (:hospitalId, :patientId, :deptId, :consultingDoctor, :visitType, :visitDate, :referredBy, :referralSource,
                :chiefComplaint, :symptomDuration, :priority, :paymentMode, :registrationFee, :remarks, TRUE)
            """;

    // Column aliases match PatientForm property names so BeanPropertyRowMapper can fill the whole form.
    private static final String SELECT_FULL = """
            SELECT p.*,
                   c.mobile, c.alternate_phone, c.email, c.address_line1, c.address_line2, c.city, c.district,
                   c.state, c.pincode, c.country, c.perm_same_as_current, c.permanent_address,
                   e.contact_name AS emergency_name, e.relationship AS emergency_relationship,
                   e.phone AS emergency_phone, e.alternate_phone AS emergency_alt_phone,
                   e.address AS emergency_address,
                   i.has_insurance, i.provider_name AS insurance_provider, i.policy_number, i.group_number,
                   i.policy_holder_name, i.holder_relationship, i.valid_from AS policy_valid_from,
                   i.valid_to AS policy_valid_to, i.coverage_type, i.sum_insured,
                   m.height_cm, m.weight_kg, m.bmi, m.known_allergies, m.chronic_conditions, m.current_medications,
                   m.past_surgeries, m.family_history, m.smoking_status, m.alcohol_use, m.disability,
                   m.vaccination_status,
                   v.dept_id, d.dept_name, v.consulting_doctor, v.visit_type, v.visit_date, v.referred_by,
                   v.referral_source, v.chief_complaint, v.symptom_duration, v.priority, v.payment_mode,
                   v.registration_fee, v.remarks
            FROM patient p
            LEFT JOIN patient_contact c ON c.patient_id = p.patient_id
            LEFT JOIN patient_emergency_contact e ON e.patient_id = p.patient_id
            LEFT JOIN patient_insurance i ON i.patient_id = p.patient_id
            LEFT JOIN patient_medical_info m ON m.patient_id = p.patient_id
            LEFT JOIN patient_visit v ON v.patient_id = p.patient_id AND v.registration_visit
            LEFT JOIN department d ON d.dept_id = v.dept_id
            WHERE p.patient_id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PatientDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts into all six patient tables of {@code hospitalId} and publishes the outbox record, with the next
     * registration number of that hospital ({@code CODE-REGyyyy0000001}). Caller must run this inside a transaction.
     */
    public void insert(PatientForm form, int hospitalId, String hospitalCode) {
        int year = form.getRegistrationDate().getYear();
        Integer next = jdbc.queryForObject(NEXT_REG_NO,
                new MapSqlParameterSource("hospitalId", hospitalId).addValue("year", year), Integer.class);
        form.setHospitalId(hospitalId);
        form.setRegistrationNo(String.format("%s-REG%d%07d", hospitalCode, year, next));
        Long id = jdbc.queryForObject(INSERT_PATIENT, new BeanPropertySqlParameterSource(form), Long.class);
        form.setPatientId(id);

        SqlParameterSource params = new BeanPropertySqlParameterSource(form);
        jdbc.update(INSERT_CONTACT, params);
        jdbc.update(INSERT_EMERGENCY, params);
        jdbc.update(INSERT_INSURANCE, params);
        jdbc.update(INSERT_MEDICAL, params);
        jdbc.update(INSERT_VISIT, params);
        publishSnapshot(form.getPatientId());
    }

    /**
     * PatientSnapshot v2 (the published reporting contract; v2 adds the hospital) for one patient, in the caller's transaction.
     * The payload is built from v_patient_report, the same view the ClickHouse backfill reads, so both
     * paths produce identical records. Inserted and deleted at once: the outbox table stays empty, while
     * Debezium picks the insert up from the WAL (architecture section 13.3). The insert runs in the
     * owner-rights function patientreg.publish_snapshot, which publishes only patients of the user's hospitals.
     */
    public void publishSnapshot(long patientId) {
        // insert and delete both happen inside the function: the application role has no rights on the outbox
        jdbc.queryForObject(PUBLISH_SNAPSHOT, new MapSqlParameterSource("id", patientId), Integer.class);
    }

    /** The application role cannot read v_patient_report itself (owner-rights function, see schema.sql). */
    private static final String PUBLISH_SNAPSHOT = "SELECT patientreg.publish_snapshot(:id)";

    public PatientForm findById(long patientId) {
        BeanPropertyRowMapper<PatientForm> mapper = new BeanPropertyRowMapper<>(PatientForm.class);
        mapper.setPrimitivesDefaultedForNullValue(true);
        List<PatientForm> list = jdbc.query(SELECT_FULL, new MapSqlParameterSource("id", patientId), mapper);
        return list.isEmpty() ? null : list.getFirst();
    }

    public List<Department> findActiveDepartments(int hospitalId) {
        return jdbc.query("SELECT dept_id, dept_code, dept_name FROM department WHERE active AND hospital_id = :h ORDER BY dept_name",
                Map.of("h", hospitalId), (rs, i) -> new Department(rs.getInt(1), rs.getString(2), rs.getString(3)));
    }

    /** Default departments for a newly added hospital (owner-rights function, see schema.sql). */
    public void seedDepartments(int hospitalId) {
        jdbc.queryForList("SELECT patientreg.seed_departments(:h)::text", Map.of("h", hospitalId));
    }

    public String findDepartmentName(Integer deptId) {
        if (deptId == null) return null;
        List<String> names = jdbc.queryForList("SELECT dept_name FROM department WHERE dept_id = :id",
                new MapSqlParameterSource("id", deptId), String.class);
        return names.isEmpty() ? null : names.getFirst();
    }

    /** Dashboard figures of one hospital: total patients, registered today, registered this month. */
    public Map<String, Object> dashboardCounts(int hospitalId) {
        return jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE registration_date = CURRENT_DATE) AS today,
                       count(*) FILTER (WHERE registration_date >= date_trunc('month', CURRENT_DATE)) AS month
                FROM patient WHERE hospital_id = :h
                """, Map.of("h", hospitalId));
    }
}
