package com.example.capstone_backend.controller;

import com.example.capstone_backend.dto.*;
import com.example.capstone_backend.service.ManualBuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manual-build")
@RequiredArgsConstructor
public class ManualBuildController {

    private final ManualBuildService manualBuildService;

    @PostMapping("/parts")
    public ResponseEntity<ManualPartListResponse> getParts(
            @RequestBody ManualPartSearchRequest request
    ) {
        ManualPartListResponse response = manualBuildService.getParts(
                request.targetCategory(),
                request.selectedParts()
        );

        return ResponseEntity.ok(response); 
    }
}