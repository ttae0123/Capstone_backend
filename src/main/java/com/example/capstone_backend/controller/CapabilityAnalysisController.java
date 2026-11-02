package com.example.capstone_backend.controller;

import com.example.capstone_backend.service.CapabilityAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
public class CapabilityAnalysisController {
    private final CapabilityAnalysisService service;

    /** 기존 /api/estimates/analyze 와 독립된 4개 카드 전용 API. */
    @PostMapping("/capabilities")
    public CapabilityAnalysisService.CapabilityReport analyze(
            @RequestBody CapabilityAnalysisService.CapabilityRequest request) {
        return service.analyze(request);
    }
}
