package com.example.capstone_backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

@Configuration
public class AppConfig {

    // Gemini 호출용 HTTP 연결/읽기 시간 제한. 4개 평가 카드 재요청에도 각 호출 단위로 적용됩니다.
    @Bean
    public RestTemplate restTemplate(
            @Value("${capstone.gemini.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${capstone.gemini.read-timeout-ms:25000}") long readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Gemini HTTP timeout은 0보다 커야 합니다.");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new RestTemplate(factory);
    }

    // 2. ObjectMapper 등록 (에러 해결을 위해 추가)
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}