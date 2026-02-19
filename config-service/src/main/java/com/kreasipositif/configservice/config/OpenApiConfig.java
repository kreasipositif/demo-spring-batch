package com.kreasipositif.configservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configures the global SpringDoc OpenAPI metadata for Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8081}")
    private String serverPort;

    @Bean
    public OpenAPI configServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Config Service API")
                        .description("""
                                Provides configuration data used by the batch-processor to validate CSV transactions.
                                
                                **Exposed resources:**
                                - `/api/v1/config/bank-codes` — list and validate bank codes
                                - `/api/v1/config/transaction-limits` — list and validate transaction amount limits
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Kreasi Positif")
                                .url("https://github.com/kreasipositif/demo-spring-batch"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local development server")
                ));
    }
}
