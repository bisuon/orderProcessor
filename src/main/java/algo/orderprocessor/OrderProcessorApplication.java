package algo.orderprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Order Processor application.
 *
 * <p>It exposes a REST API (see {@code web} package), an Actuator health/metrics surface
 * and a small bundled web UI ({@code src/main/resources/static/index.html}) on top of the
 * concurrent {@link OrderProcessor} engine.
 */
@SpringBootApplication
public class OrderProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessorApplication.class, args);
    }
}

