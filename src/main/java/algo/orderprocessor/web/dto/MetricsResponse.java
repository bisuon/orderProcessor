package algo.orderprocessor.web.dto;

/**
 * Live processing metrics returned by {@code GET /api/v1/metrics}.
 */
public record MetricsResponse(
    long submitted,
    long processed,
    long failed,
    long pending,
    long premiumProcessed,
    long standardProcessed,
    boolean running) {
}

