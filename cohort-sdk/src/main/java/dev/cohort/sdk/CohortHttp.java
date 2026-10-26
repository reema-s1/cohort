package dev.cohort.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cohort.common.CohortEvent;
import dev.cohort.common.EventBatch;
import dev.cohort.common.FlagConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** The SDK's calls to the server: poll config, send events, and optionally listen for changes. */
final class CohortHttp {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final HttpClient client;
    private final URI configUri;
    private final URI eventsUri;
    private final URI streamUri;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper mapper;

    CohortHttp(String serverUrl, String apiKey, Duration timeout, ObjectMapper mapper) {
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.configUri = URI.create(base + "/sdk/v1/config");
        this.eventsUri = URI.create(base + "/sdk/v1/events");
        this.streamUri = URI.create(base + "/sdk/v1/stream");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * @param knownVersion the version the SDK already has, sent as If-None-Match; negative for none
     * @return the new config, or empty when the server says 304 Not Modified
     */
    Optional<FlagConfig> fetchConfig(long knownVersion) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(configUri)
                .timeout(timeout)
                .header(API_KEY_HEADER, apiKey)
                .GET();
        if (knownVersion >= 0) {
            request.header("If-None-Match", "\"" + knownVersion + "\"");
        }
        HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 304) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("config fetch failed with HTTP " + response.statusCode());
        }
        return Optional.of(mapper.readValue(response.body(), FlagConfig.class));
    }

    /**
     * Opens the server-sent event stream of config versions. The returned lines arrive as the
     * server sends them; closing the stream closes the connection.
     */
    Stream<String> openStream() throws IOException, InterruptedException {
        // No request timeout: it would cut a healthy idle stream. The server recycles connections.
        HttpRequest request = HttpRequest.newBuilder(streamUri)
                .header(API_KEY_HEADER, apiKey)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("config stream failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    void sendEvents(List<CohortEvent> events) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(eventsUri)
                .timeout(timeout)
                .header(API_KEY_HEADER, apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(new EventBatch(events))))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("event send failed with HTTP " + response.statusCode());
        }
    }
}
