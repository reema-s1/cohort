package dev.cohort.server.events;

import dev.cohort.common.CohortEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consumes {@code cohort.events} and maintains per-variant exposure and conversion counts.
 *
 * <p>Delivery is at least once, so every step is idempotent: the event id is inserted into
 * {@code seen_events} first and a repeat is skipped; a user's exposure and conversion are each
 * recorded once per flag. The batch commits in one database transaction, and Kafka offsets are
 * committed only after the listener returns, so a crash replays the batch rather than losing it.
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
    public void onBatch(List<CohortEvent> events) {
        // Count increments in memory and apply them once per (flag, variant) at the end, in key
        // order: fewer writes on the hot stats rows, and a fixed lock order so parallel consumers
        // can never deadlock on them.
        Map<String, long[]> deltas = new TreeMap<>();
        for (CohortEvent event : events) {
            if (event == null) {
                continue; // undeserialisable record; ErrorHandlingDeserializer already logged it
            }
            if (jdbc.update("INSERT INTO seen_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", event.id()) == 0) {
                duplicates.increment();
                continue;
            }
            switch (event.type()) {
                case EXPOSURE -> recordExposure(event, deltas);
                case CONVERSION -> recordConversion(event, deltas);
            }
            processed.increment();
        }
        deltas.forEach((key, delta) -> {
            String[] parts = key.split("\u0000", 2);
            jdbc.update("""
                    INSERT INTO experiment_stats (flag_key, variant, exposures, conversions) VALUES (?, ?, ?, ?)
                    ON CONFLICT (flag_key, variant) DO UPDATE
                    SET exposures = experiment_stats.exposures + EXCLUDED.exposures,
                        conversions = experiment_stats.conversions + EXCLUDED.conversions
                    """, parts[0], parts[1], delta[0], delta[1]);
        });
    }

    private void recordExposure(CohortEvent event, Map<String, long[]> deltas) {
        int inserted = jdbc.update("""
                INSERT INTO experiment_exposures (flag_key, user_id, variant, first_seen) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, event.flagKey(), event.userId(), event.variant(), Timestamp.from(event.timestamp()));
        if (inserted == 1) {
            deltas.computeIfAbsent(event.flagKey() + '\u0000' + event.variant(), k -> new long[2])[0]++;
        }
    }

    private void recordConversion(CohortEvent event, Map<String, long[]> deltas) {
        // Credit the variant of the user's first exposure, once per user.
        List<String> credited = jdbc.queryForList("""
                INSERT INTO experiment_conversions (flag_key, user_id, variant, converted_at)
                SELECT flag_key, user_id, variant, ? FROM experiment_exposures WHERE flag_key = ? AND user_id = ?
                ON CONFLICT DO NOTHING
                RETURNING variant
                """, String.class, Timestamp.from(event.timestamp()), event.flagKey(), event.userId());
        if (!credited.isEmpty()) {
            deltas.computeIfAbsent(event.flagKey() + '\u0000' + credited.getFirst(), k -> new long[2])[1]++;
        } else if (jdbc.queryForObject("SELECT count(*) FROM experiment_exposures WHERE flag_key = ? AND user_id = ?",
                Integer.class, event.flagKey(), event.userId()) == 0) {
            orphanConversions.increment();
        }
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
