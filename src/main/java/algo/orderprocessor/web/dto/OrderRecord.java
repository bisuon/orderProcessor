package algo.orderprocessor.web.dto;

import java.util.Objects;

/**
 * In-memory representation of an order's lifecycle state.
 * Used for status queries; later phases will persist this to a database.
 */
public record OrderRecord(
    String orderId,
    boolean premium,
    long creationTime,
    OrderStatus status,
    long submittedAt,
    Long processedAt) {

    public OrderRecord {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
    }

    public static OrderRecord newSubmitted(String orderId, boolean premium, long creationTime) {
        return new OrderRecord(orderId, premium, creationTime, OrderStatus.ACCEPTED, System.currentTimeMillis(), null);
    }

    public OrderRecord markProcessing() {
        return new OrderRecord(orderId, premium, creationTime, OrderStatus.PROCESSING, submittedAt, null);
    }

    public OrderRecord markProcessed() {
        return new OrderRecord(orderId, premium, creationTime, OrderStatus.PROCESSED, submittedAt, System.currentTimeMillis());
    }

    public OrderRecord markFailed() {
        return new OrderRecord(orderId, premium, creationTime, OrderStatus.FAILED, submittedAt, System.currentTimeMillis());
    }

    public enum OrderStatus {
        ACCEPTED,      // newly submitted, queued
        PROCESSING,    // worker picked it up
        PROCESSED,     // handler completed successfully
        FAILED         // handler threw exception
    }
}

