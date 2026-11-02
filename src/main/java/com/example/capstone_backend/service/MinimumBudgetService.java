package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.RecommendationMinimumBudget;
import com.example.capstone_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 용도별 예산 커트라인은 고정값으로 운영한다.
 * 게임별 금액은 2026-10-30 DB를 사용해 산정한 '상위 옵션 목표' 가이드이며,
 * 실제 플레이 FPS를 보장하는 값은 아니다.
 *
 * refreshAll()은 명시적으로 실행할 때만 DB 값을 갱신한다.
 * 기존 최소 호환 견적 계산식은 MinimumBudgetCalculator에 그대로 두고
 * calculateOriginalMinimums()를 통해 조회할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class MinimumBudgetService {
    private static final Logger log = LoggerFactory.getLogger(MinimumBudgetService.class);

    // 실제 자동견적 요청에서 사용하는 3가지 용도만 포함한다.
    private static final List<String> USAGES = List.of("OFFICE", "GAMING", "HIGH_END");
    private static final List<String> GAMES = List.of(
            "LOL",
            "PUBG",
            "CYBERPUNK",
            "CRIMSON_DESERT"
    );

    private static final Map<String, Long> FIXED_MINIMUMS = Map.of(
            "OFFICE", 500_000L,
            "GAMING", 1_000_000L,
            "HIGH_END", 4_500_000L
    );

    // 이전에 산정한 상위 옵션 기준 최소 견적 합계 (2026-10-30 DB 기준).
    private static final Map<String, Long> GAME_MINIMUMS = Map.of(
            "LOL", 750_000L,               // 롤: FHD 높음 / 144FPS
            "PUBG", 1_400_000L,            // 배틀그라운드: FHD 높은 옵션
            "CYBERPUNK", 2_300_000L,       // 사이버펑크: 4K 울트라 / RT OFF
            "CRIMSON_DESERT", 3_000_000L  // 붉은사막: QHD 높음 / 60FPS 이상 목표
    );

    // 기존 계산식을 수동으로 확인할 수 있도록 부품 Repository는 유지한다.
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

    // 0: 하이엔드 상한 미설정. 필요한 경우 환경 설정에서 별도로 지정한다.
    @Value("${capstone.high-end.max-budget:0}")
    private long configuredHighEndMaximum;

    /**
     * 고정 예산 3개 + 게임별 가이드 3개를 DB에 저장한다.
     * 기존 계산 결과에 따라 고정 커트라인이 덮어써지지 않도록 한다.
     */
    public synchronized void refreshAll() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();

            for (String usage : USAGES) {
                minimumBudgets.save(new RecommendationMinimumBudget(
                        usage, FIXED_MINIMUMS.get(usage), now));
            }
            for (String game : GAMES) {
                minimumBudgets.save(new RecommendationMinimumBudget(
                        game, GAME_MINIMUMS.get(game), now));
            }

            // 이전 용도 명칭이 DB에 남아 있으면 정리한다.
            if (minimumBudgets.existsById("VIDEO_EDITING")) {
                minimumBudgets.deleteById("VIDEO_EDITING");
            }

            log.info("고정 용도별 최소 예산 저장: {}", FIXED_MINIMUMS);
            log.info("게임별 상위 옵션 가이드 저장: {}", GAME_MINIMUMS);
        });
    }

    /**
     * 이전의 최소 견적 계산식을 그대로 실행해 결과를 확인하는 용도.
     * 이 메서드는 recommendation_minimum_budgets에 저장된 고정값을 바꾸지 않는다.
     */
    @Transactional(readOnly = true)
    public Map<String, MinimumBudgetCalculator.Result> calculateOriginalMinimums() {
        MinimumBudgetCalculator.Parts data = new MinimumBudgetCalculator.Parts(
                cpus.findAll(), gpus.findAll(), mainboards.findAll(), rams.findAll(),
                ssds.findAll(), powers.findAll(), cases.findAll(), coolers.findAll());

        Map<String, MinimumBudgetCalculator.Result> calculated = new LinkedHashMap<>();
        for (String usage : USAGES) {
            MinimumBudgetCalculator.Result result = MinimumBudgetCalculator.find(usage, data)
                    .orElseThrow(() -> new IllegalStateException(
                            usage + "의 성능/호환성 조건을 만족하는 최소 조합이 없습니다."));
            calculated.put(usage, result);
            log.info("[계산 참고값] {}={}원 / cpu={}, gpu={}, mainboard={}, ram={}, ssd={}, power={}, case={}, cooler={}",
                    usage, result.amount(), result.cpuId(), result.gpuId(), result.mainboardId(),
                    result.ramId(), result.ssdId(), result.powerId(), result.caseId(), result.coolerId());
        }
        return Collections.unmodifiableMap(calculated);
    }

    /** 기존 용도별 최소 예산 조회: OFFICE / GAMING / HIGH_END */
    @Transactional(readOnly = true)
    public long minimumFor(String usage) {
        String normalized = BudgetRatioService.normalizeUsage(usage);
        return minimumBudgets.findById(normalized)
                .orElseThrow(() -> new IllegalStateException(
                        normalized + " 최소 예산이 DB에 없습니다. 초기화를 먼저 실행하세요."))
                .getMinimumBudget();
    }

    /** 기존 API와 호환: 용도별 3개만 반환하며 게임별 값은 포함하지 않는다. */
    @Transactional(readOnly = true)
    public List<RecommendationMinimumBudget> currentMinimums() {
        return USAGES.stream().map(u -> minimumBudgets.findById(u)
                .orElseThrow(() -> new IllegalStateException(u + " 최소 예산이 없습니다.")))
                .toList();
    }

    /** 새 게임별 최소 예산을 별도로 조회한다. */
    @Transactional(readOnly = true)
    public long gameMinimumFor(String game) {
        String key = normalizeGame(game);
        return minimumBudgets.findById(key)
                .orElseThrow(() -> new IllegalStateException(key + " 게임 가이드 예산이 DB에 없습니다."))
                .getMinimumBudget();
    }

    /** 추후 프론트 가이드에 활용할 게임별 3개 값. */
    @Transactional(readOnly = true)
    public List<RecommendationMinimumBudget> currentGameMinimums() {
        return GAMES.stream().map(g -> minimumBudgets.findById(g)
                .orElseThrow(() -> new IllegalStateException(g + " 게임 가이드 예산이 없습니다.")))
                .toList();
    }

    private static String normalizeGame(String game) {
        if (game == null || game.isBlank()) {
            throw new IllegalArgumentException("게임 이름을 입력하세요.");
        }

        return switch (game.trim().toUpperCase(Locale.ROOT)) {
            case "LOL", "LEAGUE_OF_LEGENDS", "롤" -> "LOL";
            case "PUBG", "배틀그라운드", "배그" -> "PUBG";
            case "CYBERPUNK", "CYBERPUNK_2077",
                 "사이버펑크", "사이버펑크 2077" -> "CYBERPUNK";
            case "CRIMSON_DESERT", "CRIMSON DESERT",
                 "붉은사막", "붉은 사막" -> "CRIMSON_DESERT";
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 게임: " + game);
        };
    }

    public record BudgetRange(String usage, long minBudget, Long maxBudget) { }

    @Transactional(readOnly = true)
    public List<BudgetRange> currentRanges() {
        long office = minimumFor("OFFICE");
        long gaming = minimumFor("GAMING");
        long highEnd = minimumFor("HIGH_END");
        if (!(0 < office && office < gaming && gaming < highEnd)) {
            throw new IllegalStateException("저장된 최소 예산의 순서가 올바르지 않습니다.");
        }
        if (configuredHighEndMaximum > 0 && configuredHighEndMaximum < highEnd) {
            throw new IllegalStateException("설정된 하이엔드 최대 예산이 최소 예산보다 작습니다.");
        }
        Long maximum = configuredHighEndMaximum > 0 ? configuredHighEndMaximum : null;
        return List.of(
                new BudgetRange("OFFICE", office, gaming - 1),
                new BudgetRange("GAMING", gaming, highEnd - 1),
                new BudgetRange("HIGH_END", highEnd, maximum));
    }

    /** 게임별 가이드가 아닌 3가지 용도의 예산 범위만 검증한다. */
    @Transactional(readOnly = true)
    public void validateBudget(String usage, long requestedBudget) {
        String normalized = BudgetRatioService.normalizeUsage(usage);
        BudgetRange range = currentRanges().stream()
                .filter(row -> row.usage().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 용도: " + normalized));
        if (requestedBudget < range.minBudget()
                || (range.maxBudget() != null && requestedBudget > range.maxBudget())) {
            throw new IllegalArgumentException(normalized + "의 허용 예산: "
                    + range.minBudget() + "원 ~ "
                    + (range.maxBudget() == null ? "상한 미설정" : range.maxBudget() + "원"));
        }
    }
}
