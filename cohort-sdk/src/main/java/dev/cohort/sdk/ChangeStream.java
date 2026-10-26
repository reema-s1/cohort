package dev.cohort.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * Listens on the server's config change stream and reports each announced version. Reconnects
 * with exponential backoff; while it is down, polling still delivers changes, just later.
 */
final class ChangeStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChangeStream.class);

    private static final long MIN_BACKOFF_MILLIS = 500;
    private static final long MAX_BACKOFF_MILLIS = Duration.ofSeconds(30).toMillis();

    private final CohortHttp http;
    private final LongConsumer onVersion;
    private final Thread thread;
    private volatile boolean closed;
    private volatile Stream<String> open;

    ChangeStream(CohortHttp http, LongConsumer onVersion) {
        this.http = http;
        this.onVersion = onVersion;
        this.thread = Thread.ofVirtual().name("cohort-stream").start(this::run);
    }

    private void run() {
        long backoff = MIN_BACKOFF_MILLIS;
        while (!closed) {
            try (Stream<String> lines = http.openStream()) {
                open = lines;
                if (closed) {
                    return; // close() ran before open was set, so it could not close this one
                }
                backoff = MIN_BACKOFF_MILLIS;
                lines.forEach(this::onLine); // returns when the server ends the stream
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.debug("Cohort config stream dropped", e);
            } finally {
                open = null;
            }
            if (closed) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
        }
    }

    /** Only {@code data:} lines matter; event names, comments and blank separators are skipped. */
    private void onLine(String line) {
        if (!line.startsWith("data:")) {
            return;
        }
        try {
            onVersion.accept(Long.parseLong(line.substring("data:".length()).trim()));
        } catch (NumberFormatException e) {
            log.debug("Ignoring unexpected config stream line: {}", line);
        }
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
        Stream<String> lines = open;
        if (lines != null) {
            lines.close();
        }
    }
}
