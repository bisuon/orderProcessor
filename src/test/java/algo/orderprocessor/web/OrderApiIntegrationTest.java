package algo.orderprocessor.web;

import algo.orderprocessor.web.dto.MetricsResponse;
import algo.orderprocessor.web.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void setUp() {
        idempotencyStore.clear();
    }

    @Test
    void submittedOrdersAreProcessedEndToEnd() {
        // Get baseline
        MetricsResponse baseline = rest.getForObject("/api/v1/metrics", MetricsResponse.class);
        long baselineSubmitted = baseline.submitted();
        long baselineProcessed = baseline.processed();

        // Submit 25 orders
        for (int i = 0; i < 25; i++) {
            String body = "{\"orderId\":\"o" + System.nanoTime() + "-" + i + "\",\"premium\":" + (i % 2 == 0) + "}";
            ResponseEntity<OrderResponse> res = rest.postForEntity("/api/v1/orders", json(body), OrderResponse.class);
            assertEquals(HttpStatus.ACCEPTED, res.getStatusCode());
            assertTrue(res.getBody().accepted());
        }

        // Wait for processing
        MetricsResponse metrics = awaitProcessed(baselineSubmitted + 25, baselineProcessed + 25);
        assertEquals(baselineSubmitted + 25, metrics.submitted());
        assertEquals(baselineProcessed + 25, metrics.processed());
        assertEquals(0, metrics.failed());
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getBody().contains("UP"));
    }

    private MetricsResponse awaitProcessed(long expectedSubmitted, long expectedProcessed) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        MetricsResponse metrics = rest.getForObject("/api/v1/metrics", MetricsResponse.class);
        while ((metrics.submitted() < expectedSubmitted || metrics.processed() < expectedProcessed) 
               && Instant.now().isBefore(deadline)) {
            sleep();
            metrics = rest.getForObject("/api/v1/metrics", MetricsResponse.class);
        }
        return metrics;
    }

    private static org.springframework.http.HttpEntity<String> json(String body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(body, headers);
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

