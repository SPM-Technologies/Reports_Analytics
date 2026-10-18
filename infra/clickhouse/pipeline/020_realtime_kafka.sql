-- Real-time path (docker compose --profile cdc): Debezium publishes each PatientSnapshot to Kafka topic
-- pr.patient.report (key = patient id, so one patient's events stay in order); ClickHouse consumes it.
CREATE TABLE IF NOT EXISTS analytics.kafka_patient_report (patient_id UInt64, registration_no String, registration_date Date, patient_name String, gender String, age_years UInt8, age_band String, patient_category String, blood_group String, marital_status String, city String, district String, state String, mobile_masked String, dept_id UInt16, dept_name String, consulting_doctor String, visit_type String, priority String, referral_source String, payment_mode String, registration_fee Decimal(10, 2), is_insured UInt8, coverage_type String, insurance_provider String, version UInt64, is_deleted UInt8, source_ts DateTime64(3, 'Asia/Kolkata'), hospital_id UInt32, hospital_code String, hospital_name String, custom_fields Map(String, String))
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'pr.patient.report',
         kafka_group_name = 'clickhouse-patient-report',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = 1,
         kafka_handle_error_mode = 'stream',
         input_format_skip_unknown_fields = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.mv_kafka_patient_report TO analytics.patient_report
AS SELECT patient_id, registration_no, registration_date, patient_name, gender, age_years, age_band, patient_category, blood_group, marital_status, city, district, state, mobile_masked, dept_id, dept_name, consulting_doctor, visit_type, priority, referral_source, payment_mode, registration_fee, is_insured, coverage_type, insurance_provider, version, is_deleted, source_ts, hospital_id, hospital_code, hospital_name, custom_fields FROM analytics.kafka_patient_report WHERE length(_error) = 0;

-- malformed messages are kept for inspection instead of being lost or blocking the stream
CREATE TABLE IF NOT EXISTS analytics.pipeline_errors (
    received_at DateTime DEFAULT now(), topic String, kafka_offset UInt64, raw_message String, error String
) ENGINE = MergeTree ORDER BY received_at TTL received_at + INTERVAL 30 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.mv_kafka_patient_report_errors TO analytics.pipeline_errors
AS SELECT _topic AS topic, _offset AS kafka_offset, _raw_message AS raw_message, _error AS error
FROM analytics.kafka_patient_report WHERE length(_error) > 0;

-- Debezium heartbeats (every 10 s, even when nobody registers) give a true "data as of" time.
CREATE TABLE IF NOT EXISTS analytics.kafka_heartbeat (ts_ms UInt64)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = '__debezium-heartbeat.pr',
         kafka_group_name = 'clickhouse-heartbeat',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = 1,
         kafka_handle_error_mode = 'stream',
         input_format_skip_unknown_fields = 1;

CREATE TABLE IF NOT EXISTS analytics.pipeline_heartbeat (id UInt8, ts DateTime64(3, 'Asia/Kolkata'))
ENGINE = ReplacingMergeTree(ts) ORDER BY id;

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.mv_kafka_heartbeat TO analytics.pipeline_heartbeat
AS SELECT 1 AS id, fromUnixTimestamp64Milli(toInt64(ts_ms), 'Asia/Kolkata') AS ts
FROM analytics.kafka_heartbeat WHERE length(_error) = 0 AND ts_ms > 0;

