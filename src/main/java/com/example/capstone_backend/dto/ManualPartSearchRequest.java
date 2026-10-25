package com.example.capstone_backend.dto;

public record ManualPartSearchRequest(
        String targetCategory,
        ManualBuildSelection selectedParts
) {
}