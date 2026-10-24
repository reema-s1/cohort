package dev.cohort.server.events;

import dev.cohort.common.CohortEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Consumes {@code cohort.events} and maintains per-variant exposure and conversion counts.
 *
 * <p>Delivery is at least once, so every step is idempotent: event ids go into
 * {@code seen_events} first and repeats are skipped; a user's exposure and conversion are each
 * recorded once per flag. The batch commits in one database transaction, and Kafka offsets are
 * committed only after the listener returns, so a crash replays the batch rather than losing it.
 *
 * <p>Each step is one set-based statement over arrays ({@code unnest}), so a batch of 1000 events
 * costs five round trips, not three per event.
 */
@Component
public class EventAggregator {

    private static final Logger log = LoggerFactory.getLogger(EventAggregator.class);

    private final JdbcTemplate jdbc;
    private final Counter processed;
    private final Counter duplicates;
    private final Counter orphanConversions;

    public EventAggregator(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.processed = Counter.builder("cohort.events.processed").description("Events aggregated").register(registry);
        this.duplicates = Counter.builder("cohort.events.duplicates").description("Repeat deliveries dropped by dedup").register(registry);
        this.orphanConversions = Counter.builder("cohort.events.orphan.conversions")
                .description("Conversions from users never exposed to the flag").register(registry);
    }

    @KafkaListener(topics = "${cohort.events.topic}")
    @Transactional
    public void onBatch(List<CohortEvent> batch) {
        List<CohortEvent> events = batch.stream().filter(e -> e != null).toList(); // nulls: undeserialisable, already logged
        if (events.isEmpty()) {
            return;
        }
        Set<UUID> fresh = claimNewIds(events);
        duplicates.increment(events.size() - fresh.size());

        List<CohortEvent> exposures = new ArrayList<>();
        List<CohortEvent> conversions = new ArrayList<>();
        Set<UUID> claimed = new HashSet<>();
        for (CohortEvent event : events) {
            // claimed also catches the same id twice within one batch.
            if (fresh.contains(event.id()) && claimed.add(event.id())) {
                (event.type() == CohortEvent.Type.EXPOSURE ? exposures : conversions).add(event);
            }
        }

        // Count increments per (flag, variant) and apply them once, in key order: fewer writes on
        // the hot stats rows, and a fixed lock order so parallel consumers cannot deadlock.
        Map<String, long[]> deltas = new TreeMap<>();
        // Exposures before conversions, so a user exposed and converting in the same batch counts.
        recordExposures(exposures, deltas);
        recordConversions(conversions, deltas);
        deltas.forEach((key, delta) -> {
            String[] parts = key.split("\u0000", 2);
            jdbc.update("""
                    INSERT INTO experiment_stats (flag_key, variant, exposures, conversions) VALUES (?, ?, ?, ?)
                    ON CONFLICT (flag_key, variant) DO UPDATE
                    SET exposures = experiment_stats.exposures + EXCLUDED.exposures,
                        conversions = experiment_stats.conversions + EXCLUDED.conversions
                    """, parts[0], parts[1], delta[0], delta[1]);
        });
        processed.increment(exposures.size() + conversions.size());
    }

    /** Inserts every event id; returns those not seen before. */
    private Set<UUID> claimNewIds(List<CohortEvent> events) {
        Set<UUID> fresh = new HashSet<>();
        UUID[] ids = events.stream().map(CohortEvent::id).toArray(UUID[]::new);
        jdbc.query(con -> statement(con, """
                        INSERT INTO seen_events (event_id) SELECT unnest(?)
                        ON CONFLICT DO NOTHING RETURNING event_id
                        """, array(con, "uuid", ids)),
                (RowCallbackHandler) rs -> fresh.add(rs.getObject(1, UUID.class)));
        return fresh;
    }

    /** First exposure per (flag, user) wins; later ones, in any variant, are ignored. */
    private void recordExposures(List<CohortEvent> exposures, Map<String, long[]> deltas) {
        if (exposures.isEmpty()) {
            return;
        }
        jdbc.query(con -> statement(con, """
                        INSERT INTO experiment_exposures (flag_key, user_id, variant, first_seen)
                        SELECT * FROM unnest(?, ?, ?, ?)
                        ON CONFLICT DO NOTHING RETURNING flag_key, variant
                        """,
                        array(con, "text", exposures.stream().map(CohortEvent::flagKey).toArray()),
                        array(con, "text", exposures.stream().map(CohortEvent::userId).toArray()),
                        array(con, "text", exposures.stream().map(CohortEvent::variant).toArray()),
                        array(con, "timestamptz", exposures.stream().map(e -> Timestamp.from(e.timestamp())).toArray())),
                (RowCallbackHandler) rs -> delta(deltas, rs.getString(1), rs.getString(2))[0]++);
    }

    /** One conversion per exposed user, credited to their exposure variant. */
    private void recordConversions(List<CohortEvent> conversions, Map<String, long[]> deltas) {
        if (conversions.isEmpty()) {
            return;
        }
        Object[] flagKeys = conversions.stream().map(CohortEvent::flagKey).toArray();
        Object[] userIds = conversions.stream().map(CohortEvent::userId).toArray();
        Object[] times = conversions.stream().map(e -> Timestamp.from(e.timestamp())).toArray();
        jdbc.query(con -> statement(con, """
                        INSERT INTO experiment_conversions (flag_key, user_id, variant, converted_at)
                        SELECT e.flag_key, e.user_id, e.variant, c.converted_at
                        FROM unnest(?, ?, ?) AS c (flag_key, user_id, converted_at)
                        JOIN experiment_exposures e ON e.flag_key = c.flag_key AND e.user_id = c.user_id
                        ON CONFLICT DO NOTHING RETURNING flag_key, variant
                        """,
                        array(con, "text", flagKeys), array(con, "text", userIds), array(con, "timestamptz", times)),
                (RowCallbackHandler) rs -> delta(deltas, rs.getString(1), rs.getString(2))[1]++);
        Integer orphans = jdbc.query(con -> statement(con, """
                        SELECT count(*) FROM unnest(?, ?) AS c (flag_key, user_id)
                        WHERE NOT EXISTS (SELECT 1 FROM experiment_exposures e
                                          WHERE e.flag_key = c.flag_key AND e.user_id = c.user_id)
                        """, array(con, "text", flagKeys), array(con, "text", userIds)),
                rs -> rs.next() ? rs.getInt(1) : 0);
        orphanConversions.increment(orphans == null ? 0 : orphans);
    }

    private static long[] delta(Map<String, long[]> deltas, String flagKey, String variant) {
        return deltas.computeIfAbsent(flagKey + '\u0000' + variant, k -> new long[2]);
    }

    private static Array array(Connection con, String type, Object[] values) throws SQLException {
        return con.createArrayOf(type, values);
    }

    private static PreparedStatement statement(Connection con, String sql, Array... arrays) throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        for (int i = 0; i < arrays.length; i++) {
            ps.setArray(i + 1, arrays[i]);
        }
        return ps;
    }

    /** seen_events only needs to outlive any plausible redelivery, so a week is plenty. */
    @Scheduled(fixedDelayString = "PT1H")
    public void pruneSeenEvents() {
        int deleted = jdbc.update("DELETE FROM seen_events WHERE seen_at < now() - interval '7 days'");
        if (deleted > 0) {
            log.info("Pruned {} dedup records older than 7 days", deleted);
        }
    }
}
