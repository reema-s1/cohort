package dev.cohort.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cohort.common.CohortUser;
import dev.cohort.common.Evaluation;
import dev.cohort.common.Evaluator;
import dev.cohort.common.FlagConfig;
import dev.cohort.common.FlagDefinition;
import dev.cohort.common.Rule;
import dev.cohort.common.Variant;
import dev.cohort.sdk.CohortClient;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per-evaluation cost of a realistic flag: an allowlist of 100 ids, an attribute rule and a
 * percentage rollout into a 50/50 split.
 *
 * <ul>
 *   <li>{@code evaluator}: the pure evaluator in cohort-common.</li>
 *   <li>{@code sdkGetVariant}: the full SDK path (snapshot read, evaluate, exposure dedup), with
 *       the client started offline from a disk snapshot so no network is involved.</li>
 * </ul>
 *
 * Run: {@code java -jar cohort-bench/target/benchmarks.jar}
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class EvaluationBenchmark {

    private static final int USERS = 4096;

    private FlagDefinition flag;
    private CohortUser[] users;
    private CohortClient client;
    private Path snapshotDir;
    private int next;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        List<String> allowlist = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            allowlist.add("qa-" + i);
        }
        flag = new FlagDefinition("new-search-ranking", true, false, "a1b2c3d4", "control", 1,
                List.of(new Variant("control", 5000), new Variant("treatment", 5000)),
                List.of(Rule.allowlist(1, allowlist, "treatment"),
                        Rule.attribute(2, "country", List.of("XX"), "control"),
                        Rule.percent(3, 5000, null)));
        users = new CohortUser[USERS];
        for (int i = 0; i < USERS; i++) {
            users[i] = CohortUser.of("user_" + i).with("country", i % 2 == 0 ? "IN" : "US");
        }

        // Start the SDK against a dead port so it falls back to a snapshot we write here.
        snapshotDir = Files.createTempDirectory("cohort-bench");
        Path snapshot = snapshotDir.resolve("snapshot.json");
        Files.writeString(snapshot, new ObjectMapper()
                .writeValueAsString(new FlagConfig(1, Map.of(flag.key(), flag))));
        client = CohortClient.builder()
                .serverUrl("http://127.0.0.1:1")
                .apiKey("bench")
                .httpTimeout(Duration.ofMillis(200))
                .pollInterval(Duration.ofHours(1))
                .flushInterval(Duration.ofHours(1))
                .bufferCapacity(USERS * 2)
                .snapshotFile(snapshot)
                .build();
        if (client.stats().configVersion() != 1) {
            throw new IllegalStateException("benchmark client did not load the snapshot");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        client.close();
        try (var files = Files.walk(snapshotDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private CohortUser nextUser() {
        next = (next + 1) & (USERS - 1);
        return users[next];
    }

    @Benchmark
    public Evaluation evaluator() {
        return Evaluator.evaluate(flag, nextUser());
    }

    @Benchmark
    public String sdkGetVariant() {
        return client.getVariant("new-search-ranking", nextUser());
    }
}
