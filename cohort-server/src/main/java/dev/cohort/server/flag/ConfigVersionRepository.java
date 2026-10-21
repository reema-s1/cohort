package dev.cohort.server.flag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The single-row global config version. Bumping it takes a row lock, which serialises flag writes;
 * that is fine for an admin API that sees a handful of writes per minute.
 */
@Repository
public class ConfigVersionRepository {

    private final JdbcTemplate jdbc;

    public ConfigVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long current() {
        return jdbc.queryForObject("SELECT version FROM config_version WHERE id = 1", Long.class);
    }

    public long bump() {
        return jdbc.queryForObject("UPDATE config_version SET version = version + 1 WHERE id = 1 RETURNING version", Long.class);
    }
}
