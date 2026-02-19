package com.kreasipositif.configservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Global CORS configuration.
 * Allows Swagger UI (and other configured origins) to call the API directly from the browser.
 */
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Allow configured origins (defaults to wildcard for local dev)
        config.setAllowedOriginPatterns(allowedOrigins);

        // Standard HTTP methods used by REST + preflight
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));

        // Allow all headers (including Authorization, Content-Type, etc.)
        config.setAllowedHeaders(List.of("*"));

        // Expose common response headers to the browser
        config.setExposedHeaders(List.of("Content-Type", "X-Request-Id"));

        // Allow credentials (cookies / Authorization headers) when origin is not wildcard
        config.setAllowCredentials(false);

        // Cache preflight response for 30 minutes
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
