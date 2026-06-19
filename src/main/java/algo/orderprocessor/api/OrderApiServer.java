package algo.orderprocessor.api;

import algo.orderprocessor.Order;
import algo.orderprocessor.OrderProcessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lightweight REST API in front of the {@link OrderProcessor}, built on the JDK's
 * built-in {@link HttpServer} so the project stays dependency-free.
 *
 * <p>Endpoints:
 * <ul>
 *     <li>{@code GET  /health}   &rarr; liveness probe</li>
 *     <li>{@code POST /orders}   &rarr; submit an order ({@code {"orderId":"o1","premium":true,"creationTime":123}})</li>
 *     <li>{@code GET  /metrics}  &rarr; submitted / processed / failed / pending counters</li>
 *     <li>{@code POST /shutdown} &rarr; drain the queue and stop accepting new orders</li>
 * </ul>
 */
public final class OrderApiServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final OrderProcessor processor;

    public OrderApiServer(int port, OrderProcessor processor) throws IOException {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.httpServer.setExecutor(Executors.newCachedThreadPool());

        httpServer.createContext("/health", this::handleHealth);
        httpServer.createContext("/orders", this::handleOrders);
        httpServer.createContext("/metrics", this::handleMetrics);
        httpServer.createContext("/shutdown", this::handleShutdown);
    }

    /** Convenience factory that wires a processor with the given worker count and a no-op handler. */
    public static OrderApiServer create(int port, int workerCount, Consumer<Order> handler) throws IOException {
        OrderProcessor processor = new OrderProcessor(workerCount, handler);
        processor.startProcessing();
        return new OrderApiServer(port, processor);
    }

    public OrderApiServer start() {
        httpServer.start();
        return this;
    }

    /** The actual port the server is bound to (useful when constructed with port 0). */
    public int port() {
        return httpServer.getAddress().getPort();
    }

    public OrderProcessor processor() {
        return processor;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        respond(exchange, 200, Map.of("status", "UP", "running", processor.isRunning()));
    }

    private void handleOrders(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            Map<String, Object> json = Json.parse(body);
            Object orderId = json.get("orderId");
            if (!(orderId instanceof String id) || id.isBlank()) {
                respond(exchange, 400, Map.of("error", "orderId is required"));
                return;
            }
            boolean premium = Boolean.TRUE.equals(json.get("premium"));
            long creationTime = json.get("creationTime") instanceof Number n
                ? n.longValue()
                : System.currentTimeMillis();

            processor.submitOrder(new Order(id, premium, creationTime));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accepted", true);
            response.put("orderId", id);
            response.put("premium", premium);
            response.put("submittedCount", processor.getSubmittedCount());
            respond(exchange, 202, response);
        } catch (IllegalStateException shuttingDown) {
            respond(exchange, 409, Map.of("error", shuttingDown.getMessage()));
        } catch (IllegalArgumentException badJson) {
            respond(exchange, 400, Map.of("error", "invalid JSON: " + badJson.getMessage()));
        }
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("submitted", processor.getSubmittedCount());
        metrics.put("processed", processor.getProcessedCount());
        metrics.put("failed", processor.getFailedCount());
        metrics.put("pending", processor.getPendingCount());
        metrics.put("running", processor.isRunning());
        respond(exchange, 200, metrics);
    }

    private void handleShutdown(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        processor.shutdown();
        respond(exchange, 200, Map.of(
            "status", "shutdown complete",
            "processed", processor.getProcessedCount(),
            "failed", processor.getFailedCount()));
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = Json.write(new LinkedHashMap<>(payload)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Stops the HTTP server and gracefully drains the underlying processor. */
    @Override
    public void close() {
        httpServer.stop(0);
        if (processor.isRunning()) {
            processor.shutdown();
        }
        processor.close();
    }

    /** Stop the HTTP server, waiting up to the given duration for in-flight exchanges. */
    public void stop(Duration grace) {
        httpServer.stop((int) Math.max(0, grace.toSeconds()));
    }
}

