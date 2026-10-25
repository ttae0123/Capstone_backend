package com.example.capstone_backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    // 1. RestTemplate 등록 (기존 코드)
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // 2. ObjectMapper 등록 (에러 해결을 위해 추가)
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}