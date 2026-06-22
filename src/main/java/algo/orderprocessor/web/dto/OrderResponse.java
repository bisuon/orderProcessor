package algo.orderprocessor.web.dto;

/**
 * Response body for an accepted order ({@code 202 Accepted}).
 */
public record OrderResponse(
    boolean accepted,
    String orderId,
    boolean premium,
    long submittedCount) {
}

