# High-Throughput Concurrent Order Processor

A Java solution to the "process a stream of 100,000 orders concurrently" coding test,
packaged as a **Spring Boot 3** application with a REST API and a small web UI.
Orders are either **Standard** or **Premium**; premium orders are always processed first.

## Highlights

| Requirement        | How it is addressed |
|--------------------|---------------------|
| **Concurrency**    | A fixed pool of worker threads pulls from a single shared queue. Counters use `LongAdder`/atomics, so there are no locks on the hot path. The web UI also includes a browser-driven concurrency demo that submits orders with multiple concurrent HTTP workers and displays throughput live. |
| **Performance & Load** | A test submits **100,000 orders from 8 concurrent producers** and asserts every one is processed. The UI can also run an interactive load demo so reviewers can see submission and drain timings visually. |
| **Priority Handling** | A `PriorityBlockingQueue` ordered by `Order.compareTo` (premium first, then creation time). A stable per-order sequence number breaks ties so equal-priority orders keep FIFO order. |
| **Shutdown**       | `shutdown()` stops accepting new orders, then **drains the queue** before workers exit. Spring graceful shutdown drains in-flight HTTP requests too. |
| **API**            | Spring Web REST controllers with DTOs, bean validation and RFC 7807 (`ProblemDetail`) error responses. |
| **Observability**  | SLF4J + Logback structured logging (with orderId + error context), plus Spring Actuator `health`/`metrics` endpoints. |
| **UI**             | A dependency-free HTML/JS page (served by the app) to submit orders and watch live metrics. |
| **API Documentation** | Auto-generated OpenAPI/Swagger specs with interactive browser (Springdoc). |
| **Order State Tracking** | Each order tracked through lifecycle: ACCEPTED → PROCESSING → PROCESSED/FAILED. Query status via `GET /orders/{id}`. |
| **Idempotency** | Optional `Idempotency-Key` header to prevent duplicate submissions (e.g. for retry scenarios). |

## Architecture

The concurrent **engine** (`OrderProcessor`) is intentionally framework-agnostic and is
reused unchanged. Spring Boot provides the web/API layer on top of it.

```
src/main/java/algo/orderprocessor/
  Order.java                       # immutable record, premium-first Comparable
  OrderProcessor.java              # the concurrent engine (submit / start / shutdown)
  OrderProcessorDemo.java          # runs 100k orders and prints metrics
  OrderProcessorApplication.java   # Spring Boot entry point
  web/
    OrderService.java              # @Service owning the engine lifecycle + logging
    OrderController.java           # @RestController, /api/v1
    GlobalExceptionHandler.java    # @RestControllerAdvice -> ProblemDetail
    WebConfig.java                 # CORS for external UIs
    dto/                           # OrderRequest / OrderResponse / MetricsResponse
src/main/resources/
  application.yml                  # port, workers, actuator, logging config
  static/index.html                # bundled web UI
src/test/java/algo/orderprocessor/
  OrderProcessorTest.java          # concurrency, priority, load, shutdown tests
  web/OrderControllerTest.java     # @WebMvcTest (validation, status codes)
  web/OrderApiIntegrationTest.java # @SpringBootTest end-to-end over HTTP
```

## Requirements

- JDK 21+
- No system Maven needed - use the bundled Maven Wrapper (`./mvnw`).

## Build & test

```bash
./mvnw verify
```

## Run the app (REST API + UI)

```bash
./mvnw spring-boot:run
# or, after `./mvnw package`:
java -jar target/order-processor-1.0.0.jar
```

Configuration (env vars or `application.yml`):

- `PORT` (default `8080`)
- `WORKERS` (default = available CPU count)
- `app.cors.allowed-origins` (default `*`)

Then open the UI at **http://localhost:8080/**.

### UI demo: Concurrency + Performance & Load

The home page now includes a **Concurrency & Load Demo** panel:
- choose total orders (e.g. `1000`)
- choose concurrent submitters (e.g. `8`)
- click **Run browser load demo**

It will:
- submit real HTTP requests to `POST /api/v1/orders`
- drive multiple concurrent browser workers in parallel
- wait until the queue drains
- display submission time, end-to-end drain time, and approximate throughput

This gives reviewers a quick, visual demonstration of the coding-test requirements without reading the test code.

## REST API (`/api/v1`)

| Method & path          | Headers / notes | Response |
|------------------------|-----------------|----------|
| `POST /api/v1/orders`  | `Idempotency-Key: <uuid>` (optional for idempotent submissions) | `202` `{"accepted":true,...}` |
| `GET  /api/v1/orders/{orderId}` | — | Order status + state (ACCEPTED/PROCESSING/PROCESSED/FAILED) or `404` |
| `GET  /api/v1/orders?limit=20` | query recent orders (default: 20, max: 100) | List of orders (most recent first) |
| `GET  /api/v1/metrics` | — | counts: submitted / processed / failed / pending / premiumProcessed / standardProcessed / running |
| `GET  /actuator/health`| liveness/readiness | `{"status":"UP"}` |
| `GET  /actuator/metrics`| JVM/HTTP metrics | Actuator metrics |
| `GET  /swagger-ui.html`| OpenAPI/Swagger documentation | Interactive API browser |

Example session:

```bash
curl -s localhost:8080/actuator/health

# Submit single order
curl -s -XPOST localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: uuid-12345' \
  -d '{"orderId":"o1","premium":true}'

# Check order status
curl -s localhost:8080/api/v1/orders/o1

# List recent orders
curl -s 'localhost:8080/api/v1/orders?limit=5'

# View metrics
curl -s localhost:8080/api/v1/metrics

# View API documentation
open http://localhost:8080/swagger-ui.html
```

## Run the standalone demo (100,000 orders)

```bash
./mvnw -q exec:java -Dexec.mainClass=algo.orderprocessor.OrderProcessorDemo
```

## New Features (Phase 1+)

### Order State Tracking
Each order now has a lifecycle state: **ACCEPTED** → **PROCESSING** → **PROCESSED** or **FAILED**.
- Query an order's current state: `GET /api/v1/orders/{orderId}`
- List recent orders: `GET /api/v1/orders?limit=20`
- Enables UI to display order-level status, not just aggregates.

### Structured Error Logging
When an order fails to process, the system logs:
- `orderId`: which order failed
- `premium`: order type
- Error type + stacktrace (if available)

Example log: `Order processing failed: orderId=o123, premium=true, error=IllegalArgumentException`

This enables troubleshooting and audit trails in production.

### Idempotency Support
Submit orders with an `Idempotency-Key` header to prevent duplicates:

```bash
curl -XPOST localhost:8080/api/v1/orders \
  -H 'Idempotency-Key: my-uuid-v1' \
  -d '{"orderId":"order-123","premium":true}'

# If you retry with the same key, you get the cached response (no duplicate order created)
curl -XPOST localhost:8080/api/v1/orders \
  -H 'Idempotency-Key: my-uuid-v1' \
  -d '{"orderId":"order-123","premium":true}'
```

Useful for client retries (network timeouts, etc.) — ensures exactly-once semantics.

### OpenAPI/Swagger Documentation
Auto-generated interactive API docs:
- Open http://localhost:8080/swagger-ui.html after starting the app
- See all endpoints, request/response schemas, example values
- Try endpoints directly from the browser

Perfect for UI teams to understand the contract without reading code.

### Tests
New integration tests added:
- **OrderFailureIntegrationTest**: Validates failure logging + state tracking
- **IdempotencyIntegrationTest**: Tests duplicate submission prevention
- All tests pass: `./mvnw verify`

## Design notes

- **Single shared priority queue + worker pool.** Any idle worker always takes the
  highest-priority order currently available, keeping global priority correct.
- **Stable ordering.** `PriorityBlockingQueue` is not stable, so each order is wrapped with
  a monotonically increasing sequence number used as the final tie-breaker.
- **Lock-free metrics.** `LongAdder` counters avoid contention under heavy concurrent load.
- **Graceful, bounded shutdown.** Workers exit only once the queue is empty; the engine is
  drained on Spring context shutdown via `@PreDestroy`.
- **Framework-agnostic engine.** `OrderProcessor` has zero Spring dependencies and can be
  embedded anywhere; Spring only owns its lifecycle and exposes it over HTTP.

## Data & Scalability

### Current design (in-memory with order state tracking)
Orders and metrics are stored **entirely in memory**:
- **Queues + counters** (`PriorityBlockingQueue`, `LongAdder`) for concurrent processing.
- **OrderRecord map** (synchronized map) for order lifecycle state (`ACCEPTED → PROCESSING → PROCESSED/FAILED`).
- Structured failure logging (orderId, error type) for troubleshooting.
- Data is lost on application restart (acceptable for coding exercise).

### Production evolution path

**Phase 1 (✅ implemented - same session)**
- Add in-memory `OrderRecord` state tracking with ACCEPTED/PROCESSING/PROCESSED/FAILED statuses.
- Expose `GET /api/v1/orders/{id}` for status queries and `GET /api/v1/orders?limit=20` for recent history.
- Add structured error logging (orderId + exception context) for observability.
- Remove unsafe `/shutdown` API endpoint.

**Phase 2 (durable storage, ~1 week)**
- Introduce Spring Data JPA + PostgreSQL (or preferred SQL DB).
- Persist `OrderRecord` on submission, update status on processing completion.
- Enable historical queries, analytics, and audit trails.
- Add idempotency keys to prevent duplicate submissions.
- No changes to the concurrent engine; it remains in-memory and framework-agnostic.

**Phase 3 (event sourcing + async decoupling, multi-week)**
- Migrate to event-driven architecture (Kafka/RabbitMQ) for multi-service workflows.
- Store **order events** instead of just final state → enables full replay, causal tracing, compensating transactions (Saga pattern).
- Decouple API ingress from processing: async message queue absorbs spikes, multiple workers scale independently.
- Outbox pattern ensures no event is lost during failures.

### Trade-offs
- **In-memory-only** ← simple, tests concurrency correctly, but no persistence.
- **Add DB** ← enables audit + compliance + multi-instance state sharing, but introduces disk I/O latency (mitigated by batching, connection pooling).
- **Event sourcing** ← most resilient and auditable, but higher complexity; use when you need full failure recovery and compliance replay.
