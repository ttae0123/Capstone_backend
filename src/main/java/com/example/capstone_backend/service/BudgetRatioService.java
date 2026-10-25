package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.RecommendationBudgetRatio;
import com.example.capstone_backend.repository.RecommendationBudgetRatioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BudgetRatioService {

    private static final Set<String> VALID_USAGES = Set.of("GAMING", "OFFICE", "VIDEO_EDITING");
    private final RecommendationBudgetRatioRepository repository;

    @Transactional(readOnly = true)
    public Allocation resolve(String usage, long requestedBudget) {
        if (requestedBudget <= 0) {
            throw new IllegalArgumentException("예산은 0원보다 커야 합니다.");
        }
        String normalized = normalizeUsage(usage);
        List<RecommendationBudgetRatio> rows = repository.findByUsageOrderByBudgetAsc(normalized);
        if (rows.isEmpty()) {
            throw new IllegalStateException("예산 비율 DB에 " + normalized + " 데이터가 없습니다. ratio CSV 적재를 확인하세요.");
        }

        RecommendationBudgetRatio selected = rows.get(0);
        long closestDistance = distance(selected.getBudget(), requestedBudget);
        for (RecommendationBudgetRatio row : rows) {
            long distance = distance(row.getBudget(), requestedBudget);
            if (distance < closestDistance) {
                selected = row;
                closestDistance = distance;
            }
        }
        return toAllocation(selected);
    }

    public static String normalizeUsage(String usage) {
        if (usage == null || usage.isBlank()) {
            return "GAMING";
        }
        String normalized = usage.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("WORK")) {
            normalized = "VIDEO_EDITING";
        }
        if (!VALID_USAGES.contains(normalized)) {
            throw new IllegalArgumentException("지원하지 않는 usage: " + usage);
        }
        return normalized;
    }

    private long distance(Long budget, long target) {
        if (budget == null || budget <= 0) {
            throw new IllegalStateException("예산 비율 테이블의 budget 값이 잘못되었습니다.");
        }
        return budget >= target ? budget - target : target - budget;
    }

    private Allocation toAllocation(RecommendationBudgetRatio row) {
        BigDecimal[] values = {
                row.getCpuRatio(), row.getGpuRatio(), row.getRamRatio(),
                row.getSsdRatio(), row.getMainboardRatio(), row.getPowerRatio(),
                row.getCaseRatio(), row.getCoolerRatio()
        };
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException("잘못된 예산 비율: " + row.getUsage() + "/" + row.getBudget());
            }
            sum = sum.add(value);
        }
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000002")) > 0) {
            throw new IllegalStateException("예산 비율 합계가 1이 아닙니다: " + row.getUsage() + "/" + row.getBudget());
        }
        return new Allocation(
                row.getBudget(), values[0].doubleValue(), values[1].doubleValue(),
                values[2].doubleValue(), values[3].doubleValue(),
                values[4].doubleValue(), values[5].doubleValue(),
                values[6].doubleValue(), values[7].doubleValue()
        );
    }

    public record Allocation(long referenceBudget, double cpu, double gpu, double ram,
                             double ssd, double mainboard, double power, double pcCase,
                             double cooler) {
    }
}
