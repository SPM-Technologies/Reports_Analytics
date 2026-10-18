-- Full (re)load of the Patients dataset from PostgreSQL into ClickHouse. Safe to re-run at any time:
-- rows with the same patient and version collapse (ReplacingMergeTree). Needed after bulk SQL loads,
-- restores or long outages, because those bypass the outbox. For crores of rows run it month by month.
INSERT INTO analytics.patient_report (patient_id, registration_no, registration_date, patient_name, gender, age_years, age_band, patient_category, blood_group, marital_status, city, district, state, mobile_masked, dept_id, dept_name, consulting_doctor, visit_type, priority, referral_source, payment_mode, registration_fee, is_insured, coverage_type, insurance_provider, version, is_deleted, source_ts, hospital_id, hospital_code, hospital_name, custom_fields)
SELECT patient_id, registration_no, registration_date, patient_name, gender, age_years, age_band, patient_category, blood_group, marital_status, city, district, state, mobile_masked, dept_id, dept_name, consulting_doctor, visit_type, priority, referral_source, payment_mode, registration_fee, is_insured, coverage_type, insurance_provider, version, is_deleted, source_ts, hospital_id, hospital_code, hospital_name,
       CAST(JSONExtractKeysAndValues(ifNull(src.custom_fields, '{}'), 'String'), 'Map(String, String)') AS custom_fields
FROM postgresql(pg_reporting) AS src;
