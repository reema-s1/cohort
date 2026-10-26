package dev.cohort.sdk;

import dev.cohort.common.CohortEvent;
import dev.cohort.common.CohortUser;
import dev.cohort.common.Evaluation.Reason;
import dev.cohort.common.FlagConfig;
import dev.cohort.common.FlagDefinition;
import dev.cohort.common.Rule;
import dev.cohort.common.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CohortClientTest {

    private static final List<Variant> AB = List.of(new Variant("control", 5000), new Variant("treatment", 5000));
    private static final List<Variant> ON_OFF = List.of(new Variant("on", 10000), new Variant("off", 0));

    @TempDir
    Path tmp;

    private FakeCohortServer server;

    private static FlagConfig config(long version, boolean darkModeEnabled) {
        FlagDefinition ranking = new FlagDefinition("new-search-ranking", true, false, "salt-1", "control", 1, AB,
                List.of(Rule.allowlist(1, List.of("qa-user"), "treatment"), Rule.percent(2, 10000, null)));
        FlagDefinition darkMode = new FlagDefinition("dark-mode", darkModeEnabled, false, "salt-2", "off", 1, ON_OFF,
                List.of(Rule.percent(1, 10000, "on")));
        return new FlagConfig(version, Map.of(ranking.key(), ranking, darkMode.key(), darkMode));
    }

    private CohortClient.Builder client() {
        return CohortClient.builder()
                .serverUrl(server.url())
                .apiKey("test")
                .pollInterval(Duration.ofMillis(100))
                .flushInterval(Duration.ofMillis(100))
                .httpTimeout(Duration.ofSeconds(2))
                .snapshotFile(tmp.resolve("snapshot.json"));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 s");
            }
            Thread.sleep(20);
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        server = new FakeCohortServer(config(1, true));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void evaluatesLocallyAndPollsWithEtag() throws Exception {
        try (CohortClient cohort = client().build()) {
            assertThat(cohort.getVariant("new-search-ranking", CohortUser.of("qa-user"))).isEqualTo("treatment");
            assertThat(cohort.isEnabled("dark-mode", CohortUser.of("anyone"), false)).isTrue();
            assertThat(cohort.stats().configSource()).isEqualTo("SERVER");

            await(() -> server.notModified.get() >= 2);
            assertThat(cohort.stats().pollFailures()).isZero();
        }
    }

    @Test
    void sameUserGetsSameVariantEveryTime() {
        try (CohortClient cohort = client().build()) {
            String first = cohort.getVariant("new-search-ranking", CohortUser.of("user_7"));
            for (int i = 0; i < 1000; i++) {
                assertThat(cohort.getVariant("new-search-ranking", CohortUser.of("user_7"))).isEqualTo(first);
            }
        }
    }

    @Test
    void killSwitchArrivesOnTheNextPoll() throws Exception {
        try (CohortClient cohort = client().build()) {
            assertThat(cohort.isEnabled("dark-mode", CohortUser.of("u"), false)).isTrue();
            server.setConfig(config(2, false));
            await(() -> !cohort.isEnabled("dark-mode", CohortUser.of("u"), true));
            assertThat(cohort.stats().configVersion()).isEqualTo(2);
        }
    }

    @Test
    void streamingDeliversAKillSwitchWithoutWaitingForAPoll() throws Exception {
        try (CohortClient cohort = client().pollInterval(Duration.ofHours(1)).streaming(true).build()) {
            await(() -> server.streamConnections.get() == 1);
            assertThat(cohort.isEnabled("dark-mode", CohortUser.of("u"), false)).isTrue();

            server.setConfig(config(2, false));
            server.announce();
            await(() -> !cohort.isEnabled("dark-mode", CohortUser.of("u"), true));
            assertThat(cohort.stats().configVersion()).isEqualTo(2);
        }
    }

    @Test
    void streamReconnectsAfterAnOutageAndCatchesUp() throws Exception {
        try (CohortClient cohort = client().pollInterval(Duration.ofHours(1)).streaming(true).build()) {
            await(() -> server.streamConnections.get() == 1);
            server.stop();
            server.setConfig(config(2, false)); // changed while the SDK could not hear about it
            server.restart();

            // The version sent on reconnect is newer than the SDK's, so it fetches straight away.
            await(() -> !cohort.isEnabled("dark-mode", CohortUser.of("u"), true));
            assertThat(server.streamConnections.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void olderConfigVersionsAreIgnored() throws Exception {
        server.setConfig(config(5, true));
        try (CohortClient cohort = client().build()) {
            server.setConfig(config(4, false));
            int before = server.configRequests.get();
            await(() -> server.configRequests.get() >= before + 2);
            assertThat(cohort.stats().configVersion()).isEqualTo(5);
            assertThat(cohort.isEnabled("dark-mode", CohortUser.of("u"), false)).isTrue();
        }
    }

    /** Chaos test: the server dies mid-flight and flag checks carry on with the last config. */
    @Test
    void keepsServingLastConfigWhenTheServerGoesDown() throws Exception {
        try (CohortClient cohort = client().build()) {
            String before = cohort.getVariant("new-search-ranking", CohortUser.of("user_42"));
            server.stop();
            await(() -> cohort.stats().pollFailures() >= 3);

            assertThatCode(() -> {
                for (int i = 0; i < 10_000; i++) {
                    cohort.getVariant("new-search-ranking", CohortUser.of("user_" + i));
                }
            }).doesNotThrowAnyException();
            assertThat(cohort.getVariant("new-search-ranking", CohortUser.of("user_42"))).isEqualTo(before);
            assertThat(cohort.getVariant("new-search-ranking", CohortUser.of("qa-user"))).isEqualTo("treatment");

            server.restart();
            server.setConfig(config(2, false));
            await(() -> !cohort.isEnabled("dark-mode", CohortUser.of("u"), true));
        }
    }

    @Test
    void restartDuringAnOutageUsesTheDiskSnapshot() {
        try (CohortClient first = client().build()) {
            assertThat(first.stats().configVersion()).isEqualTo(1);
        }
        server.stop();

        try (CohortClient restarted = client().build()) {
            assertThat(restarted.stats().configSource()).isEqualTo("DISK");
            assertThat(restarted.getVariant("new-search-ranking", CohortUser.of("qa-user"))).isEqualTo("treatment");
        }
    }

    @Test
    void noServerAndNoSnapshotFallsBackToCallerDefaults() {
        server.stop();
        try (CohortClient cohort = client().snapshotFile(null).build()) {
            assertThat(cohort.isEnabled("dark-mode", CohortUser.of("u"), true)).isTrue();
            assertThat(cohort.getVariant("new-search-ranking", CohortUser.of("u"), "control")).isEqualTo("control");
            assertThat(cohort.evaluate("new-search-ranking", CohortUser.of("u")).reason()).isEqualTo(Reason.FLAG_NOT_FOUND);
            assertThat(cohort.stats().configSource()).isEqualTo("NONE");
        }
    }

    @Test
    void unknownFlagServesCallerDefault() {
        try (CohortClient cohort = client().build()) {
            assertThat(cohort.getVariant("no-such-flag", CohortUser.of("u"), "fallback")).isEqualTo("fallback");
            assertThat(cohort.isEnabled("no-such-flag", CohortUser.of("u"), false)).isFalse();
        }
    }

    @Test
    void logsOneExposurePerUserOnlyForSplitAssignments() throws Exception {
        try (CohortClient cohort = client().build()) {
            for (int i = 0; i < 5; i++) {
                cohort.getVariant("new-search-ranking", CohortUser.of("user_1"));
            }
            cohort.getVariant("new-search-ranking", CohortUser.of("qa-user")); // allowlisted: no exposure
            cohort.isEnabled("dark-mode", CohortUser.of("user_1"), false);     // fixed variant: no exposure
            cohort.track("new-search-ranking", CohortUser.of("user_1"), "applied_to_job");
        }
        // close() flushed the buffer.
        List<CohortEvent> events = server.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(CohortEvent.Type.EXPOSURE);
        assertThat(events.get(0).userId()).isEqualTo("user_1");
        assertThat(events.get(1).type()).isEqualTo(CohortEvent.Type.CONVERSION);
        assertThat(events.get(1).eventName()).isEqualTo("applied_to_job");
    }

    @Test
    void fullBufferDropsEventsInsteadOfBlocking() {
        server.failEvents(true);
        try (CohortClient cohort = client().bufferCapacity(10).flushInterval(Duration.ofHours(1)).build()) {
            for (int i = 0; i < 50; i++) {
                cohort.track("new-search-ranking", CohortUser.of("user_" + i), "click");
            }
            assertThat(cohort.stats().eventsDropped()).isEqualTo(40);
            assertThat(cohort.stats().eventsQueued()).isEqualTo(10);
        }
    }

    @Test
    void eventsAreBatchedAndFlushedOnTheInterval() throws Exception {
        try (CohortClient cohort = client().bufferCapacity(5000).build()) {
            for (int i = 0; i < 1200; i++) {
                cohort.getVariant("new-search-ranking", CohortUser.of("user_" + i));
            }
            // The fake server records a batch before the SDK counts it as sent, so wait for both.
            await(() -> server.events().size() == 1200 && cohort.stats().eventsSent() == 1200);
            assertThat(cohort.stats().eventsDropped()).isZero();
        }
    }
}
