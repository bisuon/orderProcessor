package algo.orderprocessor.web;

import algo.orderprocessor.Order;
import algo.orderprocessor.web.dto.MetricsResponse;
import algo.orderprocessor.web.dto.OrderRecord;
import algo.orderprocessor.web.dto.OrderRequest;
import algo.orderprocessor.web.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * REST API for submitting orders and observing processing metrics.
 *
 * <p>All endpoints are versioned under {@code /api/v1}.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Orders", description = "Order submission, status tracking, and metrics")
public class OrderController {

    private final OrderService orderService;
    private final IdempotencyStore idempotencyStore;

    public OrderController(OrderService orderService, IdempotencyStore idempotencyStore) {
        this.orderService = orderService;
        this.idempotencyStore = idempotencyStore;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "Submit a new order",
        description = "Submits an order for asynchronous, priority-ordered processing. Premium orders are prioritized over standard orders. " +
            "Use the optional Idempotency-Key header to make requests idempotent.",
        responses = {
            @ApiResponse(responseCode = "202", description = "Order accepted and queued for processing"),
            @ApiResponse(responseCode = "400", description = "Invalid request (missing orderId, validation error)"),
            @ApiResponse(responseCode = "409", description = "Idempotency key reuse with different payload")
        }
    )
    public OrderResponse submit(
        @Valid @RequestBody OrderRequest request,
        @RequestHeader(name = "Idempotency-Key", required = false)
        @Parameter(description = "Unique key for idempotent submissions.", example = "uuid-12345")
        String idempotencyKey) {

        long creationTime = request.creationTime() != null
            ? request.creationTime()
            : System.currentTimeMillis();
        String requestSignature = request.orderId() + "|" + request.premium() + "|" + request.creationTime();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotencyStore.ClaimResult claim = idempotencyStore.claim(idempotencyKey, requestSignature);
            if (claim.status() == IdempotencyStore.LookupStatus.HIT || claim.status() == IdempotencyStore.LookupStatus.PENDING) {
                return awaitIdempotentResponse(claim.future());
            }
            if (claim.status() == IdempotencyStore.LookupStatus.CONFLICT) {
                throw new IllegalStateException("Idempotency-Key has already been used with a different request payload");
            }
        }

        try {
            orderService.submit(new Order(request.orderId(), request.premium(), creationTime));
            OrderResponse response = new OrderResponse(true, request.orderId(), request.premium(), orderService.getSubmittedCount());
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyStore.complete(idempotencyKey, requestSignature, response);
            }
            return response;
        } catch (RuntimeException ex) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyStore.fail(idempotencyKey, requestSignature, ex);
            }
            throw ex;
        }
    }

    private OrderResponse awaitIdempotentResponse(java.util.concurrent.CompletableFuture<OrderResponse> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause == null ? ex : cause);
        }
    }

    @GetMapping("/orders/{orderId}")
    @Operation(
        summary = "Get order status",
        description = "Retrieve the current status and details of a specific order.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "Order not found")
        }
    )
    public ResponseEntity<OrderRecord> getOrder(
        @PathVariable
        @Parameter(description = "Order ID", example = "order-1001")
        String orderId) {
        return orderService.getOrder(orderId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/orders")
    @Operation(
        summary = "List recent orders",
        description = "Retrieve recently submitted orders, sorted by submission time (most recent first).",
        responses = {
            @ApiResponse(responseCode = "200", description = "List of orders"),
            @ApiResponse(responseCode = "400", description = "Invalid limit parameter")
        }
    )
    public List<OrderRecord> getRecentOrders(
        @RequestParam(defaultValue = "20")
        @Parameter(description = "Max number of orders to return", example = "20")
        @Min(value = 1, message = "limit must be >= 1")
        @Max(value = 100, message = "limit must be <= 100")
        int limit) {
        return orderService.getRecentOrders(limit);
    }

    @GetMapping("/metrics")
    @Operation(
        summary = "Get processing metrics",
        description = "Retrieve real-time metrics: submitted count, processed count, failed count, and pending count.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Metrics snapshot")
        }
    )
    public MetricsResponse metrics() {
        return orderService.metrics();
    }
}
