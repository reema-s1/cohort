package dev.cohort.server.sdk;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-sent events for SDKs that want changes immediately rather than on their next poll.
 *
 * <p>The stream carries only the new config version ({@code event: config}, {@code data: 42}); the
 * SDK then fetches the config the usual way, with its ETag. So the stream is a hint, not a second
 * source of truth: a dropped or missed message only means the change arrives on the next poll.
 * Each connection gets the current version on connect, so a reconnecting SDK catches up at once.
 */
@Component
public class ConfigStream {

    private static final Logger log = LoggerFactory.getLogger(ConfigStream.class);

    /** Connections are recycled now and then; SDKs reconnect, which also clears out dead peers. */
    private static final Duration CONNECTION_LIFETIME = Duration.ofMinutes(10);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastPublished = new AtomicLong(-1);
    // A slow client must not hold up the transaction listener that publishes the change.
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

    public ConfigStream(MeterRegistry registry) {
        Gauge.builder("cohort.config.streams", emitters, Set::size)
                .description("SDKs connected to the config change stream")
                .register(registry);
    }

    public SseEmitter subscribe(long currentVersion) {
        SseEmitter emitter = new SseEmitter(CONNECTION_LIFETIME.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitter, currentVersion);
        return emitter;
    }

    /** Tells every connected SDK about a version. Versions already announced are skipped. */
    public void publish(long version) {
        if (lastPublished.getAndAccumulate(version, Math::max) >= version) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            sender.execute(() -> send(emitter, version));
        }
    }

    /** A comment line keeps idle connections from being cut by proxies. */
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            sender.execute(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException e) {
                    drop(emitter);
                }
            });
        }
    }

    private void send(SseEmitter emitter, long version) {
        try {
            emitter.send(SseEmitter.event().name("config").data(Long.toString(version), MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException e) {
            drop(emitter);
        }
    }

    /** The client went away; a plain complete, since that is routine, not a server error. */
    private void drop(SseEmitter emitter) {
        emitters.remove(emitter);
        emitter.complete();
    }

    /**
     * Streams never finish on their own, so close them before graceful shutdown starts waiting
     * for in-flight requests; SDKs reconnect to another instance.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        log.info("Closing {} config streams", emitters.size());
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
        sender.shutdown();
    }
}
