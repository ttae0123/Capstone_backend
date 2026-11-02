package com.example.capstone_backend.service;

/** Gemini HTTP 호출 시간 초과를 일반 내부 오류와 구분하기 위한 예외. */
public class ExternalAiTimeoutException extends RuntimeException {
    public ExternalAiTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
