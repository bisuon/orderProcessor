package algo.orderprocessor;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small demo runner for the concurrent order processor.
 */
public final class OrderProcessorDemo {

    private OrderProcessorDemo() {
    }

    public static void main(String[] args) {
        AtomicLong premiumCount = new AtomicLong();
        AtomicLong standardCount = new AtomicLong();

        try (OrderProcessor processor = new OrderProcessor(8, order -> {
            if (order.isPremium()) {
                premiumCount.incrementAndGet();
            } else {
                standardCount.incrementAndGet();
            }
        })) {
            processor.startProcessing();

            long baseTime = System.currentTimeMillis();
            for (int i = 0; i < 100_000; i++) {
                boolean premium = (i % 5) == 0;
                long creationTime = baseTime + ThreadLocalRandom.current().nextInt(10_000);
                processor.submitOrder(new Order("order-" + i, premium, creationTime));
            }

            processor.shutdown();
            processor.awaitIdle(Duration.ofSeconds(5));

            System.out.println("submitted=" + processor.getSubmittedCount());
            System.out.println("processed=" + processor.getProcessedCount());
            System.out.println("failed=" + processor.getFailedCount());
            System.out.println("premiumProcessed=" + premiumCount.get());
            System.out.println("standardProcessed=" + standardCount.get());
        }
    }
}

