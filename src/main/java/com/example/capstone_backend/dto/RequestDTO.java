package com.example.capstone_backend.dto;

import java.util.List;

public class RequestDTO {

    public record EstimateRequest(
            String usage,
            Long budget,
            List<String> brands
    ) {
    }
}