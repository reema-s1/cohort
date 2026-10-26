package dev.cohort.server.sdk;

import dev.cohort.common.CohortUser;
import dev.cohort.common.Evaluation;
import dev.cohort.common.Evaluator;
import dev.cohort.common.FlagConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sdk/v1")
public class SdkController {

    private final ConfigService configService;
    private final ConfigStream configStream;

    public SdkController(ConfigService configService, ConfigStream configStream) {
        this.configService = configService;
        this.configStream = configStream;
    }

    /**
     * Full flag config. The ETag is the global config version; SDKs send it back in
     * If-None-Match and get an empty 304 when nothing changed, which is most polls.
     */
    @GetMapping("/config")
    public ResponseEntity<byte[]> config(WebRequest request) {
        ConfigService.Snapshot snapshot = configService.current();
        if (request.checkNotModified(snapshot.etag())) {
            return null; // Spring has already written the 304 and the ETag header.
        }
        return ResponseEntity.ok()
                .eTag(snapshot.etag())
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.APPLICATION_JSON)
                .body(snapshot.json());
    }

    /**
     * Server-sent events: {@code event: config} with the new config version whenever a flag
     * changes, and once on connect. The SDK fetches {@code /config} when the version is newer.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return configStream.subscribe(configService.current().version());
    }

    public record EvaluateRequest(@NotBlank String userId, Map<String, String> attributes, List<String> flags) {
    }

    public record EvaluateResponse(long configVersion, Map<String, Evaluation> evaluations) {
    }

    /** Server-side evaluation for clients that cannot run the SDK. Uses the same evaluator. */
    @PostMapping("/evaluate")
    public EvaluateResponse evaluate(@Valid @RequestBody EvaluateRequest request) {
        FlagConfig config = configService.current().config();
        CohortUser user = new CohortUser(request.userId(), request.attributes());
        Collection<String> keys = request.flags() == null || request.flags().isEmpty()
                ? config.flags().keySet().stream().sorted().toList()
                : request.flags();
        Map<String, Evaluation> evaluations = new LinkedHashMap<>();
        for (String key : keys) {
            Evaluation evaluation = Evaluator.evaluate(config.flag(key), user);
            evaluations.put(key, evaluation.flagKey() == null
                    ? new Evaluation(key, null, evaluation.reason(), false)
                    : evaluation);
        }
        return new EvaluateResponse(config.version(), evaluations);
    }
}
