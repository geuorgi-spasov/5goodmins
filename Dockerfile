# ---------------------------------------------------------------------------
# Stage 1 — build the jar
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The POM on its own first, so the dependency download below lands in an image
# layer keyed only on the POM. Editing a Java file reuses that layer instead of
# re-fetching the tree.
#
# No BuildKit cache mount. Railway's builder is not BuildKit -- it has its own
# Dockerfile parser, which rejected `--mount=type=cache` outright ("missing an
# id argument"). Even with an id it would not have helped there: a cache mount
# lives on the build machine and Railway's builders are throwaway, while the
# jars it holds never reach an image layer, so nothing carries over. Plain
# layer caching works on every builder, which is the property that matters for
# the one that deploys this.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/

# Tests are skipped here on purpose: they use Testcontainers, which needs a
# Docker daemon, and there is none inside a Docker build. The full suite runs
# in GitHub Actions (./mvnw verify); only a green build should deploy.
RUN ./mvnw -B -DskipTests package

# target/app.jar, not target/*.jar: the POM pins <finalName>, so there is no
# glob to match a second jar the day a sources or javadoc plugin is added.
RUN java -Djarmode=tools -jar target/app.jar extract --launcher --destination extracted

# ---------------------------------------------------------------------------
# Stage 2 — run it
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --no-create-home --shell /usr/sbin/nologin spring \
    && chown spring:spring /app
USER spring

COPY --from=build --chown=spring:spring /build/extracted/BOOT-INF/lib           BOOT-INF/lib
COPY --from=build --chown=spring:spring /build/extracted/org                    org
COPY --from=build --chown=spring:spring /build/extracted/META-INF               META-INF
COPY --from=build --chown=spring:spring /build/extracted/BOOT-INF/classes       BOOT-INF/classes
COPY --from=build --chown=spring:spring /build/extracted/BOOT-INF/classpath.idx BOOT-INF/classpath.idx

EXPOSE 8080

# Absolute cap, not MaxRAMPercentage: Railway bills per GB-second, and a heap
# sized as a percentage of the container limit grows to fill it and never
# shrinks. Raise only if the memory graph actually says so.
#
# -Xms128m rather than 256m because the app idles most of the time and the
# initial size only affects the first seconds of heap resizing. The opposite
# convention -- Xms equal to Xmx -- is the right one when predictability
# matters more than footprint; here footprint is what is being billed.
#
# -Xmx512m is the heap only. Metaspace, code cache, thread stacks and direct
# buffers sit outside it, so expect roughly 650-750MB resident. Any memory
# limit set on the service belongs there, not at 512.
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
