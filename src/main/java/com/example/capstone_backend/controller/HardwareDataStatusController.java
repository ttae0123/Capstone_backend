package com.example.capstone_backend.controller;

import java.util.Collections;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부품 크롤링 및 예산 비율 갱신이 함께 수행되는 현재 파이프라인 기준의 갱신 시각입니다.
 * 정확한 데이터 수집 시각은 파이프라인의 갱신 정책에 종속됩니다.
 */
@RestController
@RequestMapping("/api/hardware-data")
public class HardwareDataStatusController {

    private final JdbcTemplate jdbcTemplate;

    public HardwareDataStatusController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> status() {
        try {
            String updatedAt = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(MAX(updated_at), '%Y-%m-%d %H:%i:%s') " +
                "FROM recommendation_budget_ratios",
                String.class
            );
            return ResponseEntity.ok(Collections.singletonMap("dataUpdatedAt", updatedAt));
        } catch (DataAccessException ex) {
            return ResponseEntity.ok(Collections.singletonMap("dataUpdatedAt", null));
        }
    }
}
