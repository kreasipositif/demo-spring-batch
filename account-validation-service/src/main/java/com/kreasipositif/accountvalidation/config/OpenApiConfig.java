package com.kreasipositif.accountvalidation.config;

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

    @Value("${server.port:8082}")
    private String serverPort;

    @Bean
    public OpenAPI accountValidationOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Account Validation Service API")
                        .description("""
                                Mock downstream service that simulates a bank core system.
                                
                                **Features:**
                                - Validates whether a source or beneficiary account number exists and is active.
                                - Each request incurs a configurable simulated latency (default **500 ms**) to mimic real bank API round-trip times.
                                - Supports single and bulk account validation.
                                
                                **Account statuses:**
                                - `ACTIVE` — account exists and is eligible for transactions
                                - `INACTIVE` — account exists but is suspended
                                - `BLOCKED` — account exists but is permanently blocked
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
