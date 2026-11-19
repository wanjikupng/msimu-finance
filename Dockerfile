# syntax=docker/dockerfile:1
#
# M-Simu Finance — single-image deployment.
#
# Stage 1 builds the Spring Boot jar with Java 17 + Maven. The frontend has no
# build step: maven-resources-plugin copies the root HTML/CSS/ES modules into
# the jar's static resources, so one process serves both the pages and the API.
#
# Stage 2 is a slim JRE image running as an unprivileged user. The application
# reads PORT from the environment (Render injects it) and binds 0.0.0.0.

############################
# Stage 1 — build
############################
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace

# Resolve dependencies in their own layer so ordinary source edits do not
# re-download the Maven world. Some plugin metadata is resolved lazily, hence
# the tolerant fallback: the package step below still fetches anything missing.
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline || true

# Application sources + the step-free frontend.
COPY . .

# Tests run in CI/verification, not inside the image build.
RUN mvn -B -DskipTests package

############################
# Stage 2 — runtime
############################
FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/*.jar /app/app.jar

# PORT is injected by Render (default 10000). SPRING_PROFILES_ACTIVE=prod turns
# off the H2 console, requires TRACOM_JWT_SECRET and tightens error responses.
ENV PORT=10000 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -Dfile.encoding=UTF-8"

USER app
EXPOSE 10000

# exec keeps the JVM as PID 1 so platform shutdown signals reach it directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
