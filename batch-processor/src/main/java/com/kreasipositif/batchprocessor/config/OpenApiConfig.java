package com.kreasipositif.batchprocessor.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI batchProcessorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Batch Processor API")
                        .description("Spring Batch job management API — trigger CSV processing jobs "
                                + "and monitor their execution status.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("KreasiPositif")
                                .email("dev@kreasipositif.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8083").description("Local")));
    }
}
