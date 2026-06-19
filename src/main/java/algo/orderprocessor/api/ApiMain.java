package algo.orderprocessor.api;

import algo.orderprocessor.Order;

import java.io.IOException;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entry point that boots the REST API. Configurable via environment variables / system
 * properties:
 * <ul>
 *     <li>{@code PORT} (default 8080)</li>
 *     <li>{@code WORKERS} (default = available processors)</li>
 * </ul>
 */
public final class ApiMain {

    private ApiMain() {
    }

    public static void main(String[] args) throws IOException {
        int port = intConfig("PORT", 8080);
        int workers = intConfig("WORKERS", Math.max(2, Runtime.getRuntime().availableProcessors()));

        LongAdder premium = new LongAdder();
        LongAdder standard = new LongAdder();

        OrderApiServer server = OrderApiServer.create(port, workers, (Order order) -> {
            if (order.isPremium()) {
                premium.increment();
            } else {
                standard.increment();
            }
        }).start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "api-shutdown-hook"));

        System.out.printf("Order Processor REST API listening on http://localhost:%d (workers=%d)%n",
            server.port(), workers);
        System.out.println("Try:");
        System.out.printf("  curl -s localhost:%d/health%n", server.port());
        System.out.printf("  curl -s -XPOST localhost:%d/orders -d '{\"orderId\":\"o1\",\"premium\":true}'%n", server.port());
        System.out.printf("  curl -s localhost:%d/metrics%n", server.port());
        System.out.printf("  curl -s -XPOST localhost:%d/shutdown%n", server.port());
    }

    private static int intConfig(String key, int fallback) {
        String value = System.getProperty(key, System.getenv(key));
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

