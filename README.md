# 5 Good Minutes

A Bulgarian-language directory of small activism actions — petitions, emails to
officials, surveys — that users browse by cause and region, subscribe to, and
receive as a daily email digest.

Five minutes is the budget. Every action links out to the platform that hosts it;
this site hosts none of them.

Work in progress.

---

## Stack

- Java 21
- Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation)
- Thymeleaf + htmx for the pages
- PostgreSQL 18 — pinned to match the deployed major version
- Flyway for migrations
- Maven
- JUnit 6 (what Spring Boot 4.1 ships), Mockito, AssertJ, Testcontainers
- Docker / Docker Compose

---

## Current state

Done:

- Spring Boot skeleton, package-by-feature layout
- Docker Compose for the backing services: PostgreSQL and Mailpit
- Flyway `V1__` migration — the user and action schema
- `AppUser` and `Action` entities with repository tests on a real PostgreSQL

Next, in order:

- Dockerfile and an `app` service in Compose, so one command runs everything
- GitHub Actions pipeline
- Deploy to Railway behind the real domain
- Registration, login, email verification
- Submitting and listing actions
- Subscriptions and the daily digest email

---

## Running it locally

Needs Docker and Java 21. The application is not containerised yet — Compose
runs the backing services only, which is the next thing on the list above.

```bash
docker compose up -d          # PostgreSQL + Mailpit
./mvnw spring-boot:run        # the application
```

- Application — http://localhost:8080
- Mailpit (catches outgoing mail) — http://localhost:8025
- PostgreSQL — localhost:5432, database `fivegoodmins`, user/password `app`

Flyway applies the migrations on startup.

Tests:

```bash
./mvnw test
```

Every test needs Docker: the repository tests run against a real PostgreSQL via
Testcontainers, because the full-text search this project depends on does not
exist in an in-memory database. One container is started for the whole suite.

---

## Scheduled jobs

`@Scheduled` runs in the application process (`shared/SchedulingConfig`). Today
there is one job: the nightly purge of accounts that never confirmed their
address, at 03:30 UTC. The daily digest joins it.

**Known constraint: this assumes exactly one instance is running.** The
scheduler knows nothing about its peers, so a second replica runs every job a
second time — harmless for the purge, but two digest emails per subscriber. It
is the single thing in this design that blocks horizontal scaling, and it is
accepted knowingly for the MVP. The fix when it matters is ShedLock or a
PostgreSQL advisory lock around each job, or moving the scheduled work to a
separate single-replica deployment.

---

## Configuration

`application.yaml` holds local-development defaults. Every value can be
overridden by an environment variable through Spring's relaxed binding, so the
application moves hosts without code changes.

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Database connection |
| `APP_BASE_URL` | Base URL used in email links |
| `SPRING_PROFILES_ACTIVE` | `dev` or `prod` |

Mail goes to Mailpit in development and to a transactional provider's HTTP API
in production, behind one `EmailSender` interface.

---

## The schema so far

Flyway owns the schema; Hibernate only validates it at startup
(`ddl-auto=validate`).

`V1__` creates:

- **`app_user`** — email, password hash, display name, role, profile fields,
  email verification timestamp, locale. Named `app_user` because `user` is a
  reserved word in PostgreSQL.
- **`action`** — the activism item: title, description, type, external link,
  status, publish date, end date, and a generated `tsvector` column for search.
- **`action_category`** and **`action_region`** — an action can carry up to three
  of each, so they are join tables rather than numbered columns.

Primary keys are `BIGINT GENERATED ALWAYS AS IDENTITY`. Timestamps are
`timestamptz`. Enum values are stored as strings with a `CHECK` constraint rather
than a PostgreSQL enum type or a lookup table.

---

## Domain vocabulary

**Action** — one activism item, with one type, one to three categories, one to
three regions, and a mandatory end date.

**Types (5):** `PETITION`, `LETTER_OR_EMAIL`, `SURVEY`, `SOCIAL_MEDIA`, `OTHER`

**Categories (10):** `NATURE`, `ANIMALS`, `CHILDREN`, `HEALTHCARE`, `DISABILITY`,
`LOCAL_COMMUNITIES`, `SOCIAL`, `HUMAN_RIGHTS`, `LABOUR_RIGHTS`, `POLITICAL`

**Regions (30):** Bulgaria's 28 oblasti plus `NATIONAL` and `INTERNATIONAL`.

**Statuses (4):** `PENDING`, `PUBLISHED`, `REJECTED`, `WITHDRAWN`. Only
`PUBLISHED` actions are publicly visible. An admin moves an action between the
first three in any direction; `WITHDRAWN` is the poster's own reversible choice.

**Roles (3):** `USER`, `POSTER`, `ADMIN`.

Enum constants are English in code and in the database. Everything a user sees is
translated through `messages_bg.properties` and `messages_en.properties`.

---

## Conventions

- Code, comments, commit messages and documentation in English. User-facing
  content is in Bulgarian.
- No hardcoded user-facing strings — every label comes from the message bundles.
  Bulgarian is the default, English is selectable.
- Flyway owns the schema. No automatic DDL generation.
- Entities stay in the service layer; controllers and templates get DTOs.

---

## Roadmap

**Now:** browsing with filters and search, action detail pages, accounts and
email verification, submitting actions with admin approval, subscriptions, the
daily digest, reporting an action, a small admin area, Bulgarian/English
switching, responsive layout.

**Later:** password reset, completion tracking, ranking, an articles section, push
notifications, a mobile app.

---

## License

Source code is licensed under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).
See [`LICENSE`](LICENSE).

In short: free to use, change and share for any noncommercial purpose, including
work by NGOs and other nonprofits. **Commercial use requires written permission.**
If you want to use it commercially, open an issue.

The project name and the content published on 5goodmins.com are not covered by
this license.
