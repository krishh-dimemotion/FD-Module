package com.banklab.fdservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ProductPricingClientConfig {

    @Bean
    RestClient productPricingRestClient(@Value("${fd.clients.product-pricing.base-url}") String baseUrl) {
        // The default JDK HttpClient factory cannot initialise its loopback selector
        // in some Windows/JDK 25 environments. URLConnection is sufficient for this
        // synchronous client and keeps the application runnable on Java 21 and 25.
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }
}
