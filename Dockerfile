# syntax=docker/dockerfile:1
# Patient Registration web application: built and run entirely in Docker (no local Java/Maven/Node needed).

# ---- build stage: Maven + JDK 21; the pr-ui module downloads Node itself (frontend-maven-plugin) ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
ENV MAVEN_OPTS="-Xmx512m"
COPY . .
# Tests need the databases, so they run separately (test.bat / CI), not in the image build.
RUN --mount=type=cache,target=/root/.m2 --mount=type=cache,target=/root/.npm \
    mvn -B -q -DskipTests package

# ---- runtime stage: Tomcat 11 on JDK 21 with only the WAR ----
FROM tomcat:11.0.27-jdk21-temurin
ENV TZ=Asia/Kolkata \
    CATALINA_OPTS="-Xms256m -Xmx512m -Duser.timezone=Asia/Kolkata"
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/pr-app/target/patient-registration.war /usr/local/tomcat/webapps/patient-registration.war
EXPOSE 8080
