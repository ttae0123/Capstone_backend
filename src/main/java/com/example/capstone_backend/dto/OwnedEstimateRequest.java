package com.example.capstone_backend.dto;

/** budget is the maximum NEW cash spend. Existing parts are never charged again. */
public record OwnedEstimateRequest(
        Long budget,
        ManualBuildSelection ownedParts,
        String usage,
        String preference
) { }
