package algo.orderprocessor.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration for auto-generated API documentation.
 * Visit http://localhost:8080/swagger-ui.html after startup.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Order Processor API")
                .version("1.0.0")
                .description("High-throughput concurrent order processor with priority-first scheduling, REST API, and web UI.")
                .contact(new Contact()
                    .name("Gang Zhu")
                    .url("https://github.com/bisuon/orderProcessor")));
    }
}

