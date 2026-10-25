package com.example.capstone_backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter @Setter
public class AiAnalysisRequest {
    private List<PartDTO> parts;

    @Getter @Setter
    public static class PartDTO {
        private String category;
        private String name;
    }
}