package com.example.capstone_backend.service;

/** Gemini 서버 오류 및 접속 실패를 안전한 메시지로 반환하기 위한 예외. */
public class ExternalAiUnavailableException extends RuntimeException {
    public ExternalAiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
