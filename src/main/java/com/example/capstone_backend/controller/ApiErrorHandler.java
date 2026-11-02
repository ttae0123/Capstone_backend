package com.example.capstone_backend.controller;

import org.springframework.http.ResponseEntity;
import com.example.capstone_backend.service.EstimateEmailException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.HttpStatus;
import com.example.capstone_backend.service.ExternalAiTimeoutException;
import com.example.capstone_backend.service.ExternalAiUnavailableException;
import com.example.capstone_backend.service.OwnedEstimateNotPossibleException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(EstimateEmailException.class)
    public ResponseEntity<Map<String, String>> emailError(EstimateEmailException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "error", ex.getCode(), "code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validationError(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_REQUEST", "message", "이메일 주소나 견적서 항목을 확인해 주세요."));
    }

    @ExceptionHandler(ExternalAiTimeoutException.class)
    public ResponseEntity<Map<String, String>> aiTimeout(ExternalAiTimeoutException error) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
                "error", "AI_TIMEOUT", "code", "AI_TIMEOUT", "message", error.getMessage()));
    }

    @ExceptionHandler(ExternalAiUnavailableException.class)
    public ResponseEntity<Map<String, String>> aiUnavailable(ExternalAiUnavailableException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "AI_UNAVAILABLE", "code", "AI_UNAVAILABLE", "message", error.getMessage()));
    }

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
