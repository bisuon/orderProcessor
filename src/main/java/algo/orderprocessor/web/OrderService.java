package algo.orderprocessor.web;

import algo.orderprocessor.Order;
import algo.orderprocessor.OrderProcessor;
import algo.orderprocessor.web.dto.MetricsResponse;
import algo.orderprocessor.web.dto.OrderRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Spring-managed facade over the dependency-free {@link OrderProcessor} engine.
 *
 * <p>The engine itself is intentionally framework-agnostic; this service owns its
 * lifecycle (start on context refresh, graceful drain on shutdown) and adds structured
 * logging so order flow is observable in production.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderProcessor processor;
    private final LongAdder premiumProcessed = new LongAdder();
    private final LongAdder standardProcessed = new LongAdder();
    private final Map<String, OrderRecord> orderStore = Collections.synchronizedMap(new LinkedHashMap<>()); // in-memory store

    public OrderService(@Value("${app.workers:0}") int workers) {
        int workerCount = workers > 0 ? workers : Math.max(2, Runtime.getRuntime().availableProcessors());
        this.processor = new OrderProcessor(workerCount, this::handleWithTracking);
        log.info("OrderProcessor configured with {} worker threads", workerCount);
    }

    @PostConstruct
    void start() {
        processor.startProcessing();
        log.info("OrderProcessor started and accepting orders");
    }

    @PreDestroy
    void stop() {
        log.info("Shutting down OrderProcessor (draining queue)...");
        processor.shutdown();
        log.info("OrderProcessor shutdown complete: processed={}, failed={}",
            processor.getProcessedCount(), processor.getFailedCount());
    }

    /** Submit an order for asynchronous, priority-ordered processing. */
    public void submit(Order order) {
        OrderRecord record = OrderRecord.newSubmitted(order.orderId(), order.isPremium(), order.creationTime());

        // Reject duplicate order IDs so UI/API clients can rely on stable order identity.
        synchronized (orderStore) {
            if (orderStore.containsKey(order.orderId())) {
                throw new IllegalStateException("Order with id '" + order.orderId() + "' already exists");
            }
            orderStore.put(order.orderId(), record);
        }

        try {
            processor.submitOrder(order);
        } catch (RuntimeException ex) {
            // Roll back the record if enqueueing fails (e.g., shutdown race).
            synchronized (orderStore) {
                orderStore.remove(order.orderId());
            }
            throw ex;
        }

        log.debug("Accepted order id={} premium={}", order.orderId(), order.isPremium());
    }

    public long getSubmittedCount() {
        return processor.getSubmittedCount();
    }

    public boolean isRunning() {
        return processor.isRunning();
    }

    public MetricsResponse metrics() {
        return new MetricsResponse(
            processor.getSubmittedCount(),
            processor.getProcessedCount(),
            processor.getFailedCount(),
            processor.getPendingCount(),
            premiumProcessed.sum(),
            standardProcessed.sum(),
            processor.isRunning());
    }

    /** Retrieve a single order by its ID. */
    public Optional<OrderRecord> getOrder(String orderId) {
        return Optional.ofNullable(orderStore.get(orderId));
    }

    /** Retrieve recent orders (most recent first). */
    public List<OrderRecord> getRecentOrders(int limit) {
        return orderStore.values().stream()
            .sorted((a, b) -> Long.compare(b.submittedAt(), a.submittedAt())) // most recent first
            .limit(limit)
            .toList();
    }

    /** Drain and stop the engine (only used by Spring lifecycle). */
    public void shutdown() {
        stop();
    }

    private void handle(Order order) {
        if (order.isPremium()) {
            premiumProcessed.increment();
        } else {
            standardProcessed.increment();
        }
        orderStore.computeIfPresent(order.orderId(), (k, v) -> v.markProcessed());
    }

    /** Handler with failure tracking: wraps the business handler and catches exceptions to mark failures. */
    private void handleWithTracking(Order order) {
        try {
            handle(order);
        } catch (RuntimeException ex) {
            orderStore.computeIfPresent(order.orderId(), (k, v) -> v.markFailed());
            throw ex; // rethrow so OrderProcessor counts it as a failure
        }
    }
}
