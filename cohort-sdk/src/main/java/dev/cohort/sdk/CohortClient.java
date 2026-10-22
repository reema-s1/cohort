package dev.cohort.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.cohort.common.CohortEvent;
import dev.cohort.common.CohortUser;
import dev.cohort.common.Evaluation;
import dev.cohort.common.Evaluation.Reason;
import dev.cohort.common.Evaluator;
import dev.cohort.common.EventBatch;
import dev.cohort.common.FlagConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Evaluates flags locally against an in-memory config snapshot, so a flag check is a hash and a
 * loop, never a network call.
 *
 * <pre>{@code
 * CohortClient cohort = CohortClient.builder()
 *     .serverUrl("http://localhost:8080")
 *     .apiKey(System.getenv("COHORT_KEY"))
 *     .build();
 * String variant = cohort.getVariant("new-search-ranking", user);
 * cohort.track("new-search-ranking", user, "applied_to_job");
 * }</pre>
 *
 * <p>Fail-safe order when evaluating a flag: the in-memory config; else the disk snapshot, if
 * the SDK just started while the server was unreachable; else the caller's default. A flag check
 * never throws.
 */
public final class CohortClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CohortClient.class);

    /** Local exposure dedup is only a volume saver (the server dedups too), so it is just cleared when full. */
    private static final int MAX_REMEMBERED_EXPOSURES = 100_000;

    private final CohortHttp http;
    private final ConfigStore store;
    private final EventBuffer events;
    private final ScheduledExecutorService poller;
    private final Set<String> exposed = ConcurrentHashMap.newKeySet();

    private final LongAdder evaluations = new LongAdder();
    private final LongAdder pollFailures = new LongAdder();
    private volatile Instant lastSuccessfulSync;

    private CohortClient(Builder builder) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.http = new CohortHttp(builder.serverUrl, builder.apiKey, builder.httpTimeout, mapper);
        this.store = new ConfigStore(builder.snapshotFile, mapper);
        this.events = new EventBuffer(builder.bufferCapacity, EventBatch.MAX_EVENTS, builder.flushInterval, http::sendEvents);
        this.poller = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("cohort-poller").factory());

        // Try the server once up front; only fall back to disk if that fails.
        if (!poll()) {
            store.loadFromDisk();
        }
        long millis = builder.pollInterval.toMillis();
        poller.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** One poll: a 304 is the common case and costs an empty response. Returns true if the server answered. */
    private boolean poll() {
        try {
            http.fetchConfig(store.version()).ifPresent(store::acceptFromServer);
            lastSuccessfulSync = Instant.now();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            pollFailures.increment();
            log.debug("Cohort config poll failed", e);
            return false;
        }
    }

    /** Full evaluation detail. Never throws; failures come back with reason ERROR and a null variant. */
    public Evaluation evaluate(String flagKey, CohortUser user) {
        evaluations.increment();
        try {
            FlagConfig config = store.get();
            if (config == null) {
                return new Evaluation(flagKey, null, Reason.FLAG_NOT_FOUND, false);
            }
            Evaluation evaluation = Evaluator.evaluate(config.flag(flagKey), user);
            if (evaluation.inExperiment()) {
                logExposure(evaluation, user);
            }
            return evaluation;
        } catch (RuntimeException e) {
            log.debug("Cohort evaluation of {} failed", flagKey, e);
            return new Evaluation(flagKey, null, Reason.ERROR, false);
        }
    }

    /** The variant for this user, or null if the flag is unknown. */
    public String getVariant(String flagKey, CohortUser user) {
        return getVariant(flagKey, user, null);
    }

    public String getVariant(String flagKey, CohortUser user, String defaultVariant) {
        String variant = evaluate(flagKey, user).variant();
        return variant == null ? defaultVariant : variant;
    }

    /** For on/off flags: true when the served variant is "on" (or "true"). */
    public boolean isEnabled(String flagKey, CohortUser user, boolean defaultValue) {
        String variant = evaluate(flagKey, user).variant();
        if (variant == null) {
            return defaultValue;
        }
        return "on".equalsIgnoreCase(variant) || "true".equalsIgnoreCase(variant);
    }

    /** Records a conversion. The server credits it to the variant of the user's first exposure. */
    public void track(String flagKey, CohortUser user, String eventName) {
        try {
            FlagConfig config = store.get();
            String variant = config == null ? null : Evaluator.evaluate(config.flag(flagKey), user).variant();
            events.offer(CohortEvent.conversion(flagKey, variant, user.id(), eventName));
        } catch (RuntimeException e) {
            log.debug("Cohort track of {} failed", flagKey, e);
        }
    }

    private void logExposure(Evaluation evaluation, CohortUser user) {
        String key = evaluation.flagKey() + '\u0000' + user.id() + '\u0000' + evaluation.variant();
        if (exposed.size() >= MAX_REMEMBERED_EXPOSURES) {
            exposed.clear();
        }
        if (exposed.add(key)) {
            events.offer(CohortEvent.exposure(evaluation.flagKey(), evaluation.variant(), user.id()));
        }
    }

    public CohortStats stats() {
        return new CohortStats(
                store.version(),
                store.source().name(),
                evaluations.sum(),
                pollFailures.sum(),
                events.size(),
                events.sent.sum(),
                events.dropped.sum(),
                events.failed.sum(),
                lastSuccessfulSync);
    }

    /** Stops polling and flushes buffered events. */
    @Override
    public void close() {
        poller.shutdownNow();
        events.close();
    }

    public static final class Builder {
        private String serverUrl;
        private String apiKey;
        private Duration pollInterval = Duration.ofSeconds(10);
        private Duration flushInterval = Duration.ofSeconds(5);
        private Duration httpTimeout = Duration.ofSeconds(2);
        private int bufferCapacity = 10_000;
        private Path snapshotFile = Path.of(System.getProperty("user.home"), ".cohort", "config-snapshot.json");

        private Builder() {
        }

        public Builder serverUrl(String serverUrl) {
            this.serverUrl = serverUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        public Builder httpTimeout(Duration httpTimeout) {
            this.httpTimeout = httpTimeout;
            return this;
        }

        public Builder bufferCapacity(int bufferCapacity) {
            this.bufferCapacity = bufferCapacity;
            return this;
        }

        /** Where the last good config is kept; null disables the disk fallback. */
        public Builder snapshotFile(Path snapshotFile) {
            this.snapshotFile = snapshotFile;
            return this;
        }

        public CohortClient build() {
            Objects.requireNonNull(serverUrl, "serverUrl");
            Objects.requireNonNull(apiKey, "apiKey");
            if (bufferCapacity <= 0) {
                throw new IllegalArgumentException("bufferCapacity must be positive");
            }
            return new CohortClient(this);
        }
    }
}
