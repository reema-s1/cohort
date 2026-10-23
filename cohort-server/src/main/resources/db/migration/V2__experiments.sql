-- Every event id the aggregator has processed. The primary key is the dedup: SDK retries and
-- Kafka redeliveries insert nothing and are skipped. Old rows are pruned after the retention window.
CREATE TABLE seen_events (
    event_id UUID        PRIMARY KEY,
    seen_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX seen_events_seen_at ON seen_events (seen_at);

-- A user counts once per flag, in the variant of their first exposure.
CREATE TABLE experiment_exposures (
    flag_key   VARCHAR(100) NOT NULL,
    user_id    VARCHAR(200) NOT NULL,
    variant    VARCHAR(100) NOT NULL,
    first_seen TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (flag_key, user_id)
);

-- A user converts at most once per flag, credited to their exposure variant.
CREATE TABLE experiment_conversions (
    flag_key     VARCHAR(100) NOT NULL,
    user_id      VARCHAR(200) NOT NULL,
    variant      VARCHAR(100) NOT NULL,
    converted_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (flag_key, user_id)
);

-- Running totals the results endpoint reads, so it never scans the per-user tables.
CREATE TABLE experiment_stats (
    flag_key    VARCHAR(100) NOT NULL,
    variant     VARCHAR(100) NOT NULL,
    exposures   BIGINT       NOT NULL DEFAULT 0,
    conversions BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (flag_key, variant)
);
