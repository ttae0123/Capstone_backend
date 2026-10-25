package com.example.capstone_backend.dto;

import java.util.List;

public record ManualPartListResponse(
        String category,
        int totalCount,
        List<ManualPartResponse> parts
) {
}