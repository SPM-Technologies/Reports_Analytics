#!/usr/bin/env bash
# One-off migration of an existing ClickHouse to stage 5b: the custom_fields column (hospitals' own form
# fields). The copy jobs and the Kafka consumer list their columns, so they are re-created; data stays.
# Run from the project folder while the containers run:  bash infra/clickhouse/migrations/2026-10-16_stage5b_custom_fields.sh
set -euo pipefail
cd "$(dirname "$0")/../../.."
PASS=$(grep '^CLICKHOUSE_ADMIN_PASSWORD=' .env | cut -d= -f2)
ch() { docker exec -i pr-clickhouse clickhouse-client --user admin --password "$PASS" "$@"; }

ch --multiquery <<'SQL'
DROP VIEW IF EXISTS analytics.mv_patient_report_sync;
DROP VIEW IF EXISTS analytics.mv_kafka_patient_report;
DROP VIEW IF EXISTS analytics.mv_kafka_patient_report_errors;
DROP TABLE IF EXISTS analytics.kafka_patient_report;
SQL
bash infra/clickhouse-schema.sh                      # adds the column to analytics and analytics_test
ch --multiquery < infra/clickhouse/pipeline/010_incremental_copy.sql
if docker ps --format '{{.Names}}' | grep -qx pr-kafka; then
  ch --multiquery < infra/clickhouse/pipeline/020_realtime_kafka.sql
fi
ch --multiquery < infra/clickhouse/pipeline/backfill.sql     # fill custom_fields of existing rows
echo "done"
