#!/bin/bash
# First start of an empty data volume: create the analytics tables in the live and test databases.
set -e
for db in analytics analytics_test; do
  for f in /schema/*.sql; do
    clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$db" --multiquery < "$f"
  done
done
