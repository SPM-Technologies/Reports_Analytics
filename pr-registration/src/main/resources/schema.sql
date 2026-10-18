-- Patient Registration schema (PostgreSQL). Safe to run repeatedly.
-- Applied at every startup by the administrator connection (pr-app SchemaMigrator) as ONE script, after
-- identity-schema.sql (hospitals). DO blocks are allowed. The application itself connects as a normal
-- role, so the row-level security policies at the end of the stage-5a section apply to it.
CREATE SCHEMA IF NOT EXISTS patientreg;
SET search_path TO patientreg;

CREATE SEQUENCE IF NOT EXISTS reg_no_seq START 1;

-- 1. Department master
CREATE TABLE IF NOT EXISTS department (
    dept_id     SERIAL PRIMARY KEY,
    dept_code   VARCHAR(10)  NOT NULL,
    dept_name   VARCHAR(100) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE
);

-- 2. Patient (core demographics)
CREATE TABLE IF NOT EXISTS patient (
    patient_id          BIGSERIAL PRIMARY KEY,
    registration_no     VARCHAR(30)  NOT NULL,
    registration_date   DATE         NOT NULL DEFAULT CURRENT_DATE,
    title               VARCHAR(10),
    first_name          VARCHAR(60)  NOT NULL,
    middle_name         VARCHAR(60),
    last_name           VARCHAR(60)  NOT NULL,
    gender              VARCHAR(10)  NOT NULL,
    date_of_birth       DATE,
    age_years           INTEGER      NOT NULL,
    blood_group         VARCHAR(5),
    marital_status      VARCHAR(15),
    nationality         VARCHAR(40),
    religion            VARCHAR(30),
    occupation          VARCHAR(60),
    national_id         VARCHAR(20),
    preferred_language  VARCHAR(30),
    patient_category    VARCHAR(20)  NOT NULL DEFAULT 'GENERAL',
    status              VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP
);
-- (the register's sort-order index, with the hospital first, is created in the stage-5a section)
DROP INDEX IF EXISTS idx_patient_reg_date;
CREATE INDEX IF NOT EXISTS idx_patient_gender   ON patient (gender);
CREATE INDEX IF NOT EXISTS idx_patient_category ON patient (patient_category);

-- 3. Contact & address (1:1)
CREATE TABLE IF NOT EXISTS patient_contact (
    patient_id            BIGINT PRIMARY KEY REFERENCES patient (patient_id) ON DELETE CASCADE,
    mobile                VARCHAR(15) NOT NULL,
    alternate_phone       VARCHAR(15),
    email                 VARCHAR(100),
    address_line1         VARCHAR(150) NOT NULL,
    address_line2         VARCHAR(150),
    city                  VARCHAR(60)  NOT NULL,
    district              VARCHAR(60),
    state                 VARCHAR(60)  NOT NULL,
    pincode               VARCHAR(10)  NOT NULL,
    country               VARCHAR(60)  NOT NULL DEFAULT 'India',
    perm_same_as_current  BOOLEAN      NOT NULL DEFAULT TRUE,
    permanent_address     VARCHAR(300)
);
CREATE INDEX IF NOT EXISTS idx_contact_mobile ON patient_contact (mobile);
CREATE INDEX IF NOT EXISTS idx_contact_city   ON patient_contact (city);

-- 4. Emergency contact (1:1)
CREATE TABLE IF NOT EXISTS patient_emergency_contact (
    patient_id       BIGINT PRIMARY KEY REFERENCES patient (patient_id) ON DELETE CASCADE,
    contact_name     VARCHAR(100) NOT NULL,
    relationship     VARCHAR(30)  NOT NULL,
    phone            VARCHAR(15)  NOT NULL,
    alternate_phone  VARCHAR(15),
    address          VARCHAR(300)
);

-- 5. Insurance (1:1)
CREATE TABLE IF NOT EXISTS patient_insurance (
    patient_id           BIGINT PRIMARY KEY REFERENCES patient (patient_id) ON DELETE CASCADE,
    has_insurance        BOOLEAN NOT NULL DEFAULT FALSE,
    provider_name        VARCHAR(100),
    policy_number        VARCHAR(40),
    group_number         VARCHAR(40),
    policy_holder_name   VARCHAR(100),
    holder_relationship  VARCHAR(30),
    valid_from           DATE,
    valid_to             DATE,
    coverage_type        VARCHAR(30),
    sum_insured          NUMERIC(12, 2)
);

-- 6. Medical information (1:1)
CREATE TABLE IF NOT EXISTS patient_medical_info (
    patient_id           BIGINT PRIMARY KEY REFERENCES patient (patient_id) ON DELETE CASCADE,
    height_cm            NUMERIC(5, 1),
    weight_kg            NUMERIC(5, 1),
    bmi                  NUMERIC(5, 1),
    known_allergies      VARCHAR(300),
    chronic_conditions   VARCHAR(300),
    current_medications  VARCHAR(300),
    past_surgeries       VARCHAR(300),
    family_history       VARCHAR(300),
    smoking_status       VARCHAR(20),
    alcohol_use          VARCHAR(20),
    disability           VARCHAR(100),
    vaccination_status   VARCHAR(30)
);

-- 7. Visit (1:N, first visit created at registration)
CREATE TABLE IF NOT EXISTS patient_visit (
    visit_id            BIGSERIAL PRIMARY KEY,
    patient_id          BIGINT  NOT NULL REFERENCES patient (patient_id) ON DELETE CASCADE,
    dept_id             INTEGER NOT NULL REFERENCES department (dept_id),
    consulting_doctor   VARCHAR(100) NOT NULL,
    visit_type          VARCHAR(20)  NOT NULL,
    visit_date          DATE         NOT NULL,
    referred_by         VARCHAR(100),
    referral_source     VARCHAR(30),
    chief_complaint     VARCHAR(300) NOT NULL,
    symptom_duration    VARCHAR(30),
    priority            VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    payment_mode        VARCHAR(20)  NOT NULL,
    registration_fee    NUMERIC(10, 2) NOT NULL DEFAULT 0,
    remarks             VARCHAR(300)
);
CREATE INDEX IF NOT EXISTS idx_visit_patient ON patient_visit (patient_id);
CREATE INDEX IF NOT EXISTS idx_visit_dept    ON patient_visit (dept_id);
CREATE INDEX IF NOT EXISTS idx_visit_date    ON patient_visit (visit_date);

-- Phase 2: flag the visit created at registration so reports use a plain join
-- instead of looking up each patient's first visit row by row.
ALTER TABLE patient_visit ADD COLUMN IF NOT EXISTS registration_visit BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE patient_visit SET registration_visit = TRUE
WHERE visit_id IN (SELECT min(visit_id) FROM patient_visit GROUP BY patient_id
                   HAVING NOT bool_or(registration_visit));
CREATE UNIQUE INDEX IF NOT EXISTS ux_visit_registration ON patient_visit (patient_id) WHERE registration_visit;

-- Phase 2: trigram indexes so "contains" search on name / reg no / mobile avoids full scans.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_patient_name_trgm ON patient
    USING gin ((first_name || ' ' || last_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_patient_regno_trgm ON patient USING gin (registration_no gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_contact_mobile_trgm ON patient_contact USING gin (mobile gin_trgm_ops);

-- ============================================================================================
-- Stage 5a: multiple hospitals (architecture section 10.1, decision D13).
-- Every row belongs to one hospital (identity.hospital). Rows that existed before belong to hospital 1.
-- ============================================================================================

-- hospital_id on every table: existing rows get 1 through the default, then the default is removed so
-- every new row must say its hospital explicitly
ALTER TABLE department                ADD COLUMN IF NOT EXISTS hospital_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE patient                   ADD COLUMN IF NOT EXISTS hospital_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE patient_contact           ADD COLUMN IF NOT EXISTS hospital_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE patient_emergency_contact ADD COLUMN IF NOT EXISTS hospital_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE patient_insurance         ADD COLUMN IF NOT EXISTS hospital_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE patient_medical_info      ADD COLUMN IF NOT EXISTS hospital_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE patient_visit             ADD COLUMN IF NOT EXISTS hospital_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE department                ALTER COLUMN hospital_id DROP DEFAULT;
ALTER TABLE patient                   ALTER COLUMN hospital_id DROP DEFAULT;
ALTER TABLE patient_contact           ALTER COLUMN hospital_id DROP DEFAULT;
ALTER TABLE patient_emergency_contact ALTER COLUMN hospital_id DROP DEFAULT;
ALTER TABLE patient_insurance         ALTER COLUMN hospital_id DROP DEFAULT;
ALTER TABLE patient_medical_info      ALTER COLUMN hospital_id DROP DEFAULT;
ALTER TABLE patient_visit             ALTER COLUMN hospital_id DROP DEFAULT;

-- registration numbers carry the hospital code (CODE-REGyyyy0000001), so the column is wider;
-- the reporting view depends on it and is re-created further down
DO $$
BEGIN
    IF (SELECT character_maximum_length FROM information_schema.columns
        WHERE table_schema = 'patientreg' AND table_name = 'patient' AND column_name = 'registration_no') < 30 THEN
        DROP VIEW IF EXISTS patientreg.v_patient_report;
        ALTER TABLE patientreg.patient ALTER COLUMN registration_no TYPE VARCHAR(30);
    END IF;
END $$;

-- Keys and the composite foreign keys that make a mixed record impossible: a contact, visit, insurance,
-- medical or emergency row must belong to the same hospital as its patient, and a visit's department to
-- the same hospital as the visit. Registration numbers and department codes are unique per hospital.
DO $$
DECLARE
    child TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'department_dept_code_key') THEN
        ALTER TABLE patientreg.department DROP CONSTRAINT department_dept_code_key;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'patient_registration_no_key') THEN
        ALTER TABLE patientreg.patient DROP CONSTRAINT patient_registration_no_key;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_department_hospital_code') THEN
        ALTER TABLE patientreg.department ADD CONSTRAINT uq_department_hospital_code UNIQUE (hospital_id, dept_code);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_department_hospital_id') THEN
        ALTER TABLE patientreg.department ADD CONSTRAINT uq_department_hospital_id UNIQUE (hospital_id, dept_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_department_hospital') THEN
        ALTER TABLE patientreg.department ADD CONSTRAINT fk_department_hospital
            FOREIGN KEY (hospital_id) REFERENCES identity.hospital (hospital_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_patient_hospital_regno') THEN
        ALTER TABLE patientreg.patient ADD CONSTRAINT uq_patient_hospital_regno UNIQUE (hospital_id, registration_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_patient_hospital_id') THEN
        ALTER TABLE patientreg.patient ADD CONSTRAINT uq_patient_hospital_id UNIQUE (hospital_id, patient_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_patient_hospital') THEN
        ALTER TABLE patientreg.patient ADD CONSTRAINT fk_patient_hospital
            FOREIGN KEY (hospital_id) REFERENCES identity.hospital (hospital_id);
    END IF;
    FOREACH child IN ARRAY ARRAY['patient_contact', 'patient_emergency_contact', 'patient_insurance',
                                 'patient_medical_info', 'patient_visit'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_' || child || '_same_hospital') THEN
            EXECUTE format('ALTER TABLE patientreg.%I ADD CONSTRAINT %I FOREIGN KEY (hospital_id, patient_id) '
                           'REFERENCES patientreg.patient (hospital_id, patient_id) ON DELETE CASCADE',
                           child, 'fk_' || child || '_same_hospital');
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_visit_department_same_hospital') THEN
        ALTER TABLE patientreg.patient_visit ADD CONSTRAINT fk_visit_department_same_hospital
            FOREIGN KEY (hospital_id, dept_id) REFERENCES patientreg.department (hospital_id, dept_id);
    END IF;
END $$;

-- the register and dashboard always look at one hospital: hospital first in the sort-order index
DROP INDEX IF EXISTS idx_patient_reg_order;
CREATE INDEX IF NOT EXISTS idx_patient_hospital_reg_order ON patient (hospital_id, registration_date DESC, patient_id DESC);

-- Registration numbers per hospital and year, gap-free: the counter row is updated inside the
-- registration transaction (a failed save rolls it back). reg_no_seq is no longer used.
CREATE TABLE IF NOT EXISTS hospital_reg_counter (
    hospital_id  INTEGER NOT NULL REFERENCES identity.hospital (hospital_id),
    reg_year     INTEGER NOT NULL,
    last_no      INTEGER NOT NULL,
    PRIMARY KEY (hospital_id, reg_year)
);
-- hospital 1 continues after the numbers issued before stage 5a (REGyyyy + 7 digits)
INSERT INTO hospital_reg_counter (hospital_id, reg_year, last_no)
SELECT 1, substring(registration_no, 4, 4)::INTEGER, max(substring(registration_no, 8)::INTEGER)
FROM patient
WHERE NOT EXISTS (SELECT 1 FROM hospital_reg_counter WHERE hospital_id = 1)
  AND hospital_id = 1 AND registration_no ~ '^REG[0-9]{11}$'
GROUP BY 2
ON CONFLICT (hospital_id, reg_year) DO NOTHING;

-- Default departments of a hospital. SECURITY DEFINER: runs as the owner, so a super administrator can
-- set up a hospital they are not yet working in (row-level security would hide it from them).
CREATE OR REPLACE FUNCTION patientreg.seed_departments(p_hospital INTEGER) RETURNS VOID
LANGUAGE sql SECURITY DEFINER SET search_path = patientreg, pg_temp AS $$
    INSERT INTO patientreg.department (hospital_id, dept_code, dept_name)
    SELECT p_hospital, code, name
    FROM (VALUES ('GEN', 'General Medicine'), ('CAR', 'Cardiology'), ('ORT', 'Orthopaedics'),
                 ('PED', 'Paediatrics'), ('GYN', 'Obstetrics & Gynaecology'), ('ENT', 'ENT'),
                 ('DER', 'Dermatology'), ('NEU', 'Neurology'), ('OPH', 'Ophthalmology'), ('DEN', 'Dental')) AS d (code, name)
    ON CONFLICT (hospital_id, dept_code) DO NOTHING
$$;
SELECT patientreg.seed_departments(1);

-- Row-level security: the application's database session can only see and write rows of the hospitals
-- of the signed-in user (app.hospital_ids, set on every borrowed connection by TenantAwareDataSource).
-- No setting means an empty list: nothing is visible (fails closed). Superusers (administration,
-- migrations) and the definer view v_patient_report are not affected.
CREATE OR REPLACE FUNCTION patientreg.current_hospitals() RETURNS INTEGER[]
LANGUAGE sql STABLE AS $$
    SELECT coalesce(string_to_array(nullif(current_setting('app.hospital_ids', true), ''), ',')::INTEGER[], '{}'::INTEGER[])
$$;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['department', 'patient', 'patient_contact', 'patient_emergency_contact', 'patient_insurance',
                             'patient_medical_info', 'patient_visit', 'hospital_reg_counter'] LOOP
        EXECUTE format('ALTER TABLE patientreg.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE patientreg.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS hospital_isolation ON patientreg.%I', t);
        -- (SELECT ...)::INTEGER[] makes PostgreSQL read the setting once per query (an InitPlan), not once per row
        EXECUTE format('CREATE POLICY hospital_isolation ON patientreg.%I '
                       'USING (hospital_id = ANY ((SELECT patientreg.current_hospitals())::INTEGER[])) '
                       'WITH CHECK (hospital_id = ANY ((SELECT patientreg.current_hospitals())::INTEGER[]))', t);
    END LOOP;
END $$;

-- Reporting stage 2: published contract for the analytics store (PatientSnapshot v1).
-- row_version increases on every change of a patient; the analytics table keeps the highest version.
ALTER TABLE patient ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_patient_change_ts ON patient ((coalesce(updated_at, created_at)));

-- Flattened, minimised record per patient (no Aadhaar, masked mobile, no free-text health data).
-- Read by ClickHouse (backfill and the one-minute copy, as reporting_ro) and by PatientDao.publishSnapshot
-- for the outbox. It runs with its owner's rights, i.e. WITHOUT row-level security: it is the publication
-- contract for the pipeline only; screens must never query it. PatientSnapshot v2 adds the hospital.
-- (stage 5b column and table, created here so that a fresh database has them before the view is created)
ALTER TABLE patient ADD COLUMN IF NOT EXISTS custom_fields JSONB NOT NULL DEFAULT '{}'::jsonb;
-- fields a hospital adds to its registration form without a release; never deleted, only deactivated
CREATE TABLE IF NOT EXISTS custom_field_def (
    hospital_id  INTEGER      NOT NULL REFERENCES identity.hospital (hospital_id),
    field_key    VARCHAR(30)  NOT NULL CHECK (field_key ~ '^[a-z][a-z0-9_]{1,29}$'),
    label        VARCHAR(60)  NOT NULL,
    data_type    VARCHAR(12)  NOT NULL CHECK (data_type IN ('TEXT', 'LONG_TEXT', 'NUMBER', 'DECIMAL', 'DATE', 'YES_NO', 'DROPDOWN', 'MULTI_SELECT')),
    section      VARCHAR(12)  NOT NULL CHECK (section IN ('personal', 'contact', 'emergency', 'insurance', 'medical', 'visit', 'additional')),
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    required     BOOLEAN      NOT NULL DEFAULT FALSE,
    min_value    NUMERIC(14, 4),
    max_value    NUMERIC(14, 4),
    max_length   INTEGER,
    options      VARCHAR(60)[] NOT NULL DEFAULT '{}',
    help_text    VARCHAR(200),
    reportable   BOOLEAN      NOT NULL DEFAULT FALSE,
    sensitivity  VARCHAR(10)  NOT NULL DEFAULT 'NORMAL' CHECK (sensitivity IN ('NORMAL', 'MASKED', 'HIDDEN')),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    version      INTEGER      NOT NULL DEFAULT 1,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP,
    PRIMARY KEY (hospital_id, field_key)
);
CREATE OR REPLACE VIEW v_patient_report AS
SELECT p.patient_id,
       p.registration_no,
       p.registration_date,
       concat_ws(' ', p.title, p.first_name, p.middle_name, p.last_name) AS patient_name,
       p.gender,
       p.age_years,
       CASE WHEN p.age_years < 14 THEN '0-13' WHEN p.age_years < 25 THEN '14-24'
            WHEN p.age_years < 45 THEN '25-44' WHEN p.age_years < 60 THEN '45-59' ELSE '60+' END AS age_band,
       p.patient_category,
       coalesce(p.blood_group, '') AS blood_group,
       coalesce(p.marital_status, '') AS marital_status,
       c.city,
       coalesce(c.district, '') AS district,
       c.state,
       left(c.mobile, 2) || 'XXXXXX' || right(c.mobile, 2) AS mobile_masked,
       v.dept_id,
       d.dept_name,
       v.consulting_doctor,
       v.visit_type,
       v.priority,
       coalesce(v.referral_source, '') AS referral_source,
       v.payment_mode,
       v.registration_fee,
       CASE WHEN i.has_insurance THEN 1 ELSE 0 END AS is_insured,
       coalesce(i.coverage_type, '') AS coverage_type,
       coalesce(i.provider_name, '') AS insurance_provider,
       p.row_version AS version,
       0 AS is_deleted,
       coalesce(p.updated_at, p.created_at) AS source_ts,
       p.hospital_id,
       h.hospital_code,
       h.name AS hospital_name,
       -- stage 5b: only the hospital's own fields shown in reports (active, reportable, not hidden) leave the
       -- registration database; how they are shown (masked or not) is decided by the reporting module
       coalesce((SELECT jsonb_object_agg(e.key, e.value)
                 FROM jsonb_each(p.custom_fields) e
                 JOIN custom_field_def f ON f.hospital_id = p.hospital_id AND f.field_key = e.key
                 WHERE f.active AND f.reportable AND f.sensitivity <> 'HIDDEN'), '{}'::jsonb) AS custom_fields
FROM patient p
JOIN identity.hospital h ON h.hospital_id = p.hospital_id
JOIN patient_contact c ON c.patient_id = p.patient_id
JOIN patient_visit v ON v.patient_id = p.patient_id AND v.registration_visit
JOIN department d ON d.dept_id = v.dept_id
LEFT JOIN patient_insurance i ON i.patient_id = p.patient_id;

-- Reporting stage 4: transactional outbox (architecture D7, section 13.3). Every save inserts a
-- PatientSnapshot v1 record here and deletes it in the same transaction: the table stays empty, but
-- both changes are in the WAL, where Debezium reads the insert (a publication covers only this table).
CREATE TABLE IF NOT EXISTS report_outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregatetype  VARCHAR(40)  NOT NULL,
    aggregateid    VARCHAR(40)  NOT NULL,
    type           VARCHAR(60)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Debezium updates this row periodically (heartbeat.action.query) so the replication slot keeps
-- advancing while the outbox is quiet; no data of interest lives here.
CREATE TABLE IF NOT EXISTS cdc_heartbeat (
    id  INTEGER PRIMARY KEY,
    ts  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO cdc_heartbeat (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- Stage 5a review: the application role has no access to v_patient_report (an owner-rights view without
-- row-level security). The outbox record of a registration is written through this function instead, which
-- publishes a patient only if it belongs to one of the caller's hospitals (app.hospital_ids).
-- Inserts the patient's snapshot into the outbox and deletes it again (the WAL keeps the insert for Debezium).
-- The application role has no rights on report_outbox at all: it can only publish true snapshots of its own
-- hospitals' patients through this function, never a hand-made payload (review finding). Returns rows published.
DROP FUNCTION IF EXISTS patientreg.publish_snapshot(BIGINT);
CREATE FUNCTION patientreg.publish_snapshot(p_patient_id BIGINT) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = patientreg, pg_temp AS $$
DECLARE
    ids BIGINT[];
BEGIN
    WITH ins AS (
        INSERT INTO patientreg.report_outbox (aggregatetype, aggregateid, type, payload)
        SELECT 'patient', v.patient_id::text, 'PatientSnapshot.v2',
               to_jsonb(v) || jsonb_build_object('source_ts', to_char(v.source_ts, 'YYYY-MM-DD HH24:MI:SS.MS'))
        FROM patientreg.v_patient_report v
        WHERE v.patient_id = p_patient_id AND v.hospital_id = ANY (patientreg.current_hospitals())
        RETURNING id)
    SELECT array_agg(id) INTO ids FROM ins;
    -- a separate statement: a DELETE cannot see rows inserted by a WITH clause of the same statement
    DELETE FROM patientreg.report_outbox WHERE id = ANY (ids);
    RETURN coalesce(array_length(ids, 1), 0);
END
$$;
-- owner-rights functions are callable by the application only, not by every database account
REVOKE ALL ON FUNCTION patientreg.publish_snapshot(BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION patientreg.seed_departments(INTEGER) FROM PUBLIC;

-- ============================================================================================
-- Stage 5b: customisation of the registration form per hospital (architecture section 10.2).
-- ============================================================================================

-- doctors of each department (suggested on the form; the doctor field stays free text)
CREATE TABLE IF NOT EXISTS doctor (
    doctor_id    SERIAL PRIMARY KEY,
    hospital_id  INTEGER      NOT NULL,
    dept_id      INTEGER      NOT NULL,
    name         VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_doctor_department FOREIGN KEY (hospital_id, dept_id) REFERENCES department (hospital_id, dept_id),
    CONSTRAINT uq_doctor UNIQUE (hospital_id, dept_id, name)
);

-- a hospital's own values of a dropdown list; a list without rows uses the built-in values (model/Lookups).
-- Values are never deleted, only deactivated, so existing records keep a valid value.
CREATE TABLE IF NOT EXISTS lookup_value (
    hospital_id  INTEGER     NOT NULL REFERENCES identity.hospital (hospital_id),
    list_key     VARCHAR(40) NOT NULL,
    value        VARCHAR(60) NOT NULL,
    sort_order   INTEGER     NOT NULL DEFAULT 0,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (hospital_id, list_key, value)
);

-- optional standard form fields a hospital does not use
CREATE TABLE IF NOT EXISTS form_field_setting (
    hospital_id  INTEGER     NOT NULL REFERENCES identity.hospital (hospital_id),
    field        VARCHAR(40) NOT NULL,
    hidden       BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (hospital_id, field)
);

-- (custom_field_def, the hospitals' own form fields, is created above, before v_patient_report reads it)

-- the values of custom fields: {"field_key": "text value", ...}, validated by the application against the definitions
ALTER TABLE patient ADD COLUMN IF NOT EXISTS custom_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['doctor', 'lookup_value', 'form_field_setting', 'custom_field_def'] LOOP
        EXECUTE format('ALTER TABLE patientreg.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE patientreg.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS hospital_isolation ON patientreg.%I', t);
        EXECUTE format('CREATE POLICY hospital_isolation ON patientreg.%I '
                       'USING (hospital_id = ANY ((SELECT patientreg.current_hospitals())::INTEGER[])) '
                       'WITH CHECK (hospital_id = ANY ((SELECT patientreg.current_hospitals())::INTEGER[]))', t);
    END LOOP;
END $$;
