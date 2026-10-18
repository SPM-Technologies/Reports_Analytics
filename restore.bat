@echo off
rem Restores a backup made by backup.bat into the running system, replacing ALL current data:
rem   restore.bat backup\patientreg_full_2026-10-16_after-stage5.dump
rem 1. stops the app (and Debezium), 2. drops schemas identity, patientreg, reporting, 3. restores the dump,
rem 4. empties ClickHouse, 5. starts everything again: the app re-creates its role and grants, pg-setup the
rem    pipeline accounts and publication, analytics-setup reloads ClickHouse from PostgreSQL.
setlocal
cd /d "%~dp0"
if "%~1"=="" (
    echo Usage: restore.bat path\to\backup.dump
    exit /b 1
)
if not exist "%~1" (
    echo File not found: %~1
    exit /b 1
)
echo This REPLACES all patients, users, hospitals, settings and saved reports with the backup "%~1".
set /p "OK=Type YES to continue: "
if /I not "%OK%"=="YES" (
    echo Cancelled.
    exit /b 1
)
docker compose stop app debezium >nul 2>&1
docker exec pr-postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c "DROP SCHEMA IF EXISTS identity, patientreg, reporting CASCADE"
if errorlevel 1 goto failed
docker exec -i pr-postgres pg_restore -U postgres -d postgres --no-owner --no-acl < "%~1"
if errorlevel 1 goto failed
docker exec pr-clickhouse sh -c "clickhouse-client --user admin --password \"$CLICKHOUSE_PASSWORD\" -q 'TRUNCATE TABLE analytics.patient_report'"
if errorlevel 1 goto failed
docker compose up -d
if errorlevel 1 goto failed
echo Restored. Analytics is reloaded by analytics-setup (see: docker compose logs analytics-setup).
echo If you use the real-time pipeline, start it again with: start.bat cdc
exit /b 0

:failed
echo Restore FAILED. See the messages above.
exit /b 1
