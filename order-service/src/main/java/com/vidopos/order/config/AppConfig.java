package com.vidopos.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;
import org.slf4j.MDC;

@Configuration
@EnableConfigurationProperties({InventoryProperties.class, OrderReconciliationProperties.class})
public class AppConfig {

    @Bean
    public RestTemplate inventoryRestTemplate(RestTemplateBuilder builder, InventoryProperties properties) {
        ClientHttpRequestInterceptor correlationInterceptor = (request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
            }
            return execution.execute(request, body);
        };

        return builder
                .rootUri(properties.getBaseUrl())
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .additionalInterceptors(correlationInterceptor)
                .build();
    }
}
