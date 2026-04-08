package com.example.capstone_backend.controller;

import com.example.capstone_backend.dto.EstimateResponse;
import com.example.capstone_backend.dto.RequestDTO;
import com.example.capstone_backend.service.EstimateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor // Lombok: 생성자 주입
public class EstimateController {

    private final EstimateService estimateService;

    @PostMapping("/recommend")
    public ResponseEntity<EstimateResponse.ResultList> getRecommendations(
            @RequestBody RequestDTO.EstimateRequest request) {

        // 서비스 호출하여 결과물 생성
        EstimateResponse.ResultList response = estimateService.generateRecommendations(request);

        return ResponseEntity.ok(response);
    }
}