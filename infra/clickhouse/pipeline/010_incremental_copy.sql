-- "Lite" sync (always on): every minute copy patients changed in the last 15 minutes. With the real-time
-- pipeline (--profile cdc) changes also arrive within seconds through Kafka; both paths write the same
-- version, so duplicates collapse. The WHERE is pushed down to PostgreSQL (only changed rows are read).
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.mv_patient_report_sync
REFRESH EVERY 1 MINUTE APPEND TO analytics.patient_report
AS SELECT patient_id, registration_no, registration_date, patient_name, gender, age_years, age_band, patient_category, blood_group, marital_status, city, district, state, mobile_masked, dept_id, dept_name, consulting_doctor, visit_type, priority, referral_source, payment_mode, registration_fee, is_insured, coverage_type, insurance_provider, version, is_deleted, source_ts, hospital_id, hospital_code, hospital_name,
       CAST(JSONExtractKeysAndValues(ifNull(src.custom_fields, '{}'), 'String'), 'Map(String, String)') AS custom_fields
FROM postgresql(pg_reporting) AS src
WHERE source_ts > now() - INTERVAL 15 MINUTE;
