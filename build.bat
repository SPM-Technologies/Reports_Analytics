@echo off
call "%~dp0env.bat"
cd /d "%PROJECT_HOME%"
call mvn -B -Dmaven.repo.local="%PROJECT_HOME%\tools\m2repo" clean package
