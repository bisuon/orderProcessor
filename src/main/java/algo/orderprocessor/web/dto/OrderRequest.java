package algo.orderprocessor.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/orders}.
 *
 * @param orderId      unique order identifier (required)
 * @param premium      whether the order is premium (defaults to false when absent)
 * @param creationTime optional client-supplied creation timestamp (epoch millis);
 *                     when absent the server's current time is used
 */
public record OrderRequest(
    @NotBlank(message = "orderId is required") String orderId,
    boolean premium,
    Long creationTime) {
}

