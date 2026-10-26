package dev.cohort.server.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cohort.common.FlagConfig;
import dev.cohort.common.Rule;
import dev.cohort.common.Variant;
import dev.cohort.server.TestcontainersConfiguration;
import dev.cohort.server.flag.FlagDtos.CreateFlagRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SdkApiIT {

    @Autowired
    TestRestTemplate http;

    @LocalServerPort
    int port;

    private static HttpHeaders headers(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        return headers;
    }

    private String createFlag() {
        String key = "sdk-" + UUID.randomUUID().toString().substring(0, 8);
        var request = new CreateFlagRequest(key, null, true, null, "control",
                List.of(new Variant("control", 5000), new Variant("treatment", 5000)),
                List.of(Rule.allowlist(1, List.of("qa-user"), "treatment"), Rule.percent(2, 10000, null)));
        http.exchange("/api/v1/flags", HttpMethod.POST, new HttpEntity<>(request, headers("dev-admin-key")), JsonNode.class);
        return key;
    }

    private ResponseEntity<FlagConfig> getConfig(String etag) {
        HttpHeaders headers = headers("dev-sdk-key");
        if (etag != null) {
            headers.setIfNoneMatch(etag);
        }
        return http.exchange("/sdk/v1/config", HttpMethod.GET, new HttpEntity<>(headers), FlagConfig.class);
    }

    @Test
    void configIsServedWithAnEtagAndReturns304WhenUnchanged() {
        String key = createFlag();
        ResponseEntity<FlagConfig> first = getConfig(null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().flag(key)).isNotNull();
        String etag = first.getHeaders().getETag();
        assertThat(etag).isEqualTo("\"" + first.getBody().version() + "\"");

        ResponseEntity<FlagConfig> second = getConfig(etag);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody()).isNull();
    }

    @Test
    void killSwitchShowsUpInTheNextConfigFetch() {
        String key = createFlag();
        ResponseEntity<FlagConfig> before = getConfig(null);
        assertThat(before.getBody().flag(key).enabled()).isTrue();

        http.exchange("/api/v1/flags/" + key + "/kill", HttpMethod.POST, new HttpEntity<>(headers("dev-admin-key")), JsonNode.class);

        ResponseEntity<FlagConfig> after = getConfig(before.getHeaders().getETag());
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getBody().version()).isGreaterThan(before.getBody().version());
        assertThat(after.getBody().flag(key).enabled()).isFalse();
    }

    @Test
    void streamAnnouncesTheCurrentVersionOnConnectAndEveryChangeAfter() throws Exception {
        String key = createFlag();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sdk/v1/stream"))
                .header("X-API-Key", "dev-sdk-key")
                .build();
        BlockingQueue<Long> versions = new LinkedBlockingQueue<>();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/event-stream"));
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                response.body()
                        .filter(line -> line.startsWith("data:"))
                        .forEach(line -> versions.add(Long.parseLong(line.substring(5).trim())));
            } catch (UncheckedIOException e) {
                // the test closed the stream
            }
        });
        try {
            Long onConnect = versions.poll(5, TimeUnit.SECONDS);
            assertThat(onConnect).isEqualTo(getConfig(null).getBody().version());

            http.exchange("/api/v1/flags/" + key + "/kill", HttpMethod.POST, new HttpEntity<>(headers("dev-admin-key")), JsonNode.class);

            // A heartbeat may repeat the current version first; wait for the newer one.
            Long afterKill = versions.poll(5, TimeUnit.SECONDS);
            while (afterKill != null && afterKill <= onConnect) {
                afterKill = versions.poll(5, TimeUnit.SECONDS);
            }
            assertThat(afterKill).isNotNull().isGreaterThan(onConnect);
            // The announced version is already being served, so fetching it right away is not a 304.
            ResponseEntity<FlagConfig> config = getConfig("\"" + onConnect + "\"");
            assertThat(config.getBody().version()).isEqualTo(afterKill);
            assertThat(config.getBody().flag(key).enabled()).isFalse();
        } finally {
            response.body().close();
            reader.interrupt();
        }
    }

    @Test
    void archivedFlagsAreLeftOutOfTheConfig() {
        String key = createFlag();
        http.exchange("/api/v1/flags/" + key, HttpMethod.DELETE, new HttpEntity<>(headers("dev-admin-key")), JsonNode.class);
        assertThat(getConfig(null).getBody().flag(key)).isNull();
    }

    @Test
    void serverSideEvaluationMatchesTheRules() {
        String key = createFlag();
        var request = Map.of("userId", "qa-user", "flags", List.of(key, "does-not-exist"));
        JsonNode body = http.exchange("/sdk/v1/evaluate", HttpMethod.POST,
                new HttpEntity<>(request, headers("dev-sdk-key")), JsonNode.class).getBody();

        JsonNode evaluation = body.get("evaluations").get(key);
        assertThat(evaluation.get("variant").asText()).isEqualTo("treatment");
        assertThat(evaluation.get("reason").asText()).isEqualTo("RULE_MATCH");
        assertThat(body.get("evaluations").get("does-not-exist").get("reason").asText()).isEqualTo("FLAG_NOT_FOUND");
    }

    @Test
    void configRequiresAnApiKey() {
        assertThat(http.getForEntity("/sdk/v1/config", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
