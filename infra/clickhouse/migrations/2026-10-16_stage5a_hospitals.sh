#!/usr/bin/env bash
# One-off migration of an existing ClickHouse to stage 5a (hospital columns, hospital-first sort key).
# The sort key of a MergeTree table cannot be changed in place, so patient_report is re-created in
# analytics and analytics_test and refilled from PostgreSQL. ClickHouse holds only a copy: nothing is lost.
# Run from the project folder while the containers run:   bash infra/clickhouse/migrations/2026-10-16_stage5a_hospitals.sh
# Fresh installations do not need it (the schema files already have the new layout).
set -euo pipefail
cd "$(dirname "$0")/../../.."
PASS=$(grep '^CLICKHOUSE_ADMIN_PASSWORD=' .env | cut -d= -f2)
ch() { docker exec -i pr-clickhouse clickhouse-client --user admin --password "$PASS" "$@"; }

echo "1/4 dropping the pipeline views and the old tables"
ch --multiquery <<'SQL'
DROP VIEW IF EXISTS analytics.mv_patient_report_sync;
DROP VIEW IF EXISTS analytics.mv_kafka_patient_report;
DROP VIEW IF EXISTS analytics.mv_kafka_patient_report_errors;
DROP TABLE IF EXISTS analytics.kafka_patient_report;
DROP TABLE IF EXISTS analytics.patient_report SYNC;
DROP TABLE IF EXISTS analytics_test.patient_report SYNC;
SQL

echo "2/4 creating the new tables"
bash infra/clickhouse-schema.sh

echo "3/4 copying all patients from PostgreSQL"
ch --multiquery < infra/clickhouse/pipeline/backfill.sql
ch --multiquery < infra/clickhouse/pipeline/010_incremental_copy.sql

echo "4/4 real-time consumer (only if Kafka runs: docker compose --profile cdc)"
if docker ps --format '{{.Names}}' | grep -qx pr-kafka; then
  ch --multiquery < infra/clickhouse/pipeline/020_realtime_kafka.sql
fi
ch -q "SELECT hospital_code, count() FROM analytics.patient_report FINAL WHERE is_deleted = 0 GROUP BY hospital_code ORDER BY hospital_code"
