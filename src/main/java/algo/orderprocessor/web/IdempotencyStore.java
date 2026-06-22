package algo.orderprocessor.web;

import algo.orderprocessor.web.dto.OrderResponse;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory idempotency key store.
 *
 * Same key + same request signature -> cached/awaitable response.
 * Same key + different request signature -> conflict.
 */
@Component
public class IdempotencyStore {

    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    public enum LookupStatus {
        MISS,
        HIT,
        PENDING,
        CONFLICT
    }

    public record LookupResult(LookupStatus status, OrderResponse response) {
        public static LookupResult miss() { return new LookupResult(LookupStatus.MISS, null); }
        public static LookupResult hit(OrderResponse response) { return new LookupResult(LookupStatus.HIT, response); }
        public static LookupResult pending() { return new LookupResult(LookupStatus.PENDING, null); }
        public static LookupResult conflict() { return new LookupResult(LookupStatus.CONFLICT, null); }
    }

    public record ClaimResult(LookupStatus status, CompletableFuture<OrderResponse> future) {
        public static ClaimResult newClaim(CompletableFuture<OrderResponse> future) { return new ClaimResult(LookupStatus.MISS, future); }
        public static ClaimResult existing(CompletableFuture<OrderResponse> future) { return new ClaimResult(LookupStatus.PENDING, future); }
        public static ClaimResult conflict() { return new ClaimResult(LookupStatus.CONFLICT, null); }
        public static ClaimResult hit(OrderResponse response) {
            CompletableFuture<OrderResponse> done = new CompletableFuture<>();
            done.complete(response);
            return new ClaimResult(LookupStatus.HIT, done);
        }
    }

    private record Entry(String requestSignature, CompletableFuture<OrderResponse> future) {
    }

    /**
     * Backward-compatible lookup used by tests; not atomic by itself.
     */
    public LookupResult lookup(String idempotencyKey, String requestSignature) {
        Entry entry = cache.get(idempotencyKey);
        if (entry == null) {
            return LookupResult.miss();
        }
        if (!entry.requestSignature().equals(requestSignature)) {
            return LookupResult.conflict();
        }
        if (!entry.future().isDone()) {
            return LookupResult.pending();
        }
        try {
            return LookupResult.hit(entry.future().join());
        } catch (RuntimeException ex) {
            return LookupResult.conflict();
        }
    }

    /**
     * Atomically claim an idempotency key for the given request signature.
     * The first caller creates the future; concurrent identical requests join it.
     */
    public ClaimResult claim(String idempotencyKey, String requestSignature) {
        CompletableFuture<OrderResponse> future = new CompletableFuture<>();
        Entry created = new Entry(requestSignature, future);
        Entry existing = cache.putIfAbsent(idempotencyKey, created);
        if (existing == null) {
            return ClaimResult.newClaim(future);
        }
        if (!existing.requestSignature().equals(requestSignature)) {
            return ClaimResult.conflict();
        }
        return ClaimResult.existing(existing.future());
    }

    /**
     * Complete a request successfully and keep the response cached for future retries.
     */
    public void complete(String idempotencyKey, String requestSignature, OrderResponse response) {
        Entry entry = cache.get(idempotencyKey);
        if (entry != null && entry.requestSignature().equals(requestSignature)) {
            entry.future().complete(response);
        }
    }

    /**
     * Complete a request exceptionally. The failure remains cached for the same key/signature.
     */
    public void fail(String idempotencyKey, String requestSignature, Throwable error) {
        Entry entry = cache.get(idempotencyKey);
        if (entry != null && entry.requestSignature().equals(requestSignature)) {
            entry.future().completeExceptionally(error);
        }
    }

    /**
     * Clear the store (for testing).
     */
    public void clear() {
        cache.clear();
    }
}
