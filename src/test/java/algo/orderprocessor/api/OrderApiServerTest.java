package algo.orderprocessor.api;

import algo.orderprocessor.OrderProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderApiServerTest {

    private OrderApiServer server;
    private HttpClient client;
    private LongAdder premium;
    private LongAdder standard;

    @BeforeEach
    void setUp() throws IOException {
        premium = new LongAdder();
        standard = new LongAdder();
        OrderProcessor processor = new OrderProcessor(4, order -> {
            if (order.isPremium()) {
                premium.increment();
            } else {
                standard.increment();
            }
        });
        processor.startProcessing();
        server = new OrderApiServer(0, processor).start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
    }

    @Test
    void submitOrderIsAcceptedAndProcessed() throws Exception {
        HttpResponse<String> response = post("/orders", "{\"orderId\":\"premium-1\",\"premium\":true,\"creationTime\":1}");
        assertEquals(202, response.statusCode());
        assertTrue(response.body().contains("\"accepted\":true"));

        server.processor().awaitIdle(Duration.ofSeconds(5));
        assertEquals(1, premium.sum());
        assertEquals(0, standard.sum());
    }

    @Test
    void invalidOrderPayloadReturns400() throws Exception {
        HttpResponse<String> response = post("/orders", "{\"premium\":true}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("orderId is required"));
    }

    @Test
    void metricsReflectSubmittedAndProcessedCounts() throws Exception {
        for (int i = 0; i < 50; i++) {
            boolean isPremium = i % 2 == 0;
            post("/orders", "{\"orderId\":\"o" + i + "\",\"premium\":" + isPremium + ",\"creationTime\":" + i + "}");
        }

        server.processor().awaitIdle(Duration.ofSeconds(5));

        HttpResponse<String> metrics = get("/metrics");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("\"submitted\":50"));
        assertTrue(metrics.body().contains("\"processed\":50"));
        assertTrue(metrics.body().contains("\"failed\":0"));
    }

    @Test
    void shutdownEndpointStopsAcceptingNewOrders() throws Exception {
        assertEquals(200, post("/shutdown", "").statusCode());

        HttpResponse<String> rejected = post("/orders", "{\"orderId\":\"late\",\"premium\":false}");
        assertEquals(409, rejected.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }
}

