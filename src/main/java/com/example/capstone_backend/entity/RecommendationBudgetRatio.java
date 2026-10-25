package com.example.capstone_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommendation_budget_ratios",
       uniqueConstraints = @UniqueConstraint(name = "uk_budget_ratio_usage_budget", columnNames = {"usage", "budget"}))
public class RecommendationBudgetRatio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String usage;

    @Column(nullable = false)
    private Long budget;

    @Column(name = "cpu_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal cpuRatio;

    @Column(name = "gpu_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal gpuRatio;

    @Column(name = "mainboard_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal mainboardRatio;

    @Column(name = "ram_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal ramRatio;

    @Column(name = "ssd_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal ssdRatio;

    @Column(name = "power_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal powerRatio;

    @Column(name = "case_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal caseRatio;

    @Column(name = "cooler_ratio", nullable = false, precision = 8, scale = 6)
    private BigDecimal coolerRatio;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public String getUsage() { return usage; }
    public Long getBudget() { return budget; }
    public BigDecimal getCpuRatio() { return cpuRatio; }
    public BigDecimal getGpuRatio() { return gpuRatio; }
    public BigDecimal getMainboardRatio() { return mainboardRatio; }
    public BigDecimal getRamRatio() { return ramRatio; }
    public BigDecimal getSsdRatio() { return ssdRatio; }
    public BigDecimal getPowerRatio() { return powerRatio; }
    public BigDecimal getCaseRatio() { return caseRatio; }
    public BigDecimal getCoolerRatio() { return coolerRatio; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
