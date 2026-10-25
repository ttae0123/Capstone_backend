package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.RecommendationMinimumBudget;
import com.example.capstone_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MinimumBudgetService {
    private static final Logger log = LoggerFactory.getLogger(MinimumBudgetService.class);
    private static final List<String> USAGES = List.of("OFFICE", "GAMING", "VIDEO_EDITING");

    private final CpuRepository cpus;
    private final GpuRepository gpus;
    private final MainboardRepository mainboards;
    private final RamRepository rams;
    private final SsdRepository ssds;
    private final PowerRepository powers;
    private final CaseRepository cases;
    private final CoolerRepository coolers;
    private final RecommendationMinimumBudgetRepository minimumBudgets;
    private final TransactionTemplate transactionTemplate;

    public synchronized void refreshAll() {
        transactionTemplate.executeWithoutResult(status -> {
            MinimumBudgetCalculator.Parts data = new MinimumBudgetCalculator.Parts(
                    cpus.findAll(), gpus.findAll(), mainboards.findAll(), rams.findAll(),
                    ssds.findAll(), powers.findAll(), cases.findAll(), coolers.findAll());
            Map<String, Long> calculated = new LinkedHashMap<>();
            for (String usage : USAGES) {
                MinimumBudgetCalculator.Result result = MinimumBudgetCalculator.find(usage, data)
                        .orElseThrow(() -> new IllegalStateException(
                                usage + "의 최소 성능/호환성 조건을 만족하는 조합이 없습니다."));
                calculated.put(usage, result.amount());
            }
            LocalDateTime now = LocalDateTime.now();
            for (var entry : calculated.entrySet()) {
                minimumBudgets.save(new RecommendationMinimumBudget(entry.getKey(), entry.getValue(), now));
            }
            log.info("최소 예산 갱신 성공: {}", calculated);
        });
    }

    @Transactional(readOnly = true)
    public long minimumFor(String usage) {
        String normalized = BudgetRatioService.normalizeUsage(usage);
        RecommendationMinimumBudget saved = minimumBudgets.findById(normalized)
                .orElseThrow(() -> new IllegalStateException(
                        normalized + " 최소 예산이 DB에 없습니다. 초기 계산 데이터를 먼저 적재하세요."));
        return saved.getMinimumBudget();
    }

    @Transactional(readOnly = true)
    public List<RecommendationMinimumBudget> currentMinimums() {
        return USAGES.stream().map(u -> minimumBudgets.findById(u)
                .orElseThrow(() -> new IllegalStateException(u + "의 최소 예산 값이 없습니다."))).toList();
    }
}
