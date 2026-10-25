package com.example.capstone_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiAnalysisResponse {
    private List<String> pros;
    private List<String> cons;
}