-- V1__create_user_and_action.sql
--
-- First migration: app_user, action, action_category, action_region.
-- Flyway wraps this whole file in one transaction (Postgres DDL is
-- transactional), so either every statement applies or none does.
--
-- Conventions (ARCHITECTURE.md §2):
--   * BIGINT GENERATED ALWAYS AS IDENTITY primary keys
--   * timestamptz everywhere, never timestamp
--   * singular snake_case table names; app_user because "user" is reserved
--   * every constraint explicitly named, because the application tells
--     violations apart by name (ADR-011)
--   * enums are varchar + CHECK, never a native Postgres ENUM type


-- ---------------------------------------------------------------------------
-- 1. Extensions
-- ---------------------------------------------------------------------------

-- Trigram similarity for the title index (ADR-007). Must exist before the
-- index below. unaccent is deliberately absent: it is STABLE, so it cannot
-- appear in a generated column, and Bulgarian has no diacritics anyway.
CREATE EXTENSION IF NOT EXISTS pg_trgm;


-- ---------------------------------------------------------------------------
-- 2. app_user
-- ---------------------------------------------------------------------------

CREATE TABLE app_user (
    id                  bigint        GENERATED ALWAYS AS IDENTITY,

    email               varchar(254)  NOT NULL,
    -- 254 is the practical RFC 5321 maximum for an address.

    password_hash       varchar(100)  NOT NULL,
    -- BCrypt is 60 chars; 100 leaves room for the {bcrypt} prefix that
    -- DelegatingPasswordEncoder writes and for a later move to Argon2.

    display_name        varchar(80)   NOT NULL,
    bio                 text,
    website_url         varchar(2048),

    role                varchar(20)   NOT NULL DEFAULT 'USER',
    enabled             boolean       NOT NULL DEFAULT true,
    -- enabled = the ban switch. Spring Security reads it via UserDetails.

    email_verified_at   timestamptz,
    -- NULL = cannot log in, receives no mail. Also answers "has the welcome
    -- email been sent?", so no separate column for that (ADR-005).

    poster_verified_at  timestamptz,
    -- Records *when* the POSTER role was granted; the role itself is the
    -- single source of truth for the privilege (ADR-010).

    last_digest_sent_at timestamptz   NOT NULL DEFAULT now(),
    -- Digest watermark (ADR-005). Defaulting to now() at registration means a
    -- new user's first digest contains only actions published after they
    -- joined, never the whole back catalogue.

    locale              varchar(2)    NOT NULL DEFAULT 'bg',
    -- The nightly job has no cookie, so the email language must be stored.

    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    -- Defaults are a safety net for SQL-level writes; in normal operation
    -- Hibernate's @CreationTimestamp / @UpdateTimestamp set them.

    CONSTRAINT pk_app_user PRIMARY KEY (id),

    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_lowercase CHECK (email = lower(email)),
    -- Lowercase in the app + plain UNIQUE = case-insensitive uniqueness
    -- without citext and without a functional index every query must match.

    CONSTRAINT ck_app_user_display_name_length
        CHECK (char_length(display_name) BETWEEN 1 AND 80),
    CONSTRAINT ck_app_user_bio_length
        CHECK (char_length(bio) <= 1000),
    -- NULL bio -> the expression is NULL -> the CHECK passes. A CHECK only
    -- fails on FALSE, which is why nullable columns need no OR IS NULL.

    CONSTRAINT ck_app_user_role
        CHECK (role IN ('USER', 'POSTER', 'ADMIN')),
    CONSTRAINT ck_app_user_locale
        CHECK (locale IN ('bg', 'en')),

    CONSTRAINT ck_app_user_poster_verified_role
        CHECK (poster_verified_at IS NULL OR role IN ('POSTER', 'ADMIN'))
    -- Keeps the timestamp consistent with the role it describes (ADR-010).
);


-- ---------------------------------------------------------------------------
-- 3. action
-- ---------------------------------------------------------------------------

CREATE TABLE action (
    id              bigint        GENERATED ALWAYS AS IDENTITY,

    author_id       bigint        NOT NULL,

    title           varchar(200)  NOT NULL,
    description     text          NOT NULL,

    action_type     varchar(20)   NOT NULL,

    external_url    varchar(2048),
    -- What the user clicks. Stored raw, for display and for the outbound link.
    -- Nullable: an action with no outbound link is valid. The detail page
    -- therefore needs a no-CTA variant (frontend brief currently assumes one).

    normalized_url  varchar(500),
    -- Lowercased host, no www., no utm_*/fbclid, no trailing slash, no
    -- fragment (ADR-011). Capped at 500 because a B-tree index entry is
    -- limited to ~2704 bytes; the fallback would be a unique index on a hash.

    video_url       varchar(2048),
    -- Host whitelist (YouTube/Vimeo) is enforced in the application: a CHECK
    -- here would be a policy rule frozen into the schema.

    status          varchar(20)   NOT NULL DEFAULT 'PENDING',
    -- Defaults to PENDING so a forgotten assignment fails closed.

    published_at    timestamptz,
    -- Written exactly once, at the first transition to PUBLISHED, and never
    -- touched again -- including when an action returns to PUBLISHED after a
    -- rejection. The digest depends on that immutability (ADR-005).

    ends_at         timestamptz   NOT NULL,
    -- Expiry is derived (ends_at < now() in the query), never a stored status.

    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),

    search_vector   tsvector      GENERATED ALWAYS AS (
                        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('simple', coalesce(description, '')), 'B')
                    ) STORED,
    -- Recomputed by Postgres on every write to title/description, so it cannot
    -- drift from the row — unlike a trigger or application code (ADR-007).
    -- 'simple' because Postgres ships no Bulgarian dictionary; the two-argument
    -- form is required because generated columns demand an IMMUTABLE
    -- expression and the one-argument form is only STABLE.
    -- setweight gives title lexemes weight A and description B so ts_rank
    -- scores a title hit above a body hit.

    CONSTRAINT pk_action PRIMARY KEY (id),

    CONSTRAINT fk_action_author FOREIGN KEY (author_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    -- Cascade lives in the database, not in JPA (ADR-015). Two systems owning
    -- one rule can disagree, and JPA would load every child into memory.

    CONSTRAINT uq_action_normalized_url UNIQUE (normalized_url),
    -- Postgres treats NULLs as distinct in a UNIQUE constraint, so any number
    -- of link-less actions coexist while a real URL can exist only once on the
    -- platform, in any status. Freeing a URL means hard-deleting the row.

    CONSTRAINT ck_action_url_pair
        CHECK ((external_url IS NULL) = (normalized_url IS NULL)),
    -- Both or neither. A normalized URL with no original is unusable, and an
    -- original with no normalized form escapes duplicate detection.

    CONSTRAINT ck_action_title_length
        CHECK (char_length(title) BETWEEN 5 AND 200),
    CONSTRAINT ck_action_description_length
        CHECK (char_length(description) BETWEEN 1 AND 5000),

    CONSTRAINT ck_action_type
        CHECK (action_type IN ('PETITION', 'LETTER_OR_EMAIL', 'SURVEY',
                               'SOCIAL_MEDIA', 'OTHER')),

    CONSTRAINT ck_action_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'REJECTED', 'WITHDRAWN')),
    -- HIDDEN is gone: once an admin can move PUBLISHED -> REJECTED, REJECTED
    -- *is* the takedown state and HIDDEN would be a second name for it.

    CONSTRAINT ck_action_published_at_required
        CHECK (status IN ('PENDING', 'REJECTED') OR published_at IS NOT NULL),
    -- Deliberately one-directional. The state graph is now cyclic, so a
    -- PENDING or REJECTED row may legitimately carry a published_at from an
    -- earlier publication. The invariant that still holds: anything currently
    -- PUBLISHED or WITHDRAWN has been published at least once.

    CONSTRAINT ck_action_ends_at_after_created
        CHECK (ends_at > created_at)
    -- The one-year maximum is service-layer policy, not a CHECK: interval
    -- arithmetic depends on the session time zone and the rule may change.
);


-- ---------------------------------------------------------------------------
-- 4. action_category   (one to three per action, ADR-017)
-- ---------------------------------------------------------------------------

CREATE TABLE action_category (
    action_id bigint      NOT NULL,
    category  varchar(30) NOT NULL,

    CONSTRAINT pk_action_category PRIMARY KEY (action_id, category),
    -- The composite PK gives uniqueness for free: no duplicate tagging.

    CONSTRAINT fk_action_category_action FOREIGN KEY (action_id)
        REFERENCES action (id) ON DELETE CASCADE,

    CONSTRAINT ck_action_category_value
        CHECK (category IN ('NATURE', 'ANIMALS', 'CHILDREN', 'HEALTHCARE',
                            'DISABILITY', 'LOCAL_COMMUNITIES', 'SOCIAL',
                            'HUMAN_RIGHTS', 'LABOUR_RIGHTS', 'POLITICAL'))
    -- The maximum of three is enforced in the service and by @Size: a
    -- row-level CHECK cannot count sibling rows.
);


-- ---------------------------------------------------------------------------
-- 5. action_region   (one to three per action, ADR-009)
-- ---------------------------------------------------------------------------

CREATE TABLE action_region (
    action_id bigint      NOT NULL,
    region    varchar(30) NOT NULL,

    CONSTRAINT pk_action_region PRIMARY KEY (action_id, region),

    CONSTRAINT fk_action_region_action FOREIGN KEY (action_id)
        REFERENCES action (id) ON DELETE CASCADE,

    CONSTRAINT ck_action_region_value
        CHECK (region IN (
            -- Bulgaria's 28 oblasti. SOFIA_CITY and SOFIA_PROVINCE are two
            -- distinct administrative units; conflating them is a real error.
            'BLAGOEVGRAD', 'BURGAS', 'VARNA', 'VELIKO_TARNOVO', 'VIDIN',
            'VRATSA', 'GABROVO', 'DOBRICH', 'KARDZHALI', 'KYUSTENDIL',
            'LOVECH', 'MONTANA', 'PAZARDZHIK', 'PERNIK', 'PLEVEN',
            'PLOVDIV', 'RAZGRAD', 'RUSE', 'SILISTRA', 'SLIVEN',
            'SMOLYAN', 'SOFIA_CITY', 'SOFIA_PROVINCE', 'STARA_ZAGORA',
            'TARGOVISHTE', 'HASKOVO', 'SHUMEN', 'YAMBOL',
            -- Plus two ordinary values that may be combined with oblasti.
            'NATIONAL', 'INTERNATIONAL'))
);


-- ---------------------------------------------------------------------------
-- 6. Indexes
-- ---------------------------------------------------------------------------

-- Full-text search over title + description.
CREATE INDEX idx_action_search_vector ON action USING gin (search_vector);

-- Trigram similarity on the title: catches Bulgarian inflections
-- ("петиция" / "петиции" / "петицията") and typos, which the unstemmed
-- 'simple' configuration cannot. Also makes ILIKE '%term%' indexable.
CREATE INDEX idx_action_title_trgm ON action USING gin (title gin_trgm_ops);

-- Postgres indexes primary keys and unique constraints, NOT the referencing
-- side of a foreign key. Without this, "all actions by this author" and every
-- cascade delete is a sequential scan.
CREATE INDEX idx_action_author_id ON action (author_id);

-- The public listing. Partial, because only PUBLISHED rows are ever listed;
-- column order matches the query's ORDER BY exactly, including the id
-- tiebreaker, so the sort comes free from the index scan.
CREATE INDEX idx_action_published ON action (published_at DESC, id DESC)
    WHERE status = 'PUBLISHED';

-- The composite PKs are keyed on action_id first, so they cannot answer
-- "which actions are in this category / region". These can.
CREATE INDEX idx_action_category_category ON action_category (category);
CREATE INDEX idx_action_region_region ON action_region (region);
