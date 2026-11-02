package com.example.capstone_backend.dto;

/** 공식 벤치마크가 아닌 Gemini 추정 게임 등급. */
public class GameClassification {
    private String referenceGame;
    private Double confidence;
    private String reason;

    public GameClassification() {}
    public String getReferenceGame() { return referenceGame; }
    public void setReferenceGame(String referenceGame) { this.referenceGame = referenceGame; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
