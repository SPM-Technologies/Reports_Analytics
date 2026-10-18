@echo off
rem Starts the whole system in Docker (builds the app image when the code changed).
rem   start.bat        app + PostgreSQL + ClickHouse (reports refresh every minute)
rem   start.bat cdc    also Kafka + Debezium (reports refresh within seconds)
cd /d "%~dp0"
if /I "%~1"=="cdc" (
    docker compose --profile cdc up -d --build
) else (
    docker compose up -d --build
)
if errorlevel 1 (
    echo Start FAILED - see the messages above.
    exit /b 1
)
echo Open http://localhost:8080/patient-registration/   (Analytics: /patient-registration/app/)
