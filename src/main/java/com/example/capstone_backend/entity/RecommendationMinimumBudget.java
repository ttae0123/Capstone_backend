package com.example.capstone_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommendation_minimum_budgets")
public class RecommendationMinimumBudget {
    @Id
    @Column(name = "usage_type", nullable = false, length = 30)
    private String usage;

    @Column(name = "minimum_budget", nullable = false)
    private Long minimumBudget;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RecommendationMinimumBudget() { }

    public RecommendationMinimumBudget(String usage, Long minimumBudget, LocalDateTime updatedAt) {
        this.usage = usage;
        this.minimumBudget = minimumBudget;
        this.updatedAt = updatedAt;
    }
    public String getUsage() { return usage; }
    public Long getMinimumBudget() { return minimumBudget; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
