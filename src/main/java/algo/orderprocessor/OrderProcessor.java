package algo.orderprocessor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * High-throughput concurrent order processor.
 *
 * Features:
 * - concurrent worker pool
 * - premium-first priority scheduling
 * - graceful shutdown that drains queued orders
 * - lightweight metrics for tests and monitoring
 */
public final class OrderProcessor implements AutoCloseable {
    private static final long POLL_TIMEOUT_MILLIS = 100L;

    private final int workerCount;
    private final Consumer<Order> orderHandler;
    private final BlockingQueue<QueuedOrder> queue = new PriorityBlockingQueue<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private final LongAdder submittedCount = new LongAdder();
    private final LongAdder processedCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();

    private volatile ExecutorService executor;

    public OrderProcessor() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors()), order -> { /* default no-op */ });
    }

    public OrderProcessor(int workerCount, Consumer<Order> orderHandler) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        this.workerCount = workerCount;
        this.orderHandler = Objects.requireNonNull(orderHandler, "orderHandler");
    }

    public void submitOrder(Order order) {
        Objects.requireNonNull(order, "order");
        if (shuttingDown.get()) {
            throw new IllegalStateException("OrderProcessor is shutting down; no new orders are accepted");
        }

        submittedCount.increment();
        queue.offer(new QueuedOrder(order, sequence.getAndIncrement()));
    }

    public void startProcessing() {
        if (shuttingDown.get()) {
            throw new IllegalStateException("Cannot start processing after shutdown");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "order-processor-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor = pool;

        for (int i = 0; i < workerCount; i++) {
            pool.execute(this::runWorker);
        }
    }

    public void shutdown() {
        shuttingDown.set(true);
        ExecutorService pool = executor;
        if (pool == null) {
            return;
        }

        pool.shutdown();
        awaitTermination(pool, Duration.ofSeconds(30));
    }

    public long getSubmittedCount() {
        return submittedCount.sum();
    }

    public long getProcessedCount() {
        return processedCount.sum();
    }

    public long getFailedCount() {
        return failedCount.sum();
    }

    public long getPendingCount() {
        return Math.max(0, getSubmittedCount() - getProcessedCount() - getFailedCount());
    }

    public boolean isRunning() {
        return started.get() && !shuttingDown.get();
    }

    public void awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && getPendingCount() == 0) {
                return;
            }
            sleepQuietly(10L);
        }
        throw new IllegalStateException("Timed out waiting for the queue to drain");
    }

    @Override
    public void close() {
        shutdown();
    }

    private void runWorker() {
        while (true) {
            if (shuttingDown.get() && queue.isEmpty()) {
                return;
            }

            try {
                QueuedOrder queuedOrder = queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (queuedOrder == null) {
                    continue;
                }
                process(queuedOrder.order());
            } catch (InterruptedException interrupted) {
                if (shuttingDown.get() && queue.isEmpty()) {
                    return;
                }
            }
        }
    }

    private void process(Order order) {
        try {
            orderHandler.accept(order);
            processedCount.increment();
        } catch (RuntimeException ex) {
            failedCount.increment();
        }
    }

    private void awaitTermination(ExecutorService pool, Duration timeout) {
        long nanosLeft = timeout.toNanos();
        long deadline = System.nanoTime() + nanosLeft;
        boolean terminated = false;
        while (!terminated && nanosLeft > 0) {
            try {
                terminated = pool.awaitTermination(Math.min(TimeUnit.MILLISECONDS.toNanos(250), nanosLeft), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            nanosLeft = deadline - System.nanoTime();
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private record QueuedOrder(Order order, long sequence) implements Comparable<QueuedOrder> {
        @Override
        public int compareTo(QueuedOrder other) {
            int byOrderPriority = this.order.compareTo(other.order);
            if (byOrderPriority != 0) {
                return byOrderPriority;
            }
            return Long.compare(this.sequence, other.sequence);
        }
    }
}

