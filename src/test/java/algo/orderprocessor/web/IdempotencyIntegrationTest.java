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
        String orderId = "order-" + System.currentTimeMillis();

        // First request with key1
        HttpHeaders headers1 = new HttpHeaders();
        headers1.setContentType(MediaType.APPLICATION_JSON);
        headers1.set("Idempotency-Key", "key1");
        HttpEntity<String> entity1 = new HttpEntity<>("{\"orderId\":\"" + orderId + "-1\",\"premium\":true}", headers1);

        ResponseEntity<OrderResponse> res1 = rest.postForEntity("/api/v1/orders", entity1, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res1.getStatusCode());
        long count1 = res1.getBody().submittedCount();

        // Second request with key2 (different key)
        HttpHeaders headers2 = new HttpHeaders();
        headers2.setContentType(MediaType.APPLICATION_JSON);
        headers2.set("Idempotency-Key", "key2");
        HttpEntity<String> entity2 = new HttpEntity<>("{\"orderId\":\"" + orderId + "-2\",\"premium\":false}", headers2);

        ResponseEntity<OrderResponse> res2 = rest.postForEntity("/api/v1/orders", entity2, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res2.getStatusCode());
        long count2 = res2.getBody().submittedCount();

        // Different keys should result in different submitted counts
        assertEquals(count1 + 1, count2,
            "Different idempotency keys should create separate orders");
    }

    @Test
    void noIdempotencyKeyAlwaysCreatesNewOrder() {
        String orderId = "order-" + System.currentTimeMillis();
        String body = "{\"orderId\":\"" + orderId + "\",\"premium\":true}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        // First submission without key
        ResponseEntity<OrderResponse> res1 = rest.postForEntity("/api/v1/orders", entity, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res1.getStatusCode());
        long count1 = res1.getBody().submittedCount();

        // Second submission (same body, no key)
        ResponseEntity<OrderResponse> res2 = rest.postForEntity("/api/v1/orders", entity, OrderResponse.class);
        assertEquals(HttpStatus.ACCEPTED, res2.getStatusCode());
        long count2 = res2.getBody().submittedCount();

        // Without idempotency key, both are treated as new orders
        assertEquals(count1 + 1, count2,
            "Submissions without Idempotency-Key should always create new orders");
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
        String orderId = "blank-key-" + System.currentTimeMillis();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "   ");

        HttpEntity<String> entity = new HttpEntity<>("{\"orderId\":\"" + orderId + "\",\"premium\":true}", headers);

        ResponseEntity<OrderResponse> first = rest.postForEntity("/api/v1/orders", entity, OrderResponse.class);
        ResponseEntity<OrderResponse> second = rest.postForEntity("/api/v1/orders", entity, OrderResponse.class);

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode());
        assertEquals(first.getBody().submittedCount() + 1, second.getBody().submittedCount());
    }
}
