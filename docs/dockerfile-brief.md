# Dockerfile brief — for a voice conversation

Self-contained context for discussing this Dockerfile out loud. Paste or attach
this whole file at the start, then say the opening line below.

> **Say this first:** "I'm a Java backend developer rebuilding after three years
> away, job-hunting. This is the Dockerfile for my portfolio project. I need to
> be able to defend every line in an interview. Quiz me on it — ask me why a
> line is there, let me answer, then tell me what I missed or got wrong. Don't
> lecture me through it; make me do the explaining. Be blunt when I'm vague."

---

## 1. The project, in five lines

**5 Good Minutes** (5goodmins.com) — a Bulgarian-language directory of small
activism actions: petitions, emails to officials, surveys. Users browse by
category and region, subscribe, and get a daily email digest. Every action links
out to the platform that hosts it; the site hosts none of them.

Portfolio project, ~9-week build, possibly a real product. Deployed to **Railway
Hobby** (~$5/month) behind Cloudflare.

**Stack:** Java 21 · Spring Boot 4.1.1 · Maven · PostgreSQL 18 · Thymeleaf +
htmx · Flyway · Spring Security 7 · Hibernate 7 · Testcontainers 2.x · JUnit 6.
Server-rendered, session auth, one modular monolith. No microservices, no Kafka,
no Redis, no Elasticsearch, no SPA — all explicitly rejected with reasons.

**Two things that matter for this conversation:**
- The tests use **Testcontainers**, so they need a Docker socket to run.
- Spring Boot **4**, not 3. Most examples online are for 3 and some of what they
  say about Docker is now wrong (see §5).

---

## 2. The file

```dockerfile
# syntax=docker/dockerfile:1

# ── Stage 1 — build the jar ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package
RUN java -Djarmode=tools -jar target/*.jar extract --launcher --destination extracted

# ── Stage 2 — run it ─────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --no-create-home --shell /usr/sbin/nologin spring \
    && chown spring:spring /app
USER spring

COPY --from=build --chown=spring:spring /build/extracted/BOOT-INF/lib     BOOT-INF/lib
COPY --from=build --chown=spring:spring /build/extracted/org              org
COPY --from=build --chown=spring:spring /build/extracted/META-INF         META-INF
COPY --from=build --chown=spring:spring /build/extracted/BOOT-INF/classes BOOT-INF/classes

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

Alongside it: `.dockerignore` excludes `target/`, `.git/`, `.github/`, `.idea/`,
`.env`, `.env.*`, `docs/`, `*.md`, `compose.yaml`. And `application.yaml` has
`server.port: ${PORT:8080}`, because Railway assigns the port at runtime.

---

## 3. The reason for each line, in one sentence

| Line | Reason |
|---|---|
| `# syntax=docker/dockerfile:1` | Pins the Dockerfile *frontend* (the parser), so newer syntax works regardless of the local Docker version. Not the daemon — the parser. |
| `FROM …:21-jdk AS build` | TCK-certified free OpenJDK; `21` must match `<java.version>` in the POM or you get `UnsupportedClassVersionError`. `AS build` names the stage. |
| `WORKDIR /build` | Each `RUN` is its own shell, so `cd` would not persist. Also keeps my files out of `/etc`, `/usr`, `/bin`. |
| `COPY .mvn/` + `COPY mvnw pom.xml` + `dependency:go-offline` | **The most important thing in the file.** A cache miss invalidates every later layer, so the ~60MB dependency download goes in a layer keyed only on the POM. Editing a Java file then reuses it. `-B` is batch mode: no colour codes, no progress spam, readable CI logs. |
| `COPY src/ src/` | Last, because sources change every commit. `src/` not `.`, so `target/` and `.git/` never enter the image. |
| `-DskipTests package` | The tests need a Docker socket (Testcontainers) and a Docker *build* has none. **CI is the quality gate, not the image build** — `./mvnw verify` runs on every push. |
| `jarmode=tools … extract --launcher` | Explodes the fat jar so its parts become separate Docker layers. As one 57MB file, a one-line code change re-pushes all 57MB. `--launcher` keeps Boot's loader so the entry point is `JarLauncher`. |
| `FROM …:21-jre` | Stage 2 discards everything not copied: no JDK, no Maven, no `~/.m2`, **no source code** in production. Smaller image *and* smaller attack surface. |
| `useradd --system …` / `USER spring` | Containers run as root by default. An unprivileged process cannot rewrite the application it is running. `--system` (no password ageing), `--no-create-home` (the JVM needs no home), `nologin` (no shell if compromised), one `RUN` because each is a layer. |
| `chown spring:spring /app` | A directory created by `WORKDIR` belongs to **root**. Without this, anything the app writes there fails with a permission error that looks nothing like its cause. |
| Four `COPY --from=build` | Ordered least-to-most volatile: `lib` (~55MB, changes with the POM), `org` (~200KB, with a Boot upgrade), `META-INF` (never), `classes` (~100KB, **every commit**). A routine deploy moves ~100KB, not 57MB. |
| `--chown=` on the COPY | Sets ownership at copy time. A later `RUN chown -R` would duplicate every file into a new layer and roughly double the image. |
| `EXPOSE 8080` | **Does nothing functional** — metadata for humans and `docker run -P`. The real port is `server.port=${PORT:8080}`. |
| `-XX:MaxRAMPercentage=75` | The JVM reads the cgroup limit, but **defaults to 25%** — on a 512MB instance that is a 128MB heap and 384MB paid for and unused. 75% leaves room for metaspace, thread stacks, code cache; 90% gets you OOM-killed by the kernel with no stack trace. |
| `-XX:+ExitOnOutOfMemoryError` | A JVM that hits OOM otherwise limps on half-dead with health checks possibly still passing. Exiting turns an invisible zombie into a restart the platform can act on. |
| In `JAVA_TOOL_OPTIONS`, not the ENTRYPOINT | Overridable by an env var without rebuilding the image. Cost: the JVM prints `Picked up JAVA_TOOL_OPTIONS:` to stderr on every start. |
| `ENTRYPOINT ["java", …]` exec form | The array makes the **JVM PID 1**. Shell form wraps it in `/bin/sh`, which does not forward `SIGTERM`, so the JVM never runs its shutdown hook and gets `SIGKILL`ed — dropping in-flight requests and the connection pool's clean close. |
| No `-jar` | The jar is extracted, so the main class is named directly and `.` is the classpath; `JarLauncher` assembles the rest. |

---

## 4. What was actually verified, so nothing here is guesswork

The runtime stage was built and run against PostgreSQL 17.11, which is what the
project was pinned to at the time. It is on 18 now (ADR-004 Rev 9 — Railway
provisions 18). The container-level checks below have **not** been repeated
against 18; what is known on 18 is that the image builds and the whole suite
passes against 18.6 in CI. None of these checks touches a Postgres version
boundary, so re-running them is a formality rather than a risk — but it is
unrun, and this list says what was measured, not what should hold.

- Boots as `uid=999(spring)` — not root.
- `/proc/1/cmdline` is `java org.springframework.boot.loader.launch.JarLauncher` — the JVM really is PID 1.
- Flyway applied `V1__` and on the second start logged `Current version of schema "public": 1` — idempotent.
- `PORT=9090` → `Tomcat started on port 9090`, so the Railway contract works.
- `GET /login` → 200; `GET /` → **401**, because `spring-boot-starter-security` is on the classpath with no `SecurityFilterChain` yet. Expected, and the reason the security config comes before the first controller.
- `docker stop` → `Graceful shutdown complete`, `HikariPool-1 - Shutdown completed.`, exit code **143** (128 + 15 = handled `SIGTERM`).

**Not verified:** the *build* stage, because the sandbox it was written in routes
traffic through a proxy a Docker build network cannot reach, so
`dependency:go-offline` failed on `wget` to Maven Central. Every command in that
stage was run outside the container instead. On a normal machine and on Railway
it has network access.

---

## 5. Boot 4 traps — treat Boot 3 advice as wrong here

- **`-Djarmode=layertools` is gone.** Deprecated in Boot 3.3, removed in 4. It is `-Djarmode=tools … extract --layers`. Nearly every blog post still says `layertools`.
- The launcher class is `org.springframework.boot.loader.launch.JarLauncher` (note `.launch.`), not the older `org.springframework.boot.loader.JarLauncher`.
- `--layers` is a *list of layer names*, not a boolean flag.
- `extract` with no `--destination` writes to a directory named after the jar, so the path depends on the version number.

---

## 6. Where to push me, and the honest weak spots

Good questions to put to me:

1. Explain Docker layer caching using three specific lines of this file.
2. You skipped your tests in the build. Defend that.
3. What does `EXPOSE` actually do?
4. Why does exec form matter? What breaks with shell form, concretely?
5. Why `MaxRAMPercentage` at all — isn't the modern JVM container-aware?
6. Why two stages? Give me two distinct reasons, not one.
7. Why is `chown` needed when `COPY --chown` is already there?

Weak spots I know about and should be able to answer rather than bluff:

- **`dependency:go-offline` is incomplete.** It misses some plugin dependencies, so `package` still downloads a little. The better option is a BuildKit cache mount (`RUN --mount=type=cache,target=/root/.m2 …`), rejected for now because cache mounts are not guaranteed to persist on a hosted builder and it is one more concept to defend.
- **Four `COPY` lines is a real tradeoff.** A single `COPY target/*.jar app.jar` plus `java -jar` is three lines and trivially explainable; it just pushes 57MB per deploy instead of ~100KB. I chose the layered version because the saving is real and the cost is four lines.
- **No healthcheck yet.** Spring Boot Actuator with only `health` exposed, pointed at by Railway, would make a failed deploy roll back instead of serving errors. Not added yet.
- **No non-root base-image hardening beyond `USER`** — no distroless, no read-only root filesystem. Both defensible next steps; neither done.
- **Nothing pins the base image by digest.** `eclipse-temurin:21-jre` is a moving tag, so two builds a month apart can differ. A digest pin is the fix; the cost is manual updates.

---

## 7. House rules for that conversation

- Brutally honest, no flattery. Challenge over-engineering **and** under-engineering.
- Name what was rejected and why, not just what was chosen.
- If a claim cannot be verified, say so rather than asserting it.
- Code, comments and docs in English; the site's content is Bulgarian.
