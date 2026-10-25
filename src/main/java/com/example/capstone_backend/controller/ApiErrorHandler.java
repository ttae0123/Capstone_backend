package com.example.capstone_backend.controller;

import org.springframework.http.ResponseEntity;
import com.example.capstone_backend.service.OwnedEstimateNotPossibleException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(OwnedEstimateNotPossibleException.class)
    public ResponseEntity<Map<String, String>> noOwnedBuild(OwnedEstimateNotPossibleException error) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "NO_COMPATIBLE_BUILD", "code", "NO_COMPATIBLE_BUILD", "message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_REQUEST",
                "message", error.getMessage() == null ? "잘못된 요청입니다." : error.getMessage()));
    }
}
