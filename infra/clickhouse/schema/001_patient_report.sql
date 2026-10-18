-- Analytics table for the Patients dataset (PatientSnapshot v1). Idempotent; applied by the admin user
-- to each analytics database (see infra/clickhouse-schema.sh). Keep in sync with patientreg.v_patient_report.
CREATE TABLE IF NOT EXISTS patient_report
(
    patient_id          UInt64,
    registration_no     String,
    registration_date   Date,
    patient_name        String,
    gender              LowCardinality(String),
    age_years           UInt8,
    age_band            LowCardinality(String),
    patient_category    LowCardinality(String),
    blood_group         LowCardinality(String),
    marital_status      LowCardinality(String),
    city                LowCardinality(String),
    district            LowCardinality(String),
    state               LowCardinality(String),
    mobile_masked       String,
    dept_id             UInt16,
    dept_name           LowCardinality(String),
    consulting_doctor   LowCardinality(String),
    visit_type          LowCardinality(String),
    priority            LowCardinality(String),
    referral_source     LowCardinality(String),
    payment_mode        LowCardinality(String),
    registration_fee    Decimal(10, 2),
    is_insured          UInt8,
    coverage_type       LowCardinality(String),
    insurance_provider  LowCardinality(String),
    version             UInt64,                                  -- = patient.row_version, highest wins
    is_deleted          UInt8,
    source_ts           DateTime64(3, 'Asia/Kolkata'),           -- when the change happened in PostgreSQL
    hospital_id         UInt32,                                  -- stage 5a: every row belongs to one hospital
    hospital_code       LowCardinality(String),
    hospital_name       LowCardinality(String),
    custom_fields       Map(String, String),                     -- stage 5b: hospitals' own fields (exposure decided by the app)
    ingested_at         DateTime64(3, 'Asia/Kolkata') DEFAULT now64(3),
    INDEX idx_dept dept_id TYPE set(0) GRANULARITY 4,
    INDEX idx_name lower(patient_name) TYPE ngrambf_v1(3, 65536, 3, 0) GRANULARITY 4
)
ENGINE = ReplacingMergeTree(version, is_deleted)
PARTITION BY toYYYYMM(registration_date)
-- hospital first: every query is limited to the user's hospitals and reads only their data blocks;
-- only columns that never change (see architecture 13.4)
ORDER BY (hospital_id, registration_date, patient_id);

-- stage 5b on an existing table (no-op on a new one)
ALTER TABLE patient_report ADD COLUMN IF NOT EXISTS custom_fields Map(String, String) AFTER hospital_name;
