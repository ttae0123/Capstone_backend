package com.example.capstone_backend.dto;

/** Gemini는 조건만 추출하고, 최종 용도/예산은 NaturalLanguageEstimateService가 결정한다. */
public class ParsedRequest {
    private Long budget;
    private String usage;
    private String gameName;

    public ParsedRequest() {}
    public Long getBudget() { return budget; }
    public void setBudget(Long budget) { this.budget = budget; }
    public String getUsage() { return usage; }
    public void setUsage(String usage) { this.usage = usage; }
    public String getGameName() { return gameName; }
    public void setGameName(String gameName) { this.gameName = gameName; }
}
