package dev.cohort.common;

import java.time.Instant;
import java.util.UUID;

/**
 * An exposure or conversion sent from the SDK to the server.
 *
 * @param id        unique per event; the aggregator drops repeats, so SDK retries and Kafka's
 *                  at-least-once delivery never double count
 * @param variant   for exposures, the variant served. Conversions are credited to the variant of
 *                  the user's first exposure, whatever this field says.
 * @param eventName for conversions, what happened (e.g. {@code applied_to_job})
 */
public record CohortEvent(
        UUID id,
        Type type,
        String flagKey,
        String variant,
        String userId,
        String eventName,
        Instant timestamp) {

    public enum Type {
        EXPOSURE,
        CONVERSION
    }

    public static CohortEvent exposure(String flagKey, String variant, String userId) {
        return new CohortEvent(UUID.randomUUID(), Type.EXPOSURE, flagKey, variant, userId, null, Instant.now());
    }

    public static CohortEvent conversion(String flagKey, String variant, String userId, String eventName) {
        return new CohortEvent(UUID.randomUUID(), Type.CONVERSION, flagKey, variant, userId, eventName, Instant.now());
    }

    /** Kafka partition key: keeps one user's events for one flag in order on one partition. */
    public String partitionKey() {
        return flagKey + ":" + userId;
    }
}
