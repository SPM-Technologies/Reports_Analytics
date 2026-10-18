@echo off
rem Shared paths: JDK and Tomcat from D:\Pravin, Maven bundled inside this project.
set "PROJECT_HOME=%~dp0"
set "PROJECT_HOME=%PROJECT_HOME:~0,-1%"
set "JAVA_HOME=D:\Pravin\jdk-21.0.12.1"
set "CATALINA_HOME=D:\Pravin\apache-tomcat-11.0.27"
set "MAVEN_HOME=%PROJECT_HOME%\tools\apache-maven-3.9.9"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
rem Docker containers (docker-compose.yml)
set "PG_CONTAINER=pr-postgres"
rem Memory caps for local Maven builds and tests (the app itself runs in Docker)
set "CATALINA_OPTS=-Xms256m -Xmx768m"
set "MAVEN_OPTS=-Xmx512m"
