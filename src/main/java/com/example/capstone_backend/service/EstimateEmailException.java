package com.example.capstone_backend.service;

import org.springframework.http.HttpStatus;

/** Safe, user-facing error for mail/PDF endpoints. Never include SMTP credentials. */
public class EstimateEmailException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public EstimateEmailException(HttpStatus status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
