@echo off
rem Rebuilds the application image from the current code and replaces the running app container.
rem The databases keep running. App URL: http://localhost:8080/patient-registration/
cd /d "%~dp0"
docker compose up -d --build app
if errorlevel 1 (
    echo Deploy FAILED.
    exit /b 1
)
echo The application restarts in about 15 seconds: http://localhost:8080/patient-registration/
