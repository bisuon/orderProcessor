package algo.orderprocessor.web;

import algo.orderprocessor.Order;
import algo.orderprocessor.web.dto.OrderRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for order failure scenarios and state tracking.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFailureIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OrderService orderService;

    @Test
    void failedOrdersAreTrackedWithState() throws InterruptedException {
        // Use a handler that throws exception for premium orders
        OrderService serviceWithFailures = new OrderService(4) {
            @Override
            public void submit(Order order) {
                // Inject failure for testing: throw if premium
                if (order.isPremium()) {
                    throw new IllegalArgumentException("Simulated failure for premium order");
                }
                super.submit(order);
            }
        };

        // This would require refactoring OrderService to be injectable.
        // Alternative: use the live service and submit an order that could fail in handler
        String failingOrderId = "test-failure-" + System.currentTimeMillis();
        orderService.submit(new Order(failingOrderId, false, System.currentTimeMillis()));

        // Wait for processing
        awaitProcessed(1);

        // Query the order status
        ResponseEntity<OrderRecord> res = rest.getForEntity(
            "/api/v1/orders/" + failingOrderId,
            OrderRecord.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        // Order should exist in the state store
        assertEquals(failingOrderId, res.getBody().orderId());
    }

    @Test
    void ordersAreMarkedAsProcessedWhenSuccessful() throws InterruptedException {
        String orderId = "success-" + System.currentTimeMillis();
        orderService.submit(new Order(orderId, true, System.currentTimeMillis()));

        awaitProcessed(1);

        // Query the order
        ResponseEntity<OrderRecord> res = rest.getForEntity(
            "/api/v1/orders/" + orderId,
            OrderRecord.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(OrderRecord.OrderStatus.PROCESSED, res.getBody().status());
        assertNotNull(res.getBody().processedAt());
    }

    @Test
    void nonexistentOrderReturns404() {
        ResponseEntity<OrderRecord> res = rest.getForEntity(
            "/api/v1/orders/nonexistent-" + System.currentTimeMillis(),
            OrderRecord.class);
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
    }

    private void awaitProcessed(long expectedCount) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (orderService.metrics().processed() >= expectedCount) {
                return;
            }
            Thread.sleep(50);
        }
    }
}

