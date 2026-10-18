@echo off
rem Full backup (structure + all data) of schemas identity, patientreg and reporting:
rem   backup\patientreg_full_YYYY-MM-DD_HHmm.dump
rem The pg_trgm extension is included so the backup restores into an empty database. ClickHouse needs no
rem backup: it is rebuilt from PostgreSQL (restore.bat does that).
call "%~dp0env.bat"
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HHmm"') do set "STAMP=%%d"
if not exist "%PROJECT_HOME%\backup" mkdir "%PROJECT_HOME%\backup"
set "FILE=%PROJECT_HOME%\backup\patientreg_full_%STAMP%.dump"
echo Backing up schemas identity, patientreg and reporting to %FILE% ...
docker exec %PG_CONTAINER% pg_dump -U postgres -d postgres -n identity -n patientreg -n reporting --extension=pg_trgm -Fc -Z 6 > "%FILE%"
if errorlevel 1 (
    echo Backup FAILED.
    exit /b 1
)
echo Done. To restore it later:  restore.bat "%FILE%"
