package dev.cohort.sdk;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.time.Duration;
import java.time.Instant;
import java.util.function.ToDoubleFunction;

/**
 * Exposes SDK health in the host app's Micrometer registry:
 * {@code new CohortMetrics(cohort).bindTo(registry)}. Micrometer is an optional dependency, so
 * this class is only loaded by apps that use it.
 */
public final class CohortMetrics implements MeterBinder {

    private final CohortClient client;

    public CohortMetrics(CohortClient client) {
        this.client = client;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "cohort.sdk.evaluations", "Flag evaluations", s -> s.evaluations());
        counter(registry, "cohort.sdk.poll.failures", "Config polls that failed", s -> s.pollFailures());
        counter(registry, "cohort.sdk.events.sent", "Events delivered to the server", s -> s.eventsSent());
        counter(registry, "cohort.sdk.events.dropped", "Events dropped because the buffer was full", s -> s.eventsDropped());
        counter(registry, "cohort.sdk.events.failed", "Events dropped after send retries failed", s -> s.eventsFailed());
        Gauge.builder("cohort.sdk.config.version", client, c -> c.stats().configVersion())
                .description("Config version held in memory")
                .register(registry);
        Gauge.builder("cohort.sdk.seconds.since.sync", client, CohortMetrics::secondsSinceSync)
                .description("Seconds since the server last answered a poll")
                .register(registry);
    }

    private void counter(MeterRegistry registry, String name, String description, ToDoubleFunction<CohortStats> value) {
        FunctionCounter.builder(name, client, c -> value.applyAsDouble(c.stats()))
                .description(description)
                .register(registry);
    }

    private static double secondsSinceSync(CohortClient client) {
        Instant last = client.stats().lastSuccessfulSync();
        return last == null ? Double.NaN : Duration.between(last, Instant.now()).toMillis() / 1000.0;
    }
}
