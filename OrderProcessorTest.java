package algo.orderprocessor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrderProcessorTest {

    @Test
    void priorityOrdersAreProcessedBeforeStandardOrdersWhenQueuedTogether() {
        List<String> processedOrderIds = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch processedAll = new CountDownLatch(10);

        try (OrderProcessor processor = new OrderProcessor(2, order -> {
            processedOrderIds.add(order.orderId());
            processedAll.countDown();
        })) {
            for (int i = 0; i < 5; i++) {
                processor.submitOrder(new Order("standard-" + i, false, 1_000L + i));
            }
            for (int i = 0; i < 5; i++) {
                processor.submitOrder(new Order("premium-" + i, true, 2_000L + i));
            }

            processor.startProcessing();
            await(processedAll, 5, TimeUnit.SECONDS);
            processor.shutdown();
        }

        assertEquals(10, processedOrderIds.size());
        assertTrue(processedOrderIds.subList(0, 5).stream().allMatch(id -> id.startsWith("premium-")),
            "Premium orders should be processed first when the queue contains both priority tiers");
    }

    @Test
    void processesOneHundredThousandOrdersUnderConcurrentSubmission() throws Exception {
        AtomicInteger premiumProcessed = new AtomicInteger();
        AtomicInteger standardProcessed = new AtomicInteger();

        try (OrderProcessor processor = new OrderProcessor(8, order -> {
            if (order.isPremium()) {
                premiumProcessed.incrementAndGet();
            } else {
                standardProcessed.incrementAndGet();
            }
        })) {
            processor.startProcessing();

            int totalOrders = 100_000;
            int producers = 8;
            int perProducer = totalOrders / producers;
            ExecutorService submissionPool = Executors.newFixedThreadPool(producers);
            CountDownLatch submitted = new CountDownLatch(producers);

            for (int p = 0; p < producers; p++) {
                final int producerIndex = p;
                submissionPool.submit(() -> {
                    long baseTime = 10_000L * producerIndex;
                    for (int i = 0; i < perProducer; i++) {
                        boolean premium = ((producerIndex + i) % 4) == 0;
                        long creationTime = baseTime + i;
                        processor.submitOrder(new Order("p" + producerIndex + "-" + i, premium, creationTime));
                    }
                    submitted.countDown();
                });
            }

            await(submitted, 10, TimeUnit.SECONDS);
            submissionPool.shutdown();
            assertTrue(submissionPool.awaitTermination(10, TimeUnit.SECONDS), "submission pool should stop cleanly");

            processor.shutdown();
            assertEquals(totalOrders, processor.getSubmittedCount());
            assertEquals(totalOrders, processor.getProcessedCount());
            assertEquals(0, processor.getFailedCount());
            assertEquals(totalOrders, premiumProcessed.get() + standardProcessed.get());
        }
    }

    @Test
    void shutdownRejectsNewOrdersAndCanBeCalledBeforeStart() {
        OrderProcessor processor = new OrderProcessor(2, order -> { /* no-op */ });
        assertDoesNotThrow(processor::shutdown);
        assertThrows(IllegalStateException.class, () -> processor.submitOrder(new Order("order-1", true, 1L)));
        assertThrows(IllegalStateException.class, processor::startProcessing);
    }

    @Test
    void startProcessingIsIdempotentAndDrainsQueue() {
        AtomicInteger processed = new AtomicInteger();
        try (OrderProcessor processor = new OrderProcessor(3, order -> processed.incrementAndGet())) {
            processor.submitOrder(new Order("standard-1", false, 10L));
            processor.submitOrder(new Order("premium-1", true, 5L));
            processor.submitOrder(new Order("standard-2", false, 20L));

            processor.startProcessing();
            processor.startProcessing();
            processor.shutdown();
        }

        assertEquals(3, processed.get());
    }

    @Test
    void awaitIdleCompletesForSmallBatch() {
        AtomicInteger processed = new AtomicInteger();
        try (OrderProcessor processor = new OrderProcessor(2, order -> processed.incrementAndGet())) {
            processor.startProcessing();
            processor.submitOrder(new Order("premium-1", true, 1L));
            processor.submitOrder(new Order("standard-1", false, 2L));
            processor.awaitIdle(Duration.ofSeconds(2));
            processor.shutdown();
        }

        assertEquals(2, processed.get());
    }

    private static void await(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            assertTrue(latch.await(timeout, unit), "timed out waiting for latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("latch wait interrupted");
        }
    }
}

