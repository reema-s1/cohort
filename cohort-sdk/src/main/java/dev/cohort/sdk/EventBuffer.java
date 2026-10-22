package dev.cohort.sdk;

import dev.cohort.common.CohortEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * A bounded queue of analytics events flushed by one background thread every flush interval, or
 * as soon as a full batch is waiting. When the queue is full new events are dropped and counted:
 * the app is never slowed down or blocked by analytics.
 */
final class EventBuffer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventBuffer.class);
    private static final int SEND_ATTEMPTS = 3;

    interface Sender {
        void send(List<CohortEvent> batch) throws Exception;
    }

    private final ArrayBlockingQueue<CohortEvent> queue;
    private final int maxBatch;
    private final Sender sender;
    private final ScheduledExecutorService flusher;
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    final LongAdder dropped = new LongAdder();
    final LongAdder sent = new LongAdder();
    final LongAdder failed = new LongAdder();

    EventBuffer(int capacity, int maxBatch, Duration flushInterval, Sender sender) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.maxBatch = maxBatch;
        this.sender = sender;
        // A single thread: batches never go out concurrently, and size-triggered flushes queue up
        // behind the timed ones instead of racing them.
        this.flusher = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("cohort-events").factory());
        long millis = flushInterval.toMillis();
        flusher.scheduleWithFixedDelay(this::flushQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Never blocks. Returns false (and counts a drop) when the buffer is full. */
    boolean offer(CohortEvent event) {
        if (!queue.offer(event)) {
            dropped.increment();
            return false;
        }
        if (queue.size() >= maxBatch && flushScheduled.compareAndSet(false, true)) {
            try {
                flusher.execute(this::flushQuietly);
            } catch (RuntimeException e) {
                flushScheduled.set(false); // closed
            }
        }
        return true;
    }

    int size() {
        return queue.size();
    }

    private void flushQuietly() {
        flushScheduled.set(false);
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Cohort event flush failed", e);
        }
    }

    /**
     * Sends everything queued, a batch at a time. If a batch still fails after retries it is
     * dropped and the rest stay queued for the next cycle, so a server outage costs at most one
     * batch per cycle rather than spinning on retries.
     */
    void flush() {
        List<CohortEvent> batch = new ArrayList<>(maxBatch);
        while (true) {
            batch.clear();
            queue.drainTo(batch, maxBatch);
            if (batch.isEmpty()) {
                return;
            }
            if (!sendWithRetry(batch)) {
                failed.add(batch.size());
                return;
            }
            sent.add(batch.size());
        }
    }

    private boolean sendWithRetry(List<CohortEvent> batch) {
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                // Retrying is safe: every event carries a UUID and the server drops repeats.
                sender.send(batch);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.debug("Cohort event send attempt {} failed", attempt, e);
                if (attempt < SEND_ATTEMPTS) {
                    try {
                        Thread.sleep(100L << attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /** Stops the background flusher, then sends whatever is left on the calling thread. */
    @Override
    public void close() {
        flusher.shutdown();
        try {
            flusher.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }
}
