# High-Throughput Concurrent Order Processor

A Java solution to the "process a stream of 100,000 orders concurrently" coding test.
Orders are either **Standard** or **Premium**; premium orders are always processed first.
The project ships with a programmatic API, a small REST API, a JUnit 5 test suite, a
runnable demo, and CI.

## Highlights

| Requirement        | How it is addressed |
|--------------------|---------------------|
| **Concurrency**    | A fixed pool of worker threads pulls from a single shared queue. All counters use `LongAdder`/atomics, so there are no locks on the hot path. |
| **Performance & Load** | A test submits **100,000 orders from 8 concurrent producers** and asserts every one is processed. The demo runs the same volume end-to-end. |
| **Priority Handling** | A `PriorityBlockingQueue` ordered by `Order.compareTo` (premium first, then creation time). A stable per-order sequence number breaks ties so equal-priority orders keep FIFO order. |
| **Shutdown**       | `shutdown()` stops accepting new orders, then **drains the queue** before workers exit. It is safe to call before `startProcessing()` and is idempotent. `awaitIdle(...)` waits for the backlog to clear. |

## Project layout

```
src/main/java/algo/orderprocessor/
  Order.java                 # immutable record, premium-first Comparable
  OrderProcessor.java        # the concurrent engine (submit / start / shutdown)
  OrderProcessorDemo.java    # runs 100k orders and prints metrics
  api/OrderApiServer.java    # REST API over the processor (JDK HttpServer, no deps)
  api/ApiMain.java           # REST API entry point
  api/Json.java              # tiny dependency-free JSON helper
src/test/java/algo/orderprocessor/
  OrderProcessorTest.java    # concurrency, priority, load, shutdown tests
  api/OrderApiServerTest.java
  api/JsonTest.java
```

## Requirements

- JDK 21+
- No system Maven needed — use the bundled Maven Wrapper (`./mvnw`).

## Build & test

```bash
./mvnw verify
```

## Core programmatic API

```java
try (OrderProcessor processor = new OrderProcessor(8, order -> handle(order))) {
    processor.startProcessing();
    processor.submitOrder(new Order("o1", true, System.currentTimeMillis()));
    processor.shutdown();           // drains the queue, then stops workers
}
```

| Method | Description |
|--------|-------------|
| `submitOrder(Order)` | Enqueue an order. Throws `IllegalStateException` once shutting down. |
| `startProcessing()`  | Start the worker pool. Idempotent. |
| `shutdown()`         | Stop intake, drain the queue, stop workers gracefully. |
| `awaitIdle(Duration)`| Block until the backlog is fully processed. |
| `getSubmittedCount()` / `getProcessedCount()` / `getFailedCount()` / `getPendingCount()` | Live metrics. |

## Run the demo (100,000 orders)

```bash
./mvnw -q exec:java -Dexec.mainClass=algo.orderprocessor.OrderProcessorDemo
```

## REST API

Start the server (default port `8080`, `WORKERS` defaults to CPU count):

```bash
./mvnw -q exec:java                 # uses algo.orderprocessor.api.ApiMain
# or, after `./mvnw package`:
PORT=8080 WORKERS=8 java -jar target/order-processor-1.0.0.jar
```

| Method & path     | Body / notes | Response |
|-------------------|--------------|----------|
| `GET /health`     | liveness probe | `{"status":"UP","running":true}` |
| `POST /orders`    | `{"orderId":"o1","premium":true,"creationTime":123}` (`creationTime` optional) | `202` `{"accepted":true,...}` |
| `GET /metrics`    | — | `{"submitted":..,"processed":..,"failed":..,"pending":..}` |
| `POST /shutdown`  | drains and stops | `200` `{"status":"shutdown complete",...}` |

Example session:

```bash
curl -s localhost:8080/health
curl -s -XPOST localhost:8080/orders -d '{"orderId":"o1","premium":true}'
curl -s localhost:8080/metrics
curl -s -XPOST localhost:8080/shutdown
```

## Design notes

- **Single shared priority queue + worker pool.** This keeps global priority correct:
  any idle worker always takes the highest-priority order currently available, instead of
  priority being limited to a single thread's local queue.
- **Stable ordering.** `PriorityBlockingQueue` is not stable, so each order is wrapped with
  a monotonically increasing sequence number used as the final tie-breaker.
- **Lock-free metrics.** `LongAdder` counters avoid contention under heavy concurrent load.
- **Graceful, bounded shutdown.** Workers exit only once the queue is empty; termination is
  awaited with a timeout so shutdown can never hang indefinitely.
- **No heavy dependencies.** The REST layer uses the JDK's built-in `HttpServer`; the only
  dependency is JUnit 5 (test scope).

