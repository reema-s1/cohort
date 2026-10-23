package dev.cohort.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cohort.common.CohortUser;
import dev.cohort.sdk.CohortClient;
import dev.cohort.sdk.CohortStats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Drives fake experiment traffic through the real SDK so the results endpoint has something to
 * show: by default 100,000 users, control converting at 10% and treatment at 11%.
 *
 * <pre>mvn -pl cohort-simulator -am install -DskipTests &amp;&amp; mvn -pl cohort-simulator exec:java</pre>
 *
 * System properties: {@code server}, {@code adminKey}, {@code sdkKey}, {@code flag}, {@code users},
 * {@code controlRate}, {@code treatmentRate}.
 */
public final class Simulator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String server = System.getProperty("server", "http://localhost:8080");
        String adminKey = System.getProperty("adminKey", "dev-admin-key");
        String sdkKey = System.getProperty("sdkKey", "dev-sdk-key");
        String flag = System.getProperty("flag", "new-search-ranking");
        int users = Integer.getInteger("users", 100_000);
        double controlRate = Double.parseDouble(System.getProperty("controlRate", "0.10"));
        double treatmentRate = Double.parseDouble(System.getProperty("treatmentRate", "0.11"));

        ensureFlag(server, adminKey, flag);

        Map<String, Integer> assigned = new HashMap<>();
        Random random = new Random(42);
        Instant start = Instant.now();
        CohortStats stats;
        try (CohortClient cohort = CohortClient.builder()
                .serverUrl(server)
                .apiKey(sdkKey)
                .bufferCapacity(users * 2)
                .snapshotFile(null)
                .build()) {
            // A run id keeps repeated runs from colliding with users already counted.
            String run = Long.toString(System.currentTimeMillis(), 36);
            for (int i = 0; i < users; i++) {
                CohortUser user = CohortUser.of("sim-" + run + "-" + i);
                String variant = cohort.getVariant(flag, user, "control");
                assigned.merge(variant, 1, Integer::sum);
                double rate = "treatment".equals(variant) ? treatmentRate : controlRate;
                if (random.nextDouble() < rate) {
                    cohort.track(flag, user, "applied_to_job");
                }
            }
            System.out.printf("Evaluated %,d users in %d ms, assignments %s%n",
                    users, Duration.between(start, Instant.now()).toMillis(), assigned);
            System.out.println("Flushing events...");
        } finally {
            System.out.flush();
        }
        // close() flushed the buffer; give the aggregator a moment, then print results.
        Thread.sleep(3000);
        JsonNode results = get(server + "/api/v1/experiments/" + flag + "/results", adminKey);
        System.out.println();
        System.out.printf("%-12s %12s %12s %10s %10s %12s%n", "variant", "exposures", "conversions", "rate", "lift", "p-value");
        for (JsonNode v : results.get("variants")) {
            System.out.printf("%-12s %,12d %,12d %9.2f%% %10s %12s%n",
                    v.get("variant").asText(),
                    v.get("exposures").asLong(),
                    v.get("conversions").asLong(),
                    v.get("conversionRate").asDouble() * 100,
                    v.get("liftVsControl").isNull() ? "-" : String.format("%+.1f%%", v.get("liftVsControl").asDouble() * 100),
                    v.get("pValue").isNull() ? "-" : String.format("%.2e", v.get("pValue").asDouble()));
        }
        System.out.println("(cumulative across runs; results lag ingestion by a few seconds)");
    }

    private static void ensureFlag(String server, String adminKey, String flag) throws Exception {
        HttpResponse<String> existing = HTTP.send(request(server + "/api/v1/flags/" + flag, adminKey).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (existing.statusCode() == 200) {
            return;
        }
        Map<String, Object> body = Map.of(
                "key", flag,
                "description", "Simulated search ranking experiment",
                "defaultVariant", "control",
                "variants", List.of(Map.of("name", "control", "weight", 5000), Map.of("name", "treatment", "weight", 5000)),
                "rules", List.of(Map.of("priority", 1, "type", "PERCENT", "condition", Map.of("basisPoints", 10000))));
        HttpResponse<String> created = HTTP.send(request(server + "/api/v1/flags", adminKey)
                        .header("Content-Type", "application/json")
                        .header("X-Actor", "simulator")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (created.statusCode() != 201) {
            throw new IllegalStateException("could not create flag: " + created.statusCode() + " " + created.body());
        }
        System.out.println("Created flag " + flag);
    }

    private static JsonNode get(String url, String apiKey) throws Exception {
        return JSON.readTree(HTTP.send(request(url, apiKey).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private static HttpRequest.Builder request(String url, String apiKey) {
        return HttpRequest.newBuilder(URI.create(url)).header("X-API-Key", apiKey);
    }
}
