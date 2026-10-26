package dev.cohort.server.flag;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cohort.common.Layer;
import dev.cohort.common.Rule;
import dev.cohort.common.Variant;
import dev.cohort.server.TestcontainersConfiguration;
import dev.cohort.server.flag.FlagDtos.CreateFlagRequest;
import dev.cohort.server.flag.FlagDtos.FlagResponse;
import dev.cohort.server.flag.FlagDtos.UpdateFlagRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class FlagApiIT {

    private static final List<Variant> AB = List.of(new Variant("control", 5000), new Variant("treatment", 5000));

    @Autowired
    TestRestTemplate http;

    @Autowired
    ConfigVersionRepository configVersion;

    private HttpHeaders admin() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "dev-admin-key");
        headers.set("X-Actor", "alice@example.com");
        return headers;
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, Object body, Class<T> type) {
        return http.exchange(path, method, new HttpEntity<>(body, admin()), type);
    }

    private FlagResponse create(String key) {
        var request = new CreateFlagRequest(key, "test flag", true, null, "control", AB,
                List.of(Rule.percent(1, 10000, null)));
        ResponseEntity<FlagResponse> response = call(HttpMethod.POST, "/api/v1/flags", request, FlagResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static String uniqueKey() {
        return "flag-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createsAndReadsBackAFlagWithAGeneratedSalt() {
        String key = uniqueKey();
        FlagResponse created = create(key);

        FlagResponse fetched = call(HttpMethod.GET, "/api/v1/flags/" + key, null, FlagResponse.class).getBody();
        assertThat(fetched.variants()).containsExactlyElementsOf(AB);
        assertThat(fetched.rules()).hasSize(1);
        assertThat(fetched.salt()).isNotBlank().isEqualTo(created.salt());
        assertThat(fetched.enabled()).isTrue();
    }

    @Test
    void rejectsWeightsThatDoNotSumToTenThousand() {
        var request = new CreateFlagRequest(uniqueKey(), null, true, null, "a",
                List.of(new Variant("a", 4000), new Variant("b", 4000)), List.of());
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/flags", request, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString()).contains("sum to 10000");
    }

    @Test
    void rejectsDuplicateKeys() {
        String key = uniqueKey();
        create(key);
        var request = new CreateFlagRequest(key, null, true, null, "control", AB, List.of());
        assertThat(call(HttpMethod.POST, "/api/v1/flags", request, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void staleUpdateIsRejectedWith409() {
        String key = uniqueKey();
        FlagResponse created = create(key);
        var first = new UpdateFlagRequest("v2", true, "control", AB, List.of(), created.version());
        ResponseEntity<FlagResponse> ok = call(HttpMethod.PUT, "/api/v1/flags/" + key, first, FlagResponse.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().version()).isEqualTo(created.version() + 1);

        // Second editor still holds the original version.
        var stale = new UpdateFlagRequest("v2-other", true, "control", AB, List.of(), created.version());
        assertThat(call(HttpMethod.PUT, "/api/v1/flags/" + key, stale, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.GET, "/api/v1/flags/" + key, null, FlagResponse.class).getBody().description())
                .isEqualTo("v2");
    }

    @Test
    void concurrentEditsFromTheSameVersionLetExactlyOneWin() throws Exception {
        String key = uniqueKey();
        FlagResponse created = create(key);
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String description = "writer-" + i;
            results.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                var request = new UpdateFlagRequest(description, true, "control", AB, List.of(), created.version());
                return (HttpStatus) call(HttpMethod.PUT, "/api/v1/flags/" + key, request, JsonNode.class).getStatusCode();
            }));
        }
        start.countDown();
        List<HttpStatus> statuses = results.stream().map(CompletableFuture::join).toList();
        assertThat(statuses).filteredOn(s -> s == HttpStatus.OK).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(writers - 1);
    }

    @Test
    void killSwitchDisablesTheFlagBumpsConfigVersionAndIsAudited() {
        String key = uniqueKey();
        create(key);
        long versionBefore = configVersion.current();

        FlagResponse killed = call(HttpMethod.POST, "/api/v1/flags/" + key + "/kill", null, FlagResponse.class).getBody();
        assertThat(killed.enabled()).isFalse();
        assertThat(configVersion.current()).isGreaterThan(versionBefore);

        JsonNode audit = call(HttpMethod.GET, "/api/v1/flags/" + key + "/audit", null, JsonNode.class).getBody();
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0).get("action").asText()).isEqualTo("KILL");
        assertThat(audit.get(0).get("actor").asText()).isEqualTo("alice@example.com");
        assertThat(audit.get(0).get("before").get("enabled").asBoolean()).isTrue();
        assertThat(audit.get(0).get("after").get("enabled").asBoolean()).isFalse();
        assertThat(audit.get(1).get("action").asText()).isEqualTo("CREATE");
    }

    @Test
    void archivedFlagsAreHiddenFromTheDefaultListing() {
        String key = uniqueKey();
        create(key);
        call(HttpMethod.DELETE, "/api/v1/flags/" + key, null, FlagResponse.class);

        FlagResponse[] live = call(HttpMethod.GET, "/api/v1/flags", null, FlagResponse[].class).getBody();
        assertThat(live).extracting(FlagResponse::key).doesNotContain(key);
        FlagResponse[] all = call(HttpMethod.GET, "/api/v1/flags?includeArchived=true", null, FlagResponse[].class).getBody();
        assertThat(all).extracting(FlagResponse::key).contains(key);
    }

    @Test
    void requestsWithoutTheAdminKeyAreRejected() {
        assertThat(http.getForEntity("/api/v1/flags", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders sdkOnly = new HttpHeaders();
        sdkOnly.set("X-API-Key", "dev-sdk-key");
        assertThat(http.exchange("/api/v1/flags", HttpMethod.GET, new HttpEntity<>(sdkOnly), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<JsonNode> createInLayer(String key, Layer layer) {
        var request = new CreateFlagRequest(key, null, true, null, "control", AB,
                List.of(Rule.percent(1, 10000, null)), layer);
        return call(HttpMethod.POST, "/api/v1/flags", request, JsonNode.class);
    }

    @Test
    void flagsInALayerCannotClaimOverlappingSlices() {
        String layer = "layer-" + UUID.randomUUID().toString().substring(0, 8);
        String first = uniqueKey();
        assertThat(createInLayer(first, new Layer(layer, 0, 5000)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createInLayer(uniqueKey(), new Layer(layer, 5000, 10000)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> overlap = createInLayer(uniqueKey(), new Layer(layer, 4000, 6000));
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(overlap.getBody().get("detail").asText()).contains("overlaps flag '" + first + "'");
        // The same range in a different layer is a separate hash space, so it is fine.
        assertThat(createInLayer(uniqueKey(), new Layer(layer + "-other", 4000, 6000)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Archiving the first flag frees its slice.
        call(HttpMethod.DELETE, "/api/v1/flags/" + first, null, FlagResponse.class);
        assertThat(createInLayer(uniqueKey(), new Layer(layer, 1000, 4000)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anUpdateCannotMoveAFlagOntoAnotherFlagsSlice() {
        String layer = "layer-" + UUID.randomUUID().toString().substring(0, 8);
        createInLayer(uniqueKey(), new Layer(layer, 0, 5000));
        String key = uniqueKey();
        FlagResponse created = create(key);
        assertThat(created.layer()).isNull();

        var onto = new UpdateFlagRequest(null, true, "control", AB, List.of(), created.version(), new Layer(layer, 2500, 7500));
        assertThat(call(HttpMethod.PUT, "/api/v1/flags/" + key, onto, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var beside = new UpdateFlagRequest(null, true, "control", AB, List.of(), created.version(), new Layer(layer, 5000, 7500));
        FlagResponse moved = call(HttpMethod.PUT, "/api/v1/flags/" + key, beside, FlagResponse.class).getBody();
        assertThat(moved.layer()).isEqualTo(new Layer(layer, 5000, 7500));
        assertThat(call(HttpMethod.GET, "/api/v1/flags/" + key, null, FlagResponse.class).getBody().layer())
                .isEqualTo(new Layer(layer, 5000, 7500));
    }
}
