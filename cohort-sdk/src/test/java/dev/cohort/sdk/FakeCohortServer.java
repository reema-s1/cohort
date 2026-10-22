package dev.cohort.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.cohort.common.CohortEvent;
import dev.cohort.common.EventBatch;
import dev.cohort.common.FlagConfig;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A stand-in for cohort-server's SDK endpoints that a test can stop and restart. */
final class FakeCohortServer implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<CohortEvent> events = new CopyOnWriteArrayList<>();
    final AtomicInteger configRequests = new AtomicInteger();
    final AtomicInteger notModified = new AtomicInteger();
    private volatile FlagConfig config;
    private volatile boolean failEvents;
    private HttpServer server;
    private int port;

    FakeCohortServer(FlagConfig config) throws IOException {
        this.config = config;
        start(0);
    }

    void start(int requestedPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        server.createContext("/sdk/v1/config", this::config);
        server.createContext("/sdk/v1/events", this::events);
        server.start();
        port = server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    /** Rebinds the same port; Windows can hold it briefly after stop, so retry for a moment. */
    void restart() throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                start(port);
                return;
            } catch (BindException e) {
                if (attempt == 20) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    String url() {
        return "http://127.0.0.1:" + port;
    }

    void setConfig(FlagConfig config) {
        this.config = config;
    }

    void failEvents(boolean fail) {
        this.failEvents = fail;
    }

    List<CohortEvent> events() {
        return events;
    }

    private void config(HttpExchange exchange) throws IOException {
        configRequests.incrementAndGet();
        FlagConfig current = config;
        String etag = "\"" + current.version() + "\"";
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            notModified.incrementAndGet();
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }
        byte[] body = mapper.writeValueAsBytes(current);
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void events(HttpExchange exchange) throws IOException {
        if (failEvents) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        EventBatch batch = mapper.readValue(exchange.getRequestBody(), EventBatch.class);
        events.addAll(batch.events());
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
