package com.skillforge.gateway;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** 网关在路由选择之前处理真实预检，只允许明确配置的产品页面来源。 */
@Configuration
public class GatewayCorsConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public CorsWebFilter gatewayCorsFilter(
            @Value("${skillforge.web.allowed-origins:http://localhost:3000,http://localhost:3100,http://localhost:3200}") String origins,
            @Value("${skillforge.code-execution.preview-origin:}") String previewOrigin) {
        var allowed = Arrays.stream(origins.split(",", -1)).map(String::trim).toList();
        if (allowed.stream().anyMatch(origin -> origin.isBlank() || origin.contains("*")
                || origin.equals("null") || origin.equals(previewOrigin))) {
            throw new IllegalArgumentException("gateway_cors_requires_explicit_product_origins");
        }
        var policy = new CorsConfiguration();
        policy.setAllowedOrigins(allowed);
        policy.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        policy.setAllowedHeaders(List.of("*"));
        policy.setExposedHeaders(List.of("X-Trace-Id", "ETag", "Retry-After", "Content-Disposition", "X-Resource-Revision"));
        policy.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", policy);
        return new CorsWebFilter(source);
    }
}
