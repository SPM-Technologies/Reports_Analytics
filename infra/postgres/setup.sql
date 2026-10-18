-- Runs at every start (pg-setup service), after the application has created its schema. Idempotent.
-- Accounts used by the reporting pipeline, the outbox publication, and the test database.
\set ON_ERROR_STOP on
SELECT 'CREATE DATABASE prtest' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'prtest') \gexec

-- read-only account for ClickHouse (backfill and one-minute sync read the published view only)
SELECT format('CREATE ROLE reporting_ro LOGIN PASSWORD %L', :'ro_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reporting_ro') \gexec
-- a changed password in .env applies on the next start
SELECT format('ALTER ROLE reporting_ro PASSWORD %L', :'ro_password') \gexec
GRANT CONNECT ON DATABASE postgres TO reporting_ro;
GRANT USAGE ON SCHEMA patientreg TO reporting_ro;
GRANT SELECT ON patientreg.v_patient_report TO reporting_ro;

-- replication account for Debezium: reads the outbox from the WAL, updates the heartbeat row
SELECT format('CREATE ROLE debezium LOGIN REPLICATION PASSWORD %L', :'dbz_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'debezium') \gexec
SELECT format('ALTER ROLE debezium PASSWORD %L', :'dbz_password') \gexec
GRANT CONNECT ON DATABASE postgres TO debezium;
GRANT USAGE ON SCHEMA patientreg TO debezium;
GRANT SELECT ON patientreg.report_outbox TO debezium;
GRANT SELECT, UPDATE ON patientreg.cdc_heartbeat TO debezium;

-- publication on the outbox ONLY (architecture section 14): Debezium never sees the patient tables
SELECT 'CREATE PUBLICATION pr_outbox_pub FOR TABLE patientreg.report_outbox'
WHERE NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'pr_outbox_pub') \gexec
-- after a restore (schemas dropped and re-created) the publication would be empty: always point it at the outbox
ALTER PUBLICATION pr_outbox_pub SET TABLE patientreg.report_outbox;
