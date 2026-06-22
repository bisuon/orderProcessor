package algo.orderprocessor;

import jakarta.annotation.Nonnull;

/**
 * Immutable order model used by the high-throughput concurrent processor.
 * Premium orders are prioritized ahead of standard orders; creation time
 * is used as the secondary ordering key.
 */
public record Order(String orderId, boolean isPremium, long creationTime) implements Comparable<Order> {

    @Override
    public int compareTo(@Nonnull Order other) {
        if (this.isPremium && !other.isPremium) return -1;
        if (!this.isPremium && other.isPremium) return 1;
        return Long.compare(this.creationTime, other.creationTime);
    }
}

