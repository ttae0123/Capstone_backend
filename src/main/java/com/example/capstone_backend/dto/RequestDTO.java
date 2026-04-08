package com.example.capstone_backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RequestDTO {
    public record EstimateRequest(
            String usage,      // 용도 (예: "GAMING", "OFFICE", "VIDEO_EDITING")
            Long budget,       // 예산 (예: 1500000)
            String preference  // 추가 선택 사항 (예: "INTEL_PREFER", "NVIDIA_ONLY" 등 - 선택사항)
    ) {}
}
