package dev.cohort.sdk;

import java.time.Instant;

/**
 * A point-in-time view of the SDK's health counters.
 *
 * @param configSource where the in-memory config came from: NONE, SERVER or DISK
 * @param lastSuccessfulSync null until the server has answered a poll once
 */
public record CohortStats(
        long configVersion,
        String configSource,
        long evaluations,
        long pollFailures,
        long eventsQueued,
        long eventsSent,
        long eventsDropped,
        long eventsFailed,
        Instant lastSuccessfulSync) {
}
