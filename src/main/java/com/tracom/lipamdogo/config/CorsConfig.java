package com.tracom.lipamdogo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    private final List<String> allowedOriginPatterns;

    /**
     * The deployed architecture serves the frontend and the API from the same
     * origin, so the browser never performs a cross-origin request and these
     * rules only matter for the two-port local workflow or a deliberately
     * split deployment.
     *
     * <p>Patterns come from {@code TRACOM_CORS_ALLOWED_ORIGINS} (comma
     * separated). The production profile leaves the list empty; local
     * development defaults to localhost/127.0.0.1 on any port.</p>
     */
    public CorsConfig(@Value("${app.security.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOriginPatterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    /**
     * CORS source consumed by Spring Security ({@code http.cors()}), so
     * pre-flight requests pass through the filter chain correctly.
     *
     * <p>Credentials are disabled because the API uses stateless Bearer tokens,
     * not cookies. No origin is trusted implicitly: only the configured
     * patterns are echoed back.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
