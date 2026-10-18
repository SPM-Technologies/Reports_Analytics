#!/usr/bin/env bash
# Applies the analytics DDL (idempotent) to the live and test databases, as the ClickHouse admin user.
set -euo pipefail
cd "$(dirname "$0")"
PASS=$(grep CLICKHOUSE_ADMIN_PASSWORD ../.env | cut -d= -f2)
for db in analytics analytics_test; do
  for f in clickhouse/schema/*.sql; do
    docker exec -i pr-clickhouse clickhouse-client --user admin --password "$PASS" --database "$db" --multiquery < "$f"
  done
  echo "schema applied to $db"
done
