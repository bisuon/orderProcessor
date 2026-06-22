package algo.orderprocessor.web;

import algo.orderprocessor.web.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for idempotency (Idempotency-Key header).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void setUp() {
        idempotencyStore.clear();
    }

    @Test
    void idempotencyKeyPreventsDuplicateSubmissions() {
        String idempotencyKey = "idem-" + System.currentTimeMillis();
        String orderId = "order-" + System.currentTimeMillis();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);

        String body = "{\"orderId\":\"" + orderId + "\",\"premium\":true}";
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        // First submission
        ResponseEntity<OrderResponse> res1 = rest.postForEntity("/api/v1/orders", entity, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res1.getStatusCode());
        assertNotNull(res1.getBody());
        long firstSubmittedCount = res1.getBody().submittedCount();

        // Second submission with same idempotency key (should return same response without incrementing)
        ResponseEntity<OrderResponse> res2 = rest.postForEntity("/api/v1/orders", entity, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res2.getStatusCode());
        assertNotNull(res2.getBody());

        // Should return cached response with same submittedCount
        assertEquals(firstSubmittedCount, res2.getBody().submittedCount(),
            "Idempotent request should return same response without creating new order");
        assertEquals(res1.getBody().orderId(), res2.getBody().orderId());
    }

    @Test
    void differentIdempotencyKeysCreateSeparateOrders() {
        String base = "order-" + System.nanoTime();

        HttpHeaders headers1 = new HttpHeaders();
        headers1.setContentType(MediaType.APPLICATION_JSON);
        headers1.set("Idempotency-Key", "key1-" + base);
        HttpEntity<String> entity1 = new HttpEntity<>("{\"orderId\":\"" + base + "-1\",\"premium\":true}", headers1);

        ResponseEntity<OrderResponse> res1 = rest.postForEntity("/api/v1/orders", entity1, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res1.getStatusCode());
        assertNotNull(res1.getBody());

        HttpHeaders headers2 = new HttpHeaders();
        headers2.setContentType(MediaType.APPLICATION_JSON);
        headers2.set("Idempotency-Key", "key2-" + base);
        HttpEntity<String> entity2 = new HttpEntity<>("{\"orderId\":\"" + base + "-2\",\"premium\":false}", headers2);

        ResponseEntity<OrderResponse> res2 = rest.postForEntity("/api/v1/orders", entity2, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res2.getStatusCode());
        assertNotNull(res2.getBody());

        // Different idempotency keys with different orderIds should produce different accepted orders.
        assertEquals(base + "-1", res1.getBody().orderId());
        assertEquals(base + "-2", res2.getBody().orderId());
        assertTrue(res2.getBody().submittedCount() >= res1.getBody().submittedCount() + 1,
            "Second accepted order should advance submitted count");
    }

    @Test
    void noIdempotencyKeyAlwaysCreatesNewOrder() {
        String base = "order-" + System.nanoTime();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<OrderResponse> res1 = rest.postForEntity(
            "/api/v1/orders",
            new HttpEntity<>("{\"orderId\":\"" + base + "-1\",\"premium\":true}", headers),
            OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res1.getStatusCode());
        assertNotNull(res1.getBody());

        ResponseEntity<OrderResponse> res2 = rest.postForEntity(
            "/api/v1/orders",
            new HttpEntity<>("{\"orderId\":\"" + base + "-2\",\"premium\":true}", headers),
            OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res2.getStatusCode());
        assertNotNull(res2.getBody());

        assertEquals(base + "-1", res1.getBody().orderId());
        assertEquals(base + "-2", res2.getBody().orderId());
        assertTrue(res2.getBody().submittedCount() >= res1.getBody().submittedCount() + 1,
            "Requests without idempotency key should be treated as separate submissions");
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadReturns409() {
        String key = "idem-conflict-" + System.currentTimeMillis();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);

        ResponseEntity<OrderResponse> first = rest.postForEntity(
            "/api/v1/orders",
            new HttpEntity<>("{\"orderId\":\"o-a\",\"premium\":true}", headers),
            OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());

        ResponseEntity<String> second = rest.postForEntity(
            "/api/v1/orders",
            new HttpEntity<>("{\"orderId\":\"o-b\",\"premium\":false}", headers),
            String.class);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
    }

    @Test
    void blankIdempotencyKeyIsTreatedAsNotProvided() {
        String base = "blank-key-" + System.nanoTime();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "   ");

        ResponseEntity<OrderResponse> first = rest.postForEntity(
            "/api/v1/orders",
            new HttpEntity<>("{\"orderId\":\"" + base + "-1\",\"premium\":true}", headers),
            OrderResponse.class);
        ResponseEntity<OrderResponse> second = rest.postForEntity(
            "/api/v1/orders",
            new HttpEntity<>("{\"orderId\":\"" + base + "-2\",\"premium\":true}", headers),
            OrderResponse.class);

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode());
        assertTrue(second.getBody().submittedCount() >= first.getBody().submittedCount() + 1);
    }
}
