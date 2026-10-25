package com.example.capstone_backend.dto;

public record ManualBuildSelection(
        Long cpuId,
        Long mainboardId,
        Long ramId,
        Long gpuId,
        Long ssdId,
        Long powerId,
        Long caseId,
        Long coolerId
) {
}
