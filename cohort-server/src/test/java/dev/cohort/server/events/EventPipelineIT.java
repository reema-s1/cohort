package dev.cohort.server.events;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cohort.common.CohortEvent;
import dev.cohort.common.EventBatch;
import dev.cohort.common.Rule;
import dev.cohort.common.Variant;
import dev.cohort.server.TestcontainersConfiguration;
import dev.cohort.server.flag.FlagDtos.CreateFlagRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class EventPipelineIT {

    @Autowired
    TestRestTemplate http;

    private static HttpHeaders headers(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", key);
        return headers;
    }

    private String createFlag() {
        String key = "exp-" + UUID.randomUUID().toString().substring(0, 8);
        var request = new CreateFlagRequest(key, null, true, null, "control",
                List.of(new Variant("control", 5000), new Variant("treatment", 5000)), List.of(Rule.percent(1, 10000, null)));
        http.exchange("/api/v1/flags", HttpMethod.POST, new HttpEntity<>(request, headers("dev-admin-key")), JsonNode.class);
        return key;
    }

    private HttpStatus post(List<CohortEvent> events) {
        return (HttpStatus) http.exchange("/sdk/v1/events", HttpMethod.POST,
                new HttpEntity<>(new EventBatch(events), headers("dev-sdk-key")), JsonNode.class).getStatusCode();
    }

    private JsonNode results(String key) {
        return http.exchange("/api/v1/experiments/" + key + "/results", HttpMethod.GET,
                new HttpEntity<>(headers("dev-admin-key")), JsonNode.class).getBody();
    }

    private static JsonNode variant(JsonNode results, String name) {
        for (JsonNode v : results.get("variants")) {
            if (v.get("variant").asText().equals(name)) {
                return v;
            }
        }
        throw new AssertionError("no variant " + name + " in " + results);
    }

    @Test
    void countsEachUserOnceAndIgnoresDuplicatesAndOrphans() throws Exception {
        String key = createFlag();
        CohortEvent retried = CohortEvent.exposure(key, "control", "u1");
        List<CohortEvent> events = List.of(
                retried,
                CohortEvent.exposure(key, "control", "u1"),            // second exposure, same user
                CohortEvent.exposure(key, "treatment", "u1"),          // later exposure in another variant
                CohortEvent.exposure(key, "treatment", "u2"),
                CohortEvent.conversion(key, "treatment", "u2", "applied_to_job"),
                CohortEvent.conversion(key, "treatment", "u2", "applied_to_job"), // converts once per user
                CohortEvent.exposure(key, "control", "u3"),
                CohortEvent.conversion(key, "control", "u3", "applied_to_job"),
                CohortEvent.conversion(key, "control", "u9", "applied_to_job")); // never exposed
        assertThat(post(events)).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post(List.of(retried))).isEqualTo(HttpStatus.ACCEPTED); // SDK retry of the same event

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode results = results(key);
        while (results.get("totalExposures").asLong() < 3 || variant(results, "treatment").get("conversions").asLong() < 1
                || variant(results, "control").get("conversions").asLong() < 1) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(200);
            results = results(key);
        }
        Thread.sleep(1000); // let any stray duplicate land before asserting exact counts
        results = results(key);

        JsonNode control = variant(results, "control");
        JsonNode treatment = variant(results, "treatment");
        assertThat(control.get("exposures").asLong()).isEqualTo(2);    // u1, u3
        assertThat(control.get("conversions").asLong()).isEqualTo(1);  // u3
        assertThat(treatment.get("exposures").asLong()).isEqualTo(1);  // u2
        assertThat(treatment.get("conversions").asLong()).isEqualTo(1); // u2, once
        assertThat(treatment.get("conversionRate").asDouble()).isEqualTo(1.0);
        assertThat(results.get("control").asText()).isEqualTo("control");
    }

    @Test
    void rejectsMalformedAndOversizedBatches() {
        CohortEvent noVariant = new CohortEvent(UUID.randomUUID(), CohortEvent.Type.EXPOSURE, "f", null, "u", null, Instant.now());
        assertThat(post(List.of(noVariant))).isEqualTo(HttpStatus.BAD_REQUEST);

        List<CohortEvent> tooMany = new ArrayList<>();
        for (int i = 0; i <= EventBatch.MAX_EVENTS; i++) {
            tooMany.add(CohortEvent.exposure("f", "control", "u" + i));
        }
        assertThat(post(tooMany)).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void resultsForAnUnknownFlagAre404() {
        assertThat(http.exchange("/api/v1/experiments/nope/results", HttpMethod.GET,
                new HttpEntity<>(headers("dev-admin-key")), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
