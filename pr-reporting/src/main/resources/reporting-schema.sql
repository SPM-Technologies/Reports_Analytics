-- Reporting module metadata in PostgreSQL (its own schema; never the registration tables).
-- Applied at every startup by the administrator connection (pr-app SchemaMigrator) as one script, after
-- the registration schema; must stay idempotent.
CREATE SCHEMA IF NOT EXISTS reporting;

CREATE TABLE IF NOT EXISTS reporting.saved_report (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(400),
    definition    TEXT         NOT NULL,          -- {"query": <report definition>, "view": <screen settings>}
    template_key  VARCHAR(40)  UNIQUE,            -- set only for built-in templates (read-only)
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- Stage 5a: a saved report belongs to a hospital (shared by that hospital's report users) and remembers its
-- author; templates (template_key set) have no hospital and are visible to everyone. Reports saved before
-- stage 5a belong to hospital 1.
ALTER TABLE reporting.saved_report ADD COLUMN IF NOT EXISTS hospital_id INTEGER;
ALTER TABLE reporting.saved_report ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE reporting.saved_report ADD COLUMN IF NOT EXISTS created_by_name VARCHAR(100);
UPDATE reporting.saved_report SET hospital_id = 1 WHERE template_key IS NULL AND hospital_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_saved_report_hospital ON reporting.saved_report (hospital_id);

-- Built-in templates: today's Report 1 and Report 2 plus two starter views. Kept in sync with the code.
INSERT INTO reporting.saved_report (template_key, name, description, definition) VALUES
('patient-register', 'Patient Register (Report 1)', 'Registered patients, newest first - the classic register list.',
 '{"query":{"dataset":"patients","columns":["registration_no","registration_date","patient_name","gender","age_years","mobile_masked","city","state","dept_name","consulting_doctor","visit_type","patient_category","is_insured","registration_fee"],"pageSize":50},"view":{"mode":"table"}}'),
('department-summary', 'Department Summary (Report 2)', 'Patients, insured patients, average age and fees per department.',
 '{"query":{"dataset":"patients","columns":["dept_name"],"measures":[{"field":"patients"},{"field":"is_insured","agg":"sum"},{"field":"age_years","agg":"avg"},{"field":"registration_fee","agg":"sum"}],"sort":[{"field":"dept_name","dir":"asc"}],"pageSize":50},"view":{"mode":"table"}}'),
('department-gender', 'Patients by Department and Gender', 'Head-count per department split by gender.',
 '{"query":{"dataset":"patients","columns":["dept_name","gender"],"measures":[{"field":"patients"}],"sort":[{"field":"dept_name","dir":"asc"},{"field":"gender","dir":"asc"}],"pageSize":100},"view":{"mode":"chart","chart":"bar"}}'),
('monthly-trend', 'Monthly Registrations', 'Registrations and fees month by month.',
 '{"query":{"dataset":"patients","columns":["registration_month"],"measures":[{"field":"patients"},{"field":"registration_fee","agg":"sum"}],"sort":[{"field":"registration_month","dir":"asc"}],"pageSize":100},"view":{"mode":"chart","chart":"line"}}')
ON CONFLICT (template_key) DO UPDATE
    SET name = EXCLUDED.name, description = EXCLUDED.description, definition = EXCLUDED.definition, updated_at = now();
;

-- ============================================================================================
-- Stage 5b: customisation of reports per hospital (architecture section 10.2)
-- ============================================================================================

-- how a standard dataset field appears in a hospital's reports: own label, masked, hidden, administrators only
CREATE TABLE IF NOT EXISTS reporting.field_setting (
    hospital_id  INTEGER     NOT NULL,
    dataset_id   VARCHAR(40) NOT NULL,
    field        VARCHAR(63) NOT NULL,
    label        VARCHAR(60),
    visibility   VARCHAR(12) NOT NULL DEFAULT 'NORMAL' CHECK (visibility IN ('NORMAL', 'MASKED', 'HIDDEN', 'ADMIN_ONLY')),
    updated_at   TIMESTAMP   NOT NULL DEFAULT now(),
    PRIMARY KEY (hospital_id, dataset_id, field)
);

-- calculated fields shared with everyone in a hospital (a report's own formulas live in its definition)
CREATE TABLE IF NOT EXISTS reporting.calculated_field (
    id               BIGSERIAL PRIMARY KEY,
    hospital_id      INTEGER       NOT NULL,
    dataset_id       VARCHAR(40)   NOT NULL,
    label            VARCHAR(60)   NOT NULL,
    formula          VARCHAR(2000) NOT NULL,
    description      VARCHAR(200),
    result_type      VARCHAR(10)   NOT NULL,
    aggregate        BOOLEAN       NOT NULL,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    version          INTEGER       NOT NULL DEFAULT 1,
    created_by       BIGINT,
    created_by_name  VARCHAR(100),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_calculated_field_label UNIQUE (hospital_id, dataset_id, label)
);

-- a saved report is private to its author until shared with the hospital
ALTER TABLE reporting.saved_report ADD COLUMN IF NOT EXISTS visibility VARCHAR(10) NOT NULL DEFAULT 'HOSPITAL'
    CHECK (visibility IN ('PRIVATE', 'HOSPITAL'));

-- dashboards: several saved reports (tiles) with shared filters, private or shared like reports
CREATE TABLE IF NOT EXISTS reporting.dashboard (
    id               BIGSERIAL PRIMARY KEY,
    hospital_id      INTEGER      NOT NULL,
    name             VARCHAR(120) NOT NULL,
    description      VARCHAR(400),
    definition       TEXT         NOT NULL,    -- {"tiles": [...], "filters": [...]}
    visibility       VARCHAR(10)  NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'HOSPITAL')),
    created_by       BIGINT,
    created_by_name  VARCHAR(100),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);

-- a hospital's downloads: title, header and footer lines, logo, and the formats users without administration may use
CREATE TABLE IF NOT EXISTS reporting.export_setting (
    hospital_id      INTEGER PRIMARY KEY,
    title_prefix     VARCHAR(80),
    header_text      VARCHAR(200),
    footer_text      VARCHAR(200),
    logo             BYTEA,
    logo_type        VARCHAR(20),
    allowed_formats  VARCHAR(30) NOT NULL DEFAULT 'xlsx,pdf,csv',
    updated_at       TIMESTAMP   NOT NULL DEFAULT now()
);

-- Row-level security as in the registration schema (the reporting metadata pool also carries the user's
-- hospitals and user id). Templates (no hospital) are visible to all; private items only to their author.
CREATE OR REPLACE FUNCTION reporting.current_hospitals() RETURNS INTEGER[]
LANGUAGE sql STABLE AS $$
    SELECT coalesce(string_to_array(nullif(current_setting('app.hospital_ids', true), ''), ',')::INTEGER[], '{}'::INTEGER[])
$$;
CREATE OR REPLACE FUNCTION reporting.current_user_id() RETURNS BIGINT
LANGUAGE sql STABLE AS $$
    SELECT nullif(current_setting('app.user_id', true), '')::BIGINT
$$;

-- How many saved reports (including colleagues' private ones, which row-level security hides) use a shared
-- calculated field: a count only, never names, and only for a field of one of the caller's hospitals.
CREATE OR REPLACE FUNCTION reporting.reports_using_shared_field(p_field_id BIGINT) RETURNS BIGINT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = reporting, pg_temp AS $$
    SELECT count(*) FROM reporting.saved_report r
    WHERE EXISTS (SELECT 1 FROM reporting.calculated_field c
                  WHERE c.id = p_field_id AND c.hospital_id = ANY (reporting.current_hospitals()))
      AND r.definition LIKE '%"sf\_' || p_field_id || '"%'   -- \_ : a literal underscore (default LIKE escape)
$$;
REVOKE ALL ON FUNCTION reporting.reports_using_shared_field(BIGINT) FROM PUBLIC;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['saved_report', 'dashboard', 'field_setting', 'calculated_field', 'export_setting'] LOOP
        EXECUTE format('ALTER TABLE reporting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE reporting.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS hospital_isolation ON reporting.%I', t);
    END LOOP;
    -- saved reports per command: templates (no hospital) can be read but never changed or deleted by the app
    DROP POLICY IF EXISTS report_read ON reporting.saved_report;
    DROP POLICY IF EXISTS report_insert ON reporting.saved_report;
    DROP POLICY IF EXISTS report_update ON reporting.saved_report;
    DROP POLICY IF EXISTS report_delete ON reporting.saved_report;
    CREATE POLICY report_read ON reporting.saved_report FOR SELECT
        USING (hospital_id IS NULL OR (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[])
               AND (visibility = 'HOSPITAL' OR created_by = (SELECT reporting.current_user_id()))));
    CREATE POLICY report_insert ON reporting.saved_report FOR INSERT
        WITH CHECK (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[]));
    CREATE POLICY report_update ON reporting.saved_report FOR UPDATE
        USING (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[])
               AND (visibility = 'HOSPITAL' OR created_by = (SELECT reporting.current_user_id())))
        WITH CHECK (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[]));
    CREATE POLICY report_delete ON reporting.saved_report FOR DELETE
        USING (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[])
               AND (visibility = 'HOSPITAL' OR created_by = (SELECT reporting.current_user_id())));
    CREATE POLICY hospital_isolation ON reporting.dashboard
        USING (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[])
               AND (visibility = 'HOSPITAL' OR created_by = (SELECT reporting.current_user_id())))
        WITH CHECK (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[]));
    FOREACH t IN ARRAY ARRAY['field_setting', 'calculated_field', 'export_setting'] LOOP
        EXECUTE format('CREATE POLICY hospital_isolation ON reporting.%I '
                       'USING (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[])) '
                       'WITH CHECK (hospital_id = ANY ((SELECT reporting.current_hospitals())::INTEGER[]))', t);
    END LOOP;
END $$;
