package algo.orderprocessor.web;

import algo.orderprocessor.web.dto.OrderResponse;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory idempotency key store.
 *
 * Same key + same request signature -> cached response.
 * Same key + different request signature -> conflict.
 */
@Component
public class IdempotencyStore {

    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    public enum LookupStatus {
        MISS,
        HIT,
        CONFLICT
    }

    public record LookupResult(LookupStatus status, OrderResponse response) {
        public static LookupResult miss() {
            return new LookupResult(LookupStatus.MISS, null);
        }

        public static LookupResult hit(OrderResponse response) {
            return new LookupResult(LookupStatus.HIT, response);
        }

        public static LookupResult conflict() {
            return new LookupResult(LookupStatus.CONFLICT, null);
        }
    }

    private record Entry(String requestSignature, OrderResponse response) {
    }

    /**
     * Lookup an idempotency key and compare request signature.
     */
    public LookupResult lookup(String idempotencyKey, String requestSignature) {
        Entry entry = cache.get(idempotencyKey);
        if (entry == null) {
            return LookupResult.miss();
        }
        if (!entry.requestSignature().equals(requestSignature)) {
            return LookupResult.conflict();
        }
        return LookupResult.hit(entry.response());
    }

    /**
     * Record a successful submission for a key/signature pair.
     */
    public void recordResponse(String idempotencyKey, String requestSignature, OrderResponse response) {
        cache.putIfAbsent(idempotencyKey, new Entry(requestSignature, response));
    }

    /**
     * Clear the store (for testing).
     */
    public void clear() {
        cache.clear();
    }
}
