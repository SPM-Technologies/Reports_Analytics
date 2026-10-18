@echo off
rem Loads bulk test patients into PostgreSQL, then reloads the analytics copy (bulk SQL bypasses the
rem outbox, so ClickHouse is refreshed by the backfill).
rem Usage: load-data.bat [rows] [hospital]     defaults: 100000 rows into hospital 1 (H001)
rem Demo data set of the project guide:  load-data.bat 100000 1, load-data.bat 20000 2, load-data.bat 10000 3
setlocal
cd /d "%~dp0"
set "ROWS=%~1"
if "%ROWS%"=="" set "ROWS=100000"
set "HOSPITAL=%~2"
if "%HOSPITAL%"=="" set "HOSPITAL=1"
set "SEED_ROWS=%ROWS%"
set "SEED_HOSPITAL=%HOSPITAL%"
echo Loading %ROWS% patients into hospital %HOSPITAL% ...
rem "run" returns the container's exit code (starts PostgreSQL and the app first if needed)
docker compose --profile seed run --rm seed
if errorlevel 1 goto failed
docker compose --profile seed run --rm --no-deps seed-backfill
if errorlevel 1 goto failed
echo Done.
exit /b 0

:failed
echo Load FAILED.
exit /b 1
