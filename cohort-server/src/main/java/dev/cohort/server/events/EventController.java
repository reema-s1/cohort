package dev.cohort.server.events;

import dev.cohort.common.CohortEvent;
import dev.cohort.common.EventBatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Validates a batch and publishes it to Kafka. It does no database work, so it stays fast and a
 * slow aggregator never backs up the SDKs.
 */
@RestController
@RequestMapping("/sdk/v1")
public class EventController {

    private final KafkaTemplate<String, CohortEvent> kafka;
    private final EventProperties properties;
    private final Counter ingested;

    public EventController(KafkaTemplate<String, CohortEvent> kafka, EventProperties properties, MeterRegistry registry) {
        this.kafka = kafka;
        this.properties = properties;
        this.ingested = Counter.builder("cohort.events.ingested").description("Events accepted and written to Kafka").register(registry);
    }

    @PostMapping("/events")
    public ResponseEntity<?> ingest(@RequestBody EventBatch batch) {
        if (batch.events().size() > EventBatch.MAX_EVENTS) {
            return problem(HttpStatus.PAYLOAD_TOO_LARGE, "at most " + EventBatch.MAX_EVENTS + " events per batch");
        }
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < batch.events().size(); i++) {
            String error = validate(batch.events().get(i));
            if (error != null) {
                errors.add("events[" + i + "]: " + error);
            }
        }
        if (!errors.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid events");
            problem.setProperty("errors", errors);
            return ResponseEntity.badRequest().body(problem);
        }

        // Keyed by flagKey:userId so one user's exposure and conversion stay in order on one partition.
        CompletableFuture<?>[] sends = batch.events().stream()
                .map(event -> kafka.send(properties.topic(), event.partitionKey(), event))
                .toArray(CompletableFuture[]::new);
        try {
            // Acknowledge only once Kafka has the events; on failure the SDK retries and dedup
            // absorbs any that did get through.
            CompletableFuture.allOf(sends).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "interrupted while publishing events");
        } catch (Exception e) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "event log unavailable; retry later");
        }
        ingested.increment(batch.events().size());
        return ResponseEntity.accepted().body(Map.of("accepted", batch.events().size()));
    }

    private static String validate(CohortEvent event) {
        if (event == null) {
            return "event is null";
        }
        if (event.id() == null || event.type() == null || event.timestamp() == null) {
            return "id, type and timestamp are required";
        }
        if (isBlank(event.flagKey()) || isBlank(event.userId())) {
            return "flagKey and userId are required";
        }
        if (event.type() == CohortEvent.Type.EXPOSURE && isBlank(event.variant())) {
            return "exposures need a variant";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
