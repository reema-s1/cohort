CREATE TABLE flags (
    key             VARCHAR(100) PRIMARY KEY,
    description     TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    salt            VARCHAR(64)  NOT NULL,
    default_variant VARCHAR(100) NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    archived        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- position fixes the order bucketing walks the variants in; it must not change mid-experiment.
CREATE TABLE variants (
    flag_key VARCHAR(100) NOT NULL REFERENCES flags (key) ON DELETE CASCADE,
    position INT          NOT NULL,
    name     VARCHAR(100) NOT NULL,
    weight   INT          NOT NULL CHECK (weight BETWEEN 0 AND 10000),
    PRIMARY KEY (flag_key, position)
);

CREATE TABLE rules (
    flag_key  VARCHAR(100) NOT NULL REFERENCES flags (key) ON DELETE CASCADE,
    position  INT          NOT NULL,
    priority  INT          NOT NULL,
    type      VARCHAR(20)  NOT NULL CHECK (type IN ('ALLOWLIST', 'ATTRIBUTE', 'PERCENT')),
    condition JSONB        NOT NULL,
    variant   VARCHAR(100),
    PRIMARY KEY (flag_key, position)
);

CREATE TABLE audit_log (
    id       BIGSERIAL PRIMARY KEY,
    flag_key VARCHAR(100) NOT NULL,
    actor    VARCHAR(200) NOT NULL,
    action   VARCHAR(20)  NOT NULL,
    before   JSONB,
    after    JSONB,
    at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_flag_key_at ON audit_log (flag_key, at DESC);

-- A single global counter bumped on every flag change. SDKs send it back as an ETag and get a
-- 304 when nothing changed.
CREATE TABLE config_version (
    id      SMALLINT PRIMARY KEY CHECK (id = 1),
    version BIGINT   NOT NULL
);

INSERT INTO config_version (id, version) VALUES (1, 0);
