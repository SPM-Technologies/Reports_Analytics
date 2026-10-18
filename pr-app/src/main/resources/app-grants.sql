-- Privileges of the application role (__APP_ROLE__ is replaced by db.username). Applied last at every
-- startup by SchemaMigrator, so tables added by any script are covered. Least privilege:
--   * patient tables: read, insert, update (no delete); row-level security limits rows to the user's hospitals
--   * the audit log: insert and read only (append-only)
--   * report_outbox: insert and delete (rows live only inside the registration transaction)
GRANT USAGE ON SCHEMA identity, patientreg, reporting TO __APP_ROLE__;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA identity, patientreg, reporting TO __APP_ROLE__;

GRANT SELECT, INSERT, UPDATE ON identity.hospital, identity.app_user TO __APP_ROLE__;
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.app_user_hospital, identity.role TO __APP_ROLE__;
GRANT SELECT, INSERT ON identity.audit_log TO __APP_ROLE__;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA patientreg TO __APP_ROLE__;
-- the outbox is written only by patientreg.publish_snapshot (owner rights): no hand-made payloads
REVOKE ALL ON patientreg.report_outbox FROM __APP_ROLE__;
-- the published view runs with its owner's rights (no row-level security): pipeline accounts only
REVOKE ALL ON patientreg.v_patient_report FROM __APP_ROLE__;
GRANT EXECUTE ON FUNCTION patientreg.seed_departments(INTEGER), patientreg.publish_snapshot(BIGINT),
    patientreg.current_hospitals() TO __APP_ROLE__;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reporting TO __APP_ROLE__;
GRANT EXECUTE ON FUNCTION reporting.reports_using_shared_field(BIGINT) TO __APP_ROLE__;

-- the pipeline's read-only account (created by the docker compose pg-setup service) keeps its access to
-- the published view, which the registration script may have re-created
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reporting_ro') THEN
        GRANT USAGE ON SCHEMA patientreg TO reporting_ro;
        GRANT SELECT ON patientreg.v_patient_report TO reporting_ro;
    END IF;
END $$;
