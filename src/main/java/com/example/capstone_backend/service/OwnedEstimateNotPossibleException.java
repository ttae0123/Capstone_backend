package com.example.capstone_backend.service;

/** The request is valid, but no full, compatible build fits the additional budget. */
public class OwnedEstimateNotPossibleException extends RuntimeException {
    public OwnedEstimateNotPossibleException(String message) { super(message); }
}
