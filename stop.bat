@echo off
rem Stops all containers. Data stays in the Docker volumes; start.bat brings everything back.
cd /d "%~dp0"
docker compose --profile cdc --profile seed stop
