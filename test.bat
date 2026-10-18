@echo off
rem Runs all automated tests (unit, web smoke, architecture rules). Needs the Postgres container running;
rem tests use the separate "prtest" database, never the live data.
call "%~dp0env.bat"
cd /d "%PROJECT_HOME%"
call mvn -B -Dmaven.repo.local="%PROJECT_HOME%\tools\m2repo" test
