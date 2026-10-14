# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build the jar
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The Maven wrapper and the POM first, on their own, so that the dependency
# download below lands in a Docker layer keyed only on the POM. Editing a Java
# file then reuses that layer instead of re-downloading ~60MB of artifacts.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

# Sources last, because they change on every commit and invalidate every layer
# after them.
COPY src/ src/

# Tests are skipped deliberately: they need Docker (Testcontainers), and this
# build runs *inside* a container with no Docker socket. CI runs ./mvnw verify
# on every push, which is the right place for it.
RUN ./mvnw -B -DskipTests package

# Explode the fat jar so its contents can be copied as separate Docker layers
# below. --launcher keeps Spring Boot's own loader, so the runtime entry point
# is JarLauncher rather than a hand-built classpath.
RUN java -Djarmode=tools -jar target/*.jar extract --launcher --destination extracted

# ---------------------------------------------------------------------------
# Stage 2 — run it
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

# A system account with no login shell and no home directory. The JVM needs
# neither, and a process that is not root cannot rewrite the application it is
# running if it is ever compromised. /app is chowned before the switch, because
# a directory created by WORKDIR belongs to root.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin spring \
    && chown spring:spring /app
USER spring

# Four copies rather than one, ordered from least to most likely to change.
# Each is its own layer, so a deploy that only touches application code pushes
# and pulls kilobytes instead of the whole 57MB jar.
COPY --from=build --chown=spring:spring /build/extracted/BOOT-INF/lib     BOOT-INF/lib
COPY --from=build --chown=spring:spring /build/extracted/org              org
COPY --from=build --chown=spring:spring /build/extracted/META-INF         META-INF
COPY --from=build --chown=spring:spring /build/extracted/BOOT-INF/classes BOOT-INF/classes

# Documentation only -- it publishes nothing by itself. The real port comes from
# server.port=${PORT:8080}, which Railway sets.
EXPOSE 8080

# MaxRAMPercentage because the JVM's default is 25% of the container limit: on a
# 512MB Railway instance that is a 128MB heap, three quarters of the memory paid
# for sitting unused. ExitOnOutOfMemoryError so the orchestrator restarts a dead
# JVM instead of leaving it to thrash.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# exec form (a JSON array), not shell form. Shell form wraps the process in
# /bin/sh, which does not forward SIGTERM, so the JVM would never get its
# shutdown hook and the container would be SIGKILLed after the grace period --
# dropping in-flight requests and the connection pool's clean shutdown.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
