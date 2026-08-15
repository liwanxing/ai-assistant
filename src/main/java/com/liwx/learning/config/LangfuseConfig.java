package com.liwx.learning.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
public class LangfuseConfig {

    @Value("${spring.ai.langfuse.secret-key:}")
    private String secretKey;

    @Value("${spring.ai.langfuse.public-key:}")
    private String publicKey;

    @Value("${spring.ai.langfuse.url:}")
    private String baseUrl;

    @Bean("langfuseRestClient")
    public RestClient langfuseRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(h -> {
                    String auth = java.util.Base64.getEncoder()
                            .encodeToString((publicKey + ":" + secretKey).getBytes());
                    h.set(HttpHeaders.AUTHORIZATION, "Basic " + auth);
                    h.set(HttpHeaders.CONTENT_TYPE, "application/json");
                })
                .build();
    }
}
